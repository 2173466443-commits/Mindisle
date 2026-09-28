import { defineStore } from 'pinia'
import {
  PM_FETCH_MAX,
  PM_PAGE_SIZE,
  listBlocks,
  listConversations,
  listThread,
  newClientMsgId,
  pmOnline,
  pmUnread,
  reportMessage,
  reportRead,
  sendPrivateMessage,
  unblockPeer,
  blockPeer as apiBlockPeer
} from '@/api/pm'
import { WS_APP, WS_DEST, onWs, wsIsUsable, wsPublish } from '@/composables/useWs'
import { useNotifyStore } from '@/stores/notify'
import { ElMessage } from 'element-plus'

// 私信的前端状态层（任务 T5.2–T5.8 · 需求 FR6 · 手册 §6.2 U9/U10、§8.2 五条可用性判据）。
//
// 【这一层存在的唯一理由：同一件事有两个入口】
// 一条新私信可能来自 STOMP 帧（/user/queue/private），也可能来自 REST 的分页返回，
// 还可能来自「WS 没连上时 send 的同步返回值」。三者都必须变成同一份界面状态，
// 所以「收到一条消息」这件事只在这里被处理一次，视图只负责画。
//
// 【peerId 一律当字符串用】
// 后端 PmUnreadView 里是 Map<Long,Integer>，Jackson 出 JSON 时键只能是字符串（{"233":2}）。
// 如果这里按数字建索引，threads[233] 与 byPeer['233'] 就是两份互不相干的状态，
// 症状是「红点说有 2 条未读，会话列表那一行却没有角标」——两个数各自都对，撞在一起才错。
// 因此所有 Map 的键都过 String()，取数也过 String()。
//
// 【pending 气泡与幂等键】
// 发出去但还没被服务端确认的消息带 clientMsgId 而没有 id。幂等键由前端生成（api/pm.js 里写明理由：
// 后端对空值补 srv-<uuid>，那是「不指望你去重」的语义），ack 回来后按这个键把占位气泡换成服务端那一行。

/** 一个会话桶的初始形状。字段名与后端 PmThreadPage 对齐，多出来的三个（loaded/loading/error）是本地态。 */
function emptyThread(peerId) {
  return {
    peerId: String(peerId),
    peerName: '',
    peerAvatar: '',
    list: [],
    nextCursor: null,
    hasMore: false,
    loading: false,
    loaded: false,
    error: '',
    blocked: false,
    peerOnline: false,
    peerLastLoginAt: null,
    // 这一页里最小的 id：断线补拉只问「比它更旧的」，服务端不需要记客户端拉到哪了（FR6.3）。
    minId: null
  }
}

/** 把后端回来的消息并进本地列表，按 id 去重（id 为 null 的 pending 不参与去重）。 */
function merge(list, incoming, prepend) {
  const seen = {}
  for (const m of list) if (m && m.id != null) seen[String(m.id)] = true
  const fresh = []
  for (const m of incoming) {
    if (!m) continue
    if (m.id != null && seen[String(m.id)]) continue
    if (m.id != null) seen[String(m.id)] = true
    fresh.push(m)
  }
  if (!fresh.length) return list
  return prepend ? fresh.concat(list) : list.concat(fresh)
}

function minIdOf(list) {
  let min = null
  for (const m of list) {
    if (!m || m.id == null) continue
    const v = Number(m.id)
    if (min === null || v < min) min = v
  }
  return min
}

export const usePmStore = defineStore('pm', {
  state: () => ({
    conversations: [],
    convNextCursor: null,
    convHasMore: false,
    convLoading: false,
    convLoaded: false,
    convError: '',
    // 私信未读（PmUnreadView.total）。它与「通知未读」是两个不同的读数：前者只算私信，
    // 后者是通知表里所有类型加起来的红点。顶栏两颗角标各用各的，谁也不能替谁归零。
    unreadTotal: 0,
    byPeerUnread: {},
    threads: {},
    // 当前正被看着的那个会话（ChatDetailView 进出时设置）。已读上报只对它发，
    // 否则「列表页扫一眼」就会把别人的一整页私信标成已读，而那是用户看不到的内容。
    activePeer: '',
    online: {},
    presenceTotal: 0,
    blocks: [],
    blocksError: '',
    blocksLoaded: false,
    // 降级说明：WS 没通时界面上要说得出「现在是轮询而不是实时」，不能让用户以为对方没回。
    transport: 'idle',
    lastSyncAt: 0
  }),

  getters: {
    threadOf: (s) => (peerId) => s.threads[String(peerId)] || null,
    unreadOf: (s) => (peerId) => Number(s.byPeerUnread[String(peerId)]) || 0,
    isOnline: (s) => (peerId) => !!s.online[String(peerId)],
    /** 会话列表 + 未读的一起显示：列表还没点开过时，角标也要有数（顶栏那颗就靠它）。 */
    conversationOf: (s) => (peerId) => {
      const key = String(peerId)
      for (const it of s.conversations) if (String(it.peerId) === key) return it
      return null
    }
  },

  actions: {
    // ============================================================ 会话列表（U9）

    async loadConversations(reset = true) {
      if (this.convLoading) return
      if (!reset && (!this.convHasMore || this.convNextCursor === null)) return
      this.convLoading = true
      this.convError = ''
      try {
        const page = await listConversations({
          size: PM_PAGE_SIZE,
          beforeId: reset ? undefined : this.convNextCursor
        })
        const list = Array.isArray(page && page.list) ? page.list : []
        this.conversations = reset ? list : this.conversations.concat(list)
        this.convNextCursor = page && page.nextCursor != null ? page.nextCursor : null
        this.convHasMore = !!(page && page.hasMore)
        this.convLoaded = true
        // unreadTotal 每页都带（与通知列表同一设计）：角标只有一个数据来源，不要再发一次 unread 请求。
        if (page && typeof page.unreadTotal === 'number') this.unreadTotal = page.unreadTotal
        for (const it of list) this.online[String(it.peerId)] = !!it.online
        this.lastSyncAt = Date.now()
      } catch (e) {
        this.convError = (e && e.message) || '会话列表没读到'
        if (reset) {
          this.conversations = []
          this.convNextCursor = null
          this.convHasMore = false
        }
        throw e
      } finally {
        this.convLoading = false
      }
    },

    loadMoreConversations() {
      return this.loadConversations(false)
    },

    // ============================================================ 会话详情（U10）

    /** 取某人的历史。reset=true 是第一页（进页面 / 轮询刷新），false 是往更早翻（nextCursor）。 */
    async fetchThread(peerId, reset = true) {
      const key = String(peerId)
      if (!this.threads[key]) this.threads[key] = emptyThread(key)
      const t = this.threads[key]
      if (t.loading) return t
      if (!reset && (!t.hasMore || t.nextCursor === null)) return t
      t.loading = true
      t.error = ''
      try {
        const page = await listThread(key, {
          size: PM_PAGE_SIZE,
          beforeId: reset ? undefined : t.nextCursor
        })
        return this.applyThread(key, page, reset)
      } catch (e) {
        t.error = (e && e.message) || '这个会话没读到'
        if (reset) {
          t.list = []
          t.nextCursor = null
          t.hasMore = false
        }
        throw e
      } finally {
        t.loading = false
      }
    },

    /**
     * 把 PmThreadPage 落进会话桶。返回有没有新行（轮询靠这个决定要不要滚到底）。
     *
     * 第一页是「最新的一页」，所以 reset 时把已有 pending 气泡留在末尾（它们还没被服务端确认，
     * 一清掉就等于把用户刚打出去的话抹了）；往更早翻则必须 prepend，否则时间顺序会倒。
     */
    applyThread(peerId, page, reset) {
      const key = String(peerId)
      if (!this.threads[key]) this.threads[key] = emptyThread(key)
      const t = this.threads[key]
      if (!page) return false
      const list = Array.isArray(page.list) ? page.list : []
      t.peerName = page.peerName || t.peerName
      t.peerAvatar = page.peerAvatar || t.peerAvatar
      t.peerOnline = !!page.peerOnline
      t.peerLastLoginAt = page.peerLastLoginAt || null
      t.blocked = !!page.blocked
      t.nextCursor = page.nextCursor != null ? page.nextCursor : null
      t.hasMore = !!page.hasMore
      // pending（还没拿到服务端 id 的占位气泡）无论哪种取法都留在末尾：那是用户刚打出去的话，
      // 刷新和翻页都不该把它抹掉，更不该插到中间去。
      const pending = t.list.filter(function (m) { return m && m.id == null })
      const known = t.list.filter(function (m) { return m && m.id != null })
      // reset 取的是「最新的一页」⇒ 旧的已知行整体作废，新行往后接；
      // 翻页取的是「更旧的一页」⇒ 必须往前接。两者共用一个 prepend 布尔，写反的 symptoms 是
      // 「往上翻一页，最新的那条跑到最上面去了」，而且因为按 id 去重，条数断言还会全绿。
      let next = merge(reset ? [] : known, list, !reset)
      next = merge(next, pending, false)
      // 判据用「合并前后的行数」而不是「这一页有没有内容」：重取一页全是重复行时不该滚动。
      const grew = next.length !== t.list.length
      t.list = next
      t.minId = minIdOf(next)
      t.loaded = true
      this.online[key] = !!page.peerOnline
      this.lastSyncAt = Date.now()
      return grew
    },

    loadMoreThread(peerId) {
      return this.fetchThread(peerId, false)
    },

    /**
     * 重连后的补拉（FR6.3「补齐而不是重放」）。
     *
     * 判据：只有本地已经有这一会话时才补，且一次最多 fetch-max（100）条；
     * 如果补出来的量真的顶到上限，说明账已经对不上，那时候该整页重取（手册 §8.2 第 4 条）。
     */
    async refillThread(peerId) {
      const key = String(peerId)
      const t = this.threads[key]
      if (!t || !t.loaded) return
      // 刻意不带 beforeId：后端只有「按游标取更旧」这一种取法，
      // 拿本地 minId 当游标问回来的是一页更旧的历史，断线期间真正错过的（id 更大的那些）一条都没有，
      // 症状是「重连之后新消息还是不出现，而且不报错」。取最新一页（fetch-max 上限）再按 id 去重即可。
      const page = await listThread(key, { size: PM_FETCH_MAX })
      if (!page) return
      const incoming = Array.isArray(page.list) ? page.list : []
      if (incoming.length >= PM_FETCH_MAX) {
        t.loaded = false
        await this.fetchThread(key, true)
        return
      }
      // 断线期间错过的都是「比 minId 更新」的，而后端只有倒序取旧页的能力，
      // 所以补拉取的是最新一页（不带 beforeId），再按 id 去重合并。
      const fresh = incoming.filter(function (m) {
        return m && m.id != null && (t.minId === null || Number(m.id) > t.minId)
      })
      if (fresh.length) t.list = merge(t.list, fresh, false)
      t.minId = minIdOf(t.list)
      this.applyPeerMeta(key, page)
    },

    applyPeerMeta(key, page) {
      const t = this.threads[key]
      if (!t || !page) return
      t.peerName = page.peerName || t.peerName
      t.peerAvatar = page.peerAvatar || t.peerAvatar
      t.peerOnline = !!page.peerOnline
      t.blocked = !!page.blocked
      this.online[key] = !!page.peerOnline
    },

    setActivePeer(peerId) {
      this.activePeer = peerId === null || peerId === undefined || peerId === '' ? '' : String(peerId)
    },

    // ============================================================ 发（T5.2 · FR6.1/6.2）

    /** 发一条文字私信（FR6.3）。 */
    sendText(peerId, content) {
      return this.send(peerId, content, 'text')
    },

    /**
     * 发一条图片私信（FR6.3「图片复用 FR4.1 上传链路」）。
     *
     * 入参必须是 api/file.js#uploadImage 返回的那条站内相对地址：后端 normalizeMsgType
     * 对 image 硬要求 content 以 /uploads/ 开头，外链直接 10001。
     * 那边给出的理由是「私信里放外链等于把接收方带出站」——私信是唯一允许进人审的私密内容类型，
     * 一次点击就可能把对方的 IP/UA 交给第三方，所以这条闸不能由前端放宽。
     */
    sendImage(peerId, url) {
      return this.send(peerId, url, 'image')
    },

    /**
     * 发送的公共路径。先画 pending 气泡，再走 WS；WS 不通直接走 REST。
     *
     * 两条路共用同一个 clientMsgId，所以「WS 发出去了一半、连接断了、又用 REST 补一次」
     * 这种最坏情况在服务端仍然只落一行（幂等键命中就返回已有那行）。
     */
    async send(peerId, content, msgType) {
      const key = String(peerId)
      const type = msgType === 'image' ? 'image' : 'text'
      const text = String(content == null ? '' : content).trim()
      if (!text) throw new Error(type === 'image' ? '图片没传上来，这条没发出去' : '要说的内容不能是空的')
      if (!this.threads[key]) this.threads[key] = emptyThread(key)
      const clientMsgId = newClientMsgId()
      const pending = {
        id: null,
        clientMsgId: clientMsgId,
        fromUserId: null,
        toUserId: Number(key),
        mine: true,
        msgType: type,
        content: text,
        riskLevel: null,
        status: 'pending',
        readAt: null,
        createdAt: new Date().toISOString(),
        alert: null,
        error: ''
      }
      const t = this.threads[key]
      t.list = merge(t.list, [pending], false)
      this.bumpConversation(key, pending)
      const payload = { toUserId: Number(key), content: text, clientMsgId: clientMsgId, msgType: type }
      if (wsPublish(WS_APP.send, payload)) return pending
      try {
        const view = await sendPrivateMessage(payload)
        this.replacePending(key, clientMsgId, view)
        return view
      } catch (e) {
        this.failPending(key, clientMsgId, (e && e.message) || '这条私信没发出去')
        throw e
      }
    },

    /**
     * 重发一条失败的气泡（FR6.4「发送状态：发送中/已送达/失败可重发」）。
     *
     * 刻意复用原来那个 clientMsgId。失败有两种：「根本没到服务端」与「到了但被规则拦下」。
     * 前者重发必须让服务端认成同一条（否则用户点两下、对方收到两条），
     * 后者重发会原样再撞同一条规则、给出同一句文案 —— 两种都不需要新键。
     * 所以这里不生成幂等键，只把气泡从 failed 挪回 pending 再走一次发送路径。
     */
    async resend(peerId, clientMsgId) {
      const key = String(peerId)
      const t = this.threads[key]
      if (!t || !clientMsgId) return null
      let hit = null
      for (const m of t.list) if (m && m.id == null && m.clientMsgId === clientMsgId) hit = m
      if (!hit) return null
      hit.status = 'pending'
      hit.error = ''
      hit.errorCode = null
      const payload = {
        toUserId: Number(key),
        content: hit.content,
        clientMsgId: hit.clientMsgId,
        msgType: hit.msgType === 'image' ? 'image' : 'text'
      }
      if (wsPublish(WS_APP.send, payload)) return hit
      try {
        const view = await sendPrivateMessage(payload)
        this.replacePending(key, clientMsgId, view)
        return view
      } catch (e) {
        this.failPending(key, clientMsgId, (e && e.message) || '这条私信没发出去', e && e.code)
        throw e
      }
    },

    // ============================================================ 已读（FR6.4）

    /**
     * 上报已读。只在「这一会话正被看着 + 标签页可见」时由视图或帧回调触发。
     *
     * upToId 不带就是「整个会话全读」（后端 SQL 是 id <= upToId，缺省值即 Long.MAX_VALUE）：
     * 用户能看到的就是已经加载在屏幕上的那些气泡，所以这里刻意不带 upToId 也不对 ——
     * 只读到屏幕上最后一条，才和「他看到了什么」一致。
     */
    async markRead(peerId, upToId) {
      const key = String(peerId)
      const t = this.threads[key]
      if (t && t.list) {
        // 本地先打勾：等回执再打的话，用户已经往下翻了两屏，"已读"标记才慢吞吞追上来。
        for (const m of t.list) {
          if (!m.mine && (upToId == null || Number(m.id) <= Number(upToId))) m.readAt = m.readAt || new Date().toISOString()
        }
        this.setUnreadOf(key, 0)
      }
      const payload = { peerId: Number(key), upToId: upToId == null ? undefined : upToId }
      if (wsPublish(WS_APP.read, payload)) return null
      try {
        return await reportRead(payload)
      } catch (e) {
        // 已读上报失败不该打扰用户：他没做错任何事，最坏结果是对方的「已读」标记晚一点出现。
        return null
      }
    },

    // ============================================================ 未读 / 在线（FR6.5、FR9.1）

    async refreshUnread() {
      try {
        const view = await pmUnread()
        if (!view) return
        this.unreadTotal = Number(view.total) || 0
        const map = {}
        const byPeer = view.byPeer || {}
        for (const k of Object.keys(byPeer)) map[String(k)] = Number(byPeer[k]) || 0
        this.byPeerUnread = map
        // peers 只包含「有未读的那些人」：会话列表已加载时顺手补上没在列表里的行（刚被私聊的新人）。
        const peers = Array.isArray(view.peers) ? view.peers : []
        for (const p of peers) {
          if (!this.conversationOf(p.peerId)) this.conversations.unshift(p)
          const cur = this.conversationOf(p.peerId)
          if (cur) {
            cur.unreadCnt = p.unreadCnt
            cur.online = p.online
            this.online[String(p.peerId)] = !!p.online
          }
        }
        this.lastSyncAt = Date.now()
      } catch (e) {
        this.convError = this.convError || (e && e.message) || '未读没读到'
      }
    },

    setUnreadOf(peerId, n) {
      const key = String(peerId)
      // 🔴 顺序是死的：prev 必须在改映射**之前**读。上一版写成「先 spread 再取
      // this.byPeerUnread[key]」，取到的已经是新值，于是 - prev + new 恒等于 0，
      // unreadTotal 这辈子都不会因为收信/已读而动一下。症状在界面上很具体：
      // 顶栏那颗私信角标（BasicLayout 读 pm.unreadTotal）在「读完私信」之后不归零，
      // 要等下一次 refreshUnread（重进页面、点通知、WS 重连）才消；而列表页每行的小红点
      // 读的是 byPeerUnread，那一行当时是对的 —— 同一个未读数两处不一致，最容易让人以为
      // 「后端算错了」，其实后端从没被问过第二个数。Gate5 的 C7/C8 就是抓这条的。
      const prev = Number(this.byPeerUnread[key]) || 0
      this.byPeerUnread = { ...this.byPeerUnread, [key]: Math.max(0, Number(n) || 0) }
      // 不要用局部映射去求全局总数：byPeerUnread 来自后端的 Map<userId,count>，
      // 只覆盖「还有未读的人」，把它求和当总数，就等于把服务端 total 里那些还没进映射的人清零。
      // 正解是差值：总数跟着这一条的变化量走。
      this.unreadTotal = Math.max(0, (Number(this.unreadTotal) || 0) - prev + this.byPeerUnread[key])
      const cur = this.conversationOf(key)
      if (cur) cur.unreadCnt = this.byPeerUnread[key]
    },

    async refreshOnline(peerIds) {
      const ids = (peerIds || []).filter(function (x) { return x !== null && x !== undefined && x !== '' })
      if (!ids.length) return
      try {
        const view = await pmOnline(ids)
        const on = {}
        const list = (view && Array.isArray(view.online)) ? view.online : []
        for (const id of ids) on[String(id)] = false
        for (const id of list) on[String(id)] = true
        this.online = { ...this.online, ...on }
        for (const it of this.conversations) if (on[String(it.peerId)] !== undefined) it.online = on[String(it.peerId)]
        const t = this.threads[String(this.activePeer)]
        if (t && on[String(t.peerId)] !== undefined) t.peerOnline = on[String(t.peerId)]
      } catch (e) {
        /* 在线点是锦上添花：读不到就停在旧值，界面上没有红点会误导人，绿点少一个不会 */
      }
    },

    // ============================================================ 拉黑 / 举报（FR6.7、FR6.8）

    async block(peerId, reason) {
      const view = await apiBlockPeer(peerId, reason)
      const t = this.threads[String(peerId)]
      if (t) t.blocked = true
      return view
    },

    async unblock(peerId) {
      const view = await unblockPeer(peerId)
      const t = this.threads[String(peerId)]
      if (t) t.blocked = false
      if (this.blocksLoaded) this.blocks = this.blocks.filter(function (x) { return String(x.peerId) !== String(peerId) })
      return view
    },

    async loadBlocks(reset = true) {
      try {
        const list = await listBlocks()
        this.blocks = Array.isArray(list) ? list : []
        this.blocksError = ''
      } catch (e) {
        this.blocksError = (e && e.message) || '黑名单没读到'
        if (reset) this.blocks = []
      } finally {
        this.blocksLoaded = true
      }
      return this.blocks
    },

    async report(messageId, reason, description) {
      const view = await reportMessage({ messageId: messageId, reason: reason, description: description || undefined })
      // taskId 可能为 null 且不算失败（并发下别人的举报已经把这条送进队列、那张刚好被办结）。
      // 所以这句文案只能说「已经收到」，不能说「已生成工单 #null」。
      ElMessage.success((view && view.tip) || '已收到你的举报，屿安会看一眼')
      return view
    },

    // ============================================================ 帧处理（T5.2/T5.3/T5.8）

    /** 收到的私信本体（/user/queue/private，mine=false）。 */
    applyIncoming(view) {
      if (!view || view.id == null) return
      const key = String(view.mine ? view.toUserId : view.fromUserId)
      if (!this.threads[key]) this.threads[key] = emptyThread(key)
      const t = this.threads[key]
      const before = t.list.length
      t.list = merge(t.list, [view], false)
      if (t.list.length === before) return // 重复帧：幂等键没兜住的补拉/重放在这里丢弃
      if (t.minId === null || Number(view.id) < t.minId) t.minId = Number(view.id)
      // 摘要无条件刷新（addUnread 交给下面的分支决定，避免与 setUnreadOf 各加一次）。
      // 早先只在「会话没加载过」时才 bump，症状是正被看着的那一行停在半小时前的最后一条。
      this.bumpConversation(key, view, false)
      // 正被看着且标签页可见才算已读（手册 FR6.4 的回执语义：回执的读者是发信方，
      // 把「切到后台的一眼」也算成已读，等于替用户撒了个他自己都没看到的谎）。
      if (t.loaded && this.activePeer === key && document.visibilityState === 'visible') {
        this.markRead(key, view.id)
        return
      }
      this.setUnreadOf(key, this.unreadOf(key) + 1)
    },

    /** 回执（/user/queue/ack）：sent / delivered / read / error 四种，pong 走 /user/queue/ping 不进来。 */
    applyAck(ack) {
      if (!ack || !ack.kind) return
      const kind = ack.kind
      if (kind === 'sent' || kind === 'delivered') {
        const key = ack.peerId != null ? String(ack.peerId) : this.activePeer
        const t = this.threads[key]
        if (!t) return
        let hit = null
        if (ack.clientMsgId) {
          for (const m of t.list) if (m.clientMsgId === ack.clientMsgId && m.id == null) hit = m
        }
        if (!hit) {
          // 没带键的 delivered 回执（重投作业发的那种）按 id 找服务端那一行。
          if (ack.id != null) {
            for (const m of t.list) if (String(m.id) === String(ack.id)) hit = m
          }
        }
        if (hit) {
          if (ack.id != null) hit.id = ack.id
          if (ack.at) hit.createdAt = ack.at
          hit.status = kind === 'sent' ? hit.status || 'sent' : 'delivered'
          this.bumpConversation(key, hit)
        }
        return
      }
      if (kind === 'read') {
        const key = ack.peerId != null ? String(ack.peerId) : this.activePeer
        const t = this.threads[key]
        if (!t) return
        const count = Number(ack.count) || 0
        if (count <= 0) return
        // 后端按 id <= upToId 翻行，所以被翻掉的恰好是「我发的、还没被读过的那里最早的一批」，
        // 且数量就是回执里的 count。按这个顺序打勾，不需要 upToId 出现在回执里。
        const mine = t.list.filter(function (m) { return m.mine && !m.readAt && m.id != null })
          .sort(function (a, b) { return Number(a.id) - Number(b.id) })
        for (let i = 0; i < Math.min(count, mine.length); i++) mine[i].readAt = ack.at || new Date().toISOString()
        return
      }
      if (kind === 'error') {
        const key = ack.peerId != null ? String(ack.peerId) : this.activePeer
        const t = this.threads[key] || this.threads[this.activePeer]
        if (!t) {
          ElMessage.error(ack.tip || '这条私信没发出去')
          return
        }
        // @MessageExceptionHandler 拿不到入参，所以这里的 clientMsgId 恒为空（后端注释已写明代价）：
        // 前端只能按「最近一条还挂着 pending 的气泡」归位。
        let hit = null
        if (ack.clientMsgId) {
          for (const m of t.list) if (m.clientMsgId === ack.clientMsgId && m.id == null) hit = m
        }
        if (!hit) {
          for (const m of t.list) if (m.id == null) hit = m
        }
        if (hit) this.failPending(key, hit.clientMsgId, ack.tip || '这条私信没发出去', ack.code)
      }
    },

    /** 点推的在线变化（/user/queue/presence：PmPresenceView.ofChange → {online:[], userId, isOnline}）。 */
    applyPresencePush(payload) {
      if (!payload) return
      const id = payload.userId == null ? null : String(payload.userId)
      if (id) {
        this.online = { ...this.online, [id]: !!payload.isOnline }
        const cur = this.conversationOf(id)
        if (cur) cur.online = !!payload.isOnline
        const t = this.threads[id]
        if (t) t.peerOnline = !!payload.isOnline
      }
      const list = Array.isArray(payload.online) ? payload.online : []
      for (const one of list) this.online[String(one)] = true
    },

    /** 全站广播（/topic/presence）只有 {onlineCount, ts}：不含任何身份，这是需求 BR11 的最小展示。 */
    applyPresenceBroadcast(payload) {
      if (payload && typeof payload.onlineCount === 'number') this.presenceTotal = payload.onlineCount
    },

    /** 通知帧 {type,id,unread,ts}：unread 是通知表里的未读数（StompPushHook 在 afterCommit 之后才算）。 */
    applyNotifyFrame(payload) {
      if (!payload) return
      const notify = useNotifyStore()
      if (typeof payload.unread === 'number') notify.setUnread(payload.unread)
      if (payload.type === 'dm' || payload.type === 'risk') {
        // 私信角标必须单独刷：通知帧只给一个未读数，不给「谁发来的第几条」。
        this.refreshUnread()
        if (this.convLoaded) this.loadConversations(true).catch(function () {})
      }
    },

    /** 危机求助卡（/user/queue/alert：{level,messageId,side,hotline,at}，载荷里没有私信正文）。 */
    applyAlertFrame(payload) {
      if (!payload || !payload.level) return
      // 卡片正文不落进列表（那是服务端在消息视图里给的 alert 字段），这里只做一次弹层提醒：
      // 载荷刻意不含正文，弹窗可能在共享屏幕上被旁人看到（需求 BR11）。
      const hotline = payload.hotline || '12356'
      ElMessage({
        message: '屿安已经注意到刚才那句话。如果此刻很难受，马上拨打 ' + hotline + '（24 小时有人接）。',
        type: 'warning',
        duration: 12000
      })
    },

    replacePending(peerId, clientMsgId, view) {
      const key = String(peerId)
      const t = this.threads[key]
      if (!t || !view) return
      let hit = null
      for (const m of t.list) if (m.clientMsgId === clientMsgId && m.id == null) hit = m
      if (!hit) {
        if (view.id != null && !t.list.some(function (m) { return String(m.id) === String(view.id) })) {
          t.list = merge(t.list, [view], false)
        }
        return
      }
      hit.id = view.id
      hit.status = view.status || 'sent'
      hit.createdAt = view.createdAt || hit.createdAt
      hit.riskLevel = view.riskLevel || null
      hit.alert = view.alert || null
      hit.error = ''
      this.bumpConversation(key, hit)
    },

    failPending(peerId, clientMsgId, message, code) {
      const key = String(peerId)
      const t = this.threads[key]
      if (!t) return
      for (const m of t.list) {
        if (m.id == null && (!clientMsgId || m.clientMsgId === clientMsgId)) {
          m.status = 'failed'
          m.error = message
          m.errorCode = code
        }
      }
    },

    /** 会话列表里那一行的「最后一句」跟着最新消息走（本地也维护，不等下一次整页重取）。 */
    bumpConversation(peerId, msg, addUnread) {
      if (!msg) return
      const key = String(peerId)
      let cur = this.conversationOf(key)
      if (!cur) {
        cur = {
          peerId: Number(key),
          peerName: '',
          peerAvatar: '',
          lastMsgId: null,
          lastContent: '',
          lastMsgType: 'text',
          lastAt: null,
          lastMine: false,
          unreadCnt: 0,
          online: !!this.online[key]
        }
        this.conversations.unshift(cur)
      }
      if (msg.id != null && cur.lastMsgId != null && Number(msg.id) <= Number(cur.lastMsgId)) {
        // 补拉/翻页带回来的旧行不该把摘要倒退；未读仍然可以累加。
      } else {
        cur.lastMsgId = msg.id == null ? cur.lastMsgId : msg.id
        cur.lastContent = msg.msgType === 'image' ? '[图片]' : msg.content
        cur.lastMsgType = msg.msgType || 'text'
        cur.lastAt = msg.createdAt || cur.lastAt
        cur.lastMine = !!msg.mine
      }
      if (addUnread) cur.unreadCnt = Number(cur.unreadCnt || 0) + 1
      const t = this.threads[key]
      if (t && t.peerName && !cur.peerName) {
        cur.peerName = t.peerName
        cur.peerAvatar = t.peerAvatar
      }
    },

    // ============================================================ 通道绑定（App.vue 调一次）

    /**
     * 把七个目的地接进本 store。返回取消函数（退出登录时必须调）。
     *
     * 为什么不在各视图里 subscribe：那样一来「详情页开着、列表页在后台」这一帧就会被两个组件
     * 各处理一遍，未读数加两次；而这里加一次是全局唯一的一次。
     */
    bindWs() {
      const offs = []
      offs.push(onWs(WS_DEST.private, (p) => this.applyIncoming(p)))
      offs.push(onWs(WS_DEST.ack, (p) => this.applyAck(p)))
      offs.push(onWs(WS_DEST.notify, (p) => this.applyNotifyFrame(p)))
      offs.push(onWs(WS_DEST.alert, (p) => this.applyAlertFrame(p)))
      offs.push(onWs(WS_DEST.presence, (p) => this.applyPresencePush(p)))
      offs.push(onWs(WS_DEST.topicPresence, (p) => this.applyPresenceBroadcast(p)))
      offs.push(onWs(WS_DEST.pong, () => { this.transport = 'open' }))
      offs.push(onWs('reconnect', () => {
        this.transport = 'open'
        // 断线补拉（FR6.3）：只补当前看着的会话，其余的等用户点进去再取 ——
        // 全站每个会话都补一次等于把 fetch-max 变成一次扫表。
        if (this.activePeer) this.refillThread(this.activePeer).catch(function () {})
        this.refreshUnread()
        if (this.convLoaded) this.loadConversations(true).catch(function () {})
      }))
      offs.push(onWs('close', () => { this.transport = 'polling' }))
      offs.push(onWs('poll', () => {
        // 降级轮询：读数 + 看着的那个会话的最新一页。合并靠 id 去重，
        // 所以「轮询和刚恢复的 WS 同时送来同一条」也只会在屏上出现一个气泡。
        this.transport = wsIsUsable() ? 'open' : 'polling'
        this.refreshUnread()
        if (this.activePeer) this.fetchThread(this.activePeer, true).catch(function () {})
        else if (this.convLoaded) this.loadConversations(true).catch(function () {})
      }))
      this.transport = wsIsUsable() ? 'open' : 'polling'
      return function () {
        for (const off of offs) off()
      }
    },

    /** 退出登录：清掉所有可能属于上一个人的会话内容。不这么做的话「退出再登录」会先闪一下旧私信。 */
    clear() {
      this.conversations = []
      this.convNextCursor = null
      this.convHasMore = false
      this.convLoaded = false
      this.convError = ''
      this.unreadTotal = 0
      this.byPeerUnread = {}
      this.threads = {}
      this.activePeer = ''
      this.online = {}
      this.presenceTotal = 0
      this.blocks = []
      this.blocksError = ''
      this.blocksLoaded = false
      this.transport = 'idle'
      this.lastSyncAt = 0
    }
  }
})

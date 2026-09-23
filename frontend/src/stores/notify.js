import { defineStore } from 'pinia'
import { listNotifications, markNotificationsRead, NOTIFY_PAGE_SIZE } from '@/api/notify'

// 未读数与通知列表（FR9.2/9.3 通知中心与实时红点的客户端侧；任务 T3.11-b 只做列表与已读）。
//
// 为什么未读数不在这个 store 里自己累加：后端每一页都带 unreadCount（它是按
// (user_id, is_read, id) 覆盖索引 COUNT 出来的真相）。前端一旦开始 +1/-1，就会出现
// 「列表说三条未读、红点显示五条」这种自相矛盾的界面，而且没有任何地方能发现。
// 所以 inc() 只留给将来的 WebSocket 推送当乐观值，其余一律以回执里的数字为准。
export const useNotifyStore = defineStore('notify', {
  state: () => ({
    unread: 0,
    // latest 是给 T5.8 的推送口留的位置：实时推来的条目先进这里，阶段 3 的铃铛只认 items。
    latest: [],
    items: [],
    nextCursor: null,
    hasMore: false,
    loading: false,
    loaded: false,
    // error 不是「有没有失败」的布尔，而是要就地显示给用户看的那句话。
    // 红点取不到数据时至少要说「没读到」，不能顶着 0 装成「你真的没有新通知」。
    error: ''
  }),
  actions: {
    setUnread(n) { this.unread = Number(n) || 0 },
    inc(n = 1) { this.unread += n },
    push(item) { this.latest.unshift(item); if (this.latest.length > 50) this.latest.pop() },
    clear() {
      this.unread = 0
      this.latest = []
      this.items = []
      this.nextCursor = null
      this.hasMore = false
      this.loaded = false
      this.error = ''
    },

    /**
     * 拉一页。reset=true（默认）从头拉最新一页并替换列表；false 用 nextCursor 续旧的那一页。
     *
     * 刻意不 catch 成「静默成功」：调用方（铃铛）要区分「读到 0 条」和「根本没读到」，
     * 前者显示空空如也，后者显示那句错误。
     */
    async load(reset = true) {
      if (this.loading) return
      if (!reset && (!this.hasMore || !this.nextCursor)) return
      this.loading = true
      this.error = ''
      try {
        const beforeId = reset ? undefined : this.nextCursor
        const page = await listNotifications({ size: NOTIFY_PAGE_SIZE, beforeId })
        const list = Array.isArray(page && page.list) ? page.list : []
        this.items = reset ? list : this.items.concat(list)
        this.unread = Number(page.unreadCount) || 0
        this.nextCursor = page.nextCursor == null ? null : page.nextCursor
        this.hasMore = !!page.hasMore
        this.loaded = true
      } catch (e) {
        this.error = e && e.message ? e.message : '通知没读到'
        if (reset) { this.items = []; this.nextCursor = null; this.hasMore = false }
        throw e
      } finally {
        this.loading = false
      }
    },

    loadMore() { return this.load(false) },

    /**
     * 只做红点：拉一页 1 条，为的是那个 unreadCount。
     *
     * 为什么不顺手把这 1 条存进 items：那会让铃铛点开时「已有一条」和「刚拉到的二十条」
     * 来自两次不同的读取，中间若来了新通知，列表就会出现一条不属于这一页的孤条目。
     * 列表留给点开那一刻一次读全，红点单独一次轻量读。
     */
    async refreshUnread() {
      try {
        const page = await listNotifications({ size: 1 })
        this.unread = Number(page && page.unreadCount) || 0
        this.error = ''
      } catch (e) {
        // 静默失败是这里唯一不能做的事：令牌过期时红点该变成「没读到」，而不是顶着一个 0 装没事。
        this.error = e && e.message ? e.message : '通知没读到'
      }
    },

    /** 点掉指定几条；回执里的 unreadCount 直接刷红点，不必再请求一次列表。 */
    markRead(ids) { return this._send({ ids: Array.isArray(ids) ? ids : [ids] }) },

    markAll() { return this._send({ all: true }, true) },

    async _send(payload, all) {
      const view = await markNotificationsRead(payload)
      const count = Number(view && view.unreadCount) || 0
      this.unread = count
      if (all) {
        // 一键已读时后端不会告诉本次动了哪几行，所以整页一起翻转——
        // 这与「按 ids 点」不同：那一种请求里的每个 id 都来自这一份列表，可以逐条对应。
        this.items.forEach(function (x) { x.read = true })
      } else {
        const hit = {}
        ;(payload.ids || []).forEach(function (id) { hit[id] = true })
        this.items.forEach(function (x) { if (hit[x.id]) x.read = true })
      }
      // 未读归零之后不假装知道后面还有什么：让下一次展开重新拉，比留着半截旧列表诚实。
      if (count === 0) this.loaded = true
      return view
    }
  }
})

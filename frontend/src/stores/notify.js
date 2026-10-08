import { defineStore } from 'pinia'
import { ElMessage } from 'element-plus'
import { WS_DEST, onWs } from '@/composables/useWs'
import {
  getNotifyPreferences,
  listNotifications,
  markNotificationsRead,
  saveNotifyPreferences,
  NOTIFY_PAGE_SIZE
} from '@/api/notify'

// 未读数与通知列表（FR9.2/9.3 通知中心与实时红点的客户端侧；任务 T3.11-b 只做列表与已读）。
//
// 为什么未读数不在这个 store 里自己累加：后端每一页都带 unreadCount（它是按
// (user_id, is_read, id) 覆盖索引 COUNT 出来的真相）。前端一旦开始 +1/-1，就会出现
// 「列表说三条未读、红点显示五条」这种自相矛盾的界面，而且没有任何地方能发现。
// 所以 inc() 只留给将来的 WebSocket 推送当乐观值，其余一律以回执里的数字为准。
/**
 * 收到实时帧之后，隔多久去补拉一次列表。
 *
 * 定时器放在**模块作用域**而不是 state 里：pinia 的 state 会被 devtools 序列化、
 * 也会在热更新时被搬来搬去，把一个 Timeout 塞进去等于往里塞一个不可序列化的对象。
 * 800ms 的取值理由：连赞三条帖子的真实场景里，一秒内会来三帧，
 * 逐帧各拉一次就是三次同样的列表请求；而把延迟拉到 2s 以上，用户已经在页面上
 * 看着那条通知「迟迟不出现」了。私信侧同一个量级（stores/pm.js 的合并刷新）。
 */
let frameTimer = null
const FRAME_RELOAD_MS = 800

/**
 * 实时帧的逐类文案。
 *
 * 这里的键是**后端推送帧里的 type**（StompPushHook.WIRE_TYPE），不是通知表的八个类型码：
 * like / audit / report 三类在帧上被折进 system，前端拿不到更细的粒度。
 * 之所以不写「谁赞了你」这种完整句子 —— 帧里刻意不带 title/content（需求 BR11：
 * toast 可能出现在共享屏幕和浏览器弹窗上），正文由随后那一次列表补拉带回来。
 */
const FRAME_TOAST = {
  comment: '有人回复了你',
  follow: '有人关注了你',
  system: '收到新的系统通知'
}

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
    error: '',
    // 通知偏好（FR9.4）。prefsLoaded 与 prefsError 分开存是必须的：
    // 「八格全是默认值」和「一个都没读到」在界面上长得一模一样，只有文案能区分它们。
    prefs: [],
    prefsLoaded: false,
    prefsLoading: false,
    prefsError: ''
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
      this.prefs = []
      this.prefsLoaded = false
      this.prefsLoading = false
      this.prefsError = ''
      if (frameTimer) { clearTimeout(frameTimer); frameTimer = null }
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
    },

    // ---------------------------------------------------------------- 通知偏好（FR9.4）

    /**
     * 读八个开关。
     *
     * 只有后端那份「完整八格」会被存进来，前端不预填一份默认表：
     * 目录顺序、哪三格置灰、置灰的理由句，全都以回执为准。少写一份映射就少一处会漂移的地方。
     */
    async loadPrefs() {
      this.prefsLoading = true
      this.prefsError = ''
      try {
        const rows = await getNotifyPreferences()
        this.prefs = Array.isArray(rows) ? rows : []
        this.prefsLoaded = true
      } catch (e) {
        this.prefsError = e && e.message ? e.message : '通知设置没读到'
      } finally {
        this.prefsLoading = false
      }
    },

    /**
     * 保存。toggles = [{ type, enabled }]，只带改动的那一两格。
     *
     * 回执（完整八格）直接覆盖 prefs：界面因此永远显示「后端认为的当前值」，
     * 而不是「我点下去时以为的值」。失败原样抛出，由调用方决定要不要留旧值。
     */
    async savePrefs(toggles) {
      this.prefsLoading = true
      this.prefsError = ''
      try {
        const rows = await saveNotifyPreferences(toggles)
        this.prefs = Array.isArray(rows) ? rows : this.prefs
        this.prefsLoaded = true
        return rows
      } catch (e) {
        this.prefsError = e && e.message ? e.message : '通知设置没保存成功'
        throw e
      } finally {
        this.prefsLoading = false
      }
    },

    // ---------------------------------------------------------------- 实时帧（T5.8）

    /**
     * 处理 /user/queue/notify 上的一帧 {type, id, unread, ts}。
     *
     * <p><b>这里刻意不碰 unread</b>：未读数的唯一真相是后端每页带回的 unreadCount，
     * 而帧上的 unread 已经由 {@code stores/pm.js#applyNotifyFrame} 写进 store 一次了。
     * 同一个数字有两个写入方，就是「列表说三条未读、红点显示五条」那种自相矛盾的界面 ——
     * 本文件顶部拒绝过一次的事情，不在这里第二次犯错。</p>
     *
     * <p><b>dm 与 risk 不在这里弹</b>：新私信由 pm 侧负责（会话列表与角标自刷新，
     * 停在会话页时再弹一条就是重复），危机走 /user/queue/alert 那条带热线的话术。
     * 这里只管 comment / follow / system 三类。</p>
     *
     * <p><b>被偏好的那一类根本不会有帧到达</b>：闸门在 {@code NotifyService#write} 里
     * 就不触发推送口了（FR9.4 与 Gate3 的实现），所以前端不需要「先弹再按类型过滤」，
     * 那份过滤逻辑留在客户端只会造成一种结果 —— 后端改了口径而这里没跟上。</p>
     */
    applyFrame(frame) {
      if (!frame || !frame.type) return
      this.push(frame)
      const copy = FRAME_TOAST[frame.type]
      if (copy) {
        ElMessage({ message: copy, type: 'info', duration: 3000 })
      }
      // 只在列表已经被读过的前提下补拉：没人看过铃铛和这一页时，
      // 为一条没人在看的帧付一次列表往返，是纯浪费。
      if (this.loaded) {
        if (frameTimer) clearTimeout(frameTimer)
        frameTimer = setTimeout(() => {
          frameTimer = null
          this.load(true).catch(function () {})
        }, FRAME_RELOAD_MS)
      }
    },

    /**
     * 订阅实时通知帧。全局唯一一次由 App.vue 负责（理由见 stores/pm.js#bindWs 的注释：
     * 两处各订阅一遍，同一帧会被处理两遍，toast 就是弹两条）。
     */
    bindWs() {
      const off = onWs(WS_DEST.notify, (p) => this.applyFrame(p))
      return function () { off && off() }
    }
  }
})

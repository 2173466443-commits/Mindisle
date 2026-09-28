import http from './http'

// 后端 com.mindisle.web.NotificationController（任务 T3.11-b · 需求 FR9.1、FR9.2）。
//
// 读接口 silent：铃铛是顶栏上的一个角标，红点取不到数据时该显示「暂时没读到」而不是一进来
// 就糊一条全局红条——尤其是令牌刚好过期的那一刻，全站每个页面都会被这条消息砸一次。
// 写接口刻意不 silent：标已读失败的唯一原因是「没登录 10002」和「参数没给对 10001」，
// 这两句话本来就该由那次点击来说，页面再翻译一遍只会和后端分叉。
//
// 两个接口都不接受 user_id：收件人只来自 JWT（与点赞、关注、举报同一口径），
// 所以这里没有任何一个参数需要前端去拼「我是谁」。

/** 列表默认页长：与后端 NotifyService.DEFAULT_SIZE 同源，改这里要同时确认后端 normalize 的 1..50 区间。 */
export const NOTIFY_PAGE_SIZE = 20

/** 一次批量已读的上联：与后端 NotifyService.MARK_BATCH_MAX 同源，超了后端回 400/10001 而不是慢慢吞吞扫全表。 */
export const NOTIFY_MARK_MAX = 100

/**
 * 我的通知列表（游标倒序）。
 * unreadCount 每页都带回来，所以红点只有一个数据来源——不要再单独开一个 unread 接口。
 */
export const listNotifications = (params) => http.get('/notifications', { params, silent: true })

/** 标记已读：{ ids: [...] } 或 { all: true } 二选一，都不给后端是 400/10001 而不是「当成全部已读」。 */
export const markNotificationsRead = (payload) => http.post('/notifications/read', payload)

/**
 * 八类通知的中文标签。
 *
 * 界面上显示的标签用的是后端每条带回来的 typeLabel，这份映射只干一件事：
 * 给「按类型决定跳到哪儿」当查表用。为什么不在这里放图标以外的文案——
 * 后端加一类通知时如果忘了同步这里，最坏结果是「跳转兜底到广场」，
 * 而不是列表里冒出一个没人认识的英文码（那种情况由 typeLabel 原样回显负责被看见）。
 */
export const NOTIFY_TYPES = [
  { value: 'like', label: '赞', ref: 'post' },
  { value: 'comment', label: '评论', ref: 'post' },
  { value: 'follow', label: '关注', ref: 'user' },
  // 私信这一格在写第一版跳转表时是 'conversation'，那是错的：后端 NotifyMessage 有两个不同常量，
  // REF_CONVERSATION = "conversation" 只被 notifyCrisisAdmin（AI 会话工单）使用；
  // 私信走的是 REF_PM = "pm"，且 ref_id 存的是**对方用户 id**（不是消息 id，见 NotifyService#notifyPm）。
  // 这一格写错的症状不是报错，而是「点私信通知跳不过去」—— 跳转全靠 refType 查表，
  // 表里没有的值永远匹配不上，所以这张表必须逐字对着后端常量类读一遍。
  { value: 'pm', label: '私信', ref: 'pm' },
  { value: 'system', label: '系统', ref: null },
  { value: 'audit', label: '审核结果', ref: 'post' },
  { value: 'crisis', label: '危机关怀', ref: null },
  { value: 'report', label: '举报回执', ref: 'report' }
]

const ICONS = { like: '👍', comment: '💬', follow: '🤝', pm: '✉️', system: '🔔', audit: '📋', crisis: '🫂', report: '🚩' }

/** 未知类型回一个通用铃铛，而不是 undefined 把列表炸成空白格子。 */
export function notifyIcon(type) {
  return ICONS[type] || '🔔'
}

/**
 * 一条通知该跳到哪儿。
 *
 * 只认 refType + refId，不猜文案：文案是给人读的，改一个字就该跳转照旧；
 * 而 ref 是后端承诺过的契约（post / comment / user / report / conversation / pm）。
 * pm 与 crisis 要一起看：crisis 的 ref 有两种，AI 会话工单是 conversation、私信风险工单是 pm
 * （NotifyService#notifyPmCrisisAdmin 的 ref_id 是**发信方用户 id**），所以这里只按 refType 分支，
 * 不按通知类型分支 —— 同一条 crisis 通知会因为 ref 不同跳到完全不同的地方，这才是对的。
 * comment 类的通知仍然跳帖子——楼中楼藏在帖子详情页里，单跳评论 id 反而看不到上下文，
 * 这个取舍与后端 NotifyService#notifyReply 的注释是同一句。
 */
export function notifyRoute(item) {
  if (!item) return null
  if (item.refType === 'post' && item.refId) return { name: 'post-detail', params: { id: item.refId } }
  if (item.refType === 'user' && item.refId) return { name: 'user-home', params: { id: item.refId } }
  // 私信类跳转：refId 就是对方用户 id，正好是 chat-detail 的路由参数（/chat/:uid）。
  // 不做「定位到那一条消息」：后端没有按消息 id 深链的接口，线程第一页取回的就是最新一段。
  if (item.refType === 'pm' && item.refId) return { name: 'chat-detail', params: { uid: item.refId } }
  return null
}

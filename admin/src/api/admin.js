import http, { rawHttp } from './http'

// ============================================================================
// 管理端接口面（手册 §9.2 阶段 6）
//
// 这份清单不是照「应该有什么」写的，是照后端真实注册的东西写的：
// 64 条 mapping 现查于 backend/src/main/java/com/mindisle/web/Admin*Controller，
// 快照存在仓库外 E:/codex workspace/_cache/009_mindisle/api-surface.txt。
// 出参的键形状也不是猜的，是拿 gate6_super 真打一轮 /api/admin/** 之后按 JSON 实际键写的
// （脚本 _cache/009_mindisle/shapes.mjs，产物 shapes.out.txt）。
//
// 三条统一约定：
//   1. 全部 silent:true —— 全局只弹一条就消失的消息，在管理端等于「什么都没发生」。
//      每个页面自己把失败原因写在块里（手册 §5.8 第 1 条：没有数据必须说出来）。
//   2. 状态/等级/动作码一律用本文件导出的白名单常量渲染下拉框，不给自由输入。
//      后端对非法筛选值是当场 10001 而不是返回空列表（AuditQueueService.list 的注释写了为什么：
//      审核台一次「筛错但看起来正常」的空列表，代价是一篇帖子在积压里躺三天）。
//   3. 列表返回都是 PageResult 壳 {list,total,page,size,hasMore}，取数先剥壳再剥嵌套视图
//      （TaskView{task,post,hitWords,overdue} / TicketView{ticket,nickname,…} /
//        AppealView{appeal,postTitle,postStatus} / ReportView{report,reporterNickname,postTitle}）。
// ============================================================================

/* ---------------- 白名单常量（真源=后端常量，改后端要同步改这里） ---------------- */

/** AdminOpLog.ACTION_* 共 18 个：A9 的动作码下拉只能从这里取，自由输入必吃 10001。 */
export const OP_ACTIONS = [
  'AUDIT_CLAIM', 'AUDIT_PASS', 'AUDIT_REJECT', 'TICKET_HANDLE', 'APPEAL_HANDLE', 'REPORT_HANDLE',
  'POST_TAKEDOWN', 'POST_RESTORE', 'POST_TOP', 'POST_FEATURE',
  'MUTE_USER', 'UNMUTE_USER', 'BAN_USER', 'RESTORE_USER', 'REVEAL_ANONYMOUS',
  'UPDATE_CONFIG', 'EXPORT_CSV', 'READ_PM'
]
export const OP_RESULTS = ['SUCCESS', 'FAIL', 'DENIED']

/** audit_task.status 五态（注意没有 CLAIMED：认领态在库里叫 PROCESSING）。 */
export const AUDIT_STATUSES = ['PENDING', 'PROCESSING', 'PASSED', 'REJECTED', 'ESCALATED']
export const RISK_LEVELS = ['L0', 'L1', 'L2', 'L3']

/** alert_ticket：等级只有 L2/L3（L1 走提醒，不进工单）；状态是小写，六态。 */
export const TICKET_LEVELS = ['L2', 'L3']
export const TICKET_STATUSES = ['pending', 'claimed', 'doing', 'closed', 'false_positive', 'expired']
export const TICKET_OPEN_STATUSES = ['pending', 'claimed', 'doing']
export const TICKET_CLOSE_TARGETS = ['closed', 'false_positive', 'expired']

/** ContentManageService.MANAGEABLE_STATUSES —— DRAFT 不在里面，管理端筛不到草稿。 */
export const POST_STATUSES = ['MACHINE_REVIEW', 'HUMAN_REVIEW', 'PUBLISHED', 'REJECTED', 'APPEALING', 'TAKEDOWN']
export const REPORT_STATUSES = ['PENDING', 'ACCEPTED', 'REJECTED']
export const REPORT_TARGET_TYPES = ['post', 'comment']
export const APPEAL_STATUSES = ['PENDING', 'ACCEPTED', 'REJECTED']
export const USER_STATUSES = ['ACTIVE', 'MUTED', 'BANNED', 'DELETED']
export const MUTE_DAYS = [1, 7, 30]
export const MATCH_TYPES = ['contains', 'regex']

/* -------------------------------- A2 数据大屏 -------------------------------- */
export const dashboardStats = () => http.get('/admin/dashboard/stats', { silent: true })
export const emotionBoard = (days) => http.get('/admin/dashboard/emotion-board', { params: { days }, silent: true })
export const hotTopics = (limit) => http.get('/admin/dashboard/hot-topics', { params: { limit }, silent: true })
export const gradeBoard = () => http.get('/admin/dashboard/grade-board', { silent: true })
export const hourHeatmap = (days) => http.get('/admin/dashboard/hour-heatmap', { params: { days }, silent: true })
// 二维热力（U16-④）：返回 { days, fromDate, toDate, cells }，cells 是稀疏的 day×hour 格。
// 一维那条 hourHeatmap 先留着不删：它回答的是「一天里哪个钟点低」，二维回答「星期几 × 钟点」，
// 两张图不是同一问题，删掉等于把两种读法之一藏起来。
export const dayHourHeatmap = (days) => http.get('/admin/dashboard/day-hour-heatmap', { params: { days }, silent: true })
export const emotionLabels = (days) => http.get('/admin/dashboard/emotion-labels', { params: { days }, silent: true })
export const aiUsage = (days) => http.get('/admin/dashboard/ai-usage', { params: { days }, silent: true })

/* ---------------- A9 推荐运维（阶段 7 · T7.9 / T7.15） ---------------- */
// 这两个口后端一直注册着，recgate7 也在 REST 层打通过（PASS），但 admin/src 里此前 0 处引用 ——
// 「接口通了」和「管理员点得到」是两件事，本轮补的是后一件（与大屏 emotionBoard 同一类缺口）。
// 语义照后端真源（AdminRecController 类注释）：rebuild 是同步跑完再返回摘要，不是异步任务表，
// 所以界面是「按钮转圈 + 回显本次 calc_ms 与各召回通道行数」，不做假的进度条，也不额外写 Redis 进度。
// timeout 必须单列：http 实例默认 15s，而 D7 的验收口径是「2 分钟内」——沿用默认值会把
// 「跑满 40 秒但确实成功」的重算误报成一次前端失败，那种假失败比慢本身更糟。
export const recStatus = (topic) => http.get('/admin/rec/status', { params: topic ? { topic } : {}, silent: true })
export const recRebuild = () => http.post('/admin/rec/rebuild', {}, { silent: true, timeout: 120000 })
// 推荐重算台账（U16-①）：读 rec_run_log，每次重算一行，定时和手动都记。
// 和 recStatus 的分工写在 A9 页面脚注里：status 是「现在算到哪」，runs 是「历史算过哪几次、每次成没成」。
export const recRuns = (limit) => http.get('/admin/rec/runs', { params: limit ? { limit } : {}, silent: true })


/* -------------------------------- A4 审核队列 -------------------------------- */
export const auditTasks = (params) => http.get('/admin/audit/tasks', { params: params || {}, silent: true })
export const auditStatusCounts = () => http.get('/admin/audit/status-counts', { silent: true })
export const auditAssigneeStats = () => http.get('/admin/audit/assignee-stats', { silent: true })
export const auditAuthorHistory = (userId) => http.get('/admin/audit/author/' + userId, { silent: true })
export const auditClaim = (taskId) => http.post('/admin/audit/tasks/claim', { taskId }, { silent: true })
export const auditAdjudicate = (payload) => http.post('/admin/audit/tasks/adjudicate', payload, { silent: true })
export const auditSyncPosts = () => http.post('/admin/audit/sync-posts', {}, { silent: true })
export const auditSyncImages = (limit) => http.post('/admin/audit/sync-images', {}, { params: limit ? { limit } : {}, silent: true })
export const auditReleaseTimeout = () => http.post('/admin/audit/release-timeout', {}, { silent: true })

/* ------------------------------- A5 危机工单 ------------------------------- */
export const tickets = (params) => http.get('/admin/tickets', { params: params || {}, silent: true })
export const ticketBoard = () => http.get('/admin/tickets/board', { silent: true })
export const ticketOverdue = (limit) => http.get('/admin/tickets/overdue', { params: { limit }, silent: true })
export const ticketDetail = (id) => http.get('/admin/tickets/' + id, { silent: true })
export const ticketTimeline = (userId, limit) => http.get('/admin/tickets/timeline/' + userId, { params: { limit }, silent: true })
export const ticketClaim = (ticketId) => http.post('/admin/tickets/claim', { ticketId }, { silent: true })
export const ticketStart = (ticketId) => http.post('/admin/tickets/start', { ticketId }, { silent: true })
export const ticketClose = (payload) => http.post('/admin/tickets/close', payload, { silent: true })
/** level 是必填参数（@RequestParam 无 required=false），不传会 400。返回 ISO 时间串。 */
export const ticketSla = (level) => http.get('/admin/tickets/sla', { params: { level }, silent: true })

/* -------------------------------- A3 工作台 -------------------------------- */
export const appealPage = (params) => http.get('/admin/appeals', { params: params || {}, silent: true })
export const appealPendingCount = () => http.get('/admin/appeals/pending-count', { silent: true })
export const appealDetail = (id) => http.get('/admin/appeals/' + id, { silent: true })
export const appealHistoryOfPost = (postId) => http.get('/admin/appeals/post/' + postId, { silent: true })
export const appealAdjudicate = (payload) => http.post('/admin/appeals/adjudicate', payload, { silent: true })

/* ------------------------------- A6 用户管理 ------------------------------- */
export const userPage = (params) => http.get('/admin/users', { params: params || {}, silent: true })
export const userDetail = (id) => http.get('/admin/users/' + id, { silent: true })
export const userMute = (payload) => http.post('/admin/users/mute', payload, { silent: true })
export const userBan = (payload) => http.post('/admin/users/ban', payload, { silent: true })
export const userRestore = (payload) => http.post('/admin/users/restore', payload, { silent: true })
/** 只有 SUPER 能做；ADMIN 点它会拿到 10003，并且在 admin_op_log 里留下一行 DENIED。 */
export const userRevealAnonymous = (payload) => http.post('/admin/users/reveal-anonymous', payload, { silent: true })
export const userRevealCount = () => http.get('/admin/users/reveal-count', { silent: true })
/**
 * 出参 data 是一个数字，不是对象——别按 .data.userId 取。
 * 但这个数字是 aliasId，不是 userId：源码 UserManageService.aliasIdOfPost 返回的是
 * post.getAliasId()，普通用户据此只能确认「自己这条匿名帖背后的别名记录」，
 * 要拿到背后的真人还得走 reveal-anonymous（只有 SUPER 有资格，且必写留痕）。
 * 非匿名帖/帖子不存在时返回 null，而 NON_NULL 序列化会让这个键整个消失，所以取值只能判空。
 */
export const userAliasOfPost = (postId) => http.get('/admin/users/alias-of-post/' + postId, { silent: true })

/* ------------------------------- A7 内容管理 ------------------------------- */
export const postPage = (params) => http.get('/admin/content/posts', { params: params || {}, silent: true })
export const postDetail = (id) => http.get('/admin/content/posts/' + id, { silent: true })
export const postTakedown = (payload) => http.post('/admin/content/post/takedown', payload, { silent: true })
export const postRestore = (payload) => http.post('/admin/content/post/restore', payload, { silent: true })
/** flag 只认 top / feature，digest 是非法值；它不写状态流转日志，只留 POST_TOP/POST_FEATURE 留痕。 */
export const postFlag = (postId, flag, on) => http.post('/admin/content/post/flag', { postId, flag, on }, { silent: true })
export const reportPage = (params) => http.get('/admin/content/reports', { params: params || {}, silent: true })
export const reportPendingCount = () => http.get('/admin/content/reports/pending-count', { silent: true })
export const reportHandle = (payload) => http.post('/admin/content/report/handle', payload, { silent: true })
export const contentChain = (postId) => http.get('/admin/content/chain/' + postId, { silent: true })

/* ------------------------------- A8 参数配置 ------------------------------- */
export const configList = (groupKey) => http.get('/admin/configs', { params: groupKey ? { groupKey } : {}, silent: true })
export const configUpdate = (cfgKey, value) => http.post('/admin/configs/update', { cfgKey, value }, { silent: true })
export const wordPage = (params) => http.get('/admin/words', { params: params || {}, silent: true })
export const wordGroups = () => http.get('/admin/word-groups', { silent: true })
export const wordAdd = (payload) => http.post('/admin/words/add', payload, { silent: true })
export const wordStatus = (id, status) => http.post('/admin/words/status', { id, status }, { silent: true })
export const wordDelete = (id) => http.post('/admin/words/delete', { id }, { silent: true })
export const dictReload = () => http.post('/admin/dict/reload', {}, { silent: true })
export const dictTrial = (text, side) => http.post('/admin/dict/trial', { text, side: side || 'user' }, { silent: true })
export const dictStatus = () => http.get('/admin/dict/status', { silent: true })

/* ------------------------------ A9 日志与报表 ------------------------------ */
/** params: {operatorId, action, result, from, to, page, size}。四个筛选位都在服务端过白名单，非法值 10001。 */
export const opLogPage = (params) => http.get('/admin/logs', { params: params || {}, silent: true })
export const opLogActionStats = (days) => http.get('/admin/logs/actions', { params: { days }, silent: true })
export const opLogReveals = () => http.get('/admin/logs/reveals', { silent: true })

// CSV 导出走的是同一条 axios 实例，不是 <a href>。
// 原因很具体：这三个接口要求 Authorization 头，裸链接不带令牌 → 401 → 浏览器下载到一个 JSON 错误体，
// 用户看到的「下载成功」是一个打不开的 .csv。后端返回的是 ResponseEntity<byte[]>（没有 Result 壳），
// 所以这里拿到的直接是 Blob；文件名从响应头 Content-Disposition 里取，取不到再本地兜一个。
async function downloadCsv (path, params, fallbackName) {
  try {
    const resp = await rawHttp.request({ url: path, method: 'get', params, responseType: 'blob', silent: true })
    const head = (resp.headers && (resp.headers['content-disposition'] || resp.headers['Content-Disposition'])) || ''
    const m = /filename="?([^";]+)"?/.exec(head)
    return { blob: resp.data, filename: m ? m[1] : fallbackName, rows: 0 }
  } catch (e) {
    // 失败时后端返回的也是 blob（application/json 的错误体），要读出来才知道是 10003 还是 10010。
    if (e.response && e.response.data instanceof Blob) {
      try {
        const j = JSON.parse(await e.response.data.text())
        const err = new Error(j.msg || '导出失败')
        err.code = j.code
        err.traceId = j.traceId
        throw err
      } catch (inner) {
        if (inner.code !== undefined) throw inner
        throw e
      }
    }
    throw e
  }
}
export const exportTicketsCsv = (days) => downloadCsv('/admin/export/tickets', { days }, 'tickets.csv')
export const exportAiUsageCsv = (days) => downloadCsv('/admin/export/ai-usage', { days }, 'ai-usage.csv')
export const exportOpLogsCsv = (params) => downloadCsv('/admin/export/op-logs', params || {}, 'op-logs.csv')

/** 把 Blob 存成文件的唯一一处实现：URL.createObjectURL 必须 revoke，否则每次导出都漏一个 Blob 引用。 */
export function saveBlob (blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename || 'export.csv'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
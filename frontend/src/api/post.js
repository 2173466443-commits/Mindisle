import http from './http'

// 后端 com.mindisle.web.PostController（任务 3.3 发帖 / 3.5 列表与详情 / 3.6 互动）。
// 读接口一律 silent：广场与详情页要自己把「为什么没数据」画出来（手册 §5.8 第 1 条），
// 不弹全局红条；写接口不 silent，因为发布失败的文案就该是那条消息本身。
export const listPosts = (params) => http.get('/posts', { params, silent: true })
export const postDetail = (id) => http.get('/posts/' + id, { silent: true })
export const createPost = (payload) => http.post('/posts', payload)
// 帖子互动（任务 T3.6 · FR4.4）：like / unlike / collect / uncollect 四个动作共用一条 POST，
// 后端按「目标 + 人 + 动作 + 当天」做幂等，所以前端连点两次不是 bug，也不需要自己去重。
// 刻意不 silent：这一路失败的原因（未登录 10002 / 帖子不可见 30001 / 账号被限制 20003）
// 就是要让人看见，闷着不响只会留下一个「点了没反应」的界面。
export const actOnPost = (id, action) => http.post('/posts/' + id + '/actions', { action })

// 评论与楼中楼（任务 T3.7 · FR4.3 / FR4.4 / FR7.3）。
// 读接口照旧 silent：评论区取不到数据时，详情页要把「为什么没有评论」就地写在评论区里，
// 而不是在正文上面盖一条全局红条。写接口刻意不 silent —— 被限流（10010）、被当日配额
// 挡住（30003）时，后端那句 msg 就是给用户看的下一句话，没必要让页面再翻译一遍。
export const listComments = (id, params) => http.get('/posts/' + id + '/comments', { params, silent: true })
export const addComment = (id, payload) => http.post('/posts/' + id + '/comments', payload)

// ---------------- 停留时长上报（任务 T4.17 · 需求 FR5.1「停留 ≥3s 才算一次浏览」）----------------
/** 与后端 UserActionCatalog.VIEW_MIN_DURATION_MS 同值：界面上那句「还差几秒」用它算，阈值本身仍在服务端。 */
export const VIEW_MIN_DURATION_MS = 3000

/**
 * 上报「这条帖我看了多久、有没有滚到底」，对应 POST /api/posts/{id}/read。
 *
 * 为什么这一条不走 axios：它主要是在 pagehide / visibilitychange(hidden) 那一刻发出的，
 * 那时页面正在被卸载，XHR 的回调没有机会回来，浏览器也会掐掉在途请求 ——
 * 于是「读完最后一段就关标签页」这一整个场景的数据全丢，而那恰恰是停留时长最需要的一笔。
 * 只有 fetch(..., { keepalive: true }) 与 navigator.sendBeacon 带「离开后仍送达」的语义。
 * 没选 sendBeacon 是它为 POST 只能带 FormData/Blob 且设不了自定义头，而这条接口按
 * Authorization 认人：匿名的停留后端不记分，发出去也只是发出去。
 *
 * 失败一律吞掉返回 null：埋点是旁路，旁路的抖动不该在界面上惊动任何人。
 */
export function reportReadProgress(id, durationMs, completed) {
  const token = localStorage.getItem('mindisle_token')
  if (!token) return Promise.resolve(null)
  const ms = Math.max(0, Math.round(Number(durationMs) || 0))
  const body = JSON.stringify({ durationMs: ms, completed: completed === true })
  return fetch('/api/posts/' + encodeURIComponent(String(id)) + '/read', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: body,
    keepalive: true
  })
    .then((res) => {
      if (!res || !res.ok) return null
      return res.json().catch(() => null)
    })
    .catch(() => null)
}

/** 评论字数上限：与后端 mindisle.post.max-comment-chars（默认 1000）和 DDL 的 VARCHAR(1000) 同源。 */
export const COMMENT_MAX_CHARS = 1000

/** 一级评论每页条数：与后端 PageQuery.DEFAULT_SIZE 同值，改这里要同时确认后端 normalize 的 1..50 区间。 */
export const COMMENT_PAGE_SIZE = 20

/** 单棵楼中楼一次能给完的回复上限：与后端 CommentService.MAX_SUBTREE_REPLIES 同值，只用来决定「先看到这儿」这句话要不要说。 */
export const COMMENT_SUBTREE_CAP = 500

/**
 * 按码点计数，不是按 String.length。
 * 后端用 codePointCount（一个 emoji 算 1 个字），前端若用 length 会算成 2 个：
 * 于是出现「界面说还剩 3 个字，提交却被 10001 拒了」。宁可这里严格一点也不能松，
 * 但既然要严格就两边同一口径。代理对用 Array.from 天然拆开。
 */
export function commentLength(value) {
  return Array.from(value || '').length
}

// 举报（任务 T3.11 · FR4.7、FR4.4、BR6）。
// 刻意不 silent：举报的失败原因（没登录 10002 / 这条你看不到 30001 / 理由不在六类里 10001）
// 恰好就是界面上该说的那句话，页面再翻译一遍只会分叉。而「你已经举报过了」和
// 「已达阈值、这条转人工了」后端是当「成功」返回的（恒 200，区别写在
// data.duplicated / data.escalated / data.reportCnt 里），所以这里不需要为一个正常结果 catch。
export const reportPost = (id, payload) => http.post('/posts/' + id + '/report', payload)

/**
 * 六类举报理由：与后端 ReportService.REASONS 的键与顺序逐字同源（手册 §6.5 第 6 条）。
 * desc 只是给单选框当副标题的人话，不参与提交；提交只认 value。
 * 两边任何一侧单独加一类，另一侧就会收到 10001 —— 加理由必须同时改后端白名单。
 */
export const POST_REPORT_REASONS = [
  { value: 'spam', label: '广告', desc: '营销、刷屏、引流' },
  { value: 'abuse', label: '攻击辱骂', desc: '人身攻击、嘲讽、霸凌' },
  { value: 'sexual', label: '色情低俗', desc: '色情、血腥、令人不适的图片' },
  { value: 'privacy', label: '泄露隐私', desc: '真实姓名、照片、账号、联系方式' },
  { value: 'self-harm', label: '自伤风险', desc: 'TA 可能正在伤害自己' },
  { value: 'other', label: '违法或其他', desc: '以上都不是，请在描述里写清楚' }
]

/** 举报描述字数上限：与后端 mindisle.report.max-description-chars（默认 200）同源，按码点计。 */
export const REPORT_DESC_MAX = 200

/** 截图证据张数上限：与后端 mindisle.report.max-evidence-images（默认 3）同源。 */
export const REPORT_EVIDENCE_MAX = 3

// 开关到动作名的映射只留这一份：PostCard、详情页、composable 里各自写一遍 ternary，
// 就是给未来「某一处忘了取反」留位置。
export const POST_ACTION_PAIRS = {
  like: { on: 'like', off: 'unlike', flag: 'liked', cnt: 'likeCnt' },
  collect: { on: 'collect', off: 'uncollect', flag: 'collected', cnt: 'collectCnt' }
}

// 出参里出现的帖子形式，与后端 PostService.TYPES 一致；界面下拉只认这三个值。
export const POST_TYPES = [
  { value: 'normal', label: '分享' },
  { value: 'hole', label: '树洞' },
  { value: 'help', label: '求助' }
]

// post.status 的 8 态：与 sql/04_community.sql 里 post.status 的 ENUM 逐字一致，
// 集合上也与后端 PostQueryService.STATUS_FILTERS 一致（读侧允许筛的态 = DDL 的全部态）。
// 列表项现在带 status 回来，「我的帖子」要靠它区分「还在审核」和「这条没发出去」，
// 所以中文名只能有一份：写在 api 层、由 PostCard 和页面共用，否则两处措辞迟早分叉。
export const POST_STATUSES = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'MACHINE_REVIEW', label: '机审中' },
  { value: 'HUMAN_REVIEW', label: '人工审核中' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'REJECTED', label: '未通过' },
  { value: 'APPEALING', label: '申诉中' },
  { value: 'TAKEDOWN', label: '已下架' },
  { value: 'DELETED', label: '已删除' }
]

const STATUS_LABELS = POST_STATUSES.reduce(function (acc, x) {
  acc[x.value] = x.label
  return acc
}, {})

/** 未知值原样回显：后端加态时这里会显示英文原值，看得见才有机会被发现；折叠成「其他」是把变化藏起来。 */
export function statusLabel(value) {
  if (!value) return '未知状态'
  return STATUS_LABELS[value] || String(value)
}

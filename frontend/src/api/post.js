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

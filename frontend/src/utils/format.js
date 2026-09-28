/** 后端 LocalDateTime 出参是 ISO 串（Jackson 关了 WRITE_DATES_AS_TIMESTAMPS），不带时区即本地时间。 */
export function toDate(value) {
  if (value === null || value === undefined || value === '') return null
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value
  if (typeof value === 'number') {
    const d = new Date(value)
    return Number.isNaN(d.getTime()) ? null : d
  }
  // MySQL 的 datetime 偶尔会序列化成 "2026-09-20 17:29:59"（带空格），Safari 的 Date 解析器不认，
  // 手工把分隔符换成 T，比引一个 dayjs 划算。
  const s = String(value).trim().replace(' ', 'T')
  const d = new Date(s)
  return Number.isNaN(d.getTime()) ? null : d
}

const MIN = 60 * 1000
const HOUR = 60 * MIN
const DAY = 24 * HOUR

/** 列表里「3 分钟前」这种相对时间：一周以内说相对，之外说日期，跨年补年份。 */
export function fromNow(value, now) {
  const d = toDate(value)
  if (!d) return ''
  const base = now || Date.now()
  const diff = base - d.getTime()
  if (diff < 0) return d.getMonth() + 1 + '月' + d.getDate() + '日'
  if (diff < MIN) return '刚刚'
  if (diff < HOUR) return Math.floor(diff / MIN) + ' 分钟前'
  if (diff < DAY) return Math.floor(diff / HOUR) + ' 小时前'
  if (diff < 7 * DAY) return Math.floor(diff / DAY) + ' 天前'
  const sameYear = d.getFullYear() === new Date(base).getFullYear()
  return sameYear
    ? d.getMonth() + 1 + '月' + d.getDate() + '日'
    : d.getFullYear() + '年' + (d.getMonth() + 1) + '月' + d.getDate() + '日'
}

/** 到期倒计时：树洞的「还有 6 天 3 小时消失」。已过期返回「已销毁」，由服务端过滤、这里只兜显示。 */
export function countdown(value, now) {
  const d = toDate(value)
  if (!d) return ''
  const left = d.getTime() - (now || Date.now())
  if (left <= 0) return '已到期'
  if (left < HOUR) return '还有 ' + Math.max(1, Math.round(left / MIN)) + ' 分钟消失'
  if (left < DAY) return '还有 ' + Math.round(left / HOUR) + ' 小时消失'
  return '还有 ' + Math.floor(left / DAY) + ' 天 ' + Math.round((left % DAY) / HOUR) + ' 小时消失'
}

/** 绝对时间，详情页第 一次 与销毁时间用得上。 */
export function fmtDateTime(value) {
  const d = toDate(value)
  if (!d) return '-'
  const pad = (n) => (n < 10 ? '0' + n : String(n))
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
    + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes())
}

/** 气泡右下角的时刻：只到分钟。会话里同一天来回十几条，带上日期会把气泡撑得比正文还长。 */
export function fmtChatTime(value) {
  const d = toDate(value)
  if (!d) return ''
  const pad = (n) => (n < 10 ? '0' + n : String(n))
  return pad(d.getHours()) + ':' + pad(d.getMinutes())
}

/**
 * 聊天记录里的日期分隔条：今天 / 昨天 / 9月26日 / 2025年12月3日。
 *
 * 为什么不用 fromNow：相对时间（「3 分钟前」）在气泡流里逐条出现时，读者没法回答
 * 「我们是从哪天开始聊的」，而这正是回看聊天记录时唯一想知道的事。所以这里要的是
 * 「分组用的绝对日期」，只在跨天时打印一次。昨天单独说，是因为私信场景里
 * 「昨天」比「9月27日」更接近人的说法，而跨年才补年份（与 fromNow 同一口径）。
 */
export function fmtChatDay(value, now) {
  const d = toDate(value)
  if (!d) return ''
  const base = toDate(now) || new Date()
  const dayStart = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const diffDays = Math.round((dayStart(base) - dayStart(d)) / DAY)
  if (diffDays === 0) return '今天'
  if (diffDays === 1) return '昨天'
  if (diffDays === 2) return '前天'
  const md = (d.getMonth() + 1) + '月' + d.getDate() + '日'
  return d.getFullYear() === base.getFullYear() ? md : d.getFullYear() + '年' + md
}

/** 计数过万折成 1.2万：列表里一个 7 位数字会把卡片撑变形。 */
export function fmtCount(n) {
  const v = Number(n)
  if (!Number.isFinite(v)) return '0'
  if (v < 10000) return String(v)
  return (v / 10000).toFixed(1).replace(/\.0$/, '') + '万'
}

/**
 * 话题热度（topic.hot_score）的显示口径：一位小数，拿不到数就画「-」。
 * 为什么从「各页私有」提成共享工具：广场与搜索页原先各有一份一模一样的实现，
 * 话题详情页（T3.8）照抄模板里那一行时漏抄了函数本体，于是整个组件在渲染阶段抛
 * TypeError: _ctx.fmtHot is not a function —— 头图和帖流一起消失，界面只剩一片空白。
 * 一个「少一个 import」级别的错，症状却是白屏，只有真跑一遍 DOM 才看得见。
 */
export function fmtHot(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(1) : '-'
}


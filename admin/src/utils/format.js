// 管理端公用的取数/格式化小工具。放在一处有两个具体原因：
// ① 后端 LocalDateTime 序列化出来是 "2026-09-29T06:06:2"（秒为 0 时会被 Jackson 省掉，
//    实测见 /api/admin/tickets/1 的 createdAt），所以不能按 "yyyy-MM-dd HH:mm:ss" 定长截取，
//    截了会出现 "...T06:06" 这种半截时间；统一走 Date.parse 之后本地化输出。
// ② 状态色在 A4/A5/A6/A7/A9 五页都要用同一套语义（绿=通过/正常、红=驳回/超时/越权被拒），
//    分散写五个 map 的话，第五页一定会写成第六种颜色。

/** ISO 串或 Date → "MM-dd HH:mm"；解析失败就原样显示，绝不显示 NaN-NaN。 */
export function fmtTime (v, withSec) {
  if (!v) return '—'
  const d = new Date(v)
  if (isNaN(d.getTime())) return String(v)
  const p = (n) => String(n).padStart(2, '0')
  const base = p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes())
  return withSec ? base + ':' + p(d.getSeconds()) : base
}

export function fmtDate (v) {
  if (!v) return '—'
  const d = new Date(v)
  if (isNaN(d.getTime())) return String(v)
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0')
}

/** el-date-picker 的值要 "yyyy-MM-ddTHH:mm:ss" 才能被 Spring 的 LocalDateTime 反序列化接住。 */
export function toLocalIso (v) {
  if (!v) return null
  const d = v instanceof Date ? v : new Date(v)
  if (isNaN(d.getTime())) return null
  const p = (n) => String(n).padStart(2, '0')
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
    + 'T' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds())
}

export function fmtNum (v) {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  if (isNaN(n)) return String(v)
  return n.toLocaleString('zh-CN')
}

/** SLA 剩余分钟 → 人话。负数是「已经超时多久」，这个符号在工单列表里是判据，不是装饰。 */
export function fmtSla (minutesLeft) {
  if (minutesLeft === null || minutesLeft === undefined) return { text: '无时限', tone: 'info', over: false }
  const m = Number(minutesLeft)
  if (isNaN(m)) return { text: '—', tone: 'info', over: false }
  const abs = Math.abs(m)
  const span = abs >= 60 ? Math.floor(abs / 60) + ' 小时 ' + (abs % 60) + ' 分' : abs + ' 分'
  if (m < 0) return { text: '超时 ' + span, tone: 'danger', over: true }
  if (m < 30) return { text: '剩 ' + span, tone: 'warning', over: false }
  return { text: '剩 ' + span, tone: 'success', over: false }
}

const STATUS_TONE = {
  // 审核队列 / 帖子 / 举报 / 申诉（大写族）
  PENDING: 'warning', PROCESSING: 'primary', PASSED: 'success', REJECTED: 'danger',
  ESCALATED: 'danger', APPEALING: 'warning', MACHINE_REVIEW: 'info', HUMAN_REVIEW: 'primary',
  PUBLISHED: 'success', TAKEDOWN: 'danger', ACCEPTED: 'success', HANDLED: 'success',
  // 工单（小写族，库里就是这样存的）
  pending: 'warning', claimed: 'primary', doing: 'primary', closed: 'success',
  false_positive: 'info', expired: 'danger',
  // 用户
  ACTIVE: 'success', MUTED: 'warning', BANNED: 'danger', DELETED: 'info',
  // 留痕结果
  SUCCESS: 'success', FAIL: 'warning', DENIED: 'danger',
  // 风险等级
  L0: 'info', L1: 'info', L2: 'warning', L3: 'danger'
}
export function statusTone (s) { return STATUS_TONE[s] || 'info' }

export const LEVEL_TEXT = { black: '黑名单', grey: '灰名单', crisis: '危机词' }
export const ACTION_TEXT = {
  BLOCK: '直接拦截', REVIEW: '转人工审核', WARN: '提示警告', ESCORT: '危机护航'
}

/** 把命中词在正文里标出来：返回 [{t, hit}] 片段，模板用 v-for 渲染 <mark>。 */
export function highlight (text, words) {
  const src = String(text || '')
  const list = (words || []).filter((w) => w && String(w).length)
  if (!list.length || !src) return [{ t: src, hit: false }]
  const sorted = list.map(String).sort((a, b) => b.length - a.length)
  const out = []
  let i = 0
  while (i < src.length) {
    const w = sorted.find((x) => src.startsWith(x, i))
    if (w) {
      out.push({ t: w, hit: true })
      i += w.length
    } else {
      const next = sorted.reduce((acc, x) => {
        const at = src.indexOf(x, i)
        return at >= 0 && at < acc ? at : acc
      }, src.length)
      out.push({ t: src.slice(i, next), hit: false })
      i = next
    }
  }
  return out
}

/** D13：密钥类参数的值在界面上、在论文截图里都只能是掩码。真值只留在库里。 */
const SECRET_KEY = /(api[_-]?key|secret|token|password|passwd)/i
export function maskValue (cfgKey, value) {
  const v = String(value === null || value === undefined ? '' : value)
  if (!SECRET_KEY.test(String(cfgKey || '')) && !/^sk-/.test(v)) return v
  if (!v) return '(空)'
  return 'sk-***' + (v.length > 6 ? '（长度 ' + v.length + '）' : '')
}

/** 错误对象的统一描述：axios 的 err.message 是人话，但没有 code 时必须说出来而不是显示 undefined。 */
export function errText (e) {
  if (!e) return '未知错误'
  const code = e.code === undefined || e.code === null ? '' : '（code=' + e.code + '）'
  return (e.message || String(e)) + code
}
// 一次性形状探针：把阶段 6 各接口返回体的键名打出来，供 admingate6.mjs 写断言时抄。
const BASE = process.env.APIBASE || 'http://127.0.0.1:18080'
const CAP = '00000000000000000000000000000000'
let T = ''
async function g (p) {
  const r = await fetch(BASE + p, { headers: T ? { Authorization: 'Bearer ' + T } : {} })
  const j = await r.json().catch(() => null)
  return j
}
async function post (p, b) {
  const r = await fetch(BASE + p, { method: 'POST', headers: { 'Content-Type': 'application/json;charset=utf-8', Authorization: 'Bearer ' + T }, body: JSON.stringify(b || {}) })
  return r.json().catch(() => null)
}
const lg = await post('/api/admin/auth/login', { username: process.env.U || 'gate6_super', password: 'Test1234', captchaId: CAP, captchaCode: 'ZZZZ' })
T = lg.data && lg.data.accessToken
if (!T) { console.log('LOGIN FAIL ' + JSON.stringify(lg)); process.exit(1) }
const keys = (v) => Array.isArray(v) ? (v.length ? '[' + Object.keys(v[0]).join(',') + '] len=' + v.length : '[]') : (v && typeof v === 'object' ? Object.keys(v).join(',') : JSON.stringify(v))
const list = ['/api/admin/dashboard/stats','/api/admin/dashboard/emotion-board','/api/admin/dashboard/hot-topics','/api/admin/dashboard/grade-board','/api/admin/dashboard/hour-heatmap','/api/admin/dashboard/emotion-labels','/api/admin/dashboard/ai-usage','/api/admin/audit/tasks?status=PENDING&page=1&size=3','/api/admin/audit/status-counts','/api/admin/audit/assignee-stats','/api/admin/tickets?page=1&size=3','/api/admin/tickets/board','/api/admin/tickets/overdue','/api/admin/appeals?page=1&size=3','/api/admin/appeals/pending-count','/api/admin/users?keyword=gate6&page=1&size=3','/api/admin/content/posts?page=1&size=3','/api/admin/content/reports?page=1&size=3','/api/admin/content/reports/pending-count','/api/admin/configs','/api/admin/words?page=1&size=3','/api/admin/word-groups','/api/admin/dict/status','/api/admin/logs?page=1&size=3','/api/admin/logs/actions','/api/admin/logs/reveals?days=30','/api/admin/users/reveal-count?days=30']
for (const p of list) {
  const j = await g(p)
  console.log(p.padEnd(52) + ' => code=' + j.code + ' data=' + keys(j.data))
}
const tr = await post('/api/admin/dict/trial', { text: 'gate6probe 我想消失', side: 'user' })
console.log('dict/trial => ' + keys(tr.data))
const sp = await post('/api/admin/audit/sync-posts', {})
console.log('audit/sync-posts => ' + JSON.stringify(sp.data))
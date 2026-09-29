// 阶段 6 管理端接口冒烟（Gate6 的 A 线：纯 HTTP，不进浏览器）
// 跑法：APIBASE=http://127.0.0.1:18080 node probe/admin-smoke.mjs
// 前置：库里有一个 role=SUPER 的账号（本脚本会用 GATE_ADMIN/GATE_PWD 登录）。
import fs from 'node:fs'
import path from 'node:path'

const APIBASE = process.env.APIBASE || 'http://127.0.0.1:18080'
const ACCT = process.env.GATE_ADMIN || 'gate6_super'
const PWD = process.env.GATE_PWD || 'Test1234'
const CAPTCHA_ID = '00000000000000000000000000000000'
const OUT = process.env.SMOKE_OUT || 'E:/codex workspace/_cache/009_mindisle/admin-smoke.json'
const rows = []
let token = ''

async function call (method, p, body, opt = {}) {
  const headers = { 'Content-Type': 'application/json;charset=utf-8' }
  if (token && !opt.noAuth) headers.Authorization = 'Bearer ' + token
  const t0 = Date.now()
  let status = 0, json = null, text = ''
  try {
    const r = await fetch(APIBASE + p, { method, headers, body: body ? JSON.stringify(body) : undefined })
    status = r.status
    text = await r.text()
    try { json = JSON.parse(text) } catch (e) { json = null }
  } catch (e) {
    text = 'FETCH-ERR ' + e.message
  }
  const cost = Date.now() - t0
  const msg = json && json.msg ? json.msg : (json ? '' : text.slice(0, 120))
  const code = json ? json.code : null
  return { p, method, status, code, msg, cost, text, body: json && json.data !== undefined ? json.data : null }
}

// 字节级判据（BOM / 编码 / 长度）不能走 call()：fetch 的 Response.text()
// 按 UTF-8 解码时会把开头的一个 U+FEFF 剥掉，于是「文本里没有 BOM」和
// 「字节里没有 BOM」在这条链路上根本不是一回事——上一轮我差点因此去给
// CsvExportService 补一个它本来就有的 BOM。导出三线改走这个取原始字节的变体。
async function callBin (p) {
  const t0 = Date.now()
  const r = await fetch(APIBASE + p, { headers: { Authorization: 'Bearer ' + token } })
  const buf = Buffer.from(await r.arrayBuffer())
  const hs = {}
  r.headers.forEach((v, k) => { hs[k] = v })
  return { p, status: r.status, headers: hs, buf, cost: Date.now() - t0 }
}

function check (name, expectStatus, res, extra) {
  const okStatus = res.status === expectStatus
  const okCode = expectStatus === 200 ? (res.code === 0) : true
  let ok = okStatus && okCode
  let note = ''
  if (extra) {
    const v = extra(res.body, res)
    if (v !== true) { ok = false; note = String(v) }
  }
  rows.push({ name, expectStatus, status: res.status, code: res.code, msg: res.msg, cost: res.cost, ok, note })
  console.log((ok ? 'PASS ' : 'FAIL ') + name.padEnd(46) + ' http=' + res.status + ' code=' + res.code + ' ' + res.cost + 'ms ' + (note || res.msg || ''))
  return ok
}

function need (cond, label) { return cond ? true : 'missing ' + label }

const step = process.env.SMOKE_STEP || 'all'

if (step === 'prep') {
  const r = await call('POST', '/api/auth/register', {
    username: ACCT, password: PWD, nickname: '闸门六超管',
    captchaId: CAPTCHA_ID, captchaCode: 'ZZZZ',
    agreeTerms: true, agreePrivacy: true, agreeSensitive: false, consentVersion: 'v1', grade: 'OTHER', regSource: 'gate6'
  }, { noAuth: true })
  console.log('register http=' + r.status + ' code=' + r.code + ' ' + r.msg)
  if (r.code === 0) console.log('uid=' + (r.body && r.body.user && r.body.user.id))
  process.exit(0)
}

// —— 登录：管理端专用入口，必须校验 role ∈ {ADMIN, SUPER}
let res = await call('POST', '/api/admin/auth/login', { username: ACCT, password: PWD, captchaId: CAPTCHA_ID, captchaCode: 'ZZZZ' }, { noAuth: true })
check('admin/login', 200, res, (b) => need(b && b.accessToken, 'accessToken'))
if (!res.body || !res.body.accessToken) { finish(1); }
token = res.body.accessToken

// —— 只读看板 7 条
res = await call('GET', '/api/admin/dashboard/stats')
check('dashboard/stats', 200, res, (b) => need(b && b.dau !== undefined, 'dau'))
res = await call('GET', '/api/admin/dashboard/emotion-board')
check('dashboard/emotion-board', 200, res, (b) => need(Array.isArray(b && b.daily), 'daily[]'))
res = await call('GET', '/api/admin/dashboard/hot-topics')
check('dashboard/hot-topics', 200, res)
res = await call('GET', '/api/admin/dashboard/grade-board')
check('dashboard/grade-board', 200, res)
res = await call('GET', '/api/admin/dashboard/hour-heatmap')
check('dashboard/hour-heatmap', 200, res)
res = await call('GET', '/api/admin/dashboard/emotion-labels')
check('dashboard/emotion-labels', 200, res)
res = await call('GET', '/api/admin/dashboard/ai-usage')
check('dashboard/ai-usage', 200, res)

// —— 审核队列
res = await call('GET', '/api/admin/audit/tasks?status=PENDING&page=1&size=20')
check('audit/tasks?PENDING', 200, res, (b) => need(b && Array.isArray(b.list), 'list'))
res = await call('GET', '/api/admin/audit/status-counts')
check('audit/status-counts', 200, res)
res = await call('GET', '/api/admin/audit/assignee-stats')
check('audit/assignee-stats', 200, res)
res = await call('POST', '/api/admin/audit/sync-posts', {})
check('audit/sync-posts', 200, res)

// —— 危机工单
res = await call('GET', '/api/admin/tickets?page=1&size=20')
check('tickets list', 200, res)
res = await call('GET', '/api/admin/tickets/board')
check('tickets/board', 200, res)
res = await call('GET', '/api/admin/tickets/overdue')
check('tickets/overdue', 200, res)

// —— 申诉 / 用户 / 内容 / 词库 / 配置
res = await call('GET', '/api/admin/appeals?status=PENDING&page=1&size=20')
check('appeals list', 200, res)
res = await call('GET', '/api/admin/users?keyword=g&page=1&size=5')
check('users list', 200, res)
res = await call('GET', '/api/admin/content/posts?keyword=&page=1&size=5')
check('content/posts', 200, res)
res = await call('GET', '/api/admin/content/reports?page=1&size=5')
check('content/reports', 200, res)
res = await call('GET', '/api/admin/configs')
check('configs list', 200, res, (b) => need(Array.isArray(b), 'array'))
res = await call('GET', '/api/admin/words?keyword=&page=1&size=5')
check('words list', 200, res)
res = await call('GET', '/api/admin/word-groups')
check('word-groups', 200, res, (b) => need(Array.isArray(b), 'array'))
res = await call('GET', '/api/admin/dict/status')
check('dict/status', 200, res)
res = await call('POST', '/api/admin/dict/trial', { text: '我很想消失', side: 'user' })
check('dict/trial', 200, res)

// —— 留痕与导出（FR7.1 / A9 / D9）
res = await call('POST', '/api/admin/dict/reload', {})
check('dict/reload(留痕)', 200, res, (b) => need(b && b.version, 'version'))
res = await call('GET', '/api/admin/logs?page=1&size=10')
check('logs list', 200, res, (b) => need(b && Array.isArray(b.list), 'list'))
res = await call('GET', '/api/admin/logs/actions')
check('logs/actions', 200, res)
res = await call('GET', '/api/admin/logs/reveals?days=30')
check('logs/reveals', 200, res)
// 表头逐字抄自 web/AdminLogController.java 的三个 XXX_COLUMNS 常量；
// 列序只由 headers 决定，所以用全等而不是 includes（includes 会放过顺序错）。
const CSV_HEADS = {
  tickets: 'id,level,user_id,source_type,source_id,risk_score,trigger_words,status,assignee_id,claim_at,sla_at,close_at,handle_note,followup_at,created_at',
  'ai-usage': 'day,scene,model,call_cnt,tokens,cost_cent,fail_cnt,avg_latency_ms',
  'op-logs': 'id,created_at,operator_id,operator_role,action,result,target,target_id,detail,ip,user_agent'
}
for (const topic of ['tickets', 'ai-usage', 'op-logs']) {
  const rb = await callBin('/api/admin/export/' + topic + '?days=30')
  checkCsv('export/' + topic, rb, CSV_HEADS[topic], 2)
}

// —— 鉴权负例：无 token 打管理端必须 401/403，不能 200
res = await call('GET', '/api/admin/dashboard/stats', null, { noAuth: true })
const denied = res.status === 401 || res.status === 403
rows.push({ name: 'no-token 必须被拒', expectStatus: 401, status: res.status, code: res.code, msg: res.msg, cost: res.cost, ok: denied, note: denied ? '' : 'expected 401/403' })
console.log((denied ? 'PASS ' : 'FAIL ') + 'no-token 必须被拒'.padEnd(46) + ' http=' + res.status)

finish(0)

function finish (forceFail) {
  const pass = rows.filter((r) => r.ok).length
  const fail = rows.length - pass
  fs.mkdirSync(path.dirname(OUT), { recursive: true })
  fs.writeFileSync(OUT, JSON.stringify({ at: new Date().toISOString(), base: APIBASE, acct: ACCT, total: rows.length, pass, fail, rows }, null, 1), 'utf8')
  console.log('=== admin-smoke 总计 ' + rows.length + ' PASS=' + pass + ' FAIL=' + fail + ' → ' + OUT)
  process.exit(fail > 0 || forceFail ? 1 : 0)
}
// CSV 导出三线专用判据：四件事一起看——BOM 字节、CRLF、表头全等、数据行。
// 注意这里的 res 是 callBin 的产物（带 buf），不再是 call 那个割了 BOM 的 text。
function checkCsv (name, res, header, minLines) {
  const bom = res.buf.length >= 3 && res.buf[0] === 0xEF && res.buf[1] === 0xBB && res.buf[2] === 0xBF
  const body = bom ? res.buf.subarray(3).toString('utf8') : res.buf.toString('utf8')
  const lines = body.split(/\r?\n/).filter((x) => x.length > 0)
  const crlf = body.indexOf('\r\n') >= 0
  const head = lines.length > 0 ? lines[0] : ''
  const ctype = res.headers['content-type'] || ''
  const ok = res.status === 200 && bom && head === header && lines.length >= minLines && crlf && /text\/csv/i.test(ctype)
  const msg = 'bom=' + bom + ' crlf=' + crlf + ' lines=' + lines.length + ' bytes=' + res.buf.length
  rows.push({ name, expectStatus: 200, status: res.status, code: null, msg, cost: res.cost, ok, note: ok ? '' : msg + ' head=' + head.slice(0, 60) })
  console.log((ok ? 'PASS ' : 'FAIL ') + name.padEnd(46) + ' http=' + res.status + ' ' + msg + ' ' + res.cost + 'ms')
  return ok
}

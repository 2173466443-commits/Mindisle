// Gate 7 的两条延迟判据现量取证（手册 §10.5 D6 行「feed ≤ 200ms」+ §10.6 行「相似位 P95 ≤ 200ms」）
// 范本：docs/gate/阶段3/perf-posts.mjs（同一套限流口径、同一套分位数算法，读者能横向对比）。
//
// 【这个脚本最容易自证的东西就是「快」，所以三条防自欺的口径写在最前面】
// 1) 限流器跑在业务逻辑之前（T2.8 的 HandlerInterceptor，固定窗口 60s，按登录身份 60 次/分，
//    application.yml rate-limit.user-per-minute）。裸跑串行会量到「P95=2ms」的漂亮数字 ——
//    那 2ms 是 429 被挡回来的耗时，SQL 压根没执行。所以：每窗口只花 BUDGET 枪、只有 HTTP 200
//    进样本、撞上 429 立刻作废当前窗口补一枪，并把 X-RateLimit-Remaining 记进证据。
// 2) 主口径直连 8080，不走 Vite 5173。dev server 的单线程代理会把 XHR 串起来排队，
//    量出来的尾延迟说不清是接口的账还是前端工具的账。F5 专门再打一遍 5173 做对照，两行相减就是代理开销。
// 3) 串行单并发测的是「单次延迟」，不是并发吞吐（50 并发压测按手册排在阶段 8，本条只要求粗测）。
//
// 【夹具从哪儿来，为什么不是挑出来的】样本里的帖子 id 全部读自同目录 shot-manifest.json
// —— 那是 Gate 7 取证脚本 recgate7.mjs 刚刚在真浏览器里拍过、判过的同一批帖子：
//   F3 用 similar.itemcfSrc.id + feed.ids18 前 5 条（相似位有 ItemCF 邻居的正常路径）；
//   F4 用 similar.cold.id（没有任何邻居行的冷帖，走的是同话题 + 热读兜底那两层）。
// 读不到这份 manifest 就直接不结论（宁可不测，也不随手抓个 id 假装测过）。
//
// 【本机环境的一条硬口径，必须写在报告头上】6379 未启动 ⇒ recgate7 的 C2 实测 cacheMode=local
// （Caffeine 兜底）。这一组数字因此是**本地缓存口径**的延迟，不是 Redis 口径；
// 阶段 8 把 Redis 起来之后必须重跑这支脚本，两版数字并排放进论文才有资格说「缓存命中」。
//
// 跑法：node "docs/gate/阶段7/perf-feed.mjs"（先跑 recgate7 产出 manifest）
//   换账号：PERF_FEED_USER / PERF_FEED_PW 环境变量
// 产物：perf-feed.log（人读）+ perf-feed.json（结构化，手册与 README 引用它，不手抄）
// 退出码：业务场景（F1~F4）任一 P95 > 200ms，或任一枪非 200/429，即 1。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const DIRECT = process.env.PERF_FEED_BASE || 'http://127.0.0.1:8080'
const PROXY = 'http://127.0.0.1:5173'
const MANIFEST = path.join(HERE, 'shot-manifest.json')
// 账号用 demo02（不是 recgate7 的 demo01）：限流按身份计窗，两个脚本同跑会互相把对方打成 429，
// 那测出来的就是限流器的性能而不是接口的性能（踩坑第 12 轮原话）。口令走环境变量，默认值是
// 需求文档 §2 写白的 dev 种子口令，仅本地测试库有效。
const ACCOUNT = {
  username: process.env.PERF_FEED_USER || 'demo02',
  password: process.env.PERF_FEED_PW || 'Test1234'
}
const WINDOW_MS = 60000
const LIMIT = 60
const BUDGET = 40
const WARM = 3
const THRESHOLD = 200

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let bucket = -1
let used = 0
async function budget () {
  const now = Date.now()
  const b = Math.floor(now / WINDOW_MS)
  if (b !== bucket) { bucket = b; used = 0 }
  if (used >= BUDGET) {
    const wait = (bucket + 1) * WINDOW_MS - now + 300
    log('  .. 限流窗口预算 ' + BUDGET + '/' + LIMIT + ' 用尽，睡 ' + wait + 'ms 等下一个自然分钟')
    await sleep(wait)
    bucket = Math.floor(Date.now() / WINDOW_MS); used = 0
  }
  used++
}
async function rotateWindow (reason) {
  const wait = (Math.floor(Date.now() / WINDOW_MS) + 1) * WINDOW_MS - Date.now() + 300
  log('  !! ' + reason + '，作废当前窗口，睡 ' + wait + 'ms')
  await sleep(wait)
  bucket = Math.floor(Date.now() / WINDOW_MS); used = 0
}

// ── 本体 ───────────────────────────────────────────────────────────────────────
const LINES = []
function log (s) { console.log(s); LINES.push(s) }

async function login (base, username, password) {
  await budget()
  const r = await fetch(base + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json;charset=utf-8' },
    body: JSON.stringify({
      username, password,
      captchaId: '00000000000000000000000000000000', captchaCode: 'ZZZZ'
    })
  })
  const j = await r.json().catch(() => ({}))
  const token = j && j.data && j.data.accessToken
  if (!token) {
    throw new Error('登录取不到 token（' + base + ' HTTP ' + r.status +
      ' code=' + (j && j.code) + '）：环境问题，不测')
  }
  return token
}

// 一次请求：只记「状态码 + 耗时 + 返回条数」，绝不在计时区间里解析 JSON 之外的东西。
// 响应体先 text() 再 parse —— 直接 res.json() 会把读流的时间算在计时之外，分位数会偏乐观。
async function once (url, token) {
  await budget()
  const t0 = Date.now()
  const res = await fetch(url, { headers: { Authorization: 'Bearer ' + token } })
  const text = await res.text()
  const cost = Date.now() - t0
  let code = null, msg = null, total = null, size = null, head = null, payload = null
  try {
    const j = JSON.parse(text)
    payload = j
    code = j && j.code
    msg = j && j.msg
    const d = j && j.data
    // 两个接口的 data 形状不一样，是现查的：/api/feed/recommend 是 PageResult（data.list），
    // /api/posts/{id}/similar 直接是数组；FeedItem 又比 PostListItem 多包一层 post（id 在
    // data.list[i].post.id / data[i].post.id）。少包这一层，取到的 id 永远是 undefined，
    // 「返回条数」和「批次是否非空」两条自证判据就一起失效了（阶段 7 取证踩过）。
    if (Array.isArray(d)) {
      size = d.length
      head = d[0] && d[0].post && d[0].post.id
    } else if (d && Array.isArray(d.list)) {
      size = d.list.length
      total = typeof d.total === 'number' ? d.total : null
      head = d.list[0] && d.list[0].post && d.list[0].post.id
    }
  } catch (e) { /* 非 JSON 交给 status/code 记账 */ }
  return {
    cost, status: res.status, code, msg, total, size, head, bytes: text.length,
    remain: res.headers.get('x-ratelimit-remaining'), payload
  }
}

function pick (sorted, q) {
  return sorted[Math.min(sorted.length - 1, Math.max(0, Math.ceil(q * sorted.length) - 1))]
}

function summarize (name, rows, throttled, business) {
  const ok = rows.filter((r) => r.status === 200)
  if (!ok.length) throw new Error(name + '：一个 200 样本都没有（全是限流/错误），不结论')
  const costs = ok.map((r) => r.cost).sort((a, b) => a - b)
  const s = {
    scene: name, business,
    samples: ok.length, throttled,
    pageSize: ok[0].size, avgSize: Math.round(ok.reduce((a, r) => a + r.size, 0) / ok.length * 100) / 100,
    emptyResponses: ok.filter((r) => !r.size).length,
    distinctHeadIds: Array.from(new Set(ok.map((r) => r.head))).length,
    min: costs[0], p50: pick(costs, 0.5), p95: pick(costs, 0.95), p99: pick(costs, 0.99),
    max: costs[costs.length - 1],
    avg: Math.round(costs.reduce((a, b) => a + b, 0) / costs.length),
    avgBytes: Math.round(ok.reduce((a, r) => a + r.bytes, 0) / ok.length),
    remainMin: Math.min.apply(null, ok.map((r) => Number(r.remain)).filter((x) => Number.isFinite(x)))
  }
  // 空响应不算测过：接口没干活的时候它最快，也最没有意义。
  if (s.emptyResponses > 0) {
    throw new Error(name + '：' + s.emptyResponses + '/' + s.samples + ' 枪返回 0 条 —— 延迟样本里混进了「什么都没算」的请求，不结论')
  }
  log(
    name.padEnd(26) +
    ' P50=' + String(s.p50).padStart(4) + 'ms' +
    ' P95=' + String(s.p95).padStart(4) + 'ms' +
    ' P99=' + String(s.p99).padStart(4) + 'ms' +
    ' max=' + String(s.max).padStart(5) + 'ms' +
    ' avg=' + String(s.avg).padStart(4) + 'ms' +
    ' n=' + String(s.samples).padStart(3) +
    ' 被限流=' + String(s.throttled).padStart(2) +
    ' 条/页=' + String(s.pageSize).padStart(2) +
    ' 首条位不同id=' + String(s.distinctHeadIds).padStart(2) +
    ' 响应≈' + String(s.avgBytes).padStart(5) + 'B' +
    ' 余量最小=' + s.remainMin
  )
  return s
}

function loadFixtures () {
  if (!fs.existsSync(MANIFEST)) {
    throw new Error('读不到 ' + MANIFEST + '：先跑 frontend/probe/recgate7.mjs 产出 manifest。' +
      '本脚本拒绝自己挑 id —— 随手抓一个 id 测出来的「相似位很快」可能测的正是那条冷启动路径，或者反过来。')
  }
  const m = JSON.parse(fs.readFileSync(MANIFEST, 'utf8'))
  const ids18 = m.feed && Array.isArray(m.feed.ids18) ? m.feed.ids18 : []
  const itemcfId = m.similar && m.similar.itemcfSrc && m.similar.itemcfSrc.id
  const coldId = m.similar && m.similar.cold && m.similar.cold.id
  if (!ids18.length || !itemcfId || !coldId) {
    throw new Error('manifest 里 feed.ids18 / similar.itemcfSrc.id / similar.cold.id 不齐' +
      '（ids18=' + ids18.length + ' itemcf=' + itemcfId + ' cold=' + coldId + '），不结论')
  }
  return { m, ids18, itemcfId, coldId }
}

const fx = loadFixtures()
// F3 轮换 6 个帖子：相似位有 ItemCF 邻居的正常路径。首个用 manifest 里那条被判据钉过的
// itemcfSrc，其余从 feed.ids18 里补足（去掉重复），保证「同一条帖子被打分器偏爱」不成为结论前提。
const similarIds = [fx.itemcfId].concat(fx.ids18.filter((x) => x !== fx.itemcfId)).slice(0, 6)
// 每个 url 多带一个 &r=<序号>：undici 没有 HTTP 缓存，但 F5 走的是 Vite dev server，
// 加它才能保证「20 枪打到同一条 URL」不被中间层合并/复用。r 不是 PageQuery 的字段，
// Spring 绑定时忽略，SQL 与不带它时完全一致（已现查：带 r 与不带 r 的 size/total/首条 id 相同）。
const SCENES = [
  { name: 'F1 推荐流首屏', n: 40, business: true, url: (i, w) => DIRECT + '/api/feed/recommend?size=6&page=1&r=' + (w ? 'w' : '') + i },
  { name: 'F2 推荐流深翻 page=4', n: 25, business: true, url: (i, w) => DIRECT + '/api/feed/recommend?size=6&page=4&r=' + (w ? 'w' : '') + i },
  { name: 'F3 相似位(有CF邻居)', n: 40, business: true, url: (i, w) => DIRECT + '/api/posts/' + similarIds[i % similarIds.length] + '/similar?size=6&r=' + (w ? 'w' : '') + i },
  { name: 'F4 相似位(冷帖兜底)', n: 25, business: true, url: (i, w) => DIRECT + '/api/posts/' + fx.coldId + '/similar?size=6&r=' + (w ? 'w' : '') + i },
  { name: 'F5 对照·Vite5173代理', n: 20, business: false, url: (i, w) => PROXY + '/api/feed/recommend?size=6&page=1&r=' + (w ? 'w' : '') + i }
]

const started = new Date().toString()
const token = await login(DIRECT, ACCOUNT.username, ACCOUNT.password)
log('# perf-feed @ ' + started)
log('# 主口径 ' + DIRECT + ' 直连（串行单并发 · 账号 ' + ACCOUNT.username +
  ' · 产品限流 ' + LIMIT + ' 次/分 · 本脚本每窗口只花 ' + BUDGET + ' 枪 · 每场景先打 ' + WARM + ' 枪预热不计样本）')
log('# 夹具读自 shot-manifest.json（run=' + fx.m.run + '，产出时刻 ' + fx.m.at + '）：' +
  'F3 轮换 id=' + similarIds.join(',') + ' · F4 冷帖 id=' + fx.coldId)
log('')

const results = []
for (const sc of SCENES) {
  for (let i = 0; i < WARM; i++) await once(sc.url(i, true), token)
  const rows = []
  let throttled = 0
  for (let i = 0; i < sc.n; i++) {
    const r = await once(sc.url(i, false), token)
    if (r.status === 429) {
      throttled++
      await rotateWindow(sc.name + ' 收到 429（该身份的窗口已满，' + BUDGET + ' 枪预算没兜住并发流量）')
      i--                       // 这一枪作废并补一枪：样本数必须说话算数
      continue
    }
    if (r.status !== 200 || r.code !== 0) {
      throw new Error(sc.name + '：HTTP ' + r.status + ' code=' + r.code + ' msg=' + r.msg +
        '（不是限流，是真错误，不结论）')
    }
    rows.push(r)
  }
  results.push(summarize(sc.name, rows, throttled, sc.business))
}

// 顺手取一条环境证据：缓存模式。取不到不影响延迟判据，只影响「这组数字是哪种缓存口径」那句说明。
let cacheMode = null
try {
  const admin = process.env.PERF_FEED_ADMIN || 'gate6_super'
  const adminTok = await login(DIRECT, admin, ACCOUNT.password)
  const st = await once(DIRECT + '/api/admin/rec/status', adminTok)
  cacheMode = st.payload && st.payload.data ? st.payload.data.cacheMode : null
  log('')
  log('# 环境证据：GET /api/admin/rec/status HTTP ' + st.status + ' cacheMode=' + cacheMode +
    '（' + admin + ' 读数）' + (cacheMode === 'local' ? ' ⇒ 本机 6379 未启动，这组数字是**本地缓存口径**，阶段 8 起 Redis 后必须重跑' : ''))
} catch (e) {
  log('')
  log('# 环境证据：管理端 status 取不到（' + e.message + '）——缓存口径那行只能写「未取证」，不改成「已确认」')
}

const business = results.filter((r) => r.business)
const worst = business.length ? Math.max.apply(null, business.map((r) => r.p95)) : null
const pass = business.length === 4 && worst !== null && business.every((r) => r.p95 <= THRESHOLD)
const main = results[0]
const proxy = results.find((r) => !r.business)
log('')
log('# 判定对象＝业务场景 F1~F4，阈值 P95 <= ' + THRESHOLD + 'ms（手册 §10.5「feed ≤ 200ms」/ §10.6「相似位 P95 ≤ 200ms」）')
log('# F1 主口径 P95=' + main.p95 + 'ms · 业务最坏 P95=' + worst + 'ms（' +
  (business.reduce((a, r) => (r.p95 === worst ? r.scene : a), '') || '-') + '）')
if (proxy) {
  log('# 对照：同一条请求 直连 P50=' + main.p50 + 'ms / 走 Vite 代理 P50=' + proxy.p50 +
    'ms（差值 ' + (proxy.p50 - main.p50) + 'ms 是 dev server 的账，不进业务判定）')
}
log('# 判定：' + (pass ? 'PASS' : 'FAIL'))

fs.writeFileSync(path.join(HERE, 'perf-feed.log'), LINES.join('\n') + '\n')
fs.writeFileSync(path.join(HERE, 'perf-feed.json'), JSON.stringify({
  started, base: DIRECT, proxy: PROXY, account: ACCOUNT.username,
  rateLimitPerMinute: LIMIT, budgetPerWindow: BUDGET, warm: WARM, thresholdMs: THRESHOLD,
  cacheMode, cacheModeNote: cacheMode === 'local'
    ? '6379 未启动，Caffeine 本地兜底；Redis 口径待阶段 8 重跑' : '见日志行',
  manifestRun: fx.m.run, manifestAt: fx.m.at,
  fixtures: { similarIds, coldId: fx.coldId, feedIds18: fx.ids18 },
  businessWorstP95: worst, pass,
  scenes: results.map((r) => Object.assign({}, r, { remainMin: r.remainMin }))
}, null, 2) + '\n')
log('# 产物：perf-feed.log / perf-feed.json（同目录，手册与 README 引用这两个文件，不手抄数字）')
process.exit(pass ? 0 : 1)
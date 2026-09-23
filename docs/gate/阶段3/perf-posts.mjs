// Gate 3 第 3 条：500 条帖量级下 GET /api/posts 的 P95 <= 500ms（NFR1，手册允许自写脚本粗测）
//
// 这个脚本第一版是被限流器骗了的，那一段要留在注释里，否则下一个人还会踩：
// 限流是 T2.8 的 HandlerInterceptor，挂在 /api/** 上、**跑在业务逻辑之前**，
// 固定窗口 = System.currentTimeMillis()/60000，按登录身份（u8）计 60 次/分。
// 所以裸跑 100 枪串行会看到「P95=2ms、量级漂亮」的假结果 ——
// 那 2ms 是 429 被挡回来的耗时，服务端根本没执行过那条 SQL。
// 修法不是绕过限流（绕过之后测的就不是生产上那一套代码了），而是
//   1) 按窗口预算发枪，用完就睡到下一个自然分钟；
//   2) 只有 HTTP 200 进延迟样本，429 单独记账并立刻放弃当前窗口；
//   3) 顺手把 X-RateLimit-Remaining 记下来当证据 —— 它独立证明「这一枪确实进了业务层、还剩多少额度」。
//
// 另外三条口径：
// A. 主口径直连 8080，不走 5173。Vite dev server 的单线程代理会把 XHR 串起来排队，
//    量出来的尾延迟说不清是接口的账还是前端工具的账。S5 专门再打一遍 5173 做对照，
//    两行相减就是代理开销，读者可自行换算。
// B. 串行单并发 —— 测的是「500 条量级下这个接口的单次延迟」，不是并发吞吐；
//    正式压测按手册排在阶段 8（本条只要求粗测）。串行也不会被限流误伤成"看起来很快"。
// C. 列表接口没有结果缓存（只有浏览量 5s 写延迟缓存；全仓 grep 无 @Cacheable），
//    所以每一枪都是真 SQL，不存在「第二枪起命中缓存所以变快」。URL 尾部的 &i=<n>
//    只是防 HTTP 层复用，服务端不读它。
//
// 前置：后端 8080 在跑；先执行同目录 seed-500-posts.sql 把可测度级抬到 >=500（跑完必须 cleanup）。
// 跑法：node "docs/gate/阶段3/perf-posts.mjs" *>&1 | Tee-Object -FilePath <日志绝对路径>
// 产物：perf-posts.json（结构化，给 README 引用）。退出码：主口径 P95>500ms 或任一枪非 200 即 1。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const DIRECT = process.env.PERF_BASE || 'http://127.0.0.1:8080'
const PROXY = 'http://127.0.0.1:5173'
const ACCOUNT = { username: 'smoke_runner', password: 'Smoke#2026x' }
const WINDOW_MS = 60000
const LIMIT = 60        // MindisleProperties.RateLimit.userPerMinute 默认值
const BUDGET = 40       // 每窗口只花 40 枪：给浏览器/通知轮询等并发流量留 20 枪余量
const WARM = 3

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

let bucket = -1
let used = 0
async function budget () {
  const now = Date.now()
  const b = Math.floor(now / WINDOW_MS)
  if (b !== bucket) { bucket = b; used = 0 }
  if (used >= BUDGET) {
    const wait = (bucket + 1) * WINDOW_MS - now + 300
    console.log('  .. 限流窗口预算 ' + BUDGET + '/' + LIMIT + ' 用尽，睡 ' + wait + 'ms 等下一个自然分钟')
    await sleep(wait)
    bucket = Math.floor(Date.now() / WINDOW_MS); used = 0
  }
  used++
}

// 被限流挡住 = 当前窗口已经不可信，立刻作废并换窗口，绝不把 429 的耗时混进样本。
async function rotateWindow (reason) {
  const wait = (Math.floor(Date.now() / WINDOW_MS) + 1) * WINDOW_MS - Date.now() + 300
  console.log('  !! ' + reason + '，作废当前窗口，睡 ' + wait + 'ms')
  await sleep(wait)
  bucket = Math.floor(Date.now() / WINDOW_MS); used = 0
}

async function login () {
  await budget()
  const r = await fetch(DIRECT + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(Object.assign({}, ACCOUNT, {
      captchaId: '00000000000000000000000000000000', captchaCode: 'ZZZZ'
    }))
  })
  const j = await r.json()
  const token = j && j.data && j.data.accessToken
  if (!token) throw new Error('登录取不到 token（HTTP ' + r.status + ' code=' + (j && j.code) + '）：环境问题，不测')
  return token
}

async function once (url, token) {
  await budget()
  const t0 = Date.now()
  const res = await fetch(url, { headers: { Authorization: 'Bearer ' + token } })
  const text = await res.text()
  const cost = Date.now() - t0
  let total = null
  let size = null
  let code = null
  try {
    const j = JSON.parse(text)
    code = j && j.code
    total = j && j.data && typeof j.data.total === 'number' ? j.data.total : null
    size = j && j.data && Array.isArray(j.data.list) ? j.data.list.length : null
  } catch (e) { /* 非 JSON 交给 status 记账 */ }
  return {
    cost, status: res.status, code, total, size, bytes: text.length,
    remain: res.headers.get('x-ratelimit-remaining'), limit: res.headers.get('x-ratelimit-limit')
  }
}

function pick (sorted, p) {
  return sorted[Math.min(sorted.length - 1, Math.max(0, Math.ceil(p * sorted.length) - 1))]
}

function summarize (name, rows, throttled) {
  const ok = rows.filter((r) => r.status === 200)
  if (!ok.length) throw new Error(name + '：一个 200 样本都没有（全是限流/错误），不结论')
  const costs = ok.map((r) => r.cost).sort((a, b) => a - b)
  const s = {
    scene: name, samples: ok.length, throttled: throttled,
    total: ok[0].total, pageSize: ok[0].size,
    min: costs[0], p50: pick(costs, 0.5), p95: pick(costs, 0.95),
    p99: pick(costs, 0.99), max: costs[costs.length - 1],
    avg: Math.round(costs.reduce((a, b) => a + b, 0) / costs.length),
    avgBytes: Math.round(ok.reduce((a, b) => a + b.bytes, 0) / ok.length),
    remainSeen: ok.map((r) => Number(r.remain)).filter((x) => Number.isFinite(x))
  }
  console.log(
    name.padEnd(30) +
    ' P50=' + String(s.p50).padStart(4) + 'ms' +
    ' P95=' + String(s.p95).padStart(4) + 'ms' +
    ' P99=' + String(s.p99).padStart(4) + 'ms' +
    ' max=' + String(s.max).padStart(5) + 'ms' +
    ' avg=' + String(s.avg).padStart(4) + 'ms' +
    ' n=' + String(s.samples).padStart(3) +
    ' 被限流=' + String(s.throttled).padStart(3) +
    ' total=' + String(s.total).padStart(4) +
    ' 条/页=' + String(s.pageSize).padStart(2) +
    ' 响应≈' + s.avgBytes + 'B'
  )
  return s
}

// 每个场景单独给样本数：主口径要够算 P95，其余场景是"别只挑对自己有利的口径"的旁证。
const SCENES = [
  ['S1 广场首屏 page=0 size=20', DIRECT + '/api/posts?page=0&size=20&i=', 60],
  ['S2 广场深翻 page=10 size=20', DIRECT + '/api/posts?page=10&size=20&i=', 40],
  ['S3 游标翻页 size=50 beforeId', DIRECT + '/api/posts?size=50&beforeId=340&i=', 40],
  ['S4 话题流 topics/1/posts', DIRECT + '/api/topics/1/posts?page=0&size=20&i=', 40],
  ['S5 对照：走 Vite 5173 代理', PROXY + '/api/posts?page=0&size=20&i=', 40]
]

const token = await login()
const started = new Date().toString()
console.log('# perf-posts @ ' + started)
console.log('# 主口径 ' + DIRECT + '（直连 · 串行单并发 · 每窗口最多 ' + BUDGET + ' 枪，' + LIMIT + ' 次/分是产品限流阈值）')
console.log('')
const results = []
for (const [name, base, count] of SCENES) {
  for (let i = 0; i < WARM; i++) await once(base + 'w' + i, token)
  const rows = []
  let throttled = 0
  for (let i = 0; i < count; i++) {
    const r = await once(base + i, token)
    if (r.status === 429) {
      throttled++
      await rotateWindow('S 场景收到 429（code=' + r.code + '），说明该身份的窗口已满')
      i--                                     // 这一枪作废，重新补一枪，保证样本数说话算数
      continue
    }
    if (r.status !== 200) throw new Error(name + '：HTTP ' + r.status + ' code=' + r.code + '（不是限流，是真错误，不结论）')
    rows.push(r)
  }
  const s = summarize(name, rows, throttled)
  s.feed = base.indexOf('/api/posts') >= 0      // 只有广场口径可以吃「total >= 500」这条量级断言
  results.push(s)
}

const business = results.filter((r) => !r.scene.startsWith('S5'))
const worst = Math.max.apply(null, business.map((r) => r.p95))
const main = results[0]
const proxy = results.find((r) => r.scene.startsWith('S5'))
// 量级断言只加在「广场 + 页码模式」那几枪上。两处坑都踩过一遍才写对的：
//   ① 游标模式按 PageResult.ofCursor 的设计把 total 置为 -1（不查总数），拿 -1 比 >=500 会误判；
//   ② 话题流 /api/topics/1/posts 的 total 是「这个话题里有几条帖」（今天 43），
//      它和「广场有没有 500 条量级」是两件事，混进同一条断言同样会误判。
const paging = results.filter((r) => r.feed && r.total >= 0)
const pass = main.p95 <= 500 && business.every((r) => r.p95 <= 500) &&
  paging.length >= 2 && paging.every((r) => r.total >= 500)
console.log('')
console.log('# 主口径 S1 P95=' + main.p95 + 'ms，业务场景（S1~S4）最坏 P95=' + worst + 'ms，阈值 500ms')
console.log('# 对照：直连 P50=' + main.p50 + 'ms vs 走 Vite 代理 P50=' + (proxy ? proxy.p50 : '-') + 'ms')
// 量级声明只引用页码场景的 total；S3 的 -1 在这里不参与判据。
console.log('# 量级：接口 total=' + main.total + '（判据要求 >=500，夹具来自 seed-500-posts.sql，测完跑 cleanup-500-posts.sql 回滚）')
console.log('# 判定：' + (pass ? 'PASS' : 'FAIL'))
fs.writeFileSync(path.join(HERE, 'perf-posts.json'), JSON.stringify({
  started, base: DIRECT, proxy: PROXY, rateLimitPerMinute: LIMIT, budgetPerWindow: BUDGET,
  warm: WARM, thresholdMs: 500, mainP95: main.p95, worstP95: worst, dbTotal: main.total, pass,
  scenes: results.map((r) => Object.assign({}, r, { remainSeen: r.remainSeen.slice(0, 3) }))
}, null, 2) + '\n')
process.exit(pass ? 0 : 1)
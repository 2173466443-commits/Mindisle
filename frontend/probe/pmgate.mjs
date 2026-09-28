// 阶段 5（站内私信 U9/U10 · T5.1–T5.8）收口取证 —— Gate5
//
// 【这个文件存在的理由】
// 私信是本项目里第一条「双入口」链路：同一套业务判据既走 REST（/api/pm/*）又走 STOMP（/app/*）。
// 后端单测能证 Service 的判据，jsdom 探针能证渲染，但两者都证不到这一条：
// **帧真的从服务器推到了另一条连接上吗**。这一步以前没人量过，
// 而它恰恰是「私信能不能用」的定义 —— 所以本文件同时跑三条通道：
//   A 组 REST 十条线（含幂等、分页方向、未读、在线、拉黑、举报、参数与内容闸、危机分级）；
//   B 组 STOMP 实时线（握手鉴权、收发闭环、已读回执推给发信方、离线不抬 delivered、
//     错误不断线、/app/ping 的 pong、presence 广播、alert 载荷不带正文）；
//   C 组真浏览器（/chat 列表、/chat/:uid 详情、顶栏角标、用户主页入口、通知点进去真的跳会话）。
//
// 【为什么 WS 用裸端点 /ws-native 而不是浏览器那条 /ws】
// WebSocketConfig 刻意挂了两条端点：/ws 走 SockJS（浏览器主链路，为了降级面），
// /ws-native 是裸 WS（探针专用）。这里用自写的最小 STOMP 帧客户端而不是 @stomp/stompjs，
// 是因为取证要的是「服务器到底发了什么帧」——套一层客户端库会把
// heart-beat、accept-version 这些细节藏起来，出问题时分不清是库的锅还是后端的锅。
//
// 前置：一个装了 pm 模块的后端实例（判据现查：/v3/api-docs 里 /api/pm/* 恰好 9 条 path），
//       以及 5173 上的 Vite dev（C 组用；只跑 A/B 两组时设 NOUI=1）。
// 跑法：cd frontend && node probe/pmgate.mjs
//       指定实例：APIBASE=http://127.0.0.1:8090 node probe/pmgate.mjs
//       只跑协议层：NOUI=1 node probe/pmgate.mjs
// 产物：docs/gate/阶段5/*.png + pmgate.log + shot-manifest.json
// 退出码：任何一条 FAIL 即 1。
//
// ⚠ 本脚本会真写库：private_message / user_block / alert_ticket / audit_task / notify_message 都会长行。
//   跑之前在文档里记下这些表的行数，跑完把增量写进 docs/gate/阶段5/README.md（不删数据，
//   删了就没法复现「危机建单」那两条判据）。夹具只在 demo01 / demo03 两个演示号之间产生。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(HERE, '..', '..')
const OUT = path.join(ROOT, 'docs', 'gate', '阶段5')
const PW_DIR = 'E:/codex workspace/_cache/node_modules/playwright-core'
const CHROME = process.env.CHROME ||
  'C:/Users/Drbrain/AppData/Local/Google/Chrome/Application/chrome.exe'
const BASE = process.env.BASE || 'http://127.0.0.1:5173'
// 🔴 默认打 8080（用户浏览器实际连的那个实例），但**开跑第一件事是验它有 pm**：
// 打在一个没装私信模块的旧进程上会得到 404/10002 两种「看起来像权限问题」的红，
// 白排查半小时。判据只有一个：/v3/api-docs 里 /api/pm/* 的 path 数 == 9。
const APIBASE = process.env.APIBASE || 'http://127.0.0.1:8080'
const DESKTOP = { width: 1600, height: 1000 }
const CAPTCHA_ID = '00000000000000000000000000000000'
const PWD = 'Test1234'
// 甲乙两个身份默认用 demo01/demo03，但必须能用环境变量换走。原因不是「想换个号试试」而是判据本身：
// C10 要拍的是「列表里那颗未读角标和顶栏那句 N 条没读是同一个数」，而本机 Chrome 常常挂着 demo01 的
// 会话详情页 —— 真人窗口一收到 WS 推送就自动上报已读，未读在几十毫秒内被吃干净（第 46 轮实测：
// id=87 那条的 read_at 比 created_at 只晚 29ms，脚本自己的甲屏那时还停在 /user/4）。
// 所以复跑只要换一对没人登录的号：GATE_SENDER=demo04 GATE_RECEIVER=demo05。
const SENDER = process.env.GATE_SENDER || 'demo01'     // 甲：会话列表的主人，也是绝大多数断言的发信方
const RECEIVER = process.env.GATE_RECEIVER || 'demo03' // 乙：收信方
const THIRD = process.env.GATE_THIRD || 'demo05'
const NOUI = process.env.NOUI === '1'
const WS_NATIVE = APIBASE.replace(/^http/, 'ws') + '/ws-native'

fs.mkdirSync(OUT, { recursive: true })

let nPass = 0
let nFail = 0
const failures = []
const manifest = []
const logLines = []
function say (s) { logLines.push(s); console.log(s) }
function check (ok, name, read) {
  if (ok) { nPass++; say('PASS ' + name + (read ? '   <- ' + read : '')) }
  else { nFail++; failures.push(name); say('FAIL ' + name + '   <- ' + (read || '')) }
  return ok
}
function shot (file, label, ok, reading) {
  manifest.push({ file: file, label: label, ok: !!ok, reading: reading || '' })
  if (ok) { say('  [图] ' + file + ' —— ' + label + (reading ? '  <- ' + reading : '')) }
  else { nFail++; failures.push('[图] ' + file + ' 的判据没成立：' + (reading || label)); say('  [图 FAIL] ' + file + ' —— ' + (reading || '')) }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
// 取证脚本的纪律：一条判据读不到，也只能让它自己变红，不许把整场带崩。
// rows(r)：把「data 缺失 / data.list 不是数组」统一折叠成空数组——上一轮就是一处 bobView.data.list 直取
//        把整份 pmgate.log 与截图清单带没了（TypeError 直接结束进程，finish() 根本没跑）。
// field(r,k)：读 data 上的单个字段，读不到给 undefined，由调用方写进 FAIL 读数。
const rows = (r) => (r && r.data && Array.isArray(r.data.list) ? r.data.list : [])
const field = (r, k) => (r && r.data ? r.data[k] : undefined)
// 顶层异常兜底：真崩了也要把已跑出来的读数落盘，并且把 stack 打进日志。
let finishRan = false
let fatalDone = false
function onFatal (err) {
  const msg = String((err && err.message) || err)
  if (finishRan) { console.log('NOTE 已收口后的迟到异常（不改退出码）：' + msg); return }
  if (fatalDone) return
  fatalDone = true
  say('FATAL 顶层异常：' + msg)
  say('  stack: ' + String((err && err.stack) || err).split(String.fromCharCode(10)).slice(0, 6).join(' | ').slice(0, 600))
  finish(true)
}
process.on('uncaughtException', onFatal)
process.on('unhandledRejection', onFatal)

// 全站非 AI 口共用一个 60 次/分钟的用户桶（RateLimitInterceptor L48/L77：rl:<身份>:<分钟窗>），
// 而 Gate5 一个脚本要对 demo01 打几十次 ⇒ 不节流的话一定会撞 429，撞了以后再多的断言都只是在测限流器。
// 这里做两层：(1) 任意两次调用之间至少隔 PACE 毫秒；(2) 真的撞了 429 就等过这个分钟窗再重跑，
// 且 429 那一次不算读数（否则「限流」会被记成「产品故障」）。
const PACE = Number(process.env.PACE || 1050)
let lastCallAt = 0
async function api (method, p, token, body) {
  const headers = {}
  if (token) headers.Authorization = 'Bearer ' + token
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  let r = null
  let j = null
  let waited = 0
  for (let attempt = 0; attempt < 4; attempt++) {
    const gap = Date.now() - lastCallAt
    if (gap < PACE) await sleep(PACE - gap)
    lastCallAt = Date.now()
    r = await fetch(APIBASE + p, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body)
    })
    if (r.status !== 429) break
    // 分钟桶：等到下一个整分钟再加半秒余量，比固定 sleep(4s) 可靠（桶是按分钟对齐的）。
    const waitMs = 60000 - (Date.now() % 60000) + 500
    waited++
    say('  ……撞限流（429）：' + method + ' ' + p + '，等 ' + waitMs + 'ms 过分钟窗（第 ' + waited + ' 次）')
    await sleep(waitMs)
  }
  try { j = await r.json() } catch (e) { j = null }
  return { status: r.status, code: j && j.code, data: j && j.data, msg: j && j.msg }
}
// multipart 上传（Gate5 第一轮根本没有这个函数，A9 就只能抄一个「昨天上传过的路径」当常量）。
// 取证要证明的是「图片私信整条链路今天还通」，那第一步就得当场传一张，而不是复用历史 url。
// 后端：FileController#uploadImage = @PostMapping("/api/files/image", consumes=MULTIPART_FORM_DATA)
//       + @RequestPart("file")，所以 part 名必须是 file；出参 ImageUploadService.StoredImage
//       {url,kind,width,height,bytes}，url 已经是 /uploads/yyyy/MM/dd/uuid.ext。
async function apiUpload (p, token, filePath) {
  const gap = Date.now() - lastCallAt
  if (gap < PACE) await sleep(PACE - gap)
  lastCallAt = Date.now()
  const form = new FormData()
  form.append('file', new Blob([fs.readFileSync(filePath)], { type: 'image/png' }), path.basename(filePath))
  // 绝不能自己写 Content-Type：multipart 的 boundary 由 fetch 生成，手写就没有 boundary，
  // 后端会直接 415（Spring 的 consumes = MULTIPART_FORM_DATA_VALUE）。
  const headers = {}
  if (token) headers.Authorization = 'Bearer ' + token
  const r = await fetch(APIBASE + p, { method: 'POST', headers, body: form })
  let j = null
  try { j = await r.json() } catch (e) { j = null }
  return { status: r.status, json: j }
}

async function login (acct) {
  const r = await api('POST', '/api/auth/login', null, {
    username: acct, password: PWD, captchaId: CAPTCHA_ID, captchaCode: 'ZZZZ'
  })
  if (!r.data || !r.data.accessToken) {
    throw new Error('登录 ' + acct + ' 没拿到 token（HTTP ' + r.status + ' code=' + r.code + '）')
  }
  return { token: r.data.accessToken, refresh: r.data.refreshToken, uid: Number(r.data.user.id), nick: r.data.user.nickname || acct }
}
// ==========================================================================================
// 最小 STOMP 1.2 客户端（裸 WebSocket，Node 22 起 globalThis.WebSocket 原生可用）
// 只做五件事：CONNECT / SUBSCRIBE / SEND / 按 NUL 切帧 / DISCONNECT。
// 刻意不引 @stomp/stompjs：取证要看原始帧（heart-beat 协商、accept-version、
// /user 前缀到底被解析成了什么），库会把这些藏起来。
// ==========================================================================================
function stompFrame (command, headers, body) {
  let s = command + '\n'
  for (const k of Object.keys(headers || {})) s += k + ':' + headers[k] + '\n'
  return s + '\n' + (body || '') + '\u0000'
}
class StompWs {
  constructor (name, token) {
    this.name = name
    this.token = token
    this.frames = []
    this.waiters = []
    this.buf = ''
    this.connected = null
    this.closed = false
    this.subs = []
    this.sends = []
    this.cursor = 0
    this.seq = 0
  }
  url () {
    // 握手鉴权三条来路之一（WsAuthHandshakeInterceptor#resolveToken）：?access_token=
    // 浏览器原生 WS 不能自定义请求头，探针也照这条走，免得「探针能连、浏览器不能」这种偏差重演。
    return WS_NATIVE + '?access_token=' + encodeURIComponent(this.token)
  }
  connect () {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.url())
      this.ws = ws
      let settled = false
      const to = setTimeout(() => { if (!settled) { settled = true; reject(new Error(this.name + ' 握手/CONNECT 超时 8s')) } }, 8000)
      ws.onopen = () => {
        // heart-beat:0,0 = 两端都不发心跳帧。取证脚本活不过几分钟，留着心跳只会让日志里
        // 多出一堆空行帧，反而把「服务器真的没推东西」和「推了心跳」混在一起看不清。
        ws.send(stompFrame('CONNECT', { host: 'mindisle', 'accept-version': '1.2', 'heart-beat': '0,0' }))
      }
      ws.onmessage = (ev) => this._onData(String(ev.data), () => {
        if (!settled) { settled = true; clearTimeout(to); resolve(this) }
      })
      ws.onerror = (ev) => { if (!settled) { settled = true; clearTimeout(to); reject(new Error(this.name + ' WS 错误')) } }
      ws.onclose = () => {
        this.closed = true
        if (!settled) { settled = true; clearTimeout(to); reject(new Error(this.name + ' 连接被关（握手没过？）')) }
      }
    })
  }
  _onData (chunk, onConnected) {
    this.buf += chunk
    let idx
    while ((idx = this.buf.indexOf('\u0000')) >= 0) {
      const raw = this.buf.slice(0, idx)
      this.buf = this.buf.slice(idx + 1)
      // 心跳：STOMP 允许只含 EOL 的空帧。把它当帧记账会把「服务器真的没推」
      // 和「推了个心跳」混成一堆噪声，所以这里直接吞掉。
      if (!raw.replace(/[\r\n]/g, '')) continue
      let head = raw
      let body = ''
      const sep = raw.indexOf('\n\n')
      if (sep >= 0) { head = raw.slice(0, sep); body = raw.slice(sep + 2) }
      const lines = head.split('\n').filter((l) => l.length)
      const headers = {}
      for (const l of lines.slice(1)) {
        const c = l.indexOf(':')
        if (c > 0) headers[l.slice(0, c)] = l.slice(c + 1)
      }
      const frame = { command: lines[0] || '', headers, body }
      // 🔴 第 44 轮用 _cache/mindisle-dbtmp/wsdiag.mjs 抓原始帧现查到的事实：Spring 的 STOMP 1.1/1.2
      // 把订阅标识放在 `subscription` 头，只有 1.0 才放在 `id`（StompHeaderAccessor#setSubscription 按版本切换）。
      // 判据统一写 headers.id 的后果：presence 广播 / pong / sent / delivered / 私信本体 五条全被读成
      // 「服务器没推」——而 wsdiag 当场证明服务器一条没少推，还顺手打出了 subscription:a-pres 这样的头。
      // 这里把 1.2 的订阅头补写成 headers.id（subscription 原值保留，取证时仍能在 frames 里回看原文），
      // 判据只写一种口径，别再让「帧到了但没对上号」冒充「实时链路不可用」。
      if (frame.command === 'MESSAGE' && !headers.id && headers.subscription) headers.id = headers.subscription
      if (body) { try { frame.json = JSON.parse(body) } catch (e) { frame.parseError = String(e && e.message) } }
      this.frames.push(frame)
      if (frame.command === 'CONNECTED') { this.connected = frame; onConnected(frame) }
      if (frame.command === 'ERROR') this.error = frame
      for (let i = this.waiters.length - 1; i >= 0; i--) {
        const w = this.waiters[i]
        let hit = false
        try { hit = !!w.pred(frame) } catch (e) { hit = false }
        if (hit) { this.waiters.splice(i, 1); w.resolve(frame) }
      }
    }
  }
  subscribe (id, destination) {
    this.ws.send(stompFrame('SUBSCRIBE', { id, destination, ack: 'auto' }, ''))
    this.subs.push({ id, destination })
    return this
  }
  sendRaw (destination, obj) {
    const body = JSON.stringify(obj)
    this.ws.send(stompFrame('SEND', {
      destination,
      'content-type': 'application/json;charset=utf-8',
      'content-length': Buffer.byteLength(body, 'utf8')
    }, body))
    this.sends.push({ destination, obj })
    return this
  }
  matches (pred, from) {
    const start = from === undefined ? this.cursor : from
    const out = []
    for (let i = start; i < this.frames.length; i++) if (pred(this.frames[i])) out.push(i)
    return out
  }
  // from：显式起点（帧序号），不传就用游标。为什么需要它见 B5/B6 那段——sent 与 delivered 的到达顺序不保证。
  async next (pred, ms, label, from) {
    const hitNow = this.matches(pred, from)
    if (hitNow.length) { const i = hitNow[0]; this.cursor = Math.max(this.cursor, i + 1); return this.frames[i] }
    const limit = ms || 6000
    return new Promise((resolve, reject) => {
      const to = setTimeout(() => {
        const k = this.waiters.findIndex((w) => w.pred === pred)
        if (k >= 0) this.waiters.splice(k, 1)
        const got = this.frames.slice(this.cursor).map((f) => f.command + ' ' + (f.headers.destination || '') + (f.json && f.json.kind ? ' kind=' + f.json.kind : '')).join(' | ')
        reject(new Error((label || this.name) + ' 等帧超时 ' + limit + 'ms；本连接累计收帧 ' + this.frames.length
          + '；游标之后 [' + got + ']；已订 ' + JSON.stringify(this.subs)))
      }, limit)
      const w = { pred, resolve: (f) => { clearTimeout(to); resolve(f) } }
      this.waiters.push(w)
    })
  }
  async noneWithin (pred, ms, label) {
    await sleep(ms || 1500)
    const hits = this.matches(pred)
    const n = hits.length
    if (n) this.cursor = Math.max(this.cursor, hits[n - 1] + 1)
    return n
  }
  static destOf (userDest) { return '/user' + userDest }
  disconnect () {
    return new Promise((resolve) => {
      if (!this.ws || this.closed) { this.closed = true; return resolve() }
      try { this.ws.send(stompFrame('DISCONNECT', { receipt: 'bye-' + this.name }, '')) } catch (e) {}
      setTimeout(() => { try { this.ws.close() } catch (e) {} this.closed = true; resolve() }, 1500)
    })
  }
}
// ==========================================================================================
// S0 前置自检：先确认「这个实例真的装了私信模块」，再谈判据
// ==========================================================================================
// 这一步不是洁癖：REST 打 /api/pm/unread 在一个没装 pm 的旧进程上会回 10002（没鉴权上下文），
// 看起来完全像权限问题，白排查半小时。唯一可靠的读数在 OpenAPI 文档里：/api/pm/* 的 path 数。
say('')
say('===== Gate5 私信/实时通道收口取证 =====')
say('后端实例 ' + APIBASE + '   前端 ' + BASE + '   UI ' + (NOUI ? '跳过(NOUI=1)' : '开'))
let doc = null
try {
  doc = await (await fetch(APIBASE + '/v3/api-docs')).json()
} catch (e) {
  say('ABORT 拿不到 ' + APIBASE + '/v3/api-docs：' + e.message + '（后端没起？端口不对？）')
  finish(true)
}
const allPaths = Object.keys(doc.paths || {})
const pmPaths = allPaths.filter((p) => p.indexOf('/api/pm') === 0).sort()
check(pmPaths.length === 9, 'S0-1 该实例已装载私信模块（/api/pm/* 恰好 9 条）',
  'totalPaths=' + allPaths.length + ' pmPaths=' + pmPaths.length + ' ' + pmPaths.join(' '))
if (pmPaths.length !== 9) {
  say('ABORT 这个后端实例上没有私信模块，探针不能往下跑')
  finish(true)
}
const sysRes = await (await fetch(APIBASE + '/api/system/info')).json().catch(() => null)
if (sysRes && sysRes.data) {
  say('S0-2 实例自述 profiles=' + sysRes.data.profiles + ' cacheMode=' + sysRes.data.cacheMode
    + ' llmProvider=' + sysRes.data.llmProvider + ' java=' + sysRes.data.javaVersion + ' serverTime=' + sysRes.data.serverTime)
}

const alice = await login(SENDER)   // 甲：会话列表的主人，也是绝大多数断言的发信方
const bob = await login(RECEIVER)   // 乙：收信方
const carol = await login(THIRD) // 丙：与这条会话无关的第三人，用来证「第三人举报别人的私信进不了队列」
say('S0-3 夹具 uid 甲=' + alice.uid + '(' + alice.nick + ') 乙=' + bob.uid + ' 丙=' + carol.uid)

// 一个时间戳后缀：clientMsgId 是幂等键，跑一次就要换一批，否则第二次跑全部命中旧行，
// 「新落库一行」这类判据会假红。
const TS = Date.now()
const key = (tag) => 'g5-' + TS + '-' + tag
const txt = (tag) => key(tag) + ' 你好，这是 Gate5 的取证消息。'
const has = (o, ks) => ks.every((k) => Object.prototype.hasOwnProperty.call(o || {}, k))

say('')
say('---- A 组：REST 十条线 ----')
// A1 会话列表：空库起步也要给出完整形状（前端 usePm 直接读 list/nextCursor/hasMore/unreadTotal）。
//
// 🔴 判据口径（Gate5 第一轮就是被这条坑红的，不是产品红）：本项目 application.yml L71 设了
//   spring.jackson.default-property-inclusion: non_null —— 值为 null 的字段在 JSON 里<b>根本不存在</b>。
//   所以「可空」的 nextCursor 只能按「缺席或为 null」判，硬要它 own-property 会得到一条假红；
//   反过来 unreadTotal / hasMore / list 这三个后端永远给值，缺席就是真红，仍然用 has() 钉住。
const a1 = await api('GET', '/api/pm/conversations?size=20', alice.token)
const a1Keys = Object.keys(a1.data || {})
check(a1.code === 0 && has(a1.data, ['list', 'hasMore', 'unreadTotal']) && Array.isArray(a1.data.list)
  && typeof a1.data.hasMore === 'boolean' && typeof a1.data.unreadTotal === 'number'
  && (a1.data.nextCursor === undefined || typeof a1.data.nextCursor === 'number'),
  'A1 GET /conversations：list/hasMore/unreadTotal 必给，可空的 nextCursor 按 non_null 契约允许缺席',
  'code=' + a1.code + ' keys=' + a1Keys.join(',') + ' listLen=' + rows(a1).length
  + ' unreadTotal=' + field(a1, 'unreadTotal') + ' hasMore=' + field(a1, 'hasMore') + ' nextCursor=' + field(a1, 'nextCursor'))
const a1b = await api('GET', '/api/pm/unread', alice.token)
check(a1b.code === 0 && has(a1b.data, ['total', 'byPeer', 'peers']), 'A1b GET /unread 返回 {total,byPeer,peers}',
  'total=' + field(a1b, 'total') + ' byPeer=' + JSON.stringify(field(a1b, 'byPeer') || null))

// A2 发信（REST 入口）：返回的就是那一行落库内容（落库优先）
const a2 = await api('POST', '/api/pm/send', alice.token,
  { toUserId: bob.uid, content: txt('a2'), clientMsgId: key('a2'), msgType: 'text' })
const m2 = a2.data || {}
check(a2.code === 0 && m2.id > 0 && m2.mine === true && m2.fromUserId === alice.uid && m2.toUserId === bob.uid,
  'A2 POST /send 落库并回视图（id/mine/from/to）', 'code=' + a2.code + ' id=' + m2.id + ' mine=' + m2.mine + ' status=' + m2.status)
// A2b 这一条是 Gate5 挖出来的<b>真</b>缺陷（不是判据写错）：改之前 INSERT 不写 created_at，
// DDL 默认值只把库里的时间填上，而 viewOf(row) 拿的是那个内存对象 —— 于是 REST 与 WS 帧里的
// createdAt 是 null，non_null 再把键删掉：刚发出去/刚收到的那条气泡没有时间，日期分组掉进「更早」，
// 而幂等重发走回查反倒带时间。修法见 PmService#send 的 row.setCreatedAt(now) 与 mapper 的列清单。
check(m2.content === txt('a2') && m2.msgType === 'text' && !!m2.createdAt,
  'A2b 视图内容与类型逐字回显，createdAt 非空（REST 与 WS 帧同一份 view，气泡时间不能靠前端兜本地时钟）',
  'createdAt=' + m2.createdAt + ' riskLevel=' + m2.riskLevel + ' status=' + m2.status + ' keys=' + Object.keys(m2).join(','))
check(m2.alert == null && !('alert' in m2), 'A2c 普通私信不带求助卡（non_null 契约下 alert 这个键压根不存在）',
  'alert=' + JSON.stringify(m2.alert) + ' key-present=' + ('alert' in m2))

// A3 幂等：同一个 clientMsgId 再发一次，只许返回同一行，不许多落一行
const a3 = await api('POST', '/api/pm/send', alice.token,
  { toUserId: bob.uid, content: txt('a2'), clientMsgId: key('a2'), msgType: 'text' })
check(a3.code === 0 && a3.data.id === m2.id, 'A3 clientMsgId 重发返回同一条 id（INSERT IGNORE 撞键后回查）',
  '第一次 id=' + m2.id + ' 重发 id=' + (a3.data && a3.data.id))
// 🔴 Gate5 必须是**可重跑**的。第一轮跑完 private_message 已经从 0 行长到十几行，
// 而 clientMsgId 带 TS、正文带 TS ⇒ 每次跑都往同一个会话里追加新行。于是
// 「会话里应当有 3 条」这类绝对条数判据在第二次跑必然假红（A12-5 已经为这个改过一次）。
// 这一段的口径统一成：**期望值由同一次全量读现算**，身份用本轮自己的 id 认，
// 绝不写死条数，也绝不做「>= 1」这种松判据（松判据等于没判）。
const a3b = await api('GET', '/api/pm/thread/' + bob.uid + '?size=100', alice.token)
const dupRows = rows(a3b).filter((x) => x.content === txt('a2'))
check(dupRows.length === 1 && dupRows[0].id === m2.id,
  'A3b 重发没有多落一行（按本轮那句唯一正文数一遍，会话里只有那一条）',
  '命中 ' + dupRows.length + ' 条 ids=' + dupRows.map((x) => x.id).join(',') + ' 会话总长=' + rows(a3b).length)

// A4 会话详情形状 + 时间正序
const a4 = await api('GET', '/api/pm/thread/' + bob.uid + '?size=20', alice.token)
check(a4.code === 0 && has(a4.data, ['peerId', 'peerName', 'peerAvatar', 'list', 'nextCursor', 'hasMore', 'blocked', 'peerOnline', 'peerLastLoginAt']),
  'A4 GET /thread/{peerId} 返回九字段会话页', 'code=' + a4.code + ' peerName=' + field(a4, 'peerName') + ' blocked=' + field(a4, 'blocked') + ' peerOnline=' + field(a4, 'peerOnline') + ' listLen=' + rows(a4).length)
const a4b = await api('POST', '/api/pm/send', alice.token, { toUserId: bob.uid, content: txt('a4b'), clientMsgId: key('a4b') })
const a4c = await api('POST', '/api/pm/send', alice.token, { toUserId: bob.uid, content: txt('a4c'), clientMsgId: key('a4c') })
const a4d = await api('GET', '/api/pm/thread/' + bob.uid + '?size=100', alice.token)
const ids4 = rows(a4d).map((x) => x.id)
const asc4 = ids4.every((v, i) => i === 0 || v > ids4[i - 1])
const runIds4 = [m2.id, field(a4b, 'id'), field(a4c, 'id')]
check(asc4 && ids4.slice(-3).join(',') === runIds4.join(','),
  'A4d 历史是时间正序，且本轮新落的那三条恰好是最新的三条（前端直接 append 到气泡数组上方，不再排序）',
  'len=' + ids4.length + ' 尾部三条=' + ids4.slice(-3).join(',') + ' 本轮=' + runIds4.join(','))
const runRows4 = rows(a4d).filter((x) => runIds4.indexOf(x.id) >= 0)
check(runRows4.length === 3 && runRows4.every((x) => x.mine === true),
  'A4e 发信方视角 mine 全为 true（只认本轮那三条：旧行里乙发来的当然是 false，混在一起判就是判据自己出错）',
  '命中=' + runRows4.length + ' mine=' + runRows4.map((x) => x.mine).join(','))
const bobView = await api('GET', '/api/pm/thread/' + alice.uid + '?size=100', bob.token)
const bobRun = rows(bobView).filter((x) => runIds4.indexOf(x.id) >= 0)
check(bobView.code === 0 && bobRun.length === 3 && bobRun.every((x) => x.mine === false),
  'A4f 同一批消息在乙的会话里 mine 全为 false（视角是参数而不是字段）',
  '会话总长=' + rows(bobView).length + ' 本轮命中=' + bobRun.length + ' mine=' + bobRun.map((x) => x.mine).join(','))

// A5 分页方向：只有「往更旧翻」一个方向。期望值同样从 ids4（同一次全量读）现算：
//   size=2 ⇒ 最新两条 = ids4 末两条，游标 = 该页最旧一条；
//   再带 beforeId=游标 ⇒ ids4 的倒数第三、第四条（slice(-4,-2)，不足四条时自动退化成一条）。
const a5 = await api('GET', '/api/pm/thread/' + bob.uid + '?size=2', alice.token)
const page1 = rows(a5)
const want1 = ids4.slice(-2)
check(page1.map((x) => x.id).join(',') === want1.join(',') && a5.data.nextCursor === want1[0] &&
  a5.data.hasMore === (ids4.length > 2),
  'A5 size=2 取最新两条、游标=该页最旧一条、hasMore 跟着「还有更旧的」走',
  'ids=' + page1.map((x) => x.id).join(',') + ' 期望=' + want1.join(',') + ' nextCursor=' + a5.data.nextCursor +
    ' hasMore=' + a5.data.hasMore + ' 全量=' + ids4.length)
const a5b = await api('GET', '/api/pm/thread/' + bob.uid + '?size=2&beforeId=' + a5.data.nextCursor, alice.token)
const want0 = ids4.slice(-4, -2)
const page2 = rows(a5b)
check(page2.map((x) => x.id).join(',') === want0.join(',') && page2.every((x) => x.id < a5.data.nextCursor),
  'A5b beforeId 游标往旧翻：恰好是再往前的两条，不重叠也不跳号',
  'ids=' + page2.map((x) => x.id).join(',') + ' 期望=' + want0.join(',') + ' hasMore=' + a5b.data.hasMore)
const a5c = await api('GET', '/api/pm/conversations?size=999', alice.token)
check(a5c.code === 0, 'A5c size=999 不报错（normalizeSize 夹到 fetch-max=100，不是一行 500）',
  'listLen=' + rows(a5c).length)
// A6 六道参数闸门（都应当是 10001，且都在「查库之前」就失败：坏内容不许进后面的表）
const a6 = []
a6.push(await api('POST', '/api/pm/send', alice.token, { toUserId: bob.uid, content: '   ', clientMsgId: key('a6blank') }))
a6.push(await api('POST', '/api/pm/send', alice.token, { toUserId: bob.uid, content: '啊'.repeat(1001), clientMsgId: key('a6long') }))
a6.push(await api('POST', '/api/pm/send', alice.token, { toUserId: bob.uid, content: txt('a6key'), clientMsgId: 'k'.repeat(65) }))
a6.push(await api('POST', '/api/pm/send', alice.token, { toUserId: bob.uid, content: txt('a6type'), msgType: 'system' }))
a6.push(await api('POST', '/api/pm/send', alice.token, { toUserId: bob.uid, content: 'https://外部图床.example/a.png', msgType: 'image', clientMsgId: key('a6img') }))
a6.push(await api('POST', '/api/pm/send', alice.token, { toUserId: alice.uid, content: txt('a6self'), clientMsgId: key('a6self') }))
const a6names = ['空内容', '码点>1000', 'clientMsgId>64', 'msgType 非白名单', 'image 指向站外', '发给自己']
a6.forEach((r, i) => check(r.code === 10001, 'A6-' + (i + 1) + ' ' + a6names[i] + ' → 10001', 'HTTP=' + r.status + ' code=' + r.code + ' msg=' + r.msg))
const a6bad = await api('GET', '/api/pm/thread/0', alice.token)
check(a6bad.code === 10001, 'A6-7 peerId=0（非法会话对象）→ 10001', 'code=' + a6bad.code)
const a6self = await api('GET', '/api/pm/thread/' + alice.uid, alice.token)
check(a6self.code === 10001, 'A6-8 查自己的会话 → 10001', 'code=' + a6self.code)
const a6ghost = await api('GET', '/api/pm/thread/999999999', alice.token)
check(a6ghost.code === 20001, 'A6-9 查无此人的会话 → 20001（不是空昵称 200）', 'code=' + a6ghost.code + ' msg=' + a6ghost.msg)

// A7 收件人不存在 / 已注销：404 档
const a7 = await api('POST', '/api/pm/send', alice.token, { toUserId: 999999999, content: txt('a7'), clientMsgId: key('a7') })
check(a7.code === 20001, 'A7 发给不存在的收件人 → 20001', 'code=' + a7.code + ' msg=' + a7.msg)

// A8 词库 BLOCK：先查库、后落库，所以这条既不该落库也不该建审核任务
const a8txt = key('a8') + ' 我这边有' + '枪支弹药' + '，你要吗'
const a8 = await api('POST', '/api/pm/send', alice.token, { toUserId: bob.uid, content: a8txt, clientMsgId: key('a8') })
check(a8.code === 50001, 'A8 命中黑词（政治违法组 BLOCK）→ 50001 CONTENT_REJECTED', 'code=' + a8.code + ' msg=' + a8.msg)
const a8b = await api('GET', '/api/pm/thread/' + bob.uid + '?size=100', alice.token)
const hit8 = rows(a8b).filter((x) => String(x.content).indexOf('枪支弹药') >= 0)
check(hit8.length === 0, 'A8b 被拦截的内容没有落库（最新 100 条里查不到那句黑词原文；会话条数会跟着判据一起长，所以判「查不到」而不是判总数）',
  '命中=' + hit8.length + ' 会话总长=' + rows(a8b).length)

// A9 图片私信：**先当场传一张真图**，拿服务端回的值再发私信。
//
// 🔴 Gate5 第一轮这里写死了一个字符串，两种错法都撞在同一个事实下面：
//   ① '/uploads/2026-09-28/xxx.png' 是**扁平日期**，HTTP 404。真目录是三层
//      uploads/yyyy/MM/dd（ImageUploadService L48 的 DATE_DIR、FileController L46 的
//      @Operation 文案「/uploads/yyyy/MM/dd/uuid.ext」，实测 GET 8080 那个扁平路径 404、
//      三层路径 200 image/png）；
//   ② 就算抄一个库里存在的历史 url，那也只证明「那天传上去过」，证不了今天这条链路是通的。
// 更要分清的是：PmService 只校验「/uploads/ 前缀」（L117 UPLOAD_PREFIX、L785），
// 所以「图片私信落库成功」和「图片在浏览器里显示得出来」是两件事 ——
// 扁平日期的 url 照样能落库，然后在页面上碎成一个裂口图标。所以这里判三条：
// A9-0 现场上传拿到三层 url、A9-0b 该 url 真取得回 image/* 字节、A9/A9b 落库与列表摘要。
const IMG_SEED = process.env.PROBE_IMG_FILE ||
  path.join(ROOT, 'backend', 'uploads', '2026', '09', '21', 'fdc43d2e9c1748cba617c6cd66ba5db0.png')
let imgPath = null
if (!fs.existsSync(IMG_SEED)) {
  check(false, 'A9-0 上传图片拿到站内 url', '种子图片不存在：' + IMG_SEED + '（可用 PROBE_IMG_FILE 指一张真图）')
} else {
  const seedBytes = fs.statSync(IMG_SEED).size
  const up9 = await apiUpload('/api/files/image', alice.token, IMG_SEED)
  const data9 = up9.json && up9.json.code === 0 ? up9.json.data : null
  imgPath = data9 ? data9.url : null
  check(!!imgPath && /^\/uploads\/\d{4}\/\d{2}\/\d{2}\/[A-Za-z0-9_-]+\.(?:png|jpe?g|gif)$/i.test(imgPath),
    'A9-0 现场上传一张图，拿到 uploads/yyyy/MM/dd 三层 url（不是扁平日期目录）',
    'HTTP=' + up9.status + ' code=' + (up9.json && up9.json.code) + ' 种子=' + seedBytes + 'B data=' + JSON.stringify(data9))
  if (imgPath) {
    const get9 = await fetch(APIBASE + imgPath)
    const ct9 = get9.headers.get('content-type') || ''
    const len9 = get9.status === 200 ? (await get9.arrayBuffer()).byteLength : 0
    check(get9.status === 200 && ct9.indexOf('image/') === 0 && len9 > 0,
      'A9-0b 这个 url 真的取得回图片字节（浏览器 img 标签吃的就是它，扁平写法 404 就是这么暴露的）',
      'HTTP=' + get9.status + ' content-type=' + ct9 + ' bytes=' + len9)
  }
}
const a9 = imgPath
  ? await api('POST', '/api/pm/send', alice.token, { toUserId: bob.uid, content: imgPath, msgType: 'image', clientMsgId: key('a9') })
  : { code: -1, msg: '上传这一步就失败了，A9 不发信（不发一条注定显示不出来的图片私信）' }
if (a9.code === 0) {
  check(a9.data.msgType === 'image' && a9.data.content === imgPath, 'A9 图片私信落库（msgType=image，内容为 /uploads 路径）',
    'id=' + field(a9, 'id') + ' content=' + field(a9, 'content') + ' code=' + a9.code)
  const conv9 = await api('GET', '/api/pm/conversations?size=20', bob.token)
  const row9 = rows(conv9).find((x) => x.peerId === alice.uid)
  check(row9 && row9.lastContent === '[图片]', 'A9b 会话列表把图片摘要成「[图片]」（正文不许漏在列表页）',
    'lastContent=' + (row9 && row9.lastContent) + ' lastMsgType=' + (row9 && row9.lastMsgType))
} else {
  check(false, 'A9 图片私信落库', 'code=' + a9.code + ' msg=' + a9.msg + ' imgPath=' + imgPath)
}

// A10 未读与已读：乙的未读来自甲的发信，markRead 只清「我收到的」那一半
const a10s1 = await api('POST', '/api/pm/send', bob.token, { toUserId: alice.uid, content: txt('a10b1'), clientMsgId: key('a10b1') })
const a10s2 = await api('POST', '/api/pm/send', bob.token, { toUserId: alice.uid, content: txt('a10b2'), clientMsgId: key('a10b2') })
check(a10s1.code === 0 && a10s2.code === 0, 'A10 乙回发两条成功', 'ids=' + [a10s1.data && a10s1.data.id, a10s2.data && a10s2.data.id].join(','))
const a10u = await api('GET', '/api/pm/unread', alice.token)
check(a10u.data.total >= 2 && a10u.data.byPeer[String(bob.uid)] >= 2, 'A10b 甲的未读汇总含乙（byPeer 只带未读>0 的人）',
  'total=' + field(a10u, 'total') + ' byPeer=' + JSON.stringify(field(a10u, 'byPeer') || null) + ' peers=' + (field(a10u, 'peers') || []).length)
const a10r = await api('POST', '/api/pm/read', alice.token, { peerId: bob.uid })
check(a10r.code === 0 && a10r.data.kind === 'read' && a10r.data.count >= 2 && a10r.data.unread === 0,
  'A10c 已读上报返回 ack{kind,count,unread}，unread 归零', 'count=' + field(a10r, 'count') + ' unread=' + field(a10r, 'unread') + ' peerId=' + field(a10r, 'peerId') + ' code=' + a10r.code)
const a10u2 = await api('GET', '/api/pm/unread', alice.token)
check(!Object.prototype.hasOwnProperty.call(field(a10u2, 'byPeer') || {}, String(bob.uid)), 'A10d 已读之后 byPeer 里不再有乙（0 不发送）',
  'total=' + field(a10u2, 'total') + ' byPeer=' + JSON.stringify(field(a10u2, 'byPeer') || null))
const a10r2 = await api('POST', '/api/pm/read', alice.token, { peerId: bob.uid })
check(a10r2.data.count === 0, 'A10e 重复上报已读翻 0 行（第二份标签页不会重播已读动画）', 'count=' + field(a10r2, 'count') + ' code=' + a10r2.code)
const a10selfRead = await api('POST', '/api/pm/read', alice.token, { peerId: bob.uid, upToId: 99999999999 })
check(a10selfRead.code === 0 && a10selfRead.data.count === 0, 'A10f 把别人的 id 当 upToId 也只是 0 行（谁的数据谁能改，判据在 SQL 里）',
  'count=' + field(a10selfRead, 'count') + ' code=' + a10selfRead.code)

// A11 在线查询（REST 侧只回答「你问的这些人里谁在线」，不广播名单）
const a11 = await api('GET', '/api/pm/online?peerIds=' + bob.uid + '&peerIds=' + carol.uid, alice.token)
check(a11.code === 0 && has(a11.data, ['online']) && Array.isArray(a11.data.online),
  'A11 GET /online?peerIds=1&peerIds=2（逗号方括号写法会被 Spring 拒，必须重复键）',
  'online=' + JSON.stringify(field(a11, 'online') || null) + ' userId=' + JSON.stringify(field(a11, 'userId') || null))
// A12 拉黑（FR6.7 双向）。注意 action 的字面值是 block / unblock，不是 add / remove——
// 这两个常量在 PmService L119-120，前端按它决定按钮文案，写错就是「点了没反应」。
// 拉黑之前先量一次历史条数与 id 清单：FR6.7 的判据是「拉黑不抹历史」，
// 那就只能是「拉黑前后的那一串 id 逐字相同」，硬编码一个 4 会在第二次跑的时候假红
// （Gate5 第一轮实测 len=6，因为库里的行会跟着判据一起长）。
const a12pre = await api('GET', '/api/pm/thread/' + bob.uid + '?size=50', alice.token)
const preIds = rows(a12pre).map((x) => x.id)
say('  …拉黑前该会话已有 ' + preIds.length + ' 条：' + preIds.join(','))
const a12self = await api('POST', '/api/pm/block/' + alice.uid, alice.token)
check(a12self.code === 10001, 'A12-0 拉黑自己 → 10001', 'code=' + a12self.code + ' msg=' + a12self.msg)
const a12 = await api('POST', '/api/pm/block/' + bob.uid + '?reason=' + encodeURIComponent('Gate5 取证：暂时不想被打扰'), alice.token)
check(a12.code === 0 && a12.data.targetId === bob.uid && a12.data.action === 'block' && a12.data.changed === true && a12.data.blocked === true,
  'A12-1 拉黑生效 {targetId,action=block,changed=true,blocked=true}', 'code=' + a12.code + ' data=' + JSON.stringify(a12.data || null))
const a12b = await api('GET', '/api/pm/blocks', alice.token)
const blk = Array.isArray(a12b.data) ? a12b.data.find((x) => x.peerId === bob.uid) : undefined
check(!!blk && blk.reason === 'Gate5 取证：暂时不想被打扰' && !!blk.blockedAt, 'A12-2 名单里列得出这一条（含原因与时间）',
  'size=' + (Array.isArray(a12b.data) ? a12b.data.length : 0) + ' item=' + JSON.stringify(blk || null))
const a12c = await api('POST', '/api/pm/send', bob.token, { toUserId: alice.uid, content: txt('a12blocked'), clientMsgId: key('a12blocked') })
check(a12c.code === 30005, 'A12-3 被拉黑的一方再发 → 30005 PM_BLOCKED', 'code=' + a12c.code + ' msg=' + a12c.msg)
const a12d = await api('GET', '/api/pm/thread/' + bob.uid + '?size=50', alice.token)
check(a12d.data.blocked === true, 'A12-4 会话页 blocked=true（双向判定，输入框该禁用）', 'blocked=' + field(a12d, 'blocked') + ' code=' + a12d.code)
const postIds = rows(a12d).map((x) => x.id)
check(preIds.length > 0 && postIds.join(',') === preIds.join(','),
  'A12-5 拉黑不抹历史（前后 id 清单逐字相同，需求 FR6.7 的落地口径）',
  '拉黑前 ' + preIds.length + ' 条 / 拉黑后 ' + postIds.length + ' 条 同序=' + (postIds.join(',') === preIds.join(',')))
const a12e = await api('POST', '/api/pm/send', bob.token, { toUserId: alice.uid, content: key('a12crisis') + ' 我真的想' + '结束生命' + '，也想' + '割腕', clientMsgId: key('a12crisis') })
check(a12e.code === 30005, 'A12-6 被拉黑之后的求助消息仍然被拦（私信这一半拦住了）', 'code=' + a12e.code)
const a12f = await api('GET', '/api/pm/thread/' + bob.uid + '?size=100', alice.token)  // 夹到 fetch-max=100：判「没落库」要往全量里找，翻不够深就是假绿
const a12leak = rows(a12f).filter((x) => String(x.content).indexOf(key('a12crisis')) >= 0)
// 旧口径是「整段会话里查不到 结束生命」。第 43 轮它红了：同一个会话里 A14 那两条危机样本是**允许落库**的,
// 上一趟跑留下的行这一趟还在（探针不删数据）,于是「没有落库」被历史数据撞红。改成查本轮那条唯一标记。
check(a12leak.length === 0, 'A12-7 被拦的危机私信没有落库（按本轮唯一标记查 = 0 行；建单走库侧核对，见 README 增量）',
  'A12-7 那条被拦的危机私信没有落库（建单走库侧核对，见 README 的行数增量）',
  '命中=' + a12leak.length + ' 会话总长=' + rows(a12f).length)
const a12g = await api('POST', '/api/pm/block/' + bob.uid, alice.token)
check(a12g.code === 0 && a12g.data.changed === false && a12g.data.blocked === true, 'A12-8 重复拉黑 changed=false（不重复建行）',
  'changed=' + field(a12g, 'changed') + ' code=' + a12g.code)
const a12h = await api('DELETE', '/api/pm/block/' + bob.uid, alice.token)
check(a12h.code === 0 && a12h.data.action === 'unblock' && a12h.data.changed === true && a12h.data.blocked === false,
  'A12-9 解除拉黑 {action=unblock,blocked=false}', 'data=' + JSON.stringify(a12h.data))
const a12i = await api('POST', '/api/pm/send', bob.token, { toUserId: alice.uid, content: txt('a12again'), clientMsgId: key('a12again') })
check(a12i.code === 0, 'A12-10 解除之后发信恢复（不是永久封死）', 'code=' + a12i.code + ' id=' + (a12i.data && a12i.data.id))

// A13 举报私信（T5.7）：直接建/升级 audit_task，不写 content_report
const a13 = await api('POST', '/api/pm/report', alice.token, { messageId: m2.id, reason: 'abuse', description: 'Gate5 取证：举报这条' })
check(a13.code === 0 && a13.data.taskId > 0 && a13.data.status === 'PENDING' && /小时内/.test(a13.data.tip || ''),
  'A13-1 举报受理 → {taskId,status=PENDING,tip}', 'data=' + JSON.stringify(a13.data))
const a13b = await api('POST', '/api/pm/report', alice.token, { messageId: m2.id, reason: 'spam', description: '同一目标第二次举报' })
check(a13b.code === 0 && a13b.data.taskId === a13.data.taskId, 'A13-2 同一目标的待审任务只有一张（uk_target_pending 兜住）',
  '第一次 taskId=' + field(a13, 'taskId') + ' 第二次 taskId=' + field(a13b, 'taskId'))
const a13c = await api('POST', '/api/pm/report', carol.token, { messageId: m2.id, reason: 'abuse' })
check(a13c.code === 30006, 'A13-3 第三人举报别人的私信 → 30006（拿自增 id 塞不进别人的会话）', 'code=' + a13c.code)
const a13d = await api('POST', '/api/pm/report', alice.token, { messageId: 999999999, reason: 'abuse' })
check(a13d.code === 30006, 'A13-4 举报不存在的消息 → 30006', 'code=' + a13d.code)
const a13e = await api('POST', '/api/pm/report', alice.token, { messageId: m2.id, reason: 'not-a-reason' })
check(a13e.code === 10001, 'A13-5 理由不在白名单 → 10001（下拉六项之外的值进不了备注）', 'code=' + a13e.code + ' msg=' + a13e.msg)
const a13f = await api('POST', '/api/pm/report', alice.token, { reason: 'abuse' })
check(a13f.code === 10001, 'A13-6 缺 messageId → 10001', 'code=' + a13f.code)

// A14 危机分级走私信（T5.4）：L3 / L2 两个样本，词面必须逐字命中词库 risk 组
const a14 = await api('POST', '/api/pm/send', alice.token, { toUserId: bob.uid, content: '我已经决定' + '结束生命' + '，也想过' + '割腕' + '的方式', clientMsgId: key('a14l3') })
check(a14.code === 0 && a14.data.riskLevel === 'L3', 'A14-1 含 L3 词（结束生命/割腕）→ riskLevel=L3',
  'code=' + a14.code + ' riskLevel=' + (a14.data && a14.data.riskLevel))
check(/12356/.test(a14.data.alert || '') && /你写的这句话/.test(a14.data.alert || ''),
  'A14-2 发信方看到的是「自己」那套求助话术，且含热线 12356', 'alert=' + field(a14, 'alert') + ' code=' + a14.code)
const a14b = await api('GET', '/api/pm/thread/' + alice.uid + '?size=50', bob.token)
const rowL3 = rows(a14b).find((x) => x.id === a14.data.id)
check(!!rowL3 && /对方此刻/.test(rowL3.alert || '') && /12356/.test(rowL3.alert || ''),
  'A14-3 收信方看到的是「对方可能需要支持」那套文案（同一行两套话术，按视角拼）', 'alert=' + (rowL3 && rowL3.alert))
const a14c = await api('POST', '/api/pm/send', alice.token, { toUserId: bob.uid, content: '最近' + '活着没意思' + '，睡也睡不着', clientMsgId: key('a14l2') })
check(a14c.code === 0 && a14c.data.riskLevel === 'L2' && !!a14c.data.alert,
  'A14-4 risk 组但不含 L3 方式/计划词 → L2（同样出求助卡，词面通道输出域只有 L0/L2/L3）',
  'riskLevel=' + (a14c.data && a14c.data.riskLevel))

// A15 鉴权闸门：私信一口都没带令牌就是 10002/401，不许退化成「空列表 200」
const a15 = await api('GET', '/api/pm/unread', null)
check(a15.code === 10002 && a15.status === 401, 'A15 无令牌读私信 → 10002 / HTTP 401', 'HTTP=' + a15.status + ' code=' + a15.code)
if (process.env.GATE_MUTED_ACCT) {
  const muted = await login(process.env.GATE_MUTED_ACCT)
  const a16 = await api('POST', '/api/pm/send', muted.token, { toUserId: bob.uid, content: '禁言号试发一条私信', clientMsgId: key('a16') })
  check(a16.code === 10003, 'A16 MUTED 账号发私信 → 10003 FORBIDDEN（能看能赞不能说）', 'uid=' + muted.uid + ' code=' + a16.code + ' msg=' + a16.msg)
} else {
  say('SKIP A16 MUTED 发信 → 10003（库里演示号全是 ACTIVE，跑法见 README：先把一个号置 MUTED 再带 GATE_MUTED_ACCT 补跑）')
}
say('')
say('---- B 组：STOMP 实时线（裸 WS /ws-native，自写最小帧客户端）----')
// B1 握手鉴权：没有效令牌就连 CONNECTED 都拿不到。拦截器是在 beforeHandshake 里返回 false，
// 对 Node 侧的表现是「握手直接失败 / 连接被关」，不会给一个能发帧的 socket。
let b1 = null
try { await new StompWs('坏令牌', 'a.b.c').connect() } catch (e) { b1 = e.message }
check(!!b1, 'B1-1 坏令牌 → 握手被拒（拿不到 CONNECTED）', b1 || '居然连上了')
let b1b = null
try { await new StompWs('无令牌', '').connect() } catch (e) { b1b = e.message }
check(!!b1b, 'B1-2 完全不带令牌 → 握手被拒', b1b || '居然连上了')

const aWs = new StompWs('甲', alice.token)
await aWs.connect()
check(!!aWs.connected && String(aWs.connected.headers.version || '') === '1.2',
  'B2 甲 CONNECT 完成，accept-version 协商到 1.2', 'headers=' + JSON.stringify(aWs.connected.headers))
aWs.subscribe('a-private', '/user/queue/private').subscribe('a-ack', '/user/queue/ack')
  .subscribe('a-notify', '/user/queue/notify').subscribe('a-alert', '/user/queue/alert')
  .subscribe('a-pres', '/topic/presence').subscribe('a-ping', '/user/queue/ping')

// B3 在线presence：甲已经在听 /topic/presence，乙的连接应当触发一次广播
const bWs = new StompWs('乙', bob.token)
await bWs.connect()
bWs.subscribe('b-private', '/user/queue/private').subscribe('b-ack', '/user/queue/ack')
  .subscribe('b-notify', '/user/queue/notify').subscribe('b-alert', '/user/queue/alert').subscribe('b-ping', '/user/queue/ping')
const pres1 = await aWs.next((f) => f.headers.id === 'a-pres', 8000, 'B3 presence').catch(() => null)
check(!!pres1 && pres1.json && typeof pres1.json.onlineCount === 'number' && pres1.json.onlineCount >= 2,
  'B3-1 乙上线触发 /topic/presence 广播，载荷只有 {onlineCount,ts}（不含任何 id）',
  'json=' + JSON.stringify(pres1 && pres1.json))
check(!!pres1 && !Object.prototype.hasOwnProperty.call(pres1.json || {}, 'userId'),
  'B3-2 presence 载荷里确实没有 userId（广播不得携带个人身份）', 'keys=' + Object.keys((pres1 && pres1.json) || {}).join(','))
const b3online = await api('GET', '/api/pm/online?peerIds=' + bob.uid + '&peerIds=' + carol.uid, alice.token)
check(b3online.code === 0 && (b3online.data.online || []).indexOf(bob.uid) >= 0,
  'B3-3 乙在 WS 上时 GET /online 列得出乙（在线判定与广播同源）', 'online=' + JSON.stringify(b3online.data.online))

// B4 心跳口：/app/ping → /user/queue/ping 的 pong
aWs.sendRaw('/app/ping', {})
const pong = await aWs.next((f) => f.headers.id === 'a-ping' && f.json && f.json.kind === 'pong', 6000, 'B4 pong').catch(() => null)
check(!!pong, 'B4 /app/ping 回 {kind:pong} 到 /user/queue/ping（前端 25s 保活靠它）', 'frame=' + JSON.stringify(pong && pong.json))

// B5 收发闭环：甲 SEND /app/private → 自己拿 sent 回执，乙的 /queue/private 收到同一行
const b5key = key('b5')
// 🔴 sent 与 delivered 谁先到不保证（第 44 轮现查，写进手册㊵）：/app/private 的 sent 走 @SendToUser 返回值,
//    要等方法返回才发；delivered 由 PmRealtimeListener 在 AFTER_COMMIT（也就是 commit() 里面）同步推,
//    于是乙在线时 delivered 常常排在 sent 前面到达同一条连接。前端 pm.js:552 对 sent 写的是
//    `status || 'sent'`（单调，不会把「已送达」降级回去），所以这不是产品缺陷；但探针只从游标往后找,
//    sent 一消费就会把缓冲区里那条 delivered 永久跳过——那是取证脚本自己的缺陷,不修就会冒充 B 组红。
const b5mark = aWs.frames.length
aWs.sendRaw('/app/private', { toUserId: bob.uid, content: txt('b5') + '😀', clientMsgId: b5key, msgType: 'text' })
const ackSent = await aWs.next((f) => f.headers.id === 'a-ack' && f.json && f.json.kind === 'sent' && f.json.clientMsgId === b5key, 8000, 'B5 sent').catch(() => null)
check(!!ackSent && ackSent.json.id > 0 && ackSent.json.peerId === bob.uid && !!ackSent.json.at,
  'B5-1 甲从 /user/queue/ack 拿到 {kind:sent,clientMsgId,id,peerId,at}', 'ack=' + JSON.stringify(ackSent && ackSent.json))
const b5recv = await bWs.next((f) => f.headers.id === 'b-private' && f.json && f.json.id === (ackSent && ackSent.json.id), 8000, 'B5 recv').catch(() => null)
check(!!b5recv && b5recv.json.mine === false && b5recv.json.content === txt('b5') + '😀',
  'B5-2 乙的 /user/queue/private 真的收到了这一帧（跨连接投递成立 = 私信可用）',
  'dest=' + (b5recv && b5recv.headers.destination) + ' mine=' + (b5recv && b5recv.json.mine))

// B6 送达回执：乙在线，所以甲随后要收到 delivered（顺序是「先推本体，成功才抬状态」）
const ackDeliv = await aWs.next((f) => f.headers.id === 'a-ack' && f.json && f.json.kind === 'delivered' && f.json.id === (ackSent && ackSent.json.id), 8000, 'B6 delivered', b5mark).catch(() => null)
check(!!ackDeliv, 'B6-1 收件人在线 → 发信方拿到 kind=delivered（本轮实测：它可能比 sent 先到一帧，所以从 b5mark 往后找）', 'ack=' + JSON.stringify(ackDeliv && ackDeliv.json))
const b6thread = await api('GET', '/api/pm/thread/' + bob.uid + '?size=5', alice.token)
const b6id = ackSent && ackSent.json ? ackSent.json.id : -1   // 上游没拿到 sent 也不许把整趟探针跑崩：-1 只会让本条判据红，不会让后面的 B7–B13 和整个 C 组失去机会
const b6row = rows(b6thread).find((x) => x.id === b6id)
check(!!b6row && b6row.status === 'delivered', 'B6-2 库里那一行 status 也翻成 delivered（REST 与 WS 读的是同一行）',
  'status=' + (b6row && b6row.status))

// B7 站内通知也推了一条（红点不依赖私信本体那一路）
const b7notify = await bWs.next((f) => f.headers.id === 'b-notify', 8000, 'B7 notify').catch(() => null)
check(!!b7notify && b7notify.json && b7notify.json.type === 'dm' && typeof b7notify.json.unread === 'number',
  'B7-1 乙的 /user/queue/notify 收到 {type:dm,id,unread,ts}（私信折成 dm，红点当场亮）',
  'json=' + JSON.stringify(b7notify && b7notify.json))
check(!!b7notify && Object.keys(b7notify.json).length === 4, 'B7-2 通知线格式恰好四键，没有正文',
  'keys=' + Object.keys((b7notify && b7notify.json) || {}).join(','))
// B8 离线不抬 delivered：乙断开后甲再发一条，甲只有 sent，没有 delivered，库里停在 sent
await bWs.disconnect()
const b8key = key('b8')
aWs.sendRaw('/app/private', { toUserId: bob.uid, content: txt('b8') + '（发的时候你不在）', clientMsgId: b8key })
const ack8 = await aWs.next((f) => f.headers.id === 'a-ack' && f.json && f.json.kind === 'sent' && f.json.clientMsgId === b8key, 8000, 'B8 sent').catch(() => null)
check(!!ack8, 'B8-1 收件人离线时发信方照样拿到 sent（落库优先，推送只是下游订阅者）', 'ack=' + JSON.stringify(ack8 && ack8.json))
const b8msgId = ack8 && ack8.json.id
const nDeliv = await aWs.noneWithin((f) => f.headers.id === 'a-ack' && f.json && f.json.kind === 'delivered' && f.json.id === b8msgId, 2500)
check(nDeliv === 0, 'B8-2 收件人离线 → 2.5s 内没有 delivered 回执（不谎报送达）', 'delivered 帧数=' + nDeliv)
const b8thread = await api('GET', '/api/pm/thread/' + bob.uid + '?size=50', alice.token)
const b8row = rows(b8thread).find((x) => x.id === b8msgId)
check(!!b8row && b8row.status === 'sent', 'B8-3 库里那一行停在 sent（等着被重投作业看见）', 'status=' + (b8row && b8row.status))

// B9 重投作业：乙重新上线后，那条离线期间的私信要被补推（retry-interval 5s + grace 3s，所以窗口给到 25s）
const bWs2 = new StompWs('乙2', bob.token)
await bWs2.connect()
bWs2.subscribe('b-private', '/user/queue/private').subscribe('b-ack', '/user/queue/ack')
  .subscribe('b-alert', '/user/queue/alert').subscribe('b-notify', '/user/queue/notify')
  .subscribe('b-ping', '/user/queue/ping')
const b9recv = await bWs2.next((f) => f.headers.id === 'b-private' && f.json && f.json.id === b8msgId, 25000, 'B9 重投').catch(() => null)
check(!!b9recv, 'B9-1 重新上线后 PmDeliveryRetryJob 把离线那条补推到 /user/queue/private',
  'id=' + (b9recv && b9recv.json.id) + ' status=' + (b9recv && b9recv.json.status))
const ack9 = await aWs.next((f) => f.headers.id === 'a-ack' && f.json && f.json.kind === 'delivered' && f.json.id === b8msgId, 15000, 'B9 delivered').catch(() => null)
check(!!ack9, 'B9-2 补推成功后发信方才收到 delivered', 'ack=' + JSON.stringify(ack9 && ack9.json))

// B10 已读回执推给「发信方」而不是读者自己
const b10key = key('b10')
aWs.sendRaw('/app/private', { toUserId: bob.uid, content: txt('b10') + '（这条会被读掉）', clientMsgId: b10key })
const ack10 = await aWs.next((f) => f.headers.id === 'a-ack' && f.json && f.json.kind === 'sent' && f.json.clientMsgId === b10key, 8000, 'B10 sent').catch(() => null)
check(!!ack10, 'B10-1 第三条 WS 私信发出（为已读回执准备未读行）', 'id=' + (ack10 && ack10.json.id))
bWs2.sendRaw('/app/read', { peerId: alice.uid })
const ack10b = await bWs2.next((f) => f.headers.id === 'b-ack' && f.json && f.json.kind === 'read', 8000, 'B10 read-self').catch(() => null)
check(!!ack10b && ack10b.json.count >= 1 && ack10b.json.unread === 0,
  'B10-2 读者自己拿到 {kind:read,count,unread=0}（气泡上的已读勾当场打）', 'ack=' + JSON.stringify(ack10b && ack10b.json))
const ack10c = await aWs.next((f) => f.headers.id === 'a-ack' && f.json && f.json.kind === 'read' && f.json.peerId === bob.uid, 8000, 'B10 read-sender').catch(() => null)
check(!!ack10c && ack10c.json.count >= 1, 'B10-3 发信方（甲）收到 kind=read 且 peerId=读者（回执的唯一读者是发信方）',
  'ack=' + JSON.stringify(ack10c && ack10c.json))
bWs2.sendRaw('/app/read', { peerId: alice.uid })
const dupRead = await aWs.noneWithin((f) => f.headers.id === 'a-ack' && f.json && f.json.kind === 'read', 2500)
check(dupRead === 0, 'B10-4 第二次重复上报翻 0 行 → 不发事件（两个标签页不会把「已读」动画播两遍）',
  '甲收到的重复 read 帧=' + dupRead)

// B11 错误不断线：被拉黑的一方从 WS 发信，只回一条 error ack，socket 必须还活着
const b11blk = await api('POST', '/api/pm/block/' + alice.uid + '?reason=' + encodeURIComponent('Gate5 B11'), bob.token)
check(b11blk.code === 0 && b11blk.data.blocked === true, 'B11-0 乙拉黑甲（为 WS 错误分支造现场）', JSON.stringify(b11blk.data))
const b11key = key('b11')
aWs.sendRaw('/app/private', { toUserId: bob.uid, content: txt('b11'), clientMsgId: b11key })
const ack11 = await aWs.next((f) => f.headers.id === 'a-ack' && f.json && f.json.kind === 'error' && f.json.code === 30005, 8000, 'B11 error').catch(() => null)
check(!!ack11 && !!ack11.json.tip, 'B11-1 WS 发信被隐私闸门拦下 → /queue/ack 收到 {kind:error,code:30005,tip}',
  'ack=' + JSON.stringify(ack11 && ack11.json))
aWs.sendRaw('/app/ping', {})
const pong11 = await aWs.next((f) => f.headers.id === 'a-ping' && f.json && f.json.kind === 'pong', 6000, 'B11 pong').catch(() => null)
check(!!pong11, 'B11-2 报错之后连接仍然可用（业务错误绝不拆 socket）', 'closed=' + aWs.closed)
const b11un = await api('DELETE', '/api/pm/block/' + alice.uid, bob.token)
check(b11un.code === 0 && b11un.data.blocked === false, 'B11-3 解除（把现场还原给后面的判据）', JSON.stringify(b11un.data))

// B12 危机走 WS：两边都拿到 /user/queue/alert，载荷恰好五键且没有正文
const b12key = key('b12')
// B12 起点：alert 帧可能排在「私信本体」那一帧之前，两条 next 都必须从这个刻度往后找，
// 否则前一个 next 把游标推过去，缓冲区里那帧 alert 就永久跳过了（同 B6 那颗 sent/delivered 逆序雷）。
const b12markA = aWs.frames.length
const b12markB = bWs2.frames.length
aWs.sendRaw('/app/private', { toUserId: bob.uid, content: '我撑不住了，想' + '结束生命', clientMsgId: b12key })
const ack12 = await aWs.next((f) => f.headers.id === 'a-ack' && f.json && f.json.kind === 'sent' && f.json.clientMsgId === b12key, 8000, 'B12 sent').catch(() => null)
check(!!ack12, 'B12-1 WS 发出的危机私信同样落库（两个入口一条判据）', 'id=' + (ack12 && ack12.json.id))
const alertA = await aWs.next((f) => f.headers.id === 'a-alert', 10000, 'B12 alertA', b12markA).catch(() => null)
const alertB = await bWs2.next((f) => f.headers.id === 'b-private' && f.json && f.json.riskLevel === 'L3', 10000, 'B12 recv', b12markB).catch(() => null)
const alertB2 = await bWs2.next((f) => f.headers.id === 'b-alert', 10000, 'B12 alertB', b12markB).catch(() => null)
check(!!alertA && !!alertB2, 'B12-2 发信方与收信方都收到 /user/queue/alert（两侧各自一张卡）',
  'A=' + JSON.stringify(alertA && alertA.json) + ' B=' + JSON.stringify(alertB2 && alertB2.json))
const alertKeys = Object.keys((alertA && alertA.json) || {}).sort().join(',')
check(alertKeys === 'at,hotline,level,messageId,side', 'B12-3 alert 载荷恰好 5 键（最小展示原则：里面没有正文）', 'keys=' + alertKeys)
const alertRaw = JSON.stringify((alertA && alertA.json) || {})
check(!!alertA && alertA.json.level === 'L3' && String(alertA.json.hotline) === '12356' && alertRaw.indexOf('结束生命') < 0,
  'B12-4 alert 里查不到那句危机原文（等级/消息 id/视角/热线之外什么都没有）',
  'level=' + (alertA && alertA.json.level) + ' hotline=' + (alertA && alertA.json.hotline) + ' side=' + (alertA && alertA.json.side) + ' 正文命中=' + (alertRaw.indexOf('结束生命') >= 0))
check(!!alertB2 && alertB2.json.side === 'receiver' && alertB2.json.messageId === (ack12 && ack12.json.id),
  'B12-5 收信方那张卡 side=receiver 且指向同一条 messageId', 'json=' + JSON.stringify(alertB2 && alertB2.json))
check(!!alertB && !!alertB.json.alert && /12356/.test(alertB.json.alert),
  'B12-6 会话里那条气泡自带的求助文案由服务端拼好（前端不许自己编热线）', 'alert=' + (alertB && alertB.json.alert))

// B13 断开也广播 presence（在线数回落），并且断开后不再收到 delivered
await bWs2.disconnect()
// 判据口径：乙断开之后广播的在线数「不高于乙在线那一次」。原来写的是 <= 1,
// 而第 43 轮用户的 Chrome 就挂着一条演示号会话（wsdiag 现查：甲收帧 10 条、乙 4 条,而 presence 早在
// 甲连接时就广播过一次）——绝对值硬编码会把「别人也在线」读成「广播没回落」。
const pres2 = await aWs.next((f) => f.headers.id === 'a-pres' && f.json && typeof f.json.onlineCount === 'number' && f.json.onlineCount <= (pres1 && pres1.json ? pres1.json.onlineCount : 99), 8000, 'B13 pres-off').catch(() => null)
check(!!pres2, 'B13 乙断开 → /topic/presence 再广播一次，在线数不高于乙在线那一次',
  '断开前=' + (pres1 && pres1.json ? pres1.json.onlineCount : '?') + ' 断开后=' + JSON.stringify(pres2 && pres2.json))
const b13online = await api('GET', '/api/pm/online?peerIds=' + bob.uid, alice.token)
check((b13online.data.online || []).indexOf(bob.uid) < 0, 'B13-2 断开之后 GET /online 不再列乙', 'online=' + JSON.stringify(b13online.data.online))
await aWs.disconnect()
say('B14 两条 WS 连接已 DISCONNECT（甲累计收帧 ' + aWs.frames.length + '，乙2 累计收帧 ' + bWs2.frames.length + '）')

// B15/B16：传输协商与降级路径。浏览器不是直接 ws:// 连 8080 的，它连的是 dev server 的 /ws，由
// Vite 代理转发；SockJS 先 GET /ws/info 看 websocket 字段决定用哪条传输，拿不到 Upgrade（代理不透、
// 某些网关、公司 VPN）时退回 xhr-streaming / xhr-polling。这两条把「降级这条路真的通」钉住 ——
// 否则前端那句「已降级为轮询」只是代码里写着，没人证明服务器真会经这条通道推消息。
const g5info = await fetch(BASE + '/ws/info?token=' + encodeURIComponent(alice.token), { signal: AbortSignal.timeout(15000) }).catch(() => null)
const g5infoText = g5info ? await g5info.text().catch(() => '') : ''
let g5infoJson = null
try { g5infoJson = JSON.parse(g5infoText) } catch (e) { g5infoJson = null }
check(!!g5info && g5info.status === 200 && !!g5infoJson && g5infoJson.websocket === true,
  'B15 GET /ws/info 经 Vite 代理返回 200 且 websocket=true（SockJS 靠这个字段决定要不要走真 WebSocket）',
  'http=' + (g5info ? g5info.status : 'null') + ' body=' + g5infoText.slice(0, 160))
const g5xhr = await fetch(BASE + '/ws/000/' + String(Date.now()) + '/xhr?token=' + encodeURIComponent(alice.token), {
  method: 'POST', headers: { 'Content-Type': 'text/plain;charset=UTF-8' }, body: '[]', signal: AbortSignal.timeout(30000)
}).catch(() => null)
const g5xhrText = g5xhr ? await g5xhr.text().catch(() => '') : ''
check(!!g5xhr && g5xhr.status === 200 && g5xhrText.indexOf('o') === 0,
  'B16 SockJS xhr-polling 传输经代理拿到 open 帧（WebSocket 握不上时浏览器就靠这条收私信，代理必须两条都透）',
  'http=' + (g5xhr ? g5xhr.status : 'null') + ' 首帧=' + JSON.stringify(g5xhrText.slice(0, 4)))
// ==========================================================================================
// C 组：真浏览器（界面层证据 —— U9 私信列表 / U10 会话详情 / U13 顶栏角标）
// ==========================================================================================
// A 组证的是「REST 契约」，B 组证的是「服务器真的把帧推到了另一条连接上」，
// 这一组证最后一件事：**用户的屏幕上出现了什么**。阶段 3 那条教训（接口全绿、单测全绿、
// 构建全绿，三件事同时成立时界面仍然可以是坏的）到今天仍然有效，所以这里每张图都挂一条
// 能失败的判据，判据不成立就进 failures、影响退出码 ——「拍了但没人核对」的图等于没拍。
let browser = null

// 两个身份各一个上下文：私信要的是「两个人同时在」，共享一个上下文等于自己给自己发。
// 令牌只能用 addInitScript 种：路由守卫（router/index.js L57）读 localStorage 的 mindisle_token，
// goto 之后再塞会先被弹回 /login 一趟，回来时已经在另一个页面上，判据拍不到东西。
async function newPage (who, bag) {
  const context = await browser.newContext({
    viewport: DESKTOP, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', acceptDownloads: true
  })
  await context.addInitScript((t) => {
    window.localStorage.setItem('mindisle_token', t.access)
    window.localStorage.setItem('mindisle_refresh', t.refresh)
  }, { access: who.token, refresh: who.refresh })
  const page = await context.newPage()
  page.setDefaultTimeout(60000)
  page.on('console', (m) => {
    if (m.type() !== 'error') return
    if (/Failed to load resource/.test(m.text())) return
    bag.console.push(who.nick + ' console.error: ' + m.text().slice(0, 200))
  })
  page.on('pageerror', (e) => bag.pageerror.push(who.nick + ' pageerror: ' + String(e && e.message).slice(0, 200)))
  page.on('response', (res) => {
    const st = res.status()
    if (st < 400) return
    if (/favicon|\.woff2?(\?|$)/.test(res.url())) return
    bag.http.push(who.nick + ' HTTP ' + st + ' ' + res.url().replace(BASE, ''))
  })
  return { context, page }
}

// dev server 会不会吐旧模块（踩坑日志第 13 轮那颗雷）：判据跑在旧 transform 上只会**假绿**。
// C 组整组的效力都建立在「浏览器里跑的是我刚才改过的那份 pm.js」之上，所以这四条排在最前面。
async function freshness (label, diskRel, servedRel) {
  const disk = fs.readFileSync(path.join(ROOT, diskRel), 'utf8')
  const lits = [...disk.matchAll(/'([^'\\\n]{4,28})'|"([^"\\\n]{4,28})"/g)].map((m) => m[1] || m[2]).filter((x) => /[\u4e00-\u9fa5]/.test(x))
  let probes = lits.slice(-6)
  let how = '中文字面量'
  if (probes.length === 0) {
    probes = disk.split('\n').map((x) => x.trim()).filter((x) => /[\u4e00-\u9fa5]/.test(x)).map((x) => x.slice(0, 40)).slice(-6)
    how = '中文注释行'
  }
  let served = ''
  let status = 0
  try { const r = await fetch(BASE + servedRel); status = r.status; served = await r.text() } catch (e) { status = -1 }
  const missing = probes.filter((x) => served.indexOf(x) < 0)
  if (probes.length === 0) return check(false, 'C0[stale] ' + label + ' 磁盘源码里一个中文探针都没抽到，判据空跑不许算绿', 'diskRel=' + diskRel)
  return check(status === 200 && served.length > 200 && missing.length === 0,
    'C0[stale] ' + label + ' 的 dev server 模块体与磁盘源码同步（按' + how + '抽样 ' + probes.length + ' 条，全部命中）',
    'http=' + status + ' 字节=' + served.length + ' 缺=' + JSON.stringify(missing))
}

// 截图 + 判据绑成一次调用：先落图再记账，截图本身失败也要把原因写进读数里。
async function snap (page, file, label, ok, reading) {
  let note = reading || ''
  try { await page.screenshot({ path: path.join(OUT, file) }) }
  catch (e) { note += '  [截图失败：' + String((e && e.message) || e).slice(0, 90) + ']' }
  shot(file, label, ok, note)
  return ok
}

// ---- 三个页面读数器（会被序列化进浏览器执行，所以内部不许引用外部变量）----
function domList () {
  const clean = (s) => String(s == null ? '' : s).replace(/\s+/g, ' ').trim()
  const rows = Array.prototype.slice.call(document.querySelectorAll('.list .row'))
  const badgeOf = (r) => {
    const c = r.querySelector('.badge .el-badge__content') || r.querySelector('.badge')
    return c ? clean(c.textContent) : ''
  }
  const nav = document.querySelector('.mi-nav-badge .el-badge__content')
  return {
    n: rows.length,
    names: rows.map((r) => clean((r.querySelector('.name') || {}).textContent)),
    lasts: rows.map((r) => clean((r.querySelector('.last') || {}).textContent)),
    badges: rows.map(badgeOf),
    presence: rows.map((r) => !!r.querySelector('.presence')),
    h1: clean((document.querySelector('h1.h1') || {}).textContent),
    dim: clean((document.querySelector('.topbar .dim') || {}).textContent),
    ops: Array.prototype.slice.call(document.querySelectorAll('.topbar .ops button')).map((b) => clean(b.textContent)),
    navBadge: nav ? clean(nav.textContent) : '',
    chan: !!document.querySelector('.chan'),
    errAlert: Array.prototype.slice.call(document.querySelectorAll('.el-alert')).map((a) => clean(a.textContent)).join(' | ').slice(0, 220)
  }
}

function domThread () {
  const clean = (s) => String(s == null ? '' : s).replace(/\s+/g, ' ').trim()
  const rows = Array.prototype.slice.call(document.querySelectorAll('.thread .row'))
  const nav = document.querySelector('.mi-nav-badge .el-badge__content')
  return {
    mine: rows.filter((r) => r.classList.contains('mine')).length,
    peer: rows.filter((r) => r.classList.contains('peer')).length,
    days: Array.prototype.slice.call(document.querySelectorAll('.thread .group .day')).map((d) => clean(d.textContent)),
    states: Array.prototype.slice.call(document.querySelectorAll('.thread .row.mine .state')).map((s) => clean(s.textContent)),
    times: Array.prototype.slice.call(document.querySelectorAll('.thread .bubble-meta')).map((b) => {
      const s = b.querySelector('span')
      return s ? clean(s.textContent) : ''
    }),
    alerts: Array.prototype.slice.call(document.querySelectorAll('.thread .alert-box')).map((a) => clean(a.textContent)),
    imgs: Array.prototype.slice.call(document.querySelectorAll('.bubble-img')).map((w) => {
      const im = w.querySelector('img')
      return im ? { src: im.getAttribute('src') || '', naturalWidth: im.naturalWidth, complete: im.complete } : null
    }).filter(Boolean),
    blocked: !!document.querySelector('.blk.blocked'),
    blockedT: clean((document.querySelector('.blocked-t') || {}).textContent),
    composer: !!document.querySelector('.composer'),
    older: clean((document.querySelector('.thread .older') || {}).textContent),
    who: clean((document.querySelector('.who-name') || {}).textContent),
    chan: !!document.querySelector('.chan'),
    dot: (function () {
      const d = document.querySelector('.who .dot')
      return d ? (d.classList.contains('on') ? 'on' : 'off') : ''
    })(),
    ops: Array.prototype.slice.call(document.querySelectorAll('.ops button')).map((b) => clean(b.textContent)),
    navBadge: nav ? clean(nav.textContent) : '',
    errAlert: Array.prototype.slice.call(document.querySelectorAll('.el-alert')).map((a) => clean(a.textContent)).join(' | ').slice(0, 220)
  }
}
// ---- 第四个读数器：只要「顶栏那顆角标 + 页面骨架」，用于非私信页上验角标 ----
function domNav () {
  const clean = (s) => String(s == null ? '' : s).replace(/\s+/g, ' ').trim()
  const nav = document.querySelector('.mi-nav-badge .el-badge__content')
  return {
    navBadge: nav ? clean(nav.textContent) : '',
    hasNav: !!document.querySelector('.mi-nav'),
    links: Array.prototype.slice.call(document.querySelectorAll('.mi-nav a')).map((a) => clean(a.textContent)),
    url: location.pathname,
    chan: !!document.querySelector('.chan')
  }
}
// 读数器抛异常时的兜底形状：判据照常算红，但 C 组不许因为一次读数失败就整组断气。
const EMPTY_LIST = { n: 0, names: [], lasts: [], badges: [], presence: [], h1: '', dim: '', ops: [], navBadge: '', chan: false, errAlert: '' }
const EMPTY_THREAD = { mine: 0, peer: 0, days: [], states: [], times: [], alerts: [], imgs: [], blocked: false, blockedT: '', composer: false, older: '', who: '', chan: false, dot: '', ops: [], navBadge: '', errAlert: '' }
const EMPTY_NAV = { navBadge: '', hasNav: false, links: [], url: '', chan: false }
// 下面六个包装的唯一目的：任何一步在浏览器里撞空（选择器没命中、导航失败、条件没等到），
// 都只把「没成立」写进读数继续往下跑。取证脚本最怕的不是某条红，而是第 3 条红导致第 4~17 条压根没跑。
async function readDom (page, fn, fallback, tag) {
  try { return await page.evaluate(fn) }
  catch (e) { say('  …………读数失败（' + (tag || '') + '）：' + String((e && e.message) || e).slice(0, 130)); return fallback }
}
async function waitSel (page, sel, ms, tag) {
  try { await page.waitForSelector(sel, { timeout: ms || 20000 }); return true }
  catch (e) { say('  …………等不到 ' + sel + '（' + (tag || '') + '）：' + String((e && e.message) || e).slice(0, 130)); return false }
}
async function waitFn (page, fn, arg, ms, tag) {
  try { const h = await page.waitForFunction(fn, arg, { timeout: ms || 15000 }); return await h.jsonValue() }
  catch (e) { say('  …………页内条件没等到（' + (tag || '') + '）：' + String((e && e.message) || e).slice(0, 130)); return null }
}
async function gotoSafe (page, url, tag) {
  try { await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 }); return true }
  catch (e) { say('  …………打开 ' + url + ' 失败（' + (tag || '') + '）：' + String((e && e.message) || e).slice(0, 130)); return false }
}
async function clickSafe (loc, name) {
  try { await loc.click({ timeout: 8000 }); return true }
  catch (e) { say('  …………点「' + name + '」失败：' + String((e && e.message) || e).slice(0, 130)); return false }
}
async function fillSafe (loc, text, name) {
  try { await loc.fill(text, { timeout: 8000 }); return true }
  catch (e) { say('  …………往「' + name + '」填字失败：' + String((e && e.message) || e).slice(0, 130)); return false }
}
// ==========================================================================================
// runC：C0 前置 → C1~C17 十六步界面取证
// ==========================================================================================
async function runC () {
  const bagA = { console: [], pageerror: [], http: [] }
  const bagB = { console: [], pageerror: [], http: [] }
  let A = null
  let B = null
  try {
    const { chromium } = require(PW_DIR)
    browser = await chromium.launch({ headless: true, executablePath: CHROME })
    say('# 浏览器 ' + browser.version() + '（headless，executablePath 指向本机 Chrome）视口 ' + DESKTOP.width + 'x' + DESKTOP.height)
    // 全站 60 次/分钟的用户桶刚刚被 A/B 两组吃掉大半，而浏览器一进 /chat 就要连打
    // （unread + conversations + thread + profile + 通知未读）。不等出这个分钟窗，
    // 症状是一条假红：「会话列表没读到：HTTP 429」，看起来像产品坏了，其实是我们自己撞的限流。
    const toNext = 60000 - (Date.now() % 60000) + 1500
    say('  …………C 组先让出限流分钟窗 ' + toNext + 'ms')
    await sleep(toNext)

    // C0：dev server 吐的模块必须和磁盘源码一致（踩坑日志第 13 轮：判据跑在旧 transform 上会假绿）
    const fTargets = [
      ['ChatListView', 'frontend/src/views/chat/ChatListView.vue', '/src/views/chat/ChatListView.vue'],
      ['ChatDetailView', 'frontend/src/views/chat/ChatDetailView.vue', '/src/views/chat/ChatDetailView.vue'],
      ['stores/pm.js', 'frontend/src/stores/pm.js', '/src/stores/pm.js'],
      ['BasicLayout', 'frontend/src/layouts/BasicLayout.vue', '/src/layouts/BasicLayout.vue']
    ]
    const fOk = []
    for (const t of fTargets) fOk.push(await freshness(t[0], t[1], t[2]))
    if (!fOk.every(Boolean)) { say('ABORT C 组：dev server 还在吐旧模块，界面判据全是空跑，先重启 Vite 再说'); return }

    // 铺三块砖。为什么不靠 A/B 两组留下的旧数据：PM_PAGE_SIZE=20，一页只取最新 20 条，
    // 而 A/B 已经往这个会话里追加了十几条，旧的那些（乙发来的、L2 求助卡那条）很可能被挤到第二页；
    // 于是「C2 要求同一页里既有 mine 又有 peer、还有求助卡」就变成碰运气。
    // 砖的正文刻意不带 g5- 前缀：这些是要给答辩看的截图，不能是乱码。
    const bkA = await api('POST', '/api/pm/send', alice.token, { toUserId: bob.uid, msgType: 'text', clientMsgId: key('cbrika'), content: '上午听你说在准备答辩，替你捏把汗。今天状态好一些了吗？' })
    const bkB = await api('POST', '/api/pm/send', bob.token, { toUserId: alice.uid, msgType: 'text', clientMsgId: key('cbrikb'), content: '好一些了，昨晚睡够以后没那么慌。谢谢你记得问我。' })
    const bkC = await api('POST', '/api/pm/send', alice.token, { toUserId: bob.uid, msgType: 'text', clientMsgId: key('cbrikl2'), content: '有时候还是觉得活着没意思，不过我知道那是情绪在说话，不是结论。' })
    say('  …………C 组铺砖 ids=' + [bkA, bkB, bkC].map((x) => (x.data && x.data.id) || ('x' + x.code)).join(',')
      + ' 风险档=' + (bkC.data && bkC.data.riskLevel) + ' 甲侧求助卡=' + !!(bkC.data && bkC.data.alert))

    A = await newPage(alice, bagA)
    // C1 甲的私信列表（U9）：行、昵称、摘要、未读、在线点、顶栏文案，全部来自后端真实读数
    await gotoSafe(A.page, BASE + '/chat', 'C1')
    await waitSel(A.page, '.list .row', 30000, 'C1 会话行')
    const c1 = await readDom(A.page, domList, EMPTY_LIST, 'C1')
    const c1ok = c1.n >= 1 && c1.names.length === c1.n && c1.names.every((x) => x.length > 0)
      && /私信/.test(c1.h1) && !c1.errAlert && c1.dim.indexOf('实时通道未连接') < 0
    await snap(A.page, '01-私信列表-会话行与未读.png',
      'C1 /chat 真的列出了会话行（昵称非空、无错误条，且顶栏没说「实时通道未连接」）', c1ok,
      'h1=' + c1.h1 + ' 行数=' + c1.n + ' names=' + JSON.stringify(c1.names) + ' badges=' + JSON.stringify(c1.badges)
      + ' 在线点=' + JSON.stringify(c1.presence) + ' dim=' + c1.dim.slice(0, 70) + ' errAlert=' + (c1.errAlert || '无') + ' 顶栏角标=' + (c1.navBadge || '无'))

    // C2 甲看的会话详情（U10）：双向气泡 + 日期分组 + 求助卡 + 每颗气泡的时间
    await gotoSafe(A.page, BASE + '/chat/' + bob.uid, 'C2')
    await waitSel(A.page, '.thread .row', 30000, 'C2 气泡行')
    await sleep(1200)
    const c2 = await readDom(A.page, domThread, EMPTY_THREAD, 'C2')
    // 判据口径两条硬规矩（都在第一轮踩过）：
    // ① 时间戳判「每颗气泡都有」而不是「有一条有」—— 后端 createdAt 为 null 时 non_null 会把键删掉，
    //    前端 fmtChatTime 兜成空串，症状正是气泡下面那行时间不见了（A2b 那颗真雷的界面表现）。
    // ② 状态槽只判数量等于 mine：statusText 对 status=sent 返回空串是设计（还没送达本来就不该吹「已送达」），
    //    送达/已读由 C5 用实时链路证明，不在这里靠旧数据碰运气。
    const c2ok = c2.mine >= 1 && c2.peer >= 1
      && c2.days.length > 0 && c2.days.every((d) => d && d.indexOf('更早') < 0)
      && c2.alerts.length >= 1
      && c2.times.length === c2.mine + c2.peer && c2.times.every((t) => t.length > 0)
      && c2.states.length === c2.mine && !c2.errAlert
    await snap(A.page, '02-会话详情-双向气泡与日期分组与求助卡.png',
      'C2 /chat/:uid 里甲乙两个方向的气泡同屏、日期分组没有掉进「更早」、L2 那条的求助卡由服务端拼好了', c2ok,
      '对端=' + c2.who + ' 我方=' + c2.mine + ' 对方=' + c2.peer + ' 日期=' + JSON.stringify(c2.days)
      + ' 求助卡=' + c2.alerts.length + '张 空时间戳=' + c2.times.filter((t) => !t).length + '/' + c2.times.length
      + ' 状态槽=' + JSON.stringify(c2.states) + ' 更早=' + c2.older.slice(0, 24) + ' errAlert=' + (c2.errAlert || '无'))
    check(!c2.chan, 'C2b 详情页顶部没有「实时通道未连接」那行 —— 浏览器这条路真的握上了 STOMP（降级行只在 !isRealtime 时渲染）',
      'chan=' + c2.chan + ' 在线点=' + c2.dot + ' 顶栏角标=' + (c2.navBadge || '无'))

    // C3 乙看同一段会话（镜像）：同一条链路的两个视角，条数必须互为镜像。中间不发新消息，否则镜像必然对不上。
    B = await newPage(bob, bagB)
    await gotoSafe(B.page, BASE + '/chat/' + alice.uid, 'C3')
    await waitSel(B.page, '.thread .row', 30000, 'C3 气泡行')
    await sleep(1200)
    const c3 = await readDom(B.page, domThread, EMPTY_THREAD, 'C3')
    const c3ok = c3.mine >= 1 && c3.peer >= 1 && c3.peer === c2.mine && c3.mine === c2.peer && !c3.errAlert
    await snap(B.page, '03-镜像-乙看的同一段会话.png',
      'C3 乙那一侧看到的是同一段对话：他的「对方」正好是甲的「我方」，条数互为镜像（没有谁多看了几条）', c3ok,
      '甲屏 我方/对方=' + c2.mine + '/' + c2.peer + ' 乙屏 我方/对方=' + c3.mine + '/' + c3.peer
      + ' 对端=' + c3.who + ' 求助卡=' + c3.alerts.length + '张 errAlert=' + (c3.errAlert || '无'))

    // C4 图片私信在浏览器里的终判。A9 证的是「现场传一张，拿到 /uploads/yyyy/MM/dd 三层 url，
    // 并且这个 url 取得回 image 字节」；那还差最后一步：页面上那颗 img 到底解码出宽高没有。
    // 扁平日期的 url（T5.5 之前的写法）照样能落库、也能被 curl 到 404，只有在浏览器里才现形为裂口图标。
    const imgsBefore = c2.imgs.length
    let picked4 = false
    try { await A.page.setInputFiles('.composer input.file', IMG_SEED); picked4 = true }
    catch (e) { say('  …………C4 选图失败：' + String((e && e.message) || e).slice(0, 130)) }
    const min4 = imgsBefore + 1
    const imgSrc4 = await waitFn(A.page, function (n) {
      const ims = Array.prototype.slice.call(document.querySelectorAll('.bubble-img img'))
      if (ims.length < n) return false
      const last = ims[ims.length - 1]
      return (last.complete && last.naturalWidth > 0) ? String(last.getAttribute('src') || '') : false
    }, min4, 30000, 'C4 图片气泡解码成功')
    await sleep(800)
    const c4 = await readDom(A.page, domThread, EMPTY_THREAD, 'C4')
    const lastImg = c4.imgs.length ? c4.imgs[c4.imgs.length - 1] : null
    const c4ok = picked4 && !!imgSrc4 && c4.imgs.length >= min4 && !!lastImg
      && lastImg.naturalWidth > 0 && /^\/uploads\/\d{4}\/\d{2}\/\d{2}\//.test(String(lastImg.src))
    await snap(A.page, '04-图片气泡-浏览器里真的显示出来了.png',
      'C4 在浏览器里选一张图 → 走 /api/files/image 上传 → 自动发出 → 气泡里的 img 解码出真实宽高（不是裂口图标）', c4ok,
      '选图=' + picked4 + ' 图片数=' + imgsBefore + '→' + c4.imgs.length + ' src=' + (lastImg && lastImg.src)
      + ' naturalWidth=' + (lastImg && lastImg.naturalWidth) + ' complete=' + (lastImg && lastImg.complete))

    // C5 手敲一句发出去：这一条才是「用户能不能用起来」的定义。
    // 「发送中」那一格刻意只记录不硬判：REST 回执通常在几百毫秒内就到，气泡一闪而过，
    // 拿它当判据就是一条会自己变红的红条（本地乐观渲染有没有生效，看得到就够了）。
    const c5text = '这一句是我在浏览器里敲进输入框、再点「发送」按钮发出去的（Gate5 UI ' + TS + '）'
    const filled5 = await fillSafe(A.page.locator('.composer textarea'), c5text, 'C5 甲的输入框')
    await sleep(300)
    const pendP = A.page.waitForFunction(function (t) {
      return Array.prototype.slice.call(document.querySelectorAll('.thread .row.mine')).some(function (r) {
        const b = r.querySelector('.bubble-txt')
        const bub = r.querySelector('.bubble')
        return !!(b && String(b.textContent).indexOf(t) >= 0 && bub && bub.classList.contains('is-pending'))
      })
    }, c5text, { timeout: 5000 }).then(function () { return true }).catch(function () { return false })
    const clicked5 = await clickSafe(A.page.locator('.comp-ops button').filter({ hasText: '发送' }).first(), 'C5 发送按钮')
    const sawPending = await pendP
    const shown5 = await waitFn(A.page, function (t) {
      return Array.prototype.slice.call(document.querySelectorAll('.thread .row.mine .bubble-txt'))
        .some(function (b) { return String(b.textContent).indexOf(t) >= 0 }) ? String(t) : false
    }, c5text, 15000, 'C5 甲屏出现我方气泡')
    const state5 = await waitFn(A.page, function (t) {
      const hit = Array.prototype.slice.call(document.querySelectorAll('.thread .row.mine')).filter(function (r) {
        const b = r.querySelector('.bubble-txt')
        return !!(b && String(b.textContent).indexOf(t) >= 0)
      })[0]
      if (!hit) return false
      const bub = hit.querySelector('.bubble')
      if (bub && bub.classList.contains('is-failed')) return 'FAILED'
      const st = hit.querySelector('.state')
      const v = st ? String(st.textContent).trim() : ''
      return (v === '已送达' || v === '已读') ? v : false
    }, c5text, 20000, 'C5 送达/已读回执回流')
    const c5ok = !!(filled5 && clicked5 && shown5) && (state5 === '已送达' || state5 === '已读')
    await snap(A.page, '05-发送与送达回执.png',
      'C5 甲在输入框里敲的字出现在我方气泡上，并且状态槽里躺着后端推回来的「已送达 / 已读」（不是本地自己写的）', c5ok,
      '填入=' + filled5 + ' 点击=' + clicked5 + ' 上屏=' + !!shown5 + ' 状态=' + JSON.stringify(state5)
      + ' 观测到发送中=' + sawPending + ' 状态槽全列=' + JSON.stringify((await readDom(A.page, domThread, EMPTY_THREAD, 'C5b')).states))

    // C6 乙侧一次都不刷新：B 从 C3 之后没动过，甲刚发的那句要自己冒出来，
    // 而且地址栏还停在原地。这是「实时私信」唯一有意义的证明（轮询也能做到，但那是兜底不是主路）。
    const url6 = B.page.url()
    const got6 = await waitFn(B.page, function (t) {
      return Array.prototype.slice.call(document.querySelectorAll('.thread .row.peer .bubble-txt'))
        .some(function (b) { return String(b.textContent).indexOf(t) >= 0 }) ? String(t) : false
    }, c5text, 15000, 'C6 乙屏不刷新收到')
    const c6 = await readDom(B.page, domThread, EMPTY_THREAD, 'C6')
    const c6ok = !!got6 && B.page.url() === url6 && !c6.errAlert && c6.peer >= c2.mine
    await snap(B.page, '06-乙侧不刷新也收到.png',
      'C6 乙那块屏从进页面到现在一次没刷新，甲刚敲的那句自己出现在对方气泡里，地址栏没变', c6ok,
      '命中=' + !!got6 + ' 地址=' + B.page.url() + ' 对方=' + c6.peer + ' 我方=' + c6.mine
      + ' 通道降级行=' + c6.chan + ' 在线点=' + c6.dot + ' errAlert=' + (c6.errAlert || '无'))

    // C7 会话页 → 对方主页（U10 的出口）：路由名不对就会跳进 404，所以连地址一起判。
    await clickSafe(A.page.locator('.ops button').filter({ hasText: '看TA的主页' }).first(), 'C7 看TA的主页')
    await waitSel(A.page, '.card-op', 15000, 'C7 资料卡操作区')
    const urlHome = A.page.url()
    const homeBtns = await A.page.evaluate(function () {
      return Array.prototype.slice.call(document.querySelectorAll('.card-op button')).map(function (b) { return String(b.textContent).trim() })
    }).catch(function () { return [] })
    const c7ok = urlHome === BASE + '/user/' + bob.uid && homeBtns.some(function (t) { return t.indexOf('私信') >= 0 })
    await snap(A.page, '07-会话页跳对方主页.png', 'C7 「看TA的主页」把地址换成 /user/<对端 id>，资料卡上确实有「私信」这个入口', c7ok,
      'url=' + urlHome + ' 期望=' + BASE + '/user/' + bob.uid + ' 操作区按钮=' + JSON.stringify(homeBtns))

    // C8 主页 → 会话（U10 的入口）：和顶栏角标、通知点击共用同一条路由（router name=chat-detail）。
    await clickSafe(A.page.locator('.card-op button').filter({ hasText: '私信' }).first(), 'C8 主页的私信按钮')
    await waitSel(A.page, '.thread .row', 20000, 'C8 从主页进来的会话')
    await sleep(800)
    const c8 = await readDom(A.page, domThread, EMPTY_THREAD, 'C8')
    const c8ok = A.page.url() === BASE + '/chat/' + bob.uid && c8.mine >= 1 && c8.composer && !c8.errAlert
    await snap(A.page, '08-主页私信入口进会话.png', 'C8 主页那颗「私信」按钮真的把人带回 /chat/:uid，气泡与输入框都在', c8ok,
      'url=' + A.page.url() + ' 我方=' + c8.mine + ' 对方=' + c8.peer + ' 输入框=' + c8.composer + ' 对端=' + c8.who)

    // C9 顶栏角标亮起。这一步先让甲离开会话页是判据成立的前提：ChatDetailView 在 onUnmounted
    // 里把 activePeer 清掉，新私信才会走 setUnreadOf(+1) 而不是被 markRead 吃掉。
    await clickSafe(A.page.locator('.ops button').filter({ hasText: '看TA的主页' }).first(), 'C9 甲离开会话页')
    await sleep(2500)
    const c9pre = await readDom(A.page, domNav, EMPTY_NAV, 'C9 前置读数')
    const c9text = '乙从自己那侧的输入框发出来的第二句（Gate5 UI ' + TS + '）'
    const filled9 = await fillSafe(B.page.locator('.composer textarea'), c9text, 'C9 乙的输入框')
    await sleep(300)
    const clicked9 = await clickSafe(B.page.locator('.comp-ops button').filter({ hasText: '发送' }).first(), 'C9 乙的发送按钮')
    const badge9 = await waitFn(A.page, function () {
      const b = document.querySelector('.mi-nav-badge .el-badge__content')
      return b ? String(b.textContent || '').trim() : false
    }, null, 15000, 'C9 顶栏私信角标亮起')
    const c9nav = await readDom(A.page, domNav, EMPTY_NAV, 'C9')
    const c9ok = Number(badge9) >= 1 && !c9pre.navBadge && c9nav.hasNav && !!(filled9 && clicked9)
    await snap(A.page, '09-顶栏私信角标亮起.png', 'C9 乙在自己那块屏上发的字，让甲这边顶栏的私信角标亮了（甲此刻既不在列表页也不在会话页）', c9ok,
      '亮之前=' + JSON.stringify(c9pre.navBadge) + ' 现在=' + JSON.stringify(badge9) + ' 导航在页=' + c9nav.hasNav
      + ' 地址=' + c9nav.url + ' 乙填入=' + filled9 + ' 乙点击=' + clicked9)

    // C10 列表页那颗行内角标和「现在有 N 条没读」必须同时说真话（两处读的是同一个 total）。
    await clickSafe(A.page.locator('.mi-nav-badge a').first(), 'C10 顶栏私信入口')
    await waitSel(A.page, '.list .row', 20000, 'C10 会话列表')
    await sleep(800)
    const c10 = await readDom(A.page, domList, EMPTY_LIST, 'C10')
    const rowBob = c10.names.findIndex(function (x) { return x === bob.nick })
    const badgeRow = rowBob >= 0 ? String(c10.badges[rowBob] || '') : ''
    const c10ok = /\/chat$/.test(A.page.url()) && c10.n >= 1
      && (rowBob >= 0 ? Number(badgeRow) >= 1 : c10.badges.some(function (b) { return Number(b) >= 1 }))
      && /现在有/.test(c10.dim)
    await snap(A.page, '10-列表未读角标.png', 'C10 顶栏角标点进去就是会话列表，乙那一行的红点和顶栏那句「现在有 N 条没读」是同一个数', c10ok,
      'url=' + A.page.url() + ' 行数=' + c10.n + ' 乙所在行=' + rowBob + '(昵称=' + bob.nick + ') 行内角标=' + JSON.stringify(badgeRow)
      + ' 全部角标=' + JSON.stringify(c10.badges) + ' dim=' + c10.dim.slice(0, 60))

    // C11 读完归零 —— 上一轮那颗真雷的界面终判。旧代码把 prev 写在 spread 之后，
    // 于是 unreadTotal = total - prev + new 恒等于 total，顶栏角标永远不归零，
    // 只有下一次 refreshUnread（重进页面 / 点通知 / WS 重连）才消；列表行的红点读的是 byPeerUnread，
    // 那一行当时是对的 —— 同一个未读数两处不一致，最容易被误判成「后端算错了」。
    const rowLoc = rowBob >= 0 ? A.page.locator('.list .row').nth(rowBob) : A.page.locator('.list .row').filter({ hasText: bob.nick }).first()
    await clickSafe(rowLoc, 'C11 点进乙的会话行')
    await waitSel(A.page, '.thread .row', 20000, 'C11 读进会话')
    const gone11 = await waitFn(A.page, function () {
      return document.querySelector('.mi-nav-badge .el-badge__content') ? false : 'GONE'
    }, null, 15000, 'C11 顶栏角标归零')
    await sleep(600)
    const c11 = await readDom(A.page, domThread, EMPTY_THREAD, 'C11')
    const c11ok = gone11 === 'GONE' && !c11.chan && c11.mine >= 1 && Number(c11.navBadge || 0) === 0
    await snap(A.page, '11-读完之后顶栏角标归零.png', 'C11 甲点进乙的会话读完，顶栏那颗角标当场消失（el-badge 的 hidden 生效 = unreadTotal 真的回到 0）', c11ok,
      'gone=' + JSON.stringify(gone11) + ' 角标读数=' + JSON.stringify(c11.navBadge) + ' 我方=' + c11.mine
      + ' 对方=' + c11.peer + ' 状态槽=' + JSON.stringify(c11.states) + ' 降级行=' + c11.chan)

    // C12 拉黑（U13）：先拍二次确认弹窗，再点确认，再拍确认之后的会话页。
    // 🔴 判据口径是第 42 轮现查改过的：isBlocked 时模板里 <template v-else> 整块消失，
    // 也就是 .thread 和 .composer 都不存在了 —— 所以判的是「!composer」，
    // 而不是「textarea.disabled」（那个 textarea 压根没渲染，判 disabled 会得到 undefined，红的还是绿的都说不清）。
    await clickSafe(A.page.locator('.ops button').filter({ hasText: '不再收TA的消息' }).first(), 'C12 不再收TA的消息')
    const dlg12 = A.page.locator('.el-dialog').filter({ hasText: '不再收这个人的消息' }).first()
    let dlg12Visible = false
    try { await dlg12.waitFor({ state: 'visible', timeout: 15000 }); dlg12Visible = true }
    catch (e) { say('  …………C12 拉黑弹窗没等到可见：' + String((e && e.message) || e).slice(0, 130)) }
    await fillSafe(dlg12.locator('input').first(), '界面取证：暂时不想被打扰，明天再说', 'C12 拉黑原因')
    const confirmCnt = await dlg12.locator('button').filter({ hasText: '确认拉黑' }).count().catch(function () { return 0 })
    const c12dlgOk = dlg12Visible && confirmCnt === 1
    await snap(A.page, '12-拉黑二次确认弹窗.png',
      'C12 「不再收TA的消息」弹的是二次确认：后果写明白了，原因是选填，确认那颗按钮是 danger', c12dlgOk,
      '可见=' + dlg12Visible + ' danger按钮数=' + confirmCnt + ' 弹窗文本=' + (await dlg12.textContent().catch(function () { return '' })).replace(/\s+/g, ' ').slice(0, 90))
    await clickSafe(dlg12.locator('button').filter({ hasText: '确认拉黑' }).first(), 'C12 确认拉黑')
    const blk12 = await waitSel(A.page, '.blk.blocked', 15000, 'C12 拉黑之后的提示块')
    await sleep(900)
    const c12 = await readDom(A.page, domThread, EMPTY_THREAD, 'C12 结果')
    const c12ok = blk12 && c12.blocked && /不再接收这个人的消息/.test(c12.blockedT) && !c12.composer && c12.ops.indexOf('解除限制') >= 0
    await snap(A.page, '13-拉黑后的会话页.png',
      'C13 拉黑之后：整块输入区连同气泡一起消失，顶上只剩那句「你已经不再接收这个人的消息」和解除限制', c12ok,
      '提示块=' + c12.blocked + ' 文案=' + c12.blockedT.slice(0, 30) + ' 输入区存在=' + c12.composer
      + ' 气泡=' + (c12.mine + c12.peer) + ' 顶部按钮=' + JSON.stringify(c12.ops))

    // C14 解除限制：这一步不只是判据，也是**收尾**——不还原就把这个账号的会话污染到下一轮，
    // 而下一轮 C4/C5 会在「已经拉黑」的页面上发信，得到一串和后端无关的红。
    await clickSafe(A.page.locator('.ops button').filter({ hasText: '解除限制' }).first(), 'C14 解除限制')
    const ok14 = await waitFn(A.page, function () {
      return !!document.querySelector('.composer textarea') && !document.querySelector('.blk.blocked')
    }, null, 20000, 'C14 输入区回来了')
    await sleep(900)
    const c13 = await readDom(A.page, domThread, EMPTY_THREAD, 'C14')
    const c13ok = !!ok14 && !c13.blocked && c13.composer && c13.mine >= 1 && c13.peer >= 1
    await snap(A.page, '14-解除限制后气泡与输入框回来了.png',
      'C14 解除限制之后历史气泡和输入框一起回来（拉黑不删本地历史，这条判据同时证了那句「历史消息仍然留在你这里」）', c13ok,
      '回来=' + JSON.stringify(ok14) + ' 提示块=' + c13.blocked + ' 输入区=' + c13.composer
      + ' 我方=' + c13.mine + ' 对方=' + c13.peer + ' 更早=' + c13.older.slice(0, 24))

    // C15 举报弹窗：只取证「弹窗与判据」，**不真的提交**。
    // 提交一次就多一条 PENDING 工单进管理端队列，那是阶段 6 的输入，不该由界面取证随手造。
    await clickSafe(A.page.locator('.toolbar button').filter({ hasText: '举报这段对话' }).first(), 'C15 举报这段对话')
    const dlg14 = A.page.locator('.el-dialog').filter({ hasText: '举报这条私信' }).first()
    let dlg14Visible = false
    try { await dlg14.waitFor({ state: 'visible', timeout: 15000 }); dlg14Visible = true }
    catch (e) { say('  …………C15 举报弹窗没等到可见：' + String((e && e.message) || e).slice(0, 130)) }
    await clickSafe(dlg14.locator('.el-select').first(), 'C15 举报原因下拉')
    const optCount = await waitFn(A.page, function () {
      const n = document.querySelectorAll('.el-select-dropdown__item').length
      return n >= 6 ? n : false
    }, null, 10000, 'C15 六个原因选项')
    const btnState = await A.page.evaluate(function () {
      const dlg = Array.prototype.slice.call(document.querySelectorAll('.el-dialog'))
        .filter(function (d) { return /举报这条私信/.test(String(d.textContent)) })[0]
      if (!dlg) return null
      const btn = Array.prototype.slice.call(dlg.querySelectorAll('button'))
        .filter(function (b) { return /提交举报/.test(String(b.textContent)) })[0]
      if (!btn) return null
      return { text: String(btn.textContent).replace(/\s+/g, ' ').trim(), disabled: !!btn.disabled }
    }).catch(function () { return null })
    const c14ok = dlg14Visible && Number(optCount) >= 6 && !!btnState && btnState.disabled === true
    await snap(A.page, '15-举报这条私信弹窗.png',
      'C15 举报弹窗：六个原因真能展开，没选原因之前「提交举报」是 disabled（前端不许让人空手往人工审核队列塞东西）', c14ok,
      '可见=' + dlg14Visible + ' 选项数=' + JSON.stringify(optCount) + ' 按钮=' + JSON.stringify(btnState))
    // el-dialog 关掉之后内容仍留在 DOM 里（v-show 不是卸载），所以「取消」必须作用域在这个弹窗内部，
    // 用全局 .el-dialog button 的 first() 会命中那个已经关掉的拉黑弹窗，点到看不见的东西上去。
    await A.page.keyboard.press('Escape').catch(function () {})
    await sleep(400)
    let cancelled = '没有取消按钮可点'
    try {
      const btnNo = dlg14.locator('button').filter({ hasText: '取消' }).first()
      if (await btnNo.isVisible().catch(function () { return false })) { await btnNo.click({ timeout: 6000 }); cancelled = '已点' }
      else cancelled = '弹窗已被 Escape 关掉'
    } catch (e) { cancelled = '点取消抛异常：' + String((e && e.message) || e).slice(0, 60) }
    await sleep(800)
    const vis14 = await dlg14.isVisible().catch(function () { return true })
    check(!vis14, 'C15b 关掉之后举报弹窗不再可见（判可见性，不判 DOM 里还在不在 —— 它本来就一直在那儿）',
      'visible=' + vis14 + ' 关闭方式=' + cancelled)

    // C17 拔网线 —— 手册 D5 最后那半句「把网线拔了再插回去，消息一条不丢、一条不重」。
    // 两点口径要写明白，否则下一轮会误读这条判据：
    // ① 这里等的是 11 秒，不是十分钟。走的是同一条机制（STOMP 退避重连 → EV_RECONNECT →
    //    refillThread 按 id 去重补拉），时长只影响重连退避的轮数，不影响「丢没丢 / 重没重」的成立与否。
    // ② setOffline(true) 只掐甲这个浏览器上下文；Node 侧的 api() 直连 8080，所以「对方在另一边照常发」
    //    是真的，不是模拟。C 组从头就是两个互相独立的 context，已经等价手册写的「普通窗口 + 无痕窗口」。
    await gotoSafe(A.page, BASE + '/chat/' + bob.uid, 'C17 断网前先把会话页打开')
    await waitSel(A.page, '.thread .row', 30000, 'C17 断网前的气泡行')
    const c17before = await readDom(A.page, domThread, EMPTY_THREAD, 'C17 断网前')
    let off17 = false
    try { await A.context.setOffline(true); off17 = true }
    catch (e) { say('  …………C17 置离线失败：' + String((e && e.message) || e).slice(0, 130)) }
    await sleep(1500)
    // 正文必须带本轮唯一标记（第 49 轮实测改判据）：C 组会话是跨轮次累积的，
    // 上一轮那两句逐字相同的话还躺在库里（id=129 / id=151 内容一模一样，两轮各留一条），
    // 不带标记时 n2=2 读到的是「历史 + 本轮」，判据会把库里的旧消息算成前端重复渲染。
    const tag17 = ' #' + TS
    const t17a = '刚才网络卡了一下，现在回来了吗' + tag17
    const t17b = '这条是我断网那会儿发的，回来应当只看到一次' + tag17
    const s17a = await api('POST', '/api/pm/send', bob.token, { toUserId: alice.uid, msgType: 'text', clientMsgId: key('c17a'), content: t17a })
    const s17b = await api('POST', '/api/pm/send', bob.token, { toUserId: alice.uid, msgType: 'text', clientMsgId: key('c17b'), content: t17b })
    say('  …………C17 甲离线期间乙发了两条 code/id=' + [s17a, s17b].map((x) => (x.data && x.data.id) || ('x' + x.code)).join(','))
    await sleep(11000)
    let back17 = false
    try { await A.context.setOffline(false); back17 = true }
    catch (e) { say('  …………C17 恢复网络失败：' + String((e && e.message) || e).slice(0, 130)) }
    const dup17 = await waitFn(A.page, function (p) {
      const texts = Array.prototype.slice.call(document.querySelectorAll('.thread .row .bubble-txt'))
        .map(function (b) { return String(b.textContent) })
      const n1 = texts.filter(function (t) { return t.indexOf(p[0]) >= 0 }).length
      const n2 = texts.filter(function (t) { return t.indexOf(p[1]) >= 0 }).length
      return (n1 >= 1 && n2 >= 1) ? { n1: n1, n2: n2, total: texts.length } : false
    }, [t17a, t17b], 60000, 'C17 重新联网后两条都补回来了')
    await sleep(2500)
    const c17after = await readDom(A.page, domThread, EMPTY_THREAD, 'C17 回来后')
    const dupAfter = await A.page.evaluate(function (p) {
      const texts = Array.prototype.slice.call(document.querySelectorAll('.thread .row .bubble-txt'))
        .map(function (b) { return String(b.textContent) })
      return {
        n1: texts.filter(function (t) { return t.indexOf(p[0]) >= 0 }).length,
        n2: texts.filter(function (t) { return t.indexOf(p[1]) >= 0 }).length,
        total: texts.length
      }
    }, [t17a, t17b]).catch(function () { return null })
    const c17ok = off17 && back17 && s17a.code === 0 && s17b.code === 0 && !!dupAfter
      && dupAfter.n1 === 1 && dupAfter.n2 === 1
      && c17after.peer >= c17before.peer && !c17after.errAlert
    await snap(A.page, '16-断网重连后不丢不重.png',
      'C17 甲的屏幕离线 11 秒（等价拔网线），乙在这期间发的两条在重新联网后各只出现一次：不丢（两条都在）、不重（各 1 颗气泡）', c17ok,
      '离线=' + off17 + ' 恢复=' + back17 + ' 发送code=' + s17a.code + '/' + s17b.code
      + ' 断网前 我方/对方=' + c17before.mine + '/' + c17before.peer
      + ' 回来后=' + c17after.mine + '/' + c17after.peer + ' 两条各出现=' + JSON.stringify(dupAfter)
      + ' 实时通道=' + (c17after.chan ? '未连接' : '已连') + ' errAlert=' + (c17after.errAlert || '无'))

    // C16 两块屏的噪声桶：console.error / 未捕获异常 / 4xx·5xx 全要走零。
    // 429 也归在这里：那是我们自己把限流桶撞穿了，不该伪装成「产品没问题」，也不该记成后端坏了 —— 记红，重跑。
    const noisy = bagA.console.concat(bagA.pageerror, bagB.console, bagB.pageerror)
    noisy.slice(0, 12).forEach(function (x) { say('    · ' + x) })
    const c15ok = bagA.console.length === 0 && bagA.pageerror.length === 0 && bagA.http.length === 0
      && bagB.console.length === 0 && bagB.pageerror.length === 0 && bagB.http.length === 0
    check(c15ok, 'C16 两块屏从头到尾没有 console.error / 未捕获异常 / 4xx·5xx',
      '甲 c=' + bagA.console.length + ' p=' + bagA.pageerror.length + ' h=' + bagA.http.length
      + '｜乙 c=' + bagB.console.length + ' p=' + bagB.pageerror.length + ' h=' + bagB.http.length
      + ' 明细=' + JSON.stringify(bagA.http.slice(0, 3)) + JSON.stringify(bagB.http.slice(0, 3))
      + JSON.stringify(bagA.console.slice(0, 2)) + JSON.stringify(bagB.pageerror.slice(0, 2)))
  } catch (e) {
    check(false, 'C 组整体异常（界面判据没跑完，剩下的截图不存在）', String((e && e.stack) || e).slice(0, 500))
  } finally {
    try { if (A) await A.context.close() } catch (e) {}
    try { if (B) await B.context.close() } catch (e) {}
    if (browser) { try { await browser.close() } catch (e) {} }
    say('  …………C 组收尾：两个浏览器上下文与 Chromium 已关（甲收帧计数见上方日志）')
  }
}
// ==========================================================================================
// 收口：日志、清单、退出码。ABORT 路径必须带 early=true 才真的停，
// 正常路径刻意不 process.exit —— 退出码交给 Node 自然结束，否则 pipe 上还没冲出去的 stdout 会丢，
// 屏幕上就出现「日志断在半截、退出码 1、却看不出哪里红」这种最难查的形态。
// ==========================================================================================
function finish (early) {
  finishRan = true
  say('')
  say('---- Gate5 取证汇总：PASS ' + nPass + ' / FAIL ' + nFail + ' / 图 ' + manifest.length + ' 张 ----')
  manifest.forEach(function (m) {
    say('  [' + (m.ok ? 'PASS' : 'FAIL') + '] ' + m.file + ' —— ' + m.label + (m.reading ? '  <- ' + m.reading : ''))
  })
  failures.forEach(function (x) { say('   ✗ ' + x) })
  try {
    fs.writeFileSync(path.join(OUT, 'pmgate.log'), logLines.join('\n') + '\n', 'utf8')
    fs.writeFileSync(path.join(OUT, 'shot-manifest.json'), JSON.stringify({
      at: new Date().toISOString(), base: BASE, api: APIBASE, chrome: CHROME, noUi: NOUI,
      viewport: DESKTOP, pace: PACE, accounts: { sender: SENDER, receiver: RECEIVER },
      runTs: TS, pass: nPass, fail: nFail, failures: failures, shots: manifest
    }, null, 2) + '\n', 'utf8')
    say('  [产物] pmgate.log(' + logLines.length + ' 行) + shot-manifest.json(' + manifest.length + ' 张) 已写入 ' + OUT)
  } catch (e) { say('  [产物写入失败] ' + String((e && e.message) || e).slice(0, 200)) }
  process.exitCode = nFail ? 1 : (early ? 1 : 0)
  if (early) process.exit(process.exitCode)
}

say('')
say('---- C 组：真浏览器（界面层证据）----')
if (NOUI) say('SKIP：NOUI=1，本轮只跑 A/B 两条协议层；界面取证与 16 张截图请看 run-gate5-ui.ps1')
else await runC()
finish()

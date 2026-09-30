// =============================================================================
// 阶段 7（离线协同过滤 + 情绪加权推荐）收口取证 —— Gate7 A/B/C/D 四线合一
//
// 【D 线主题判据 2026-09-30 随需求 Q9 改判，改判后未重跑】主背景钉的值由午夜蓝 rgb(14,22,38)
// 换成浅灰 rgb(246,246,247)；「三通道 ≥248 即漏白」整条反了（浅色主题下白卡是正确形态），
// 改成「三通道 ≤60 即深色残留」。docs/gate/阶段7 现存那批图与清单.md 仍是深色版口径的产物，
// 在重跑之前不能拿来证明浅色新主题。本轮按用户指令不重跑（也不覆盖历史取证）。
//
// 【这个文件要证的三件事，对应手册 §10.5 与 §10.6】
//   D6（§10.5 行 1274）：推荐流连点三次「换一批」不重复；每条卡片带非空理由；
//       点「不感兴趣」当场消失，并且这一步真的落进三层记账（行为表 / 缓存行 / 邻居联压）。
//   D7（§10.5 行 1275）：离线作业可手动触发、可查健康度，「通道占比」那行日志能落盘可查。
//   T7.16（§10.6 行 1285）：详情页「看了又看」三层召回（ItemCF 邻居 → 同话题 → 质量分榜），
//       冷帖不空窗、不可见源帖与不存在同形（30001）、相似位不记曝光。
//
// 【为什么接口线和浏览器线必须分开跑】
// 与阶段 6 同一口径：接口线红了就没必要截图，截图线绿了也不能证明接口对。
// 这里 A/B/C 三条都是纯 HTTP + root 直连 SQL，D 线才起真 Chrome。
// 三条线共用同一批判据编号，最后合写进 docs/gate/阶段7/清单.md。
//
// 【SQL 判据为什么写进探针而不是留给人手跑】
// D6 的「三层记账」有一半只在数据库里看得见：user_action 多了一行没有、weight 是不是 -3.00、
// recommend_result 那一行有没有被逻辑删。这些数如果靠人跑完再抄进文档，下一轮复跑就对不上了
// （手册踩坑第 14 轮：「凡是汇总数，必须用一条命令从表里数出来」）。
// 因此本文件在 sql.ps1 / rootpwd.cnf 存在时现查现判；两者缺任一则相关判据记 SKIP 并计入汇总，
// 绝不因为「跑不了」就当成通过 —— 假绿比红更难查。
//
// 【限流是这个环境的既有事实，判据要尊重它】
// 普通接口 60 次/分/用户（application.yml rate-limit.user-per-minute）。本文件按账号主动节流到
// PACE_MAX 枪/分钟，撞上 10010 时睡到下一自然分钟重试。绝不为跑绿而调高服务端配置。
//
// 跑法：cd frontend && node probe/recgate7.mjs
//   只跑接口线（不起浏览器）：NOUI=1 node probe/recgate7.mjs
//   换后端地址：GATE7_API=http://127.0.0.1:18080 node probe/recgate7.mjs
// 产物：docs/gate/阶段7/{*.png, console-evidence.log, shot-manifest.json, 清单.md, d7-log-excerpt.log}
// 退出码：任何一条 FAIL 即 1；SKIP 不减分但会写进汇总与清单，不许静默。
// =============================================================================
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'

const require = createRequire(import.meta.url)
const HERE = path.dirname(fileURLToPath(import.meta.url))
const FE = path.resolve(HERE, '..')
const ROOT = path.resolve(FE, '..')
const OUT_DIR = process.env.GATE7_OUT || path.join(ROOT, 'docs', 'gate', '阶段7')
const TMP = process.env.GATE7_TMP || 'E:/codex workspace/_cache/mindisle-dbtmp/g7'
const PW_DIR = 'E:/codex workspace/_cache/node_modules/playwright-core'
const CHROME = process.env.GATE7_CHROME ||
  'C:/Users/Drbrain/AppData/Local/Google/Chrome/Application/chrome.exe'
const WEB = process.env.GATE7_WEB || 'http://127.0.0.1:5173'
const API = process.env.GATE7_API || 'http://127.0.0.1:8080'
const BACKEND_LOG = process.env.GATE7_LOG || 'E:/codex workspace/_cache/mindisle-dbtmp/run19b.out'
const SQL_PS1 = process.env.GATE7_SQL_PS1 || 'E:/codex workspace/_cache/mindisle-dbtmp/sql.ps1'
const SQL_CNF = process.env.GATE7_SQL_CNF || 'E:/codex workspace/_cache/mindisle-dbtmp/rootpwd.cnf'
const DESKTOP = { width: 1600, height: 1000 }
const PACE_MAX = Number(process.env.GATE7_PACE || 45)
const MAX_RETRY = 4
const CAPTCHA_ID = '00000000000000000000000000000000'
const PWD = process.env.GATE7_PW || 'Test1234'
const DEMO_USER = process.env.GATE7_DEMO || 'demo01'
const ADMIN_USER = process.env.GATE7_ADMIN || 'gate6_super'
const RUN = process.env.GATE7_RUN || String(Date.now()).slice(-6)

// 六路召回通道：与 RecConstants / ColdStart 的常量表一字不差，写错一个字母这条判据就白设。
const CHANNELS = ['usercf', 'itemcf', 'content', 'hot', 'explore', 'emotion']
// 热读兜底专属文案（ReasonBuilder.CHANNEL_HOT 分支 + 兜底 default 分支）。
// 个性化通道出现这三句里的任何一句，就是「把对照组的话塞进实验组」，D6 当场作废。
const HOT_WORDS = ['社区里最近被很多屿民读完的内容', '社区今天讨论最多的', '社区推荐：']
const SIM_HOT_WORD = '共读记录还太少'   // 相似位自己的热读补位文案（SimilarPostService#reasonFor）

const log = []
const checks = []
const shots = []
const throttle = { paced: 0, retried: 0, gaveUp: 0 }
let passN = 0
let failN = 0
let skipN = 0
let leakCount = 0
let consoleErrCount = 0

const say = (line) => { log.push(line); console.log(line) }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function check (id, name, ok, detail) {
  const state = ok === true ? 'PASS' : (ok === false ? 'FAIL' : 'SKIP')
  if (state === 'PASS') passN++
  else if (state === 'FAIL') failN++
  else skipN++
  checks.push({ id, name, state, detail: detail === undefined ? '' : String(detail) })
  say('  ' + state + ' [' + id + '] ' + name + (detail === undefined || detail === '' ? '' : '  → ' + detail))
}

// ---------------------------------------------------------------------------
// HTTP：统一走 fetch，返回 {status, code, msg, data, raw}。限流与「10010 伪装成功」都在这层挡。
// ---------------------------------------------------------------------------
const pace = {}
async function paceGate (who) {
  const key = who || '-'
  const st = pace[key] || (pace[key] = { bucket: -1, cnt: 0 })
  const bucket = Math.floor(Date.now() / 60000)
  if (bucket !== st.bucket) { st.bucket = bucket; st.cnt = 0 }
  if (st.cnt >= PACE_MAX) {
    const wait = 60000 - (Date.now() % 60000) + 2000
    throttle.paced++
    say('  -- 节流：' + key + ' 本分钟已打满 ' + st.cnt + ' 枪，等 ' + wait + 'ms 进下一分钟')
    await sleep(wait)
    st.bucket = Math.floor(Date.now() / 60000); st.cnt = 0
  }
  st.cnt++
}

async function http (method, p, token, body, who) {
  await paceGate(who || (token ? 'token' : 'guest'))
  const headers = {}
  if (token) headers.Authorization = 'Bearer ' + token
  if (body !== undefined && body !== null) headers['Content-Type'] = 'application/json;charset=utf-8'
  const r = await fetch(API + p, {
    method, headers, body: body === undefined || body === null ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(60000)
  })
  const raw = await r.text()
  let j = null
  try { j = JSON.parse(raw) } catch (e) { j = null }
  return {
    status: r.status,
    code: j && j.code !== undefined ? Number(j.code) : null,
    msg: j && j.msg ? String(j.msg) : '',
    data: j ? j.data : null,
    raw: raw.slice(0, 4000)
  }
}

// 「必须被拒」类判据专用：10010（限流）不算被正确拒绝，撞上就重试，重试仍撞记 SKIP。
// 阶段 6 踩过：把「非 0」当「被拒」，于是限流会把五条权限判据全染成假绿。
async function expectRejected (label, method, p, token, body, who, wantCodes) {
  for (let i = 0; i <= MAX_RETRY; i++) {
    const r = await http(method, p, token, body, who)
    if (r.code === 10010 && r.status === 429) {
      throttle.retried++
      const wait = 60000 - (Date.now() % 60000) + 2000
      say('  -- ' + label + ' 撞限流，等 ' + wait + 'ms 重试第 ' + (i + 1) + ' 次')
      await sleep(wait)
      continue
    }
    const okCode = r.code !== null && (!wantCodes || wantCodes.indexOf(r.code) >= 0)
    return { r, rejected: r.code !== 0 && okCode }
  }
  throttle.gaveUp++
  return { r: { code: null, status: 0, msg: '重试 ' + MAX_RETRY + ' 次仍被限流' }, rejected: null }
}

async function login (username, who) {
  const r = await http('POST', '/api/auth/login', null,
    { username, password: PWD, captchaId: CAPTCHA_ID, captchaCode: 'ZZZZ' }, who || username)
  const t = r.data && r.data.accessToken
  if (!t) throw new Error('登录失败 ' + username + '：HTTP ' + r.status + ' code=' + r.code + ' msg=' + r.msg)
  return { token: t, userId: Number(r.data.user && r.data.user.id) }
}

// 卡片摊平：FeedItem = {post:{...}, reason, recallChannel, score}，顶层没有 id。
const rowId = (row) => (row && row.post ? Number(row.post.id) : null)
const rowsFrom = (data) => (data && Array.isArray(data.list) ? data.list : (Array.isArray(data) ? data : []))
const channelsOf = (rows) => rows.map((x) => x.recallChannel)
const hasHotWord = (rows) => rows.filter((x) => {
  const r = String(x.reason === null || x.reason === undefined ? '' : x.reason)
  return HOT_WORDS.some((w) => r.indexOf(w) >= 0)
})
// 曝光采样是确定性取模（UserActionCatalog#sampleExpose）：floorMod(userId*31+itemId,10)<3。
// 复刻这条公式，D6 的埋点判据才能从「大概有几条」变成「逐条可对」。
const sampled = (userId, itemId) => (((userId * 31 + itemId) % 10) + 10) % 10 < 3

// ---------------------------------------------------------------------------
// SQL：单条 SELECT 一次调用，解析 mysql -t 的方框表。口令只存在于仓库外的 cnf 里。
// ---------------------------------------------------------------------------
let sqlSeq = 0
let sqlAvailable = null
function sqlProbe () {
  if (sqlAvailable !== null) return sqlAvailable
  sqlAvailable = fs.existsSync(SQL_PS1) && fs.existsSync(SQL_CNF)
  if (!sqlAvailable) say('  !! 找不到 ' + SQL_PS1 + ' 或 cnf，DB 侧判据将整批判 SKIP（不算通过）')
  return sqlAvailable
}
// 单引号。SQL 字面量只能这样拼出来：把 '- 直接写进 JS 字符串会断掉外层引号，
// 本轮实测 "SELECT IFNULL(MAX(calc_at),'-') FROM t" 被 JS 解析成「字符串 减 字符串」= NaN，
// 而 node --check 一声不响 —— 语法合法的坏表达式是这一类脚本最贵的 bug。
const Q = String.fromCharCode(39)

function sqlRows (label, text) {
  if (!sqlProbe()) return null
  fs.mkdirSync(TMP, { recursive: true })
  const inF = path.join(TMP, 'g7-' + RUN + '-' + (++sqlSeq) + '-' + label + '.sql')
  const outF = inF.replace(/\.sql$/, '.out')
  // 哨兵行：证明这批语句真的进了 mysql 并跑完。输出里没有 g7ok 就是「SQL 文件写了、语句没跑起来」，
  // 那种假绿比红难查（踩坑第 7 轮：mysql 的退出码不能当证据），一律返回 null 让判据记 SKIP/FAIL。
  fs.writeFileSync(inF, "SET NAMES utf8mb4;\nSELECT 'g7ok' AS g7_marker;\n" + text.trim() + '\n', 'utf8')
  try {
    execFileSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
      SQL_PS1, '-SqlFile', inF, '-OutFile', outF], { encoding: 'utf8', timeout: 120000 })
  } catch (e) {
    say('  !! SQL 执行器报错 ' + label + '：' + String(e && e.message).slice(0, 160))
    return null
  }
  const out = fs.readFileSync(outF, 'utf8')
  if (/ERROR \d+/.test(out)) {
    say('  !! SQL 报错 ' + label + '：' + (out.split('\n').filter((l) => /ERROR/.test(l))[0] || '').slice(0, 200))
    return null
  }
  // 【这一段被改写过一次，为什么】mysql -t 把每张结果表打成连续的 | 与 + 行，
  // 而表与表之间也只隔一条 +---+ 边框线，压根没有「别的行」能用来断表。
  // 早先那版按「遇到非 |/+ 行就断表」分组，哨兵表 g7_marker 和真结果表被粘成同一张表，
  // 于是 rest = 「排除含 g7ok 的表」= 空集，每个 sqlCell 都回 ''：
  // run1 里 A2/A12/C3/C10/C11/C12/C14/B8/D4b/D6 那一串红与 SKIP 全是这一个根因，
  // 而 C12 拿到的是 '' == '' → 0 == 0 的假绿。空串与零在这种解析里最难查，写法直接改成拍平行：
  // 把所有 | 行按出现顺序拍平，先定位哨兵行 g7ok，它下面第一行是真表头，再往下全是数据。
  const flat = []
  out.split('\n').forEach((l) => {
    if (l.charAt(0) !== '|') return
    flat.push(l.split('|').slice(1, -1).map((c) => c.trim()))
  })
  const gi = flat.findIndex((r) => r[0] === 'g7ok')
  if (gi < 0) {
    say('  !! SQL 没跑起来 ' + label + '（输出里没有哨兵行 g7ok，这种假绿必须当红看）')
    return null
  }
  const header = flat[gi + 1] || []
  const data = flat.slice(gi + 2)
  if (!data.length) return { header: header, data: [], empty: true }
  return { header: header, data: data, empty: false }
}
function sqlCell (label, text) {
  const r = sqlRows(label, text)
  if (!r) return null
  if (!r.data.length) return ''
  return r.data[0][0]
}

// ===========================================================================
// 开工前置：两个服务必须在跑，否则整个文件红得没有意义
// ===========================================================================
async function preflight () {
  const h = await fetch(API + '/actuator/health', { signal: AbortSignal.timeout(8000) })
  const j = await h.json()
  check('P0', '后端 ' + API + ' 健康', j.status === 'UP', 'status=' + j.status)
  if (j.status !== 'UP') throw new Error('后端不健康，停止取证（不拍假图、不写假数）')
  const f = await fetch(WEB, { signal: AbortSignal.timeout(8000) })
  check('P1', '前端 ' + WEB + ' 可访问', f.status === 200, 'HTTP ' + f.status)
}

let demo = null
let superTok = null
const st = {}

// ===========================================================================
// A 线 —— D6：推荐流连点三次不重复 + 每张卡带理由 + 负反馈三层记账
// ===========================================================================
async function lineA () {
  say('')
  say('==== A 线（D6 推荐流契约 + 负反馈三层记账）====')

  // A1 游客：推荐接口必须 401/10002，不能退化成「给游客一条长得像推荐的列表」
  const g = await expectRejected('A1 游客 recommend', 'GET', '/api/feed/recommend?size=6&page=1', null, undefined, 'guest', [10002])
  check('A1', '游客打 GET /api/feed/recommend 被拒（不退化成热读榜）', g.rejected === true,
    'HTTP ' + g.r.status + ' code=' + g.r.code + ' msg=' + g.r.msg)

  demo = await login(DEMO_USER)
  st.demoId = demo.userId
  say('  -- ' + DEMO_USER + ' userId=' + st.demoId)

  // A2 这一批缓存的真实行量：判据要 18 条不重复，先得确认库里够 18 条可读
  const cacheRows = sqlCell('a2-cache', 'SELECT COUNT(*) FROM recommend_result WHERE user_id=' + st.demoId + " AND scene='feed' AND deleted=0;")
  const cacheMode = sqlCell('a2-mode', 'SELECT mode FROM recommend_result WHERE user_id=' + st.demoId + " AND scene='feed' AND deleted=0 LIMIT 1;")
  st.cacheRows = cacheRows === null ? null : Number(cacheRows)
  check('A2', 'DB：这一批 recommend_result 够三屏（≥18 行）', st.cacheRows === null ? null : st.cacheRows >= 18,
    st.cacheRows === null ? 'SQL 不可用，SKIP' : 'active=' + st.cacheRows + ' mode=' + cacheMode)

  // A3 连取三屏，攒够 18 条判据
  const pages = []
  const ids18 = []
  for (let p = 1; p <= 3; p++) {
    let r = null
    for (let i = 0; i <= MAX_RETRY; i++) {
      r = await http('GET', '/api/feed/recommend?size=6&page=' + p, demo.token, undefined, DEMO_USER)
      if (r.code === 10010 && r.status === 429) { throttle.retried++; await sleep(60000 - (Date.now() % 60000) + 2000); continue }
      break
    }
    if (r.code !== 0) { check('A3.' + p, '第 ' + p + ' 屏请求成功', false, 'code=' + r.code + ' msg=' + r.msg); continue }
    const rows = rowsFrom(r.data)
    pages.push({ p, rows, hasMore: r.data && r.data.hasMore, total: r.data && r.data.total })
    rows.forEach((x) => ids18.push(rowId(x)))
    say('  -- 第 ' + p + ' 屏 ' + rows.length + ' 条：' + ids18.slice(-6).join(',') +
      '｜通道 ' + channelsOf(rows).join(',') + '｜hasMore=' + r.data.hasMore + ' total=' + r.data.total)
  }
  st.pages = pages
  st.ids18 = ids18.filter((x) => x !== null)

  check('A3', '三屏各回 6 条（page=1/2/3 每屏都满）', pages.length === 3 && pages.every((x) => x.rows.length === 6),
    pages.map((x) => 'p' + x.p + '=' + x.rows.length).join(' '))
  const uniq = new Set(st.ids18)
  check('A4', 'D6 核心：三屏 18 条 id 两两不重复', uniq.size === 18, 'distinct=' + uniq.size + ' / ' + st.ids18.length)
  check('A5', '出参 total 恒为 -1（游标分页不承诺总数，前端不许显示「共 N 条」）',
    pages.every((x) => Number(x.total) === -1), pages.map((x) => 'p' + x.p + '.total=' + x.total).join(' '))
  const badCh = st.ids18.length ? pages.flatMap((x) => x.rows).filter((x) => CHANNELS.indexOf(x.recallChannel) < 0) : []
  check('A6', 'recallChannel 全部落在六通道内', badCh.length === 0, badCh.length ? '越界=' + JSON.stringify(badCh.map((x) => [rowId(x), x.recallChannel])) : CHANNELS.join('/'))
  const allRows = pages.flatMap((x) => x.rows)
  const noReason = allRows.filter((x) => !x.reason)
  check('A7', '每条卡片都带非空 reason（热读兜底通道除外，见 A8）',
    noReason.every((x) => x.recallChannel === 'hot'), '空理由 ' + noReason.length + ' 条，通道分布=' + JSON.stringify(noReason.reduce((a, x) => { a[x.recallChannel] = (a[x.recallChannel] || 0) + 1; return a }, {})))
  const hotNull = allRows.filter((x) => x.recallChannel === 'hot')
  check('A8', '热读兜底诚实标注：reason 与 score 都为 null（不给对照组编话）',
    hotNull.every((x) => x.reason === null && (x.score === null || x.score === undefined)),
    'hot 行数=' + hotNull.length + (hotNull.length ? ' 例=' + JSON.stringify(hotNull[0]).slice(0, 120) : ''))
  const personalized = allRows.filter((x) => x.recallChannel && x.recallChannel !== 'hot')
  const polluted = hasHotWord(personalized)
  check('A9', '个性化通道不夹热读文案（ReasonBuilder 早退抢文案那个 bug 的复数判据）',
    polluted.length === 0, polluted.length ? '污染=' + JSON.stringify(polluted.map((x) => [rowId(x), x.recallChannel, x.reason])) : '检查 ' + personalized.length + ' 条，三句热读文案 0 命中')
  const scored = personalized.filter((x) => Number(x.score) > 0)
  check('A10', '个性化通道带离线打分（score>0，前端「为什么推给我」有真数可显示）',
    personalized.length === 0 || scored.length === personalized.length,
    scored.length + '/' + personalized.length + ' 例=' + (personalized[0] ? rowId(personalized[0]) + ':' + personalized[0].score : '-'))
  const cardShape = allRows[0] && allRows[0].post ? Object.keys(allRows[0].post) : []
  check('A11', '卡片与广场同形（post 里带齐 title/displayName/topics/四个计数）',
    ['id', 'title', 'displayName', 'topics', 'viewCnt', 'likeCnt', 'commentCnt', 'collectCnt'].every((k) => cardShape.indexOf(k) >= 0),
    cardShape.length + ' 字段')

  // A12 曝光记账：markExposed 不采样（发出的每一条都置位），user_action 的 expose 按 3/10 确定性取模。
  // 【为什么这儿要一直翻到批次见底，而不是只看六屏】命中位取决于帖子 id 的尾数：demo01 的 user_id=2，
  // 命中条件是 floorMod(2*31 + item_id, 10) < 3，也就是 item_id % 10 落在 {8,9,0}。
  // 现查 recommend_result（一条 SELECT 数出来的，不是猜的）：这一批 50 条里只有 1 条在窗口内，
  // 所以前三屏 18 条、乃至前六屏 36 条都极可能一条都采不中 —— run1「应采中 0 条」就是这么来的。
  // 采不中时「逐条一致」是对空集自证，绿灯没有意义，因此夹具必须翻到这一批见底；
  // 判据同时钉住「应采中 >=1 条」，采不到就记红，绝不自证。
  // 「18 条不重复」那两条判据（A4 / D2）仍然只看前三屏，两边不互相污染。
  const extra = []
  const seenExtra = new Set()
  let stopPage = 3
  for (let p = 4; p <= 14; p++) {
    const r = await http('GET', '/api/feed/recommend?size=6&page=' + p, demo.token, undefined, DEMO_USER)
    const got = r.code === 0 ? rowsFrom(r.data) : []
    if (!got.length) break
    stopPage = p
    got.forEach((x) => { const id = rowId(x); if (id !== null && !seenExtra.has(id)) { seenExtra.add(id); extra.push(id) } })
  }
  st.idsExtra = extra
  const served = st.ids18.concat(extra)
  say('  -- 曝光记账夹具：前三屏 ' + st.ids18.length + ' 条 + 第 4~' + stopPage + ' 屏 ' + extra.length +
    ' 条 = 去重后 ' + served.length + ' 条，其中按取模公式应采中 ' + served.filter((id) => sampled(st.demoId, id)).length + ' 条')
  const exposed = sqlCell('a12-exposed', 'SELECT COUNT(*) FROM recommend_result WHERE user_id=' + st.demoId + " AND scene='feed' AND deleted=0 AND is_exposed=1;")
  check('A12', 'DB：缓存行的 is_exposed 被置位（发出的那几条）',
    exposed === null ? null : Number(exposed) >= 6,
    exposed === null ? 'SQL 不可用，SKIP' : '置位行数=' + exposed + '（本轮共取到 ' + served.length + ' 条）')
  const expRows = sqlRows('a13-expose', 'SELECT target_id FROM user_action WHERE user_id=' + st.demoId +
    " AND action_type='expose' AND scene='feed' AND day_bucket=CURDATE() AND deleted=0;")
  if (expRows === null || !served.length) {
    check('A13', 'DB：user_action 的 expose 行与 sampleExpose 取模公式逐条一致', null, 'SQL 不可用或一条都没取到，SKIP')
  } else {
    const gotSet = new Set(expRows.data.map((r) => Number(r[0])))
    const wrong = served.filter((id) => sampled(st.demoId, id) !== gotSet.has(id))
    const nSampled = served.filter((id) => sampled(st.demoId, id)).length
    // 反向也查一遍：表里今天这些 expose 行，每一条都必须满足同一个取模公式
    // （防「不知道从哪儿灌进来的分母」，也让这条判据在夹具采中 0 条时不可能蒙绿）
    const bogus = expRows.data.filter((r) => !sampled(st.demoId, Number(r[0]))).map((r) => r[0])
    check('A13', 'DB：expose 行与 floorMod(userId*31+itemId,10)<3 逐条一致（30% 采样可离线复算）',
      nSampled >= 1 && wrong.length === 0 && bogus.length === 0,
      served.length + ' 条夹具中应采中 ' + nSampled + ' 条，实测命中 ' + served.filter((id) => gotSet.has(id)).length +
      ' 条；表内不符公式 ' + bogus.length + ' 行' + (nSampled >= 1 ? '' : '（应采中 0 条＝判据空转，记红不记绿）'))
  }

  // A14-A19 负反馈三层记账。目标帖的三个条件：本尊在这一批缓存里 + 今天还没驳回过 +
  // 至少 1 个邻居也在这一批缓存里（否则第二层「联压邻居」没有夹具可验，只能记 SKIP）。
  // 【集合运算全部放在内存里】缓存行与今天的驳回行各查一次 SQL，邻居候选走 /similar 接口。
  // 先前那一版「每个邻居单独发一条 SQL」会往取证脚本里塞三十多次 mysql 调用：慢，
  // 而且任何一次超时都会把整条判据拖成 SKIP。汇总数必须由一条命令从表里数出来（踩坑第 14 轮）。
  const cacheAgg = sqlRows('a14-cache', 'SELECT item_id, COUNT(*) FROM recommend_result WHERE user_id=' + st.demoId +
    " AND scene='feed' AND deleted=0 GROUP BY item_id;")
  const dislikedAgg = sqlRows('a14-disliked', "SELECT target_id FROM user_action WHERE user_id=" + st.demoId +
    " AND action_type='dislike' AND target_type='post' AND day_bucket=CURDATE() AND deleted=0;")
  let target = null
  st.simProbes = []
  if (!cacheAgg || !dislikedAgg) {
    check('A14', '找得到一条「在缓存里、今天没驳回过、且有邻居也在缓存里」的目标帖', null, 'SQL 不可用，SKIP（不判 0 也不判过）')
  } else {
    const cacheCnt = new Map(cacheAgg.data.map((r) => [Number(r[0]), Number(r[1])]))
    const dislikedToday = new Set(dislikedAgg.data.map((r) => Number(r[0])))
    say('  -- 这一批缓存里有 ' + cacheCnt.size + ' 个不同帖子；' + DEMO_USER + ' 今天已驳回过 ' + dislikedToday.size + ' 个')
    const candIds = []
    for (const id of personalized.map(rowId)) {
      if (id !== null && cacheCnt.has(id) && !dislikedToday.has(id) && candIds.indexOf(id) < 0) candIds.push(id)
    }
    let orphan = null
    for (const id of candIds.slice(0, 8)) {
      const sim = await http('GET', '/api/posts/' + id + '/similar?size=12', demo.token, undefined, DEMO_USER)
      const simRows = sim.code === 0 ? rowsFrom(sim.data) : []
      st.simProbes.push({ id, rows: simRows })
      const nb = simRows.map(rowId).filter((x) => x && x !== id)
      const overlap = nb.filter((n) => cacheCnt.has(n))
      say('  -- 候选 ' + id + '：相似位 ' + nb.length + ' 条，其中也在这一批缓存里 ' + overlap.length + ' 条')
      if (!orphan && nb.length) orphan = { id, overlap: [] }
      if (overlap.length >= 1) { target = { id, overlap }; break }
    }
    if (!target) target = orphan
    if (!target) {
      check('A14', '找得到一条「在缓存里、今天没驳回过、且有邻居也在缓存里」的目标帖', null,
        '三屏 18 条里没有一条既在这一批缓存里又没被今天驳回过（合格候选 ' + candIds.length + ' 个），SKIP')
    } else {
      st.target = target
      say('  -- 目标帖=' + target.id + ' 邻居中同样在缓存里的=' + (target.overlap.join(',') || '(无：第二层将记 SKIP)'))
      const cntSql = 'SELECT COUNT(*) FROM recommend_result WHERE user_id=' + st.demoId + " AND scene='feed' AND deleted=0;"
      const beforeTotal = Number(sqlCell('a15-before', cntSql))
      const d = await http('POST', '/api/feed/dislike', demo.token, { postId: target.id }, DEMO_USER)
      const removed = d.data ? Number(d.data.removed) : 0
      const removedSim = d.data ? Number(d.data.removedSimilar) : 0
      check('A14', 'POST /api/feed/dislike 成功并回执两个真实计数', d.code === 0 && d.data && typeof d.data.removed === 'number',
        'code=' + d.code + ' msg=' + d.msg + ' 回执=' + JSON.stringify(d.data))
      const afterTotal = Number(sqlCell('a15-after', cntSql))
      check('A15', '第一层：本尊被逻辑删，缓存总数正好减少 removed+removedSimilar 行（不是只减 removed）',
        removed >= 1 && beforeTotal - afterTotal === removed + removedSim,
        'removed=' + removed + ' removedSimilar=' + removedSim + ' 缓存 ' + beforeTotal + '→' + afterTotal +
        ' 实际减少=' + (beforeTotal - afterTotal))
      const watch = [target.id].concat(target.overlap)
      const resid = sqlRows('a16-resid', 'SELECT item_id, COUNT(*) FROM recommend_result WHERE user_id=' + st.demoId +
        " AND scene='feed' AND deleted=0 AND item_id IN (" + watch.join(',') + ') GROUP BY item_id;')
      check('A16', '第二层：DB 里本尊与被联压的邻居都读不到活跃行了',
        resid === null ? null : (target.overlap.length ? resid.data.length === 0 : null),
        resid === null ? 'SQL 不可用，SKIP' : (target.overlap.length
          ? '本尊 ' + target.id + ' 与邻居 ' + target.overlap.join(',') + ' 残留活跃行=' + JSON.stringify(resid.data) + '（空数组才算过）'
          : '邻居不在这批缓存里，联压层没有夹具，SKIP'))
      const wa = sqlRows('a17-w', "SELECT weight, scene, target_type FROM user_action WHERE user_id=" + st.demoId +
        " AND action_type='dislike' AND target_type='post' AND target_id=" + target.id + ' AND day_bucket=CURDATE() AND deleted=0;')
      check('A17', '第三层：行为表落一行 dislike，weight=-3.00、scene=feed（矩阵真的记住了）',
        wa === null ? null : (wa.data.length === 1 ? (wa.data[0][0] === '-3.00' && wa.data[0][1] === 'feed' && wa.data[0][2] === 'post') : false),
        wa === null ? 'SQL 不可用，SKIP' : JSON.stringify(wa.data) + '｜DDL 注释写 -5、代码常量是 -3，见清单「口径偏离」')
      const dup = await http('POST', '/api/feed/dislike', demo.token, { postId: target.id }, DEMO_USER)
      const dupAgg = sqlRows('a18-dup', "SELECT weight, COUNT(*) FROM user_action WHERE user_id=" + st.demoId +
        " AND action_type='dislike' AND target_type='post' AND target_id=" + target.id + ' AND day_bucket=CURDATE() GROUP BY weight;')
      check('A18', '幂等：连点两次不再删行、也不再叠权重（uk_action 带 day_bucket，撞键是覆盖不是累加）',
        dup.code === 0 && Number(dup.data && dup.data.removed) === 0 && dupAgg !== null &&
        dupAgg.data.length === 1 && Number(dupAgg.data[0][1]) === 1 && dupAgg.data[0][0] === '-3.00',
        '第二次 removed=' + (dup.data ? dup.data.removed : '?') + ' removedSimilar=' + (dup.data ? dup.data.removedSimilar : '?') +
        ' 行为表=' + (dupAgg ? JSON.stringify(dupAgg.data) : 'SQL 不可用'))
      const after = []
      for (let p = 1; p <= 3; p++) {
        const r = await http('GET', '/api/feed/recommend?size=6&page=' + p, demo.token, undefined, DEMO_USER)
        if (r.code === 0) rowsFrom(r.data).forEach((x) => after.push(rowId(x)))
      }
      st.idsAfter = after
      const still = after.filter((id) => id === target.id || target.overlap.indexOf(id) >= 0)
      check('A19', 'D6 当场没：驳回之后再取三屏，本尊与被联压的邻居一条都不回来', still.length === 0,
        '三屏 ' + after.length + ' 条，残留=' + JSON.stringify(still))
    }
  }

  // A20 参数与鉴权边界：坏入参必须 10001/400，不许 500 兜底码
  const z = await expectRejected('A20a', 'POST', '/api/feed/dislike', demo.token, { postId: 0 }, DEMO_USER, [10001])
  check('A20a', 'dislike postId=0 被拒（10001/400，不是 90004 兜底）', z.rejected === true, 'code=' + z.r.code + ' msg=' + z.r.msg)
  const nb = await expectRejected('A20b', 'POST', '/api/feed/dislike', demo.token, undefined, DEMO_USER, [10001])
  check('A20b', 'dislike 空请求体被拒（@RequestBody(required=false) 走参数判据）', nb.rejected === true, 'code=' + nb.r.code)
  const ng = await expectRejected('A20c', 'POST', '/api/feed/dislike', null, { postId: 1 }, 'guest', [10002])
  check('A20c', '游客打 dislike 被拒（10002/401）', ng.rejected === true, 'code=' + ng.r.code)
  const big = await http('GET', '/api/feed/recommend?size=999&page=1', demo.token, undefined, DEMO_USER)
  check('A21', 'size 越界被夹到上限（PageQuery.MAX_SIZE=50，不报错也不越权）',
    big.code === 0 && rowsFrom(big.data).length <= 50, '回 ' + rowsFrom(big.data).length + ' 条')
}

// ===========================================================================
// B 线 —— T7.16：详情页「看了又看」三层召回
// ===========================================================================
async function lineB () {
  say('')
  say('==== B 线（T7.16 相似位三层召回）====')

  const g = await expectRejected('B1', 'GET', '/api/posts/1/similar?size=6', null, undefined, 'guest', [10002])
  check('B1', '游客打 /api/posts/{id}/similar 被拒（10002/401，不退化成公开热榜）', g.rejected === true, 'code=' + g.r.code)

  // 源帖选择：先复用 A 线刚打过的 /similar 响应（同一轮、同一账号、同一批缓存，读数可以直接继承），
  // 不够再往后试。省配额是一半理由，另一半是让 B 线和 A 线看的是同一批邻居。
  // 但真正用于断言的 rows 必须重新按 size=6 取一次：A 线那批是按 size=12 打的，
  // 而 B3 判的是「默认整页 6 条」，复用 12 条的响应会把判据自己搞红。
  let srcId = null
  const seenProbes = st.simProbes || []
  for (const pr of seenProbes) {
    if (pr.rows.some((x) => x.recallChannel === 'itemcf')) { srcId = pr.id; break }
  }
  const triedIds = seenProbes.map((p) => p.id)
  if (srcId === null) {
    for (const id of st.ids18.filter((x) => triedIds.indexOf(x) < 0).slice(0, 8)) {
      const r = await http('GET', '/api/posts/' + id + '/similar?size=12', demo.token, undefined, DEMO_USER)
      if (r.code !== 0) continue
      const rs = rowsFrom(r.data)
      seenProbes.push({ id, rows: rs })
      if (rs.some((x) => x.recallChannel === 'itemcf')) { srcId = id; break }
    }
  }
  if (srcId === null) {
    check('B2', '找一个能出 ItemCF 邻居的源帖（三层召回的第 1 层）', null,
      '试过的 ' + (seenProbes.length) + ' 个源帖都没出邻居行，SKIP（item_similarity 是离线算的，接口没坏）')
    return
  }
  const srcResp = await http('GET', '/api/posts/' + srcId + '/similar?size=6', demo.token, undefined, DEMO_USER)
  if (srcResp.code !== 0) { check('B2', '源帖 ' + srcId + ' 按默认 size 重取相似位', false, 'code=' + srcResp.code + ' msg=' + srcResp.msg); return }
  const src = { id: srcId, rows: rowsFrom(srcResp.data) }
  st.simItemcf = src
  const rows = src.rows
  const ids = rows.map(rowId)
  check('B2', '源帖能出 ItemCF 邻居（第 1 层：一起被读过的那批）', rows.some((x) => x.recallChannel === 'itemcf'),
    '源帖=' + src.id + ' 通道=' + channelsOf(rows).join(','))
  check('B3', '默认 size=6：整页返回、条数 ≤6、不含源帖自己',
    ids.length <= 6 && ids.indexOf(src.id) < 0 && ids.length > 0, ids.length + ' 条 ' + ids.join(','))
  const badCh = rows.filter((x) => ['itemcf', 'content', 'hot'].indexOf(x.recallChannel) < 0)
  check('B4', '相似位通道只许三层（itemcf / content / hot），不混 usercf/explore/emotion', badCh.length === 0,
    badCh.length ? JSON.stringify(badCh.map((x) => [rowId(x), x.recallChannel])) : channelsOf(rows).join(','))
  const noWhy = rows.filter((x) => !x.reason)
  check('B5', '相似位每条都带理由（这里的理由由 SimilarPostService 现给，不读缓存）', noWhy.length === 0, noWhy.length + ' 条空理由')
  const mislabel = rows.filter((x) => x.recallChannel === 'itemcf' && String(x.reason).indexOf(SIM_HOT_WORD) >= 0)
  check('B6', 'itemcf 行不披热读补位的文案（第一层有货就不许说「共读记录还太少」）', mislabel.length === 0,
    mislabel.length ? JSON.stringify(mislabel.map((x) => [rowId(x), x.reason])) : rows.filter((x) => x.recallChannel === 'itemcf').length + ' 条 itemcf 全带邻居话术')

  const s999 = await http('GET', '/api/posts/' + src.id + '/similar?size=999', demo.token, undefined, DEMO_USER)
  const s0 = await http('GET', '/api/posts/' + src.id + '/similar?size=0', demo.token, undefined, DEMO_USER)
  const s3 = await http('GET', '/api/posts/' + src.id + '/similar?size=3', demo.token, undefined, DEMO_USER)
  check('B7', 'size 越界不报错、只夹到区间（999→≤12，0→默认 6，3→≤3）',
    s999.code === 0 && rowsFrom(s999.data).length <= 12 && s0.code === 0 && rowsFrom(s0.data).length <= 6 && s3.code === 0 && rowsFrom(s3.data).length <= 3,
    '999→' + rowsFrom(s999.data).length + ' 0→' + rowsFrom(s0.data).length + ' 3→' + rowsFrom(s3.data).length)

  // 冷帖：没有 item_similarity 邻居行的公开帖，必须靠同话题 + 质量分榜补出内容（§10.6 第 1 条：不空窗）
  const coldSql = sqlRows('b8-cold', 'SELECT p.id FROM post p LEFT JOIN item_similarity s ON s.item_id=p.id AND s.deleted=0 ' +
    "WHERE p.deleted=0 AND p.status='PUBLISHED' AND p.visibility='public' AND p.user_id<>" + st.demoId +
    ' AND s.item_id IS NULL ORDER BY p.id DESC LIMIT 8;')
  const coldIds = coldSql ? coldSql.data.map((r) => Number(r[0])) : []
  let cold = null
  for (const id of coldIds) {
    const r = await http('GET', '/api/posts/' + id + '/similar?size=6', demo.token, undefined, DEMO_USER)
    if (r.code === 0 && rowsFrom(r.data).length > 0) { cold = { id, rows: rowsFrom(r.data) }; break }
  }
  // 把冷帖夹具留给 D 线：界面层的「冷帖不空窗」必须用 B 线刚刚实测过不空窗的那一条，
  // 否则截图线会自己另找一个可能真的没兜底的帖子，拍出来一张空推荐位还判不出是谁的错。
  st.cold = cold
  st.coldIds = coldIds
  if (!sqlProbe()) check('B8', '冷帖（无 ItemCF 邻居）不空窗：兜底两层能补出内容', null, 'SQL 不可用，SKIP')
  else check('B8', '冷帖（无 ItemCF 邻居）不空窗：兜底两层能补出内容', cold !== null && cold.rows.length > 0,
    cold ? '冷帖=' + cold.id + ' 出' + cold.rows.length + ' 条 通道=' + channelsOf(cold.rows).join(',') : '试了 ' + coldIds.join(',') + ' 全部空手而归')
  if (cold) {
    const cw = cold.rows.filter((x) => x.recallChannel === 'itemcf')
    check('B9', '冷帖的结果里不许出现 itemcf（它压根没有邻居行，出现就是脏缓存）', cw.length === 0,
      cw.length ? JSON.stringify(cw.map((x) => [rowId(x), x.reason])) : channelsOf(cold.rows).join(','))
  }

  // 不可见与不存在同形：私密帖 / 审核中帖 / 不存在的 id
  const priv = sqlCell('b10-priv', "SELECT id FROM post WHERE deleted=0 AND visibility='private' AND user_id<>" + st.demoId + ' ORDER BY id DESC LIMIT 1;')
  const aud = sqlCell('b10-aud', "SELECT id FROM post WHERE deleted=0 AND status IN ('PENDING','AUDITING','REJECTED') AND user_id<>" + st.demoId + ' ORDER BY id DESC LIMIT 1;')
  for (const [label, idv, want] of [['B10', priv, [30001, 30002]], ['B11', aud, [30001, 30002]]]) {
    if (!idv) { check(label, '不可见源帖与不存在同形（DB 里没有这种夹具）', null, 'SQL 没查到夹具，SKIP'); continue }
    const r = await expectRejected(label, 'GET', '/api/posts/' + idv + '/similar?size=6', demo.token, undefined, DEMO_USER, want)
    check(label, '不可见源帖（' + (label === 'B10' ? '私密' : '审核中/未通过') + ' id=' + idv + '）与不存在同形，不许当探针',
      r.rejected === true, 'HTTP ' + r.r.status + ' code=' + r.r.code + ' msg=' + r.r.msg)
  }
  const nf = await expectRejected('B12', 'GET', '/api/posts/99999999/similar?size=6', demo.token, undefined, DEMO_USER, [30001])
  check('B12', '不存在的源帖 → 30001/404（不是 200 空数组）', nf.rejected === true, 'code=' + nf.r.code + ' HTTP=' + nf.r.status)

  // 相似位不记曝光（手册 §10.6：去重窗口只管主 feed）
  const cntOf = () => sqlCell('b13-exp', "SELECT COUNT(*) FROM user_action WHERE user_id=" + st.demoId +
    " AND action_type='expose' AND scene='feed' AND day_bucket=CURDATE();")
  const e0 = cntOf()
  for (const id of [src.id, cold ? cold.id : src.id, st.ids18[0]]) {
    await http('GET', '/api/posts/' + id + '/similar?size=12', demo.token, undefined, DEMO_USER)
  }
  const e1 = cntOf()
  check('B13', 'DB：连打三次相似位，expose 行数一动不动（被动区块不灌分母）',
    e0 === null || e1 === null ? null : Number(e0) === Number(e1), 'before=' + e0 + ' after=' + e1)
}

// ===========================================================================
// C 线 —— D7：离线作业可手动触发、健康度可查、「通道占比」日志可查
// ===========================================================================
function logTailFrom (offset) {
  if (!fs.existsSync(BACKEND_LOG)) return null
  const buf = fs.readFileSync(BACKEND_LOG)
  const dec = new TextDecoder('gbk')
  const tail = buf.length > offset ? buf.subarray(offset) : Buffer.alloc(0)
  return dec.decode(tail)
}

async function lineC () {
  say('')
  say('==== C 线（D7 离线作业与健康度）====')

  const denied = await expectRejected('C1', 'POST', '/api/admin/rec/rebuild', demo.token, undefined, DEMO_USER, [10003])
  check('C1', '普通 USER 打管理端重算被拒（10003，且不是被限流挡的假拒）', denied.rejected === true,
    'HTTP ' + denied.r.status + ' code=' + denied.r.code + ' msg=' + denied.r.msg)

  const sup = await login(ADMIN_USER, ADMIN_USER)
  superTok = sup.token
  const s0 = await http('GET', '/api/admin/rec/status', sup.token, undefined, ADMIN_USER)
  if (s0.code !== 0) { check('C2', 'GET /api/admin/rec/status', false, 'code=' + s0.code + ' msg=' + s0.msg); return }
  st.statusBefore = s0.data
  say('  -- status(前)：' + JSON.stringify(s0.data).slice(0, 400))
  check('C2', '健康度读数齐全（running/lastFinishedAt/cacheRows/similarityRows/cacheMode/resultTtlMinutes）',
    ['running', 'lastFinishedAt', 'cacheRows', 'similarityRows', 'cacheMode', 'resultTtlMinutes', 'now'].every((k) => k in s0.data),
    'cacheRows=' + s0.data.cacheRows + ' similarityRows=' + s0.data.similarityRows + ' cacheMode=' + s0.data.cacheMode + ' ttl=' + s0.data.resultTtlMinutes + 'min')
  const c3Db = sqlCell('c3-cache', 'SELECT COUNT(*) FROM recommend_result WHERE deleted=0;')
  check('C3', '接口读数与 DB 对得上：cacheRows == recommend_result 活跃行',
    c3Db === null ? null : Number(c3Db) === Number(s0.data.cacheRows),
    c3Db === null ? 'SQL 不可用，SKIP' : '接口=' + s0.data.cacheRows + ' DB=' + c3Db)

  const calcBefore = sqlCell('c4-calc', 'SELECT COALESCE(MAX(calc_at), ' + Q + 'none' + Q + ') FROM recommend_result WHERE deleted=0;')
  const oplogBefore = sqlCell('c4-oplog', 'SELECT COUNT(*) FROM admin_op_log;')
  st.logOffset = fs.existsSync(BACKEND_LOG) ? fs.statSync(BACKEND_LOG).size : -1
  say('  -- 重算前：MAX(calc_at)=' + calcBefore + ' admin_op_log=' + oplogBefore + ' 日志偏移=' + st.logOffset + ' 字节')

  let rb = null
  for (let i = 0; i <= MAX_RETRY; i++) {
    const t0 = Date.now()
    rb = await http('POST', '/api/admin/rec/rebuild', sup.token, undefined, ADMIN_USER)
    rb.wall = Date.now() - t0
    if (rb.code === 10010 && rb.status === 429) {
      throttle.retried++
      say('  -- 重算撞上重入锁/限流（上一轮还在跑），等 20s 重试第 ' + (i + 1) + ' 次')
      await sleep(20000)
      continue
    }
    break
  }
  st.rebuild = rb
  say('  -- Summary：' + JSON.stringify(rb.data))
  check('C4', 'POST /api/admin/rec/rebuild 同步返回一轮摘要（不是 90001 的桩）', rb.code === 0 && !!rb.data,
    'HTTP ' + rb.status + ' code=' + rb.code + ' msg=' + rb.msg + ' 墙钟=' + rb.wall + 'ms')
  const sm = rb.data || {}
  st.summary = sm
  check('C5', '摘要六项读数都 >0（质量分/话题热度/相似度/有邻居/用户/结果行）',
    ['qualityRows', 'topicRows', 'similarityRows', 'itemsWithNeighbors', 'users', 'resultRows'].every((k) => Number(sm[k]) > 0),
    'q=' + sm.qualityRows + ' topic=' + sm.topicRows + ' sim=' + sm.similarityRows + ' nb=' + sm.itemsWithNeighbors + ' users=' + sm.users + ' rows=' + sm.resultRows)
  check('C6', '耗时可接受（作业 calcMs 与探针墙钟都 <120s，验收现场等得起）',
    Number(sm.calcMs) > 0 && Number(sm.calcMs) < 120000 && rb.wall < 120000, 'calcMs=' + sm.calcMs + 'ms 墙钟=' + rb.wall + 'ms')
  const share = sm.channelShare || {}
  st.channelShare = share
  const keys = Object.keys(share)
  const sum = keys.reduce((a, k) => a + Number(share[k]), 0)
  check('C7', '通道占比的 key 全部属于六通道（ENUM 写错会被静默存成默认值，这条是防线）',
    keys.length > 0 && keys.every((k) => CHANNELS.indexOf(k) >= 0), keys.map((k) => k + '=' + share[k]).join(' '))
  check('C8', '占比之和 ≥ 结果行数（写库时会跳过未知通道，等号是常态）', sum >= Number(sm.resultRows),
    'sum=' + sum + ' resultRows=' + sm.resultRows + ' 差=' + (sum - Number(sm.resultRows)))
  check('C9', '诚实记录：本轮真正出现了 6 通道里的哪几路（少于六路不是 bug，是这份数据的真实分布）',
    true, '出现 ' + keys.length + '/6 路：' + keys.join(',') + '｜未出现：' + CHANNELS.filter((k) => keys.indexOf(k) < 0).join(','))

  const calcAfter = sqlCell('c10-calc', 'SELECT COALESCE(MAX(calc_at), ' + Q + 'none' + Q + ') FROM recommend_result WHERE deleted=0;')
  st.calcBefore = calcBefore
  st.calcAfter = calcAfter
  check('C10', 'DB：MAX(calc_at) 前移了（这一轮真的写过库，不是内存里空转）',
    calcBefore === null || calcAfter === null ? null : (calcBefore === 'none' ? calcAfter !== 'none' : calcAfter > calcBefore),
    calcBefore + ' → ' + calcAfter)
  // 这一批的行共用同一个 calc_at，所以「按 calc_at 数出来的行数」就是接口回执 resultRows 的独立复核。
  const batchRows = calcAfter === null || calcAfter === 'none' ? null
    : sqlCell('c11-batch', 'SELECT COUNT(*) FROM recommend_result WHERE deleted=0 AND calc_at=' + Q + calcAfter + Q + ';')
  check('C11', 'DB：这一批新写的行数 == 摘要 resultRows（接口回的不是自夸数）',
    batchRows === null ? null : Number(batchRows) === Number(sm.resultRows), 'DB=' + batchRows + ' 摘要=' + sm.resultRows)
  const oplogAfter = sqlCell('c12-oplog', 'SELECT COUNT(*) FROM admin_op_log;')
  check('C12', 'DB：admin_op_log 行数不变（重算不落审计，这条决策有判据钉住，不是漏项）',
    oplogBefore === null || oplogAfter === null ? null : Number(oplogBefore) === Number(oplogAfter),
    oplogBefore + ' → ' + oplogAfter)
  const s1 = await http('GET', '/api/admin/rec/status', sup.token, undefined, ADMIN_USER)
  check('C13', 'status 复查：lastFinishedAt 前进、running 落回 false、cacheRows 与 DB 一致',
    s1.code === 0 && String(s1.data.lastFinishedAt) > String(st.statusBefore.lastFinishedAt) && s1.data.running === false,
    'lastFinishedAt ' + st.statusBefore.lastFinishedAt + ' → ' + (s1.data ? s1.data.lastFinishedAt : '?') + ' cacheRows=' + (s1.data ? s1.data.cacheRows : '?'))

  // D6 的下半句：负反馈不只是删缓存，还要在下一次重算里真的把它算没了
  if (st.target) {
    let back = 0
    for (let p = 1; p <= 3; p++) {
      const r = await http('GET', '/api/feed/recommend?size=6&page=' + p, demo.token, undefined, DEMO_USER)
      if (r.code === 0) back += rowsFrom(r.data).filter((x) => rowId(x) === st.target.id).length
    }
    const stillRow = sqlCell('c14-row', 'SELECT COUNT(*) FROM recommend_result WHERE user_id=' + st.demoId + " AND scene='feed' AND deleted=0 AND item_id=" + st.target.id + ';')
    check('C14', '重算之后驳回过的那条依然不回来（行为矩阵 -3 生效，不是只删了缓存）',
      back === 0 && (stillRow === null || Number(stillRow) === 0), '三屏命中=' + back + ' 活跃行=' + stillRow)
  } else {
    check('C14', '重算之后驳回过的那条依然不回来', null, 'A 线没找到驳回目标，SKIP')
  }

  // C15 挪到 D 线之后再判（见 lineC15）：要在这段日志里查的第二句「负反馈：用户…」，
  // 是 D4 在真浏览器里点「不感兴趣」时才写进日志的 —— 在 C 线读日志的那一瞬间它还没发生。
  // 顺序错了会把对的判据读成红（run1 就是这么红的：本轮新增 1 行，只有通道占比那句）。
  // 这里只把「重算之前的日志偏移」记进 st.logOffset，日志本身等所有动作落完再一次性读。
}

// ---------------------------------------------------------------------------
// C15 —— 后端日志复核（必须在 D 线之后跑，理由见 lineC 末尾那段注释）
// 读「C 线记录的日志偏移 → 当前文件末尾」，判两句：
//   1) 重算那一行的「通道占比」——手册 D7 的验收物；
//   2) 界面点出来的那句「负反馈：用户」——FR5.8 三层记账里唯一落在日志上的一句。
// 日志是 Windows 控制台按 GBK 落盘的，所以整段读回来再解码，不在 shell 里 grep。
// ---------------------------------------------------------------------------
async function lineC15 () {
  st.logExcerpt = []
  say('')
  say('==== C15 补线（后端日志复核：读 C 线记录的偏移 → 当前末尾）====')
  const tail = st.logOffset >= 0 ? logTailFrom(st.logOffset) : null
  if (tail === null) {
    check('C15', '后端日志里能查到「通道占比」与「负反馈：用户」两行（D7 的验收物）', null,
      '日志文件不可读或偏移没取到：' + BACKEND_LOG + ' offset=' + st.logOffset)
    return
  }
  const keep = tail.split('\n').filter((l) => /推荐重算完成|通道占比|负反馈：用户|推荐流退热度兜底|相似位：拦截/.test(l))
  st.logExcerpt = keep
  const hasShare = keep.some((l) => /推荐重算完成.*通道占比/.test(l))
  const hasFb = keep.some((l) => /负反馈：用户/.test(l))
  check('C15', '后端日志里能查到「通道占比」与「负反馈：用户」两行（D7 的验收物）',
    hasShare && hasFb, '偏移 ' + st.logOffset + ' 之后新增 ' + keep.length + ' 行推荐类日志：通道占比=' +
    hasShare + ' 负反馈=' + hasFb + '（GBK 落盘、脚本内解码，原文一字不改地写进 d7-log-excerpt.log）')
}

// ===========================================================================
// D 线 —— 真浏览器（界面层证据：截图 + DOM 判据 + 深色残留 + 控制台红字）
// ===========================================================================
// 【为什么接口线全绿还要再跑这一条】阶段 3 那颗最贵的雷：npm run build exit 0、
// 冒烟 246 项全绿、页面照样白屏（TopicDetailView 调了组件里根本不存在的 fmtHot）。
// 「接口对、单测对、构建对」三件事同时成立时界面仍然可以是坏的，所以这里每张图
// 都挂一条能失败的判据：判据不成立就进 FAIL、影响退出码，不留「拍了但没人核对」的图。
// 【令牌只能用 addInitScript 种】路由守卫读的是 localStorage 的 mindisle_token，
// goto 之后再塞会先被弹回 /login 一趟，回来时人已经在另一个页面上，判据拍不到东西。
// 【这一线的红字与深色残留都算账】.el-message / .el-popper 这些是 Element Plus 自己画背景
// 的组件，theme.css 只覆盖了少数 CSS 变量，所以「主题统一」必须逐类量一遍；
// 后六个表面（推荐卡 / 理由行 / 浮层 / 看了又看三件）是阶段 7 新增的，
// 少列一个就是给新界面留一张免检票。
const SURFACES = [
  '.el-card', '.mi-card', '.el-dialog', '.el-popover', '.el-popper', '.el-message',
  '.el-message-box', '.el-input__wrapper', '.el-textarea__inner', '.el-select-dropdown',
  '.el-radio-button__inner', '.el-tag', '.el-tabs__content', '.el-loading-mask', '.el-drawer',
  '.recommend', '.rec-card', '.rec-why', '.why-pop', '.similar', '.sim-card', '.sim-why'
]
const MI_BG = 'rgb(246, 246, 247)'  // #f6f6f7 —— 需求 Q9 于 2026-09-30 改判后的主背景，与阶段 3 同一口径
const OLD_DARK_BG = 'rgb(14, 22, 38)' // #0E1626 改判前的午夜蓝：再出现就是深色主题回退
const DARK_MAX = 60                    // 三通道都 ≤60 的底色视为「深色表面」

// FeedView 的两句「替后端说实话」兜底文案与相似位那句，抄在这里是为了做**反向**判据：
// 接口给了理由时页面不许显示兜底话，接口没给理由时页面只许显示这两句里对的那一句。
// 前端一旦「顺手」自己编一句像是个性化出来的话，D6 的下半句（诚实标注）当场作废。
const HOT_FALLBACK_UI = '这条来自热读兜底：你的协同过滤缓存这一轮没覆盖到它，后端因此没给个性化理由。'
const NO_REASON_UI = '后端这一条没返回理由（reason 为空）。这一栏宁可空着，也不替你编一句。'
const SIM_NO_REASON_UI = '后端这条没给理由（reason 为空），页面不替它编。'
const SIM_CH_LABELS = { itemcf: '共读相似', content: '同话题', hot: '热读补位' }

const cleanText = (v) => String(v === null || v === undefined ? '' : v).replace(/\s+/g, ' ').trim()
// 摊平成「判据用的形状」：只留 id/标题/通道/理由/分数，接口一改至少这里报错而不是静默。
function uiRows (list) {
  return (Array.isArray(list) ? list : []).map((row) => {
    const post = (row && row.post) || {}
    return {
      id: post.id === undefined || post.id === null ? null : Number(post.id),
      title: cleanText(post.title),
      channel: row ? row.recallChannel : null,
      reason: row ? row.reason : null,
      score: row ? row.score : null
    }
  })
}
// 屏幕上这一行「应该」是什么：接口给话就用接口的话，不给就用页面那句诚实兜底。
function expectUiReason (r, isSimilar) {
  const txt = r && r.reason !== null && r.reason !== undefined && String(r.reason) !== '' ? cleanText(r.reason) : ''
  if (txt) return txt
  if (isSimilar) return SIM_NO_REASON_UI
  return r && r.channel === 'hot' ? HOT_FALLBACK_UI : NO_REASON_UI
}
// 分数显示口径抄 FeedView#fmtRecScore：null 必须显示成「未打分」，不能显示成 0.0000
// （0.0000 会被读成「相关性极低」，而事实是它压根没参与相关性计算）。
function fmtUiScore (v) {
  if (v === null || v === undefined || v === '') return '未打分（热读兜底通道不给相关性分）'
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(4) : String(v)
}

let uiBrowser = null

async function audit (page, label) {
  const found = await page.evaluate((sels) => {
    const bad = []
    const bg = getComputedStyle(document.body).backgroundColor
    for (const s of sels) {
      document.querySelectorAll(s).forEach((el) => {
        const r = el.getBoundingClientRect()
        if (r.width < 6 || r.height < 6) return
        const cs = getComputedStyle(el)
        if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity) === 0) return
        const m = /rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/.exec(cs.backgroundColor)
        if (!m) return
        if (m[4] !== undefined && parseFloat(m[4]) === 0) return
        // 判据方向 2026-09-30 反转（见文件头）：浅色主题下白底是对的，要防的是深色残留
        const dark = Number(m[1]) <= DARK_MAX && Number(m[2]) <= DARK_MAX && Number(m[3]) <= DARK_MAX
        if (dark || cs.backgroundColor === OLD_DARK_BG) {
          bad.push(s + ' -> ' + cs.backgroundColor + ' [' + String(el.className).slice(0, 58) + ']')
        }
      })
    }
    return { bg, bad, text: (document.getElementById('app') || document.body).innerText.replace(/\s+/g, ' ').trim().length }
  }, SURFACES)
  if (found.bg !== MI_BG) {
    leakCount++
    say('  !! ' + label + ' 主背景不是浅灰底：' + found.bg + '（需求 Q9 已改判为 #f6f6f7）')
  }
  if (found.bad.length) {
    leakCount += found.bad.length
    say('  !! ' + label + ' 深色残留表面 ' + found.bad.length + ' 处：' + found.bad.slice(0, 6).join(' ; '))
  }
  return found
}

// 截图与四道闸绑成一次调用：先量再拍，任何一道不过就把读数写进清单，不重拍美化。
async function snap (page, bag, mark, spec, ok, note) {
  if (spec.scrollTo) {
    try { await page.locator(spec.scrollTo).first().scrollIntoViewIfNeeded({ timeout: 10000 }) }
    catch (e) { say('  -- ' + spec.file + ' 滚到 ' + spec.scrollTo + ' 没成功：' + String(e && e.message).slice(0, 90)) }
  }
  const a = await audit(page, spec.file)
  const nowErr = bag.console.length + bag.pageerror.length + bag.http.length
  const delta = nowErr - mark.err
  mark.err = nowErr
  const pass = ok === true && a.text >= spec.minText && a.bad.length === 0 && delta === 0
  let shotNote = note || ''
  try { await page.screenshot({ path: path.join(OUT_DIR, spec.file) }) }
  catch (e) { shotNote += '  [截图失败：' + String((e && e.message) || e).slice(0, 90) + ']' }
  if (!pass) {
    say('  !! ' + spec.file + ' 四道闸没过：判据=' + (ok === true ? 'ok' : String(ok)) +
      ' 文字=' + a.text + '(≥' + spec.minText + ') 深色残留=' + a.bad.length + ' 新增红字=' + delta)
  }
  shots.push({ file: spec.file, url: spec.url, desc: spec.desc, chars: a.text, bg: a.bg,
    leaks: a.bad.length, newErrors: delta, judgement: ok === true ? 'PASS' : String(ok), pass, note: shotNote })
  return pass
}

async function newUiPage (bag, token) {
  const context = await uiBrowser.newContext({ viewport: DESKTOP, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' })
  if (token) {
    await context.addInitScript((t) => { window.localStorage.setItem('mindisle_token', t) }, token)
  }
  const page = await context.newPage()
  page.setDefaultTimeout(45000)
  page.on('console', (m) => {
    if (m.type() !== 'error') return
    // 资源类的失败由下面的 response 通道记账（那里有 URL + 状态码），这里跳过免得同一件事数两遍
    if (/Failed to load resource/.test(m.text())) return
    bag.console.push('console.error: ' + m.text().slice(0, 200))
  })
  page.on('pageerror', (e) => bag.pageerror.push('pageerror: ' + String((e && e.message) || e).slice(0, 200)))
  page.on('response', (res) => {
    const code = res.status()
    const u = res.url()
    if (code < 400) return
    if (/favicon|\.woff2?(\?|$)/.test(u)) return
    bag.http.push('HTTP ' + code + ' ' + u.replace(WEB, ''))
  })
  return { context, page }
}

// 「先挂监听、后动手」：点完再等就漏掉了那一次响应（按钮触发的请求可能在点击的同一 tick 就回了）。
function watchUi (page, frag) {
  return page.waitForResponse((res) => res.url().indexOf(frag) >= 0, { timeout: 45000 })
}
async function jsonRows (promise) {
  try {
    const res = await promise
    const j = await res.json()
    if (!j || Number(j.code) !== 0) return { rows: [], code: j ? j.code : null }
    return { rows: uiRows(rowsFrom(j.data)), code: Number(j.code) }
  } catch (e) {
    return { rows: [], code: null, error: String((e && e.message) || e).slice(0, 140) }
  }
}
async function clickUi (page, selector, label) {
  try { await page.locator(selector).first().click({ timeout: 15000 }); return true }
  catch (e) { say('  -- ' + label + ' 没点成（' + selector + '）：' + String(e && e.message).slice(0, 110)); return false }
}

const readRecDom = (page) => page.evaluate(() => {
  const clean = (v) => String(v === null || v === undefined ? '' : v).replace(/\s+/g, ' ').trim()
  const rows = Array.prototype.slice.call(document.querySelectorAll('.recommend .rec-row'))
  const one = (root, sel) => clean((root.querySelector(sel) || {}).textContent)
  // ids = 帖子身份键。种子数据里有四条帖子同名（264 / 923 / 1075 / 1104 都叫「冒烟·匿名树洞」），
  // 拿标题当身份键会把这份数据自己的重名读成「推荐重复」，所以 PostCard 根节点补了 data-post-id。
  const ids = rows.map((r) => clean(((r.querySelector('.mi-card') || r).getAttribute('data-post-id'))))
  return {
    n: rows.length,
    ids: ids,
    idsComplete: ids.length > 0 && ids.every((x) => !!x),
    titles: rows.map((r) => one(r, '.title')),
    reasons: rows.map((r) => one(r, '.rec-reason')),
    whyBtns: rows.map((r) => !!r.querySelector('.btn-rec-why')),
    dismissBtns: rows.map((r) => !!r.querySelector('.hide')),
    head: one(document.querySelector('.recommend'), 'h2'),
    secHint: one(document.querySelector('.recommend .sec-head .hint'), 'span'),
    foot: one(document.querySelector('.recommend .rec-foot'), 'span'),
    errAlerts: Array.prototype.slice.call(document.querySelectorAll('.recommend .el-alert'))
      .map((x) => clean(x.textContent)).join(' | ').slice(0, 200)
  }
})

const readSimDom = (page) => page.evaluate(() => {
  const clean = (v) => String(v === null || v === undefined ? '' : v).replace(/\s+/g, ' ').trim()
  const sec = document.querySelector('.similar')
  const rows = sec ? Array.prototype.slice.call(sec.querySelectorAll('.sim-row')) : []
  const one = (root, sel) => clean((root.querySelector(sel) || {}).textContent)
  const ids = rows.map((r) => clean(((r.querySelector('.mi-card') || r).getAttribute('data-post-id'))))
  return {
    has: !!sec,
    n: rows.length,
    ids: ids,
    idsComplete: ids.length > 0 && ids.every((x) => !!x),
    titles: rows.map((r) => one(r, '.title')),
    reasons: rows.map((r) => one(r, '.sim-reason')),
    channels: rows.map((r) => one(r, '.sim-ch')),
    head: sec ? one(sec, 'h2.sim-h') : '',
    hint: sec ? one(sec.querySelector('.sec-head'), 'span') : '',
    emptyLine: sec ? clean((sec.querySelector('p.dim:last-of-type') || {}).textContent).slice(0, 120) : ''
  }
})

// 浮层是 teleport 到 body 的，页面上可能同时留着好几个（每张卡一个），所以只认「看得见的那一个」。
const readWhyDom = (page) => page.evaluate(() => {
  const clean = (v) => String(v === null || v === undefined ? '' : v).replace(/\s+/g, ' ').trim()
  const pops = Array.prototype.slice.call(document.querySelectorAll('.why-pop')).filter((el) => {
    const cs = getComputedStyle(el)
    const r = el.getBoundingClientRect()
    return r.width > 20 && r.height > 20 && cs.display !== 'none' && cs.visibility !== 'hidden'
  })
  const el = pops.length ? pops[pops.length - 1] : null
  if (!el) return null
  const popper = el.closest('.el-popper')
  const cs2 = popper ? getComputedStyle(popper) : null
  return {
    n: el.querySelectorAll('p').length,
    text: clean(el.textContent),
    bg: cs2 ? cs2.backgroundColor : '-',
    // 键名沿用 leak（历史证据 JSON 里就是它），但 2026-09-30 起含义反转：现在量的是「浮层有没有残留深色底」
    leak: cs2 ? (() => { const q = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(cs2.backgroundColor); return q ? (Number(q[1]) <= 60 && Number(q[2]) <= 60 && Number(q[3]) <= 60) : false })() : false
  }
})

async function lineD () {
  say('')
  say('==== D 线（真浏览器：U3 推荐流 + U4 看了又看 + 游客闸门）====')
  const dIds = ['D1', 'D2', 'D3', 'D4', 'D4b', 'D5', 'D5b', 'D6', 'D7', 'D8', 'D9']
  const skipAll = (why) => dIds.forEach((id) => check(id, '真浏览器取证（D 线整体）', null, why))
  if (!demo || !demo.token) return skipAll('A 线没拿到 ' + DEMO_USER + ' 的 token，界面层无从登录')
  let chromium = null
  try { chromium = require(PW_DIR).chromium }
  catch (e) { return skipAll('playwright-core 加载失败：' + String((e && e.message) || e).slice(0, 120)) }
  if (!fs.existsSync(CHROME)) return skipAll('找不到 Chrome：' + CHROME)
  uiBrowser = await chromium.launch({ headless: true, executablePath: CHROME })
  st.browserVersion = typeof uiBrowser.version === 'function' ? uiBrowser.version() : 'unknown'
  const bag = { console: [], pageerror: [], http: [] }
  const mark = { err: 0 }
  let ctxMain = null
  let ctxGuest = null
  try {
    const main = await newUiPage(bag, demo.token)
    ctxMain = main.context
    const page = main.page

    // ---- 01 首屏：六张卡 + 每张的理由文字与接口逐条一致 ----
    const w1 = watchUi(page, '/api/feed/recommend')
    await page.goto(WEB + '/feed', { waitUntil: 'domcontentloaded', timeout: 45000 })
    const r1 = await jsonRows(w1)
    const rows1 = r1.rows
    try { await page.waitForSelector('.recommend .rec-row', { timeout: 30000 }) } catch (e) { say('  -- 首屏没等到推荐卡') }
    const dom1 = await readRecDom(page)
    const bad1 = rows1.map((x, i) => ({ api: x, domTitle: dom1.titles[i], domReason: dom1.reasons[i] }))
      .filter((x, i) => !x.domTitle || x.domTitle !== x.api.title || x.domReason !== expectUiReason(x.api, false))
    check('D1', '首屏六张卡：DOM 张数=6、标题与接口逐条对得上、每行文字等于接口 reason（页面不替后端编话）',
      rows1.length === 6 && dom1.n === 6 && bad1.length === 0 && dom1.head === '为你推荐',
      '接口 ' + rows1.length + ' 条 / DOM ' + dom1.n + ' 张 / 标题栏「' + dom1.head + '」/ 不符 ' + bad1.length +
      (bad1.length ? ' 例=' + JSON.stringify(bad1[0]).slice(0, 220) : '') +
      (dom1.errAlerts ? '｜页面有红条：' + dom1.errAlerts : ''))
    await snap(page, bag, mark, { file: '01-推荐流六卡与接口理由逐条一致.png', url: '/feed', scrollTo: '.recommend',
      desc: 'U3「为你推荐」六张卡：卡片沿用广场 PostCard，多出来的只有 #reason 插槽那一行', minText: 900 },
      rows1.length === 6 && dom1.n === 6 && bad1.length === 0,
      '通道=' + rows1.map((x) => x.channel).join(',') + '；首屏角标「' + dom1.secHint + '」')
    st.uiHead = dom1.head
    st.uiSecHint = dom1.secHint

    // ---- 02 连点两次「换一批」：三批 18 条 id 两两不重复（D6 的界面版）----
    const w2 = watchUi(page, '/api/feed/recommend')
    const c2 = await clickUi(page, '.recommend .btn-rec-swap', 'D2 第一次「换一批」')
    const rows2 = (await jsonRows(w2)).rows
    await page.waitForTimeout(500)
    const dom2 = await readRecDom(page)
    const w3 = watchUi(page, '/api/feed/recommend')
    const c3 = await clickUi(page, '.recommend .btn-rec-swap', 'D2 第二次「换一批」')
    const rows3 = (await jsonRows(w3)).rows
    await page.waitForTimeout(500)
    const dom3 = await readRecDom(page)
    const batches = rows1.concat(rows2).concat(rows3)
    const uniqD = new Set(batches.map((x) => x.id))
    // 【D2 的身份键从「标题」换成「帖子 id」，理由写在这儿】run1 实测三批 distinct=18 全对、
    // DOM 与接口同序，却被判红：屏上数到 1 组「重复标题」。种子数据里 264 / 923 / 1075 / 1104
    // 四条帖子的标题一模一样，而 D6 承诺的是「同一批不重复的帖子」——身份键只能是帖子 id，不是它的显示文字。
    // 配套改动：PostCard 根节点加 data-post-id（DOM 上多一个属性，样式/文字/交互一条没动）。
    // 标题那个读数不删，继续打印出来，只是不再参与判据，读者能同时看到两个口径。
    const seenIds = rows1.concat(rows2).map((x) => String(x.id))
    const seenTitles = rows1.concat(rows2).map((x) => x.title)
    const domIds = dom3.ids
    const domRepeat = domIds.filter((t) => seenIds.indexOf(t) >= 0)
    const domTitleRepeat = dom3.titles.filter((t) => seenTitles.indexOf(t) >= 0).length
    const domMatchApi = dom3.idsComplete && domIds.join('|') === rows3.map((x) => String(x.id)).join('|')
    check('D2', 'D6 界面版：连点两次「换一批」＝三批 ' + batches.length + ' 条 id 两两不重复，屏上这批与已见过的零交集',
      c2 && c3 && batches.length === 18 && uniqD.size === 18 && dom3.n === 6 && dom3.idsComplete &&
      domRepeat.length === 0 && domMatchApi,
      '批1=' + rows1.map((x) => x.id).join(',') + '｜批2=' + rows2.map((x) => x.id).join(',') +
      '｜批3=' + rows3.map((x) => x.id).join(',') + ' distinct=' + uniqD.size +
      '｜屏上重复项(按帖子id)=' + domRepeat.length + '｜DOM 与第三批同id同序=' + domMatchApi +
      '｜另按标题数到 ' + domTitleRepeat + ' 组重名（种子数据四条同名，不参与判据）')
    await snap(page, bag, mark, { file: '02-换一批两次三批十八条不重复.png', url: '/feed', scrollTo: '.recommend',
      desc: '连点两次「换一批」之后的第三批：18 条 id 两两不重复，页脚的到底说明同时可见', minText: 900 },
      batches.length === 18 && uniqD.size === 18 && dom3.idsComplete && domRepeat.length === 0,
      '三批 18 条 distinct=' + uniqD.size + '；页脚「' + dom3.foot + '」')
    st.uiBatches = { b1: rows1, b2: rows2, b3: rows3, dom3 }

    // ---- 03 「为什么推给我」浮层：三行都来自接口字段 ----
    let idx3 = -1
    for (let i = 0; i < rows3.length; i++) {
      if (rows3[i].channel && rows3[i].channel !== 'hot') { idx3 = i; break }
    }
    if (idx3 < 0) {
      check('D3', '「为什么推给我」浮层三行与接口字段一致', null, '第三批六条全是热读兜底，没有个性化行可点，SKIP')
    } else {
      const chosen = rows3[idx3]
      const rowLoc = page.locator('.recommend .rec-row').nth(idx3)
      const wBtn = await rowLoc.locator('.btn-rec-why').count()
      const clicked = wBtn ? await rowLoc.locator('.btn-rec-why').click({ timeout: 15000 }).then(() => true).catch(() => false) : false
      let why = null
      try {
        const h = await page.waitForFunction(() => {
          const pops = Array.prototype.slice.call(document.querySelectorAll('.why-pop')).filter((el) => {
            const cs = getComputedStyle(el)
            const r = el.getBoundingClientRect()
            return r.width > 20 && r.height > 20 && cs.display !== 'none' && cs.visibility !== 'hidden'
          })
          const el = pops.length ? pops[pops.length - 1] : null
          return el ? String(el.textContent).replace(/\s+/g, ' ').trim() : null
        }, undefined, { timeout: 10000 })
        why = await readWhyDom(page)
        if (!why) why = { n: 0, text: await h.jsonValue(), bg: '-', leak: false } // leak 含义见 D 线判据注释：深色残留
      } catch (e) { why = await readWhyDom(page) }
      const wantScore = fmtUiScore(chosen.score)
      const okLines = !!why && why.n >= 5
      const okCh = !!why && why.text.indexOf('（' + chosen.channel + '）') >= 0 && why.text.indexOf('未标注通道') < 0
      const okScore = !!why && why.text.indexOf('离线打分：' + wantScore) >= 0
      const okReason = !!why && why.text.indexOf('这条流为什么有它：' + expectUiReason(chosen, false)) >= 0
      check('D3', '「为什么推给我」浮层：' + (why ? why.n : 0) + ' 行读数全部来自接口三字段（通道 ' + chosen.channel +
        ' / 分数 ' + wantScore + ' / 理由原文）', clicked && okLines && okCh && okScore && okReason,
        '卡片#' + chosen.id + ' 通道=' + chosen.channel + ' 分数=' + chosen.score +
        '｜三行命中=' + [okCh, okScore, okReason].join(',') + '｜p 数=' + (why ? why.n : 0) +
        '｜文字=' + (why ? why.text.slice(0, 200) : '浮层没出现'))
      await snap(page, bag, mark, { file: '03-为什么推给我浮层三行.png', url: '/feed', scrollTo: '.recommend',
        desc: '点某一张卡的「为什么推给我」：召回通道 / 离线打分 / 这条流为什么有它，三行都是接口原样给的字段', minText: 900 },
        okLines && okCh && okScore && okReason, '目标卡 id=' + chosen.id + '，浮层文字量 ' + (why ? why.text.length : 0) + ' 字')
      st.whyText = why ? why.text : ''
      st.whyTarget = chosen
      st.whyIndex = idx3
    }

    // ---- 04 当场点「不感兴趣」：卡少了、toast 说了真数 ----
    const before4 = dom3.n
    const wDismiss = watchUi(page, '/api/feed/dislike')
    const targetIdx = typeof st.whyIndex === 'number' && st.whyIndex >= 0 ? st.whyIndex : 0
    await page.keyboard.press('Escape').catch(() => {})
    await page.waitForTimeout(250)
    let clicked4 = false
    const rowLoc4 = page.locator('.recommend .rec-row').nth(targetIdx)
    if (await rowLoc4.count()) {
      clicked4 = await rowLoc4.locator('.hide').click({ timeout: 15000 }).then(() => true)
        .catch((e) => { say('  -- D4「不感兴趣」没点成：' + String((e && e.message) || e).slice(0, 110)); return false })
    } else {
      say('  -- D4 找不到第 ' + (targetIdx + 1) + ' 张推荐卡（这一批只有 ' + dom3.n + ' 张）')
    }
    const r4 = await jsonRows(wDismiss)
    let gone4 = false
    try {
      await page.waitForFunction((want) => document.querySelectorAll('.recommend .rec-row').length === want,
        before4 - 1, { timeout: 15000 })
      gone4 = true
    } catch (e) { say('  -- 点了「不感兴趣」之后卡片数没变成 ' + (before4 - 1)) }
    const dom4 = await readRecDom(page)
    let toast4 = ''
    try {
      const h = await page.waitForFunction(() => {
        const el = document.querySelector('.el-message')
        if (!el) return null
        const t = String(el.innerText || el.textContent).replace(/\s+/g, ' ').trim()
        return t || null
      }, undefined, { timeout: 8000 })
      toast4 = await h.jsonValue()
    } catch (e) { toast4 = '' }
    const target4 = typeof st.whyIndex === 'number' ? rows3[st.whyIndex] : null
    // 残留判据同样按帖子 id 数（标题会撞种子数据的重名）；DOM 万一没带上 id 才退回标题口径，并在读数里说明
    const stillThere = !target4 ? null : (dom4.idsComplete ? dom4.ids.indexOf(String(target4.id)) >= 0
      : dom4.titles.indexOf(target4.title) >= 0)
    check('D4', 'D6 当场没：点「不感兴趣」卡片 ' + before4 + '→' + dom4.n + '，被点的那条从屏上消失，提示条把两个真实计数说出来',
      clicked4 && gone4 && dom4.n === before4 - 1 && stillThere === false &&
      toast4.indexOf('这条已从本批缓存里删掉') >= 0 && /删掉（\d+ 行）/.test(toast4),
      '点击=' + clicked4 + ' 张数=' + dom4.n + ' 残留=' + stillThere + '｜提示原文=' + JSON.stringify(toast4.slice(0, 160)) +
      '｜接口回执=' + JSON.stringify(r4.code === null ? '(没抓到响应)' : r4.code))
    await snap(page, bag, mark, { file: '04-不感兴趣当场消失并回执真实行数.png', url: '/feed', scrollTo: '.recommend',
      desc: '点掉一条之后：这一批只剩 5 张卡，绿条写明「本批缓存删掉 N 行 + 压掉 M 条下一屏候选」', minText: 900 },
      gone4 && dom4.n === before4 - 1 && stillThere === false && toast4.length > 0,
      target4 ? '被驳回的卡 id=' + target4.id + '（通道 ' + target4.channel + '）' : '没定位到被点的卡')
    st.uiDismiss = { target: target4, toast: toast4, before: before4, after: dom4.n }

    // ---- 04b 这一次界面驳回在行为表里正好一行（界面 → DB 打通）----
    if (target4 && target4.id) {
      const w = sqlRows('d4b-dislike', "SELECT weight, scene FROM user_action WHERE user_id=" + st.demoId +
        " AND action_type='dislike' AND target_type='post' AND target_id=" + target4.id +
        ' AND day_bucket=CURDATE() AND deleted=0;')
      check('D4b', 'DB：界面这一次驳回在 user_action 里正好一行（weight=-3.00 / scene=feed）',
        w === null ? null : (w.data.length === 1 && w.data[0][0] === '-3.00' && w.data[0][1] === 'feed'),
        w === null ? 'SQL 不可用，SKIP' : '帖子=' + target4.id + ' 行=' + JSON.stringify(w.data))
    } else {
      check('D4b', 'DB：界面这一次驳回在 user_action 里正好一行', null, 'D4 没定位到被驳回的帖子，SKIP')
    }

    // ---- 05 详情页「看了又看」：三层召回的第 1 层真的出现在屏上 ----
    const src = st.simItemcf
    if (!src || !src.id) {
      check('D5', '详情页「看了又看」出现 ItemCF 邻居（B 线选的源帖）', null, 'B 线没找到能出邻居的源帖，SKIP')
      check('D5b', '相似位不混通道、不显示源帖自己', null, '依赖 D5，SKIP')
    } else {
      const w5 = watchUi(page, '/similar')
      await page.goto(WEB + '/post/' + src.id, { waitUntil: 'domcontentloaded', timeout: 45000 })
      const rr5 = await jsonRows(w5)
      const api5 = rr5.rows
      let dom5 = await readSimDom(page)
      if (!dom5.n) { await page.waitForTimeout(1500); dom5 = await readSimDom(page) }
      const bad5 = api5.map((x, i) => ({ api: x, t: dom5.titles[i], r: dom5.reasons[i], c: dom5.channels[i] }))
        .filter((x, i) => !x.t || x.t !== x.api.title || x.r !== expectUiReason(x.api, true) ||
          x.c !== (SIM_CH_LABELS[x.api.channel] || '未标注通道'))
      const itemcfOnScreen = dom5.channels.filter((c) => c === '共读相似').length
      const apiItemcf = api5.filter((x) => x.channel === 'itemcf').length
      check('D5', '详情页「看了又看」：' + dom5.n + ' 张卡与接口逐条同序同文字，标题「' + dom5.head + '」',
        dom5.has && dom5.n === api5.length && dom5.n > 0 && dom5.n <= 6 && bad5.length === 0 && dom5.head === '看了又看',
        '源帖=' + src.id + ' 接口 ' + api5.length + ' 条 / DOM ' + dom5.n + ' 张 / 不符 ' + bad5.length +
        (bad5.length ? ' 例=' + JSON.stringify(bad5[0]).slice(0, 200) : '') + '｜通道串=' + dom5.channels.join(','))
      // 「源帖自己不在列表里」这条 run1 是空转的：它比的是 src.rows[0].title，
      // 而相似位接口回的是 {post:{...}} 包一层，那个字段是 undefined，于是 cleanText 出空串、indexOf 恒为 -1，
      // 一条永远绿的判据不算判据。改成按 DOM 上的 data-post-id 直接比源帖 id。
      const srcOnScreen = dom5.idsComplete ? dom5.ids.indexOf(String(src.id)) >= 0 : null
      check('D5b', '屏上的「共读相似」条数等于接口的 itemcf 条数，且源帖自己不在列表里',
        itemcfOnScreen === apiItemcf && apiItemcf >= 1 && dom5.idsComplete && srcOnScreen === false &&
        dom5.ids.filter((t) => !!t).length === dom5.n,
        'itemcf DOM=' + itemcfOnScreen + ' 接口=' + apiItemcf + '｜源帖 ' + src.id + ' 出现在屏上=' + srcOnScreen +
        '｜DOM 通道分布=' + dom5.channels.join(','))
      await snap(page, bag, mark, { file: '05-详情页看了又看ItemCF.png', url: '/post/' + src.id, scrollTo: '.similar',
        desc: 'U4 详情页「看了又看」：ItemCF 邻居在前、同话题与热读补位在后，每条右侧标出自己所属通道', minText: 800 },
        dom5.has && bad5.length === 0 && itemcfOnScreen >= 1,
        '源帖 #' + src.id + ' 相似位 ' + dom5.n + ' 条（' + dom5.channels.join(',') + '）')
      st.uiSim = { srcId: src.id, api: api5, dom: dom5 }
    }

    // ---- 06 冷帖兜底：没有邻居行的帖子也不许空窗 ----
    if (!st.cold || !st.cold.id) {
      check('D6', '冷帖（无 ItemCF 邻居）详情页兜底两层能补出内容', null, 'B 线没找到冷帖夹具（SQL 不可用或全部有邻居），SKIP')
    } else {
      const w6 = watchUi(page, '/similar')
      await page.goto(WEB + '/post/' + st.cold.id, { waitUntil: 'domcontentloaded', timeout: 45000 })
      const rr6 = await jsonRows(w6)
      let dom6 = await readSimDom(page)
      if (!dom6.n) { await page.waitForTimeout(1500); dom6 = await readSimDom(page) }
      const leaked = dom6.channels.filter((c) => c === '共读相似').length
      check('D6', '冷帖 #' + st.cold.id + ' 不空窗：兜底补出 ' + dom6.n + ' 条，且一条「共读相似」都不出现（它没有邻居行）',
        dom6.n > 0 && rr6.rows.length > 0 && leaked === 0 &&
        dom6.channels.every((c) => c === '同话题' || c === '热读补位'),
        '接口 ' + rr6.rows.length + ' 条 / DOM ' + dom6.n + ' 张｜通道串=' + dom6.channels.join(',') +
        '｜空窗文案=' + JSON.stringify(dom6.n === 0 ? dom6.emptyLine : ''))
      await snap(page, bag, mark, { file: '06-冷帖相似位兜底不空窗.png', url: '/post/' + st.cold.id, scrollTo: '.similar',
        desc: '没有任何 ItemCF 邻居的新帖：相似位靠同话题 + 质量分榜补满，不出现空推荐位', minText: 800 },
        dom6.n > 0 && leaked === 0, '冷帖 #' + st.cold.id + ' 补出 ' + dom6.n + ' 条')
      st.uiCold = { id: st.cold.id, n: dom6.n, channels: dom6.channels }
    }
  } catch (e) {
    say('  !! D 线主页面抛异常：' + String((e && e.stack) || e).slice(0, 400))
    check('D7', '游客打 /feed 被路由守卫弹回 /login', null, '主页面线已经崩了，SKIP')
  }
  // ---- 07 游客闸门：没有 token 就什么都看不到，且不是一张白屏 ----
  try {
    const guest = await newUiPage(bag, null)
    ctxGuest = guest.context
    await guest.page.goto(WEB + '/feed', { waitUntil: 'domcontentloaded', timeout: 45000 })
    await guest.page.waitForTimeout(1200)
    const urlNow = guest.page.url()
    const g = await guest.page.evaluate(() => {
      const clean = (v) => String(v === null || v === undefined ? '' : v).replace(/\s+/g, ' ').trim()
      return {
        hasPwd: !!document.querySelector('input[type=password]'),
        btn: Array.prototype.slice.call(document.querySelectorAll('button')).map((b) => clean(b.textContent)).join(','),
        text: clean((document.getElementById('app') || document.body).innerText).length,
        recSection: !!document.querySelector('.recommend'),
        hint: clean(Array.prototype.slice.call(document.querySelectorAll('.hint')).map((x) => x.textContent).join(' ')).slice(0, 160)
      }
    })
    st.guestUrl = urlNow.replace(WEB, '')
    st.guestHasRec = g.recSection
    st.guestText = g.text
    check('D7', '游客打 /feed：被弹回 /login 并带上 redirect，页面上没有推荐流那一节（不退化成热读榜）',
      urlNow.indexOf('/login') >= 0 && urlNow.indexOf('redirect') >= 0 && g.hasPwd && !g.recSection,
      '落地 URL=' + urlNow.replace(WEB, '') + '｜口令框=' + g.hasPwd + '｜推荐节=' + g.recSection + '｜文字=' + g.text + ' 字')
    await snap(guest.page, bag, mark, { file: '07-游客被弹回登录页.png', url: '/feed → /login?redirect=/feed',
      // 文字量这道闸是防「白屏假绿」的，不是防「这页本来就没几行字」的：登录页整页 innerText 实测 136 字
      // （口令框 + 注册/登录两个按钮 + 一句提示），沿用推荐流那 900 字门槛会把它读成红图。
      // 门槛按这一页的真实字数压到 100，读数照常打印，谁来看都能复核这个数。
      desc: '未登录直连 /feed：路由守卫弹回登录页并带 redirect，登录后原路返回（游客态没有推荐流）', minText: 100 },
      urlNow.indexOf('/login') >= 0 && g.hasPwd && !g.recSection, '按钮=' + g.btn.slice(0, 90))
  } catch (e) {
    check('D7', '游客打 /feed 被弹回 /login', false, '游客上下文起不来：' + String((e && e.message) || e).slice(0, 160))
  }
  consoleErrCount += bag.console.length + bag.pageerror.length
  const shotPass = shots.length && shots.every((x) => x.pass)
  check('D8', '本轮 ' + shots.length + ' 张截图四道闸全过（判据 / 文字量 / 深色残留 / 新增红字）',
    shots.length > 0 && shotPass === true,
    '通过 ' + shots.filter((x) => x.pass).length + '/' + shots.length + '｜累计深色残留 ' + leakCount + ' 处')
  check('D9', '浏览器控制台零红字、零页面异常、零 4xx/5xx 资源（HTTP 429 也算）',
    bag.console.length === 0 && bag.pageerror.length === 0 && bag.http.length === 0,
    'console=' + bag.console.length + ' pageerror=' + bag.pageerror.length + ' http=' + bag.http.length +
    (bag.http.length || bag.console.length ? ' 例=' + JSON.stringify(bag.http.concat(bag.console).slice(0, 3)).slice(0, 260) : ''))
  st.bag = bag
  try { await uiBrowser.close() } catch (e) { say('  -- 关浏览器时报错：' + String((e && e.message) || e).slice(0, 120)) }
}


// ===========================================================================
// 收尾 —— 四线串跑、判据补齐、产物落盘
//
// 【为什么驱动长在文件末尾而不是让调用方自己拼】
// 手册的收口口径是「一支脚本 = 一份证据」：清单.md、shot-manifest.json、
// console-evidence.log 必须由跑判据的那支脚本自己数出来。人抄一遍就会抄成上一轮的数
// （踩坑日志里「把脑子里的表当成现量」已经记过十二次）。
//
// 【一条判据都不许从清单里「蒸发」】
// B 线找不到能出邻居的源帖会提前 return，D 线在起浏览器那一步失败会整段跳过 —— 这两种情况下
// 如果后面的编号直接不出现，读者看到的清单就成了「本轮 40 条全绿」这种假话。
// ensureIds 把没执行到的编号补成 SKIP 并写明为什么没跑；SKIP 不进 PASS 也不进 FAIL。
// ===========================================================================
const NL = String.fromCharCode(10)
const IDS = {
  A: ['A1', 'A2', 'A3', 'A4', 'A5', 'A6', 'A7', 'A8', 'A9', 'A10', 'A11', 'A12', 'A13', 'A14', 'A15', 'A16', 'A17', 'A18', 'A19', 'A20a', 'A20b', 'A20c', 'A21'],
  B: ['B1', 'B2', 'B3', 'B4', 'B5', 'B6', 'B7', 'B8', 'B9', 'B10', 'B11', 'B12', 'B13'],
  C: ['C1', 'C2', 'C3', 'C4', 'C5', 'C6', 'C7', 'C8', 'C9', 'C10', 'C11', 'C12', 'C13', 'C14', 'C15'],
  D: ['D1', 'D2', 'D3', 'D4', 'D4b', 'D5', 'D5b', 'D6', 'D7', 'D8', 'D9']
}

function ensureIds (group, why) {
  const got = {}
  checks.forEach((c) => { got[c.id] = true })
  const missing = IDS[group].filter((id) => !got[id])
  missing.forEach((id) => check(id, '（这一条没被执行：它前面有一处中断或前置不成立）', null, why))
  if (missing.length) say('  -- ' + group + ' 线补记 ' + missing.length + ' 条 SKIP：' + missing.join(','))
}

async function phases () {
  say('==== Gate7 取证 · RUN=' + RUN + ' · 本地 ' + new Date().toLocaleString('zh-CN') + ' ====')
  say('  后端=' + API + ' 前端=' + WEB + ' 出图目录=' + OUT_DIR)
  say('  SQL 执行器=' + (sqlProbe() ? '就绪（DB 侧判据现场只读现查）' : '缺失（DB 侧判据整批判 SKIP，不当通过）'))
  say('  界面线=' + (process.env.NOUI === '1' ? 'NOUI=1，D 线整条记 SKIP' : '开真 Chrome（headless）'))
  await preflight()
  const guards = [['A', lineA], ['B', lineB], ['C', lineC]]
  if (process.env.NOUI !== '1') guards.push(['D', lineD])
  for (let i = 0; i < guards.length; i++) {
    const g = guards[i]
    try { await g[1]() } catch (e) {
      say('  !! ' + g[0] + ' 线抛异常（这一条不是判据 FAIL，但它下面所有判据都会被补成 SKIP）：' +
        String((e && e.stack) || e).slice(0, 500))
    }
    // C 组的补齐压到最后：C15 要等 D 线把那句「负反馈：用户」点出来才有东西可查
    if (g[0] === 'C') continue
    ensureIds(g[0], g[0] + ' 线没走到这一条：前一处抛异常，或该条依赖的前置不成立')
  }
  if (process.env.NOUI === '1') {
    check('C15', '后端日志里能查到「通道占比」与「负反馈：用户」两行', null,
      'NOUI=1：D 线整条没跑，界面点击写下的那句负反馈日志不会出现，本条不当通过')
  } else {
    try { await lineC15() } catch (e) {
      say('  !! C15 复核抛异常（这一条不是判据 FAIL，下面的补齐会把 C15 记成 SKIP）：' +
        String((e && e.stack) || e).slice(0, 300))
    }
  }
  ensureIds('C', 'C 线没走到这一条：前一处抛异常，或该条依赖的前置不成立')
  if (process.env.NOUI === '1') {
    IDS.D.forEach((id) => check(id, '真浏览器界面层取证（D 线整条）', null,
      'NOUI=1：本轮只跑接口线，界面层一条未跑，不当通过'))
  }
}

function finish () {
  const fails = checks.filter((c) => c.state === 'FAIL')
  say('')
  say('==== 汇总 · 判据 ' + checks.length + ' 条：PASS=' + passN + ' FAIL=' + failN + ' SKIP=' + skipN + ' ====')
  say('==== 截图 ' + shots.length + ' 张（四道闸过 ' + shots.filter((x) => x.pass).length + ' 张）· 深色残留 ' +
    leakCount + ' 处 · 控制台红字与页面异常 ' + consoleErrCount + ' 条 ====')
  say('==== 节流复盘：主动等下一自然分钟 ' + throttle.paced + ' 次 · 撞 429 重试 ' + throttle.retried +
    ' 次 · 放弃 ' + throttle.gaveUp + ' 次 ====')
  fails.forEach((c) => say('  FAIL [' + c.id + '] ' + c.name + ' → ' + c.detail))
  process.exit(failN > 0 ? 1 : 0)
}

async function runAll () {
  fs.mkdirSync(OUT_DIR, { recursive: true })
  try {
    await phases()
  } catch (e) {
    say('  !! 驱动在四线之前就停了：' + String((e && e.message) || e).slice(0, 300))
    check('P2', '开工前置（preflight 抛异常，四线一条未执行）', false, String((e && e.message) || e).slice(0, 220))
    ;['A', 'B', 'C', 'D'].forEach((g) => ensureIds(g, '前置检查没过，本轮整条线未执行'))
  }
  try {
    writeArtifacts()
  } catch (e) {
    say('  !! 产物落盘失败：' + String((e && e.stack) || e).slice(0, 400))
    check('Z1', '产物落盘（console-evidence.log / shot-manifest.json / 清单.md / d7-log-excerpt.log）', false,
      String((e && e.message) || e).slice(0, 220))
  }
  finish()
}

// ---------------------------------------------------------------------------
// 产物四件：console-evidence.log（判据逐行原文）/ d7-log-excerpt.log（后端日志原文）
//            shot-manifest.json（结构化读数）/ 清单.md（给人读的那一份）
// 四件都从同一批内存变量写出来，所以「清单里的数」与「日志里的数」不可能对不上。
// ---------------------------------------------------------------------------
const mdCell = (v) => String(v === undefined || v === null ? '' : v).split('|').join('∣').split(NL).join(' ')

function writeArtifacts () {
  const now = new Date()
  const shotPass = shots.filter((x) => x.pass).length
  const bag = st.bag || { console: [], pageerror: [], http: [] }
  fs.writeFileSync(path.join(OUT_DIR, 'console-evidence.log'), log.join(NL) + NL, 'utf8')

  const excerpt = st.logExcerpt || []
  const exHead = [
    '# d7-log-excerpt —— 本轮「手动触发重算」之后后端日志里与推荐有关的新增行（C15 判据的原文）',
    '# 源文件 ' + BACKEND_LOG + ' · 重算前偏移 ' + st.logOffset + ' 字节 · Windows 控制台按 GBK 落盘，脚本内解码',
    '# 抓取时间 ' + now.toISOString(),
    '# 只留 5 个关键词命中的行（推荐重算完成 / 通道占比 / 负反馈：用户 / 推荐流退热度兜底 / 相似位：拦截），一个字没改，也没挑过。'
  ]
  fs.writeFileSync(path.join(OUT_DIR, 'd7-log-excerpt.log'), exHead.concat(
    excerpt.length ? excerpt : ['（本轮一行都没抓到：日志文件不可读，或这一轮的重算没打出这几句 —— 那 C15 会是 SKIP 而不是 PASS）']
  ).join(NL) + NL, 'utf8')

  const sm = st.summary || {}
  const share = st.channelShare || {}
  const shareKeys = Object.keys(share)
  fs.writeFileSync(path.join(OUT_DIR, 'shot-manifest.json'), JSON.stringify({
    at: now.toISOString(), run: RUN, api: API, web: WEB, out: OUT_DIR,
    chrome: st.browserVersion || 'not-launched', viewport: DESKTOP,
    accounts: { feedUser: { username: DEMO_USER, id: st.demoId === undefined ? null : st.demoId, recMode: st.demoId === undefined ? null : (st.demoId % 10 < 7 ? 'cf' : 'hot') }, offlineAdmin: ADMIN_USER },
    sqlAvailable: sqlAvailable === true,
    counts: {
      checks: checks.length, pass: passN, fail: failN, skip: skipN,
      shots: shots.length, shotsPass: shotPass, leaks: leakCount,
      browserConsoleErrors: consoleErrCount, httpErrors: (bag.http || []).length
    },
    throttle: throttle,
    offline: {
      statusBefore: st.statusBefore || null, rebuild: st.rebuild ? { code: st.rebuild.code, msg: st.rebuild.msg, wallMs: st.rebuild.wall } : null,
      calcBefore: st.calcBefore || null, calcAfter: st.calcAfter || null, summary: sm, channelShare: share, logLines: excerpt.length
    },
    feed: {
      ids18: st.ids18 || null, dislikeTarget: st.target ? { id: st.target.id, neighborsInCache: st.target.overlap } : null,
      batches: st.uiBatches ? { b1: st.uiBatches.b1, b2: st.uiBatches.b2, b3: st.uiBatches.b3, dom3: st.uiBatches.dom3 } : null,
      head: st.uiHead || null, secHint: st.uiSecHint || null,
      dismiss: st.uiDismiss || null, whyPopover: st.whyText ? { target: st.whyTarget || null, text: st.whyText } : null
    },
    similar: {
      itemcfSrc: st.simItemcf ? { id: st.simItemcf.id, rows: uiRows(st.simItemcf.rows) } : null,
      cold: st.cold ? { id: st.cold.id, ids: st.coldIds, rows: uiRows(st.cold.rows) } : null,
      onScreen: st.uiSim || null, onScreenCold: st.uiCold || null
    },
    browserErrorBags: bag,
    checks: checks,
    shots: shots
  }, null, 2) + NL, 'utf8')

  const md = []
  md.push('# 阶段 7（双通道离线推荐 + 情绪加权 + 看了又看）收口取证清单')
  md.push('')
  md.push('- 取证时间：' + now.toISOString() + '（UTC）／本地 ' + now.toLocaleString('zh-CN') + ' · 运行号 ' + RUN)
  md.push('- 脚本：`frontend/probe/recgate7.mjs` 四线合一 —— A 线推荐流契约与负反馈三层记账、B 线相似位三层召回、C 线离线作业与健康度、D 线真浏览器界面层')
  md.push('- 服务：后端 ' + API + '（直连，不走 Vite 代理）· 前端 ' + WEB + '（Vite dev）· 视口 ' + DESKTOP.width + '×' + DESKTOP.height + '（手册 §12 论文截图统一尺寸）')
  md.push('- 浏览器：' + (st.browserVersion ? 'Chrome ' + st.browserVersion + '（headless，用系统 Chrome，不下载 playwright 自带浏览器）' : '本轮未启动（NOUI=1 或前置失败，D 线整条 SKIP）'))
  md.push('- 账号：' + DEMO_USER + '(#' + (st.demoId === undefined ? '-' : st.demoId) + '，推荐流与相似位) · ' + ADMIN_USER + '（离线重算）· 口令不落任何产物；DB 侧判据走仓库外 cnf 的只读 SELECT')
  md.push('- 判据 **共 ' + checks.length + ' 条：PASS ' + passN + ' ／ FAIL ' + failN + ' ／ SKIP ' + skipN + '**（SKIP 不计入通过，逐条写明为什么没跑）')
  md.push('- 截图 **共 ' + shots.length + ' 张，四道闸通过 ' + shotPass + ' 张**（判据 / 文字量 / 深色残留 / 新增红字）；深色残留 ' + leakCount + ' 处；控制台红字与页面异常 ' + consoleErrCount + ' 条；4xx/5xx 资源 ' + (bag.http || []).length + ' 条')
  md.push('- 节流复盘：主动等下一自然分钟 ' + throttle.paced + ' 次 · 撞 10010/429 重试 ' + throttle.retried + ' 次 · 放弃 ' + throttle.gaveUp + ' 次（普通接口 60 次/分/用户是产品行为，判据尊重它，不调高服务端配置）')
  md.push('')
  md.push('## 一、逐条判据（编号对齐手册 §10.5 D6/D7 与 §10.6 T7.16）')
  md.push('')
  md.push('| 编号 | 判据 | 结论 | 现场读数 |')
  md.push('| --- | --- | --- | --- |')
  checks.forEach((c) => md.push('| ' + c.id + ' | ' + mdCell(c.name) + ' | ' + (c.state === 'FAIL' ? '**FAIL**' : c.state) + ' | ' + mdCell(c.detail) + ' |'))
  md.push('')
  md.push('## 二、逐张截图')
  md.push('')
  md.push('| 文件 | 路由 | 文字量 | 主背景 | 深色残留 | 新增红字 | 判据 | 结论 | 备注 |')
  md.push('| --- | --- | --- | --- | --- | --- | --- | --- | --- |')
  shots.forEach((s) => md.push('| `' + s.file + '` | `' + mdCell(s.url) + '` | ' + s.chars + ' | ' + mdCell(s.bg) + ' | ' + s.leaks + ' | ' + s.newErrors + ' | ' + mdCell(s.judgement) + ' | ' + (s.pass ? 'PASS' : '**FAIL**') + ' | ' + mdCell(s.note) + ' |'))
  md.push('')
  md.push('## 三、每张照片要说明什么')
  md.push('')
  shots.forEach((s) => md.push('- **' + s.file + '**：' + s.desc))
  md.push('')
  md.push('## 四、这一轮数出来的关键读数（每条都能用同一支脚本重跑出来）')
  md.push('')
  md.push('- **离线作业摘要**：质量分 ' + sm.qualityRows + ' 行 · 话题热度 ' + sm.topicRows + ' 行 · item 相似度 ' + sm.similarityRows + ' 行 · 有邻居的帖子 ' + sm.itemsWithNeighbors + ' 个 · 覆盖用户 ' + sm.users + ' 人 · 推荐结果 ' + sm.resultRows + ' 行 · 作业自身 ' + sm.calcMs + 'ms · 探针墙钟 ' + (st.rebuild ? st.rebuild.wall : '-') + 'ms（C5/C6）')
  md.push('- **`MAX(calc_at)`**：' + st.calcBefore + ' → ' + st.calcAfter + '；按这个时间戳数出来的行数 = 摘要 `resultRows`（C10/C11），接口回的不是自夸数')
  md.push('- **通道占比**：' + (shareKeys.length ? shareKeys.map((k) => k + '=' + share[k]).join('、') : '本轮没读到') + '（' + shareKeys.length + '/6 路出现；六路里没出现的那些不是 bug，是这份数据的真实分布，C9 只做诚实记录）')
  const bt = st.uiBatches
  md.push('- **换一批三次共 18 条 id**：' + (bt ? '批1 ' + bt.b1.map((x) => x.id).join(',') + ' ／ 批2 ' + bt.b2.map((x) => x.id).join(',') + ' ／ 批3 ' + bt.b3.map((x) => x.id).join(',') + '（distinct=' + new Set(bt.b1.concat(bt.b2).concat(bt.b3).map((x) => x.id)).size + '）' : 'D 线未跑'))
  md.push('- **「为什么推给我」浮层原文**：' + (st.whyText ? '「' + st.whyText + '」' : '本轮没抓到（第三批全是热读兜底或 D 线未跑）'))
  md.push('- **不感兴趣**：卡片 ' + (st.uiDismiss ? st.uiDismiss.before : '-') + ' → ' + (st.uiDismiss ? st.uiDismiss.after : '-') + ' 张，回执原文「' + (st.uiDismiss ? st.uiDismiss.toast : '-') + '」')
  md.push('- **相似位源帖 #' + (st.simItemcf ? st.simItemcf.id : '-') + '**：接口通道 ' + (st.simItemcf ? channelsOf(st.simItemcf.rows).join(',') : '-') + '；屏上通道 ' + (st.uiSim ? st.uiSim.dom.channels.join(',') : '-'))
  md.push('- **冷帖 #' + (st.cold ? st.cold.id : '-') + '**：兜底补出 ' + (st.cold ? st.cold.rows.length : 0) + ' 条（接口通道 ' + (st.cold ? channelsOf(st.cold.rows).join(',') : '-') + '，屏上 ' + (st.uiCold ? st.uiCold.channels.join(',') : '-') + '），一条 itemcf 都不该出现')
  md.push('- **游客闸门**：落地 ' + (st.guestUrl ? mdCell(st.guestUrl) : 'D7 未跑') + '；推荐节存在=' + (st.guestHasRec === undefined ? '-' : st.guestHasRec))
  md.push('')
  md.push('## 五、本轮没做到的（写在这里，不藏进汇总数字里）')
  md.push('')
  md.push('- **`rec_run_log` 这张「作业运行日志」表在库里不存在**（`sql/*.sql` 与后端实体全仓 grep 0 命中）。手册 §10.5 / FR5.8 要求重算留一行历史；现在能查到的只有 `recommend_result.calc_at`（最后一个批次的时间戳）与 `GET /api/admin/rec/status` 的现算读数，**没有历次重算的成功/失败/耗时台账**。这条结转阶段 8，本轮不用 status 接口冒充它有。**')
  md.push('- **「立即重算」只有接口、没有管理端按钮**：后端 `AdminRecController` 的 `POST /api/admin/rec/rebuild` 已实装并同步返回摘要，但 `admin/src/router/index.js` 的七条业务路由（dashboard/tickets/audit/content/users/configs/logs）里没有推荐运维页，`admin/src/api/admin.js` 也没有它的封装。手册 §10.5 那句「点一下立刻重算 + 进度写 Redis」只落地了前半句的一半：**触发是同步 HTTP，没有异步任务，也没有进度条**。本轮证据因此走接口线（C 线）而不是管理端截图。')
  md.push('- **负反馈权重有两处口径打架**：跑分的是 `track/UserActionCatalog.java:99` 的 `new BigDecimal("-3.00")`，而 `sql/05_recommend.sql:22` 的行为表注释与 `sql/09_seed.sql:36` 的 `rec.weight_profile` 写的是 `-5`。`rec.weight_profile` 这一行**全仓 Java 0 处读取**（打分器不读配置表，读常量），所以 D4b 判据钉的是 `-3.00`。要么阶段 8 把配置接进 `ImplicitScorer`，要么删掉那行死配置 —— 本轮只如实记录，不改数、不迁就判据。')
  md.push('- **A/B 分流（T7.8）只有代码、没有效果数字**：`RecMode.forUser(userId)` 按尾号 0–6 走 CF、7–9 走纯热度，`recommend_result.mode` 是 ENUM(cf/hot/ab)，本轮实测 ' + DEMO_USER + ' 的 id 尾号 ' + (st.demoId === undefined ? '-' : st.demoId % 10) + ' → 走 ' + (st.demoId === undefined ? '-' : (st.demoId % 10 < 7 ? 'cf' : 'hot')) + ' 组。六组对照、消融、超参曲线、PR 曲线（T7.11–T7.14）属于论文材料侧实验，按用户本轮指示**不在代码交付范围内，四条判据一条不签**。')
  md.push('- **推荐流没有「前端独立分页缓存」**：`GET /api/feed/recommend` 的换一批是退回第一屏重取（页面页脚那句话就是这么写的），所以 D2 的「三批不重复」量的是**同一批次内的 18 条不重复**，跨批次去重靠 `recommend_result` 的 `is_exposed`/`deleted`。这一句写明白，是为了不让读者把 D2 读成「无限刷不重复」。')
  md.push('')
  fs.writeFileSync(path.join(OUT_DIR, '清单.md'), md.join(NL) + NL, 'utf8')
  say('')
  say('  [产物] console-evidence.log(' + log.length + ' 行) · d7-log-excerpt.log(' + excerpt.length + ' 行) · shot-manifest.json · 清单.md(' + md.length + ' 行) · 截图 ' + shots.length + ' 张 → ' + OUT_DIR)
}

runAll()
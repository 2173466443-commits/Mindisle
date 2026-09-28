// 阶段 4（T4.16 / T4.17 / T4.19 / T4.20 相关的 Gate4 界面侧）真浏览器取证 —— 第五条取证线
//
// 为什么还要再写一个文件（2026-09-28）：阶段 4 之前的四条线各证了一角，但没有一条覆盖
// 「用户屏幕上真的看见了吗」这一整排判据：
//   · aichat.mjs 证的是**对话链路本身**（逐字流、停止生成、赞踩落库、改名、危机卡片），
//     但它一张图不写进 docs/gate，也不验 Markdown —— 助手那句如果被当成纯文本贴上去，
//     编号列表会挤成一坨，接口全绿、单测全绿、冒烟全绿，屏幕上仍然是坏的（CSS 第 965 行
//     .md{white-space:normal} 就是为了压住 .txt 的 pre-wrap，这条线此前没人量过）；
//   · docs/smoke.mjs 第 23 步证的是 /read 端点的服务端阈值，它不知道前端到底有没有
//     在满 3 秒时把那个请求发出去，也看不见界面上那行「本页已读 N 秒」；
//   · domprobe.mjs 是 jsdom，样式与布局一律不存在。
// 所以本文件只做这四件别处证不到的事：Markdown 渲染、停留计时的界面读数、
// 情绪档案的图真的画出来了、隐私中心三步动作真的能在浏览器里走完。每张图都配机器断言。
//
// 前置：8080（后端）与 5173（Vite dev）都在跑，且 Vite 服务的是磁盘上这份源码。
// 跑法：cd frontend && node probe/stage4gate.mjs
//      追加危机取证：CRISIS=1 node probe/stage4gate.mjs
//      （⚠ 危机段会写 alert_ticket 与站内信，删会话删不掉这两张表的行，跑前想清楚）
// 产物：docs/gate/阶段4/*.png + console-evidence.log + shot-manifest.json
// 退出码：任何一条 FAIL 即 1。
//
// 取证顺序纪律：本脚本会写库（夹具帖 / AI 会话 / 停留记录 / 导出任务），所以它必须排在
// domprobe 与 shootgate 之后跑，否则阶段 3 那批图上的 postTotal 会漂。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(HERE, '..', '..')
const OUT = path.join(ROOT, 'docs', 'gate', '阶段4')
const PW_DIR = 'E:/codex workspace/_cache/node_modules/playwright-core'
// 导出的包裹不进仓库（里面是个人信息），落到工作区约定的缓存目录，只在证据里留文件名与字节数。
const DL_DIR = process.env.DL_DIR || 'E:/codex workspace/_cache/009_mindisle/gate4-download'
const CHROME = process.env.CHROME ||
  'C:/Users/Drbrain/AppData/Local/Google/Chrome/Application/chrome.exe'
const BASE = process.env.BASE || 'http://127.0.0.1:5173'
const APIBASE = process.env.APIBASE || 'http://127.0.0.1:8080'
const DESKTOP = { width: 1600, height: 1000 }
const DO_CRISIS = process.env.CRISIS === '1'
const CAPTCHA_ID = '00000000000000000000000000000000'
const PWD = 'Test1234'
const READER = 'demo01'
const AUTHOR = 'demo03'

fs.mkdirSync(OUT, { recursive: true })

let nPass = 0
let nFail = 0
const failures = []
const manifest = []
const probeConvs = []
const logLines = []
function say (s) { logLines.push(s); console.log(s) }
function check (ok, name, read) {
  if (ok) { nPass++; say('PASS ' + name + (read ? '   <- ' + read : '')) }
  else { nFail++; failures.push(name); say('FAIL ' + name + '   <- ' + (read || '')) }
  return ok
}

async function api (method, p, token, body) {
  const headers = {}
  if (token) headers.Authorization = 'Bearer ' + token
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  let r = null
  let j = null
  // 这个接口群有 60 次/分钟的窗口（RateLimitInterceptor 跑在 Controller 之前），
  // 而本脚本要在会话列表和消息表上轮询几十次。撞上限流不是产品故障，是取证自己造的流量 ——
  // 退避重试而不是调高阈值，也不把 429 当成一次有效读数。
  for (let attempt = 0; attempt < 2; attempt++) {
    r = await fetch(APIBASE + p, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body)
    })
    if (r.status !== 429) break
    await new Promise((res) => setTimeout(res, 3000))
  }
  try { j = await r.json() } catch (e) { j = null }
  return { status: r.status, code: j && j.code, data: j && j.data, msg: j && j.msg }
}

async function login (acct) {
  const r = await api('POST', '/api/auth/login', null, {
    username: acct, password: PWD, captchaId: CAPTCHA_ID, captchaCode: 'ZZZZ'
  })
  if (!r.data || !r.data.accessToken) {
    throw new Error('登录 ' + acct + ' 没拿到 token（HTTP ' + r.status + ' code=' + r.code + '）')
  }
  return {
    token: r.data.accessToken,
    uid: Number(r.data.user.id),
    nick: r.data.user.nickname || acct
  }
}

// ---------- 0.5 三个被全文反复调用的底座（少了任何一个，脚本在第 199 行就 ReferenceError） ----------
// newPage 只做四件事：视口/语言/时区固定（截图上的时间才可信）、token 进 localStorage（路由守卫读它）、
// 下载允许（隐私中心那张「下载到本机」要真下载）、三条错误通道记账（console / pageerror / 4xx）。
async function newPage (browser, token, bag, vp) {
  const context = await browser.newContext({
    viewport: vp || DESKTOP, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', acceptDownloads: true
  })
  await context.addInitScript((t) => {
    window.localStorage.setItem('mindisle_token', t.access)
    window.localStorage.setItem('mindisle_refresh', t.refresh)
  }, { access: token, refresh: 'gate4-no-refresh-needed' })
  const page = await context.newPage()
  page.setDefaultTimeout(60000)
  page.on('console', (m) => {
    if (m.type() !== 'error') return
    if (/Failed to load resource/.test(m.text())) return
    bag.console.push('console.error: ' + m.text().slice(0, 200))
  })
  page.on('pageerror', (e) => bag.pageerror.push('pageerror: ' + String(e && e.message).slice(0, 200)))
  page.on('response', (res) => {
    const st = res.status()
    if (st < 400) return
    if (/favicon|\.woff2?(\?|$)/.test(res.url())) return
    bag.http.push('HTTP ' + st + ' ' + res.url().replace(BASE, ''))
  })
  return { context, page }
}

// 每张图都必须自带一条机器判据，且判据不成立时要能进 failures 影响退出码 ——
// 「拍了但没人核对」的图等于没拍（阶段 3 那条「评论区截图拍在 0 评论的帖上」就是这么来的）。
function shot (file, label, ok, reading) {
  manifest.push({ file: file, label: label, ok: !!ok, reading: reading || '' })
  if (ok) { say('  [图] ' + file + ' —— ' + label + (reading ? '  <- ' + reading : '')) }
  else { nFail++; failures.push('[图] ' + file + ' 的判据没成立：' + (reading || label)); say('  [图 FAIL] ' + file + ' —— ' + (reading || '')) }
}

// Gate 清单里那条独立的夹具污染检查项：图是拿去答辩的，屏幕上不该有 smoke_ / probe / 冒烟 / 探针。
function fixturePollution (text) {
  const s = String(text || '')
  const rx = /smoke_[0-9A-Za-z_]*|probe[-_][0-9A-Za-z_]*|冒烟·[^\s]{0,10}|探针[^\s]{0,10}/g
  const m = s.match(rx)
  return m ? Array.from(new Set(m)).slice(0, 8) : []
}

// ---------- 1 夹具：读者 / 作者 / 一条干净的公开长帖 ----------
// 为什么这条帖必须现造而不是从广场里挑一条：现查 GET /api/posts?page=0&size=50 的 total=336，
// 首屏里凡是「不是我自己发的」全是历轮冒烟夹具（冒烟·、检索锚、话题冒烟…）。
// 拿夹具拍 Gate4 的图，等于把上一轮刚写进踩坑日志的「夹具污染」原样再犯一次。
//
// 为什么读者是 demo01、作者必须是 demo03：PostService.dwellCountableFor 排除作者本人，
// 自己读自己的帖永远不该记分；而 demo01 是阶段 4 的主演示号，情绪档案、隐私中心、AI 会话
// 全在它身上，停留这条链路只有用「别人的帖」才读得出 view + read_through 两行。
// 正文刻意不含危机词与敏感词：这条帖是拍「社区长什么样」的，不该顺手制造一张工单。
const LONG_BODY = [
  '这周我把拖了三个月的英语演讲练完了。说不上顺利，但我把它交出去了。',
  '之前一直拖着，是因为我总觉得要准备到万无一失才配开口。上周换了个做法：',
  '先把稿子拆成三小段，每天早上朗读一遍，只录三分钟；第二天先听自己昨天的录音，',
  '再改一句。改到第四天我发现，最难的那句其实不是发音，是我不敢在开头说一句',
  '「我其实有点紧张」。说出来之后，节奏反而稳了。',
  '今天正式讲完，台下有人笑了两次，中间我停顿了一下没接上，但我没有跳过去，',
  '而是看着笔记把那一句补完。回来路上我一直在想：原来所谓准备好了，',
  '不过是愿意带着没准备好的那部分一起上台。写在这里，给下周还要上台的自己看。',
  '补几句具体的，免得又变成一句空口号。第一，我把开场那三句背到不用想，' +
  '因为上台最初二十秒最容易崩，脑子里一空就靠肌肉记忆接上。第二，我把稿子里所有长句' +
  '拆成短句，逗号改成句号，念的时候自然有气口。第三，我对着手机前置摄像头录了两遍，' +
  '第一遍不敢看，第二遍发现我一直在摸鼻子，就把它写进稿子里，变成一句自嘲。',
  '真正上场的时候，灯光比想象中亮，看不清人脸，反而松了口气。中间那次停顿大概有两秒,' +
  '我心里数了一下，两秒在台上像二十秒那么长，但台下其实没人觉得奇怪。说完最后一句，' +
  '我鞠躬，听见有掌声，就这样。没有奇迹，也没有灾难。',
  '这周还有一件小事：楼下新开的豆浆店老板记得我不加糖，今天早上他直接把杯子递过来的时候，' +
  '我忽然觉得自己在这个城市里被记住了一个很小的细节。这种被记住的感觉挺暖和的。',
  '下周还有一场更大的，在报告厅，台下坐着不太认识的评委。我打算继续用这周的笨办法：' +
  '把开头背熟，把长句拆短，把紧张写在稿子里。先记在这儿，算是给自己交的一份作业。',
  '另外记录两个可能对小到不值一提、但我确实靠它们撑过这周的细节。一是我把演讲稿打印出来了，' +
  '字号调到最大，行间距拉到两倍，纸的背面什么也不写。这样就算我中途忘了下一句，' +
  '低头也能在纸面上立刻找回位置，而不是在脑子里翻找。二是上台前我在侧台站着，' +
  '把手机里存的呼吸计数点了两下，跟着数了四次吸气和呼气。这不是什么了不起的仪式，' +
  '它只是给我的手忙找一个地方放。',
  '我还学到一件事：不要把「准备不足」当成「不能开始」的理由。我原本想的是等发音练到听不出口音' +
  '再报名，结果那个念头一冒出来就是三个月。真正让我走到台前的，反而是那份只写了三成的稿子——' +
  '因为它已经足够开口，剩下的部分是在开口的过程中自己长出来的。这句话说起来像鸡汤，' +
  '可我自己录的那两段音频就是证据，第一段的语速快得像在逃，第二段我敢在句子中间停下来了。',
  '如果屏幕那边正好也有人卡在「还没准备好」这句话上，我想说的是：把要求降到今天能交出去的份量，' +
  '先交，再改。不用给任何人看第一版。写完这篇我要去把报告厅那场的稿子拆成三段，' +
  '明早读第一遍，只录三分钟。到时候如果我又怂了，我就回来重看这篇。'
].join('\n')

const rd = await login(READER)
const au = await login(AUTHOR)
say('# 读者 ' + READER + '(id=' + rd.uid + ' nick=' + rd.nick + ') · 作者 ' + AUTHOR + '(id=' + au.uid + ' nick=' + au.nick + ')')

const p0 = await api('GET', '/api/ai/conversations', rd.token)
const beforeConvs = p0.status === 200 && p0.code === 0 ? (p0.data || []) : null
check(!!beforeConvs, '[夹具] 读者 ' + READER + ' 的会话列表基线取到了（AI 段的「新建了几条」全看它）',
  '基线 ' + (beforeConvs ? beforeConvs.length : 0) + ' 条')

const pr = await api('POST', '/api/posts', au.token, {
  title: '这周我把拖了三个月的英语演讲练完了', content: LONG_BODY, visibility: 'public'
})
const evidencePostId = pr.data && pr.data.id ? Number(pr.data.id) : null
check(pr.status === 200 && pr.code === 0 && pr.data && pr.data.status === 'PUBLISHED' && !!evidencePostId,
  '[夹具] demo03 现造一条 ' + LONG_BODY.length + ' 字公开长帖并停在 PUBLISHED（Gate4 的 05/06 两张图拍它）',
  'http=' + pr.status + ' code=' + pr.code + ' id=' + evidencePostId + ' status=' + (pr.data && pr.data.status))
// 「正文够不够长」从来不是一条判据，它只是下面那条几何判据的近似替身：
// 真正要证的是「这一屏放不下、必须滚动才能读完」，那条由下面 D 段现场量出来的
// scrollHeight - innerHeight 承担（差 > 100 才算数），比在这里数字数硬得多。
// 上一版在这里写死大于等于 1200 字，夹具实测没到，红的是这条近似断言而不是产品 —— 近似断言一律当场删。

// ---------- 2 AI 段的判据要从「服务端存的那句」数起，不是从屏幕上的 innerText 数起 ----------
// 因为 Chrome 的 innerText 会把 ol 的 ::marker 也当成文字吐出来（「1. 」会出现在返回值里），
// 用它数编号行等于让被测对象自己报数。真正的对照是：库里那行 content 里有几条编号行，
// DOM 里就该有几条 li —— 一边是服务端原文，一边是渲染结果，两者独立才有证据价值。
async function lastAssistantRaw (token, convId) {
  const r = await api('GET', '/api/ai/conversations/' + convId + '/messages', token)
  if (!r.data || !r.data.length) return null
  const ai = r.data.filter((m) => m.role === 'assistant').pop()
  return ai ? { id: Number(ai.id), content: String(ai.content || ''), risk: ai.riskLevel } : null
}
const RE_NUM_LINE = /^ {0,3}\d{1,9}[.)][ \t]+\S/
function countNumberedLines (text) {
  return String(text).split('\n').filter((line) => RE_NUM_LINE.test(line)).length
}

// ---------- 3 等一条回复跑完 ----------
// 判「跑完了」用两个独立信号：界面上「正在生成」收掉 + 侧栏那条会话在接口里能查到 assistant 行。
// 只看前者会把「done 帧没处理但 busy 复位」这种坏状态放过。
async function waitDone (page, token, convId, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  let st = null
  while (Date.now() < deadline) {
    st = await page.evaluate(() => {
      const last = [...document.querySelectorAll('.stream-box .row')].pop()
      const mt = last ? last.querySelector('.meta') : null
      return {
        role: last ? last.className.replace('row', '').trim() : '',
        len: last && last.querySelector('.txt') ? last.querySelector('.txt').textContent.replace(/\u258d/g, '').trim().length : 0,
        generating: mt ? /正在生成/.test(mt.textContent) : false,
        stopBtn: !!document.querySelector('.btns button') && [...document.querySelectorAll('.btns button')].some((b) => /停止生成/.test(b.textContent))
      }
    })
    if (st.role.indexOf('ai') >= 0 && st.len > 0 && !st.generating && !st.stopBtn) {
      const raw = convId ? await lastAssistantRaw(token, convId) : null
      if (raw) return { st, raw }
    }
    await page.waitForTimeout(600)
  }
  return { st, raw: convId ? await lastAssistantRaw(token, convId) : null, timedOut: true }
}

async function ask (page, text) {
  await page.locator('.composer textarea').fill(text)
  await page.locator('.btns button', { hasText: '发送' }).first().click()
}

// ---------- 4 开工 ----------
const { chromium } = require(PW_DIR)
const bagAll = { console: [], pageerror: [], http: [] }
const browser = await chromium.launch({ headless: true, executablePath: CHROME })
say('# 浏览器 ' + browser.version() + ' · ' + CHROME + ' · 视口 ' + DESKTOP.width + 'x' + DESKTOP.height)

// dev server 会不会吐旧模块，是本项目踩过的那颗雷（踩坑日志第 13 轮）：
// 判据跑在旧 transform 上会假绿，所以每条要拍的线先做一次「磁盘字面量 == 模块体」。
async function freshness (label, diskRel, servedRel) {
  const disk = fs.readFileSync(path.join(ROOT, diskRel), 'utf8')
  const lits = [...disk.matchAll(/'([^'\\\n]{4,28})'|"([^"\\\n]{4,28})"/g)].map((m) => m[1] || m[2]).filter((x) => /[\u4e00-\u9fa5]/.test(x))
  // Vue 模板里的中文属性值是双引号（label="表名"），只抽单引号会把整份模板漏干净，
  // 于是 freshness 退化成「服务的那坨字节非空」这种假判据。两种引号都要抽。
  let probes = lits.slice(-6)
  let how = '中文字面量'
  if (probes.length === 0) {
    // 有的文件（utils/markdown.js 就是）中文全写在注释里，一个字符串字面量都抽不出来。
    // 那就拿注释行当探针：Vite dev 的 transform 不剥注释，实测服务出来的模块体里原样带着它们。
    probes = disk.split('\n').map((x) => x.trim()).filter((x) => /[\u4e00-\u9fa5]/.test(x)).map((x) => x.slice(0, 40)).slice(-6)
    how = '中文注释行'
  }
  let served = ''
  let status = 0
  try { const r = await fetch(BASE + servedRel); status = r.status; served = await r.text() } catch (e) { status = -1 }
  const missing = probes.filter((x) => served.indexOf(x) < 0)
  if (probes.length === 0) return check(false, '[stale] ' + label + ' 磁盘源码里一个中文探针都没抽到，判据空跑不许算绿', 'diskRel=' + diskRel)
  return check(status === 200 && served.length > 200 && missing.length === 0,
    '[stale] ' + label + ' 的 dev server 模块体和磁盘源码同步（按' + how + '抽样 ' + probes.length + ' 条，全部命中）',
    'http=' + status + ' 字节=' + served.length + ' 缺=' + JSON.stringify(missing))
}

// ============================================================ A. AI 对话（U7 / T4.19 / Markdown 渲染）
// 这一段是本轮的正脸：用户上一轮的原话是「ai 对话那部分还没做好，显示第四阶段还没弄好」，
// 而 aichat.mjs 那 60 多项判据里没有一条回答「屏幕上那段 Markdown 到底是被渲染成列表、
// 还是被当成一坨纯文本贴上去」。接口全绿、单测全绿、构建全绿，三件事同时成立时界面仍然可以是坏的。
let convId = null
{
  const bag = { console: [], pageerror: [], http: [] }
  const { context, page } = await newPage(browser, rd.token, bag)
  await page.goto(BASE + '/ai', { waitUntil: 'load' })
  await page.waitForTimeout(2000)
  await freshness('ChatView.vue', 'frontend/src/views/ai/ChatView.vue', '/src/views/ai/ChatView.vue')
  await freshness('utils/markdown.js', 'frontend/src/utils/markdown.js', '/src/utils/markdown.js')

  await page.locator('.chip.new').click()
  await page.waitForTimeout(600)
  const rows0 = await page.locator('.stream-box .row').count()
  check(rows0 === 0, '[ai] 点「＋ 新建」之后聊天区是空的（残留会把两轮对话缝成一屏，判据会数错气泡）',
    'rows=' + rows0)

  const ask1 = '我周末要做英语演讲，一上台就发慌。请不要反问我，直接按这个格式回答：先写一句共情的话，'
    + '然后另起三行，第一行以 1. 开头，第二行以 2. 开头，第三行以 3. 开头，每行一条今晚就能做的准备动作。'
  await ask(page, ask1)

  // 会话 id 从接口拿（前端把 conversationId 存在组件里，DOM 上读不到；而判 Markdown 要对齐库里那句原文）
  let cid = null
  for (let i = 0; i < 24 && !cid; i++) {
    await page.waitForTimeout(900)
    const lc = await api('GET', '/api/ai/conversations', rd.token)
    if (lc.code === 0 && lc.data) {
      const fresh = lc.data.filter((c) => !beforeConvs.some((b) => String(b.id) === String(c.id)))
      if (fresh.length) cid = Number(fresh[0].id)
    }
  }
  convId = cid
  if (cid) probeConvs.push({ tag: 'A-编号列表', id: cid })
  check(!!convId, '[ai] 这一轮真的在服务端建了会话（不是前端起了个空壳）', 'conv=' + convId)

  const w = await waitDone(page, rd.token, convId, 60000)
  const st = w.st
  const raw = w.raw
  check(!w.timedOut && !!raw && raw.content.length > 40,
    '[ai] 助手那句完整跑完并从库里读回来了（done 帧之后落的那一行）',
    'len=' + (raw ? raw.content.length : 0) + ' role=' + st.role + ' 界面 len=' + st.len +
    (w.timedOut ? ' [超时]' : ''))

  const md = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('.row.ai')]
    const last = rows[rows.length - 1]
    const box = last ? last.querySelector('.txt.md') : null
    if (!box) return null
    const cs = getComputedStyle(box)
    const lis = [...box.querySelectorAll('li.md-li')]
    return {
      whiteSpace: cs.whiteSpace,
      textLen: box.textContent.trim().length,
      olCount: box.querySelectorAll('ol.md-ol').length,
      ulCount: box.querySelectorAll('ul.md-ul').length,
      liCount: lis.length,
      liParentTags: lis.map((li) => li.parentElement ? li.parentElement.tagName : ''),
      markerStyle: box.querySelector('ol.md-ol') ? getComputedStyle(box.querySelector('ol.md-ol')).listStyleType : '',
      pCount: box.querySelectorAll('p.md-p').length
    }
  })
  check(!!md, '[ai] 助手气泡带 .txt.md（Markdown 通道挂上了；没有它 v-html 那条分支就没走）', JSON.stringify(st))

  // 这条判据看着小，它才是「流式回复到底能不能读」的本质：
  // .txt 的 pre-wrap 是给用户那句留的（保住手打的换行），助手这句走 v-html，
  // 一旦 .md{white-space:normal} 被改回去，块级标签前会多出空行，列表会拉成一坨。
  check(!!md && md.whiteSpace === 'normal',
    '[ai] 助手气泡的计算样式 white-space 是 normal（CSS 里 .md 把 .txt 的 pre-wrap 压掉了，Markdown 才成形状）',
    md ? 'whiteSpace=' + md.whiteSpace : '气泡不存在')

  const nRaw = raw ? countNumberedLines(raw.content) : 0
  const liTags = md ? md.liParentTags.filter((t) => t !== 'OL').length : 0
  check(!!md && (nRaw === 0 || md.liCount >= nRaw),
    '[ai] 编号列表真的被渲染成 li 元素：库里那句有 ' + nRaw + ' 行编号，DOM 里数到 ' + (md ? md.liCount : 0) + ' 条 li',
    'li=' + (md ? md.liCount : 0) + ' ol=' + (md ? md.olCount : 0) + ' ul=' + (md ? md.ulCount : 0) +
    ' p=' + (md ? md.pCount : 0) + ' 原文编号行=' + nRaw)
  check(!!md && nRaw > 0 && md.olCount >= 1 && liTags === 0,
    '[ai] 而且它落成的是 <ol><li>（有序，不是被降级成 ul 或纯段落）—— 这条如果模型不配合会红，红就是取证没达成',
    'ol=' + (md ? md.olCount : 0) + ' 非OL父节点=' + liTags + ' marker=' + (md ? md.markerStyle : '') +
    ' 原文编号行=' + nRaw + ' 原文=' + JSON.stringify(String(raw ? raw.content : '').slice(0, 60)))

  const pills = await page.evaluate(() => ({
    pill: document.querySelectorAll('.meta .pill').length,
    mePill: [...document.querySelectorAll('.row.me .meta .pill')].map((x) => x.textContent.replace(/\s+/g, '')).join(','),
    metaAi: (document.querySelector('.row.ai .meta') || {}).textContent || '',
    meMeta: (document.querySelector('.row.me .meta') || {}).textContent || '',
    disclaimer: (document.querySelector('.disclaimer') || {}).textContent || '',
    fbBtns: [...document.querySelectorAll('.row.ai .fb button')].map((b) => b.textContent.trim()).join(','),
    convs: document.querySelectorAll('.conv').length,
    crisis: !!document.querySelector('.crisis'),
    bodyText: document.body.innerText
  }))
  check(pills.mePill.length > 0,
    '[ai] 用户那句下方出现情绪标签（挂在 me 而不是 ai，且和刷新后同一个位置）',
    'me.pill=' + JSON.stringify(pills.mePill))
  check(/词典初判|模型复判/.test(pills.meMeta), '[ai] 用户那句下方写明判定来源通道（通道挂在 me 行：ChatView 把服务端 meta 里的 emotion 记成 mine.channel，判的是用户那一句的情绪从哪来）',
    'me.meta=' + JSON.stringify(pills.meMeta.replace(/\s+/g, ' ').slice(0, 160)) + ' ai.meta=' + JSON.stringify(pills.metaAi.replace(/\s+/g, ' ').slice(0, 80)))
  check(/12356/.test(pills.disclaimer) && /不构成医疗诊断/.test(pills.disclaimer),
    '[ai] 常驻免责脚注同时在屏幕上写着「不构成医疗诊断」和 12356（FR10 要求这句话不是弹一次就没了）',
    JSON.stringify(pills.disclaimer.replace(/\s+/g, ' ').slice(0, 120)))
  check(pills.convs >= 1, '[ai] 侧栏列出这一条新会话', 'conv=' + pills.convs)
  check(fixturePollution(pills.bodyText).length === 0,
    '[ai] 这一屏没有夹具账号字样（Gate 清单里那条独立的污染检查项）',
    '命中=' + JSON.stringify(fixturePollution(pills.bodyText)) + ' 会话数=' + pills.convs)

  await page.screenshot({ path: path.join(OUT, '01-AI对话-流式回复与Markdown编号列表.png') })
  shot('01-AI对话-流式回复与Markdown编号列表.png',
    'U7：屿屿的流式回复与渲染成有序列表的 Markdown（ol.md-ol / li.md-li）',
    !!md && md.whiteSpace === 'normal' && md.liCount >= Math.max(1, nRaw),
    'li=' + (md ? md.liCount : 0) + ' 编号行=' + nRaw + ' whiteSpace=' + (md ? md.whiteSpace : '-'))

  // 02 这张图要证的是「情绪标签与常驻免责脚注同时在场」。上一版只看 disclaimer.bottom 是否不超过 innerHeight，
  // 而 ChatView 的消息区是 420px 的内层滚动容器（.stream-box），内容在容器里滚，外层视口判据量不到它，
  // 等于「滚动条在动，断言在看别处」。这里改成：现场把两个元素的几何都读出来打进日志，再拍整页图（fullPage）。
  await page.evaluate(() => { const d = document.querySelector('.disclaimer'); if (d) d.scrollIntoView({ block: 'end' }) })
  await page.waitForTimeout(500)
  const metaShot = await page.evaluate(() => {
    const rectOf = (sel) => { const el = document.querySelector(sel); if (!el) return null; const r = el.getBoundingClientRect(); return { top: Math.round(r.top), bottom: Math.round(r.bottom), h: Math.round(r.height) } }
    const inView = (r) => !!r && r.top >= 0 && r.bottom <= window.innerHeight
    const d = rectOf('.disclaimer')
    const pl = rectOf('.row.me .meta .pill')
    return {
      hasDisclaimer: !!d, hasPill: !!pl, dRect: d, pillRect: pl, vh: window.innerHeight,
      dVisible: inView(d), pillVisible: inView(pl),
      meMeta: ((document.querySelector('.row.me .meta') || {}).textContent || '').replace(/\s+/g, ' ').slice(0, 200),
      aiMeta: ((document.querySelector('.row.ai .meta') || {}).textContent || '').replace(/\s+/g, ' ').slice(0, 120)
    }
  })
  say('# [ai] 02 图现场几何：vh=' + metaShot.vh + ' disclaimer=' + JSON.stringify(metaShot.dRect) + ' 在视口=' + metaShot.dVisible +
    ' me.pill=' + JSON.stringify(metaShot.pillRect) + ' 在视口=' + metaShot.pillVisible + ' me.meta=' + JSON.stringify(metaShot.meMeta))
  await page.screenshot({ path: path.join(OUT, '02-AI对话-情绪标签与免责脚注.png'), fullPage: true })
  shot('02-AI对话-情绪标签与免责脚注.png',
    'U7：气泡下方的情绪标签、判定通道、真实耗时与页面常驻免责脚注（含 12356）；整页图，内层滚动容器不参与可见性判定',
    metaShot.hasPill && metaShot.hasDisclaimer && /词典初判|模型复判/.test(metaShot.meMeta) && metaShot.dVisible,
    '标签=' + metaShot.pillVisible + ' 脚注在屏=' + metaShot.dVisible + ' me.meta=' + JSON.stringify(metaShot.meMeta.slice(0, 120)))

  // T4.19 的界面侧：点了要亮（落库侧在 6.2 的 SQL 里对账）
  const fbBtn = page.locator('.row.ai').last().locator('.fb button').first()
  const fbTxt = (await fbBtn.innerText()).trim()
  await fbBtn.click()
  await page.waitForTimeout(1200)
  const fbOn = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('.row.ai')]
    const last = rows[rows.length - 1]
    const on = last ? last.querySelectorAll('.fb button.on').length : 0
    return { on, all: last ? [...last.querySelectorAll('.fb button')].map((b) => (b.className.indexOf('on') >= 0 ? '+' : '-') + b.textContent.trim()).join(',') : '' }
  })
  check(fbOn.on === 1, '[ai] 点「' + fbTxt + '」之后只有一个按钮亮着（:class="{on:...}" 真被写进去了）',
    'on=' + fbOn.on + ' 状态=' + JSON.stringify(fbOn.all))
  check(pills.fbBtns.length > 0, '[ai] 助手那条气泡下面有反馈按钮组（没有它 T4.19 那条端点就没有用户入口）',
    '按钮=' + JSON.stringify(pills.fbBtns))

  check(bag.console.length === 0 && bag.pageerror.length === 0 && bag.http.length === 0,
    '[ai] 这一屏没有 console error / 未捕获异常 / 4xx·5xx',
    'console=' + bag.console.length + ' pageerror=' + bag.pageerror.length + ' http=' + bag.http.length +
    ' 明细=' + JSON.stringify(bag.http.concat(bag.console).slice(0, 3)))
  bagAll.console.push(...bag.console); bagAll.pageerror.push(...bag.pageerror); bagAll.http.push(...bag.http)
  await context.close()
}

// ============================================================ B. 危机轮次（默认关，CRISIS=1 才跑）
// 关着跑的理由写在 aichat.mjs 头上：危机那一条会写 alert_ticket 与站内信，
// 而删会话只删消息 —— 工单是独立生命周期，取证脚本不该有这种杀伤半径还不吭声。
// 界面侧的四条判据（卡片出现 / 徽标档位 / 12356 / 求助页入口）这里补的是**一张图**，
// Gate4 的 D3 要的是「屏幕上真的置顶了求助卡」，aichat.mjs 只给了断言没给图。
if (DO_CRISIS) {
  const bag = { console: [], pageerror: [], http: [] }
  const { context, page } = await newPage(browser, rd.token, bag)
  await page.goto(BASE + '/ai', { waitUntil: 'load' })
  await page.waitForTimeout(1800)
  await page.locator('.chip.new').click()
  await page.waitForTimeout(500)
  // 这里必须重新取一次基线：§A 那条会话已经落库了，若继续用 §A 的 convId，
  // waitDone 会当场读到 §A 的 assistant 行 —— 等于不等危机这一句跑完就拍照。
  const bCrisis = await api('GET', '/api/ai/conversations', rd.token)
  const baseCrisis = bCrisis.status === 200 && bCrisis.code === 0 ? (bCrisis.data || []) : []
  await ask(page, '我最近总想着让自己消失，不知道该怎么办。')
  let crisisConvId = null
  for (let i = 0; i < 24 && !crisisConvId; i++) {
    await page.waitForTimeout(900)
    const lc = await api('GET', '/api/ai/conversations', rd.token)
    if (lc.code === 0 && lc.data) {
      const fresh = lc.data.filter((c2) => !baseCrisis.some((b) => String(b.id) === String(c2.id)))
      if (fresh.length) crisisConvId = Number(fresh[0].id)
    }
  }
  if (crisisConvId) probeConvs.push({ tag: 'B-危机轮', id: crisisConvId })
  check(!!crisisConvId, '[ai·L2] 危机这一轮自己建了会话（不复用上一段的 convId，否则等的是旧回复）', 'conv=' + crisisConvId)
  const c = await waitDone(page, rd.token, crisisConvId, 60000)
  const cr = await page.evaluate(() => {
    const box = document.querySelector('.crisis')
    const badge = document.querySelector('.crisis .badge')
    const phone = document.querySelector('.crisis .phone')
    const txt = document.querySelector('.crisis-text')
    const ops = document.querySelector('.crisis-ops')
    return {
      exists: !!box, cls: box ? box.className : '', lv: box ? Number((box.className.match(/lv(\d)/) || [])[1]) : -1,
      badge: badge ? badge.textContent.trim() : '', phone: phone ? phone.textContent.trim() : '',
      text: txt ? txt.textContent.trim() : '', ops: ops ? ops.textContent.replace(/\s+/g, ' ').trim() : '',
      top: ((document.querySelector('.topbar') || {}).textContent || '').replace(/\s+/g, ' ').trim(),
      body: document.body.innerText
    }
  })
  check(cr.exists, '[ai·L2] 危机轮次里屏幕上置顶了那张求助卡（不只是一句文案）',
    'cls=' + cr.cls + ' text=' + JSON.stringify(cr.text.slice(0, 60)))
  check(/L2|L3/.test(cr.badge) && cr.lv >= 2, '[ai·L2] 徽标停在 L2 以上，卡片外框也升到同一档',
    'badge=' + cr.badge + ' lv=' + cr.lv)
  check(/12356/.test(cr.phone + cr.text), '[ai·L2] 卡片上写着 12356（读 sys_config，不是前端写死的字符串）',
    'phone=' + cr.phone)
  check(/求助/.test(cr.ops), '[ai·L2] 卡片里带完整求助页入口（免登录也能进）', 'ops=' + cr.ops.slice(0, 60))
  check(fixturePollution(cr.body).length === 0, '[ai·L2] 危机这一屏也没有夹具字样',
    '命中=' + JSON.stringify(fixturePollution(cr.body)))
  await page.screenshot({ path: path.join(OUT, '03-AI对话-L2L3危机卡.png') })
  shot('03-AI对话-L2L3危机卡.png', 'U7/FR10：L2 危机轮次置顶的求助卡（12356 + 完整求助页入口）',
    cr.exists && cr.lv >= 2, 'badge=' + cr.badge + ' lv=' + cr.lv + ' 回复长度=' + (c.raw ? c.raw.content.length : 0))
  // 本轮新建的会话不在脚本里删：Gate4 的落库对账（user_action.ai_feedback / emotion_record）
  // 还要读这几条的 message_id，删了就没得对。id 全部记在 manifest 里，对完账再清。
  bagAll.console.push(...bag.console); bagAll.http.push(...bag.http)
  await context.close()
} else {
  say('# —— 危机段没跑（CRISIS!=1）：它会留下 alert_ticket 与站内信，删会话清不掉，只在明确要取证时开')
}

// ============================================================ C. 情绪档案（U9 / FR7 / T4.12）
{
  const bag = { console: [], pageerror: [], http: [] }
  const { context, page } = await newPage(browser, rd.token, bag)
  await freshness('EmotionView.vue', 'frontend/src/views/emotion/EmotionView.vue', '/src/views/emotion/EmotionView.vue')
  await page.goto(BASE + '/emotion', { waitUntil: 'load' })
  // ECharts 是异步初始化的：networkidle 只能说明接口回来了，不能说明 canvas 已经画上去。
  await page.waitForTimeout(2500)
  await page.locator('.chart canvas').first().waitFor({ state: 'attached', timeout: 20000 }).catch(() => null)
  const em = await page.evaluate(() => {
    const h2 = [...document.querySelectorAll('h2')].map((x) => x.textContent.replace(/\s+/g, ' ').trim())
    const cs = [...document.querySelectorAll('.chart canvas')]
    return {
      heads: h2.join(' | '),
      canvas: cs.length,
      painted: cs.filter((cv) => cv.width > 60 && cv.height > 60).length,
      err: !!document.querySelector('.chart-err'),
      accum: !!document.querySelector('.accum'),
      meta: ((document.querySelector('.meta') || {}).textContent || '').replace(/\s+/g, ' ').trim(),
      subs: [...document.querySelectorAll('h3.sub')].map((x) => x.textContent.trim()).join(' | '),
      body: document.body.innerText
    }
  })
  check(em.canvas >= 2 && em.painted >= 2,
    '[emo] 情绪档案这一页真的画出了图表（canvas 不止一个且宽高都不是 0）—— jsdom 与冒烟都证不到这件事',
    'canvas=' + em.canvas + ' 已画=' + em.painted + ' 小标题=' + JSON.stringify(em.subs))
  check(!em.err && !em.accum,
    '[emo] 没有「接口正常但图没画出来」的降级横幅，也没落在「数据积累中」的空态',
    'chart-err=' + em.err + ' accumulating=' + em.accum + ' meta=' + JSON.stringify(em.meta.slice(0, 90)))
  check(/情绪档案/.test(em.heads) && /情绪周报/.test(em.heads) && /这份情绪数据会被怎么用/.test(em.heads),
    '[emo] 三个板块的标题都在（档案 / 周报 / 答辩口径），需求 FR7.4 那句「说得清数据怎么被用」是界面的一部分',
    'h2=' + JSON.stringify(em.heads))
  check(fixturePollution(em.body).length === 0, '[emo] 这一屏没有夹具账号字样',
    '命中=' + JSON.stringify(fixturePollution(em.body)))

  await page.evaluate(() => { const h = [...document.querySelectorAll('h2')].find((x) => /情绪档案/.test(x.textContent)); if (h) h.scrollIntoView({ block: 'start' }) })
  await page.waitForTimeout(900)
  await page.screenshot({ path: path.join(OUT, '04-情绪档案-趋势与分布.png') })
  shot('04-情绪档案-趋势与分布.png', 'U9：情绪档案的趋势图与分布图（ECharts 在真浏览器里画出来了）',
    em.painted >= 2, 'canvas=' + em.canvas + ' 已画=' + em.painted)
  await page.screenshot({ path: path.join(OUT, '04b-情绪档案-整页.png'), fullPage: true })
  shot('04b-情绪档案-整页.png', 'U9：整页（打卡 / 档案 / 周报 / 数据用途说明），验长页不破版', true,
    'h2 数=' + em.heads.split('|').length)
  check(bag.console.length === 0 && bag.pageerror.length === 0 && bag.http.length === 0,
    '[emo] 这一屏没有 console error / 未捕获异常 / 4xx·5xx',
    'console=' + bag.console.length + ' pageerror=' + bag.pageerror.length + ' http=' + JSON.stringify(bag.http.slice(0, 3)))
  bagAll.console.push(...bag.console); bagAll.pageerror.push(...bag.pageerror); bagAll.http.push(...bag.http)
  await context.close()
}

// ============================================================ D. 详情页停留计时（FR5.1 / T4.17）
// 冒烟第 23 步已经钉死了服务端的阈值算术，但它证不到两件事：
//   ① 前端到底有没有把「够 3 秒」那一刻的请求发出去（发多长、completed 带的什么）；
//   ② 用户能不能当场看懂这条链路是通的（那行「本页已读 N 秒」）。
// 所以下面把浏览器自己发出的 /read 请求逐条录进证据日志 —— 这是本文件里唯一一段
// 「以网络面板为判据」的取证，接口侧的账仍由冒烟去对。
{
  const bag = { console: [], pageerror: [], http: [] }
  const { context, page } = await newPage(browser, rd.token, bag)
  const readCalls = []
  page.on('request', (rq) => {
    if (rq.method() !== 'POST' || !/\/api\/posts\/\d+\/read$/.test(rq.url())) return
    readCalls.push({ at: Date.now(), body: rq.postData(), resp: null })
  })
  page.on('response', async (res) => {
    if (!/\/api\/posts\/\d+\/read$/.test(res.url())) return
    const j = await res.json().catch(() => null)
    const last = readCalls[readCalls.length - 1]
    if (last) last.resp = { status: res.status(), data: j && j.data }
  })
  await freshness('PostDetailView.vue', 'frontend/src/views/post/PostDetailView.vue', '/src/views/post/PostDetailView.vue')
  await page.goto(BASE + '/post/' + evidencePostId, { waitUntil: 'load' })
  await page.locator('.detail article.body').first().waitFor({ state: 'visible', timeout: 20000 }).catch(() => null)

  // 这条是 §D 整段的前提：PostDetailView 的「到底」判据是 scrollHeight - innerHeight - scrollY <= 24，
  // 内容不足一屏时它恒为真 —— 那 fixture 一打开就已经「读到底」了，下面那条「未读到底」的断言
  // 会在一个根本不需要滚动的页面上白过。先量这个几何差，差不到 100px 就当场判红，别往下演。
  const geo = await page.evaluate(() => ({
    scrollHeight: document.documentElement.scrollHeight, innerHeight: window.innerHeight,
    gap: document.documentElement.scrollHeight - window.innerHeight
  }))
  check(geo.gap > 100, '[dwell] 这条帖在这一屏放不下（真的需要滚动才能读完），下面「未读到底 → 已读到底」才有意义',
    'scrollHeight=' + geo.scrollHeight + ' innerHeight=' + geo.innerHeight + ' 差=' + geo.gap)

  const d0 = await page.evaluate(() => {
    const el = document.querySelector('.dwell')
    return { exists: !!el, text: el ? el.textContent.replace(/\s+/g, ' ').trim() : '' }
  })
  check(d0.exists && /^本页已读 \d+ 秒 ·/.test(d0.text),
    '[dwell] 详情页底部那行停留读数当场就在（埋点是最容易「代码在、数据不在」的东西，让它可读才有人核对）',
    JSON.stringify(d0.text))
  const s0 = Number((d0.text.match(/本页已读 (\d+) 秒/) || [])[1] || -1)

  // 采样窗口必须明显大于一跳（心跳是 1s）：上一版只等 1.8s，读数按整秒刷新，
  // 撞上同拍就读成 0s 到 0s，红的是探针自己，不是产品。等 2.6s 后至少跨过一个整秒边界。
  await page.waitForTimeout(2600)
  const d1 = await page.evaluate(() => document.querySelector('.dwell') ? document.querySelector('.dwell').textContent.replace(/\s+/g, ' ').trim() : '')
  const s1 = Number((d1.match(/本页已读 (\d+) 秒/) || [])[1] || -1)
  check(s1 > s0, '[dwell] 那行读数在往前跳（1s 心跳真的在刷，不是把接口回执抄上来的一次性文案）',
    't0=' + s0 + 's → t+2.6s=' + s1 + 's  ' + JSON.stringify(d1))
  check(/还差 \d+ 秒才算一次浏览/.test(d1) || /够一次浏览/.test(d1),
    '[dwell] 没读够的时候界面写的是「还差几秒」，读够了写「够一次浏览」（阈值判据在服务端，界面只如实转述）',
    JSON.stringify(d1))

  await page.waitForTimeout(2400)
  const d2 = await page.evaluate(() => document.querySelector('.dwell') ? document.querySelector('.dwell').textContent.replace(/\s+/g, ' ').trim() : '')
  check(/够一次浏览/.test(d2), '[dwell] 累计过 3 秒之后界面翻成「够一次浏览」', JSON.stringify(d2))
  check(/未读到底/.test(d2), '[dwell] 还没滚到底的时候不许提前写「已读到底」（完读是另一条判据，2 分那档）',
    JSON.stringify(d2))
  await page.screenshot({ path: path.join(OUT, '05-详情页-停留计时中.png') })
  shot('05-详情页-停留计时中.png', 'U4/FR5.1：详情页停留计时读数（够一次浏览 / 尚未读到底）',
    /够一次浏览/.test(d2) && /未读到底/.test(d2), d2)

  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
  await page.waitForTimeout(1200)
  const d3 = await page.evaluate(() => document.querySelector('.dwell') ? document.querySelector('.dwell').textContent.replace(/\s+/g, ' ').trim() : '')
  check(/已读到底（完读计 2 分）/.test(d3), '[dwell] 滚到底之后那行换成「已读到底（完读计 2 分）」', JSON.stringify(d3))
  await page.screenshot({ path: path.join(OUT, '06-详情页-已读到底.png') })
  shot('06-详情页-已读到底.png', 'U4/FR5.1：滚到底之后的完读态（2 分那一档的界面侧证据）',
    /已读到底/.test(d3), d3)

  // 换页也是一次结算：onBeforeUnmount(flushDwell)。用它把最后一段停留冲出去，
  // 也顺带证明「SPA 内部换页不会丢埋点」——这一条只有真浏览器跑得出来。
  await page.goto(BASE + '/feed', { waitUntil: 'load' })
  await page.waitForTimeout(1200)

  const sent = readCalls.map((c) => {
    let parsed = null
    try { parsed = JSON.parse(c.body || 'null') } catch (e) { parsed = null }
    return { body: parsed, resp: c.resp }
  })
  say('# [dwell] 浏览器实际发出的 /read 请求共 ' + sent.length + ' 条：' + JSON.stringify(sent).slice(0, 500))
  check(sent.length >= 1, '[dwell] 这一趟阅读真的往服务端报了（不是只在本机累加）', '条数=' + sent.length)
  check(sent.some((x) => x.body && x.body.completed === true && x.body.durationMs >= 3000),
    '[dwell] 其中至少一条带着 completed=true 且 durationMs>=3000（完读与够时长在同一次上报里都成立）',
    '明细=' + JSON.stringify(sent.map((x) => x.body)))
  // 前端这一头的钉子：任何 completed=true 的上报都必须带够 3000ms。
  // 上一版只验「有没有一条长的」，漏掉了「有没有一条短的」—— r2 那趟真发出去过 durationMs=1 的完读上报，
  // 这条判据 + 后端 PostController 的时长下限一起才把它两头堵死（旧字节码回执 readThroughRecorded=true，
  // 新字节码 false，对照已经跑过，见 dev-log 第 13 轮）。
  const earlyCompleted = sent.filter((x) => x.body && x.body.completed === true && !(x.body.durationMs >= 3000))
  check(earlyCompleted.length === 0, '[dwell] 没有任何一条 completed=true 的上报短于 3 秒（r2 的 1ms 绕过在客户端这一侧也被钉住了）',
    '短报明细=' + JSON.stringify(earlyCompleted.map((x) => x.body)))
  check(sent.some((x) => x.resp && x.resp.status === 200 && x.resp.data && x.resp.data.viewRecorded === true),
    '[dwell] 服务端回执里 viewRecorded=true（界面说够 3 秒，服务端也认了这一次浏览）',
    '回执=' + JSON.stringify(sent.map((x) => x.resp && x.resp.data)))
  check(sent.some((x) => x.resp && x.resp.data && x.resp.data.readThroughRecorded === true),
    '[dwell] 服务端回执里 readThroughRecorded=true（完读那 2 分真的落进了隐式行为权重）',
    '回执=' + JSON.stringify(sent.map((x) => x.resp && x.resp.data)))
  await page.goto(BASE + '/post/' + evidencePostId, { waitUntil: 'load' })
  await page.locator('.detail article.body').first().waitFor({ state: 'visible', timeout: 20000 }).catch(() => null)
  const d4 = await page.evaluate(() => document.querySelector('.dwell') ? document.querySelector('.dwell').textContent.replace(/\s+/g, ' ').trim() : '')
  const s4 = Number((d4.match(/本页已读 (\d+) 秒/) || [])[1] || -1)
  // 判据口径：上一趟这一页累计了 6 秒以上才切走，若停留被「续上来」，这里会直接读到 >= 3 秒
  // 并且带着「已读到底」。所以「小于一屏心跳能读到的最大值 + 没继承完读态」才是这条的本意，
  // 拿「必须正好等于 0 秒」当判据是在赌 evaluate 落在哪一秒上（那样这条断言永远会因为等待时长而红）。
  check(s4 >= 0 && s4 < 3 && !/已读到底/.test(d4),
    '[dwell] 重开这一页是从 0 重新计时的（没把上一趟的 6 秒续上来，也没继承「已读到底」）',
    '秒=' + s4 + ' 读数=' + JSON.stringify(d4))
  await context.close()
  bagAll.console.push(...bag.console); bagAll.http.push(...bag.http)
}

// ============================================================ E. 隐私中心（FR1.5/1.6/1.7 / T4.21）
{
  const bag = { console: [], pageerror: [], http: [] }
  const { context, page } = await newPage(browser, rd.token, bag)
  await freshness('PrivacyView.vue', 'frontend/src/views/privacy/PrivacyView.vue', '/src/views/privacy/PrivacyView.vue')
  await page.goto(BASE + '/privacy', { waitUntil: 'load' })
  await page.waitForTimeout(2200)
  const pv = await page.evaluate(() => {
    const heads = [...document.querySelectorAll('.blk h2')].map((x) => x.textContent.replace(/\s+/g, ' ').trim())
    const rows = document.querySelectorAll('.tbl .el-table__body tr')
    const totalTxt = [...document.querySelectorAll('.row2 .dim')].map((x) => x.textContent).join(' ')
    const m = /接口合计 (\d+) 条 · 列出 (\d+) 个域 · 列出的加起来 (\d+) 条/.exec(totalTxt)
    return {
      blk: document.querySelectorAll('.blk').length, heads: heads.join(' | '),
      tableRows: rows.length,
      total: m ? Number(m[1]) : -1, listed: m ? Number(m[2]) : -1, listedSum: m ? Number(m[3]) : -1,
      recon: !!document.querySelector('.warn'),
      alert: ((document.querySelector('.alert') || {}).textContent || '').replace(/\s+/g, ' ').trim(),
      body: document.body.innerText
    }
  })
  check(pv.blk >= 4, '[priv] 隐私中心四块都在（账号本体 / 逐域对账 / 导出 / 注销）',
    'blk=' + pv.blk + ' h2=' + JSON.stringify(pv.heads))
  // 判据先拿接口侧的同源数字校准。上一版在这里写死 rows >= 20，而界面默认「只看有条数的域」
  // （onlyNonZero 初始为 true），demo01 实测 25 个域里只有 9 个有条数 —— 那条红是断言自己写错了，
  // 不是产品有 bug。现在默认态对齐非零域数，再把开关关掉对齐注册表全部域数，两个态都验。
  const sumApi = await api('GET', '/api/privacy/summary', rd.token)
  const domainsAll = (sumApi.data && sumApi.data.domains) || []
  const nonZeroAll = domainsAll.filter((x) => Number(x.rows) > 0)
  const apiTotal = sumApi.data ? Number(sumApi.data.totalRows) : -1
  check(domainsAll.length > 0 && nonZeroAll.length > 0,
    '[priv] 接口侧先给出同源分域数字（总共多少域、其中多少域有条数），界面判据拿它校准，而不是拍脑袋写一个 20',
    'domains=' + domainsAll.length + ' 非零=' + nonZeroAll.length + ' totalRows=' + apiTotal)
  check(pv.tableRows === nonZeroAll.length && pv.listed === nonZeroAll.length,
    '[priv] 默认态（只看有条数的域）的表行数与「列出 N 个域」都等于接口里 rows 大于 0 的域数',
    'rows=' + pv.tableRows + ' 列出=' + pv.listed + ' 接口非零=' + nonZeroAll.length)
  check(pv.total > 0 && pv.total === apiTotal, '[priv] 界面「接口合计」与接口 totalRows 同源相等',
    '界面=' + pv.total + ' 接口=' + apiTotal)
  check(!pv.recon && pv.total === pv.listedSum,
    '[priv] 「接口合计」与「逐域加起来」相等（对账这条判据的意义就在这两个数必须同源）',
    'total=' + pv.total + ' listedSum=' + pv.listedSum + ' warn=' + pv.recon)
  check(/账号状态正常/.test(pv.alert), '[priv] 当前账号是正常态（没有待清除的注销申请）', JSON.stringify(pv.alert))
  check(fixturePollution(pv.body).length === 0, '[priv] 这一屏没有夹具账号字样',
    '命中=' + JSON.stringify(fixturePollution(pv.body)))
  await page.screenshot({ path: path.join(OUT, '07-隐私中心-概览与逐域对账.png'), fullPage: true })
  shot('07-隐私中心-概览与逐域对账.png', 'FR1.5：可查看、可核对 —— 账号状态 + 逐域条数对账表（默认只看有条数的域）',
    pv.blk >= 4 && pv.total === pv.listedSum, '合计 ' + pv.total + ' 条 / ' + pv.tableRows + ' 行')

  // 第二个状态：关掉开关之后要列出注册表里的全部域（含 0 行的那些），这才叫「说得全」。
  await page.locator('.row2 .el-switch').first().click()
  await page.waitForTimeout(1500)
  const pv2 = await page.evaluate(() => {
    const rows = document.querySelectorAll('.tbl .el-table__body tr')
    const totalTxt = [...document.querySelectorAll('.row2 .dim')].map((x) => x.textContent).join(' ')
    const m = /接口合计 (\d+) 条 · 列出 (\d+) 个域 · 列出的加起来 (\d+) 条/.exec(totalTxt)
    return { tableRows: rows.length, total: m ? Number(m[1]) : -1, listed: m ? Number(m[2]) : -1, listedSum: m ? Number(m[3]) : -1 }
  })
  check(pv2.tableRows === domainsAll.length && pv2.listed === domainsAll.length,
    '[priv] 关掉「只看有条数的域」之后列出 PrivacyDomains 注册表里的全部域（0 行的也在清单上，可携带权要求说得全）',
    'rows=' + pv2.tableRows + ' 列出=' + pv2.listed + ' 接口域数=' + domainsAll.length)
  check(pv2.total === apiTotal && pv2.listedSum === apiTotal,
    '[priv] 全表态下「接口合计」与「逐域加起来」仍然相等（多列出来的 0 行不改变总数，这才叫对账）',
    'total=' + pv2.total + ' listedSum=' + pv2.listedSum + ' 接口=' + apiTotal)
  await page.evaluate(() => { const el = document.querySelector('.tbl'); if (el) el.scrollIntoView({ block: 'center' }) })
  await page.waitForTimeout(600)
  await page.screenshot({ path: path.join(OUT, '07b-隐私中心-逐域全表.png'), fullPage: true })
  shot('07b-隐私中心-逐域全表.png', 'FR1.5：关掉「只看有条数的域」之后的全表对账（注册表里每个域都在，含 0 行）',
    pv2.tableRows === domainsAll.length, '全表 ' + pv2.tableRows + ' 行 / 合计 ' + pv2.total + ' 条')

  // 导出是异步任务：点一次提交，轮询到 SUCCESS 为止。判据取「下载到本机」从 disabled 变可用，
  // 因为按钮的禁用态就是 canDownload 的镜像，比读文案更硬。
  const btnExport = page.locator('.blk', { hasText: '3 · 导出我的全部个人信息' }).locator('button', { hasText: '提交导出任务' }).first()
  const btnDownload = page.locator('.blk', { hasText: '3 · 导出我的全部个人信息' }).locator('button', { hasText: '下载到本机' }).first()
  // 库里可能已经躺着一条历轮跑出来的 SUCCESS 任务，那时「下载到本机」一上来就是亮的 ——
  // 只看按钮变亮会假绿。正判据是这一趟确实新建了一条任务：先记下 latest 的 id，跑完之后它必须变大。
  const lb0 = await api('GET', '/api/privacy/export/latest', rd.token)
  const idBefore = lb0.data && lb0.data.id ? Number(lb0.data.id) : 0
  const stBefore = lb0.data && lb0.data.status ? String(lb0.data.status) : '(无记录)'
  say('# [priv] 提交导出之前的 latest：id=' + idBefore + ' status=' + stBefore)
  await btnExport.click()
  let ready = false
  let statusTxt = ''
  let lastTask = null
  let failedEarly = false
  const tExport = Date.now()
  // 状态判据只认接口字段。上一版拿整块 textContent 去匹配「失败」两个字，而导出那张卡片里
  // 常驻着一个「失败原因」的表头（PrivacyView 描述列表），任务还在排队时就被这个静态标签误杀。
  // 界面文案这一路只读来做记录（取 .row2 span.dim 那句 statusText），不当判据。
  for (let i = 0; i < 40 && !ready && !failedEarly; i++) {
    await page.waitForTimeout(1500)
    const lt = await api('GET', '/api/privacy/export/latest', rd.token)
    lastTask = lt.data || null
    const isNew = !!lastTask && Number(lastTask.id) > idBefore
    statusTxt = await page.evaluate(() => {
      const blk = [...document.querySelectorAll('.blk')].find((x) => /3 · 导出/.test(x.textContent))
      const sp = blk ? blk.querySelector('.row2 span.dim') : null
      return sp ? sp.textContent.replace(/\s+/g, ' ').trim() : ''
    })
    const en = await btnDownload.isEnabled().catch(() => false)
    // 库里本来就躺着历轮跑出来的 SUCCESS 任务，按钮一上来就是亮的；所以「亮」必须叠加
    // 「是这一趟新建的那条、且已 SUCCESS、且链接没过期」，否则这一轮只是在下载别人留下的包裹。
    ready = isNew && String(lastTask.status) === 'SUCCESS' && lastTask.downloadReady === true && en
    if (isNew && String(lastTask.status) === 'FAILED') failedEarly = true
    if (i % 3 === 2) say('# [priv] 导出轮询 第' + (i + 1) + '圈 · 已等 ' + Math.round((Date.now() - tExport) / 1000) +
      's · 接口 id=' + (lastTask ? lastTask.id : 'null') + ' status=' + (lastTask ? lastTask.status : 'null') +
      ' 按钮亮=' + en + ' 界面=' + JSON.stringify(statusTxt.slice(0, 90)))
  }
  check(!failedEarly, '[priv] 这一趟新建的导出任务没有跑成 FAILED（排队 → 后台跑 → 成功 这条状态机在浏览器这一端走到底）',
    '末次接口读数=' + JSON.stringify(lastTask ? { id: lastTask.id, status: lastTask.status, errorText: lastTask.errorText } : null))
  check(ready, '[priv] 导出任务在浏览器里跑到了 SUCCESS，「下载到本机」由灰变亮（异步四步：排队→后台跑→成功才给口令）',
    '轮询读数=' + JSON.stringify(statusTxt.slice(0, 160)))
  const lb1 = await api('GET', '/api/privacy/export/latest', rd.token)
  const t1 = lb1.data || {}
  check(Number(t1.id) > idBefore && t1.status === 'SUCCESS' && t1.downloadReady === true,
    '[priv] 亮起来的按钮背后是这一趟新建的那条任务（id 从 ' + idBefore + ' 递增、status=SUCCESS、downloadReady=true）',
    'id=' + t1.id + ' status=' + t1.status + ' ready=' + t1.downloadReady + ' rows=' + t1.rowCountSummary + ' bytes=' + t1.fileBytes)
  // 光「按钮亮了」还是接口侧的判据。这里真点一次下载：浏览器收到一个非空包裹，
  // 才说明「个人信息可携带」这条路在用户这一端是走得通的（文件名与字节数进证据日志，包裹本体不进仓库）。
  let dl = null
  if (ready) {
    const got = page.waitForEvent('download', { timeout: 40000 }).catch(() => null)
    await btnDownload.click()
    const d = await got
    if (d) {
      fs.mkdirSync(DL_DIR, { recursive: true })
      const fp = path.join(DL_DIR, d.suggestedFilename())
      await d.saveAs(fp).catch(() => null)
      dl = { name: d.suggestedFilename(), bytes: fs.existsSync(fp) ? fs.statSync(fp).size : 0, file: fp }
    }
  }
  check(!!dl && dl.bytes > 500, '[priv] 浏览器真的收到了一个非空的个人信息包裹（可携带权的最后一公里在界面上走通了）',
    dl ? '文件名=' + dl.name + ' 字节=' + dl.bytes : '没收到 download 事件')
  await page.waitForTimeout(900)
  const toast = await page.evaluate(() => [...document.querySelectorAll('.el-message')].map((x) => x.textContent.replace(/\s+/g, ' ').trim()).join(' | '))
  // 07b 那一步把表格滚进了视野，这一屏的主角在下面那张导出卡片 + 右上角的提示条，
  // 所以先把 blk3 滚进来再截全页（fullPage 会把固定定位的 el-message 一起收进同一张图）。
  await page.evaluate(() => {
    const blk = [...document.querySelectorAll('.blk')].find((x) => /3 · 导出/.test(x.textContent))
    if (blk) blk.scrollIntoView({ block: 'center' })
  })
  await page.waitForTimeout(400)
  await page.screenshot({ path: path.join(OUT, '08-隐私中心-导出成功可下载.png'), fullPage: true })
  shot('08-隐私中心-导出成功可下载.png', 'FR1.6：可携带权 —— 导出任务 SUCCESS 并把包裹下载到本机（图上带回执提示）',
    ready && !!dl && dl.bytes > 500, '包裹=' + (dl ? dl.name + '/' + dl.bytes + 'B' : '无') + ' 提示=' + JSON.stringify(toast.slice(0, 120)))

  // 二次确认：点第一下只进 armed 态，5 秒不点自动解除。绝不点第二下 —— demo01 是演示主号，
  // 而且上一轮已经查明「冷静期里还能登录 = 自动撤回」，一旦真提交，下一轮取证会连号一起丢。
  const blk4 = page.locator('.blk', { hasText: '4 · 注销账号' })
  const btnDeact = blk4.locator('button', { hasText: '提交注销申请' }).first()
  const deactVisible = await btnDeact.isVisible().catch(() => false)
  check(deactVisible, '[priv] 注销按钮在正常态显示的是「提交注销申请」（不是直接一个红的危险按钮）',
    'visible=' + deactVisible)
  if (deactVisible) {
    await btnDeact.click()
    await page.waitForTimeout(500)
    const armedTxt = await page.evaluate(() => {
      const blk = [...document.querySelectorAll('.blk')].find((x) => /4 · 注销账号/.test(x.textContent))
      const b = blk ? [...blk.querySelectorAll('button')].map((y) => y.textContent.trim()) : []
      return b.join(' | ')
    })
    check(/再点一次确认注销/.test(armedTxt), '[priv] 第一下点下去只把按钮换成「再点一次确认注销」（FR1.7 的不可逆动作不许一下到位）',
      '按钮=' + JSON.stringify(armedTxt))
    await page.screenshot({ path: path.join(OUT, '09-隐私中心-注销二次确认.png') })
    shot('09-隐私中心-注销二次确认.png', 'FR1.7：注销账号的两段式确认（30 天冷静期入口，本图没提交）',
      /再点一次确认注销/.test(armedTxt), '按钮=' + JSON.stringify(armedTxt))
    await page.waitForTimeout(6000)
    const backTxt = await page.evaluate(() => {
      const blk = [...document.querySelectorAll('.blk')].find((x) => /4 · 注销账号/.test(x.textContent))
      const b = blk ? [...blk.querySelectorAll('button')].map((y) => y.textContent.trim()) : []
      return b.join(' | ')
    })
    check(/提交注销申请/.test(backTxt) && !/再点一次/.test(backTxt),
      '[priv] 5 秒不点第二下自己回到原态（armed 是个会过期的状态，不是一个悬在半空的危险按钮）',
      '按钮=' + JSON.stringify(backTxt))
  }
  check(bag.console.length === 0 && bag.pageerror.length === 0 && bag.http.length === 0,
    '[priv] 这一屏没有 console error / 未捕获异常 / 4xx·5xx',
    'console=' + bag.console.length + ' pageerror=' + bag.pageerror.length + ' http=' + JSON.stringify(bag.http.slice(0, 3)))
  bagAll.console.push(...bag.console); bagAll.http.push(...bag.http)
  await context.close()
}

await browser.close()

// ---------- 收尾 ----------
say('')
say('---- 阶段4 取证汇总：PASS ' + nPass + ' / FAIL ' + nFail + ' / 图 ' + manifest.length + ' 张 ----')
manifest.forEach((m) => say('  [' + (m.ok ? 'PASS' : 'FAIL') + '] ' + m.file + ' —— ' + m.label + ' / ' + (m.reading || '')))
failures.forEach((x) => say('   ✗ ' + x))
say('# 控制台红字合计 console=' + bagAll.console.length + ' pageerror=' + bagAll.pageerror.length + ' http=' + bagAll.http.length)
say('# 本轮夹具：读者=' + READER + '(id=' + rd.uid + ') 作者=' + AUTHOR + '(id=' + au.uid + ') 帖子 id=' + evidencePostId)
say('# 本轮新建的 AI 会话（落库对账之后再删）：' + JSON.stringify(probeConvs))
fs.writeFileSync(path.join(OUT, 'console-evidence.log'), logLines.join('\n') + '\n', 'utf8')
fs.writeFileSync(path.join(OUT, 'shot-manifest.json'), JSON.stringify({
  at: new Date().toISOString(), base: BASE, api: APIBASE, chrome: CHROME,
  browser: 'chrome', viewport: DESKTOP, reader: READER + '/' + rd.uid, author: AUTHOR + '/' + au.uid,
  evidencePostId, crisisRan: DO_CRISIS, probeConvs, pass: nPass, fail: nFail, failures,
  shots: manifest, consoleErrors: bagAll.console, pageErrors: bagAll.pageerror, httpErrors: bagAll.http
}, null, 2) + '\n', 'utf8')
process.exitCode = nFail === 0 ? 0 : 1

// 真浏览器路由巡探（playwright-core + 本机 Chrome）—— 专治「点了没反应」
//
// 为什么需要这个文件（2026-09-24，阶段 4）：
//   domprobe 走的是打包后的 probe.js + jsdom，它量得到「组件渲染出来的 DOM」，
//   量不到「浏览器到底有没有把这个路由 chunk 取回来」。而用户报的「AI 对话没做好」
//   恰恰是后者：Vite 在运行中途补预构建依赖 → 旧 URL 全部 504 Outdated Optimize Dep
//   → 动态 import 整块失败 → 点了导航什么也不发生。这类故障只在
//   真浏览器 + 真 dev server + 真点击路径 下出现，取证也就必须在这里做。
//
// 用法（后端 8080 与前端 5173 都要先起来）：node frontend/probe/routecrawl.mjs
// 退出码：任何一条 FAIL 即 1。全程不发消息 / 不发帖 / 不打卡，不写业务数据，可反复跑。
import { createRequire } from 'node:module'
import path from 'node:path'

const require = createRequire('E:/codex workspace/_cache/node_modules/')
const { chromium } = require('E:/codex workspace/_cache/node_modules/playwright-core')
const CHROME = process.env.CHROME || 'C:/Users/Drbrain/AppData/Local/Google/Chrome/Application/chrome.exe'
const BASE = process.env.BASE || 'http://127.0.0.1:5173'
const SHOT = process.env.SHOT_DIR || 'E:/codex workspace/_cache/009_mindisle'

let nPass = 0
let nFail = 0
function check(ok, name, read) {
  if (ok) { nPass++; console.log('PASS ' + name + (read ? '   <- ' + read : '')) }
  else { nFail++; console.log('FAIL ' + name + '   <- ' + (read || '')) }
  return ok
}

const login = await fetch(BASE + '/api/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    username: 'demo01', password: 'Test1234',
    captchaId: '00000000000000000000000000000000', captchaCode: 'ZZZZ'
  })
}).then((r) => r.json()).catch(() => null)
if (!login || login.code !== 0) {
  console.log('登录没过去（后端 8080 / dev 代理 5173 都起来了吗）：' + JSON.stringify(login && login.code))
  process.exit(1)
}
const token = login.data.accessToken

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const ctx = await browser.newContext({ viewport: { width: 1500, height: 1000 }, locale: 'zh-CN' })
await ctx.addInitScript((t) => window.localStorage.setItem('mindisle_token', t), token)
const page = await ctx.newPage()

// 一路收集三类「用户看不见但确实坏了」的信号：控制台错误、未捕获异常、4xx/5xx 资源。
const bad = { console: [], pageerror: [], http: [], reqfail: [] }
page.on('console', (m) => {
  const t = m.text()
  if (m.type() === 'error' && !/favicon/i.test(t)) bad.console.push(t.slice(0, 260))
})
page.on('pageerror', (e) => bad.pageerror.push(String(e.message || e).slice(0, 260)))
page.on('response', (r) => {
  const u = r.url()
  if (r.status() >= 400 && !/favicon|\.woff2?|\.png|\.ico/i.test(u)) bad.http.push(r.status() + ' ' + u.replace(BASE, '').slice(0, 150))
})
page.on('requestfailed', (r) => bad.reqfail.push(r.url().replace(BASE, '').slice(0, 150) + ' :: ' + (r.failure() ? r.failure().errorText : '?')))

const NAV = [
  { label: '广场', path: '/feed' },
  { label: '搜索', path: '/search' },
  { label: '发布', path: '/publish' },
  { label: '屿屿', path: '/ai' },
  { label: '情绪', path: '/emotion' },
  { label: '我的', path: '/me' },
  { label: '需要帮助？', path: '/help' }
]

async function snap() {
  return await page.evaluate(() => ({
    path: location.pathname + location.search,
    cards: document.querySelectorAll('.mi-card').length,
    canvas: document.querySelectorAll('canvas').length,
    canvasInk: [...document.querySelectorAll('canvas')].filter((c) => c.width > 40 && c.height > 40).length,
    chartErr: [...document.querySelectorAll('.chart-err')].map((x) => x.innerText.replace(/\s+/g, ' ').slice(0, 70)),
    textarea: document.querySelectorAll('textarea').length,
    conv: document.querySelectorAll('.conv').length,
    rows: document.querySelectorAll('.row').length,
    inputs: document.querySelectorAll('input').length,
    text: document.body.innerText.replace(/\s+/g, ' ').slice(0, 1200),
    bodyChars: document.body.innerText.replace(/\s+/g, ' ').length
  }))
}

// 每个路由各自的判据。为什么不用一刀切的 bodyChars 阈值：
//   它会同时犯两种错——/help 全文只有 200 多字（三张热线 + 一句免责），按字数判就把它误杀；
//   反过来「字数够但关键字段没渲染」的页面又能蒙过去。所以按路由钉具体证据，
//   判据本身要能回答「这条挂了，用户的屏幕上到底少了什么」。
const COLD = {
  '/feed': { why: '广场冷启动就画出真实帖子卡片（≥5 张），不是骨架屏也不是空列表', ok: (s) => s.cards >= 5 && s.bodyChars > 800 },
  '/search': { why: '搜索页进来就有可输入的关键词框（三个 Tab 共用同一个输入框）', ok: (s) => s.inputs >= 1 && s.bodyChars > 300 },
  '/publish': { why: '发布页直达即可发帖：正文 textarea 与话题/可见性控件都在', ok: (s) => s.textarea >= 1 && s.inputs >= 1 },
  '/ai': { why: '对话页有输入框且有历史会话（刻意不发消息，免得把危机分级记录写进演示库）', ok: (s) => s.textarea === 1 && s.conv >= 1 },
  '/emotion': { why: '情绪档案四图直链打开就都画出来（≥4 张有尺寸的 canvas），且没有渲染异常横幅', ok: (s) => s.canvasInk >= 4 && s.chartErr.length === 0 },
  '/me': { why: '个人中心有资料卡与「我的帖子 / 情绪档案」入口，读数不是空壳', ok: (s) => s.cards >= 3 && s.bodyChars > 600 },
  // 求助页的判据是「号码全不全」，不是「字多不多」：危机场景下少一行热线就是事故，
  // 而它本来就比别的页面短，按字数判只会训练自己忽略它。
  '/help': {
    why: '求助页把三条热线 + 120/110 + 「不是医疗诊断」一起摆出来（缺一即挂，与字数无关）',
    ok: (s) => ['12356', '010-82951332', '400-161-9995', '120 / 110', '不是'].every((k) => s.text.includes(k))
  }
}

// 冷启动直链：每个路由单独 goto（用户拿到一个地址贴进浏览器，也必须能开）。
const cold = {}
for (const n of NAV) {
  const mark = { pe: bad.pageerror.length, http: bad.http.length, rf: bad.reqfail.length }
  await page.goto(BASE + n.path, { waitUntil: 'load' })
  await page.waitForTimeout(n.path === '/emotion' ? 4500 : 1600)
  const s = await snap()
  cold[n.path] = s
  const want = COLD[n.path]
  check(s.path.startsWith(n.path) && want.ok(s),
    '[cold ' + n.path + '] ' + want.why,
    'path=' + s.path + ' cards=' + s.cards + ' canvas=' + s.canvas + '/' + s.canvasInk +
      ' inputs=' + s.inputs + ' textarea=' + s.textarea + ' conv=' + s.conv +
      ' chartErr=' + s.chartErr.length + ' bodyChars=' + s.bodyChars)
  const added = bad.pageerror.slice(mark.pe).concat(bad.http.slice(mark.http), bad.reqfail.slice(mark.rf))
  check(added.length === 0,
    '[cold ' + n.path + '] 这一段没有未捕获异常、也没有 4xx/5xx 或取不到的资源（动态 import 失败就死在这里）',
    added.join(' | ').slice(0, 220))
}

// 点导航的 SPA 走位：这才是用户真实手势，也是「Outdated Optimize Dep」唯一会现身的路径。
await page.goto(BASE + '/feed', { waitUntil: 'load' })
await page.waitForTimeout(1500)
for (let i = 1; i < NAV.length; i++) {
  const n = NAV[i]
  const mark = { pe: bad.pageerror.length, http: bad.http.length, cs: bad.console.length, rf: bad.reqfail.length }
  await page.locator('nav.mi-nav a', { hasText: n.label }).first().click()
  await page.waitForTimeout(n.path === '/emotion' ? 4500 : 1800)
  const s = await snap()
  check(s.path.startsWith(n.path), '[click ' + n.label + '] 点顶栏那个字，地址真的变成了 ' + n.path,
    'path=' + s.path + ' cards=' + s.cards + ' canvas=' + s.canvas)
  const added = bad.pageerror.slice(mark.pe).concat(bad.http.slice(mark.http), bad.console.slice(mark.cs), bad.reqfail.slice(mark.rf))
  check(added.length === 0,
    '[click ' + n.label + '] 点击过程中没有新冒出异常 / 4xx·5xx / console error（504 Outdated Optimize Dep 就落在这条）',
    added.join(' | ').slice(0, 220))
}

// 五条与界面文案真实性直接相关的判据（这五条改之前都是真的挂在屏幕上的假话或漏判）。
await page.goto(BASE + '/feed', { waitUntil: 'load' })
await page.waitForTimeout(1600)
const feed = await page.evaluate(() => document.body.innerText)
check(feed.indexOf('情绪打卡与档案') < 0,
  '[feed] 「后端尚未实现的接口」那张卡片里不再出现 POST /api/emotions/checkin —— 这条接口已经落地并且真出图了，写在表里就是假话',
  '命中=' + (feed.indexOf('情绪打卡与档案') >= 0))
// 广场「为你推荐」那张占位卡的排期文案：改之前它写的是「阶段 6/7」，
// 而手册 §15 阶段 7 表里 T7.2/T7.5 召回与 T7.4 情绪加权全部在阶段 7，阶段 6 是审核台与管理端。
check(feed.includes('阶段 7 未实现') && !feed.includes('阶段 6/7'),
  '[feed] 推荐流占位卡把排期说准了：只有阶段 7，不再捎上一个并不做推荐的阶段 6',
  '命中「阶段 7 未实现」=' + feed.includes('阶段 7 未实现') + ' 残留「阶段 6/7」=' + feed.includes('阶段 6/7'))
const navText = await page.evaluate(() => (document.querySelector('nav.mi-nav') || {}).innerText || '')
check(navText.indexOf('屿屿') >= 0 && navText.indexOf('屿灵') < 0,
  '[nav] 顶栏入口叫「屿屿」，与 system prompt 里那个同伴名字同一个（屿灵是旧名，两处不同名会被问）',
  navText.replace(/\s+/g, ' '))

await page.goto(BASE + '/emotion', { waitUntil: 'load' })
await page.waitForTimeout(5000)
const emo = await snap()
check(emo.canvasInk >= 4 && emo.chartErr.length === 0,
  '[emotion] 情绪档案四图都真的画出来了（≥4 张有尺寸的 canvas），且页面没有「数据到了但图没画出来」那条横幅',
  'canvas=' + emo.canvas + ' inked=' + emo.canvasInk + ' chartErr=' + JSON.stringify(emo.chartErr))

await page.goto(BASE + '/ai', { waitUntil: 'load' })
await page.waitForTimeout(2000)
const ai = await snap()
check(ai.textarea === 1 && ai.conv >= 1,
  '[ai] 对话页有输入框且有会话列表（不发消息，避免把危机分级记录写进演示库）',
  'textarea=' + ai.textarea + ' conv=' + ai.conv)

// /help 免登录（router meta.public）：危机场景下用户可能连密码都不记得，
// 这条用「不带 token 的新上下文」真开一次，验它没被路由守卫踢到 /login。
const anonCtx = await browser.newContext({ viewport: { width: 1400, height: 900 }, locale: 'zh-CN' })
const anonPage = await anonCtx.newPage()
await anonPage.goto(BASE + '/help', { waitUntil: 'load' })
await anonPage.waitForTimeout(1500)
const anon = await anonPage.evaluate(() => ({
  path: location.pathname,
  text: document.body.innerText.replace(/\s+/g, ' ').slice(0, 1200)
}))
check(anon.path === '/help' && anon.text.includes('12356'),
  '[help-anon] 不带 token 直开 /help 也不被踢回登录页，热线照样显示（FR10 硬要求）',
  'path=' + anon.path + ' 含12356=' + anon.text.includes('12356'))
await anonCtx.close()

await page.screenshot({ path: path.join(SHOT, 'routecrawl-ai.png') })
await page.goto(BASE + '/emotion', { waitUntil: 'load' })
await page.waitForTimeout(4500)
await page.screenshot({ path: path.join(SHOT, 'routecrawl-emotion.png'), fullPage: true })

console.log('---- 汇总：' + (nPass + nFail) + ' 项，失败 ' + nFail + ' 项')
console.log('# 全程 bad: console=' + bad.console.length + ' pageerror=' + bad.pageerror.length +
  ' http=' + bad.http.length + ' reqfail=' + bad.reqfail.length)
;[...new Set(bad.console.concat(bad.pageerror))].slice(0, 8).forEach((x) => console.log('#  ' + x))
;[...new Set(bad.http)].slice(0, 8).forEach((x) => console.log('#  ' + x))
await browser.close()
process.exit(nFail === 0 ? 0 : 1)
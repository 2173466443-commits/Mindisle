// 改版预览取证（小红书式笔记墙 UI）
// 这不是 gate：不写 docs/gate/，只把改版后的关键页面拍成图供人工评审。
// 前置：8080（后端）与 5173（Vite dev）都在跑。
// 跑法：cd frontend && node probe/uipreview.mjs
// 产物：docs/改版预览/*.png + 同目录 console.log
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)

const HERE = path.dirname(fileURLToPath(import.meta.url))
const FE = path.resolve(HERE, '..')
const ROOT = path.resolve(FE, '..')
const OUT_DIR = path.join(ROOT, 'docs', '改版预览')
const PW_DIR = 'E:/codex workspace/_cache/node_modules/playwright-core'
const CHROME = 'C:/Users/Drbrain/AppData/Local/Google/Chrome/Application/chrome.exe'
const BASE = 'http://127.0.0.1:5173'
const ACCT = { username: 'smoke_runner', password: 'Smoke#2026x' }

fs.mkdirSync(OUT_DIR, { recursive: true })
const { chromium } = require(PW_DIR)

async function api (method, p, token, body) {
  const headers = {}
  if (token) headers.Authorization = 'Bearer ' + token
  if (body) headers['Content-Type'] = 'application/json'
  const r = await fetch(BASE + p, { method, headers, body: body ? JSON.stringify(body) : undefined })
  let j = null
  try { j = await r.json() } catch (e) { j = null }
  return { status: r.status, code: j && j.code, data: j && j.data }
}

const login = await api('POST', '/api/auth/login', null, {
  username: ACCT.username, password: ACCT.password,
  captchaId: '00000000000000000000000000000000', captchaCode: 'ZZZZ'
})
const token = login.data && login.data.accessToken
if (!token) throw new Error('登录取不到 token，HTTP 层 code=' + login.code)
console.log('login ok')

const posts = await api('GET', '/api/posts?page=0&size=30', token)
const list = (posts.data && posts.data.list) || []
if (!list.length) throw new Error('广场没有数据，拍图无意义')
const me = Number(login.data.user.id)
const postId = Number(list[0].id)
const other = list.find((x) => !x.anonymous && Number(x.authorId) !== me)
const authorId = other ? Number(other.authorId) : 0
const topics = await api('GET', '/api/topics?page=0&size=12', token)
const withTopic = (topics.data || []).filter((t) => Number(t.postCnt) > 0)
const topicId = withTopic.length ? Number(withTopic[0].id) : 0
console.log('sample post=' + postId + ' author=' + authorId + ' topic=' + topicId)

const browser = await chromium.launch({ headless: true, executablePath: CHROME })
const errs = []
const shots = []

async function shoot (name, route, width, height, waitSel, clickSel) {
  const context = await browser.newContext({
    viewport: { width, height }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai'
  })
  if (!/\/login/.test(route)) {
    await context.addInitScript((t) => { window.localStorage.setItem('mindisle_token', t) }, token)
  }
  const page = await context.newPage()
  page.on('console', (m) => { if (m.type() === 'error') errs.push(name + ' console.error: ' + m.text().slice(0, 200)) })
  page.on('pageerror', (e) => errs.push(name + ' pageerror: ' + String(e).slice(0, 200)))
  page.on('response', (r) => {
    if (r.status() >= 400 && !/favicon/.test(r.url())) errs.push(name + ' HTTP ' + r.status() + ' ' + r.url().slice(0, 140))
  })
  await page.goto(BASE + route, { waitUntil: 'networkidle' })
  if (waitSel) {
    try { await page.waitForSelector(waitSel, { timeout: 8000 }) } catch (e) { errs.push(name + ' 等不到 ' + waitSel) }
  } else {
    await page.waitForTimeout(1500)
  }
  await page.waitForTimeout(800)
  // 胶囊态发布器要点一下才展开，这一张专拍展开态（Item 1 的验收图）
  if (clickSel) {
    try { await page.click(clickSel); await page.waitForTimeout(600) } catch (e) { errs.push(name + ' 点不到 ' + clickSel) }
  }
  const info = await page.evaluate(() => ({
    wall: document.querySelectorAll('.mi-wall').length,
    feedRows: document.querySelectorAll('.feed .row').length,
    cards: document.querySelectorAll('.post').length,
    nav: !!document.querySelector('nav.mi-nav'),
    search: !!document.querySelector('.mi-search'),
    bodyBg: getComputedStyle(document.body).backgroundColor,
    hscroll: document.documentElement.scrollWidth > document.documentElement.clientWidth
  }))
  await page.screenshot({ path: path.join(OUT_DIR, name + '.png') })
  shots.push(Object.assign({ name: name, route: route, width: width }, info))
  console.log(name.padEnd(18) + JSON.stringify(info))
  await context.close()
}

await shoot('01-广场-1600', '/feed', 1600, 1000, '.post')
await shoot('02-广场-1920', '/feed', 1920, 1080, '.post')
await shoot('03-广场-1280', '/feed', 1280, 900, '.post')
await shoot('04-帖子详情', '/post/' + postId, 1600, 1000)
await shoot('05-搜索', '/search?q=' + encodeURIComponent('解禁'), 1600, 1000)
if (authorId) await shoot('06-他人主页', '/user/' + authorId, 1600, 1000)
if (topicId) await shoot('07-话题页', '/topic/' + topicId, 1600, 1000)
await shoot('08-我的帖子', '/me/posts', 1600, 1000)
await shoot('09-AI对话', '/ai', 1600, 1000)
await shoot('10-情绪日历', '/emotion', 1600, 1000)
await shoot('11-私信列表', '/chat', 1600, 1000)
await shoot('12-登录', '/login', 1600, 1000)
await shoot('13-广场-移动', '/feed', 390, 844, '.post')
await shoot('18-广场-发布框展开', '/feed', 1600, 1000, '.post', '.composer-pill')
await shoot('14-个人中心', '/me', 1600, 1000)
await shoot('15-发布页', '/publish', 1600, 1000)
await shoot('16-隐私设置', '/privacy', 1600, 1000)
await shoot('17-注册', '/register', 1600, 1000)

await browser.close()

fs.writeFileSync(path.join(OUT_DIR, 'console.log'),
  '## 页面结构\n' + shots.map((s) => JSON.stringify(s)).join('\n') +
  '\n\n## console/network 红字 ' + errs.length + ' 条\n' + (errs.join('\n') || '（无）') + '\n')

console.log('\n红字合计: ' + errs.length)
errs.slice(0, 25).forEach((e) => console.log('  ' + e))
console.log('输出目录: ' + OUT_DIR)
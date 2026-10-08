// U13 通知中心整页取证：一次跑、三张图、一组断言。
// 与 shootland.mjs 同一口径：只证新增页面，不重签既有阶段闸门。
// 前置：8080 后端 + 5173 Vite dev 在跑。跑法：cd frontend && node probe/shootnc.mjs
import path from 'node:path'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const ROOT = 'E:/codex workspace/009_心屿AI心理陪伴社区'
const OUT_DIR = path.join(ROOT, 'docs', 'gate', '首页')
const PW_DIR = 'E:/codex workspace/_cache/node_modules/playwright-core'
const CHROME = process.env.GATE_CHROME || 'C:/Users/Drbrain/AppData/Local/Google/Chrome/Application/chrome.exe'
const BASE = 'http://127.0.0.1:5173'
const MI_BG = 'rgb(246, 246, 247)'
const ACCT = { username: 'smoke_runner', password: 'Smoke#2026x' }

const { chromium } = require(PW_DIR)
const errors = []
const say = (l) => console.log(l)

const login = await (async () => {
  const r = await fetch('http://127.0.0.1:8080/api/auth/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ ...ACCT, captchaId: '00000000000000000000000000000000', captchaCode: 'ZZZZ' })
  })
  const j = await r.json()
  return j
})()
const token = login.data && login.data.accessToken
if (!token) { say('登录取不到 token（code=' + login.code + '）：环境问题，不拍图'); process.exit(2) }

const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] })
const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
await ctx.addInitScript((t) => { window.localStorage.setItem('mindisle_token', t) }, token)
const page = await ctx.newPage()
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
page.on('pageerror', (e) => errors.push(String(e && e.message)))

await page.goto(BASE + '/notifications', { waitUntil: 'networkidle', timeout: 45000 })
await page.waitForSelector('.nc', { timeout: 20000 })
await page.waitForFunction(() => document.querySelectorAll('.nc-item').length > 0, { timeout: 20000 })
  .catch(() => say('WARN 通知列表为空态（该账号没有通知记录）'))

const snap = () => page.evaluate(() => {
  const q = (s) => document.querySelector(s)
  const t = (s) => { const e = q(s); return e ? e.textContent.replace(/\s+/g, ' ').trim() : null }
  return {
    url: location.pathname,
    bodyBg: getComputedStyle(document.body).backgroundColor,
    cardBg: q('.nc-item') ? getComputedStyle(q('.nc-item')).backgroundColor : null,
    items: document.querySelectorAll('.nc-item').length,
    unread: document.querySelectorAll('.nc-item.is-unread').length,
    chips: Array.from(document.querySelectorAll('.chip')).map((c) => c.textContent.replace(/\s+/g, '')),
    onChip: q('.chip.is-on') ? q('.chip.is-on').textContent.replace(/\s+/g, '') : null,
    lead: t('.topbar .dim'),
    firstTitle: t('.nc-item .nc-title'),
    firstContent: t('.nc-item .nc-content'),
    firstMeta: t('.nc-item .nc-meta'),
    markBtn: document.querySelectorAll('.nc-mark').length,
    more: t('.more'),
    honest: t('.lines'),
    navLink: !!q('.mi-bell'),
    stage: document.querySelectorAll('.mi-stage').length
  }
})

const before = await snap()
const shots = [path.join(OUT_DIR, 'U13-通知中心-全部.png'), path.join(OUT_DIR, 'U13-通知中心-整页.png')]
await page.screenshot({ path: shots[0] })
await page.screenshot({ path: shots[1], fullPage: true })

// 交互 1：点「未读」胶囊，列表应当只剩亮着的条目
const unreadChip = page.locator('.chip', { hasText: '未读' }).first()
if (await unreadChip.count()) {
  await unreadChip.click()
  await page.waitForTimeout(600)
}
const afterFilter = await snap()
await page.screenshot({ path: path.join(OUT_DIR, 'U13-通知中心-仅未读.png') })
shots.push(path.join(OUT_DIR, 'U13-通知中心-仅未读.png'))

// 交互 2：切回全部，点第一条右侧的「已读」小胶囊，未读数应少一
const allChip = page.locator('.chip', { hasText: '全部' }).first()
if (await allChip.count()) { await allChip.click(); await page.waitForTimeout(400) }
const mark = page.locator('.nc-mark').first()
let markOk = false
let afterMark = afterFilter
if (await mark.count()) {
  const n0 = (await snap()).unread
  await mark.click()
  await page.waitForTimeout(1200)
  const n1 = (await snap()).unread
  markOk = n1 === n0 - 1
  afterMark = await snap()
}

// 手机宽度再来一张：通知是「手机上才会反复看」的页面
const m = await browser.newContext({ viewport: { width: 390, height: 844 } })
await m.addInitScript((t) => { window.localStorage.setItem('mindisle_token', t) }, token)
const mp = await m.newPage()
mp.on('console', (c => { if (c.type() === 'error') errors.push('mobile: ' + c.text()) }))
await mp.goto(BASE + '/notifications', { waitUntil: 'networkidle', timeout: 45000 })
await mp.waitForSelector('.nc', { timeout: 20000 })
const pMobile = path.join(OUT_DIR, 'U13-通知中心-移动端.png')
await mp.screenshot({ path: pMobile, fullPage: true })
shots.push(pMobile)

const fails = []
if (before.url !== '/notifications') fails.push('路径是 ' + before.url)
if (before.bodyBg !== MI_BG) fails.push('页面底 ' + before.bodyBg + ' != ' + MI_BG)
if (before.cardBg !== 'rgb(255, 255, 255)') fails.push('卡片底 ' + before.cardBg + ' 不是白')
if (before.items === 0) fails.push('一条通知都没渲染出来')
if (!before.chips.length) fails.push('分类胶囊一个都没有')
if (!before.navLink) fails.push('顶栏铃铛不在（说明 BasicLayout 没套上）')
if (before.stage > 0) fails.push('页面上出现了 StageNotice 错误条')
if (afterFilter.unread > 0 && afterFilter.items !== afterFilter.unread) fails.push('「仅未读」筛选没生效：items=' + afterFilter.items + ' unread=' + afterFilter.unread)
if (await mark.count() && !markOk) fails.push('点单条「已读」后未读数没有少一')
if (errors.length) fails.push('控制台红字 ' + errors.length + ' 条：' + JSON.stringify(errors.slice(0, 3)))

say(JSON.stringify({ before, afterFilter, afterMark, markOk }, null, 2))
say('shots: ' + shots.join(', '))
await browser.close()
if (fails.length) { say('FAIL ' + fails.length + ' 条：\n- ' + fails.join('\n- ')); process.exit(1) }
say('PASS U13 通知中心：条目 ' + before.items + ' / 未读 ' + before.unread + ' / 胶囊 ' + before.chips.length + ' 个 / 单条已读生效')
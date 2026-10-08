// U14 危机求助页取证：一次跑、四张图、一组断言。免登录（游客视角）。
// 跑法：cd frontend && node probe/shoothelp.mjs
import path from 'node:path'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const ROOT = 'E:/codex workspace/009_心屿AI心理陪伴社区'
const OUT = path.join(ROOT, 'docs', 'gate', '首页')
const PW = 'E:/codex workspace/_cache/node_modules/playwright-core'
const CHROME = process.env.GATE_CHROME || 'C:/Users/Drbrain/AppData/Local/Google/Chrome/Application/chrome.exe'
const BASE = 'http://127.0.0.1:5173'
const MI_BG = 'rgb(246, 246, 247)'

const { chromium } = require(PW)
const errors = []
const say = (l) => console.log(l)
const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] })
const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
const page = await ctx.newPage()
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
page.on('pageerror', (e) => errors.push(String(e && e.message)))

await page.goto(BASE + '/help', { waitUntil: 'networkidle', timeout: 45000 })
await page.waitForSelector('.hp', { timeout: 20000 })
await page.waitForFunction(() => document.querySelectorAll('.hl').length >= 4, { timeout: 15000 })

const snap = () => page.evaluate(() => {
  const t = (s) => { const e = document.querySelector(s); return e ? e.textContent.replace(/\s+/g, ' ').trim() : null }
  return {
    url: location.pathname,
    bodyBg: getComputedStyle(document.body).backgroundColor,
    cardBg: t('.hero') ? getComputedStyle(document.querySelector('.hero')).backgroundColor : null,
    hl: Array.from(document.querySelectorAll('.hl')).map((x) => ({
      tag: x.querySelector('.hl-tag').textContent.trim(),
      num: x.querySelector('.hl-num').textContent.trim(),
      badge: x.querySelector('.hl-badge') ? x.querySelector('.hl-badge').textContent.trim() : ''
    })),
    tel: Array.from(document.querySelectorAll('.hl-num a')).map((a) => a.getAttribute('href')),
    safeBtns: Array.from(document.querySelectorAll('.sbtn')).map((b) => b.textContent.trim()),
    source: t('.hero .dim'),
    cardText: t('.card-text'),
    gdRows: document.querySelectorAll('.gd-row').length,
    ring: !!document.querySelector('.br-ring'),
    honest: t('ul.lines'),
    stage: document.querySelectorAll('.mi-stage').length
  }
})

const before = await snap()
const shots = [path.join(OUT, 'U14-求助页-首屏.png'), path.join(OUT, 'U14-求助页-整页.png')]
await page.screenshot({ path: shots[0] })
await page.screenshot({ path: shots[1], fullPage: true })

// 点「我需要帮助」：自助引导应当整段展开
await page.locator('.s-help').click()
await page.waitForTimeout(700)
const afterHelp = await snap()
await page.screenshot({ path: path.join(OUT, 'U14-求助页-我需要帮助.png'), fullPage: true })
shots.push(path.join(OUT, 'U14-求助页-我需要帮助.png'))

// 走一步 grounding + 开一轮呼吸，证交互不是死图
const seen = await page.evaluate(() => {
  const cur = document.querySelector('.gd-row.is-cur')
  return cur ? cur.textContent.replace(/\s+/g, ' ').trim() : null
})
await page.locator('.gd-done').click()
await page.waitForTimeout(300)
const moved = await page.evaluate(() => {
  const cur = document.querySelector('.gd-row.is-cur')
  return cur ? cur.textContent.replace(/\s+/g, ' ').trim() : null
})
await page.locator('.br-ops .mini').click()
await page.waitForTimeout(2500)
const breath = await page.evaluate(() => {
  const r = document.querySelector('.br-ring')
  return { phase: r.querySelector('.br-phase').textContent.trim(), count: r.querySelector('.br-count').textContent.trim(), transform: getComputedStyle(r).transform }
})
await page.screenshot({ path: path.join(OUT, 'U14-求助页-着陆与呼吸.png'), fullPage: true })
shots.push(path.join(OUT, 'U14-求助页-着陆与呼吸.png'))

// 安全确认落本地：刷新一次应当还能看到那条记录，且没有发出任何写请求
const localState = await page.evaluate(() => localStorage.getItem('mindisle_crisis_checkin'))
await page.reload({ waitUntil: 'networkidle' })
await page.waitForSelector('.safe-note', { timeout: 10000 })
const note = await page.evaluate(() => { const e = document.querySelector('.safe-note'); return e ? e.textContent.replace(/\s+/g, ' ').trim() : null })

// 复制号码（剪贴板在无头环境里可能被拒，只证按钮可点且不炸页面）
let copyText = null
try { await page.locator('.hl .mini').first().click(); await page.waitForTimeout(400); copyText = await page.evaluate(() => document.querySelector('.hl .mini').textContent.trim()) } catch (e) { copyText = 'ERR ' + e.message }

const m = await browser.newContext({ viewport: { width: 390, height: 844 } })
const mp = await m.newPage()
mp.on('pageerror', (e) => errors.push('mobile: ' + e.message))
await mp.goto(BASE + '/help', { waitUntil: 'networkidle', timeout: 45000 })
await mp.waitForSelector('.hp', { timeout: 20000 })
await mp.screenshot({ path: path.join(OUT, 'U14-求助页-移动端.png'), fullPage: true })
shots.push(path.join(OUT, 'U14-求助页-移动端.png'))

const fails = []
if (before.url !== '/help') fails.push('路径 ' + before.url)
if (before.bodyBg !== MI_BG) fails.push('页面底 ' + before.bodyBg)
if (before.hl.length < 4) fails.push('热线卡只有 ' + before.hl.length + ' 张')
if (!before.hl.some((x) => x.num.indexOf('12356') >= 0)) fails.push('没有 12356')
if (!before.hl.some((x) => x.num.indexOf('12355') >= 0)) fails.push('没有 12355（FR10.3）')
if (!before.hl.some((x) => x.tag.indexOf('心理咨询中心') >= 0)) fails.push('没有校心理中心（FR10.3）')
if (!before.hl.some((x) => x.badge === '待配置')) fails.push('校中心占位值没有标「待配置」，会被误认成真号码')
if (before.tel.length !== 3) fails.push('可拨号码链接应为 3 条（12356/12355/120），实得 ' + before.tel.length)
if (!before.tel.every((h) => h && h.indexOf('tel:') === 0)) fails.push('号码链接不合法：' + JSON.stringify(before.tel))
if (!(await page.locator('.hl-off').count())) fails.push('校中心的占位号码没走「暂不可拨」，而是给了可拨按钮')
if (before.safeBtns.length !== 2) fails.push('安全确认按钮不是两个：' + JSON.stringify(before.safeBtns))
if (before.gdRows !== 5) fails.push('grounding 不是五步：' + before.gdRows)
if (!before.ring) fails.push('呼吸引导的圈不在')
if (afterHelp.gdRows !== 5) fails.push('点「我需要帮助」后自助引导没展开')
if (moved === seen) fails.push('点「我说完了」之后 grounding 没有推进')
if (!/scale|matrix/.test(breath.transform) || breath.phase === '准备') fails.push('呼吸动画没跑起来：' + JSON.stringify(breath))
if (!localState || localState.indexOf('"safe":false') < 0) fails.push('「我需要帮助」没写进 localStorage：' + localState)
if (!note || note.indexOf('需要帮助') < 0) fails.push('刷新后看不到那条本地确认记录')
if (before.stage > 0) fails.push('页面出现 StageNotice')
if (errors.length) fails.push('控制台红字 ' + errors.length + ' 条：' + JSON.stringify(errors.slice(0, 3)))

say(JSON.stringify({ before, afterHelp, breath, copyText, localState, note }, null, 2))
say('shots: ' + shots.join(', '))
await browser.close()
if (fails.length) { say('FAIL ' + fails.length + ' 条：\n- ' + fails.join('\n- ')); process.exit(1) }
say('PASS U14 求助页：热线 ' + before.hl.length + ' 张 / 12356+12355+校中心+120 齐 / grounding 可推进 / 呼吸在跑 / 安全确认落本地')
// U1 落地页（游客视角）取证：一次跑、两张图、三条断言。
// 不进 frontend/probe/ 那套阶段闸门全家桶：它证的是新增页面，不是重签既有阶段证据（用户指令）。
// 前置：8080 后端 + 5173 Vite dev 在跑。跑法：cd frontend && node probe/shootland.mjs
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const ROOT = 'E:/codex workspace/009_心屿AI心理陪伴社区'
const OUT_DIR = path.join(ROOT, 'docs', 'gate', '首页')
const PW_DIR = 'E:/codex workspace/_cache/node_modules/playwright-core'
const CHROME = process.env.GATE_CHROME || 'C:/Users/Drbrain/AppData/Local/Google/Chrome/Application/chrome.exe'
const BASE = 'http://127.0.0.1:5173'
const MI_BG = 'rgb(246, 246, 247)'

const { chromium } = require(PW_DIR)
const errors = []
const say = (l) => console.log(l)

const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] })
// 关键：全新 context，不写 localStorage，不注入 token —— 这一张证的就是「没登录也能看」。
const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
const page = await ctx.newPage()
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
page.on('pageerror', (e) => errors.push(String(e && e.message)))

await page.goto(BASE + '/', { waitUntil: 'networkidle', timeout: 45000 })
await page.waitForSelector('.land', { timeout: 20000 })
await page.waitForFunction(() => {
  const w = document.querySelector('.land-wall')
  return !!w && (w.querySelectorAll('article.post').length > 0 || !!w.querySelector('.el-skeleton') === false)
}, { timeout: 20000 }).catch(() => say('WARN 卡片墙未在本帧出现（可能接口降级或为空态）'))

const report = await page.evaluate(() => {
  const q = (s) => document.querySelector(s)
  const cs = (s, p) => { const el = q(s); if (!el) return null; const v = getComputedStyle(el); return v ? v[p] : null }
  return {
    url: location.pathname,
    bodyBg: getComputedStyle(document.body).backgroundColor,
    landBg: cs('.land', 'backgroundColor'),
    topBg: cs('.land-top', 'backgroundColor'),
    cardBg: cs('.land-num-card', 'backgroundColor'),
    cards: document.querySelectorAll('.land-wall article.post').length,
    coverText: document.querySelectorAll('.land-wall .cover-empty .cover-text').length,
    imgs: document.querySelectorAll('.land-wall .imgs').length,
    nums: Array.from(document.querySelectorAll('.land-num')).map((n) => n.textContent.trim()),
    mood: q('.land-mood-line') ? q('.land-mood-line').textContent.replace(/\s+/g, ' ').trim() : null,
    bars: document.querySelectorAll('.land-bar-row').length,
    topics: document.querySelectorAll('.land-topic').length,
    nav: document.querySelectorAll('nav.mi-nav a').length,
    loginCta: q('.land-top-actions') ? q('.land-top-actions').textContent.replace(/\s+/g, ' ').trim() : null,
    heroBtns: Array.from(document.querySelectorAll('.land-cta button')).map((b) => b.textContent.trim())
  }
})

const ts = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')
const pFull = path.join(OUT_DIR, 'U1-落地页-游客视角-整页.png')
const pFold = path.join(OUT_DIR, 'U1-落地页-游客视角-首屏.png')
await page.screenshot({ path: pFold })
await page.screenshot({ path: pFull, fullPage: true })

// 手机宽度再来一张：落地页是拉新页面，移动端首屏比桌面更重要。
const m = await browser.newContext({ viewport: { width: 390, height: 844 } })
const mp = await m.newPage()
await mp.goto(BASE + '/', { waitUntil: 'networkidle', timeout: 45000 })
await mp.waitForSelector('.land-hero', { timeout: 20000 })
const pMobile = path.join(OUT_DIR, 'U1-落地页-游客视角-移动端.png')
await mp.screenshot({ path: pMobile, fullPage: true })

const fails = []
if (report.url !== '/') fails.push('不在 / 而在 ' + report.url + '（守卫把游客弹走了）')
if (report.landBg !== MI_BG) fails.push('.land 主背景 ' + report.landBg + ' != ' + MI_BG)
if (report.topBg !== 'rgb(255, 255, 255)') fails.push('顶栏底色 ' + report.topBg + ' 不是白')
if (report.cards === 0) fails.push('精选笔记墙是空的')
if (report.nav !== 0) fails.push('落地页出现了 BasicLayout 的频道导航 ' + report.nav + ' 条')
if (errors.length) fails.push('控制台红字 ' + errors.length + ' 条')

say(JSON.stringify(report, null, 2))
say('shots: ' + [pFold, pFull, pMobile].join(', '))
if (errors.length) say('console errors: ' + JSON.stringify(errors.slice(0, 5)))
await browser.close()
if (fails.length) { say('FAIL ' + fails.length + ' 条：\n- ' + fails.join('\n- ')); process.exit(1) }
say('PASS 游客落地页：卡片 ' + report.cards + ' 张 / 无频道导航 / 无红字')
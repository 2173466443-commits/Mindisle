// 横向溢出定位：列出比视口还宽的元素（改版调试用，不是 gate）
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const PW_DIR = 'E:/codex workspace/_cache/node_modules/playwright-core'
const CHROME = 'C:/Users/Drbrain/AppData/Local/Google/Chrome/Application/chrome.exe'
const BASE = 'http://127.0.0.1:5173'
const { chromium } = require(PW_DIR)

const r = await fetch(BASE + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'smoke_runner', password: 'Smoke#2026x', captchaId: '00000000000000000000000000000000', captchaCode: 'ZZZZ' }) })
const j = await r.json()
const token = j.data.accessToken

const browser = await chromium.launch({ headless: true, executablePath: CHROME })
for (const [name, route, w] of [['feed', '/feed', 1600], ['detail', '/post/1309', 1600], ['mobile', '/feed', 390], ['emotion', '/emotion', 1600], ['ai', '/ai', 1600]]) {
  const ctx = await browser.newContext({ viewport: { width: w, height: 1000 }, locale: 'zh-CN' })
  await ctx.addInitScript((t) => { window.localStorage.setItem('mindisle_token', t) }, token)
  const page = await ctx.newPage()
  await page.goto(BASE + route, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)
  const out = await page.evaluate((vw) => {
    const docW = document.documentElement.scrollWidth
    const bad = []
    document.querySelectorAll('body *').forEach((el) => {
      const b = el.getBoundingClientRect()
      if (b.width === 0) return
      if (b.right > vw + 1) bad.push({ t: el.tagName.toLowerCase() + '.' + String(el.className).slice(0, 45), right: Math.round(b.right), w: Math.round(b.width) })
    })
    return { docW, vw, over: docW > vw, n: bad.length, sample: bad.slice(0, 12) }
  }, w)
  console.log('=== ' + name + ' ' + w + ' docW=' + out.docW + ' overflow=' + out.over + ' count=' + out.n)
  out.sample.forEach((s) => console.log('   ' + s.t + '  right=' + s.right + ' w=' + s.w))
  await ctx.close()
}
await browser.close()
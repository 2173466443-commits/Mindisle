// U15 通知偏好（FR9.4）取证：一次跑、四张图、一组断言。
// 与 shootnc.mjs 同一口径：只证本轮新增的设置卡，不重签既有阶段闸门。
// 前置：8080 后端（含本轮 jar）+ 5173 Vite dev 在跑。跑法：cd frontend && node probe/shootpref.mjs
import path from 'node:path'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const ROOT = 'E:/codex workspace/009_心屿AI心理陪伴社区'
const OUT_DIR = path.join(ROOT, 'docs', 'gate', '首页')
const PW_DIR = 'E:/codex workspace/_cache/node_modules/playwright-core'
const CHROME = process.env.GATE_CHROME || 'C:/Users/Drbrain/AppData/Local/Google/Chrome/Application/chrome.exe'
const BASE = 'http://127.0.0.1:5173'
const ACCT = { username: 'smoke_runner', password: 'Smoke#2026x' }

const { chromium } = require(PW_DIR)
const errors = []
const say = (l) => console.log(l)
const shot = (n) => path.join(OUT_DIR, 'U15-通知偏好-' + n + '.png')

async function apiLogin() {
  const r = await fetch('http://127.0.0.1:8080/api/auth/login', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ ...ACCT, captchaId: '00000000000000000000000000000000', captchaCode: 'ZZZZ' })
  })
  const j = await r.json()
  return j.data && j.data.accessToken
}
const token = await apiLogin()
if (!token) { say('登录取不到 token：环境问题，不拍图'); process.exit(2) }

const snap = (page) => page.evaluate(() => {
  const rows = Array.from(document.querySelectorAll('.pref-row')).map((li) => {
    const sw = li.querySelector('.el-switch')
    const lab = li.querySelector('.pref-label')
    const hint = li.querySelector('.pref-hint') || li.querySelector('.pref-reason')
    return {
      label: lab ? lab.textContent.trim() : '',
      hint: hint ? hint.textContent.replace(/\s+/g, ' ').slice(0, 40) : '',
      checked: sw ? sw.classList.contains('is-checked') : null,
      disabled: sw ? sw.classList.contains('is-disabled') : null,
      locked: li.classList.contains('is-locked'),
      dirty: li.classList.contains('is-dirty')
    }
  })
  const q = (s) => document.querySelector(s)
  const t = (s) => { const e = q(s); return e ? e.textContent.replace(/\s+/g, ' ').trim() : null }
  const btn = q('.prefs-foot button')
  return {
    rowCount: rows.length, rows,
    foot: t('.prefs-foot .dim'),
    lead: t('.prefs-head .dim'),
    saveDisabled: btn ? !!btn.disabled : null,
    items: document.querySelectorAll('.nc-item').length,
    bell: !!q('.mi-bell')
  }
})

const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] })
const ctx = await browser.newContext({ viewport: { width: 1600, height: 1200 } })
await ctx.addInitScript((t) => { window.localStorage.setItem('mindisle_token', t) }, token)
const page = await ctx.newPage()
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
page.on('pageerror', (e) => errors.push(String(e && e.message)))

await page.goto(BASE + '/notifications', { waitUntil: 'networkidle', timeout: 45000 })
await page.waitForSelector('.prefs', { timeout: 20000 })
await page.waitForFunction(() => document.querySelectorAll('.pref-row').length === 8, { timeout: 20000 })
  .catch(() => say('WARN 八个开关没画满'))

const open = await snap(page)
await page.screenshot({ path: shot('八格开关'), fullPage: true })

// 交互 1：把「赞」关掉 —— 应当长出「待保存」标记，保存按钮从灰变亮
const likeRow = page.locator('.pref-row').filter({ hasText: '赞' }).first()
await likeRow.locator('.el-switch').click()
await page.waitForTimeout(400)
const dirty = await snap(page)
await page.screenshot({ path: shot('待保存改动') })

// 交互 2：点保存 —— 用后端回执重画，底部那句话应当说「已保存 1 个开关」
await page.locator('.prefs-foot button').click()
await page.waitForTimeout(1400)
const saved = await snap(page)
await page.screenshot({ path: shot('已保存'), fullPage: true })

// 交互 3：置灰的那三档点不动（真去点一下「危机关怀」，既不变化也不进草稿）
const crisisRow = page.locator('.pref-row').filter({ hasText: '危机关怀' }).first()
await crisisRow.locator('.el-switch').click({ force: true }).catch(() => {})
await page.waitForTimeout(400)
const afterLocked = await snap(page)

// 收尾还原：把「赞」再打开并保存，夹具账号不留关闭态（顺带证反向也能存）
await likeRow.locator('.el-switch').click()
await page.waitForTimeout(300)
await page.locator('.prefs-foot button').click()
await page.waitForTimeout(1400)
const restored = await snap(page)

// 手机宽度一张：设置页是「关掉打扰」最常发生的地方
const mob = await browser.newContext({ viewport: { width: 390, height: 844 } })
await mob.addInitScript((t) => { window.localStorage.setItem('mindisle_token', t) }, token)
const mp = await mob.newPage()
mp.on('console', (c) => { if (c.type() === 'error') errors.push('mobile: ' + c.text()) })
mp.on('pageerror', (e) => errors.push('mobile: ' + String(e && e.message)))
await mp.goto(BASE + '/notifications', { waitUntil: 'networkidle', timeout: 45000 })
await mp.waitForSelector('.prefs', { timeout: 20000 })
await mp.screenshot({ path: shot('移动端'), fullPage: true })

const by = (s, name) => s.rows.find((r) => r.label.indexOf(name) === 0)
const fails = []
if (open.rowCount !== 8) fails.push('开关不是八格，实得 ' + open.rowCount)
if (!open.lead || open.lead.indexOf('仍然会出现在') < 0) fails.push('页首那句「关的是打扰不是记录」没显示：' + open.lead)
const lockedNames = open.rows.filter((r) => r.locked).map((r) => r.label)
if (lockedNames.join(',') !== '系统,审核结果,危机关怀') fails.push('置灰的不是那三档，实得 ' + JSON.stringify(lockedNames))
if (open.rows.some((r) => r.locked && !r.disabled)) fails.push('有置灰的档位没禁用开关')
if (open.rows.some((r) => !r.locked && r.disabled)) fails.push('可关的档位被禁用了')
if (open.rows.some((r) => !r.checked)) fails.push('开页时八格应当全是接收')
if (open.saveDisabled !== true) fails.push('没有改动时保存按钮却是亮的')
if (!open.bell) fails.push('顶栏铃铛不在，说明主框架没套上')
if (!by(dirty, '赞') || by(dirty, '赞').dirty !== true) fails.push('关掉「赞」没有长出待保存标记')
if (!dirty.foot || dirty.foot.indexOf('1 个开关待改动') < 0) fails.push('底部没说出待改动数量：' + dirty.foot)
if (dirty.saveDisabled) fails.push('有改动时保存按钮还是灰的')
if (!by(saved, '赞') || by(saved, '赞').checked !== false || by(saved, '赞').dirty) fails.push('保存后「赞」没停在关闭态')
if (!saved.foot || saved.foot.indexOf('已保存 1 个开关') < 0) fails.push('保存回执那句话没出现：' + saved.foot)
if (afterLocked.rows.some((r) => r.locked && r.dirty)) fails.push('点置灰档位竟然进了草稿')
if (afterLocked.foot && afterLocked.foot.indexOf('待改动') >= 0) fails.push('点置灰档位后底部冒出待保存话术：' + afterLocked.foot)
if (!by(restored, '赞') || by(restored, '赞').checked !== true) fails.push('还原「赞」没成功，夹具账号会被留在关闭态')
if (errors.length) fails.push('控制台红字 ' + errors.length + ' 条：' + JSON.stringify(errors.slice(0, 3)))

say(JSON.stringify({
  openRows: open.rows.map((r) => r.label + '/' + (r.locked ? '置灰' : '可关') + '/' + r.hint),
  openFoot: open.foot, dirtyFoot: dirty.foot, savedFoot: saved.foot, restoredFoot: restored.foot,
  items: open.items
}, null, 2))
say('shots: ' + [shot('八格开关'), shot('待保存改动'), shot('已保存'), shot('移动端')].join(', '))
await browser.close()
if (fails.length) { say('FAIL ' + fails.length + ' 条：\n- ' + fails.join('\n- ')); process.exit(1) }
say('PASS U15 通知偏好：八格齐 / 三档置灰点不动 / 改动可存可改回 / 回执那句话是真的')


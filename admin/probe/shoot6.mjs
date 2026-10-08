// 阶段 6（管理端 A1–A9）真浏览器取证 —— 手册 §9.4 第 4 条：页面全部可交互、主题统一、无 console 红字
// 注：手册那条原文写于深色时代，且签的是**用户端** U3/U4/U5/U6；管理端的主题口径见下面 MI_BG 的说明。
//
// 与 frontend/probe/shootgate.mjs（阶段 3）同一套骨架、同一套判据，理由也同一句：
// jsdom / REST 探针的 PASS 不能当成 §6.4 第 4 条的勾，「页面在真浏览器里到底长什么样」必须看图。
//
// 但这一轮的判据比阶段 3 多了两条，因为管理端的失败形状不同：
//   ① 每个页面查 .err 元素个数（各视图的失败横幅都挂在 .err 上，v-else / v-if 才出现）。
//      管理端最怕的是「表格画得很整齐，其实一个接口都没回来」——那种图肉眼看不出来，.err 数得出来。
//   ② 两条链路图不是「跳得过去」，而是「目标页把我来的路上想看的东西筛出来了」：
//      A6 越权解匿被拒（ADMIN 点「接口自检」拿到 10003）→ 自动跳转 A9 且 result=DENIED 命中那一行。
//      这条链拍下来之前先记 DENIED 总数，拍完再记一次，**必须 +1**——
//      「留痕写了 ≠ 留痕存在」只有用数字钉住才算证过（FR8.4）。
//
// 前置：8080（后端，dev 关验证码）与 5174（admin Vite dev）都在跑。
// 跑法：cd admin && node probe/shoot6.mjs
//      换浏览器 / 换地址：GATE_CHROME=<chrome.exe 绝对路径> GATE_BASE=http://127.0.0.1:5174 node probe/shoot6.mjs
// 产物：docs/gate/阶段6/*.png + console-evidence.log + shot-manifest.json + 清单.md
//
// 凭据一律从仓库里的闸门探针正则现取（frontend/probe/admingate6.mjs），本文件不写口令，
// 输出与产物里也不出现口令：夹具账号密码进过一次日志就再也擦不干净。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(HERE, '..', '..')          // 项目根（admin 的上一级）
const GATE = process.env.GATE_SRC || path.join(ROOT, 'frontend', 'probe', 'admingate6.mjs')
const OUT_DIR = path.join(ROOT, 'docs', 'gate', '阶段6')
const PW_DIR = 'E:/codex workspace/_cache/node_modules/playwright-core'
const CHROME = process.env.GATE_CHROME ||
  'C:/Users/Drbrain/AppData/Local/Google/Chrome/Application/chrome.exe'
const BASE = process.env.GATE_BASE || 'http://127.0.0.1:5174'
const DESKTOP = { width: 1600, height: 1000 }        // 手册 §12：论文截图统一尺寸
// 管理端主题：深色工作台 #0E1626。需求 Q9 于 2026-09-30 的改判（深色治愈 → 明亮简约·小红书式）
// **只覆盖用户端 5173**，管理端 5174 不在改判范围内（admin/src/styles/theme.css 至今仍是深色令牌块）。
// 所以本探针的「漏白」方向与用户端探针（shootgate/recgate7 已反转为「深色残留」）**故意相反**，
// 不是漏改：管理端下白底表面才是缺陷，深底才是正确形态。
const MI_BG = 'rgb(14, 22, 38)'                      // #0E1626 午夜蓝（管理端现行主题，见上）

const SURFACES = [
  '.el-card', '.mi-card', '.el-dialog', '.el-popover', '.el-popper', '.el-message',
  '.el-message-box', '.el-input__wrapper', '.el-textarea__inner', '.el-select-dropdown',
  '.el-radio-button__inner', '.el-tag', '.el-tabs__content', '.el-loading-mask', '.el-drawer',
  '.el-descriptions', '.el-table', '.el-table__header', '.el-timeline-item', '.el-form-item__label'
]

const log = []
const say = (line) => { log.push(line); console.log(line) }
const shots = []
let leakCount = 0
let errorCount = 0
let checkFailCount = 0
// MARK：本轮唯一标记串。解匿理由必须带它，否则复跑时会把上一轮留下的夹具
// 当成「这一次点击产生的那一行 DENIED」——正文类断言没有本轮记号就等于没写。
const MARK = String(Date.now()).slice(-6)

// ---------- 0 凭据现取 ----------
function creds () {
  const s = fs.readFileSync(GATE, 'utf8')
  const pick = (name) => {
    const m = new RegExp("const " + name + " = '([^']+)'").exec(s)
    if (!m) throw new Error('在 ' + path.basename(GATE) + ' 里找不到 const ' + name + '，凭据现取失败')
    return m[1]
  }
  return { SUPER: pick('SUPER'), ADMIN: pick('ADMIN'), PWD: pick('PWD'), CID: pick('CAPTCHA_ID') }
}

// ---------- 1 API（走 5174 的 /api 代理，和被测页面完全同一条路径） ----------
async function api (method, p, token, body) {
  const headers = {}
  if (token) headers.Authorization = 'Bearer ' + token
  if (body) headers['Content-Type'] = 'application/json;charset=utf-8'
  const r = await fetch(BASE + p, { method, headers, body: body ? JSON.stringify(body) : undefined })
  rlObserve(r.headers.get('X-RateLimit-Remaining'))
  let j = null
  try { j = await r.json() } catch (e) { j = null }
  return { http: r.status, code: j ? j.code : null, data: j ? j.data : null, msg: j && j.msg }
}

async function adminLogin (username, C) {
  const r = await api('POST', '/api/admin/auth/login', null, {
    username, password: C.PWD, captchaId: C.CID, captchaCode: 'ZZZZ'
  })
  if (!r.data || !r.data.accessToken) {
    throw new Error(username + ' 管理端登录失败 code=' + r.code + ' msg=' + r.msg + '（环境问题，不拍图）')
  }
  return r.data
}

// ---------- 2 取真数据：编号一律现查 ----------
async function discover () {
  const C = creds()
  const sup = await adminLogin(C.SUPER, C)
  const adm = await adminLogin(C.ADMIN, C)
  const SUPER = sup.accessToken
  const ADMIN = adm.accessToken

  // 回读身份这条接口本身就是本轮拍图的前提：注入 token 之后 localStorage 里没有 profile，
  // 角色判定只能靠 GET /api/admin/me 重建。它挂了，下面所有「SUPER 才有」的控件都会假绿。
  const meS = await api('GET', '/api/admin/me', SUPER)
  const meA = await api('GET', '/api/admin/me', ADMIN)
  if (!meS.data || meS.data.role !== 'SUPER') throw new Error('/api/admin/me 回读不到 SUPER：' + JSON.stringify(meS))
  if (!meA.data || meA.data.role !== 'ADMIN') throw new Error('/api/admin/me 回读不到 ADMIN：' + JSON.stringify(meA))

  const deniedBefore = await api('GET',
    '/api/admin/logs?action=REVEAL_ANONYMOUS&result=DENIED&page=1&size=5', ADMIN)
  if (deniedBefore.code !== 0) throw new Error('DENIED 基线读取失败 code=' + deniedBefore.code)

  // 解匿链路要的目标：一个真有匿名别名、且不是管理员自己的账号。
  // 不写死 userId：闸门每跑一次都会新注册账号，写死的编号下一轮就是 20001。
  const users = await api('GET', '/api/admin/users?page=1&size=30', SUPER)
  let target = null
  for (const u of (users.data && users.data.list) || []) {
    if (u.role !== 'USER') continue
    const d = await api('GET', '/api/admin/users/' + u.id, SUPER)
    const aliases = d.data && d.data.aliases
    if (aliases && aliases.length) { target = { userId: Number(u.id), nickname: u.nickname, aliasId: Number(aliases[0].id), aliasName: aliases[0].aliasName }; break }
  }
  if (!target) throw new Error('翻了 30 个 USER 账号没有一个有匿名别名，解匿链路拍不了（夹具没跑 admingate6？）')

  const tickets = await api('GET', '/api/admin/tickets?page=1&size=10', SUPER)
  const tl = (tickets.data && tickets.data.list) || []
  const ticketId = tl.length ? Number(tl[0].ticket ? tl[0].ticket.id : tl[0].id) : null

  const audit = await api('GET', '/api/admin/audit/tasks?status=PENDING&page=1&size=10', SUPER)
  const al = (audit.data && audit.data.list) || []

  const posts = await api('GET', '/api/admin/content/posts?page=1&size=10', SUPER)
  const pl = (posts.data && posts.data.list) || []

  const stats = await api('GET', '/api/admin/dashboard/stats', SUPER)
  return {
    C, SUPER, ADMIN, supUser: sup.user, admUser: adm.user,
    deniedBefore: Number(deniedBefore.data.total),
    deniedFirstId: (deniedBefore.data.list && deniedBefore.data.list[0]) ? Number(deniedBefore.data.list[0].id) : 0,
    target, ticketId, auditRows: al.length, postRows: pl.length,
    usersTotal: Number(users.data.total),
    auditTotal: Number(audit.data.total),
    postsTotal: Number(posts.data.total),
    stats: stats.data || {}
  }
}
// ---------- 3 每张图的机器断言 ----------
// 返回：主背景、漏白表面、页面文字量、可见 .err 横幅（管理端专用判据）
async function audit (page, label) {
  const found = await page.evaluate((sels) => {
    const bad = []
    const bg = getComputedStyle(document.body).backgroundColor
    const visible = (el) => {
      const r = el.getBoundingClientRect()
      if (r.width < 6 || r.height < 6) return false
      const cs = getComputedStyle(el)
      return !(cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity) === 0)
    }
    for (const s of sels) {
      document.querySelectorAll(s).forEach((el) => {
        if (!visible(el)) return
        const cs = getComputedStyle(el)
        const m = /rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/.exec(cs.backgroundColor)
        if (!m) return
        if (m[4] !== undefined && parseFloat(m[4]) === 0) return
        if (Number(m[1]) >= 248 && Number(m[2]) >= 248 && Number(m[3]) >= 248) {
          bad.push(s + ' -> ' + cs.backgroundColor + ' [' + String(el.className).slice(0, 58) + ']')
        }
      })
    }
    const errNodes = Array.prototype.filter.call(document.querySelectorAll('.err'), visible)
    return {
      bg, bad, errBanners: errNodes.length,
      errText: errNodes.map((e) => e.innerText.replace(/\s+/g, ' ').slice(0, 90)),
      text: (document.getElementById('app') || document.body).innerText.replace(/\s+/g, ' ').trim().length
    }
  }, SURFACES)
  if (found.bg !== MI_BG) {
    leakCount++
    say('  !! ' + label + ' 主背景不是午夜蓝：' + found.bg + '（管理端现行深色工作台 #0E1626；需求 Q9 的浅色改判只覆盖用户端 5173）')
  }
  if (found.bad.length) {
    leakCount += found.bad.length
    say('  !! ' + label + ' 漏白表面 ' + found.bad.length + ' 处：' + found.bad.slice(0, 6).join(' ; '))
  }
  return found
}

// ---------- 3.5 限流自适应 ----------
// NFR7 写的 60 次/分（application.yml: rate-limit.user-per-minute）是产品规则，不是取证脚本的障碍。
// 一屏后台要发 8~12 个请求（/admin/me + 本页数据 + 侧边栏待办角标三件），17 张图连着拍必然越界，
// 于是上一轮 8 张红里有 7 张是 429 串扰——那是环境问题把页面拍成了残废，不是产品缺陷。
// 两条红线：① 绝不为了拍绿改后端限流配置；② 也绝不为拍绿把「接口挂了要显示 .err」这条判据关掉。
// 做法只有老实这一种：读后端自己发的 X-RateLimit-Remaining，配额不够就把这一张推到下一个窗口，
// 真撞上 429 就把这一张整张复拍（复拍后仍然 429 才判 FAIL，那是真故障）。
const WINDOW_MS = 60000
const RL_FLOOR = 15          // 剩余配额低于这个数就先等窗口翻转
const RL_PAGE_COST = 14      // 一次后台页面加载的请求数：/admin/me + 侧栏三件 + 探活 + 本页数据 + 深链附带
let rlWin = Math.floor(Date.now() / WINDOW_MS)
let rlLowest = 60

function rlTick () {
  const w = Math.floor(Date.now() / WINDOW_MS)
  if (w !== rlWin) { rlWin = w; rlLowest = 60 }
  return rlWin
}

function rlObserve (remaining) {
  const v = Number(remaining)
  if (!Number.isFinite(v)) return
  rlTick()
  if (v < rlLowest) rlLowest = v
}

/** 拍一张图大约要吃 need 个请求；配额不够就等到下一个整分钟窗口。 */
async function rlPace (need, tag) {
  rlTick()
  if (rlLowest - need >= RL_FLOOR) return false
  const waitMs = (rlWin + 1) * WINDOW_MS - Date.now() + 500
  say('  .. 限流自适应：本窗口剩余配额 ' + rlLowest + '，拍 ' + tag + ' 预计需要 ' + need +
    ' 次请求，等窗口翻转 ' + Math.ceil(waitMs / 1000) + 's（不改限流配置、不放宽判据）')
  await sleep(Math.max(waitMs, 1000))
  rlWin = Math.floor(Date.now() / WINDOW_MS)
  rlLowest = 60
  return true
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** 硬等到下一个整分钟窗口（复拍前用），别在同一个窗口里重试——那只会再吃一次 429。 */
async function rlWaitWindow () {
  const w = rlTick()
  const waitMs = (w + 1) * WINDOW_MS - Date.now() + 800
  say('  .. 等限流窗口翻转 ' + Math.ceil(waitMs / 1000) + 's')
  await sleep(Math.max(waitMs, 1200))
  rlWin = Math.floor(Date.now() / WINDOW_MS)
  rlLowest = 60
}

function watch (page, errs, allowHttp) {
  const throttled = { n: 0 }
  page.on('console', (m) => {
    if (m.type() !== 'error') return
    // 资源类失败本身不带 URL，没法定责；统一由下面的 response 通道记账，避免同一件事数两遍。
    if (/Failed to load resource/.test(m.text())) return
    errs.push('console.error: ' + m.text().slice(0, 200))
  })
  page.on('response', (res) => {
    const st = res.status()
    const u = res.url()
    rlObserve(res.headers()['x-ratelimit-remaining'])
    if (st === 429) throttled.n++
    if (st < 400) return
    if (/favicon|\.woff2?(\?|$)/.test(u)) return
    // allowHttp：越权自检那张图就是要看后端回 403。把「预期内的拒绝」写进白名单，
    // 而不是把整条判据关掉——关掉之后真正的 500 也会一起被放过。
    if ((allowHttp || []).indexOf(st) >= 0) { say('  ok 预期内 HTTP ' + st + ' ' + u.replace(BASE, '')); return }
    errs.push('HTTP ' + st + ' ' + u.replace(BASE, '') + ' [' + (res.headers()['content-type'] || '-') + ']')
  })
  page.on('pageerror', (e) => errs.push('pageerror: ' + String(e && e.message).slice(0, 200)))
  page.on('requestfailed', (r) => {
    const u = r.url()
    if (/favicon|\.woff2?(\?|$)/.test(u)) return
    errs.push('requestfailed: ' + u.slice(0, 120) + ' ' + (r.failure() ? r.failure().errorText : ''))
  })
  return throttled
}

/**
 * 定版画布的包围盒必须完整落在这一屏的视口里（手册 §9.4 D9）。
 * 这条判据是本轮被真 bug 逼出来的：六图判据（面板集合 / 标题 / 画布墨迹）全绿、
 * 17 张图 17 PASS，可 .screen 少写一行 box-sizing，1920 画布加 26px 左右内边距实际占位 1972px，
 * 乘 scale(0.8333) 后右边缘溢出版口 43px，被 .screen-wrap 的 overflow:hidden 整条切掉 ——
 * 23 点热力格和右下角「返回工作台」在截图里根本没出现，而判据一条都没红。
 * 「元素在不在」和「元素看不看得见」是两件事，所以这里既量画布，也点名画布右下角那两个元素。
 */
async function fitsViewport (p, label) {
  const i = await p.evaluate(() => {
    const el = document.querySelector('.screen')
    if (!el) return null
    const b = el.getBoundingClientRect()
    const cell = document.querySelector('.hcell:last-child')
    const back = document.querySelector('.screen .back')
    const cb = cell ? cell.getBoundingClientRect() : null
    const ab = back ? back.getBoundingClientRect() : null
    return {
      vw: window.innerWidth, vh: window.innerHeight,
      x: b.x, y: b.y, w: b.width, h: b.height,
      scrollW: document.documentElement.scrollWidth,
      cellRight: cb ? cb.right : null, cellText: cell ? cell.innerText.replace(/\s+/g, '/') : null,
      backRight: ab ? ab.right : null, backBottom: ab ? ab.bottom : null,
      backText: back ? back.innerText : null
    }
  })
  if (!i) return label + '：找不到 .screen 定版画布'
  const bad = []
  if (i.x < -1 || i.y < -1) bad.push('画布左上角跑到视口外（x=' + Math.round(i.x) + ' y=' + Math.round(i.y) + '）')
  if (i.x + i.w - i.vw > 1) bad.push('右边缘溢出版口 ' + (i.x + i.w - i.vw).toFixed(1) + 'px')
  if (i.y + i.h - i.vh > 1) bad.push('下边缘溢出版口 ' + (i.y + i.h - i.vh).toFixed(1) + 'px')
  if (i.scrollW > i.vw + 1) bad.push('文档横向滚动宽度 ' + i.scrollW + ' 大于视口 ' + i.vw)
  if (i.cellRight === null) bad.push('数不到 24 小时热力的最后一格（.hcell:last-child 不存在）')
  else if (i.cellRight > i.vw + 1) bad.push('23 点热力格（' + i.cellText + '）右边缘 ' + Math.round(i.cellRight) + ' 已在视口 ' + i.vw + ' 之外')
  if (i.backRight === null) bad.push('数不到「返回工作台」链接（.screen .back 不存在）')
  else {
    if (i.backRight > i.vw + 1) bad.push('「' + i.backText + '」右边缘 ' + Math.round(i.backRight) + ' 已在视口 ' + i.vw + ' 之外')
    if (i.backBottom > i.vh + 1) bad.push('「' + i.backText + '」下边缘 ' + Math.round(i.backBottom) + ' 已在视口 ' + i.vh + ' 之外')
  }
  if (!bad.length) {
    say('  ok ' + label + ' 定版画布 ' + Math.round(i.w) + 'x' + Math.round(i.h) + ' 完整落在视口内（scale 后无裁切）')
    return null
  }
  return label + ' 定版画布溢出版口：' + bad.join('；') + '（旧判据只数元素在不在，抓不到「在但被 overflow 切了」）'
}

async function take (page, errs, spec) {
  const a = await audit(page, spec.file)
  let ok = true
  const minText = spec.minText === undefined ? 120 : spec.minText
  if (a.text < minText) { ok = false; say('  !! ' + spec.file + ' 页面文字量只有 ' + a.text + ' 字，疑似没画出来') }
  if (a.errBanners > (spec.allowErr || 0)) {
    ok = false
    say('  !! ' + spec.file + ' 页面上有 ' + a.errBanners + ' 条失败横幅（.err）：' + a.errText.join(' || '))
  }
  if (spec.assert) {
    let msg = null
    try { msg = await spec.assert(page) } catch (e) { msg = 'assert 跑挂了：' + String(e.message).slice(0, 160) }
    if (msg) { ok = false; say('  !! ' + spec.file + ' DOM 判据不过：' + msg) } else say('  ok ' + spec.file + ' DOM 判据通过')
  }
  // 元素级截图：spec.el 存在时只拍那一块。本轮加它的原因很具体 ——
  // /dashboard 的「推荐链路健康度」卡在下半区，1600x1000 视口截图根本拍不到，
  // 于是出现过「判据绿 + 图上看不到 = 等于没有证据」。统一宽度口径不破：
  // 视口仍是 §12 的 1600x1000，只是这张 PNG 裁到那块卡的实宽。
  if (spec.el) {
    const loc = page.locator(spec.el).first()
    await loc.scrollIntoViewIfNeeded()
    await page.waitForTimeout(500)
    await loc.screenshot({ path: path.join(OUT_DIR, spec.file) })
  } else {
    await page.screenshot({ path: path.join(OUT_DIR, spec.file), fullPage: !!spec.full })
  }
  const realErrs = errs.splice(0, errs.length)
  if (realErrs.length) {
    errorCount += realErrs.length
    ok = false
    say('  !! ' + spec.file + ' 控制台红字 ' + realErrs.length + ' 条：' + realErrs.slice(0, 4).join(' || '))
  }
  if (!ok) checkFailCount++
  shots.push({
    file: spec.file, url: spec.url, desc: spec.desc, chars: a.text, bg: a.bg,
    leaks: a.bad.length, errBanners: a.errBanners, errors: realErrs.length, pass: ok
  })
  say('  -> ' + spec.file + '  [' + (ok ? 'PASS' : 'FAIL') + '] 文字 ' + a.text + ' / 漏白 ' + a.bad.length +
    ' / 失败横幅 ' + a.errBanners + ' / 红字 ' + realErrs.length)
  return ok
}

/** 整张复拍：这一屏被限流污染了，判据不过的不是产品而是脚本自己。最多三次。 */
async function shoot (browser, spec) {
  for (let attempt = 1; attempt <= 3; attempt++) {
    await rlPace(RL_PAGE_COST, spec.file)
    const ok = await shootOnce(browser, spec, attempt)
    if (ok !== 'THROTTLED') return
    say('  .. ' + spec.file + ' 第 ' + attempt + ' 次整张作废：这一屏的请求被 60 次/分挡在门外，等到窗口翻转再复拍')
    await rlWaitWindow()
  }
}

async function shootOnce (browser, spec, attempt) {
  const context = await browser.newContext({ viewport: DESKTOP, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' })
  const tok = spec.token === 'super' ? d.SUPER : spec.token === 'admin' ? d.ADMIN : null
  if (tok) {
    // 只注入令牌，不注入身份：身份必须由 GET /api/admin/me 在服务端重建。
    // 这行就是本轮那个产品缺陷的复现条件——localStorage 里只有 token、没有 profile，
    // 如果 store 不回读，role 会退化成 GUEST、SUPER 专属按钮全消失，图会拍出误导结论。
    await context.addInitScript((t) => { window.localStorage.setItem('mindisle_admin_token', t) }, tok)
  }
  const page = await context.newPage()
  const errs = []
  const throttled = watch(page, errs, spec.allowHttp)
  page.setDefaultTimeout(45000)
  try {
    await page.goto(BASE + spec.url, { waitUntil: 'networkidle' })
    await page.waitForTimeout(700)
    if (spec.reload) {
      await page.reload({ waitUntil: 'networkidle' })
      await page.waitForTimeout(900)
    }
    if (spec.act) await spec.act(page)
    // 只要这一屏出现过 429，页面画出来的就是「半个产品」，拍进论文比不拍更糟。
    // 判据不迁就：作废重来，重来还挂就照实在的 FAIL 记。
    if (throttled.n > 0 && attempt <= 2) {
      say('  .. ' + spec.file + ' 捕获 ' + throttled.n + ' 个 HTTP 429（取证脚本自己把配额用完了）')
      return 'THROTTLED'
    }
    await take(page, errs, spec)
  } catch (e) {
    checkFailCount++
    say('  !! ' + spec.file + ' 整个没拍成：' + String(e.message).slice(0, 200))
    shots.push({ file: spec.file, url: spec.url, desc: spec.desc, chars: 0, bg: '-', leaks: 0, errBanners: 0, errors: 0, pass: false })
  }
  await context.close()
  return 'DONE'
}
// ---------- 4 开工 ----------
const { chromium } = require(PW_DIR)
if (!fs.existsSync(OUT_DIR)) fs.mkdirSync(OUT_DIR, { recursive: true })

const d = await discover()
say('# 取证账号 SUPER=' + d.supUser.username + '(id=' + d.supUser.id + ') / ADMIN=' + d.admUser.username +
  '(id=' + d.admUser.id + ') · /api/admin/me 回读通过')
say('# 解匿链路目标：用户 #' + d.target.userId + '（' + (d.target.nickname || '') + '）别名 #' +
  d.target.aliasId + '（' + d.target.aliasName + '）· DENIED 基线 total=' + d.deniedBefore +
  ' 最新 id=' + d.deniedFirstId)
say('# 现查数据量：用户 ' + d.usersTotal + ' · PENDING 审核 ' + d.auditTotal + ' 行 ' + d.auditRows +
  ' · 帖子 ' + d.postsTotal + ' 行 ' + d.postRows + ' · 工单首条 #' + d.ticketId)

const browser = await chromium.launch({ headless: true, executablePath: CHROME })

// 列表页统一判据：接口有数据而页面一行不画 = 前端把数据吃掉了；
// 接口 0 条而页面画了行 = 上一轮的残留或选择器打错。两种都算 FAIL。
const rowsAgree = (want) => async (p) => {
  const n = await p.locator('.el-table__row').count()
  if (want === 0) return n === 0 ? null : '接口 0 条，页面却画了 ' + n + ' 行（残留/选择器错）'
  if (n === 0) return '接口返回 ' + want + ' 条，页面一行都没画'
  return null
}

const specs = [
  {
    file: '01-A1登录页.png', url: '/login', token: null, minText: 60,
    desc: 'A1 管理员登录：图形验证码由后端签发（dev 关验证码，生产必开）',
    assert: async (p) => {
      const src = await p.locator('.cap-img').first().getAttribute('src')
      if (!src || src.indexOf('data:image/png;base64,') !== 0) return '验证码图片没拿到：' + String(src).slice(0, 40)
      return null
    }
  },
  {
    file: '02-F5刷新后身份仍有效.png', url: '/dashboard', token: 'super', reload: true,
    desc: '按 F5 之后顶栏仍是「闸门六超管 / SUPER」——身份由 GET /api/admin/me 回读，不靠前端缓存',
    assert: async (p) => {
      const role = (await p.locator('.top .who .el-tag').first().innerText()).trim()
      const nick = (await p.locator('.top .who .nick').first().innerText()).trim()
      if (role !== 'SUPER') return '刷新后 role 是 ' + role + '（期望 SUPER：说明身份回读没生效，界面退化成 GUEST）'
      if (nick === '未登录') return '刷新后顶栏写着「未登录」'
      return null
    }
  },
  {
    file: '03-A1运营工作台.png', url: '/dashboard', token: 'super',
    desc: 'A1 工作台：八张待办卡 + SLA 超时工单 + 审核队列五态 + 人均处理量',
    assert: async (p) => {
      // 现查 DashboardView.vue 的 todos 数组：8 张（待审/工单/举报/申诉 + SLA 超时/危机累计/今日活跃/词元）。
      // 判据原来写 4 张，是照手册旧稿记的数——这是「断言过期」不是「产品回归」。改成数量对齐 + 按标签点名，
      // 少任何一张真待办卡照样红，而且红的时候会说是哪一张，比单看一个数字更可查。
      const n = await p.locator('.dash .todo').count()
      if (n !== 8) return '待办卡数到 ' + n + ' 张，与 todos 定义的 8 张不一致'
      const txt = (await p.locator('.dash').first().innerText()).replace(/\s+/g, '')
      const miss = ['待人工审核', '未办结危机工单', '待处置举报', '待裁定申诉'].filter((k) => txt.indexOf(k) < 0)
      if (miss.length) return '待办卡里缺了「' + miss.join('、') + '」'
      // A9 推荐运维读数卡（任务 7.9 / 7.15 · 手册 §10.2）。后端 AdminRecController 两端点在
      // stage-5 就合进了主干，但 admin/src 里当时 0 处引用 /admin/rec/*——「接口实现了 + api 封装了」
      // 不等于「按钮有了」，这条判据就是为了钉住这个差别：读数口不在页面上，7.9 就不算交付。
      // 这里刻意不点「立即重算一轮」：重算是同步全量写 recommend_result / item_similarity 的真动作，
      // 它成功的证据是 docs/gate/阶段7/d7-log-excerpt.log 里那行后端摘要，不该混进工作台这张图；
      // 这张图证明的是「口点得到、数读得到」。
      const recCard = p.locator('[data-block="rec-ops"]')
      const rc = await recCard.count()
      if (rc !== 1) return '工作台推荐运维卡数到 ' + rc + ' 个（应恰好 1 个 [data-block="rec-ops"]）'
      const rtxt = (await recCard.first().innerText()).replace(/\s+/g, '')
      const need = ['推荐链路健康度与离线重算', '拉取状态', '立即重算一轮', '缓存行cacheRows',
        '相似对similarityRows', '缓存模式', '上次失败']
      const rmiss = need.filter((k) => rtxt.indexOf(k) < 0)
      if (rmiss.length) return '推荐运维卡缺这些读数或按钮：' + rmiss.join('、')
      const tagTxt = (await recCard.locator('.el-tag').first().innerText()).trim()
      if (!tagTxt) return '推荐运维状态灯文案为空——三色判据（读数失败 / 缓存 0 行 / 正常）必须说清当前是哪一档'
      if (tagTxt.indexOf('读数失败') >= 0) return '状态灯显示读数失败：' + tagTxt
      const cacheVal = (await recCard.locator('.el-descriptions__content').first().innerText()).trim()
      if (cacheVal === '—') return 'cacheRows 显示「—」，说明 /admin/rec/status 没回数'
      if (cacheVal === '0') say('  .. cacheRows=0：个性化未生效（全员走热度兜底），读数卡按 warning 显示，判据不判红')
      return null
    }
  },
  {
    file: '04-A3危机工单列表.png', url: '/tickets', token: 'super',
    desc: 'A3 危机工单：状态看板 + 六态筛选 + SLA 倒计时（FR9 危机响应）',
    assert: async (p) => {
      const n = await p.locator('.bcard').count()
      if (n < 3) return '看板卡只有 ' + n + ' 张'
      return rowsAgree(d.ticketId ? 1 : 0)(p)
    }
  },
  {
    file: '05-A3工单处置抽屉.png', url: '/tickets?ticketId=' + d.ticketId, token: 'super',
    desc: 'A3 深链直接开单：认领→处置→办结三步行在同一个抽屉里，时间线在下方',
    assert: async (p) => {
      const t = (await p.locator('.el-drawer__title').first().innerText()).trim()
      if (t.indexOf('#' + d.ticketId) < 0) return '工单抽屉标题不是这一单：' + t
      return null
    }
  },
  {
    file: '06-A4审核队列.png', url: '/audit', token: 'super',
    desc: 'A4 审核队列：命中词高亮 + 认领 + 批量裁决（先审后发 BR4）',
    assert: rowsAgree(d.auditRows)
  },
  {
    file: '07-A5内容管理-帖子.png', url: '/content', token: 'super',
    desc: 'A5 内容管理帖子 Tab：状态六态筛选、下架/恢复/置顶/加精',
    assert: rowsAgree(d.postRows)
  },
  {
    file: '08-A5内容管理-举报.png', url: '/content?tab=reports', token: 'super',
    desc: 'A5 举报 Tab：?tab=reports 深链落位（举报与申诉是两条状态机，不能混成一个角标）',
    assert: async (p) => {
      const t = (await p.locator('.el-tabs__item.is-active').first().innerText()).trim()
      if (t.indexOf('举报') < 0) return '活动 Tab 是「' + t + '」，深链 ?tab=reports 没落位'
      return null
    }
  },
  {
    file: '09-A6用户管理.png', url: '/users', token: 'super',
    desc: 'A6 用户检索：状态四态 + 禁言到期 + 注销冷静期都在列表里可见',
    assert: async (p) => {
      const role = (await p.locator('.top .who .el-tag').first().innerText()).trim()
      if (role !== 'SUPER') return '顶栏角色不是 SUPER：' + role
      return rowsAgree(d.usersTotal ? 1 : 0)(p)
    }
  },
  {
    file: '10-A6档案深链-SUPER可解匿.png', url: '/users?userId=' + d.target.userId, token: 'super',
    desc: 'A6 深链 ?userId= 直接开档案：别名表里出现「解匿」按钮（角色驱动控件，FR8.4）',
    assert: async (p) => {
      const t = (await p.locator('.el-drawer__title').first().innerText()).trim()
      if (t.indexOf('#' + d.target.userId) < 0) return '档案抽屉没开在这一行：' + t
      const n = await p.locator('.el-drawer').getByRole('button', { name: '解匿' }).count()
      if (n === 0) return 'SUPER 身份下别名行没有「解匿」按钮——身份回读或角色判定失效'
      return null
    }
  },
  {
    file: '11-A7参数配置-词库.png', url: '/configs', token: 'super',
    desc: 'A7 敏感词词库：分组/级别/动作/作用侧 + 版本与重建入口（FR7.1）',
    assert: async (p) => {
      const g = await p.locator('.gtag').count()
      if (g === 0) return '词库分组标签一个都没画（word-groups 接口空？）'
      return rowsAgree(1)(p)
    }
  },
  {
    file: '12-A7词库试审命中.png', url: '/configs', token: 'super',
    desc: 'A7 词库试审：原文 / 归一化后 / 命中词三件一起摆出来，这是「可回放」的证据',
    act: async (p) => {
      await p.getByRole('tab', { name: '词库试审' }).click()
      await p.waitForTimeout(500)
      await p.getByRole('button', { name: '放一条危机样例' }).click()
      await p.getByRole('button', { name: '试审', exact: true }).click()
      await p.waitForTimeout(1500)
    },
    assert: async (p) => {
      const hit = await p.locator('.hit-text').count()
      if (hit === 0) return '试审结果没画出来（.hit-text 不存在）'
      const n = await p.locator('.el-table__row').count()
      if (n === 0) return '这条危机文本一个词都没命中——词库或引擎有问题'
      return null
    }
  },
  {
    file: '13-A9操作日志.png', url: '/logs', token: 'super',
    desc: 'A9 操作日志：四个筛位（操作人/动作码/结果/时间）+ 动作码分布图 + 三个 CSV 导出',
    assert: async (p) => {
      const c = await p.locator('.chart canvas').count()
      if (c === 0) return '动作码分布图没画出来（.chart canvas 不存在）'
      const v = (await p.locator('.head .v').first().innerText()).trim()
      if (v === '—') return '解匿累计卡没取到数（/logs/reveals 失败）'
      return rowsAgree(1)(p)
    }
  },
  {
    file: '14-A2运行大屏.png', url: '/screen', token: 'super', minText: 100,
    desc: 'A2 数据大屏六图齐全：双轴趋势 / 情绪分布环形饼 / 24 小时热力 / 话题词云 / 年级柱状 / AI 用量，每块 echarts 图都验「画布真有墨」，再验定版画布在 1600x1000 与 1920x1080 两个视口下都不溢出版口',
    assert: async (p) => {
      // 手册 §9.3 六图判据（本轮新写）。旧判据只数 .screen .card >= 6 + .hcell === 24，
      // 那个形状抓不到两种真实故障：① 少一块图但顶栏数字卡补上了格数；② canvas 在、
      // 容器尺寸也对，但 series 一条都没画上去（数据源为空时 echarts 就画成一片空白）。
      // 所以这里改成：面板集合逐个点名 + 每张 echarts 数一次「非透明像素」，
      // 大屏底色是 CSS 画的、echarts 画布本身透明，于是「有墨」等价于「series 真画出来了」。
      const cards = await p.locator('.screen .card').count()
      if (cards < 6) return '大屏卡片只有 ' + cards + ' 张'
      const cells = await p.locator('.hcell').count()
      if (cells !== 24) return '24 小时热区数到 ' + cells + ' 格（应为 24）'
      const want = ['trend', 'emotion', 'hours', 'word', 'grade', 'ai']
      const got = await p.evaluate(() =>
        Array.prototype.map.call(document.querySelectorAll('.panel[data-chart]'), (el) => el.getAttribute('data-chart')))
      const miss = want.filter((k) => got.indexOf(k) < 0)
      const extra = got.filter((k) => want.indexOf(k) < 0)
      if (miss.length || extra.length) {
        return '六图面板集合不等于手册 §9.3：缺 [' + miss.join(',') + '] 多 [' + extra.join(',') + '] 实际 [' + got.join(',') + ']'
      }
      const titles = await p.evaluate(() =>
        Array.prototype.map.call(document.querySelectorAll('.panel[data-chart]'), (el) => {
          const h = el.querySelector('.ph')
          return [el.getAttribute('data-chart'), h ? h.innerText : '']
        }))
      const named = {
        trend: '近 14 日活跃与情绪指数', emotion: '情绪分布', hours: '24 小时情绪热力',
        word: '高频话题词云', grade: '年级聚合柱状', ai: 'AI 用量与费用'
      }
      const wrongTitle = want.filter((k) => {
        const row = titles.filter((t) => t[0] === k)[0]
        const txt = (row ? row[1] : '').replace(/\s+/g, '')
        return txt.indexOf(named[k].replace(/\s+/g, '')) < 0
      }).map((k) => {
        const row = titles.filter((t) => t[0] === k)[0]
        return k + ' 标题是「' + ((row ? row[1] : '').replace(/\s+/g, ' ').slice(0, 24) || '空') + '」'
      })
      if (wrongTitle.length) return wrongTitle.join('；') + '（六图要求逐个点名，图换了判据跟着换，不许只数个数）'
      const blank = await p.evaluate((keys) => keys.filter((key) => {
        const panel = document.querySelector('.panel[data-chart="' + key + '"]')
        const cv = panel && panel.querySelector('canvas')
        if (!cv) return true
        if (!cv.width || !cv.height) return true
        let ink = 0
        try {
          const d = cv.getContext('2d').getImageData(0, 0, cv.width, cv.height).data
          for (let i = 3; i < d.length; i += 4) { if (d[i] > 0) ink++ }
        } catch (e) { return true }
        return ink === 0
      }), ['trend', 'emotion', 'word', 'grade', 'ai'])
      if (blank.length) return '这些面板的画布是空白的（canvas 在但 0 墨迹像素）：' + blank.join('、')
      // —— 以下两条是本轮补的：墨迹判据全绿的同时，这张页面其实被切掉了 43px ——
      const f1 = await fitsViewport(p, '视口 ' + DESKTOP.width + 'x' + DESKTOP.height)
      if (f1) return f1
      // 手册 §9.4 D9「1920 全屏无错位」：改视口后等 resize 监听重算 scale 并重绘，再复测一次。
      // 测完必须恢复成 §12 的统一截图尺寸，否则这张 PNG 就不是 1600x1000 了。
      await p.setViewportSize({ width: 1920, height: 1080 })
      await p.waitForTimeout(1500)
      const f2 = await fitsViewport(p, '视口 1920x1080')
      await p.setViewportSize({ width: DESKTOP.width, height: DESKTOP.height })
      await p.waitForTimeout(800)
      if (f2) return f2
      return null
    }
  }
]

// 18 号：手册 §10.2 7.9「管理端按钮 + 读数」的真凭据。
// 这块卡在 /dashboard 下半区，视口截图拍不到，所以走元素级截图（take() 里的 spec.el）。
// 判据刻意不点「立即重算一轮」：那是同步全量写库动作，它的成功证据在
// docs/gate/阶段7/d7-log-excerpt.log（离线作业日志可查），不该由一张静态 PNG 顶替。
specs.push({
  file: '18-A1推荐链路健康度.png', url: '/dashboard', token: 'super', minText: 100,
  el: '[data-block="rec-ops"]',
  desc: 'A1 工作台下半区「推荐链路健康度与离线重算」卡（元素级截图）：状态灯 + 拉取状态 + 立即重算一轮两个按钮 + cacheRows/similarityRows/相似批次时刻/缓存模式/上次作业完成/结果TTL/作业占用/上次失败八项读数',
  assert: async (p) => {
    const n = await p.locator('[data-block="rec-ops"]').count()
    if (n !== 1) return '推荐链路卡数量是 ' + n + '（应为恰好 1）'
    const btns = await p.locator('[data-block="rec-ops"] button').count()
    if (btns < 2) return '推荐链路卡里只有 ' + btns + ' 个按钮（应有「拉取状态」「立即重算一轮」）'
    const lab = await p.locator('[data-block="rec-ops"] .el-descriptions__label').count()
    if (lab < 8) return '推荐链路读数只有 ' + lab + ' 项（八项读数没画全）'
    const txt = (await p.locator('[data-block="rec-ops"]').first().innerText()).replace(/\s+/g, '')
    if (txt.indexOf('读数失败') >= 0) return '推荐链路卡上写着读数失败：' + txt.slice(0, 60)
    const v = (await p.locator('[data-block="rec-ops"] .el-descriptions__content').first().innerText()).trim()
    if (v === '—') return 'cacheRows 读数是 —（/api/admin/rec/status 没取到数）'
    say('  ok rec-ops 读数 cacheRows=' + v + ' · 按钮 ' + btns + ' 个 · 读数 ' + lab + ' 项')
    return null
  }
})

for (const spec of specs) {
  say('# —— ' + spec.file + ' ' + spec.url)
  await shoot(browser, spec)
}
// ---------- 5 A6 → A9 越权留痕链路（同一个浏览器上下文里连着走三步）----------
// 这三张图必须是「一次真实动作」的连续证据，不能各开一个上下文补拍：
// 分开拍就只能拍到库里已有的 DENIED，拍不到「这次点击产生的那一行」。
async function revealChain (browser) {
  await rlPace(RL_PAGE_COST * 2, 'A6→A9 链路三张')
  const context = await browser.newContext({ viewport: DESKTOP, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' })
  await context.addInitScript((t) => { window.localStorage.setItem('mindisle_admin_token', t) }, d.ADMIN)
  const page = await context.newPage()
  const errs = []
  const throttled = watch(page, errs, [403])   // 这一条链路的正常产出就是 403
  let submits = 0                  // 这一轮真的发出去几次越权 POST，DENIED 的期望增量就跟着它，不写死 1
  page.setDefaultTimeout(45000)
  const u = '/users?userId=' + d.target.userId
  say('# —— 15-A6ADMIN对照-无解匿按钮.png ' + u + '（ADMIN 身份）')
  await page.goto(BASE + u, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1400)
  await take(page, errs, {
    file: '15-A6ADMIN对照-无解匿按钮.png', url: u, desc: '同一个档案页换成 ADMIN 登录：解匿按钮消失，只留「接口自检（预期 10003）」',
    assert: async (p2) => {
      const role = (await p2.locator('.top .who .el-tag').first().innerText()).trim()
      if (role !== 'ADMIN') return '顶栏角色是 ' + role + '（期望 ADMIN）'
      const sup = await p2.locator('.el-drawer').getByRole('button', { name: '解匿' }).count()
      if (sup > 0) return 'ADMIN 身份下也画出了「解匿」按钮——角色判定没生效，这张图会误导答辩'
      const self = await p2.locator('.el-drawer').getByRole('button', { name: /接口自检/ }).count()
      if (self === 0) return '别名行连「接口自检」入口都没有（别名表是空的？）'
      return null
    }
  })

  say('# —— 16-A6越权被拒当场提示.png（点「接口自检」）')
  await page.locator('.el-drawer').getByRole('button', { name: /接口自检/ }).first().click()
  await page.waitForTimeout(600)
  // 解匿必须有书面依据：理由为空时前端自己就拦下（10001 那条走不到后端），
  // 于是这一轮链路根本发不出请求，16/17 两张图必然空手而归。
  // 非 SUPER 的确认按钮文案是「仍然发起（自检越权路径）」——它本来就是设计给这条自检链路的。
  const dlg = page.locator('.el-dialog:visible').last()
  await dlg.locator('textarea').fill('探针越权自检-' + MARK)
  await rlPace(6, '越权自检那次 POST')
  submits++
  await dlg.getByRole('button', { name: /仍然发起|确认解匿/ }).click()
  await page.waitForTimeout(1800)
  await take(page, errs, {
    file: '16-A6越权被拒当场提示.png', url: u, minText: 100,
    desc: 'ADMIN 强行调用解匿接口：后端 10003，界面把「这次拒绝已经留下一行 DENIED」说出来，并给出跳去 A9 的入口',
    assert: async (p2) => {
      const n = await p2.locator('.denied').count()
      if (n === 0) return '没出现被拒提示（.denied）——大概率请求没发出去或前端把错误吞了'
      const txt = (await p2.locator('.denied').first().innerText()).replace(/\s+/g, ' ')
      if (txt.indexOf('DENIED') < 0) return '被拒提示里没写 DENIED：' + txt.slice(0, 90)
      return null
    }
  })

  say('# —— 17-A9深链筛中这条DENIED.png')
  await page.locator('.denied').getByRole('button', { name: /去日志页/ }).click()
  await page.waitForURL(/result=DENIED/, { timeout: 30000 })
  await page.waitForTimeout(2200)
  await take(page, errs, {
    file: '17-A9深链筛中这条DENIED.png', url: '/logs?action=REVEAL_ANONYMOUS&result=DENIED&operatorId=' + d.admUser.id,
    desc: 'A9 收到深链后真的把 result=DENIED 筛了出来，第一条就是刚才那次越权（标着「我」）',
    assert: async (p2) => {
      if (decodeURIComponent(p2.url()).indexOf('result=DENIED') < 0) return '地址栏里没有 result=DENIED：' + p2.url()
      const rows = await p2.locator('.list .el-table__row').count()
      if (rows === 0) return '深链筛完一张空表——接口没接 result 参数就等于这条链是哑链'
      const first = (await p2.locator('.list .el-table__row').first().innerText()).replace(/\s+/g, ' ')
      if (first.indexOf('#' + d.admUser.id) < 0) return '第一行不是刚才那个 ADMIN 的操作：' + first.slice(0, 80)
      if (first.indexOf('我') < 0) return '第一行没标出「（我）」，说明只看我的操作这条深链没接上'
      const hint = await p2.locator('.alert').count()
      if (hint === 0) return '缺少深链来源说明条（.alert）'
      return null
    }
  })
  if (throttled.n > 0) say('  .. 这条链路里出现过 ' + throttled.n + ' 个 HTTP 429，这三张图的可信度以这一行为准')
  await context.close()
  return submits
}

const submits = await revealChain(browser)

// 数字核对：这次点击必须在 admin_op_log 里多出一行 DENIED，且操作人就是那个 ADMIN。
const deniedAfter = await api('GET', '/api/admin/logs?action=REVEAL_ANONYMOUS&result=DENIED&page=1&size=5', d.ADMIN)
const afterTotal = Number(deniedAfter.data && deniedAfter.data.total)
const top = deniedAfter.data && deniedAfter.data.list && deniedAfter.data.list[0]
say('')
say('# DENIED 计数：跑前 ' + d.deniedBefore + ' → 跑后 ' + afterTotal + '（期望 +' + submits + '）· 最新一行 id=' +
  (top ? top.id : '-') + ' 操作人=' + (top ? top.operatorId : '-') + ' 角色=' + (top ? top.operatorRole : '-'))
if (submits === 0) {
  checkFailCount++
  say('  !! 链路数字判据不过：这一轮一次越权都没发出去（弹窗没开或理由没填），那三张图都不可信')
} else if (afterTotal !== d.deniedBefore + submits) {
  checkFailCount++
  say('  !! 链路数字判据不过：发了 ' + submits + ' 次越权，DENIED 却从 ' + d.deniedBefore + ' 变成 ' + afterTotal)
} else if (top && Number(top.operatorId) !== Number(d.admUser.id)) {
  checkFailCount++
  say('  !! 链路数字判据不过：新增 DENIED 的操作人不是 ' + d.admUser.id + ' 而是 ' + top.operatorId)
} else {
  say('  ok 链路数字判据通过：越权尝试 100% 留痕（FR8.4），且可被 result=DENIED 筛出')
}
await browser.close()

// ---------- 6 产物 ----------
const pass = shots.filter((s) => s.pass).length
say('')
say('---- 汇总：' + shots.length + ' 张截图，通过 ' + pass + ' 张；主背景/弹层漏白 ' + leakCount +
  ' 处；控制台红字 ' + errorCount + ' 条；断言失败 ' + checkFailCount + ' 条 ----')
fs.writeFileSync(path.join(OUT_DIR, 'console-evidence.log'), log.join('\n') + '\n', 'utf8')
fs.writeFileSync(path.join(OUT_DIR, 'shot-manifest.json'), JSON.stringify({
  at: new Date().toISOString(), base: BASE, chrome: CHROME, browser: browser.version(), viewport: DESKTOP,
  superAccount: { username: d.supUser.username, id: d.supUser.id, role: d.supUser.role },
  adminAccount: { username: d.admUser.username, id: d.admUser.id, role: d.admUser.role },
  chain: {
    targetUserId: d.target.userId, aliasId: d.target.aliasId,
    deniedBefore: d.deniedBefore, deniedAfter: afterTotal, newestDeniedLogId: top ? top.id : null,
    newestOperator: top ? top.operatorId : null
  },
  data: { usersTotal: d.usersTotal, auditTotal: d.auditTotal, postsTotal: d.postsTotal, ticketId: d.ticketId },
  leakCount, errorCount, checkFailCount, shots
}, null, 2) + '\n', 'utf8')

const md = []
md.push('# 阶段 6（管理端 A1–A9）真浏览器取证清单')
md.push('')
md.push('- 取证时间：' + new Date().toISOString() + '（UTC） / 本地 ' + new Date().toLocaleString('zh-CN'))
md.push('- 前端：' + BASE + '（Vite dev 8.3.0，管理端独立端口）· 后端：/api 代理到 127.0.0.1:8080')
md.push('- 浏览器：Chrome ' + browser.version() + '（headless，可执行文件走系统 Chrome，不下载 playwright 浏览器）· 视口 ' +
  DESKTOP.width + 'x' + DESKTOP.height + '（手册 §12 论文截图统一尺寸）')
md.push('- 账号：SUPER ' + d.supUser.username + '(#' + d.supUser.id + ') / ADMIN ' + d.admUser.username +
  '(#' + d.admUser.id + ')，口令不落任何产物；身份由 GET /api/admin/me 服务端回读')
md.push('- 判据：每张图四道闸（页面文字量 / 深色漏白 / 控制台红字 / 该页 DOM 判据）+ 管理端专属第五道（可见 .err 横幅必须为 0）')
md.push('- 主题口径（2026-10-08 写白，避免和用户端探针打架）：需求 Q9 于 2026-09-30 改判为「明亮简约 · 小红书式」，**改判范围只覆盖用户端 5173**；管理端 5174 沿用深色工作台（`admin/src/styles/theme.css` 的 `--mi-bg: #0E1626`），本清单的「漏白」判据因此仍是「白底表面 = 缺陷」，方向与用户端探针 shootgate.mjs / recgate7.mjs 已反转的「深色残留 = 缺陷」**故意相反**，不是漏改。若日后要把管理端也统一成浅色，这一条判据连同本目录 18 张图必须一起重签。')
md.push('- 结果：**' + shots.length + ' 张，通过 ' + pass + ' 张**；漏白 ' + leakCount + ' 处；红字 ' + errorCount + ' 条；断言失败 ' + checkFailCount + ' 条')
md.push('')
md.push('## A6 → A9 越权留痕链路（FR8.4 的唯一硬证据）')
md.push('')
md.push('1. `15`：同一张用户档案，ADMIN 登录时「解匿」按钮不存在，只留「接口自检（预期 10003）」入口；')
md.push('2. `16`：点它 → 后端 10003，界面当场说明「这次拒绝已写入 admin_op_log」；')
md.push('3. `17`：点「去日志页看这条 DENIED」→ A9 的 `result=DENIED` 深链把这一行筛到第一条，且标着「（我）」。')
md.push('')
md.push('数字核对：`REVEAL_ANONYMOUS + DENIED` 跑前 **' + d.deniedBefore + '** 行 → 跑后 **' + afterTotal +
  '** 行（本轮越权 POST ' + submits + ' 次，期望 +' + submits + '），最新一行 id=' + (top ? top.id : '-') + '，操作人=' + (top ? top.operatorId : '-') + '。')
md.push('')
md.push('## 逐张')
md.push('')
md.push('| 文件 | 路由 | 文字量 | 漏白 | .err 横幅 | 红字 | 结论 |')
md.push('| --- | --- | --- | --- | --- | --- | --- |')
for (const s of shots) {
  md.push('| `' + s.file + '` | `' + s.url + '` | ' + s.chars + ' | ' + s.leaks + ' | ' + s.errBanners +
    ' | ' + s.errors + ' | ' + (s.pass ? 'PASS' : '**FAIL**') + ' |')
}
md.push('')
md.push('## 每张照片要说明什么')
md.push('')
for (const s of shots) md.push('- **' + s.file + '**：' + s.desc)
md.push('')
// ---------- 5.5 逐条判据台账（脚本自生成，不手抄）----------
// 上一版这一节是人抄进 清单.md 的，抄出两个后果：① 覆写型脚本一跑就把这节冲掉；
// ② 「六图判据不在这条线上 ⇒ Gate6 判 ◐」这句话写死之后，六图判据补进了 UI 线，台账却没跟着改。
// 所以台账的每一句都必须由本脚本现读 admingate6.json 现算，求和对不上直接 throw——
// 宁可让取证跑挂，也不能让文档里出现一条对不上 JSON 的「事实」。
const GLABEL = {
  P1: '登录与角色闸', P2: '机审黑灰词', P3: '人审队列与原子认领', P4: '申诉', P5: '内容治理',
  P6: '解匿', P7: '禁言', P8: '危机工单', P9: '词库热更新', P10: '配置热生效',
  P11: '举报处置', P12: '导出', P13: '审计留痕', P14: '大屏读数', P15: '人审通过分支与D4计时'
}
const gateFile = path.join(OUT_DIR, 'admingate6.json')
md.push('## 逐条判据台账（脚本原样输出，非手抄）')
md.push('')
if (!fs.existsSync(gateFile)) {
  md.push('- ⚠️ 本目录缺 `admingate6.json`：REST 线台账无法自生成，这一节必须重跑 `frontend/probe/admingate6.mjs` 后再出。')
} else {
  const gj = JSON.parse(fs.readFileSync(gateFile, 'utf8'))
  if (!Array.isArray(gj.rows)) throw new Error('admingate6.json 没有 rows 数组，无法自生成台账')
  const groups = {}
  const fails = []
  for (const r of gj.rows) {
    const m = /^(P\d+)\b/.exec(String(r.name || ''))
    const k = m ? m[1] : 'OTHER'
    if (!groups[k]) groups[k] = { n: 0, pass: 0, fail: 0 }
    groups[k].n++
    if (r.ok) groups[k].pass++
    else { groups[k].fail++; fails.push(r.name) }
  }
  const gkeys = Object.keys(groups).sort((a, b) => (a === 'OTHER' ? 1 : b === 'OTHER' ? -1 : Number(a.slice(1)) - Number(b.slice(1))))
  const sumN = gkeys.reduce((a, k) => a + groups[k].n, 0)
  const sumPass = gkeys.reduce((a, k) => a + groups[k].pass, 0)
  const sumFail = gkeys.reduce((a, k) => a + groups[k].fail, 0)
  if (sumN !== gj.total) throw new Error('台账分组求和 ' + sumN + ' 与 JSON 声明 total ' + gj.total + ' 不一致')
  if (sumPass !== gj.pass || sumFail !== gj.fail) throw new Error('台账分组 PASS/FAIL 求和 ' + sumPass + '/' + sumFail + ' 与声明 ' + gj.pass + '/' + gj.fail + ' 不一致')
  md.push('- 来源：`frontend/probe/admingate6.mjs` 产出的 `admingate6.json`（REST 线，走 /api/admin/*，不经浏览器）· 运行号 `' +
    gj.run + '` · 取证时刻 ' + gj.at + ' · 目标 ' + gj.base)
  md.push('- 合计 **' + gj.total + ' 条判据：PASS ' + gj.pass + ' ／ FAIL ' + gj.fail + '**（' +
    gkeys.map((k) => k + ' ' + (GLABEL[k] || '') + ' ' + groups[k].n).join(' · ') + '）；分线求和 ' + sumN +
    ' = 合计（脚本断言，不等直接 throw，不是人核对）')
  md.push('- 限流复盘：`' + JSON.stringify(gj.throttle) + '`（产品口径 60 次/分不调高）')
  if (gj.d4) {
    md.push('- D4 全流程计时（P15 现量，脚本产出的数，不是手抄）：发布 → 等下一轮定时同步进队列 → 认领 → 人审通过 → 管理端回读 → 另一个真人读得到，' +
      '实测 **' + gj.d4.elapsedMs + 'ms**，手册 §9.4 上限 ' + (gj.d4.limitMs / 1000) + ' 秒。其中「发帖之后进队列」这一跳实测 ' +
      gj.d4.queueWaitMs + 'ms（同步作业 cron 一分钟一轮，这一跳天生异步；把它单独写出来，是防止台账把它当成 0 或干脆没算）；' +
      '裁决返回于 ' + gj.d4.adjudicateDoneMs + 'ms，终点取「另一个读者读得到」那一枪之后。靶子帖 id=' + gj.d4.postId +
      ' · 审核任务 id=' + gj.d4.taskId + ' · 作者 uid=' + gj.d4.authorId + '（' + (gj.d4.author2 || '?') + '）')
    md.push('- 可见性成对判据：通过之前同一个读者读不到、通过之后读得到，两条成对出现才算「放行」真的改变了可见性；' +
      '而管理端回读那条还要求 `publishedAt` 非空——状态改了而发布时间没改，广场游标把它排在末尾、' +
      '推荐新帖池按 `published_at >= since` 直接跳过，等于「状态机说它上线了，读取侧一致当它没发布」。')
  } else {
    md.push('- ⚠️ 本轮 `admingate6.json` 里没有 `d4` 字段：D4 的计时判据（P15）未跑，§9.4 第一条不能签 ☑。')
  }
  if (fails.length) md.push('- 本线 FAIL：' + fails.join(' ; '))
  md.push('- 口径：P14 那 1 条钉的是 `/api/admin/dashboard/stats` 的 12 个读数都有值；**§9.3 的六图判据在 UI 线**，' +
    '由本脚本 14 号判据逐个面板点名（`data-chart` 集合恰好六块 + 五块 echarts 画布各数一次非透明像素、24 小时热力按 DOM 网格数满 24 格 + 六个标题逐个点名），' +
    '本轮 UI 线 run=' + MARK + '。两条线各自记账、互不顶替：REST 线证明取数口，UI 线证明图上真画出来了。')
}
md.push('')
fs.writeFileSync(path.join(OUT_DIR, '清单.md'), md.join('\n'), 'utf8')
process.exitCode = (leakCount || errorCount || checkFailCount) ? 1 : 0
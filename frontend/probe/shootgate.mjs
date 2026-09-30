// Gate 3 真浏览器取证（手册 §6.4 第 4 条「前端全部可交互 + 主题统一 + 无 console 红字」）
//
// 为什么还要这个文件：domprobe 用的是 jsdom，它不含样式与布局。手册 §5.9 早写白过一句
// 「jsdom 的 PASS 不能当成 §6.4 第 4 条的勾」。这一步就是那半条没证过的证据：
// 页面在真浏览器里到底长什么样、Element Plus 的弹层有没有跟着主题走、控制台有没有红字。
//
// 它不只是「截图机器」，每张图都带三条机器断言，任何一条不过就 exit 1 ——
// 否则截图会成为一张「拍得挺好看但没人验证过内容」的装饰，那正是本项目的老毛病。
//
// 【判据口径 2026-09-30 随需求 Q9 改判】主背景由午夜蓝 #0E1626 改为浅灰 #f6f6f7，
// 「三通道 ≥248 即漏白」反转为「三通道 ≤60 即深色残留」。改判之后本探针**没有重跑**
// （用户指令：前面测试过的不用再测；且它会覆盖 docs/gate/阶段3 的历史取证），
// 所以那批截图与 console-evidence.log 记录的仍是深色版，不能当作浅色新主题的证据引用。
//
// 前置：8080（后端）与 5173（Vite dev）都在跑。
// 跑法：cd frontend && node probe/shootgate.mjs
//      只想改尺寸/换浏览器：GATE_CHROME=<chrome.exe 绝对路径> node probe/shootgate.mjs
// 产物：docs/gate/阶段3/*.png + console-evidence.log + 清单.md
//
// 依赖 playwright-core 装在 E 盘工具缓存里（与 jsdom 同一口径，不进 package.json，
// 因为它不属于产品工程，且它一旦被写进 dependencies，下一个接手的人会被误导去装 200MB 浏览器）。
// 浏览器用系统 Chrome，不跑 npx playwright install。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const HERE = path.dirname(fileURLToPath(import.meta.url))
const FE = path.resolve(HERE, '..')
const ROOT = path.resolve(FE, '..')
const OUT_DIR = path.join(ROOT, 'docs', 'gate', '阶段3')
const PW_DIR = 'E:/codex workspace/_cache/node_modules/playwright-core'
const CHROME = process.env.GATE_CHROME ||
  'C:/Users/Drbrain/AppData/Local/Google/Chrome/Application/chrome.exe'
const BASE = 'http://127.0.0.1:5173'
const DESKTOP = { width: 1600, height: 1000 }   // 手册 §12：论文截图统一 1600x1000
const MOBILE = { width: 390, height: 844 }
const ACCT = { username: 'smoke_runner', password: 'Smoke#2026x' }

// 会被 Element Plus 自己画背景的那些表面。theme.css 只覆盖了少数 CSS 变量，
// 所以「主题统一」这件事必须逐类量一遍，不能只看页面主体。
const SURFACES = [
  '.el-card', '.mi-card', '.el-dialog', '.el-popover', '.el-popper', '.el-message',
  '.el-message-box', '.el-input__wrapper', '.el-textarea__inner', '.el-select-dropdown',
  '.el-radio-button__inner', '.el-tag', '.el-tabs__content', '.el-loading-mask', '.el-drawer'
]
const MI_BG = 'rgb(246, 246, 247)'  // #f6f6f7 —— 需求 Q9 于 2026-09-30 改判（深色治愈 → 明亮简约·小红书式）后的主背景
const OLD_DARK_BG = 'rgb(14, 22, 38)' // #0E1626 改判前的午夜蓝：它再出现，就是深色主题回退
const DARK_MAX = 60                    // 三通道都 ≤60 的底色视为「深色表面」，浅色主题下不该出现在下面这些类上

const log = []
const say = (line) => { log.push(line); console.log(line) }
const shots = []
let leakCount = 0
let errorCount = 0
let checkFailCount = 0

async function api (method, p, token, body) {
  const headers = {}
  if (token) headers.Authorization = 'Bearer ' + token
  if (body) headers['Content-Type'] = 'application/json'
  const r = await fetch(BASE + p, { method, headers, body: body ? JSON.stringify(body) : undefined })
  let j = null
  try { j = await r.json() } catch (e) { j = null }
  return { status: r.status, code: j && j.code, data: j && j.data }
}

// ---------- 1 取真数据：编号一律现查，不写死（写死的编号在下一轮冒烟之后就是假图） ----------
async function discover () {
  const login = await api('POST', '/api/auth/login', null, {
    username: ACCT.username, password: ACCT.password,
    captchaId: '00000000000000000000000000000000', captchaCode: 'ZZZZ'
  })
  const token = login.data && login.data.accessToken
  if (!token) throw new Error('登录取不到 token（HTTP 层 code=' + login.code + '）：环境问题，不拍图')
  const topics = await api('GET', '/api/topics?page=0&size=12', token)
  const posts = await api('GET', '/api/posts?page=0&size=30', token)
  let list = (posts.data && posts.data.list) || []
  const withTopic = (topics.data || []).filter((t) => Number(t.postCnt) > 0)
  // 取样帖必须是「非本人 + 真有评论」的那一条：
  //   · 自己的帖不画举报按钮（.acts .act-report 由 !isMine 控制），拿它拍 07 会得到一张没有举报入口的图；
  //   · commentCnt=0 的帖拍出来评论区是空的 —— 「U4 评论区可用」这件事就不能靠一张空态图作证。
  // 首屏（最新 30 条）今天全是刚造的夹具、零评论，所以顺着 nextCursor 往深页找，最多再翻 6 页 ×50 条。
  const notMineOf = (arr) => arr.filter((x) => Number(x.authorId) !== Number(login.data.user.id))
  const bestCommented = (arr) => arr.slice().sort((a, b) => (b.commentCnt || 0) - (a.commentCnt || 0))[0]
  let cursor = posts.data && posts.data.nextCursor
  let pages = 0
  let withComment = bestCommented(notMineOf(list).length ? notMineOf(list) : list)
  while ((!withComment || Number(withComment.commentCnt) === 0) && cursor && pages < 6) {
    pages++
    const more = await api('GET', '/api/posts?size=50&beforeId=' + cursor, token)
    const ml = (more.data && more.data.list) || []
    if (!ml.length) break
    cursor = more.data && more.data.nextCursor
    list = list.concat(ml)
    const pool = notMineOf(list)
    withComment = bestCommented(pool.length ? pool : list)
  }
  const others = list.filter((p) => !p.anonymous && Number(p.authorId) !== Number(login.data.user.id))
  return {
    token,
    userId: Number(login.data.user.id),
    topicId: withTopic.length ? Number(withTopic[0].id) : (topics.data[0] ? Number(topics.data[0].id) : null),
    topicName: withTopic.length ? withTopic[0].name : '',
    postId: withComment ? Number(withComment.id) : (list[0] ? Number(list[0].id) : null),
    authorId: others.length ? Number(others[0].authorId) : null,
    total: posts.data ? Number(posts.data.total) : 0,
    commentCnt: withComment ? Number(withComment.commentCnt) : 0,
    topicCount: (topics.data || []).length
  }
}

// ---------- 2 每张图的机器断言 ----------
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
        // 判据方向 2026-09-30 反转：浅色主题下白卡是正确形态，上一版「三通道 ≥248 即漏白」
        // 会把每一张 .mi-card 都判成缺陷。现在要防的是「深色残留」——旧午夜蓝整块回来，
        // 或某个表面还留着近黑的底（三通道均 ≤ DARK_MAX）。
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
    say('  !! ' + label + ' 主背景不是浅灰底：' + found.bg + '（需求 Q9 已于 2026-09-30 改判为明亮简约 #f6f6f7）')
  }
  if (found.bad.length) {
    leakCount += found.bad.length
    say('  !! ' + label + ' 深色残留表面 ' + found.bad.length + ' 处：' + found.bad.slice(0, 6).join(' ; '))
  }
  return found
}

async function shoot (browser, spec, ctxOpts) {
  const context = await browser.newContext(Object.assign({
    viewport: spec.mobile ? MOBILE : DESKTOP,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai'
  }, ctxOpts || {}))
  if (spec.token) {
    await context.addInitScript((t) => { window.localStorage.setItem('mindisle_token', t) }, spec.token)
  }
  const page = await context.newPage()
  const errs = []
  page.on('console', (m) => {
    if (m.type() !== 'error') return
    // 「Failed to load resource」这条红字本身不带 URL，没法定责；
    // 资源类的失败改由下面的 response 通道统一记账（那里有 URL + 状态码），避免同一件事被数两遍。
    if (/Failed to load resource/.test(m.text())) return
    errs.push('console.error: ' + m.text().slice(0, 200))
  })
  page.on('response', (res) => {
    const st = res.status()
    const u = res.url()
    if (st < 400) return
    if (/favicon|\.woff2?(\?|$)/.test(u)) return
    errs.push('HTTP ' + st + ' ' + u.replace(BASE, '') + ' [' + (res.headers()['content-type'] || '-') + ']')
  })
  page.on('pageerror', (e) => errs.push('pageerror: ' + String(e && e.message).slice(0, 200)))
  page.on('requestfailed', (r) => {
    const u = r.url()
    if (/favicon|\.woff2?(\?|$)/.test(u)) return
    errs.push('requestfailed: ' + u.slice(0, 120) + ' ' + (r.failure() ? r.failure().errorText : ''))
  })
  page.setDefaultTimeout(45000)
  await page.goto(BASE + spec.url, { waitUntil: 'networkidle' })
  await page.waitForTimeout(700)
  if (spec.act) {
    try { await spec.act(page) } catch (e) { say('  -- ' + spec.file + ' 交互没做成：' + String(e.message).slice(0, 120)) }
  }
  const a = await audit(page, spec.file)
  let ok = true
  if (a.text < (spec.minText || 120)) { ok = false; say('  !! ' + spec.file + ' 页面文字量只有 ' + a.text + ' 字，疑似没画出来') }
  if (spec.count) {
    const n = await page.locator(spec.count.selector).count()
    if (n < spec.count.min) { ok = false; say('  !! ' + spec.file + ' 选择器 ' + spec.count.selector + ' 只数到 ' + n + '，期望 >= ' + spec.count.min) }
    else say('  ok ' + spec.file + ' ' + spec.count.selector + ' = ' + n)
  }
  if (spec.assert) {
    let msg = null
    try { msg = await spec.assert(page) } catch (e) { msg = 'assert 跑挂了：' + String(e.message).slice(0, 120) }
    if (msg) { ok = false; say('  !! ' + spec.file + ' 布局判据不过：' + msg) }
    else say('  ok ' + spec.file + ' 布局判据通过')
  }
  await page.screenshot({ path: path.join(OUT_DIR, spec.file), fullPage: !!spec.full })
  if (!ok) checkFailCount++
  if (errs.length) {
    errorCount += errs.length
    say('  !! ' + spec.file + ' 控制台红字 ' + errs.length + ' 条：' + errs.slice(0, 4).join(' || '))
  }
  shots.push({
    file: spec.file, url: spec.url, desc: spec.desc, chars: a.text,
    bg: a.bg, leaks: a.bad.length, errors: errs.length, pass: ok && !errs.length
  })
  say('  -> ' + spec.file + '  [' + (ok && !errs.length ? 'PASS' : 'FAIL') + '] 文字 ' + a.text + ' 字 / 深色残留 ' + a.bad.length + ' / 红字 ' + errs.length)
  await context.close()
}

// ---------- 3 开工 ----------
const { chromium } = require(PW_DIR)
if (!fs.existsSync(OUT_DIR)) fs.mkdirSync(OUT_DIR, { recursive: true })

const d = await discover()
say('# 取证账号 ' + ACCT.username + '(id=' + d.userId + ') · 广场公开帖 total=' + d.total +
  ' · 话题 ' + d.topicCount + ' 条 · 有帖话题 #' + d.topicId + '(' + d.topicName + ') · 取样帖 #' + d.postId +
  ' · 屿友 #' + d.authorId)

const browser = await chromium.launch({ headless: true, executablePath: CHROME })
say('# 浏览器 ' + browser.version() + ' · 可执行文件 ' + CHROME)

const specs = [
  { file: '01-U2登录页-未登录.png', url: '/login', token: null, desc: 'U2 登录页（浅色主题下的表单与危机入口），无 token' },
  { file: '02-路由守卫-未登录进广场被弹回登录.png', url: '/feed', token: null, desc: '未登录访问 /feed，守卫应换成登录页并把 redirect 带上', act: async (p) => { await p.waitForTimeout(400) } },
  { file: '03-U3广场-推荐流.png', url: '/feed', token: d.token, desc: 'U3 广场：发布器紧凑形态 + 来源切换 + 类型 Tab + 信息流', count: { selector: '.feed .row', min: 3 } },
  { file: '03b-U3广场-整页.png', url: '/feed', token: d.token, full: true, desc: 'U3 广场整页（含无限滚动已加载的全部内容，验证长页不破版）' },
  { file: '04-U3-b关注流.png', url: '/feed', token: d.token, desc: '切到「关注」来源：应换成关注流且地址栏带 ?type=follow', act: async (p) => { await p.locator('.mi-card.source .el-radio-button__inner', { hasText: '关注' }).first().click(); await p.waitForTimeout(900) } },
  { file: '05-U6话题详情.png', url: '/topic/' + d.topicId, token: d.token, desc: 'U6 话题圈：头图/发帖数/关注按钮 + 该话题的公开帖流', count: { selector: '.flow .mi-card.post', min: 1 } },
  { file: '06-U4帖子详情-评论区.png', url: '/post/' + d.postId, token: d.token, desc: 'U4 详情：正文 + 评论树 + 互动条', count: { selector: '.comments .row', min: (d.commentCnt > 0 ? 1 : 0) },
    // 同一屏两个「评论 N」必须是同一个数：卡片用 post.comment_cnt（只数已发布、含楼中楼），
    // 评论区标题以前用的是列表 total（可见的一级评论数，还把作者自己那条待审算进来）——
    // 于是出现「卡片 19 / 标题 20」，肉眼在这张图上看了三轮都没当回事，直到本轮逐张看图才发现。
    // 这条判据的作用就是把「两处同名数字必须同口径」钉成机器可判的，不再依赖看图的人较真。
    assert: async (p) => {
      const got = await p.evaluate(() => {
        const squeeze = (el) => (el ? String(el.textContent).replace(/\s+/g, '') : '')
        const card = Array.from(document.querySelectorAll('.stat')).map(squeeze).find((t) => /^评论/.test(t)) || ''
        return { card: card.replace(/^评论/, ''), head: squeeze(document.querySelector('.comments .h .n')) }
      })
      say('  .. 06 两处「评论」读数：卡片=' + got.card + ' / 评论区标题=' + got.head)
      if (!got.card) return '卡片上的「评论 N」没取到，判据无法执行（选择器 .stat 变了？）'
      if (!got.head) return '评论区标题的数字没取到（选择器 .comments .h .n 变了？）'
      if (got.card !== got.head) return '同一屏两个「评论」数字对不上：卡片=' + got.card + ' / 评论区标题=' + got.head
      return null
    } },
  { file: '07-U4详情-举报弹层.png', url: '/post/' + d.postId, token: d.token, desc: '举报对话框（FR8.5 先审后发的用户侧入口），验弹层跟主题一致 + 理由单选左对齐', act: async (p) => { const b = p.locator('.acts .act-report').first(); if (await b.count()) { await b.click(); await p.waitForTimeout(700) } },
    assert: async (p) => {
      const box = await p.evaluate(() => {
        const d = document.querySelector('.el-dialog__body')
        const r = document.querySelector('.reasons .el-radio')
        if (!d || !r) return null
        return { dl: d.getBoundingClientRect().left, rl: r.getBoundingClientRect().left }
      })
      if (!box) return '弹层或理由单选没找到（弹层没开成？）'
      const off = Math.round(box.rl - box.dl)
      if (off > 12) return '理由单选被 el-radio-group 的 align-items:center 推到中间：左偏移 ' + off + 'px（判据 ≤12px）'
      return null
    } },
  { file: '08-U5发布器.png', url: '/publish', token: d.token, desc: 'U5 发布器全形态：类型/可见性/话题/情绪与树洞提示' },
  { file: '09-U5发布器-话题预选.png', url: '/publish?topic=' + d.topicId, token: d.token, desc: '从话题页跳来：?topic= 预填应带进表单（闭环那条）' },
  { file: '10-搜索-帖子.png', url: '/search?q=' + encodeURIComponent('焦虑'), token: d.token, desc: '站内搜索帖子通道（LIKE 路径；全文通道未开）' },
  { file: '11-搜索-话题.png', url: '/search?m=topic&q=' + encodeURIComponent('焦虑'), token: d.token, desc: '站内搜索话题通道' },
  { file: '12-搜索-屿友.png', url: '/search?m=user&q=' + encodeURIComponent('冒烟'), token: d.token, desc: '站内搜索屿友通道' },
  { file: '13-搜索-空态.png', url: '/search?q=' + encodeURIComponent('zzz这条一定搜不到zzz'), token: d.token, desc: '空态文案：搜不到不等于不存在（全文通道未开的诚实提示）' },
  { file: '14-U12我的帖子.png', url: '/me/posts', token: d.token, desc: 'U12 我的：私密/待审/未通过三态筛选' },
  { file: '15-U11屿友主页.png', url: '/user/' + d.authorId, token: d.token, desc: 'U11 他人主页：公开帖列表 + 关注按钮' },
  { file: '16-顶栏通知铃铛.png', url: '/feed', token: d.token, desc: '顶栏铃铛弹层（T3.11-b 站内通知），验 popover 跟主题一致', act: async (p) => { const b = p.locator('.mi-bell').first(); if (await b.count()) { await b.click(); await p.waitForTimeout(900) } } },
  { file: '17-危机求助页.png', url: '/help', token: null, desc: '免登录可达的危机求助页（FR10：12356 转介）' },
  // 404 页按设计就只有两行字，120 字那条「疑似没画出来」的门槛对它不适用，单给它一个门槛
  { file: '18-404页.png', url: '/no-such-page-xyz', token: d.token, minText: 20, desc: 'NotFound：直接敲一个不存在的地址不该白屏' },
  { file: '19-移动端-广场.png', url: '/feed', token: d.token, mobile: true, desc: '390x844 手机视口下的广场（响应式基线，不承诺移动端产品）' },
  { file: '20-移动端-话题详情.png', url: '/topic/' + d.topicId, token: d.token, mobile: true, desc: '390x844 话题详情' }
]

for (const spec of specs) {
  say('# —— ' + spec.file + ' ' + spec.url)
  try {
    await shoot(browser, spec, spec.mobile ? { isMobile: true, hasTouch: true, deviceScaleFactor: 2 } : null)
  } catch (e) {
    checkFailCount++
    say('  !! ' + spec.file + ' 整个没拍成：' + String(e.message).slice(0, 180))
    shots.push({ file: spec.file, url: spec.url, desc: spec.desc, chars: 0, bg: '-', leaks: 0, errors: 0, pass: false })
  }
}
await browser.close()

const pass = shots.filter((s) => s.pass).length
say('')
say('---- 汇总：' + shots.length + ' 张截图，通过 ' + pass + ' 张；主背景/弹层深色残留 ' + leakCount + ' 处；控制台红字 ' + errorCount + ' 条；断言失败 ' + checkFailCount + ' 条 ----')
fs.writeFileSync(path.join(OUT_DIR, 'console-evidence.log'), log.join('\n') + '\n', 'utf8')
fs.writeFileSync(path.join(OUT_DIR, 'shot-manifest.json'), JSON.stringify({
  at: new Date().toISOString(), base: BASE, chrome: CHROME, browser: browser.version(),
  viewport: DESKTOP, mobile: MOBILE, account: ACCT.username, accountId: d.userId,
  samples: { postTotal: d.total, topicId: d.topicId, postId: d.postId, authorId: d.authorId },
  leakCount, errorCount, checkFailCount, shots
}, null, 2) + '\n', 'utf8')
process.exitCode = (leakCount || errorCount || checkFailCount) ? 1 : 0
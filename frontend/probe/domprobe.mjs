// 前端 DOM 级取证（任务 T3.13 第二批 · 手册 §6.2 U11/U12）。
//
// 为什么要有这个文件：npm run build 只证明「能编译」，docs/smoke.mjs 只证明「接口对」。
// 中间那段——「组件真的渲染出这些字段、点了 Tab 真的换了查询、匿名帖真的点不进作者」——
// 一直是文档里的写白项。本机没有可用的真实浏览器，但 jsdom + 编译后的探针包能把这段补上：
// 组件是真实的组件、请求是真实的 XHR、走的是真实的 Vite 代理，只是渲染目标不是浏览器而是 jsdom。
//
// 跑法（两个前置都得在）：
//   1) 后端 8080 已启动；
//   2) 前端 dev server 已启动：cd frontend && npm run dev
//   node probe/domprobe.mjs
// 产物落在 E 盘缓存目录，不进仓库。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const FE = path.resolve(HERE, '..')
const OUT = 'E:/codex workspace/_cache/mindisle-dbtmp/fe-probe'
const JSDOM_ENTRY = 'E:/codex workspace/_cache/node_modules/jsdom/lib/api.js'
const BASE = 'http://127.0.0.1:5173'

const results = []
const notes = []
function check(no, name, ok, detail) {
  results.push([no, ok ? 'PASS' : 'FAIL', name, detail === undefined ? '' : String(detail)])
}
function sleep(ms) {
  return new Promise(function (r) { setTimeout(r, ms) })
}

// ---------- 1 用同一套 Vite 配置编译探针入口（iife，好让 jsdom 直接执行） ----------
const vite = await import('vite')
const vuePlugin = (await import('@vitejs/plugin-vue')).default
const componentsMod = await import('unplugin-vue-components/vite')
const resolversMod = await import('unplugin-vue-components/resolvers')
const started = Date.now()
await vite.build({
  root: FE,
  configFile: false,
  mode: 'production',
  logLevel: 'warn',
  plugins: [
    vuePlugin(),
    componentsMod.default({ resolvers: [resolversMod.ElementPlusResolver()], dts: false })
  ],
  resolve: { alias: { '@': path.join(FE, 'src') } },
  build: {
    outDir: OUT,
    emptyOutDir: true,
    minify: false,
    target: 'es2020',
    cssCodeSplit: false,
    chunkSizeWarningLimit: 20000,
    rollupOptions: {
      input: path.join(FE, 'probe', 'entry.js'),
      output: { format: 'iife', name: 'MindisleProbe', inlineDynamicImports: true, entryFileNames: 'probe.js' }
    }
  }
})
const bundlePath = path.join(OUT, 'probe.js')
const bundle = fs.readFileSync(bundlePath, 'utf8')
notes.push('bundle: ' + bundle.length + 'B / 编译 ' + (Date.now() - started) + 'ms / ' + bundlePath)

// ---------- 2 真登录拿真 token（只为注入 localStorage；页面里的请求由 jsdom 经 5173 代理发） ----------
const loginResp = await fetch(BASE + '/api/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    username: 'smoke_seen_20260921020038',
    password: 'Smoke#2026x',
    captchaId: '00000000000000000000000000000000',
    captchaCode: 'ZZZZ'
  })
})
const loginBody = await loginResp.json()
const token = loginBody && loginBody.data ? loginBody.data.accessToken : null
if (!token) {
  console.log('登录取不到 token（HTTP ' + loginResp.status + '），探针整体不跑：没有 token 的话每一条都会报 401，那是环境问题不是前端问题。')
  process.exitCode = 1
  for (const n of notes) console.log('# ' + n)
} else {
  notes.push('login via 5173 proxy: code=' + loginBody.code + ' tokenLen=' + token.length)
  await runProbe(bundle, token)
  // 第 11 段刻意放在最后：它会写库、还要另开一个 jsdom 窗口，
  // 不能让它把前面 1–10 段那些只读断言的环境搅浑。
  await runBellProbe(bundle)
  finish()
}

async function runProbe(bundleCode, accessToken) {
  const mod = await import(pathToFileURL(JSDOM_ENTRY).href)
  const JSDOM = mod.JSDOM
  const VirtualConsole = mod.VirtualConsole
  const logs = []
  const vc = new VirtualConsole()
  vc.on('jsdomError', function (e) { logs.push('jsdomError: ' + ((e && e.message) || String(e))) })
  vc.on('error', function () { logs.push('console.error: ' + Array.prototype.join.call(arguments, ' ')) })
  vc.on('warn', function () { logs.push('console.warn: ' + Array.prototype.join.call(arguments, ' ')) })

  const dom = new JSDOM('<!doctype html><html><head><meta charset="utf-8"></head><body><div id="app"></div></body></html>', {
    // 深链进 U12：这一条比「先挂 /feed 再 push」更硬，它同时验了路由守卫从 localStorage 认令牌这条路。
    url: BASE + '/me/posts',
    runScripts: 'dangerously',
    pretendToBeVisual: true,
    virtualConsole: vc
  })
  const w = dom.window
  // Element Plus 的几个组件会摸 IntersectionObserver / ResizeObserver / matchMedia，jsdom 没实现；
  // 探针不测可见性与动画，给空壳即可 —— 但必须是「空壳」而不是「不注入」，否则组件初始化就抛。
  w.IntersectionObserver = function IntersectionObserver() {
    this.observe = function () {}
    this.unobserve = function () {}
    this.disconnect = function () {}
  }
  w.ResizeObserver = function ResizeObserver() {
    this.observe = function () {}
    this.unobserve = function () {}
    this.disconnect = function () {}
  }
  w.matchMedia = function matchMedia() {
    return {
      matches: false, media: '', onchange: null,
      addListener: function () {}, removeListener: function () {},
      addEventListener: function () {}, removeEventListener: function () {},
      dispatchEvent: function () { return false }
    }
  }
  w.localStorage.setItem('mindisle_token', accessToken)

  const sc = w.document.createElement('script')
  sc.textContent = bundleCode
  w.document.head.appendChild(sc)

  const docText = function () {
    return w.document.body.textContent.replace(/\s+/g, ' ')
  }
  const cards = function () {
    return Array.prototype.slice.call(w.document.querySelectorAll('article.post'))
  }
  const cardTitles = function () {
    return cards().map(function (c) {
      const t = c.querySelector('.title')
      return t ? t.textContent.trim() : '?'
    })
  }
    // 卡片底部互动条上的按钮文本（「赞 3」「已收藏 1」这种），探针只读不点
  const actButtons = function () {
    return Array.prototype.slice.call(w.document.querySelectorAll('article.post .meta .act'))
      .map(function (e) { return e.textContent.replace(/\s+/g, ' ').trim() })
  }
  const badges = function () {
    return Array.prototype.slice.call(w.document.querySelectorAll('article.post .head .el-tag'))
      .map(function (e) { return e.textContent.trim() })
  }
  function countOf(list, value) {
    return list.filter(function (x) { return x === value }).length
  }
  async function until(fn, ms, label) {
    const t0 = Date.now()
    for (;;) {
      let ok = false
      try { ok = !!fn() } catch (e) { ok = false }
      if (ok) return true
      if (Date.now() - t0 > ms) {
        notes.push('TIMEOUT ' + label + ' 之后 DOM 文本=「' + docText().slice(0, 220) + '」')
        return false
      }
      await sleep(120)
    }
  }
  async function pickTab(label) {
    const group = Array.prototype.slice.call(w.document.querySelectorAll('.el-radio-button'))
      .find(function (x) { return x.textContent.trim() === label })
    if (!group) return false
    const input = group.querySelector('input')
    if (!input) return false
    input.click()
    return true
  }
  const route = function () {
    const r = w.__probeRouter.currentRoute.value
    return { name: r.name, path: r.fullPath }
  }

  check('1', '探针包挂载成功（createApp + pinia + router + ElementPlus 全跑通）',
    await until(function () { return w.__probeMounted === true }, 12000, 'mount'),
    'router=' + JSON.stringify(w.__probeRouter ? route() : null))
  // 深链首屏：初始导航要等路由守卫 + 懒加载的组件解析完，currentRoute 才离开 START 路由（path 还是 /）。
  // 所以这里必须轮询，不能挂载后立刻读一次 —— 上一版就是这么误报成「守卫没放行」的。
  const landed = await until(function () { return route().name === 'my-posts' }, 8000, 'deep-link')
  check('1', '深链 /me/posts 被路由守卫放行并停在 my-posts（没有被踢去登录页）',
    landed && route().path === '/me/posts', route().path)
  check('1', '浏览器地址栏停在 /me/posts（history 模式深链可用，不是 redirect 之后改了地址）',
    w.location.pathname === '/me/posts', w.location.href)

  const gotFour = await until(function () { return cards().length === 4 }, 10000, 'mine-4')
  check('2', 'U12 真渲染出 4 张卡片（后端 mine 回 4 条：42/41/39/40）', gotFour, 'titles=' + cardTitles().join('|'))
  check('2', 'U12 四条标题逐字来自数据库',
    cardTitles().join('|').indexOf('冒烟·仅自己可见') >= 0
    && cardTitles().join('|').indexOf('冒烟·转人工') >= 0
    && cardTitles().join('|').indexOf('冒烟·被看见') >= 0
    && cardTitles().join('|').indexOf('冒烟·匿名树洞') >= 0,
    cardTitles().join('|'))
  const b = badges()
  check('2', '状态徽标：已发布 x3 + 人工审核中 x1（status 字段真的接到了卡片上）',
    countOf(b, '已发布') === 3 && countOf(b, '人工审核中') === 1, b.join(','))
  check('2', '私密徽标只有 1 条「仅自己可见」（private 只有 post 39）',
    countOf(b, '仅自己可见') === 1, b.join(','))
  check('2', 'U12 计数行走页码模式的 total', docText().indexOf('我发过 4 条') >= 0,
    docText().slice(0, 160))
  check('2', 'U12 自己的帖子上没有「不感兴趣」按钮（dismissable=false）',
    docText().indexOf('不感兴趣') < 0 && w.document.querySelectorAll('article.post .hide').length === 0)
  const links = w.document.querySelectorAll('article.post .who.link')
  check('2', '作者名可点的卡片是 3 张而不是 4 张（匿名帖 authorId 为空 → 天然不可点）',
    links.length === 3, 'links=' + links.length)
  check('2', '读接口是 silent 的：没有弹出任何全局消息条',
    w.document.querySelectorAll('.el-message').length === 0)
  check('2', '「这一页还欠什么」写白段渲染', docText().indexOf('这一页还欠什么') >= 0)

  // ---------- 3 切 Tab：真的换了一次带 status 的请求 ----------
  await pickTab('未通过')
  const emptied = await until(function () { return docText().indexOf('这个状态下还没有帖子') >= 0 }, 10000, 'tab-rejected')
  check('3', '切到「未通过」Tab → 该账号没有 REJECTED 帖，界面给空态而不是留着上一份列表',
    emptied, cards().length + ' 张卡')
  await pickTab('人工审核中')
  const oneReview = await until(function () { return cards().length === 1 }, 10000, 'tab-review')
  check('3', '切到「人工审核中」→ 只剩待审那条（40）',
    oneReview && cardTitles().join('|').indexOf('冒烟·转人工') >= 0, cardTitles().join('|'))
  await pickTab('全部')
  const backFour = await until(function () { return cards().length === 4 }, 10000, 'tab-all')
  check('3', '切回「全部」重新拉回 4 条（reload 丢弃旧列表而不是叠加）', backFour)
  check('3', '整个切 Tab 过程没有冒全局错误条',
    w.document.querySelectorAll('.el-message').length === 0)

  // ---------- 4 从卡片作者名点进 U11 ----------
  const who = Array.prototype.slice.call(links).find(function (e) {
    return e.textContent.trim() === '冒烟可见性账号'
  })
  if (who) who.click()
  const jumped = await until(function () { return route().name === 'user-home' }, 8000, 'goto-user')
  check('4', '点作者名进 U11 主页，而不是进详情页（.who 上的 click.stop 生效）',
    jumped && route().path === '/user/23', route().path)
  const oneCard = await until(function () { return cards().length === 1 }, 10000, 'profile-1')
  check('4', 'U11 只渲染 1 张卡（公开 + 已过审 + 非匿名：post 41）',
    oneCard && cardTitles().join('|') === '冒烟·被看见', cardTitles().join('|'))
  check('4', 'U11 不出现匿名树洞那条（前端这条链路就是解匿面，测它比测后端更容易被忽略）',
    docText().indexOf('冒烟·匿名树洞') < 0)
  check('4', 'U11 标题按路由参数渲染', docText().indexOf('屿友 23 的主页') >= 0)
  // T3.6 之后这一页多了真东西：资料卡与关注按钮都接的是新接口，写白段也换了措辞
  check('4', 'U11 资料卡渲染（昵称 + 四个统计来自 GET /api/users/:id/profile）',
    docText().indexOf('公开帖子') >= 0 && docText().indexOf('获赞') >= 0
    && docText().indexOf('资料卡暂时取不到') < 0)
  check('4', '自己看自己不出现「关注」按钮，改出一行说明（不存在「关注自己」这种关系）',
    docText().indexOf('这是你自己的主页') >= 0)
  check('4', 'U11 写白段已改写为「刻意有什么、刻意没有什么」',
    docText().indexOf('这一页刻意有什么、刻意没有什么') >= 0)

  // ---------- 5 同一个组件实例换对象：watch 生效吗 ----------
  await w.__probeRouter.push('/user/99999999')
  const alertSeen = await until(function () { return docText().indexOf('看不到这个主页') >= 0 }, 10000, 'user-404')
  check('5', '主页 id 换成不存在的用户：旧卡片被清空并给专属告警（组件复用时不 watch 就会停在上一家）',
    alertSeen && cards().length === 0, cards().length + ' 张卡 / ' + route().path)

  // ---------- 6 地址栏手打非数字 id ----------
  await w.__probeRouter.push('/user/abc')
  await sleep(400)
  check('6', '/user/abc 给「id 不是数字」的专属提示，而不是发一个注定 404 的请求再显示空白',
    docText().indexOf('地址里的用户 id 不是数字') >= 0 && cards().length === 0)
  // ---------- 7 别人的主页：关注按钮必须存在（探针全程只读，不去点它） ----------
  const feedResp = await fetch(BASE + '/api/posts?size=50', { headers: { Authorization: 'Bearer ' + accessToken } })
  const feedBody = await feedResp.json()
  const others = ((feedBody.data && feedBody.data.list) || []).filter(function (x) { return x.authorId && Number(x.authorId) !== 23 })
  const otherId = others.length ? others[0].authorId : null
  if (!otherId) {
    check('7', '广场上取不到别人的 authorId，这一步没有样本可判', false, 'n=' + ((feedBody.data && feedBody.data.list) || []).length)
  } else {
    await w.__probeRouter.push('/user/' + otherId)
    // 等的是按钮本身，不是标题：标题是 route 参数的 computed，换人的瞬间就改完了，
    // 而资料卡要等一次 GET 才回来。拿标题当信号就会判到「上一个人的卡片」上 —— 第一版就是这么假失败的。
    const btnSeen = await until(function () {
      const b = Array.prototype.slice.call(w.document.querySelectorAll('.card-op button'))
      return b.length === 1 && b[0].textContent.replace(/\s+/g, '') === '关注'
    }, 10000, 'other-home-follow-btn')
    const btns = Array.prototype.slice.call(w.document.querySelectorAll('.card-op button'))
      .map(function (e) { return e.textContent.replace(/\s+/g, '').trim() })
    check('7', '别人主页出现一颗「关注」按钮（未关注态文案，且不是自己的主页）',
      btnSeen && btns.length === 1 && btns[0] === '关注', 'id=' + otherId + ' btns=' + JSON.stringify(btns))
    check('7', '别人主页资料卡取到了才画按钮：没有「资料卡暂时取不到」的降级行，统计行也在',
      docText().indexOf('资料卡暂时取不到') < 0 && docText().indexOf('获赞') >= 0)
    // 这一条断言的是「当前是未关注态」，顺带说明本探针的边界：全程只发 GET，一颗按钮也没点过，
    // 所以它不会把 user_follow 写脏；写路径的取证在 docs/smoke.mjs 第 15 步与 root 直连 SQL。
    check('7', '这一跳落在未关注态（文案是「关注」而不是「已关注」）',
      docText().indexOf('已关注') < 0)
  }

  // ---------- 8 广场卡片：互动条是否真的渲染（只读，不点击） ----------
  await w.__probeRouter.push('/')
  const feedCards = await until(function () { return cards().length > 0 }, 12000, 'feed-cards')
  const acts = actButtons()
  check('8', '广场每张卡片底部都有两颗互动按钮（赞 / 收藏），文本带后端回来的计数',
    feedCards && acts.length === cards().length * 2
    && acts.some(function (x) { return /^赞 \d+$/.test(x) })
    && acts.some(function (x) { return /^收藏 \d+$/.test(x) }),
    'cards=' + cards().length + ' acts=' + acts.slice(0, 4).join(','))
  const detailLinkOk = docText().indexOf('评论') >= 0
  check('8', '互动条没把「浏览/评论」挤掉（旧字段还在，只是位置让给了按钮）', detailLinkOk)

  // ---------- 9 U4 评论区：匿名树洞帖（post 141，19 条一级评论，其中一条是马甲发的） ----------
  const secRows = function () {
    return Array.prototype.slice.call(w.document.querySelectorAll('.comments .row'))
  }
  const secThreads = function () {
    return Array.prototype.slice.call(w.document.querySelectorAll('.comments article.thread'))
  }
  const secText = function (sel) {
    const el = w.document.querySelector(sel)
    return el ? el.textContent.replace(/\s+/g, ' ').trim() : ''
  }
  await w.__probeRouter.push('/post/141')
  const cmt141 = await until(function () { return secThreads().length === 19 }, 15000, 'comments-141')
  check('9', 'U4 详情页评论区真渲染出 19 棵一级楼（GET /api/posts/141/comments 走的是 Vite 代理）',
    cmt141, 'threads=' + secThreads().length + ' rows=' + secRows().length)
  check('9', '匿名评论显示马甲名：第一楼作者名以「匿名屿民·」开头',
    secText('.comments article.thread .row .who').indexOf('匿名屿民·') === 0,
    secText('.comments article.thread .row .who'))
  // 这条不是「页面上没画 id」：authorId 为 null 时出参里连字段都没有（Jackson NON_NULL），
  // 所以前端就算想画也拿不到 —— 解匿面在响应体这一层，不在 CSS 这一层。
  check('9', '翻到底之后不再给「查看更多」按钮，改出一行「共 19 条一级评论」',
    w.document.querySelectorAll('.comments .more').length === 0
    && docText().indexOf('共 19 条一级评论') >= 0, secText('.comments .pager'))
  check('9', '评论区加载全程没有弹全局消息条（读接口是 silent 的）',
    w.document.querySelectorAll('.el-message').length === 0)
  check('9', '发表框、字数计数、匿名勾选三样都在（没登录时才不画，这一版登录着）',
    w.document.querySelectorAll('.comments .box textarea').length === 1
    && w.document.querySelectorAll('.comments .send').length === 1
    && secText('.comments .len') === '0 / 1000', secText('.comments .len'))

  // ---------- 10 楼中楼：预览截断 + 展开整棵 + 两级压平（post 140，一号楼 5 条回复） ----------
  await w.__probeRouter.push('/post/140')
  const cmt140 = await until(function () { return secThreads().length === 1 }, 15000, 'comments-140')
  const previewRows = secRows().length
  check('10', '一号楼默认只给 3 条回复预览（后端 REPLY_PREVIEW=3，replyTotal=5）',
    cmt140 && previewRows === 4, 'rows=' + previewRows)
  const expandBtn = secText('.comments .expand')
  check('10', '预览不够就出现「查看 5 条回复」，数字来自后端 replyTotal 而不是页面自己数',
    expandBtn === '查看 5 条回复', expandBtn)
  const btn = w.document.querySelector('.comments .expand')
  if (btn) btn.click()
  const expandedAll = await until(function () { return secRows().length === 6 }, 12000, 'expand-140')
  check('10', '点展开 → 整棵子树 5 条回复都出来了（rootId 分支一次给完），按钮随即消失',
    expandedAll && w.document.querySelectorAll('.comments .expand').length === 0,
    'rows=' + secRows().length)
  check('10', '压平成两级：回复里有「回复 @某某」这句话，但没有第三层缩进',
    docText().indexOf('回复 @') >= 0
    && w.document.querySelectorAll('.comments .row.indent .row.indent').length === 0,
    secText('.comments article.thread .row.indent:nth-child(4) .text'))
  check('10', '「楼主」标只出现在本帖作者的评论上（authorIsPostOwner 真接到了界面）',
    Array.prototype.slice.call(w.document.querySelectorAll('.comments article.thread .el-tag'))
      .filter(function (x) { return x.textContent.trim() === '楼主' }).length === 2,
    'n=' + Array.prototype.slice.call(w.document.querySelectorAll('.comments .el-tag'))
      .filter(function (x) { return x.textContent.trim() === '楼主' }).length)
  check('10', '危机词那条评论正常显示（已被分级、工单已落库，但内容不隐藏：L2 不删帖）',
    docText().indexOf('想伤害自己') >= 0)
  check('10', '换帖之后评论区是干净重挂的：上一帖的 19 楼没有留在这页',
    secThreads().length === 1)

  const lastText = docText()
  dom.window.close()
  notes.push('tail DOM text: ' + lastText.slice(0, 120))
  notes.push('window logs: ' + (logs.length ? logs.slice(0, 8).join(' || ') : 'none'))
}

// ---------- 11 顶栏铃铛（任务 T3.11-b）：这个探针第一次真的「点一次写路径」 ----------
//
// 上面 1–10 段全程只发 GET、一颗按钮也没点过（当时的边界写在注释里：不污染夹具）。
// 于是「点铃铛到底会不会发列表请求」「点已读到底会不会把 is_read 写成 1」这两件事
// 只有 JUnit 和 docs/smoke.mjs 在证，界面这一环是空的。这一段把它补上。
// 代价是它确实会写库，所以所有写入都落在三个一次性账号身上（甲→乙、丙→乙）：
// 只有「乙」收通知，甲丙互不相干，夹具 user 23 与 post 39–42 一行都不碰。
async function runBellProbe(bundleCode) {
  const stamp = String(Date.now()).slice(-9)
  const nameA = "probe_ntf_a" + stamp
  const nameB = "probe_ntf_b" + stamp
  const nameC = "probe_ntf_c" + stamp

  async function api(method, p, token, body) {
    const headers = { "Content-Type": "application/json" }
    if (token) headers.Authorization = "Bearer " + token
    const resp = await fetch(BASE + p, {
      method: method, headers: headers, body: body ? JSON.stringify(body) : undefined
    })
    let json = null
    try { json = await resp.json() } catch (e) { json = null }
    return { status: resp.status, json: json }
  }
  async function register(username, nickname) {
    const r = await api("POST", "/api/auth/register", null, {
      username: username, password: "Smoke#2026x", nickname: nickname,
      captchaId: "00000000000000000000000000000000", captchaCode: "ZZZZ",
      agreeTerms: true, agreePrivacy: true, consentVersion: "v1.0", regSource: "probe-script"
    })
    const d = r.json && r.json.data ? r.json.data : {}
    return { token: d.accessToken || null, id: d.user ? Number(d.user.id) : null, status: r.status }
  }

  const A = await register(nameA, "探针通知甲")
  const B = await register(nameB, "探针通知乙")
  const C = await register(nameC, "探针通知丙")
  check("11", "注册三名一次性账号（甲/乙/丙），本段所有写入只落在这三个人身上",
    !!A.token && !!B.token && !!C.token && !!A.id && !!B.id && !!C.id,
    "a=" + A.id + " b=" + B.id + " c=" + C.id)
  if (!A.token || !B.token || !C.token) {
    notes.push("BELL PROBE 提前收工：注册没拿到 token（甲 " + A.status + " 乙 " + B.status + " 丙 " + C.status + "）")
    return
  }
  const fAB = await api("POST", "/api/users/" + B.id + "/follow", A.token, { action: "follow" })
  check("11", "甲关注乙 → changed=true（下面要点掉的那条未读就是它）",
    fAB.status === 200 && !!(fAB.json && fAB.json.data) && fAB.json.data.changed === true,
    fAB.status + " " + JSON.stringify((fAB.json && fAB.json.data) || {}).slice(0, 150))

  // 换一份登录态就得换一个窗口：令牌是挂载那一刻从 localStorage 读的，
  // 中途改 localStorage 不会让已经建好的 pinia store 重来一遍。
  const mod = await import(pathToFileURL(JSDOM_ENTRY).href)
  const JSDOM = mod.JSDOM
  const blogs = []
  const vc = new mod.VirtualConsole()
  vc.on("jsdomError", function (e) { blogs.push("jsdomError: " + ((e && e.message) || String(e))) })
  vc.on("error", function () { blogs.push("console.error: " + Array.prototype.join.call(arguments, " ")) })
  const dom = new JSDOM('<!doctype html><html><head><meta charset="utf-8"></head><body><div id="app"></div></body></html>', {
    url: BASE + "/feed", runScripts: "dangerously", pretendToBeVisual: true, virtualConsole: vc
  })
  const w = dom.window
  w.IntersectionObserver = function IntersectionObserver() {
    this.observe = function () {}; this.unobserve = function () {}; this.disconnect = function () {}
  }
  w.ResizeObserver = function ResizeObserver() {
    this.observe = function () {}; this.unobserve = function () {}; this.disconnect = function () {}
  }
  w.matchMedia = function matchMedia() {
    return { matches: false, media: "", onchange: null,
      addListener: function () {}, removeListener: function () {},
      addEventListener: function () {}, removeEventListener: function () {},
      dispatchEvent: function () { return false } }
  }
  w.HTMLElement.prototype.scrollTo = function scrollTo() {}
  w.Element.prototype.scrollIntoView = function scrollIntoView() {}
  w.localStorage.setItem("mindisle_token", B.token)
  const sc = w.document.createElement("script")
  sc.textContent = bundleCode
  w.document.head.appendChild(sc)

  const items = function () {
    return Array.prototype.slice.call(w.document.querySelectorAll(".mi-notify-item"))
  }
  const unreadItems = function () {
    return w.document.querySelectorAll(".mi-notify-item.is-unread")
  }
  const itemText = function (i) {
    const el = items()[i]
    return el ? el.textContent.replace(/\s+/g, " ").trim() : ""
  }
  const badge = function () {
    const e = w.document.querySelector(".mi-badge .el-badge__content")
    return e ? e.textContent.trim() : ""
  }
  const headText = function () {
    const e = w.document.querySelector(".mi-notify-count")
    return e ? e.textContent.replace(/\s+/g, " ").trim() : ""
  }
  const headBtns = function () {
    return Array.prototype.slice.call(w.document.querySelectorAll(".mi-notify-head button"))
      .map(function (b) { return b.textContent.replace(/\s+/g, "").trim() })
  }
  function docText2() { return w.document.body.textContent.replace(/\s+/g, " ") }
  async function until2(fn, ms, label) {
    const t0 = Date.now()
    for (;;) {
      let ok = false
      try { ok = !!fn() } catch (e) { ok = false }
      if (ok) return true
      if (Date.now() - t0 > ms) {
        notes.push("TIMEOUT " + label + " 之后 DOM 文本=「" + docText2().slice(0, 200) + "」")
        return false
      }
      await sleep(120)
    }
  }

  const mounted = await until2(function () { return w.__probeMounted === true }, 12000, "bell-mount")
  const onFeed = mounted && await until2(function () {
    return w.__probeRouter && w.__probeRouter.currentRoute.value.name === "feed"
  }, 8000, "bell-feed")
  check("11", "以乙的身份重新挂载并停在广场（顶栏每个页面都挂着，铃铛就在这一层）",
    onFeed, w.location.pathname)
  const bell = w.document.querySelector(".mi-bell")
  check("11", "已登录时顶栏画出铃铛（未登录那一块整棵 v-if 掉，不给游客留一个点开必 401 的空壳）",
    !!bell)

  // onMounted 里那次 refreshUnread 走的就是同一个 /api/notifications（size=1），
  // 但它刻意不把拿到的那一条塞进 items。下面两条一起钉这件事：
  // 红点已经亮了（说明轻量刷新真读到了），列表却还是没有行（说明它没顺手当列表用）。
  const dot1 = await until2(function () { return badge() === "1" }, 10000, "bell-badge")
  check("11", "红点由挂载时那次 size=1 的轻量刷新点亮：徽标显示 1", dot1, 'badge="' + badge() + '"')
  check("11", "还没点铃铛之前列表是空的：轻量刷新只取 unreadCount，不把那一条例塞进 items",
    items().length === 0, "items=" + items().length)

  if (bell) bell.click()
  const oneItem = await until2(function () { return items().length === 1 }, 12000, "bell-list-1")
  check("11", "点铃铛 → 真的发出 GET /api/notifications 并渲染出那一条（列表只在点开这一刻读）",
    oneItem, "items=" + items().length)
  const line1 = itemText(0)
  check("11", "文案来自后端 typeLabel+title：「探针通知甲 关注了你」，界面上不出现登录名 " + nameA,
    line1.indexOf("探针通知甲 关注了你") >= 0 && line1.indexOf("关注") >= 0 && line1.indexOf(nameA) < 0,
    line1)
  check("11", "未读那条带 is-unread（左侧色条是「没读过」唯一的视觉标记）",
    unreadItems().length === 1, "unread=" + unreadItems().length)
  check("11", "头部计数与按钮跟着未读数走：「1 条未读」+ 一颗「全部已读」",
    headText().indexOf("1 条未读") >= 0 && headBtns().indexOf("全部已读") >= 0,
    'head="' + headText() + '" btns=' + JSON.stringify(headBtns()))

  // ---------- 逐条已读：点一条通知 = 一次 POST /notifications/read + 一次跳转 ----------
  const firstLi = items()[0]
  if (firstLi) firstLi.click()
  const jumpedToA = await until2(function () {
    return w.__probeRouter.currentRoute.value.name === "user-home"
  }, 8000, "bell-jump")
  check("11", "点这条 follow 通知跳到甲的主页（跳转只认 refType=user，不猜文案）",
    jumpedToA && w.__probeRouter.currentRoute.value.params.id === String(A.id),
    "path=" + w.__probeRouter.currentRoute.value.path + " 应为 /user/" + A.id)
  const readOne = await until2(function () { return unreadItems().length === 0 && badge() === "" }, 8000, "bell-read-one")
  check("11", "点完这条就不亮了：is-unread 归零、徽标消失（markRead 回执里的 unreadCount 直接刷了红点）",
    readOne, 'badge="' + badge() + '" unread=' + unreadItems().length + ' items=' + items().length + ' head="' + headText() + '"')
  const srvAfterOne = await api("GET", "/api/notifications?size=20", B.token, null)
  check("11", "服务端同步认为已读：乙自己再读一次列表 unreadCount=0（界面不是自说自话）",
    srvAfterOne.status === 200 && Number(srvAfterOne.json.data.unreadCount) === 0,
    "unreadCount=" + (srvAfterOne.json && srvAfterOne.json.data ? srvAfterOne.json.data.unreadCount : "?"))

  // ---------- 再来一条未读，然后一键已读 ----------
  const fCB = await api("POST", "/api/users/" + B.id + "/follow", C.token, { action: "follow" })
  check("11", "丙再关注乙一次 → 又来一条未读（同一个人不能替别人把通知读完）",
    fCB.status === 200 && !!(fCB.json && fCB.json.data) && fCB.json.data.changed === true,
    fCB.status + " " + JSON.stringify((fCB.json && fCB.json.data) || {}).slice(0, 150))
  // 重新点开才会再读一次：@show 只在「由关到开」那一刻发，所以这里只能反复点，
  // 奇偶交给循环去试，而不是靠猜 popper 现在是 display:none 还是压根没渲染。
  let reopened = false
  for (let k = 0; k < 4 && !reopened; k++) {
    if (bell) bell.click()
    await sleep(400)
    reopened = await until2(function () { return items().length === 2 }, 3000, "bell-reopen-" + k)
  }
  check("11", "关掉再点开 → 重新读了一次列表，两条都在（reset 是替换不是叠加）",
    reopened, "items=" + items().length)
  check("11", "重读之后只有新来的那条亮着：上一轮的已读是真落库了，不是前端记在内存里",
    unreadItems().length === 1 && items().length === 2,
    "unread=" + unreadItems().length + " items=" + items().length)
  const allBtn = Array.prototype.slice.call(w.document.querySelectorAll(".mi-notify-head button"))
    .find(function (b) { return b.textContent.replace(/\s+/g, "") === "全部已读" })
  if (allBtn) allBtn.click()
  const allRead = await until2(function () { return unreadItems().length === 0 }, 8000, "bell-mark-all")
  check("11", "点「全部已读」→ 两条都熄灭，头部变成「暂无未读」，那颗按钮自己也跟着消失",
    allRead && headText().indexOf("暂无未读") >= 0 && headBtns().indexOf("全部已读") < 0,
    'head="' + headText() + '" btns=' + JSON.stringify(headBtns()))
  const srvAfterAll = await api("GET", "/api/notifications?size=20", B.token, null)
  const listAll = (srvAfterAll.json && srvAfterAll.json.data && srvAfterAll.json.data.list) || []
  check("11", "一键已读落到库里：两条 read 都是 true（第二次独立取证，不看界面）",
    srvAfterAll.status === 200 && listAll.length === 2 && listAll.every(function (x) { return x.read === true }),
    "n=" + listAll.length + " read=" + listAll.map(function (x) { return x.read }).join(","))
  check("11", "整段铃铛交互没有弹过全局错误条：读是 silent 的，写也没出错",
    w.document.querySelectorAll(".el-message").length === 0,
    "n=" + w.document.querySelectorAll(".el-message").length)
  notes.push("bell 一次性账号：" + nameA + "(" + A.id + ") " + nameB + "(" + B.id + ") " + nameC + "(" + C.id + ")")
  notes.push("bell 通知 id：" + listAll.map(function (x) { return x.id }).join(","))
  notes.push("bell window logs: " + (blogs.length ? blogs.slice(0, 6).join(" || ") : "none"))
  dom.window.close()
}

function finish() {
  let fails = 0
  console.log('')
  console.log('===== DOM PROBE（jsdom + 真实 Vite 代理 + 真实后端）=====')
  for (const r of results) {
    if (r[1] === 'FAIL') fails++
    console.log(r[1] + ' [' + r[0] + '] ' + r[2] + (r[3] ? '   <- ' + r[3] : ''))
  }
  console.log('---- 汇总：' + results.length + ' 项，失败 ' + fails + ' 项')
  for (const n of notes) console.log('# ' + n)
  process.exitCode = fails ? 1 : 0
}

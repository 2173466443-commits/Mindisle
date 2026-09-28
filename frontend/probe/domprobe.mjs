// 前端 DOM 级取证（任务 T3.13 第二批 · 手册 §6.2 U11/U12）。
//
// 为什么要有这个文件：npm run build 只证明「能编译」，docs/smoke.mjs 只证明「接口对」。
// 中间那段——「组件真的渲染出这些字段、点了 Tab 真的换了查询、匿名帖真的点不进作者」——
// 一直是文档里的写白项。jsdom + 编译后的探针包能把这段补上：组件是真实的组件、请求是真实的
// XHR、走的是真实的 Vite 代理，只是渲染目标不是浏览器而是 jsdom。
//
// 两条取证线的边界（2026-09-23 Gate3 那轮之后补，此前这里写的是「本机没有可用的真实浏览器」，已经不成立）：
// 真浏览器那一半在 probe/shootgate.mjs（Playwright 驱动 Chrome 拍 21 张图 + 读控制台）。别拿一条的绿替另一条背书——
// jsdom 不做 CSS 级联、不做真实字体度量、不滚动、不加载 favicon，所以「颜色错、对齐错、空态插画、501」这类
// **结构存在但视觉错误**的问题只有 shootgate 抓得到；反过来「点了 Tab 请求参数真的变了」这种要读几十个字段
// 的深水区断言，写在 jsdom 里比写在图里可维护。本轮把「卡片与评论区标题的『评论 N』必须同口径」两条各钉了一份。
//
// 跑法（两个前置都得在）：
//   1) 后端 8080 已启动；
//   2) 前端 dev server 已启动：cd frontend && npm run dev
//   node probe/domprobe.mjs
// 产物落在 E 盘缓存目录，不进仓库。
import fs from 'node:fs'
import { spawnSync } from 'node:child_process'
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

// ---------- 1.5 刷新演示夹具：树洞到期销毁会把「写死编号」的取证夹具自己带走 ----------
// 这一条不是装饰。T3.15 的 auto_destroy_at 是发帖时算好的**绝对时间**，读侧按「不晚于
// NOW 就当这条不存在」过滤。2026-09-28 那天 post 42 与 post 141 自然到期，domprobe 当场
// 红 10 条而产品逻辑一行都没错——所以探针前必须先跑一次幂等 seed（库里没有过期树洞时它
// ROW_COUNT()=0，空跑一趟），这样「红」才只剩「真红」这一种解释。
const ROOT = path.resolve(FE, '..')
const seedScript = path.join(ROOT, 'docs', 'seed-demo.mjs')
const seedRes = spawnSync('node', [seedScript, '--quiet'], { encoding: 'utf8' })
const seedLine = String(seedRes.stdout || '').replace(/\r/g, '').trim().split('\n').filter(Boolean).pop() || '(seed 一行都没输出)'
if (seedRes.error) {
  notes.push('seed: ERROR 起进程就失败：' + seedRes.error.message + ' —— 夹具没被刷新，本轮红先按这条查')
} else if (seedRes.status === 2) {
  notes.push('seed: SKIP(exit 2) ' + seedLine + ' —— 环境不齐（多半是仓库外那份 root 凭据不在），不是产品问题')
} else if (seedRes.status !== 0) {
  notes.push('seed: FAIL(exit ' + seedRes.status + ') ' + seedLine + ' —— 夹具状态不可信，本轮红先按这条查')
} else {
  notes.push('seed: OK(exit 0) ' + seedLine)
}

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
  // 第 11、12 段刻意放在最后：它们都会写库、都要另开一个 jsdom 窗口，
  // 不能让它们把前面 1–10 段那些只读断言的环境搅浑。
  await runBellProbe(bundle)
  await runSourceSearchProbe(bundle)
  await runTopicProbe(bundle)
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
  // 先回到一个「资料卡一定出得来」的主页，再从有卡片切到 abc：watch 只认有效编号那一支时，
  // 上一个人的卡片会连那颗「关注」按钮一起留在屏上，而按钮用的已经是地址栏里那个读不了的 abc。
  // 从 99999999（本来就取不到卡片）直接跳 abc 抓不到这个症状，所以这一步不能省。
  await w.__probeRouter.push('/user/23')
  const cardAgain = await until(function () { return !!w.document.querySelector('section.card') }, 10000, 'user-card-again')
  check('6', '（前置）回到自己的主页，资料卡真的又画出来了 —— 下一次要判的是「有卡片 → 地址栏改坏」这条边',
    cardAgain, 'card=' + cardAgain + ' path=' + route().path)
  await w.__probeRouter.push('/user/abc')
  const abcCleared = await until(function () { return !w.document.querySelector('section.card') }, 10000, 'user-abc')
  check('6', '/user/abc 给「id 不是数字」的专属提示，而不是发一个注定 404 的请求再显示空白；上一张资料卡必须跟着一起消失',
    abcCleared && docText().indexOf('地址里的用户 id 不是数字') >= 0 && cards().length === 0,
    'cardCleared=' + abcCleared + ' cards=' + cards().length)
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
      // 第 49 轮改判据：阶段 5 在资料卡操作区永芯加了一颗「私信」（Gate5 C7/C8 已经拿截图证过），
      // 这里再去认 b.length === 1 就永远不可能成立——只钉住第一颗，后面有几颗交给下面那条判据去管。
      return b.length >= 1 && b[0].textContent.replace(/\s+/g, '') === '关注'
    }, 10000, 'other-home-follow-btn')
    const btns = Array.prototype.slice.call(w.document.querySelectorAll('.card-op button'))
      .map(function (e) { return e.textContent.replace(/\s+/g, '').trim() })
    check('7', '别人主页出现一颗「关注」按钮（未关注态文案，且不是自己的主页）',
      btnSeen && btns.length <= 2 && btns[0] === '关注',
      'id=' + otherId + ' btns=' + JSON.stringify(btns) + ' (关注在前，私信允许在后：阶段 5 U9 入口)')
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
  check('9', '翻到底之后不再给「查看更多」按钮，改出一行「一级评论 19 条已全部加载」',
    w.document.querySelectorAll('.comments .more').length === 0
    && docText().indexOf('一级评论 19 条已全部加载') >= 0, secText('.comments .pager'))
  // 同一屏两处「评论 N」必须同口径：卡片那个数来自 post.comment_cnt（只数已发布、含楼中楼），
  // 评论区标题以前用的是列表 total（可见的一级评论数，还把作者自己那条待审算进来），于是出现过
  // 「卡片 19 / 标题 20」。这条是 Gate3 截图 06 那轮补的，jsdom 这边同步钉一份：
  // 以后谁再改这两处的口径，至少会先在这里红一条，而不是等下一轮看图才发现。
  const cardCmt = (function () {
    const hit = Array.prototype.slice.call(w.document.querySelectorAll('.stat'))
      .map(function (e) { return e.textContent.replace(/\s+/g, '') })
      .filter(function (t) { return /^评论/.test(t) })[0]
    return hit ? hit.replace(/^评论/, '') : ''
  })()
  const headCmt = secText('.comments .h .n').replace(/\s+/g, '')
  check('9', '卡片「评论 N」与评论区标题「评论 N」是同一个数（两处必须同口径）',
    cardCmt !== '' && cardCmt === headCmt, '卡片=' + cardCmt + ' / 标题=' + headCmt)
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

// ---------- 12 关注流 Tab + 站内搜索页（任务 T3.17 / T3.9 的前端半程） ----------
// 这一段和第 11 段一样会写库：两名一次性账号、四条夹具帖、一条关注关系。
// 它要证明的三件事，build 和接口冒烟都给不出证据：
// ① 广场页现在同时挂着两份分页列表，切 Tab 用的是 v-show 而不是 v-if —— 节点不销毁，
//    两份无限滚动的哨兵才还在（这一条只能靠「两节同时存在于 DOM 里」看出来）；
// ② 切回来不重抓。这只能看请求日志，看界面是猜：状态被保留和「又抓了一遍同样的数据」长得一模一样；
// ③ 搜索页三条路径各自的空态、结果表、类型筛选、地址栏同步与深链自动执行。
async function runSourceSearchProbe(bundleCode) {
  const stamp = String(Date.now()).slice(-9)
  const nameR = "probe_src_r" + stamp
  const nameD = "probe_src_d" + stamp
  const kw = "探针关注" + stamp
  const nickD = "探针作者" + stamp

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

  const R = await register(nameR, "探针读者" + stamp)
  const D = await register(nameD, nickD)
  check("12", "注册两名一次性账号：读者 R 只负责看关注流，作者 D 只负责被关注（本段所有写入都落在这两个人身上）",
    !!R.token && !!D.token && !!R.id && !!D.id,
    "r=" + R.id + "(" + R.status + ") d=" + D.id + "(" + D.status + ")")
  if (!R.token || !D.token) {
    notes.push("SOURCE/SEARCH PROBE 提前收工：注册没拿到 token（读者 " + R.status + " 作者 " + D.status + "）")
    return
  }
  const mk = async function (body) {
    const rr = await api("POST", "/api/posts", D.token, body)
    return rr.json && rr.json.data ? rr.json.data : null
  }
  const pub1 = await mk({ title: kw + "甲", content: "关注流夹具甲，正文也带 " + kw })
  const pub2 = await mk({ title: kw + "乙", content: "关注流夹具乙，正文也带 " + kw })
  const hole = await mk({ title: kw + "洞", content: "树洞夹具一条 " + kw, type: "hole" })
  const privv = await mk({ title: kw + "私", content: "私密夹具一条 " + kw, visibility: "private" })
  const fixIds = [pub1, pub2, hole, privv].filter(Boolean).map(function (x) { return x.id })
  check("12", "作者落库四条夹具：两条公开实名 + 一条树洞（自动匿名）+ 一条仅自己可见 —— 新号 24 小时内每日 5 帖，这里只占 4 条",
    fixIds.length === 4 && hole && hole.anonymous === true
      && String(hole.displayName || "").indexOf("匿名屿民·") === 0
      && privv && privv.visibility === "private",
    "ids=" + fixIds.join(",") + " hole=" + (hole ? hole.displayName : "-") + " priv=" + (privv ? privv.visibility : "-"))
  const fRD = await api("POST", "/api/users/" + D.id + "/follow", R.token, { action: "follow" })
  check("12", "读者关注作者 → changed=true：关注流读的就是这条 user_follow（后端每次现取，没有缓存，取关立刻消失）",
    fRD.status === 200 && !!(fRD.json && fRD.json.data) && fRD.json.data.changed === true,
    fRD.status + " " + JSON.stringify((fRD.json && fRD.json.data) || {}).slice(0, 120))

  const mod = await import(pathToFileURL(JSDOM_ENTRY).href)
  const blogs = []
  const vc = new mod.VirtualConsole()
  vc.on("jsdomError", function (e) { blogs.push("jsdomError: " + ((e && e.message) || String(e))) })
  vc.on("error", function () { blogs.push("console.error: " + Array.prototype.join.call(arguments, " ")) })
  const dom = new mod.JSDOM('<!doctype html><html><head><meta charset="utf-8"></head><body><div id="app"></div></body></html>', {
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
  // 请求日志：装在挂载之前，整段都用它。axios 在浏览器构建里走 XHR，
  // 包一层 open 就能拿到真实 URL —— 判「切 Tab 有没有重抓」只有这一种不骗自己的办法。
  w.__reqLog = []
  const originOpen = w.XMLHttpRequest.prototype.open
  w.XMLHttpRequest.prototype.open = function (method, url) {
    w.__reqLog.push(String(method) + " " + String(url))
    return originOpen.apply(this, arguments)
  }
  const reqs = function (needle) {
    return (w.__reqLog || []).filter(function (line) { return line.indexOf(needle) >= 0 })
  }
  w.localStorage.setItem("mindisle_token", R.token)
  const sc = w.document.createElement("script")
  sc.textContent = bundleCode
  w.document.head.appendChild(sc)

  const txt = function (el) { return el ? el.textContent.replace(/s+/g, " ").trim() : "" }
  const secs = function () { return Array.prototype.slice.call(w.document.querySelectorAll("section.plaza")) }
  // 两节的区分不靠 DOM 顺序：广场那节顶部有类型 Tab（一个 radio-group），关注那节没有。
  const plazaSec = function () {
    return secs().find(function (x) { return !!x.querySelector(".el-radio-group") }) || null
  }
  const folSec = function () {
    return secs().find(function (x) { return !x.querySelector(".el-radio-group") }) || null
  }
  const cardsIn = function (sec) {
    return sec ? Array.prototype.slice.call(sec.querySelectorAll("article.post")) : []
  }
  const titlesIn = function (sec) {
    return cardsIn(sec).map(function (c) { return txt(c.querySelector(".title")) })
  }
  const dismissBtns = function (sec) {
    if (!sec) return []
    return Array.prototype.slice.call(sec.querySelectorAll("article.post button"))
      .filter(function (b) { return txt(b).indexOf("不感兴趣") >= 0 })
  }
  function pickIn(root, label) {
    if (!root) return false
    const btn = Array.prototype.slice.call(root.querySelectorAll(".el-radio-button"))
      .find(function (x) { return txt(x) === label })
    if (!btn) return false
    const inp = btn.querySelector("input")
    if (!inp) return false
    inp.click()
    return true
  }
  function docText4() { return w.document.body.textContent.replace(/s+/g, " ") }
  async function until4(fn, ms, label) {
    const t0 = Date.now()
    for (;;) {
      let ok = false
      try { ok = !!fn() } catch (e) { ok = false }
      if (ok) return true
      if (Date.now() - t0 > ms) {
        notes.push("TIMEOUT " + label + " 之后 DOM 文本=「" + docText4().slice(0, 200) + "」")
        return false
      }
      await sleep(120)
    }
  }
  const route4 = function () { return w.__probeRouter.currentRoute.value }

  const mounted4 = await until4(function () { return w.__probeMounted === true }, 12000, "src-mount")
  const onFeed4 = mounted4 && await until4(function () { return route4().name === "feed" }, 8000, "src-feed")
  check("12", "以读者身份挂载并停在广场（这一段的入口是页面本身，不是任何接口）",
    onFeed4, w.location.pathname)
  const twoSecs = await until4(function () { return secs().length === 2 }, 10000, "src-two-sections")
  check("12", "广场页同时挂着两节列表：切 Tab 用的是 v-show 而不是 v-if —— 节点一旦被销毁，那两份无限滚动的哨兵就再也等不到「第二次进视野」",
    twoSecs && secs().length === 2, "n=" + secs().length)
  const plazaReady = await until4(function () { return cardsIn(plazaSec()).length > 0 }, 12000, "src-plaza")
  const plazaTitles0 = titlesIn(plazaSec()).join("|")
  const plazaDismiss0 = dismissBtns(plazaSec()).length
  check("12", "首屏只露出广场：广场节有卡片且带「不感兴趣」，关注节整节 display:none（数据没取，节点已经在）",
    plazaReady && plazaTitles0.split("|").length > 0 && plazaDismiss0 > 0
      && plazaSec().style.display !== "none" && folSec().style.display === "none",
    'plazaCards=' + cardsIn(plazaSec()).length + ' plazaHide=' + plazaDismiss0
      + ' folStyle="' + folSec().getAttribute("style") + '"')

  w.__reqLog.length = 0
  const clickedFol = pickIn(w.document.querySelector(".mi-card.source"), "关注")
  const folTwo = await until4(function () { return cardsIn(folSec()).length === 2 }, 12000, "src-follow-2")
  check("12", "点「关注」→ 只发出一次 GET /api/feed/following（不是又抓一遍广场，也不是不发请求）",
    clickedFol && reqs("/api/feed/following").length === 1,
    JSON.stringify(reqs("/api/feed/following")))
  check("12", "关注流恰好两张卡片，标题全部来自作者这次的夹具：树洞与私密那两条恒不出现（前端没做任何二次过滤，接口回什么就画什么）",
    folTwo && titlesIn(folSec()).length === 2
      && titlesIn(folSec()).every(function (t) { return t.indexOf(kw) === 0 })
      && titlesIn(folSec()).join("|").indexOf(kw + "洞") < 0
      && titlesIn(folSec()).join("|").indexOf(kw + "私") < 0,
    "titles=" + titlesIn(folSec()).join("|"))
  check("12", "切过来之后是关注节显示、广场节 display:none：两节互为镜像，都不靠重建",
    folSec().style.display !== "none" && plazaSec().style.display === "none",
    'fol="' + folSec().getAttribute("style") + '" plaza="' + plazaSec().getAttribute("style") + '"')
  check("12", "关注流的卡片不带「不感兴趣」：那是广场的负反馈位，后端也没有对应的这条路径的接口（对比广场那节有 " + plazaDismiss0 + " 个）",
    dismissBtns(folSec()).length === 0, "n=" + dismissBtns(folSec()).length)
  check("12", "关注流的作者名两条都可点进主页（.who.link）：这条流里全是实名帖，这正是它和广场的区别",
    folSec().querySelectorAll(".who.link").length === 2,
    "link=" + folSec().querySelectorAll(".who.link").length
      + " names=" + Array.prototype.slice.call(folSec().querySelectorAll(".who")).map(txt).join("/"))
  check("12", "关注节的计数行报的是后端 total（页码模式首屏才有的那个字段），不是本页卡片数",
    txt(folSec()).indexOf("关注的人共 2 条可见更新") >= 0,
    "hint=" + Array.prototype.slice.call(folSec().querySelectorAll(".hint")).map(txt).join(" || ").slice(0, 180))

  w.__reqLog.length = 0
  const backPlaza = pickIn(w.document.querySelector(".mi-card.source"), "广场")
  await sleep(900)
  check("12", "切回广场：一条列表请求都没发，卡片与标题顺序和切走前逐字一致（两份列表各有各的游标与缓存，切 Tab 不重抓）",
    backPlaza && reqs("/api/posts").length === 0 && titlesIn(plazaSec()).join("|") === plazaTitles0,
    "reqs=" + JSON.stringify(reqs("/api/posts")) + " cards=" + cardsIn(plazaSec()).length)

  await w.__probeRouter.push({ name: "search" })
  const onSearch = await until4(function () { return route4().name === "search" }, 8000, "src-search-mount")
  check("12", "顶栏「搜索」这条路进得来 /search（路由注册 + requiresAuth 放行已登录读者）",
    onSearch, w.location.pathname)
  check("12", "空关键词时给的是说明而不是错误：「输入关键词后按回车开搜」，且一条请求都不发（后端空词直接 10001）",
    docText4().indexOf("输入关键词后按回车开搜") >= 0 && reqs("/api/search").length === 0,
    "reqs=" + reqs("/api/search").length)
  check("12", "结果类别是单选按钮组而不是下拉：与全站另外三处「换一张列表」同一个控件，本段也才能复用同一个点击器",
    w.document.querySelectorAll(".modes .el-radio-button").length === 3
      && w.document.querySelectorAll(".el-select").length === 0,
    "modes=" + Array.prototype.slice.call(w.document.querySelectorAll(".modes .el-radio-button")).map(txt).join("/"))

  const setKw = function (value) {
    const input = w.document.querySelector("input.el-input__inner")
    if (!input) return false
    input.value = value
    input.dispatchEvent(new w.Event("input", { bubbles: true }))
    return true
  }
  const clickSearch = function () {
    const btn = Array.prototype.slice.call(w.document.querySelectorAll(".el-input-group__append button"))
      .find(function (b) { return txt(b) === "搜索" })
    if (!btn) return false
    btn.click()
    return true
  }
  const searchCards = function () {
    return Array.prototype.slice.call(w.document.querySelectorAll(".page .list article.post"))
  }
  const setOk = setKw(kw)
  const subOk = clickSearch()
  const got3 = await until4(function () { return searchCards().length === 3 }, 12000, "src-search-3")
  check("12", "在输入框里打关键词、点「搜索」→ GET /api/search/posts 真发出并画出三张卡片：两条公开 + 那条树洞（它公开可见，只是匿名）",
    setOk && subOk && got3 && reqs("/api/search/posts").length >= 1,
    "reqs=" + JSON.stringify(reqs("/api/search/posts")).slice(0, 170) + " n=" + searchCards().length)
  check("12", "私密夹具搜不到：四条落库的帖子里只回三条（可见性判据与广场共用同一份，不是搜索自己另写一套）",
    searchCards().map(function (c) { return txt(c.querySelector(".title")) }).join("|").indexOf(kw + "私") < 0,
    "titles=" + searchCards().map(function (c) { return txt(c.querySelector(".title")) }).join("|"))
  check("12", "树洞那条在结果里带着马甲名「匿名屿民·」：搜索这条路径同样不能解匿（FR1.4）",
    Array.prototype.slice.call(w.document.querySelectorAll(".page .list .who"))
      .some(function (e) { return txt(e).indexOf("匿名屿民·") === 0 }),
    "who=" + Array.prototype.slice.call(w.document.querySelectorAll(".page .list .who")).map(txt).join("/"))
  check("12", "计数行报出后端 total：「共 3 条命中」，并把这次查询写进地址栏 ?q=（能刷新还在、能后退回去、链接能发给别人）",
    docText4().indexOf("共 3 条命中") >= 0 && decodeURIComponent(w.location.search).indexOf(kw) >= 0,
    "search=" + decodeURIComponent(w.location.search))
  // ---------- 类型筛选：请求与地址栏各判一次（上一版就是只看界面没看地址栏，漏掉了没同步的那半条） ----------
  const reqsBeforeType = reqs("/api/search/posts").length
  const typeOk = pickIn(w.document.querySelector(".filters"), "树洞")
  const got1 = await until4(function () { return searchCards().length === 1 }, 12000, "src-search-type")
  const typeReqs = reqs("/api/search/posts")
  check("12", "点类型筛选「树洞」→ 只多发一条请求，且 type=hole 是发请求那一刻才拼进去的（参数由 query() 现取，不是挂载时的快照）",
    typeOk && typeReqs.length === reqsBeforeType + 1
      && String(typeReqs[typeReqs.length - 1] || "").indexOf("type=hole") >= 0,
    JSON.stringify(typeReqs).slice(0, 330))
  check("12", "筛选后只剩那一条树洞，标题逐字对得上，计数行跟着后端重算成「共 1 条命中」",
    got1 && searchCards().length === 1 && txt(searchCards()[0].querySelector(".title")) === kw + "洞"
      && docText4().indexOf("共 1 条命中") >= 0,
    "titles=" + searchCards().map(function (c) { return txt(c.querySelector(".title")) }).join("|"))
  check("12", "筛选条件同步进了地址栏 ?type=hole：改回 run() 之前这里只调 reload()，筛选生效了链接却还是没筛的那条，「把这一屏发给同学」会发错",
    decodeURIComponent(w.location.search).indexOf("type=hole") >= 0,
    "search=" + decodeURIComponent(w.location.search))
  // ---------- 清空：屏幕与地址栏要一起回到「还没搜」 ----------
  const clearBtn = w.document.querySelector(".el-input__clear")
  if (clearBtn) clearBtn.dispatchEvent(new w.MouseEvent("click", { bubbles: true }))
  const clearedOut = await until4(function () { return searchCards().length === 0 }, 8000, "src-search-cleared")
  check("12", "点输入框的清空叉 → 结果清空、回到「输入关键词后按回车开搜」那句说明，且清空本身不发请求（后端空词直接 10001）",
    !!clearBtn && clearedOut && docText4().indexOf("输入关键词后按回车开搜") >= 0
      && reqs("/api/search/posts").length === typeReqs.length,
    "clearBtn=" + !!clearBtn + " reqs=" + reqs("/api/search/posts").length)
  check("12", "清空连地址栏一起清：屏幕上已经是「还没搜」，链接里就不该还留着 q=（否则一刷新会凭空恢复一份刚被用户清掉的结果）",
    decodeURIComponent(w.location.search).indexOf(kw) < 0,
    "search=" + decodeURIComponent(w.location.search))
  // ---------- 零命中 ----------
  pickIn(w.document.querySelector(".filters"), "全部")
  const kwNone = setKw(nickD) && clickSearch()
  const emptied = await until4(function () { return docText4().indexOf("共 0 条命中") >= 0 }, 12000, "src-search-empty")
  check("12", "换成一个只有昵称里才有的词（先退回「全部」类型）→ 零命中：画空态而不是报错，不许留着上一轮的结果，type 也从地址栏掉了",
    kwNone && emptied && searchCards().length === 0
      && w.document.querySelectorAll(".page .list .el-empty").length > 0
      && decodeURIComponent(w.location.search).indexOf("type=") < 0,
    "n=" + searchCards().length + " empty=" + w.document.querySelectorAll(".page .list .el-empty").length
      + " search=" + decodeURIComponent(w.location.search))
  const reqsBeforeUser = reqs("/api/search/users").length
  const modeOk2 = pickIn(w.document.querySelector(".modes"), "屿友")
  const oneUser = await until4(function () { return w.document.querySelectorAll(".page li.user").length === 1 }, 12000, "src-search-user")
  check("12", "同一个关键词换到「屿友」这一栏就命中：三条路径查的是三张表，切栏不换词，且只多发自己那一条请求",
    modeOk2 && oneUser && reqs("/api/search/users").length === reqsBeforeUser + 1
      && txt(w.document.querySelector(".page li.user")).indexOf(nickD) >= 0
      && txt(w.document.querySelector(".page li.user")).indexOf("uid " + D.id) >= 0,
    "row=" + txt(w.document.querySelector(".page li.user")) + " reqs=" + reqs("/api/search/users").length)
  check("12", "换栏也把类别写回了地址栏 ?m=user：刷新回来还在屿友这一栏，而不是被弹回帖子栏",
    decodeURIComponent(w.location.search).indexOf("m=user") >= 0,
    "search=" + decodeURIComponent(w.location.search))
  const liUser = w.document.querySelector(".page li.user")
  if (liUser) liUser.click()
  const jumped = await until4(function () { return route4().name === "user-home" }, 8000, "src-jump-user")
  check("12", "点这条屿友结果跳进作者主页 /user/" + D.id + "：搜索的落点与主页是同一条路由，不是另做一份资料卡",
    jumped && String(route4().params.id) === String(D.id), "path=" + route4().path)

  await w.__probeRouter.push({ name: "search", query: { m: "topic", q: "焦虑" } })
  const topicHits = await until4(function () { return w.document.querySelectorAll(".page .topics .topic").length >= 2 }, 12000, "src-topic")
  check("12", "带着 ?m=topic&q=焦虑 深链进搜索页：组件重新挂载时 onMounted 读地址栏自动跑了一次话题检索（刷新还在、链接可分享）",
    route4().name === "search" && topicHits
      && docText4().indexOf("秋招焦虑") >= 0 && reqs("/api/search/topics").length >= 1,
    'search="' + w.location.search + '" reqs=' + JSON.stringify(reqs("/api/search/topics")).slice(0, 160))
  const sTopicCards = Array.prototype.slice.call(w.document.querySelectorAll(".page .topics .topic.topic-link"))
  check("12", "话题卡现在点得动了（T3.8 交付的就是这一步，之前这里是一句「点不动是刻意的」）：这一栏每张卡都带着「进入话题」，那句解释也跟着结果一起出现",
    sTopicCards.length >= 2
      && sTopicCards.every(function (c) { return txt(c.querySelector(".t-go")).indexOf("进入话题") >= 0 })
      && docText4().indexOf("点任意一张话题卡进话题页") >= 0,
    "cards=" + sTopicCards.length + " go=" + (sTopicCards[0] ? txt(sTopicCards[0].querySelector(".t-go")) : "-"))
  const sGoCard = sTopicCards.find(function (c) { return txt(c.querySelector(".t-name")).indexOf("焦虑") >= 0 }) || sTopicCards[0] || null
  if (sGoCard) sGoCard.click()
  const sJumped = await until4(function () { return route4().name === "topic-detail" }, 8000, "src-jump-topic")
  check("12", "点搜索页这张话题卡 → 路由真的换成 topic-detail、地址栏真的变成 /topic/编号：搜索页与广场共用同一个详情页，没有各画一份头图"
    + "（这一条放在本组最末尾，因为它一旦点出去就离开了 /search）",
    !!sGoCard && sJumped && String(route4().path).indexOf("/topic/") === 0,
    "path=" + route4().path + " name=" + route4().name)
  check("12", "整段关注流 + 搜索页没弹过一条全局错误条：三条搜索路径都是 silent 的，401/10001/90002 各自由页面自己说清楚",
    w.document.querySelectorAll(".el-message").length === 0,
    "n=" + w.document.querySelectorAll(".el-message").length)
  notes.push("src/search 一次性账号：" + nameR + "(" + R.id + ") " + nameD + "(" + D.id + ")")
  notes.push("src/search 夹具帖 id：" + fixIds.join(",") + "（其中 " + (privv ? privv.id : "-") + " 是私密、" + (hole ? hole.id : "-") + " 是树洞）")
  notes.push("src/search window logs: " + (blogs.length ? blogs.slice(0, 6).join(" || ") : "none"))
  notes.push("本段触发搜索用的是「点搜索按钮」，没有测 @keyup.enter：jsdom 下 keyup 与 Element Plus 输入框包装层的对应关系不保证成立，键盘路径留给真浏览器")
  dom.window.close()
}

// ---------- 13 话题页（任务 T3.8 的前端半程：U6 头图与帖流 + 墙上入口 + 创建话题 + ?topic= 预填） ----------
// 这一段照样会写库：一名一次性账号、两条夹具帖（一条走接口挂题、一条从话题页点出来）、一次关注来回、一个新建话题。
// 它要证明的六件事，build 与接口冒烟都给不出证据：
// ① 广场墙上那张卡点下去真的变成 /topic/编号，而不是「解释得挺好但动不了」（这一条改的就是上一段那句旧断言）；
// ② 头图三个数字逐个来自接口，关注那一个「先本地加一、再以回执覆盖」之后还要独立读一次接口三方对齐；
// ③ 切「热帖」是真多发了一条 sort=top 的请求，且今天与「最新」同序这件事必须写在屏幕上而不是藏在注释里；
// ④ 零帖话题与编号打错（abc）这两种「看起来像坏了」的状态，页面各自有一句人话，且 abc 一条请求都不发；
// ⑤ 创建话题：超长挡在提交之前不发请求；提交之后回执的 usable 与后端 audit_status 必须一致
//    —— 预审开关（FR8.6）默认开着，所以「进不去」在这里是正常结局，界面不许把它说成失败，也不许画成能点的空壳；
// ⑥ 从话题页点「发帖到该话题」→ 发布请求体里真的带着 topicIds，用户不必再手动挑一次。
async function runTopicProbe(bundleCode) {
  const stamp = String(Date.now()).slice(-9)
  const nameU = "probe_topic_u" + stamp
  const kw = "探针话题" + stamp

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

  const U = await register(nameU, "探针话题客" + stamp)
  check("13", "注册一名一次性账号：话题详情/帖流/关注/创建/发帖这五条路径全要求登录，本段所有写入都落在它身上",
    !!U.token && !!U.id, "u=" + U.id + "(" + U.status + ")")
  if (!U.token) {
    notes.push("TOPIC PROBE 提前收工：注册没拿到 token（" + U.status + "）")
    return
  }
  // 夹具不从「我以为库里有什么」出发：先读一次话题墙，从真实数据里挑「有帖的那个」和「零帖的那个」。
  const wallResp = await api("GET", "/api/topics?limit=50", null, null)
  const wall = (wallResp.json && wallResp.json.data) || []
  const T = wall.filter(function (t) { return Number(t.postCnt) > 0 })[0] || wall[0] || null
  const TE = wall.filter(function (t) {
    return Number(t.postCnt) === 0 && (!T || Number(t.id) !== Number(T.id))
  })[0] || null
  check("13", "话题墙里能挑出两种真夹具：至少一个「有帖话题」（验列表）和至少一个「零帖话题」（验空态那句诚实话）",
    wall.length > 0 && !!T && !!TE,
    "wall=" + wall.length + " T=" + (T ? T.id + ":" + T.name + "/发帖" + T.postCnt : "-")
      + " TE=" + (TE ? TE.id + ":" + TE.name : "-"))
  if (!T) {
    notes.push("TOPIC PROBE 提前收工：话题墙上没有一个已过审话题")
    return
  }
  const tid = Number(T.id)
  const pubPost = await api("POST", "/api/posts", U.token, {
    title: kw + "甲", content: "话题页夹具正文一条 " + kw, topicIds: [tid]
  })
  const pubData = pubPost.json && pubPost.json.data ? pubPost.json.data : null
  check("13", "带 topicIds 向一个已过审话题发帖被后端放行（PUBLISHED）：话题页的列表接下来才有东西可画",
    pubPost.status === 200 && !!pubData && pubData.status === "PUBLISHED",
    pubPost.status + " id=" + (pubData ? pubData.id : "-") + " st=" + (pubData ? pubData.status : "-"))
  const srvBefore = await api("GET", "/api/topics/" + tid, U.token, null)
  const cardBefore = (srvBefore.json && srvBefore.json.data) || {}
  const listBefore = await api("GET", "/api/topics/" + tid + "/posts", U.token, null)
  const totalBefore = Number((listBefore.json && listBefore.json.data || {}).total)

  const mod = await import(pathToFileURL(JSDOM_ENTRY).href)
  const blogs = []
  const vc = new mod.VirtualConsole()
  vc.on("jsdomError", function (e) { blogs.push("jsdomError: " + ((e && e.message) || String(e))) })
  vc.on("error", function () { blogs.push("console.error: " + Array.prototype.join.call(arguments, " ")) })
  const dom = new mod.JSDOM('<!doctype html><html><head><meta charset="utf-8"></head><body><div id="app"></div></body></html>', {
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
  // 三条日志各管一件事：请求行判「发了没有、发了几条」，请求体判「预填到底带没带进 payload」，
  // 响应体判「界面那句回执有没有替后端撒谎」。前两条在界面 DOM 里根本看不见。
  w.__reqLog = []
  w.__bodyLog = []
  w.__respLog = []
  const originOpen = w.XMLHttpRequest.prototype.open
  w.XMLHttpRequest.prototype.open = function (method, url) {
    this.__probeLine = String(method) + " " + String(url)
    w.__reqLog.push(this.__probeLine)
    return originOpen.apply(this, arguments)
  }
  const originSend = w.XMLHttpRequest.prototype.send
  w.XMLHttpRequest.prototype.send = function (body) {
    const self = this
    w.__bodyLog.push({ line: String(self.__probeLine || "?"), body: body == null ? "" : String(body) })
    self.addEventListener("load", function () {
      let json = null
      try { json = JSON.parse(String(self.responseText || "")) } catch (e) { json = null }
      w.__respLog.push({ line: String(self.__probeLine || "?"), status: self.status, json: json })
    })
    return originSend.apply(this, arguments)
  }
  // 前缀匹配而不是包含匹配：reqs("/api/topics/1") 会把 .../posts 与 .../follow 一起数进来，
  // 那种断言看着严格，其实一条也没钉住。
  const reqsStart = function (prefix) {
    return (w.__reqLog || []).filter(function (x) { return String(x).indexOf(prefix) === 0 })
  }
  const reqsEq = function (line) {
    return (w.__reqLog || []).filter(function (x) { return String(x) === line })
  }
  const respOf = function (line) {
    const hit = (w.__respLog || []).filter(function (r) { return r.line === line })
    return hit.length ? hit[hit.length - 1] : null
  }
  w.localStorage.setItem("mindisle_token", U.token)
  const sc = w.document.createElement("script")
  sc.textContent = bundleCode
  w.document.head.appendChild(sc)

  const txt = function (el) { return el ? String(el.textContent).trim() : "" }
  const docText = function () { return String(w.document.body.textContent) }
  const msgTexts = function () {
    return Array.prototype.slice.call(w.document.querySelectorAll(".el-message")).map(txt)
  }
  const btnLike = function (root, needle) {
    if (!root) return null
    const list = Array.prototype.slice.call(root.querySelectorAll("button"))
    return list.find(function (x) { return txt(x).indexOf(needle) >= 0 }) || null
  }
  const pickRadio = function (root, label) {
    if (!root) return false
    const list = Array.prototype.slice.call(root.querySelectorAll(".el-radio-button"))
    const hit = list.find(function (x) { return txt(x) === label })
    if (!hit) return false
    const inp = hit.querySelector("input")
    if (!inp) return false
    inp.click()
    return true
  }
  const setInput = function (el, value) {
    if (!el) return false
    el.value = value
    el.dispatchEvent(new w.Event("input", { bubbles: true }))
    return true
  }
  // .t-stats 的直接子节点里还挂着那个 el-tag（它自己也是个 span），数三个计数前要按类名滤掉它。
  const stats = function () {
    const box = w.document.querySelector("section.hero .t-stats")
    if (!box) return []
    const kids = Array.prototype.slice.call(box.children)
    return kids.filter(function (el) { return String(el.className).indexOf("el-tag") < 0 }).map(txt)
  }
  const tagOfHero = function () {
    const box = w.document.querySelector("section.hero .t-stats")
    if (!box) return ""
    const kids = Array.prototype.slice.call(box.children)
    const hit = kids.find(function (el) { return String(el.className).indexOf("el-tag") >= 0 })
    return txt(hit || null)
  }
  const cards = function () {
    return Array.prototype.slice.call(w.document.querySelectorAll("section.flow .list article.post"))
  }
  const titles = function () {
    return cards().map(function (c) { return txt(c.querySelector(".title")) })
  }
  const route13 = function () { return w.__probeRouter.currentRoute.value }
  async function until13(fn, ms, label) {
    const t0 = Date.now()
    for (;;) {
      let ok = false
      try { ok = !!fn() } catch (e) { ok = false }
      if (ok) return true
      if (Date.now() - t0 > ms) {
        notes.push("TIMEOUT " + label + " 之后 DOM 文本=「" + docText().slice(0, 220) + "」")
        return false
      }
      await sleep(120)
    }
  }
  const mounted = await until13(function () { return w.__probeMounted === true }, 12000, "topic-mount")
  const wallReady = await until13(function () {
    return w.document.querySelectorAll(".topics .topic.topic-link").length > 0
  }, 12000, "topic-wall")
  const wallCards = Array.prototype.slice.call(w.document.querySelectorAll(".topics .topic.topic-link"))
  check("13", "广场墙上每一张话题卡都带着「进入话题」这个落点：这一条同时把 T3.8 之前那个「点不动」的旧状态钉死",
    mounted && wallReady && wallCards.length > 0 && wallCards.every(function (c) {
      return txt(c.querySelector(".t-go")).indexOf("进入话题") >= 0
    }),
    "cards=" + wallCards.length + " go=" + (wallCards[0] ? txt(wallCards[0].querySelector(".t-go")) : "-"))
  const cardEl = wallCards.find(function (c) {
    return txt(c.querySelector(".t-name")) === "# " + T.name
  }) || null
  if (cardEl) cardEl.click()
  const onTopic = await until13(function () {
    return route13().name === "topic-detail" && String(route13().params.id) === String(tid)
  }, 8000, "topic-route")
  check("13", "点墙上那张「" + T.name + "」→ 路由真的变成 topic-detail、地址栏真的变成 /topic/" + tid
    + "（搜索页那条入口在第 12 组已验：两处共用同一个组件，不会出现两处各画一份头图）",
    !!cardEl && onTopic, "name=" + (cardEl ? txt(cardEl.querySelector(".t-name")) : "-") + " path=" + route13().path)
  const heroOk = await until13(function () { return !!w.document.querySelector("section.hero .t-name") }, 12000, "topic-hero")
  check("13", "话题头三件套真渲染出来了：名字带 # 前缀、三个计数各占一个 span、已过审官方话题挂「官方」角标",
    heroOk && txt(w.document.querySelector("section.hero .t-name")) === "# " + T.name
      && stats().length === 3 && tagOfHero() === "官方",
    "stats=" + stats().join("/") + " tag=" + tagOfHero())
  check("13", "头图那两个数字与接口逐字相同（发帖数、关注数）：前端不做任何「先猜一个」的估算",
    stats()[0] === "发帖 " + cardBefore.postCnt && stats()[1] === "关注 " + cardBefore.followCnt,
    "dom=" + stats().join("/") + " api=" + cardBefore.postCnt + "/" + cardBefore.followCnt)
  const detailReqs = reqsEq("GET /api/topics/" + tid)
  const postReqs = reqsStart("GET /api/topics/" + tid + "/posts")
  check("13", "进这一页恰好两条请求：一条话题资料、一条帖流；帖流首屏不带 sort 参数（latest 是后端默认档，前端不重复发一遍）",
    detailReqs.length === 1 && postReqs.length === 1 && String(postReqs[0] || "").indexOf("sort=") < 0,
    JSON.stringify(detailReqs.concat(postReqs)).slice(0, 260))
  const listOk = await until13(function () { return titles().indexOf(kw + "甲") >= 0 }, 12000, "topic-list")
  check("13", "话题下的帖流画出卡片，且刚挂上这个话题的那条夹具就在里面（post_topic 真的驱动了这条列表，不是「所有帖子」）",
    listOk && cards().length >= 1 && cards().length <= 20, "titles=" + titles().join("|").slice(0, 200))
  const totalDom = txt(w.document.querySelector("section.flow .tabs .hint"))
  check("13", "计数行「这个话题下共 N 条可见帖子」的 N 与后端 total 逐字相同（界面不许自己数卡片数冒充总数）",
    totalDom.indexOf("这个话题下共 ") === 0 && totalDom.indexOf(String(totalBefore) + " 条可见帖子") >= 0,
    "dom=" + totalDom + " api=" + totalBefore)
  const tidReqsBeforeTe = reqsStart("GET /api/topics/" + tid).length
  if (TE) await w.__probeRouter.push({ name: "topic-detail", params: { id: TE.id } })
  const onTe = await until13(function () {
    return route13().name === "topic-detail" && String(route13().params.id) === String(TE ? TE.id : 0)
  }, 8000, "topic-te-route")
  const emptyOk = await until13(function () {
    return docText().indexOf("这个话题还没有人发帖") >= 0
  }, 12000, "topic-te-empty")
  check("13", "换到一个零帖话题（走的是组件复用 + watch 编号那条路）：头图说「发帖 0」，列表给的是「还没有人发帖，点上面开个头」",
    !!TE && onTe && emptyOk && txt(w.document.querySelector("section.hero .t-name")) === "# " + TE.name
      && stats()[0] === "发帖 0" && cards().length === 0,
    "TE=" + (TE ? TE.id : "-") + " name=" + txt(w.document.querySelector("section.hero .t-name"))
      + " stats=" + stats().join("/"))
  const teDetail = reqsEq("GET /api/topics/" + (TE ? TE.id : 0))
  const tePosts = reqsStart("GET /api/topics/" + (TE ? TE.id : 0) + "/posts")
  check("13", "这次「换话题」只发了新话题那两条请求，一条都没再打回旧话题 " + tid + "（组件复用最容易漏的就是这一步）",
    teDetail.length === 1 && tePosts.length === 1 && reqsStart("GET /api/topics/" + tid).length === tidReqsBeforeTe,
    "teDetail=" + teDetail.length + " tePosts=" + tePosts.length + " oldTid=" + reqsStart("GET /api/topics/" + tid).length
      + "/" + tidReqsBeforeTe)
  w.__reqLog.length = 0
  await w.__probeRouter.push("/topic/abc")
  const badOk = await until13(function () {
    return w.document.querySelectorAll(".page .stage").length > 0
  }, 8000, "topic-abc")
  check("13", "地址栏编号写成 abc：页面自己说一句人话（这个话题不存在），且一条请求都不发 —— 不拿 abc 去后端换 404 再让人猜为什么白屏",
    badOk && w.__reqLog.length === 0 && docText().indexOf("这个话题不存在") >= 0,
    "reqs=" + JSON.stringify(w.__reqLog).slice(0, 160))
  check("13", "上面这一整段只读路径没弹过一条全局红条：详情与帖流都是 silent 接口，页面自己解释自己",
    w.document.querySelectorAll(".el-message").length === 0,
    "n=" + w.document.querySelectorAll(".el-message").length)
  // ---- 创建话题（刻意排在只读段之后：这条路会弹 ElMessage，放前面会把「只读段零红条」那条断言搅浑）----
  await w.__probeRouter.push({ name: "feed" })
  const backFeed = await until13(function () { return route13().name === "feed" }, 8000, "topic-back-feed")
  const openBtn = w.document.querySelector(".btn-create-topic")
  if (backFeed && openBtn) openBtn.click()
  const dlgOk = await until13(function () { return !!w.document.querySelector(".el-dialog") }, 8000, "topic-create-dialog")
  const overName = Array(41).join("超")
  const typedOver = setInput(w.document.querySelector(".el-dialog input.el-input__inner"), overName)
  // 那一行是 computed，Vue 的组件更新排在微任务里：setInput 之后同步读 DOM 拿到的必然还是「已输入 0」。
  // 上一版这条就是这么假失败的（再点一次提交时模型其实早就带上新值了）——凡判「输入之后界面跟着改没有」，都必须等到。
  const counterOk = await until13(function () {
    return txt(w.document.querySelector(".el-dialog .hint")).indexOf("已输入 40") === 0
  }, 8000, "topic-name-counter")
  const nameHint = txt(w.document.querySelector(".el-dialog .hint"))
  check("13", "在话题名里连敲 40 个字：数码点当场给出「已输入 40 / 上限 32」—— 这一行是跟着字走的，不用等提交完才告诉用户超了",
    backFeed && dlgOk && typedOver && counterOk && nameHint.indexOf("上限 32") >= 0,
    "hint=" + nameHint.slice(0, 90))
  w.__reqLog.length = 0
  const overSubmit = w.document.querySelector(".el-dialog .btn-do-create")
  if (overSubmit) overSubmit.click()
  const overToast = await until13(function () { return msgTexts().join("|").indexOf("话题名最长 32") >= 0 }, 6000, "topic-overlong-toast")
  check("13", "超长这一次被挡在提交之前：一条 POST /api/topics 都没发出去（不做 maxlength，但判定必须发生在网络请求之前）"
    + " —— 口径与后端 normalizeName 的 codePointCount 一致，一个 emoji 算一个字",
    overToast && reqsStart("POST /api/topics").length === 0,
    "toast=" + msgTexts().join("|").slice(0, 90) + " reqs=" + reqsStart("POST /api/topics").length)
  const legalName = kw + "圈"
  setInput(w.document.querySelector(".el-dialog input.el-input__inner"), legalName)
  setInput(w.document.querySelector(".el-dialog textarea"), "探针夹具的一句话简介")
  const createReqsBefore = reqsStart("POST /api/topics").length
  const doCreate = w.document.querySelector(".el-dialog .btn-do-create")
  if (doCreate) doCreate.click()
  const alertOk = await until13(function () { return !!w.document.querySelector(".el-dialog .el-alert") }, 12000, "topic-create-alert")
  const createResp = respOf("POST /api/topics")
  const createdData = createResp && createResp.json ? createResp.json.data : null
  const createdId = createdData && createdData.id ? Number(createdData.id) : 0
  const usable = !!(createdData && createdData.usable)
  const alertTitle = txt(w.document.querySelector(".el-dialog .el-alert__title"))
  check("13", "合法长度这一次真的发出去了：恰好一条 POST /api/topics、HTTP 200，横幅把话题名念回给用户（不是一句笼统的「成功」）"
    + " —— 预审开着时「进了待审」也走 200，接口不拿状态码骗前端",
    alertOk && reqsStart("POST /api/topics").length === createReqsBefore + 1 && !!createResp
      && createResp.status === 200 && !!createdData && alertTitle.indexOf(createdData.name || legalName) >= 0,
    "reqs=" + reqsStart("POST /api/topics").length + " status=" + (createResp ? createResp.status : "-")
      + " id=" + createdId + " alert=" + alertTitle.slice(0, 70))
  if (usable) {
    const goBtn = btnLike(w.document.querySelector(".el-dialog .el-alert"), "现在就进去")
    if (goBtn) goBtn.click()
    const entered = await until13(function () {
      return route13().name === "topic-detail" && String(route13().params.id) === String(createdId)
    }, 8000, "topic-created-enter")
    check("13", "（预审关掉时走这条路）横幅里那颗「现在就进去」真的把地址栏换成 /topic/" + createdId
      + "，头图角标是「屿友创建」不是「官方」—— 自建话题恒不进官方墙，界面也没有把它画成官方",
      entered && tagOfHero() === "屿友创建", "path=" + route13().path + " tag=" + tagOfHero())
    const ownDetail = await api("GET", "/api/topics/" + createdId, U.token, null)
    check("13", "界面说「能进去」的时候接口也是这么说的：GET /api/topics/" + createdId + " 返回 200 且 isOfficial=false（回执 usable 与读接口不许各讲一套）"
      + " —— 本条只在 mindisle.topic.require-pre-review=false 那次启动里才会跑到",
      ownDetail.status === 200 && !!ownDetail.json && !!ownDetail.json.data
        && ownDetail.json.data.isOfficial === false,
      ownDetail.status + " code=" + (ownDetail.json ? ownDetail.json.code : "-")
        + " isOfficial=" + (ownDetail.json && ownDetail.json.data ? ownDetail.json.data.isOfficial : "-"))
  } else {
    const dlgBody = Array.prototype.slice.call(w.document.querySelectorAll(".el-dialog .dlg-body")).map(txt).join(" ")
    check("13", "（预审开着，默认配置走的就是这条）「已提交」没被伪装成失败：auditStatus=PENDING，弹窗里同时写清了「阶段 3 还没有审核台能放行、管理端属任务 6.1」，"
      + " 而且根本没有画那颗点不动的「现在就进去」（FR8.6 的边界在界面上是可见的，不是藏在注释里）",
      !!createdData && createdData.auditStatus === "PENDING" && usable === false
        && dlgBody.indexOf("审核台") >= 0 && dlgBody.indexOf("6.1") >= 0
        && !btnLike(w.document.querySelector(".el-dialog .el-alert"), "现在就进去"),
      "auditStatus=" + (createdData ? createdData.auditStatus : "-") + " body=" + dlgBody.slice(0, 150))
    const pendDetail = await api("GET", "/api/topics/" + createdId, U.token, null)
    await w.__probeRouter.push({ name: "topic-detail", params: { id: createdId } })
    const pendPage = await until13(function () { return docText().indexOf("这个话题还在审核中") >= 0 }, 10000, "topic-pending-page")
    check("13", "深链进一个待审话题是后端 + 前端一起认的账：接口 409/30004，页面给的是「还在审核中 + FR8.6 预审开关默认开着」这句人话，"
      + " 并且不画那个空的头图（拿不到资料就宁可少一块卡片，也不摆一颗点了没反应的关注按钮）",
      pendDetail.status === 409 && !!pendDetail.json && Number(pendDetail.json.code) === 30004
        && pendPage && docText().indexOf("FR8.6") >= 0 && !w.document.querySelector("section.hero"),
      pendDetail.status + " code=" + (pendDetail.json ? pendDetail.json.code : "-")
        + " hero=" + !!w.document.querySelector("section.hero"))
  }
  // ---- ?topic= 预填闭环：话题页点「发帖到该话题」→ 发布页认编号 → 请求体真的带着 topicIds ----
  await w.__probeRouter.push({ name: "topic-detail", params: { id: tid } })
  const backTopic = await until13(function () {
    return route13().name === "topic-detail" && String(route13().params.id) === String(tid)
      && !!w.document.querySelector("section.hero .pub-btn")
  }, 12000, "topic-back-to-fixture")
  const jumpPub = w.document.querySelector("section.hero .pub-btn")
  if (jumpPub) jumpPub.click()
  const onPublish = await until13(function () { return route13().name === "publish" }, 8000, "topic-to-publish")
  const searchNow = decodeURIComponent(w.location.search)
  check("13", "话题页那颗「发帖到该话题」把编号写进了地址栏（/publish?topic=" + tid + "）："
    + " 落点是一个能刷新、能转给同学的地址，不是内存里的一个变量（上一版这里踩过：切 Tab 忘了同步 query）",
    backTopic && !!jumpPub && onPublish && searchNow.indexOf("topic=" + tid) >= 0, "search=" + searchNow)
  const presetOk = await until13(function () {
    return docText().indexOf("已带上话题 # " + T.name) >= 0
  }, 12000, "topic-preset-line")
  check("13", "发布页顶上那行确认语把话题名字念了出来、并且挂的是 is-ok 那一档："
    + " 三态不是摆样子 —— checking 与 miss 各有各的话，这里等到的是「带上了」而不是替用户猜一个",
    presetOk && !!w.document.querySelector(".preset-line.is-ok"),
    "line=" + txt(w.document.querySelector(".preset-line")).slice(0, 80)
      + " cls=" + (w.document.querySelector(".preset-line") ? String(w.document.querySelector(".preset-line").className) : "-"))
  const tagTexts = Array.prototype.slice.call(w.document.querySelectorAll(".composer .w260 .el-tag")).map(txt).join("|")
  const selectText = txt(w.document.querySelector(".composer .w260"))
  check("13", "预填不是只在文案里说了句好话：关联话题那个下拉框上看得到 # " + T.name + "（选中值真进了 form.topicIds）"
    + " —— 顺序也钉住了：草稿恢复在前、合入预填在后，反过来的话一分钟前那份草稿会把手跳进来的这个话题覆盖掉",
    tagTexts.indexOf(T.name) >= 0 || selectText.indexOf(T.name) >= 0,
    "tags=" + tagTexts.slice(0, 70) + " select=" + selectText.slice(0, 70))
  setInput(w.document.querySelector(".composer .title-input input.el-input__inner"), kw + "乙")
  setInput(w.document.querySelector(".composer textarea"), "从话题页跳过来发的第二条 " + kw)
  const postsReqsBefore = reqsStart("POST /api/posts").length
  const sendBtn = btnLike(w.document.querySelector(".composer .actions"), "发布")
  if (sendBtn) sendBtn.click()
  const posted = await until13(function () { return reqsStart("POST /api/posts").length > postsReqsBefore }, 12000, "topic-preset-publish")
  const bodies = w.__bodyLog.filter(function (b) { return b.line === "POST /api/posts" })
    .map(function (b) { return b.body }).join(" || ")
  // 序列化出来的键名是带引号的："topicIds":[1]。上一版拿不带引号的 "topicIds:" 当针，
  // 于是界面、请求、后端全对了，唯独这条断言永远判不过 —— 针写错比判据写松更隐蔽，因为它看起来像在挑业务的错。
  const topicNeedle = '"topicIds":' + JSON.stringify([tid])
  check("13", "点「发布」那一刻的请求体里已经带着 " + topicNeedle + "：整条预填链路一路通到 payload，用户没有再手动挑一次话题"
    + "（这一条只有请求体能证明，界面上那个选中态证明不了它）",
    posted && bodies.indexOf("topicIds") >= 0 && bodies.indexOf(topicNeedle) >= 0,
    "body=" + bodies.slice(0, 240))
  const publishResp = respOf("POST /api/posts")
  const newData = publishResp && publishResp.json ? publishResp.json.data : null
  const toDetail = await until13(function () { return route13().name === "post-detail" }, 12000, "topic-preset-redirect")
  check("13", "后端认下了这个预挂：新帖 status=PUBLISHED，页面按回执里的 id 跳进了详情页（不是丢回列表让用户自己找哪条是刚发的）"
    + "；挂题成功这件事是后端说的，不是前端自己宣布的",
    !!newData && newData.status === "PUBLISHED" && toDetail
      && String(route13().params.id) === String(newData.id),
    "status=" + (publishResp ? publishResp.status : "-") + " st=" + (newData ? newData.status : "-")
      + " route=" + route13().path)
  await w.__probeRouter.push({ name: "topic-detail", params: { id: tid } })
  const bothThere = await until13(function () {
    const now = titles()
    return now.indexOf(kw + "甲") >= 0 && now.indexOf(kw + "乙") >= 0
  }, 15000, "topic-loop-close")
  check("13", "回到这个话题页，两条夹具帖都排在首页里：一条走接口挂题、一条从话题页点出来发的 —— 进话题、发进话题、在话题里看到它，这一整圈闭环通了"
    + "（话题页读的是 post_topic，不是「所有帖子里标题带话题名的那些」）",
    bothThere, "titles=" + titles().join("|").slice(0, 200))
  notes.push("topic 一次性账号：" + nameU + "(" + U.id + ")；有帖话题 " + (T ? T.id + ":" + T.name + "/发帖" + T.postCnt : "-")
    + "；零帖话题 " + (TE ? TE.id + ":" + TE.name : "-"))
  notes.push("topic 夹具帖 id：" + (pubData ? pubData.id : "-") + "(甲·走接口挂题) " + (newData ? newData.id : "-") + "(乙·走 ?topic= 预填)")
  notes.push("topic 新建话题 id=" + createdId + " auditStatus=" + (createdData ? createdData.auditStatus : "-")
    + " usable=" + usable + " —— 预审开关（FR8.6）默认开着，PENDING 是这条路径今天的正常结局")
  notes.push("本段验的是「选中值有没有一路走到 payload」：jsdom 下 el-select 的 popper teleport 在 body 上，所以那条预填断言同时认 .el-tag 与整个 select 的文本，两种画法都不影响判据；点下拉的鼠标路径留给真浏览器")
  notes.push("topic window logs: " + (blogs.length ? blogs.slice(0, 6).join(" || ") : "none"))
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

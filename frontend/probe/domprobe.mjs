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

  const lastText = docText()
  dom.window.close()
  notes.push('tail DOM text: ' + lastText.slice(0, 120))
  notes.push('window logs: ' + (logs.length ? logs.slice(0, 8).join(' || ') : 'none'))
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

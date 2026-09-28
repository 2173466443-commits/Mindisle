// U7「屿屿」AI 对话全链路真浏览器取证（playwright-core + 本机 Chrome）—— 阶段 4 的第六条取证线
//
// 为什么还要再写一个文件（2026-09-24）：
//   前五条线里没有一条真的把消息发给屿屿。routecrawl 在注释里明写「不发消息，避免把危机分级
//   记录写进演示库」，docs/smoke.mjs 那 246 项里搜不到 /api/ai/。于是用户那句
//   「AI 对话那部分还没做好」恰好落在所有证据的缝隙里：路由打得开、输入框在、会话列表在，
//   但**发出去到底有没有逐字流回来、停止生成到底停不停、赞踩到底落没落库、危机卡片到底弹不弹**，
//   此前只有手跑的口头结论。这几件事只能真发一条 + 在 DOM 上按帧采样，所以有了这个文件。
//
// 这个文件第一次跑（2026-09-24 15:46）就抓到四处，全部改掉了：
//   ① 实时流把情绪标签挂在助手气泡上，回看旧会话时却挂在用户气泡上 —— 同一条消息两个位置；
//   ② meta 已经回到 L0，用户气泡上还顶着「本机词表提示 L1」——本机预估该在服务端判过之后让位；
//   ③ 「停止生成」如果在第一个字出来之前按下去，服务端一个字都没写（实测 new conv=0），
//      界面说「未发送成功，没有落库」是**对的**；但如果已经有字出来，半句会带着 interrupted 落库。
//      这两种收尾必须分开测，本文件把「等到有字之后再按」定为正式判据，并回读接口核对落库状态；
//   ④ 侧栏第一条不等于接口第一条（同秒的 last_msg_at 会并排），删除类判据必须按标题定位。
//
// 用法（后端 8080 与前端 5173 都要在跑）：
//   node frontend/probe/aichat.mjs                     常规链路（不打危机词）
//   $env:CRISIS='1'; node frontend/probe/aichat.mjs    追加 L2 危机闭环
//   $env:CRISIS='1'; $env:L3='1'; node ...             再追加一条 L3
// 收尾：本文件新建的会话最后一律删掉，跑完 demo01 的会话列表长度与跑之前一致。
//   ⚠ 这条「长度一致」的前提是基线没撞上会话配额（keep-conversations=50）：配额一到，产品会自己
//   回收最旧的会话，长度类判据就会假红。所以文件里有一段「前提守卫」，基线余量不足时先腾掉
//   最旧的历史探针会话并把 id 打进日志（详见下面 QUOTA/HEADROOM 那段注释）。
//   ⚠ 但 L2/L3 那两条**删不掉 alert_ticket 和站内信**（删会话只删消息，工单是独立生命周期），
//   所以危机段默认关，只在明确要取证时开 —— 这条约束就是 routecrawl 当初不发消息的原因。
// 退出码：任何一条 FAIL 即 1。
import { createRequire } from 'node:module'
import fsSync from 'node:fs'
import path from 'node:path'

const require = createRequire('E:/codex workspace/_cache/node_modules/')
const { chromium } = require('E:/codex workspace/_cache/node_modules/playwright-core')
const CHROME = process.env.CHROME || 'C:/Users/Drbrain/AppData/Local/Google/Chrome/Application/chrome.exe'
const BASE = process.env.BASE || 'http://127.0.0.1:5173'
const ROOT = 'E:/codex workspace/009_心屿AI心理陪伴社区'
const SHOT = process.env.SHOT_DIR || 'E:/codex workspace/_cache/009_mindisle'
const DO_CRISIS = process.env.CRISIS === '1'
const DO_L3 = process.env.L3 === '1'

let nPass = 0
let nFail = 0
const failures = []
function note(s) { console.log('#  ' + s) }
function check(ok, name, read) {
  if (ok) { nPass++; console.log('PASS ' + name + (read ? '   <- ' + read : '')) }
  else { nFail++; failures.push(name); console.log('FAIL ' + name + '   <- ' + (read || '')) }
  return ok
}

// ------------------------------------------------------------ 登录（API 侧清场 + 浏览器侧免登录）
const login = await fetch(BASE + '/api/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    username: 'demo01', password: 'Test1234',
    captchaId: '00000000000000000000000000000000', captchaCode: 'ZZZZ'
  })
}).then((r) => r.json()).catch(() => null)
if (!login || login.code !== 0) {
  console.log('登录没过去（8080 与 5173 的 /api 代理都要在）：' + JSON.stringify(login && login.code))
  process.exit(1)
}
const token = login.data.accessToken
const AUTH = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' }
const listConv = async function () {
  const j = await fetch(BASE + '/api/ai/conversations', { headers: AUTH }).then((r) => r.json()).catch(() => null)
  return j && j.code === 0 ? j.data || [] : null
}
const listMsg = async function (id) {
  const j = await fetch(BASE + '/api/ai/conversations/' + id + '/messages', { headers: AUTH }).then((r) => r.json()).catch(() => null)
  return j && j.code === 0 ? j.data || [] : null
}
const before = await listConv()
if (!before) { console.log('会话列表拉不到，后面的判据没有基线可比'); process.exit(1) }
const beforeIds = new Set(before.map(function (c) { return String(c.id) }))
note('基线：demo01 现有会话 ' + before.length + ' 条，最大 id=' + Math.max.apply(null, before.map(function (c) { return c.id })))

// ------------------------------------------------------------ 前提守卫：会话保留配额（2026-09-28 复跑红过之后新增）
// 这一轮复跑红了两条：「侧栏比基线多一条」和「收尾回到基线长度」。查下来不是功能坏了，
// 是探针的前提没人检查。后端 T4.2 有一条既定行为：create() 建完会话会调
// conversationMapper.softDeleteBeyondQuota(userId, llm.keep-conversations)，
// 活跃会话一旦撞上配额，**产品就把最旧的那几条逻辑删除**（application.yml 的 keep-conversations=50、
// ConversationService.java 里 SIDEBAR_MAX=50 与 create() 尾部那段）。
// demo01 被历轮取证线反复建会话，跑到 09-28 19:25 时基线正好等于 50：本轮新建了 122/123 两条，
// 于是 16:30 由 injection 留下的 65/66 被回收掉，侧栏长度纹丝不动 —— 症状长得像「新建会话没生效」，
// 实际是配额回收把「多一条」吃掉了。修法不是放宽断言（那等于把观测通道自己改瞎），
// 是把前提写成一条会自己失败的守卫：基线必须给本轮最多 3 条新建留出余量；
// 余量不够就先删最旧的历史探针会话腾位置，删掉的 id 与标题全部打进日志留证
// （是软删除，行还在 conversation 里 deleted=1，要还原用一条 UPDATE 就行）。
const QUOTA = 50
const HEADROOM = 3
if (before.length + HEADROOM > QUOTA) {
  const need = before.length + HEADROOM - QUOTA
  const victims = before.slice(before.length - need)
  const trimmed = []
  for (const v of victims) {
    const res = await fetch(BASE + '/api/ai/conversations/' + v.id, { method: 'DELETE', headers: AUTH })
    const j = await res.json().catch(() => null)
    if (j && j.code === 0) trimmed.push(v)
  }
  note('[前提守卫] 基线 ' + before.length + ' 条只剩 ' + (QUOTA - before.length) + ' 条余量，而本轮最多新建 ' + HEADROOM +
    ' 条，会撞上会话配额的自动回收（那是 T4.2 的产品行为，不是 bug）→ 先腾掉最旧的 ' + trimmed.length + ' 条历史探针会话：' +
    trimmed.map(function (v) { return v.id + ':' + v.title.slice(0, 10) }).join(' , '))
  const aliveSet = new Set((((await listConv()) || [])).map(function (c) { return String(c.id) }))
  for (let i = before.length - 1; i >= 0; i--) { if (!aliveSet.has(String(before[i].id))) before.splice(i, 1) }
  beforeIds.clear()
  before.forEach(function (c) { beforeIds.add(String(c.id)) })
}
check(before.length + HEADROOM <= QUOTA,
  '[ai·前提] 基线加上本轮新建上限没有撞上会话保留配额（配额一撞，回收会逻辑删除最旧的会话，长度类判据的前提就不成立了）',
  '基线=' + before.length + ' 配额=' + QUOTA + ' 需留=' + HEADROOM + ' 实留=' + (QUOTA - before.length))

// ------------------------------------------------------------ 第 0 条判据：dev server 吐的模块是不是新的
// 踩过的雷（手册 §14 / 踩坑日志第 13 轮）：改完 .vue 之后 Vite 会继续吐**改之前**的 transform，
// 于是「浏览器里读到的界面文案」证明的是上一次编译，判据会假绿。
// 这里把磁盘源文件里的中文字面量抽出来，和 dev server 返回的模块体逐条比对：
// 界面文案的改动一定伴随字面量变化，少一条就说明服务的是旧模块，直接判失败。
async function freshness(label, diskRel, servedRel) {
  const disk = fsSync.readFileSync(path.join(ROOT, diskRel), 'utf8')
  const lits = [...disk.matchAll(/'([^'\\\n]{4,28})'/g)].map(function (m) { return m[1] })
    .filter(function (x) { return /[\u4e00-\u9fa5]/.test(x) })
  const tail = lits.slice(-8)
  let served = ''
  let status = 0
  try {
    const r = await fetch(BASE + servedRel)
    status = r.status
    served = await r.text()
  } catch (e) { status = -1 }
  const missing = tail.filter(function (x) { return served.indexOf(x) < 0 })
  check(status === 200 && served.length > 200 && missing.length === 0,
    '[stale] ' + label + ' 的 dev server 模块体和磁盘源码同步（抽样 ' + tail.length + ' 条中文字面量全在）',
    'http=' + status + ' 模块字节=' + served.length + ' 缺=' + JSON.stringify(missing))
}

// ------------------------------------------------------------ 浏览器
const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const ctx = await browser.newContext({ viewport: { width: 1500, height: 1000 }, locale: 'zh-CN' })
await ctx.addInitScript(function (t) { window.localStorage.setItem('mindisle_token', t) }, token)
const page = await ctx.newPage()
const bad = { console: [], pageerror: [], http: [], reqfail: [] }
let deliberateAborts = 0
page.on('console', function (m) { const t = m.text(); if (m.type() === 'error' && !/favicon/i.test(t)) bad.console.push(t.slice(0, 240)) })
page.on('pageerror', function (e) { bad.pageerror.push(String(e.message || e).slice(0, 240)) })
page.on('response', function (r) {
  const u = r.url()
  if (r.status() >= 400 && !/favicon|\.woff2?|\.png|\.ico/i.test(u)) bad.http.push(r.status() + ' ' + u.replace(BASE, '').slice(0, 140))
})
page.on('requestfailed', function (r) { bad.reqfail.push(r.url().replace(BASE, '').slice(0, 140) + ' :: ' + (r.failure() ? r.failure().errorText : '?')) })

await page.goto(BASE + '/ai', { waitUntil: 'load' })
await page.waitForTimeout(2200)
await freshness('ChatView.vue', 'frontend/src/views/ai/ChatView.vue', '/src/views/ai/ChatView.vue')
await freshness('api/ai.js', 'frontend/src/api/ai.js', '/src/api/ai.js')

// 每条气泡的读数。逐行给而不是只给「最后一条」：② 那类「标签挂错气泡」的 bug
// 只有把 role + pill + meta 三样一起摊开才看得见。
function state() {
  return page.evaluate(function () {
    const rows = [...document.querySelectorAll('.stream-box .row')]
    const pick = function (el, sel) { return el && el.querySelector(sel) ? el.querySelector(sel) : null }
    const detail = rows.map(function (r) {
      const t = pick(r, '.txt')
      const mt = pick(r, '.meta')
      const pl = pick(r, '.pill')
      return {
        role: r.className.replace('row', '').trim(),
        len: t ? t.innerText.replace(/\u258d/g, '').trim().length : 0,
        text: t ? t.innerText.replace(/\u258d/g, '').trim() : '',
        meta: mt ? mt.innerText.replace(/\s+/g, ' ').trim() : '',
        pill: pl ? pl.innerText.replace(/\s+/g, '') : ''
      }
    })
    const last = detail[detail.length - 1] || { role: '', len: 0, text: '', meta: '', pill: '' }
    const crisisEl = document.querySelector('.crisis')
    const badgeEl = document.querySelector('.crisis .badge')
    const phoneEl = document.querySelector('.crisis .phone')
    const linkEl = document.querySelector('.crisis a.link')
    const topEl = document.querySelector('.topbar')
    const lastRow = rows[rows.length - 1]
    return {
      rows: detail,
      count: rows.length,
      lastRole: last.role,
      lastText: last.text,
      lastLen: last.len,
      lastMeta: last.meta,
      streaming: last.meta.indexOf('正在生成') >= 0,
      interrupted: last.meta.indexOf('生成中断') >= 0,
      offline: last.meta.indexOf('离线模式') >= 0,
      rewritten: last.meta.indexOf('安全改写') >= 0,
      lastFb: lastRow ? [...lastRow.querySelectorAll('.fb button')].map(function (b) { return (b.className.indexOf('on') >= 0 ? '+' : '-') + b.innerText.trim() }) : [],
      toast: [...document.querySelectorAll('.el-message')].map(function (x) { return x.innerText.replace(/\s+/g, ' ').trim() }).join(' | '),
      convs: [...document.querySelectorAll('.conv .ct')].map(function (x) { return x.innerText.trim() }),
      crisis: crisisEl ? crisisEl.innerText.replace(/\s+/g, ' ').slice(0, 260) : '',
      badge: badgeEl ? badgeEl.innerText.trim() : '',
      phone: phoneEl ? phoneEl.innerText.trim() : '',
      helpLink: linkEl ? linkEl.innerText.trim() : '',
      top: topEl ? topEl.innerText.replace(/\s+/g, ' ') : '',
      consent: document.body.innerText.indexOf('还没有同意') >= 0,
      warns: [...document.querySelectorAll('.warn')].map(function (x) { return x.innerText.replace(/\s+/g, ' ').slice(0, 140) }),
      disclaimer: document.querySelector('.disclaimer') ? document.querySelector('.disclaimer').innerText.replace(/\s+/g, ' ') : ''
    }
  })
}
function rowOf(st, role) { return st.rows.filter(function (r) { return r.role.indexOf(role) >= 0 }).pop() || null }

async function sendText(text) {
  await page.fill('.composer textarea', text)
  await page.locator('.btns button', { hasText: '发送' }).first().click()
}

// 按帧采样。真流式的特征是**同一轮里出现多个不同长度**；
// 「等整段回来再一次贴上去」（axios 兜底、或 SSE 被代理缓冲）只会采到 1 个长度。
async function waitStream(timeoutMs) {
  const seen = []
  const deadline = Date.now() + timeoutMs
  let st = await state()
  while (Date.now() < deadline) {
    st = await state()
    if (st.lastRole.indexOf('ai') >= 0 && seen[seen.length - 1] !== st.lastLen) seen.push(st.lastLen)
    if (st.streaming || !st.lastLen) { await page.waitForTimeout(110); continue }
    await page.waitForTimeout(280)
    const again = await state()
    if (again.streaming) { continue }
    return { seen: seen, st: again }
  }
  return { seen: seen, st: st, timedOut: true }
}

// ------------------------------------------------------------ A. 首屏基线
const base = await state()
check(base.count === 0, '[ai] 进来先是一条空白对话区（有残留消息说明上次离开时的会话被偷偷续上了）', 'rows=' + base.count)
check(base.convs.length === before.length,
  '[ai] 侧栏会话数与接口返回一致（列表不是装饰，它读的是真数据）',
  'DOM=' + base.convs.length + ' API=' + before.length)
check(base.disclaimer.length > 10 && /不构成医疗诊断|不能替代|危机|12356/.test(base.disclaimer),
  '[ai] 页面底部有常驻免责脚注（T4.18：这句话不是弹一次就没了的提示条）',
  base.disclaimer.slice(0, 90))
check(!base.consent, '[ai] 当前账号已给过敏感信息同意，没有出现「去授权」的拦路横幅', JSON.stringify(base.warns))

// ------------------------------------------------------------ B. 发一条普通消息，看它真流回来
const msg1 = '我最近总是失眠，白天没精神，心情很低落。'
check(await page.locator('.btns button', { hasText: '发送' }).first().isDisabled().catch(function () { return false }),
  '[ai] 输入框为空时发送按钮是禁用的（不打字就点不出一次空请求）')
await sendText(msg1)
await page.waitForTimeout(700)
const mine0 = rowOf(await state(), 'me')
check(!!mine0 && mine0.text === msg1,
  '[ai] 点「发送」之后自己那句立刻上屏且文字完整（不依赖服务端回包，弱网下也不会「我说的话不见了」）',
  mine0 ? 'len=' + mine0.len : 'rows=无 me')
const b = await waitStream(70000)
check(b.seen.filter(function (x) { return x > 0 }).length >= 3,
  '[ai] 屿屿那句是**逐字流出来**的（同一轮采到 ≥3 个不同长度），不是整段回来一次贴上去',
  '长度序列=' + JSON.stringify(b.seen.slice(0, 14)) + (b.timedOut ? ' [超时]' : ''))
check(b.st.lastRole.indexOf('ai') >= 0 && b.st.lastLen >= 15,
  '[ai] 回复完整落在最后一条气泡里且不是空的',
  'len=' + b.st.lastLen + ' role=' + b.st.lastRole)
check(b.st.streaming === false, '[ai] 流结束后「正在生成」的标记收掉了（收掉才说明 done 帧真的被处理）', b.st.lastMeta.slice(0, 130))
check(/首字\s*\d+\s*ms[\s\S]{0,8}全篇\s*\d+\s*ms/.test(b.st.lastMeta),
  '[ai] 气泡下面写着真实耗时（done 帧带回的 firstTokenMs/latencyMs，不是前端自己估的）', b.st.lastMeta.slice(0, 150))
check(b.st.offline === false,
  '[ai] 这一轮不是离线降级（degraded=false，走的是真模型；只有模型不可用时才该出现「离线模式」）', b.st.lastMeta.slice(0, 150))
const after1 = await state()
const meRow1 = rowOf(after1, 'me')
const aiRow1 = rowOf(after1, 'ai')
check(after1.convs.length === base.convs.length + 1,
  '[ai] 侧栏多出这一条新会话（服务端真的建了 conversation，不是前端起了个空壳）',
  '前=' + base.convs.length + ' 后=' + after1.convs.length)
const newTitle = after1.convs[0] || ''
check(newTitle.length > 0 && newTitle.length <= 20 && msg1.indexOf(newTitle.slice(0, 8)) === 0,
  '[ai] 新会话标题取自我这句话的开头且截到 TITLE_MAX=20 以内（不是「新的对话」占位符）',
  '标题=' + JSON.stringify(newTitle) + ' 长度=' + newTitle.length)
check(!!meRow1 && /难过|低落|焦|喜|怒|平静|恐惧|惊讶|羞耻/.test(meRow1.pill),
  '[ai] **用户那句**的气泡下方出现中文情绪标签（词典通道 DUT 词面 + 否定/程度修正的结果，挂在 me 而不是 ai 上）',
  'me.pill=' + JSON.stringify(meRow1 && meRow1.pill) + ' meta=' + (meRow1 ? meRow1.meta.slice(0, 90) : ''))
check(!!meRow1 && /词典初判/.test(meRow1.meta), '[ai] 标签旁边写明它是词典通道给的（不写来源等于让用户猜）', meRow1.meta.slice(0, 120))
check(!!aiRow1 && aiRow1.pill === '', '[ai] 助手那条气泡不再重复挂情绪标签（一次判定只出现一次，回看旧会话时也是这个位置）', 'ai.pill=' + JSON.stringify(aiRow1 && aiRow1.pill))
check(!!meRow1 && meRow1.meta.indexOf('本机词表提示') < 0,
  '[ai] 服务端已经判过这一轮之后，用户气泡上不再顶着「本机词表提示 L?」——本机预估是断网兜底，不是第二把尺子',
  meRow1 ? meRow1.meta.slice(0, 120) : 'no me row')
check(!!meRow1 && meRow1.meta.indexOf('未发送成功') < 0, '[ai] 服务端确认收到之后，用户那句不会挂着「未发送成功，没有落库」', meRow1 ? meRow1.meta.slice(0, 120) : '')

// ------------------------------------------------------------ C. 赞踩：点了要亮，重开要在
await page.locator('.row.ai').last().locator('.fb button').first().click()
await page.waitForTimeout(900)
const fbBtn = page.locator('.row.ai').last().locator('.fb button').first()
const fbClass = await fbBtn.getAttribute('class')
const fbState = await state()
const fbAi = rowOf(fbState, 'ai')
check((fbClass || '').indexOf('on') >= 0,
  '[ai] 点「有用」之后按钮变高亮（前端先给反馈，才谈得上下面那条持久化判断）',
  'class=' + fbClass + ' lastFb=' + JSON.stringify(fbState.lastFb))
const conv14title = newTitle
await page.locator('.chip.new').click()
await page.waitForTimeout(700)
check((await state()).count === 0, '[ai] 点「＋ 新建」把聊天区清空（残留会把两条对话缝成一屏看）')
await page.locator('.conv', { hasText: conv14title }).first().click()
await page.waitForTimeout(1600)
const reopened = await state()
const reMe = rowOf(reopened, 'me')
const reAi = rowOf(reopened, 'ai')
check(reopened.count >= 2, '[ai] 从侧栏点开旧会话能拉回消息（侧栏不只是能点，GET messages 真返了内容）', 'rows=' + reopened.count)
check(reopened.lastFb.some(function (x) { return x.indexOf('+有用') === 0 }),
  '[ai] 重开之后「有用」仍然亮着 —— 赞踩落在 chat_message.feedback 而不是前端内存',
  JSON.stringify(reopened.lastFb))
check(!!reMe && reMe.pill === (meRow1 ? meRow1.pill : ''),
  '[ai] 实时流和刷新后看到的情绪标签在**同一条气泡的同一个位置**（这条曾经不一致：当场挂助手、刷新挂用户）',
  '刷新后 me.pill=' + JSON.stringify(reMe && reMe.pill) + ' 当时 me.pill=' + JSON.stringify(meRow1 && meRow1.pill))
check(!!reAi && reAi.pill === '', '[ai] 刷新后助手气泡同样没有情绪标签', 'ai.pill=' + JSON.stringify(reAi && reAi.pill))

// ------------------------------------------------------------ D. 停止生成（等到真出字之后再按）
const longAsk = '帮我把调整睡眠的办法逐条写清楚，请至少写十二条，每条都展开说一下为什么。'
await page.locator('.chip.new').click()
await page.waitForTimeout(500)
await sendText(longAsk)
let st0 = await state()
let waitMs = 0
while (waitMs < 45000 && !(st0.lastRole.indexOf('ai') >= 0 && st0.lastLen >= 12 && st0.streaming)) {
  await page.waitForTimeout(80); waitMs += 80; st0 = await state()
}
check(st0.lastRole.indexOf('ai') >= 0 && st0.lastLen >= 12,
  '[ai] 「停止生成」是在**已经有字出来**之后按下的（不然测的是断网取消，不是中断生成）',
  '等了 ' + waitMs + 'ms role=' + st0.lastRole + ' len=' + st0.lastLen)
const stopBtn = page.locator('.btns button', { hasText: '停止生成' }).first()
const stopVisible = await stopBtn.isVisible().catch(function () { return false })
check(stopVisible, '[ai] 生成过程中出现「停止生成」按钮（busy.send 驱动，不是一句写在文档里的空话）', 'streaming=' + st0.streaming)
if (stopVisible) {
  deliberateAborts++
  await stopBtn.click()
  await page.waitForTimeout(800)
  const s1 = await state()
  await page.waitForTimeout(1500)
  const s2 = await state()
  check(s2.streaming === false && s1.lastLen === s2.lastLen && s1.lastLen > 0,
    '[ai] 点「停止生成」之后文字**不再增长**（两次读数等长），SSE 连接是真被掐掉的',
    't+0.8s=' + s1.lastLen + ' t+2.3s=' + s2.lastLen)
  check(s2.lastRole.indexOf('ai') >= 0 && s2.interrupted,
    '[ai] 停止之后助手气泡留着「生成中断」的痕迹（不是假装没发生过，也不是把气泡抹掉）',
    'role=' + s2.lastRole + ' meta=' + s2.lastMeta.slice(0, 130))
  check(s2.count >= 2 && s2.lastLen > 0,
    '[ai] 中断的半句仍然在 DOM 上（ChatService 按 interrupted:client-cancel 存已生成部分）',
    'rows=' + s2.count + ' len=' + s2.lastLen)
  check(/已停止生成/.test(s2.toast), '[ai] 按下去给了「已停止生成」这句人话回执', 'toast=' + s2.toast.slice(0, 80))
  // 回读接口：UI 说半句落库了，就得能在服务端查到那一行 interrupted=true。
  const created2 = (await listConv()) || []
  const t2 = created2.filter(function (c) { return !beforeIds.has(String(c.id)) })
  const target2 = t2[0]
  if (target2) {
    const rows2 = (await listMsg(target2.id)) || []
    const ai = rows2.filter(function (m) { return m.role === 'assistant' }).pop()
    check(rows2.length >= 2 && !!ai && ai.interrupted === true && (ai.content || '').length > 0,
      '[ai·服务端] 半句真的落库了：assistant 行 interrupted=' + (ai ? ai.interrupted : 'n/a'),
      'conv=' + target2.id + ' rows=' + rows2.length + ' assistant len=' + (ai ? (ai.content || '').length : 0) + ' interrupted=' + (ai ? ai.interrupted : null))
    const u = rows2.filter(function (m) { return m.role === 'user' }).pop()
    check(!!u && !!u.emotionLabel, '[ai·服务端] 用户那行的情绪标签也落了库（回看时标签的来源就是它）',
      'user emotion=' + (u ? u.emotionLabel : null) + ' channel=' + (u ? u.emotionChannel : null))
  } else {
    check(false, '[ai·服务端] 半句真的落库了', '找不到本轮新建的会话，没法回读')
    check(false, '[ai·服务端] 用户那行的情绪标签也落了库', '同上')
  }
} else {
  const s = await waitStream(30000)
  note('回复在按钮出现前就结束了：seen=' + JSON.stringify(s.seen))
  check(false, '[ai] 生成过程中出现「停止生成」按钮', '没采到生成窗口')
}

// ------------------------------------------------------------ E. 删除（两段式 + 按标题定位，不按位置）
const created = (await listConv()) || []
const fresh = created.filter(function (c) { return !beforeIds.has(String(c.id)) })
check(fresh.length >= 2, '[ai] 本轮真实新建的会话数（既是要删的清单，也是 B/D 两段各自建单的证据）',
  '新=' + fresh.map(function (x) { return x.id + ':' + x.title.slice(0, 6) }).join(' , '))
const victim = fresh[0]
await page.locator('.conv', { hasText: victim.title }).first().click()
await page.waitForTimeout(1200)
const del = page.locator('.conv', { hasText: victim.title }).first().locator('.del')
await del.click()
const armedTxt = (await del.innerText()).trim()
check(/确认/.test(armedTxt), '[ai] 第一次点删除只是把按钮变成「确认」（两段式，防误删）', armedTxt)
await del.click()
await page.waitForTimeout(1300)
const afterDel = (await listConv()) || []
check(!afterDel.some(function (c) { return String(c.id) === String(victim.id) }),
  '[ai] 第二次点下去这条真的从服务端消失了（DELETE 通了）',
  '目标=' + victim.id + ' 现在=' + afterDel.map(function (c) { return c.id }).join(','))
check((await state()).convs.length === afterDel.length,
  '[ai] 删完侧栏也少了同一行（DOM 与接口在删除之后仍然一致，不是只删了服务端）',
  'DOM=' + (await state()).convs.length + ' API=' + afterDel.length)

// ------------------------------------------------------------ R. 会话重命名（FR2.1 的最后一块缺口，2026-09-24 补齐）
// 为什么单开这一段：需求文档 §6.6 的 FR2.1 写的是「会话可新建、可回看、可重命名、可删除」，
// 而本轮之前四件里只做了三件 —— PATCH /api/ai/conversations/{id} 和侧栏那个 ✎ 都是这一轮补的。
//
// 两条血写的定位纪律（都是这一段自己踩出来的）：
// ① 按「接口顺序的下标」定位，不按标题定位。演示库里 seed 与历轮探针建过同名会话
//    （实测 #1/#2 标题逐字相同），上一版按标题 .first() 定位，被判据自己踩中一次假失败。
// ② 每次点 ✎ 之后先核对「输入框预填的标题 == 我预期这一条现在的标题」，不对就立刻 Esc 收手，
//    绝不把 PATCH 发出去。上一版在证伪跑里因为界面停在编辑态、按标题回捞到了别的行，
//    把一次改名打到了会话 #1 上（改完还得手工 UPDATE 回去）—— 取证脚本不许有这种杀伤半径。
const patchCalls = []
page.on('request', function (rq) {
  if (rq.method() === 'PATCH' && rq.url().indexOf('/api/ai/conversations/') >= 0) {
    patchCalls.push(rq.url().replace(BASE, ''))
  }
})
async function sideRows() {
  return page.evaluate(function () {
    return [...document.querySelectorAll('.conv')].map(function (el) {
      const inp = el.querySelector('.ct-input')
      const ct = el.querySelector('.ct')
      return {
        editing: !!inp,
        on: el.className.indexOf(' on') >= 0,
        value: inp ? inp.value : '',
        shown: ct ? ct.innerText.trim() : '',
        maxlength: inp ? Number(inp.getAttribute('maxlength') || 0) : 0
      }
    })
  })
}
const countTitle = function (rows, t) { return rows.filter(function (x) { return x.shown === t }).length }
const editCount = function (rows) { return rows.filter(function (x) { return x.editing }).length }
const shownAt = function (rows, i) { return rows[i] ? rows[i].shown : '' }
const onIndexOf = function (rows) {
  const hit = rows.map(function (x, i) { return x.on ? i : -1 }).filter(function (i) { return i >= 0 })
  return hit.length === 1 ? hit[0] : -1
}
/**
 * 安全地进入某一行的编辑态：点 ✎ → 读回预填标题 → 与「这条会话当前应有的标题」核对。
 * 对不上就 Esc 并返回 ok=false，让判据去红，而不是把改动写进别人的行。
 */
async function openRenameAt(idx, expectTitle) {
  await page.locator('.conv').nth(idx).locator('.rn').click()
  await page.waitForTimeout(450)
  const rows = await sideRows()
  const cur = rows[idx]
  if (!cur || !cur.editing || cur.value !== expectTitle) {
    await page.keyboard.press('Escape')
    await page.waitForTimeout(350)
    return { ok: false, rows: await sideRows(), cur: cur || null }
  }
  return { ok: true, rows: rows, cur: cur }
}
// 服务端某一行的当前标题（用来确认「我改的就是这一条」）
const srvTitleOf = async function (id) {
  const rows = (await listConv()) || []
  const hit = rows.filter(function (c) { return String(c.id) === String(id) })
  return hit.length === 1 ? hit[0].title : null
}
let renamePool = ((await listConv()) || []).filter(function (c) { return !beforeIds.has(String(c.id)) })
if (!renamePool.length) {
  note('[ai·rename] 前面几段把本轮会话删干净了，补发一句话建一条再测重命名')
  await page.locator('.chip.new').click()
  await page.waitForTimeout(500)
  await sendText('帮我列三条调整睡眠的办法。')
  await waitStream(45000)
  renamePool = ((await listConv()) || []).filter(function (c) { return !beforeIds.has(String(c.id)) })
}
check(renamePool.length > 0, '[ai·rename] 有一条本轮新建的会话可以拿来改标题（不碰 demo01 的旧数据）',
  '可测=' + renamePool.map(function (c) { return c.id }).join(','))
const rc = renamePool[0]
const srvApi = (await listConv()) || []
const rIdx = srvApi.findIndex(function (c) { return String(c.id) === String(rc.id) })
const rows0 = await sideRows()
check(rIdx >= 0 && rIdx < rows0.length && rows0.length === srvApi.length,
  '[ai·rename] 能按接口顺序给这条会话定到下标（侧栏与 GET 列表同源同序，改名判据才有靶子）',
  '下标=' + rIdx + ' 侧栏行数=' + rows0.length + ' 接口行数=' + srvApi.length)
const rnCount = await page.locator('.conv .rn').count()
check(rnCount === rows0.length && rnCount > 0,
  '[ai·rename] 侧栏每一行都长着 ✎（入口不是只给某一条开的小灶）', '✎=' + rnCount + ' .conv=' + rows0.length)
check(editCount(rows0) === 0,
  '[ai·rename] 没点之前页面上没有输入框（重命名是点出来的，不是一直占位）', 'editing=' + editCount(rows0))
// 先把这条选中：不选中的话「点 ✎ 会不会顺手切换会话」就是在两条空值之间比，假绿。
await page.locator('.conv').nth(rIdx).click()
await page.waitForTimeout(1300)
const rowsSel = await sideRows()
const on0 = onIndexOf(rowsSel)
check(on0 === rIdx, '[ai·rename] 改名前先选中了这一条（给「有没有顺手切换会话」一个非空且正确的基线）',
  '选中下标=' + on0 + ' 期望=' + rIdx + ' 文本=' + JSON.stringify(shownAt(rowsSel, on0).slice(0, 12)))
const dupOld0 = countTitle(rowsSel, rc.title)
const total0 = rowsSel.length
// 这一段整体兜一个 try：中途任何一步抛错（例如靶子不对导致等不到输入框），
// 都必须把红记下来并继续往下走 —— 本文件后面还有危机段与「删掉本轮新建会话」的收尾，
// 让取证脚本半路崩掉就等于往演示库里留下改过名的脏会话（上一轮真的留了一条）。
try {
  const rSideTitle = '睡眠调整清单（改名取证）'
  // —— R1 进入编辑态：入口、预填、上限、不误切换
  const g1 = await openRenameAt(rIdx, rc.title)
  check(g1.ok && g1.cur && g1.cur.editing && g1.cur.value === rc.title,
    '[ai·rename] 点 ✎ 出现行内输入框，而且预填的就是这一条当前的标题（对不上就 Esc，绝不改到别人的行上）',
    '预填=' + JSON.stringify(g1.cur ? g1.cur.value : null) + ' 期望=' + JSON.stringify(rc.title))
  check(g1.ok && g1.cur.maxlength === 30,
    '[ai·rename] 输入框的 maxlength 就是后端 TITLE_MAX=30（前后端同一个上限，不各写一套）',
    'maxlength=' + (g1.cur ? g1.cur.maxlength : 'n/a'))
  check(editCount(g1.rows) === 1,
    '[ai·rename] 全页同时只有一个输入框（用行内 input，不弹框、不为此引 ElMessageBox）',
    'input=' + editCount(g1.rows))
  check(onIndexOf(await sideRows()) === on0,
    '[ai·rename] 点 ✎ 只进编辑态，没有顺手切换会话（@click.stop 挡住了 openConversation）',
    '选中下标=' + onIndexOf(await sideRows()) + ' 基线=' + on0)
  await page.locator('.conv-box .ct-input').fill(rSideTitle)
  await page.keyboard.press('Enter')
  await page.waitForTimeout(1500)
  const stR = await state() // 先读 toast：ElMessage 三秒就自己消失，晚读会假失败
  const rows2 = await sideRows()
  check(shownAt(rows2, rIdx) === rSideTitle,
    '[ai·rename] 回车之后侧栏那一行立刻换成新标题', '该行=' + JSON.stringify(shownAt(rows2, rIdx).slice(0, 14)))
  check(countTitle(rows2, rc.title) === dupOld0 - 1,
    '[ai·rename] 旧标题在侧栏少了一条（按计数差判 —— 库里本来就有同名会话，存在性判断不成立）',
    '同名 改前=' + dupOld0 + ' 改后=' + countTitle(rows2, rc.title))
  check(countTitle(rows2, rSideTitle) === 1,
    '[ai·rename] 新标题在侧栏恰好一条（是原地换掉，不是又追加一行）', '条数=' + countTitle(rows2, rSideTitle))
  const srv2 = (await listConv()) || []
  check(srv2.length === total0,
    '[ai·rename] 改名不增不减会话总数（PATCH 只 UPDATE title，没有偷偷 INSERT/DELETE）',
    '接口 改前=' + total0 + ' 改后=' + srv2.length)
  check(await srvTitleOf(rc.id) === rSideTitle,
    '[ai·rename] 服务端 id 没变而标题变了（PATCH 真的改了这一行，且只改了这一行）',
    'srv=' + JSON.stringify(await srvTitleOf(rc.id)))
  check(patchCalls.length === 1,
    '[ai·rename] 一次回车只发出一次 PATCH（Enter 与 blur 都会提交，先把 renaming 清空才拦得住双发）',
    'PATCH=' + JSON.stringify(patchCalls))
  await page.reload({ waitUntil: 'load' })
  await page.waitForTimeout(2000)
  const rowsRl = await sideRows()
  check(shownAt(rowsRl, rIdx) === rSideTitle && countTitle(rowsRl, rc.title) === dupOld0 - 1,
    '[ai·rename] 刷新之后新标题仍然在、旧标题仍然少一条（FR2.1 要的是持久化，不是前端内存里的一次改名）',
    '该行=' + JSON.stringify(shownAt(rowsRl, rIdx)) + ' 旧标题条数=' + countTitle(rowsRl, rc.title))
  check(editCount(rows2) === 0,
    '[ai·rename] 提交之后输入框收回去（编辑态生命周期结束，不会留一个空框在原地）', 'editing=' + editCount(rows2))
  check(String(stR.toast).indexOf('现在叫') >= 0,
    '[ai·rename] 改成功给了一句带新标题的人话回执', 'toast=' + JSON.stringify(stR.toast).slice(0, 130))
  // —— R2 空白标题：前端就该拦下，绝不许把原标题抹掉
  const g2 = await openRenameAt(rIdx, rSideTitle)
  check(g2.ok, '[ai·rename] 第二次进编辑态，靶子仍然是这一条（预填 == 上一次存下来的标题）',
    '预填=' + JSON.stringify(g2.cur ? g2.cur.value : null))
  await page.locator('.conv-box .ct-input').fill('   ')
  await page.keyboard.press('Enter')
  await page.waitForTimeout(1200)
  const rowsB = await sideRows()
  check(shownAt(rowsB, rIdx) === rSideTitle,
    '[ai·rename] 把标题清成三个空格再回车，那一行仍是原标题（不会出现一行没有字的会话）',
    '该行=' + JSON.stringify(shownAt(rowsB, rIdx)))
  check(patchCalls.length === 1,
    '[ai·rename] 空白标题在前端就被拦住，一个 PATCH 都没多发（后端 10001 只是第二道闸）',
    'PATCH=' + JSON.stringify(patchCalls))
  check(await srvTitleOf(rc.id) === rSideTitle,
    '[ai·rename] 服务端也还是原标题（空标题这条路前后端都没有写坏数据）',
    'srv=' + JSON.stringify(await srvTitleOf(rc.id)))
  // —— R3 失焦提交：填一半点别处 == 按回车
  const blurTitle = '点别处也要能保存'
  await openRenameAt(rIdx, rSideTitle)
  await page.locator('.conv-box .ct-input').fill(blurTitle)
  await page.locator('.chip.new').click()
  await page.waitForTimeout(1400)
  const rowsBl = await sideRows()
  check(shownAt(rowsBl, rIdx) === blurTitle,
    '[ai·rename] 失焦（点别处）同样会保存：填一半走开不会悄悄丢掉',
    '该行=' + JSON.stringify(shownAt(rowsBl, rIdx)) + ' 服务端=' + JSON.stringify(await srvTitleOf(rc.id)))
  check(patchCalls.length === 2,
    '[ai·rename] 失焦这一次仍然只发了 1 个 PATCH（Enter 和 blur 两条路共用同一个提交闸）',
    'PATCH=' + JSON.stringify(patchCalls))
  // —— R4 超长标题：后端截断
  const longTitle = '长标题截断验证'.repeat(7)
  const g4 = await openRenameAt(rIdx, blurTitle)
  check(g4.ok, '[ai·rename] 改超长之前再核对一次靶子（上一步是失焦提交的，标题得是那一个）',
    '预填=' + JSON.stringify(g4.cur ? g4.cur.value : null))
  await page.locator('.conv-box .ct-input').fill(longTitle)
  await page.keyboard.press('Enter')
  await page.waitForTimeout(1500)
  const rowsL = await sideRows()
  const keptTitle = shownAt(rowsL, rIdx)
  check(longTitle.length > 30 && keptTitle.length === 30 && keptTitle.indexOf('长标题截断验证') === 0,
    '[ai·rename] ' + longTitle.length + ' 个字的标题被截成 30 个字（后端 SafetyGuard.cut 兜底，不靠前端自觉）',
    '输入长度=' + longTitle.length + ' 该行长度=' + keptTitle.length)
  // —— R5 Esc 取消：不许先保存再假装没保存
  const g5 = await openRenameAt(rIdx, keptTitle)
  check(g5.ok, '[ai·rename] 试取消之前靶子仍然正确（预填 == 截断后的 30 字标题）',
    '预填长度=' + (g5.cur ? g5.cur.value.length : 'n/a'))
  await page.locator('.conv-box .ct-input').fill('这条草稿不该被保存')
  await page.keyboard.press('Escape')
  await page.waitForTimeout(1000)
  const rowsE = await sideRows()
  check(editCount(rowsE) === 0,
    '[ai·rename] Esc 把输入框收掉（取消有出口，不会被编辑态困住）', 'editing=' + editCount(rowsE))
  check(shownAt(rowsE, rIdx) === keptTitle,
    '[ai·rename] Esc 之后显示的还是上一个存下来的标题（不是草稿，也不是空白）',
    '该行=' + JSON.stringify(shownAt(rowsE, rIdx).slice(0, 14)))
  check(patchCalls.length === 3,
    '[ai·rename] Esc 这条路一个 PATCH 都没发（取消就是取消：Enter 1 次 + 失焦 1 次 + 超长 1 次 = 3）',
    'PATCH=' + JSON.stringify(patchCalls))
  check(await srvTitleOf(rc.id) === keptTitle,
    '[ai·rename] 界面标题与服务端标题逐字相等（改名这条链路 DOM 与库里一致）',
    'srv=' + JSON.stringify(await srvTitleOf(rc.id)) + ' 长度=' + keptTitle.length)
} catch (e) {
  check(false, '[ai·rename] 改名这一段跑到了最后（半路抛异常会连带跳过收尾清场，所以必须兜住）',
    String((e && e.message) || e).replace(/\s+/g, ' ').slice(0, 150))
}
check(patchCalls.every(function (one) { return one === '/api/ai/conversations/' + rc.id }),
  '[ai·rename] 这几次 PATCH 的 URL 逐字指向这一条会话（取证脚本的杀伤半径被钉在自己的靶子上）',
  'PATCH=' + JSON.stringify(patchCalls))
note('[ai·rename] 本轮改名链路共发出 ' + patchCalls.length + ' 次 PATCH，全部指向 conv=' + rc.id)
// ------------------------------------------------------------ F. 危机闭环（只在 CRISIS=1 时跑，会建真工单）
if (DO_CRISIS) {
  const crisisMsg = '我感觉撑不下去了，活着没意思，每天都很累。'
  await page.locator('.chip.new').click()
  await page.waitForTimeout(500)
  await sendText(crisisMsg)
  const c = await waitStream(70000)
  const cMe = rowOf(c.st, 'me')
  check(c.st.crisis.length > 20, '[ai·L2] 危机轮次里求助卡片出现了（不只是一句文案，是那张带号码的卡）', c.st.crisis.slice(0, 170))
  check(c.st.phone.indexOf('12356') >= 0, '[ai·L2] 卡片上的热线就是 12356（读 sys_config，写死会在改配置时害人）', 'phone=' + c.st.phone)
  check(/L2|需要专业支持/.test(c.st.badge), '[ai·L2] 等级徽标停在 L2 档', 'badge=' + c.st.badge)
  check(c.st.helpLink.indexOf('求助') >= 0, '[ai·L2] 卡片里带「完整求助页」入口（免登录也能进，见 routecrawl 的 help-anon）', 'link=' + c.st.helpLink)
  check(/后端判定\s*L2/.test(c.st.top), '[ai·L2] 顶栏小字同步显示「后端判定 L2」', c.st.top.slice(0, 120))
  check(!!cMe && /屿屿判定\s*L2/.test(cMe.meta), '[ai·L2] 用户那句下方写的是**屿屿判定**而不是本机词表提示（服务端结论到场后本机预估让位）', cMe ? cMe.meta.slice(0, 120) : '')
  note('[ai·L2] 回复原文：' + c.st.lastText.slice(0, 150))
  const cs = ((await listConv()) || []).filter(function (x) { return !beforeIds.has(String(x.id)) })
  for (const x of cs) {
    const rw = (await listMsg(x.id)) || []
    note('[ai·L2] 服务端 conv=' + x.id + ' risk=' + JSON.stringify(rw.map(function (m) { return m.role + ':' + m.riskLevel })))
  }
  if (DO_L3) {
    const l3Msg = '我已经写好遗书了，今晚就把安眠药吃了，这样就能一了百了。'
    await page.locator('.chip.new').click()
    await page.waitForTimeout(500)
    await sendText(l3Msg)
    const l3 = await waitStream(70000)
    check(/L3|立即求助/.test(l3.st.badge), '[ai·L3] 最高一档把徽标升到 L3 并给出立即求助文案', 'badge=' + l3.st.badge + ' phone=' + l3.st.phone)
    note('[ai·L3] 回复原文：' + l3.st.lastText.slice(0, 150))
  }
}

// ------------------------------------------------------------ G. 清场
const left = (await listConv()) || []
const mine2 = left.filter(function (c) { return !beforeIds.has(String(c.id)) })
for (const c of mine2) {
  await fetch(BASE + '/api/ai/conversations/' + c.id, { method: 'DELETE', headers: AUTH })
}
const final = (await listConv()) || []
check(final.length === before.length,
  '[ai] 收尾：本轮新建的会话已全部删除，演示账号的列表回到跑之前的长度',
  '删=' + mine2.map(function (x) { return x.id }).join(',') + ' 现在=' + final.length + '（基线 ' + before.length + '）')

const nonAbortFail = bad.reqfail.filter(function (x) { return !/\/api\/ai\/chat\/stream :: net::ERR_ABORTED/.test(x) })
check(nonAbortFail.length === bad.reqfail.length - deliberateAborts || bad.reqfail.length === 0,
  '[ai] 取不到的资源只有一种：本文件主动点「停止生成」造成的 ' + deliberateAborts + ' 次 ERR_ABORTED',
  'reqfail=' + bad.reqfail.length + ' 其中主动中止=' + deliberateAborts + ' 明细=' + JSON.stringify(bad.reqfail.slice(0, 3)))
check(bad.console.length === 0 && bad.pageerror.length === 0 && bad.http.length === 0,
  '[ai] 全程没有 console error / 未捕获异常 / 4xx·5xx',
  'console=' + bad.console.length + ' pageerror=' + bad.pageerror.length + ' http=' + bad.http.length)

await page.goto(BASE + '/ai', { waitUntil: 'load' })
await page.waitForTimeout(1500)
await page.screenshot({ path: path.join(SHOT, 'aichat-final.png'), fullPage: false })

console.log('---- 汇总：' + (nPass + nFail) + ' 项，失败 ' + nFail + ' 项')
failures.forEach(function (x) { console.log('   ✗ ' + x) })
;[...new Set(bad.console.concat(bad.pageerror))].slice(0, 6).forEach(function (x) { console.log('#  ' + x) })
;[...new Set(bad.http.concat(bad.reqfail))].slice(0, 6).forEach(function (x) { console.log('#  ' + x) })
await browser.close()
process.exit(nFail === 0 ? 0 : 1)
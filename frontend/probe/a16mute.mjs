// ==========================================================================================
// A16 补跑：证明 BR6「用户被禁言期间：可读、可点赞，不可发帖/评论/私信」这条判据。
//
// 为什么要拆成两个阶段（这是一个取证姿势的问题，不是偷懒）：
//   登录闸门 AuthService#allowCoolingOrReject 只放行 ACTIVE 与「冷静期内的 DELETED」，
//   MUTED 会被判 20003（本轮实测：登录 gate5_muted → HTTP 403 code=20003）。
//   而真实产品里「被禁言」发生在一个已经登着的人身上 —— 令牌是禁言之前发出去的。
//   所以本脚本用 phase=login 先把令牌写盘，中间由 SQL 把账号置 MUTED，
//   phase=send 再拿那枚旧令牌去打读接口和写接口。
//   这同时就是阶段 6 A6「禁言即时生效」要复用的那条路径的现量读数。
//
// 跑法（三步，中间那步是 SQL，见 docs/gate/阶段5/README.md §4）：
//   node probe/a16mute.mjs --phase=login --acct=gate5_mute2
//   …把该账号置成 MUTED…
//   node probe/a16mute.mjs --phase=send  --acct=gate5_mute2 --peer=5
// 退出码：任何一条 FAIL 即 1。
// ==========================================================================================
import fs from 'node:fs'
import path from 'node:path'

const APIBASE = process.env.APIBASE || 'http://127.0.0.1:8080'
const PWD = 'Test1234'
const CAPTCHA_ID = '00000000000000000000000000000000'
const TOK_DIR = 'E:/codex workspace/_cache/mindisle-dbtmp'
const argv = process.argv.slice(2)
const opt = (k, d) => {
  const a = argv.find((x) => x.startsWith('--' + k + '='))
  return a ? a.split('=').slice(1).join('=') : d
}
const phase = opt('phase', 'login')
const acct = opt('acct', 'gate5_mute2')
const peer = Number(opt('peer', 5))
const file = path.join(TOK_DIR, 'a16-token-' + acct + '.json')

let nPass = 0
let nFail = 0
function say (s) { console.log(s) }
function check (ok, name, read) {
  if (ok) { nPass++; say('PASS ' + name + (read ? '   <- ' + read : '')) }
  else { nFail++; say('FAIL ' + name + '   <- ' + (read || '')) }
  return ok
}

async function call (method, p, token, body) {
  const headers = {}
  // 🔴 必须显式带 Content-Type：Spring 的 @RequestBody 在没有这个头时不会把裸 JSON 反序列化成对象，
  //    直接 500/90004（本轮第一次跑就栽在这里，误看了半天的「登录挂了」）。
  if (body !== undefined) headers['Content-Type'] = 'application/json;charset=utf-8'
  if (token) headers.Authorization = 'Bearer ' + token
  const r = await fetch(APIBASE + p, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body)
  })
  let j = null
  try { j = await r.json() } catch (e) { j = null }
  return { status: r.status, code: j && j.code, data: j && j.data, msg: j && j.msg }
}

async function login (name) {
  const r = await call('POST', '/api/auth/login', null, {
    username: name, password: PWD, captchaId: CAPTCHA_ID, captchaCode: 'ZZZZ'
  })
  if (!r.data || !r.data.accessToken) {
    throw new Error('登录 ' + name + ' 没拿到 token（HTTP ' + r.status + ' code=' + r.code + ' ' + (r.msg || '') + '）')
  }
  return { token: r.data.accessToken, uid: Number(r.data.user.id), nick: r.data.user.nickname || name, status: r.data.user.status }
}

if (phase === 'login') {
  const u = await login(acct)
  fs.mkdirSync(TOK_DIR, { recursive: true })
  fs.writeFileSync(file, JSON.stringify({ acct, uid: u.uid, nick: u.nick, token: u.token, at: new Date().toISOString() }, null, 2), 'utf8')
  say('A16-0 禁言之前先登录：账号 ' + acct + ' uid=' + u.nick + '/' + u.uid + ' status=' + u.status + ' 令牌已写盘 ' + file)
  check(u.status === 'ACTIVE', 'A16-0a 这个夹具账号此刻必须是 ACTIVE（否则下面「被禁言」这个动作没有前置态）', 'status=' + u.status)
  const r0 = await call('POST', '/api/pm/send', u.token, {
    toUserId: peer, msgType: 'text', content: '禁言之前发的一条：' + Date.now(), clientMsgId: 'a16-pre-' + Date.now()
  })
  say('A16-0b 置 MUTED 之前发信应当成功 code=' + r0.code + ' id=' + ((r0.data && r0.data.id) || '-'))
  check(r0.code === 0, 'A16-0c 未禁言时私信可发（这一步是下一刀「变红」的对照组）', 'code=' + r0.code + ' id=' + ((r0.data && r0.data.id) || '-'))
  say('NEXT 现在把这个账号置成 MUTED，再跑 --phase=send')
  process.exit(nFail ? 1 : 0)
}

// ---------- phase=send ----------
if (!fs.existsSync(file)) {
  say('FAIL A16 没有令牌文件：' + file + '，先跑 --phase=login')
  process.exit(1)
}
const saved = JSON.parse(fs.readFileSync(file, 'utf8'))
const tok = saved.token
say('A16-1 用禁言之前发出去的那枚令牌打接口 uid=' + saved.uid + ' 签发于 ' + saved.at)

const me = await call('GET', '/api/users/me', tok)
check(me.code === 0, 'A16-2 令牌本身还活着：/api/users/me 认它（禁言不是封号，不能把人从会话里踢出去）', 'code=' + me.code + ' status=' + ((me.data && me.data.status) || '-') + ' msg=' + (me.msg || ''))
const mutedNow = me.data && String(me.data.status) === 'MUTED'
check(mutedNow, 'A16-3 库里此刻确实是 MUTED（这一步不过就说明中间那条 SQL 没生效，后面的红全是假红）', 'status=' + ((me.data && me.data.status) || '读不到'))

const conv = await call('GET', '/api/pm/conversations', tok)
check(conv.code === 0, 'A16-4 禁言期间「看」没被夺：会话列表照常 200/code=0', 'code=' + conv.code + ' listLen=' + ((conv.data && conv.data.list && conv.data.list.length) || 0))
const unread = await call('GET', '/api/pm/unread', tok)
check(unread.code === 0, 'A16-5 未读汇总也照常能读', 'code=' + unread.code + ' total=' + ((unread.data && unread.data.total) || 0))

const send = await call('POST', '/api/pm/send', tok, {
  toUserId: peer, msgType: 'text', content: '禁言之后试发的一条：' + Date.now(), clientMsgId: 'a16-post-' + Date.now()
})
check(send.code === 10003, 'A16-6 禁言之后发私信 → 10003 FORBIDDEN（只夺「说」）', 'HTTP=' + send.status + ' code=' + send.code + ' msg=' + (send.msg || ''))
const again = await call('POST', '/api/pm/send', tok, {
  toUserId: peer, msgType: 'text', content: '再来一次：' + Date.now(), clientMsgId: 'a16-post2-' + Date.now()
})
check(again.code === 10003, 'A16-7 第二次还是 10003（不是偶发，也不是限流伪装成拒绝）', 'code=' + again.code)
const post = await call('POST', '/api/posts', tok, { title: '禁言期间发帖', content: '禁言期间发帖 ' + Date.now(), type: 'normal', visibility: 'public' })
check(post.code === 10003, 'A16-8 同一条闸门也拦住发帖（证明判据来自 PostingQuotaService 而不是私信模块自己写的私房逻辑）', 'code=' + post.code + ' msg=' + (post.msg || ''))
say('---- A16 补跑汇总：PASS ' + nPass + ' / FAIL ' + nFail + ' ----')
process.exit(nFail ? 1 : 0)
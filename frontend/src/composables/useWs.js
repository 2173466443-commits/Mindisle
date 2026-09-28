import { Client, ReconnectionTimeMode, TickerStrategy } from '@stomp/stompjs'
import SockJS from 'sockjs-client/dist/sockjs'
import { computed, readonly, ref } from 'vue'

// 私信与通知的实时通道（任务 T5.1 / T5.8 · 需求 FR6.1、FR6.3、FR9.2 · 手册 §8.1、§8.2、§8.3）。
//
// 【模块级单例，不是 useXxx() 每个组件一份】
// 这个文件的形式是「导出函数 + 模块作用域状态」，而不是在 setup 里 new Client。原因是私信有三个消费者
// （App 收 notify/alert、会话列表收 presence、详情页收 private/ack），如果每个组件各自建一条连接：
// ① 后端 PresenceRegistry 会把同一个人记成 N 个会话，② ack 只回到「发起 publish 的那条连接」，
// 详情页发出去的消息，会话列表那条连接永远收不到回执（手册 §8.1 的目的地表就是按 session 寻址的）。
// 所以全站一条连接，帧在模块里散给订阅者；组件卸载只摘自己的回调，绝不断公共的连接。
//
// 【为什么走 SockJS 而不是裸 WebSocket】
// 后端在同一个 /ws 上挂了 SockJS（另有一条 /ws-native 给探针）。浏览器主链路走 SockJS 是为了
// 「代理不支持 Upgrade 时自动降级 xhr-polling」——Gate5 要验的「关掉 WS 仍能收消息」靠的就是这一层。
// 代价是令牌必须放查询串：SockJS 的 addPath 会把 qs 原样搬到最终传输 URL 上，
// 于是 ?token= 同时出现在 /ws/info 和 /ws/…/websocket 两次请求上，后端 WsAuthHandshakeInterceptor 正是读它。
// 放在 Sec-WebSocket-Protocol 或 header 里都不行：浏览器不给裸 WebSocket 加 header，握手也拿不到自定义头。
//
// 【心跳 30s 这个数字不能自己改】
// 后端 WebSocketConfig 里 STOMP 协议心跳与 SockJS 的 setHeartbeatTime 都是 30_000，
// 手册 §8.1 明写「三个数字必须同值，否则总有一侧先判对方死了」。这里三处都引同一个常量。

/** SockJS 端点（后端 ENDPOINT_SOCKJS = "/ws"）；/ws-native 只给探针，浏览器不用。 */
export const WS_ENDPOINT = '/ws'

/** 与后端 HEARTBEAT_MILLIS 同值。 */
export const HEARTBEAT_MILLIS = 30000

/** 应用层心跳的发送间隔与「多久没回就判死」。协议层心跳只证明线路活着，证明不了 broker 还认我这个用户。 */
export const APP_PING_INTERVAL_MILLIS = 60000
export const PONG_STALE_MILLIS = 150000

/** WS 不可用时的降级轮询间隔（手册 §8.2 第 5 条）。 */
export const POLL_INTERVAL_MILLIS = 30000

/** 服务端点对点目的地（PmPushGateway 的七个常量在前端只有一个拼写来源）。 */
export const WS_DEST = {
  private: '/user/queue/private',
  ack: '/user/queue/ack',
  notify: '/user/queue/notify',
  alert: '/user/queue/alert',
  presence: '/user/queue/presence',
  pong: '/user/queue/ping',
  topicPresence: '/topic/presence'
}

/** 入站目的地（后端 @MessageMapping("/private"|"/read"|"/ping") + app 前缀 /app）。 */
export const WS_APP = {
  send: '/app/private',
  read: '/app/read',
  ping: '/app/ping'
}

/** 除了七个真实目的地，这里还有三个「本模块自己造的广播键」，让消费方不必关心连接细节。 */
const EV_RECONNECT = 'reconnect'
const EV_POLL = 'poll'
const EV_CLOSE = 'close'

/** 令牌在 localStorage 里（stores/user.js 写它）。这里直读而不引 store：引了就会形成 user↔ws 的循环依赖。 */
const TOKEN_KEY = 'mindisle_token'
const tokenNow = () => localStorage.getItem(TOKEN_KEY) || ''

/**
 * 连接态：idle（没登录/已断开）→ connecting → open → closed。
 *
 * 界面上那颗「实时通道」小灯和降级轮询都由它决定。刻意不做成一个布尔：
 * 「正在重连」和「从没连过」在用户眼里是两句话，前者要说「正在重新连接」，后者才说「未连接」。
 */
const status = ref('idle')
const lastError = ref('')
const lastPongAt = ref(0)
const openOnce = ref(false)

export const wsStatus = readonly(status)
export const wsIsOpen = computed(() => status.value === 'open')

/** dest -> Set<handler>。handler 收到的是已经 JSON.parse 过的载荷（解析失败时是原始字符串）。 */
const handlers = new Map()

/**
 * 订阅一个键（真实目的地或 EV_* 之一）。返回取消函数。
 *
 * 取消函数是必须有的：组件卸载时如果只靠「连接是全站的」这句话而不摘回调，
 * 已经销毁的组件还会被帧叫醒，然后去读它自己已经 null 掉的 ref —— 这类 bug 只在「先进详情页再退出」时出现。
 */
export function onWs(key, handler) {
  if (typeof handler !== 'function') return function () {}
  let set = handlers.get(key)
  if (!set) {
    set = new Set()
    handlers.set(key, set)
  }
  set.add(handler)
  return function () {
    const s = handlers.get(key)
    if (s) {
      s.delete(handler)
      if (!s.size) handlers.delete(key)
    }
  }
}

function emit(key, body) {
  const set = handlers.get(key)
  if (!set || !set.size) return
  // 复制一份再遍历：handler 里可能会取消自己的订阅，边遍历边删会让后面的订阅者被跳过一次。
  for (const fn of Array.from(set)) {
    try {
      fn(body)
    } catch (e) {
      console.warn('[MindIsle][ws] 订阅者抛错 key=' + key, e)
    }
  }
}

/** 帧体一律是后端 Jackson 写的 JSON；空体（心跳类）和解析失败都不该把整条链路炸掉。 */
function parseBody(frame) {
  const raw = frame && frame.body
  if (raw === undefined || raw === null || raw === '') return {}
  try {
    return JSON.parse(raw)
  } catch (e) {
    console.warn('[MindIsle][ws] 帧体不是 JSON：' + String(raw).slice(0, 120))
    return raw
  }
}

let client = null
let pollTimer = null
let pingTimer = null

function startPolling() {
  if (pollTimer) return
  // 第一次立即读，之后每 30s 一次：不立即读的话，「WS 挂了」的头 30 秒里连角标都不会动。
  emit(EV_POLL, {})
  pollTimer = setInterval(function () {
    if (!tokenNow()) {
      stopPolling()
      return
    }
    emit(EV_POLL, {})
  }, POLL_INTERVAL_MILLIS)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/**
 * [GATE5 修复] deactivate() 在 7.3.0 里仍返回 Promise，但 activate() 已经从 Promise 变成 void 一次了：
 * 同类漂移不该由界面上的一条未捕获异常来发现。这里把「返回值一定是 Promise」这个假设摘掉，
 * 有 catch 就挂上、没有就算了；异常也不许抛回调用方——调用它的是退出登录与重建连接这两条路，
 * 在那两处抛出去等于把登出与重连一起带走。
 */
function safeDeactivate(c) {
  try {
    const p = c.deactivate()
    if (p && typeof p.catch === 'function') p.catch(function () {})
  } catch (e) {
    /* 关不掉也得继续往下走 */
  }
}

function startPing() {
  stopPing()
  lastPongAt.value = Date.now()
  wsPublish(WS_APP.ping, {})
  pingTimer = setInterval(function () {
    if (!client || !client.connected) return
    if (Date.now() - lastPongAt.value > PONG_STALE_MILLIS) {
      // 协议层还在收心跳、业务层却收不到自己的 pong —— 这条连接在 broker 里已经不是「当前用户」了
      // （多标签页轮换 Principal 的现场，PmMessageController#ping 的注释点名的就是它）。
      // 与其让用户对着一个「看着在线、其实不回话」的通道等，不如主动重建一次。
      lastError.value = '实时通道无响应，正在重建'
      const dying = client
      client = null
      stopPing()
      safeDeactivate(dying)
      connectWs()
      return
    }
    wsPublish(WS_APP.ping, {})
  }, APP_PING_INTERVAL_MILLIS)
}

function stopPing() {
  if (pingTimer) {
    clearInterval(pingTimer)
    pingTimer = null
  }
}

/**
 * 建立（或复用）全站唯一的一条 STOMP 连接。多次调用是幂等的。
 *
 * beforeConnect 里重新读令牌，是为了「重连用的是当下最新的令牌」：
 * access token 只有 2 小时，而一条挂着不动的页面可能连着好几天，
 * 用建 client 那一刻抄下来的旧令牌去重连，结果就是每次重连都被握手拒回 403。
 */
export function connectWs() {
  if (!tokenNow()) return
  if (client) return
  status.value = 'connecting'
  lastError.value = ''
  const c = new Client({
    webSocketFactory: function () {
      const t = tokenNow()
      const Ctor = SockJS && SockJS.default ? SockJS.default : SockJS
      return new Ctor(WS_ENDPOINT + '?token=' + encodeURIComponent(t), null, {
        // 禁掉回环探测之外的多余传输：本地 8080 上 eventsource/xhr-streaming 会让一次断线重连
        // 打出十几条请求，排查时看不出到底是哪一层在重试。
        transports: ['websocket', 'xdr-streaming', 'xhr-polling', 'eventsource']
      })
    },
    // 退避阶梯由库自己管（1s 起、倍增、30s 封顶）。手册 §8.3 写的是「1→2→5→10→30」，
    // 落地成指数是因为 stompjs 7 的 reconnectTimeMode.EXPONENTIAL 就是这条能力，
    // 手写阶梯要自己管定时器，而「定时器忘了清」正是这一层最难查的一类 bug（记进 §8 偏离）。
    reconnectDelay: 1000,
    maxReconnectDelay: 30000,
    reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
    heartbeatIncoming: HEARTBEAT_MILLIS,
    heartbeatOutgoing: HEARTBEAT_MILLIS,
    // Interval 而不是默认的 Worker：Worker 由 Blob URL 创建，将来上 CSP（阶段 8 安全自查）
    // 会当场把心跳停掉，症状是「连接看似活着、其实再也不发心跳，于是 30s 后被服务端判死」。
    heartbeatStrategy: TickerStrategy.Interval,
    debug: function () {}
  })

  c.onConnect = function () {
    status.value = 'open'
    const reconnected = openOnce.value
    openOnce.value = true
    stopPolling()
    // 订阅必须在 onConnect 里做：重连之后旧订阅全部作废，写在 client 外面只会静默丢掉后面所有帧，
    // 症状是「刚连上还能收到，过一会儿就不动了」。
    Object.keys(WS_DEST).forEach(function (k) {
      const dest = WS_DEST[k]
      c.subscribe(dest, function (frame) {
        if (k === 'pong') lastPongAt.value = Date.now()
        emit(dest, parseBody(frame))
      })
    })
    startPing()
    if (reconnected) emit(EV_RECONNECT, {})
  }

  c.onStompError = function (frame) {
    lastError.value = (frame && frame.headers && frame.headers.message) || '实时通道错误'
    console.warn('[MindIsle][ws] STOMP ERROR', lastError.value)
  }
  c.onWebSocketClose = function () {
    stopPing()
    // client 还在 active：库会自己按退避再试，所以这里只改状态、只开轮询，不要自己再 activate 一次。
    status.value = client ? 'connecting' : 'closed'
    emit(EV_CLOSE, {})
    if (tokenNow()) startPolling()
  }
  c.onWebSocketFailure = function () {
    lastError.value = '实时通道连不上，已切到轮询'
  }
  c.onHeartbeatLost = function () {
    lastError.value = '实时通道心跳丢失'
  }

  client = c
  // [GATE5 修复] @stomp/stompjs 从 7.x 起 activate() 返回 void（esm6/client.d.ts:634 写死 activate(): void;），
  // 不再是 Promise。原来这行写成 c.activate().catch(...)，每次连实时通道都在这一行抛
  // TypeError: Cannot read properties of undefined (reading 'catch')。它是在 App.vue 的
  // onMounted 里同步抛出的，后果有两层：① 后面的 pm.bindWs() 永远执行不到，私信与通知的订阅
  // 一条都叠不上；② Vue 的 post-flush 队列被这段异常打断，全站组件的 onMounted 都不再发
  // HTTP 请求。症状就是页面渲染得出来、数据一条都不读、顶栏一直说后端未连接，
  // Gate5 C 组第一轮把整组界面判据全数拍红的就是它。
  // activate() 现在没有可等的东西，失败与降级全部交给上面三个回调
  // （onWebSocketClose / onWebSocketFailure / onHeartbeatLost），它们本来就负责切轮询。
  try {
    c.activate()
  } catch (e) {
    lastError.value = (e && e.message) || '实时通道启动失败'
    status.value = 'closed'
    if (tokenNow()) startPolling()
  }
}

/** 当前能不能走 WS 发东西。false 时调用方必须改走 REST，而不是把消息丢在原地。 */
export function wsIsUsable() {
  return !!(client && client.connected)
}

/**
 * 发一帧。返回 false 表示「根本没发出去」，调用方（stores/pm.js）据此回落 REST。
 *
 * 刻意不排队等连接起来再发：私信的 pending 气泡已经有 clientMsgId，
 * 「等连上再补发」要么丢、要么在重连之后重复发一条，两种都比重走一次 REST 难解释。
 */
export function wsPublish(dest, body) {
  if (!wsIsUsable()) return false
  try {
    client.publish({ destination: dest, body: JSON.stringify(body === undefined || body === null ? {} : body) })
    return true
  } catch (e) {
    lastError.value = (e && e.message) || '发送失败'
    return false
  }
}

/** 退出登录 / 令牌没了时调用：断连接、停轮询、停 ping，状态回 idle。 */
export function disconnectWs() {
  stopPolling()
  stopPing()
  const c = client
  client = null
  openOnce.value = false
  status.value = 'idle'
  lastError.value = ''
  if (c) safeDeactivate(c)
}

/** 只给探针和排障用：把内部计数读出来，不改任何东西。 */
export function wsDebug() {
  return {
    status: status.value,
    lastError: lastError.value,
    lastPongAt: lastPongAt.value,
    polling: !!pollTimer,
    pinging: !!pingTimer,
    subscriberKeys: Array.from(handlers.keys())
  }
}

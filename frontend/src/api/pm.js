import http from './http'

// 后端 com.mindisle.web.PmController + com.mindisle.pm.PmMessageController
// （任务 T5.2–T5.7 · 需求 FR6 · 手册 §6.1 行 5.x、§8.1 目的地表、§8.2 五条可用性判据）。
//
// 【这批函数为什么和 STOMP 并存而不是只留一条】
// /api/pm/send 与 /app/private 落到同一个 PmService#send，判据完全相同（拉黑、禁言、词库、危机分级、
// 幂等键都在 Service 里）。留着 REST 不是为了「多一个入口」，而是手册 §8.2 第 5 条要的降级可用：
// 代理吃掉 Upgrade、公司网关掐长连接、扩展插件挡 ws:// —— 这几种情况下用户仍然必须能把话发出去。
// 所以这里的每个写接口都是 useWs 那条路的**等价兜底**，不是重复实现：
// 一旦哪天有人把 WS 的判据改了而 REST 没改，降级的那一刻业务规则也跟着降级，
// 「拉黑之后还能发消息」就是这么来的。
//
// 【哪些 silent、哪些不 silent】
// 读接口（会话列表、历史、未读、在线、黑名单）全部 silent：顶栏那颗私信角标每个页面都挂着，
// 令牌刚好过期的那一刻不该被全站红条砸一次，页面自己就地画「没读到」（与 api/notify.js 同一口径）。
// 写接口（发送、已读、举报、拉黑）刻意不 silent：10001/10002 这类话只有那一次点击有权说，
// 页面再翻译一遍就会和后端文案分叉。

/** 默认页长：与后端 mindisle.pm.default-size（20）同源。 */
export const PM_PAGE_SIZE = 20

/** 单页上限：后端 mindisle.pm.max-size（50）会把更大的值夹住而不是报错，但前端不该发那种请求。 */
export const PM_SIZE_MAX = 50

/** 断线补拉的单次上限：后端 mindisle.pm.fetch-max（100）。超过一页说明账对不上，那时该整页刷新而不是继续增量。 */
export const PM_FETCH_MAX = 100

/** 一条私信的字数上限：后端 mindisle.pm.max-content-chars（1000），且按码点计（😀 算 1 个）。 */
export const PM_CONTENT_MAX = 1000

/** 幂等键长度上限：与 private_message.client_msg_id 列宽、后端 client-msg-id-max-chars 同值。 */
export const PM_CLIENT_MSG_ID_MAX = 64

/** 拉黑原因上限（mindisle.pm.block-reason-max）。超了后端回 10001，这里只做输入框的 maxlength。 */
export const PM_BLOCK_REASON_MAX = 200

/** 举报补充说明上限（mindisle.pm.report-remark-max）。 */
export const PM_REPORT_REMARK_MAX = 500

/**
 * 举报原因的六个选项：**顺序与取值逐字抄自后端 PmService.REPORT_LABELS**。
 *
 * 那份 Map 用 LinkedHashMap 而不是 Map.copyOf，注释写明「这个顺序就是前端下拉的顺序」，
 * 所以这里不能按字母重排、也不能自己发明第七项。后端加一类时先改那边再同步这里。
 */
export const PM_REPORT_REASONS = [
  { value: 'spam', label: '广告骚扰' },
  { value: 'abuse', label: '攻击辱骂' },
  { value: 'sexual', label: '色情低俗' },
  { value: 'privacy', label: '泄露隐私' },
  { value: 'self-harm', label: '自伤风险' },
  { value: 'other', label: '其他' }
]

/** 消息类型白名单（后端 PrivateMessage.TYPE_*）；system 是服务端自己写的，前端不发。 */
export const PM_MSG_TYPES = { text: 'text', image: 'image' }

/**
 * 生成一条幂等键。
 *
 * 后端对空值的处置是「补一个 srv-<uuid> 再入库」（PmService#normalizeClientMsgId），
 * 那是「客户端没指望你去重」的语义，不是幂等。前端要的是**重连或双击之后不出现两条气泡**，
 * 所以每一条待发的消息都必须自带一个稳定的键，而不是把去重交给服务端。
 * crypto.randomUUID 在 http:// 非安全上下文里不存在（本项目实测 https 与 localhost 有），
 * 留一个时间戳+随机的兜底，长度仍卡在 64 以内。
 */
export function newClientMsgId() {
  let rnd = ''
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    rnd = crypto.randomUUID()
  } else {
    rnd = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12)
  }
  return ('c-' + rnd).slice(0, PM_CLIENT_MSG_ID_MAX)
}

/** 会话列表（U9）：params = { beforeId?, size? }，出参 PmConversationPage = { list, nextCursor, hasMore, unreadTotal }。 */
export const listConversations = (params) => http.get('/pm/conversations', { params, silent: true })

/**
 * 与某个人的历史（U10）：出参 PmThreadPage，list 是**时间正序**（后端游标倒序取、返回前翻正）。
 *
 * 🔴 断线补拉**不要**把本地已知最小 id 当 beforeId 传上来。这一句在本文件里写过，
 * 那是错的：后端的游标只有「取比 beforeId 更旧的」这一个方向（PmService#listThread），
 * 拿 minId 问它要更新的，它只会照字面返回一片更旧的空 —— 补不到新消息，而且不报错。
 * 补拉的正确做法是刻意不带 beforeId 取最新的一页（stores/pm.js#refillThread），
 * 合并靠 id 去重，所以「多取一点」永远比「取错方向」便宜。
 */
export const listThread = (peerId, params) => http.get('/pm/thread/' + peerId, { params, silent: true })

/** 发一条私信（降级入口）：payload = { toUserId, content, clientMsgId, msgType }，出参 PmMessageView。 */
export const sendPrivateMessage = (payload) => http.post('/pm/send', payload)

/** 已读上报：payload = { peerId, upToId? }，出参一条 kind='read' 的 PmAckView（含剩余未读）。 */
export const reportRead = (payload) => http.post('/pm/read', payload)

/** 未读读数（FR9.1 角标 + WS 不可用时的 30s 轮询目标）。出参 PmUnreadView = { total, byPeer, peers }。 */
export const pmUnread = () => http.get('/pm/unread', { silent: true })

/**
 * 在线状态（FR6.5）。
 *
 * 🔴 参数形状必须现查不能想当然：后端签名是 {@code @RequestParam List<Long> peerIds}，
 * 而 axios 默认把数组序列化成 {@code peerIds[]=1&peerIds[]=2}（带方括号），
 * Spring 那边根本没有叫 {@code peerIds[]} 的参数 ⇒ 结果是「所有人都不在线」，
 * 而且一句错误都没有。paramsSerializer.indexes = null 把它改成重复参数 {@code peerIds=1&peerIds=2}，
 * 与后端注释里写明的口径一致（那边刻意不用逗号串，因为拆法不一致会静默漏人）。
 */
export const pmOnline = (peerIds) => http.get('/pm/online', {
  params: { peerIds: peerIds && peerIds.length ? peerIds : undefined },
  paramsSerializer: { indexes: null },
  silent: true
})

/** 举报一条私信（FR6.8）：payload = { messageId, reason, description? }，出参 { taskId, status, tip }。 */
export const reportMessage = (payload) => http.post('/pm/report', payload)

/** 拉黑（FR6.7）：reason 走 query 参数（后端是 @RequestParam），body 传 null；重复拉黑返回 changed=false 而不是 409。 */
export const blockPeer = (userId, reason) => http.post('/pm/block/' + userId, null, {
  params: reason ? { reason } : {}
})

/** 解除拉黑：物理删除 user_block 一行，changed=false 表示本来就没拉黑。 */
export const unblockPeer = (userId) => http.delete('/pm/block/' + userId)

/** 我的黑名单（U9 设置项）。出参 List<PmBlockItem>。 */
export const listBlocks = () => http.get('/pm/blocks', { silent: true })

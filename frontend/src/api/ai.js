import http from './http'

// 后端 com.mindisle.web.AiController（阶段 4 · 任务 T4.2 / T4.5 / T4.19）。
//
// 口径来自需求 §9.1 的 AI 域五行，加上一条 §9.1 概览里省略、但 U7「左会话列表 + 右聊天区」
// 必须要有的读接口 GET /api/ai/conversations/{id}/messages —— 点开旧会话却拉不到消息，
// 侧栏就是一个只能点的装饰。这条偏离已写进 docs/dev-log.md 的契约漂移清单。
//
// 为什么流式那条不在这个文件里：POST /api/ai/chat/stream 返回的是 text/event-stream，
// 要走 fetch + ReadableStream 逐帧解析，而 http.js 是 axios（它拿完整响应才 resolve，
// 等于把流式降级成整段）。手册 §5.8 第 4 条同时禁用 EventSource，因为它带不了 JWT 头。
// 所以那条请求留在 ChatView.vue 里自己发，认证头也在那里自己拼。

/** 侧栏一次取的会话数上限：与后端 ConversationService.SIDEBAR_MAX 同源（超了会被夹住而不是报错）。 */
export const CONVERSATION_MAX = 50

/** 点开一条旧会话时一次取回的消息数上限：与后端 MESSAGE_PAGE_MAX 同源。 */
export const MESSAGE_PAGE_MAX = 200

/** 会话标题的输入上限：后端按 TITLE_MAX=30 截断，这里先夹一次是为了让「还能打几个字」这件事可见。 */
export const TITLE_MAX = 30

/** 列表与回看都是 silent：侧栏拉不到就该在原地写一句「暂时读不到」，不该用全局红条把对话打死。 */
export const listConversations = (params) => http.get('/ai/conversations', { params, silent: true })
export const listMessages = (id, params) => http.get('/ai/conversations/' + id + '/messages', { params, silent: true })

/** 新建 / 删除 / 赞踩是写接口，刻意不 silent：失败的唯一原因是没登录或参数不对，那两句话该由这次点击来说。 */
export const createConversation = (payload) => http.post('/ai/conversations', payload || {})
export const deleteConversation = (id) => http.delete('/ai/conversations/' + id)

/** 重命名（FR2.1，v1.2.6 补）。后端只认 title 一个字段；空标题 10001、不存在或不是你的 90006，都由这次点击自己把话说明白，所以不 silent。 */
export const renameConversation = (id, title) => http.patch('/ai/conversations/' + id, { title })

/** feedback 只接受 UP / DOWN / NONE 三个值，且只能打在屿屿的回复上（打在用户自己那句会被后端 10001 拒掉）。 */
export const sendFeedback = (messageId, feedback) => http.post('/ai/messages/' + messageId + '/feedback', { feedback })

/**
 * 后端 conversation.style 的三档枚举（阶段 2 建表时定下的 warm / rational / humorous）。
 *
 * <p>用户设置里存的是另一套四档（gentle / direct / humor / listener），由后端
 * ChatService.mapStyle 按「语气软硬」映射过来，所以这里只描述落库值 —— 它是回看旧会话时
 * 侧栏那一行的小字说明，不是第二个可改的开关：改语气仍然只走个人设置里的 ai_style。</p>
 */
export const STYLE_TEXT = {
  warm: '温暖陪伴',
  rational: '理性梳理',
  humorous: '轻松幽默'
}

/** 风险等级的中文标签（meta.riskLevel 是 0-3 的数字，不是 "L2" 这样的字符串）。 */
export const RISK_TEXT = ['L0 日常陪伴', 'L1 低落·可倾诉', 'L2 需要专业支持', 'L3 建议立即求助']

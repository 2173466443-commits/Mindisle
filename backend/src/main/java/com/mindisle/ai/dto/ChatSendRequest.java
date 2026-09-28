package com.mindisle.ai.dto;

/**
 * {@code POST /api/ai/chat/stream} 的请求体（需求 §9.1 AI 对话行 + §9.2 SSE 事件表 · 任务 T4.17）。
 *
 * <p><b>为什么 {@code conversationId} 是 String 而不是 Long</b>：前端 ChatView.vue 第 330 行发的是
 * {@code conversationId.value || localSession.value}，而后两者在浏览器里都是字符串
 * ——{@code localSession} 是阶段 2 留下的本机会话号（形如 {@code local-xxxx}）。
 * 声明成 Long 会让 Jackson 在反序列化这一步就抛 400，用户看到的是「发送失败」，
 * 而真正的原因（他有一条还没上云的本机历史）根本没机会被说出来。
 * 所以类型按前端实际发的东西定，解析规则放在 ChatService 里：非纯数字 = 新会话。</p>
 *
 * <p><b>不加 Bean Validation 注解</b>：与 {@code TopicCreateRequest} 同一口径 ——
 * 长度上限走配置（NFR10 禁止在业务代码里写死，而注解值必须是编译期常量），
 * 且「这句话太长了」只由服务层说一次，说成一句能直接念给用户的话，
 * 而不是让 GlobalExceptionHandler 把注解文案拼成 10001。</p>
 *
 * @param conversationId 会话标识；null/空/非数字都按「新开会话」处理
 * @param message        用户这一轮的原话，清洗前的形态（清洗由 SafetyGuard 负责）
 * @param style          对话人格，前端有自己的一套档位词表，见 {@code ChatService#mapStyle}
 */
public record ChatSendRequest(String conversationId, String message, String style) {
}

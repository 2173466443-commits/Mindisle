package com.mindisle.ai.dto;

/**
 * 会话内一条消息（{@code GET /api/ai/conversations/{id}/messages} 与 SSE done 之后的回看）。
 *
 * <p>情绪与风险字段<b>原样透出</b>：需求 §10.1 U7 的侧栏要让用户看到「这条被标了 sadness」，
 * 论文做人工校正标注（{@code emotion_channel=manual}）也要能读出当前值。</p>
 *
 * <p>不透出「本轮有没有被输出侧闸改写过」：{@code chat_message} 里没有这一列，
 * 它只在 SSE 的 done 事件里作为一次性提示存在（前端拿它整段替换气泡）。
 * 事后从数据库是推不出来的 —— 存的就是改写后的文本。这是有意的取舍：
 * 改写记录进日志（{@code SafetyGuard} 的 warn 行）而不是进业务表，
 * 免得管理端把「AI 说过什么」和「AI 本来想说什么」混成一张表。</p>
 *
 * <p>也不透出 tokens/latency：那些是计费与性能字段，管理端用量页有。</p>
 *
 * @param id               消息 id，反馈接口要用
 * @param role             user / assistant / system
 * @param content          正文（assistant 侧存的是<b>过闸之后</b>的文本）
 * @param emotionLabel     用户消息的 7 类情绪标签；助手消息为 null
 * @param emotionChannel   dict / llm / manual；null = 这一行没被识别过（助手行就是 null）
 * @param riskLevel        本轮等级 L0-L3
 * @param feedback         NONE / UP / DOWN（FR2.6 的赞踩）
 * @param degraded         true = 这条回复来自离线话术库
 * @param interrupted      true = 生成被中断，正文只存了已生成的部分（任务 T4.6）
 * @param model            实际模型名，降级时是 {@code offline-empathy-bank}
 * @param promptVersion    提示词版本，A/B 与回归定位用
 * @param createdAt        创建时间（格式在服务层固定，不让 Jackson 配置决定前端看到什么）
 */
public record MessageView(Long id, String role, String content, String emotionLabel,
                          String emotionChannel, String riskLevel, String feedback,
                          boolean degraded, boolean interrupted, String model,
                          String promptVersion, String createdAt) {
}

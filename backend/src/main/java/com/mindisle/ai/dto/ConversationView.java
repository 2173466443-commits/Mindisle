package com.mindisle.ai.dto;

/**
 * 会话列表项（{@code GET /api/ai/conversations} · 需求 §9.1 · 任务 T4.2 的读侧）。
 *
 * <p>刻意不含 {@code userId}：这张表按当前登录用户过滤，回一个前端本来就知道的归属字段
 * 只会让它被误用成「按 userId 查别人的会话」的入口（NFR8 数据最小化）。</p>
 *
 * <p>也刻意不含 {@code messageCount}：一列会话一个 COUNT 会让首页侧栏打出 N 条 SQL，
 * 而前端只需要「点进去再拉消息」。真要做未读/条数展示时，用 conversation.last_msg_at 与
 * 一次批量 GROUP BY，而不是逐条 count。</p>
 *
 * @param id        会话 id
 * @param title     标题（首条消息截断，上限 {@code mindisle.llm.title-max-chars}）
 * @param style     本会话人格：warm / rational / humorous
 * @param summary   长对话滚动摘要，未压缩时为 null
 * @param lastMsgAt 最后一条消息时间，字符串（格式在服务层固定，不让 Jackson 配置决定前端看到什么）
 * @param createdAt 创建时间
 */
public record ConversationView(Long id, String title, String style, String summary,
                               String lastMsgAt, String createdAt) {
}

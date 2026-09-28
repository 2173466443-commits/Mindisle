package com.mindisle.ai.dto;

/**
 * {@code POST /api/ai/messages/{id}/feedback} 的请求体（需求 §9.1 · FR2.6「用户对回答的评价」）。
 *
 * <p>取值域就是 {@code chat_message.feedback} 的 ENUM（NONE/UP/DOWN）。服务层逐字比对而不是
 * 交给注解：判错了要回一句「评价只能是赞同、反对或撤销」这种人话，
 * 且这条判据要和 {@code MessageView.feedback} 的读侧放在一起看，拆到注解里就没人对着看了。</p>
 *
 * @param feedback UP / DOWN / NONE（NONE = 撤销刚才的评价）
 */
public record FeedbackRequest(String feedback) {
}

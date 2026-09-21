package com.mindisle.post.dto;

/**
 * 帖子互动入参（任务 3.6 · 需求 FR4.4、接口清单见需求 §9.1「{@code POST /posts/{id}/actions}」）。
 *
 * <p>需求把点赞、收藏、举报合并成一个 actions 端点，本类就是它的 body。
 * 取值白名单与报错文案由 {@code PostInteractionService.normalizeAction} 唯一决定，
 * 这里不加 {@code @NotBlank}：校验注解的报错要走 GlobalExceptionHandler 的字段错误分支，
 * 同一个「参数不对」就会出现两种 msg 格式，而前端只想要一句能直接念给用户的话。</p>
 *
 * @param action like / unlike / collect / uncollect
 */
public record PostActionRequest(String action) {
}

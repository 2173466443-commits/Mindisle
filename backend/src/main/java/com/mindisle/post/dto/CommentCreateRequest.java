package com.mindisle.post.dto;

/**
 * 发表评论/回复入参（任务 3.7 · 需求 FR4.4 · 接口清单见需求 §9.1「{@code POST /comments}」）。
 *
 * <p>不加 {@code @NotBlank/@Size}：字数上限走配置（NFR10）且报错要成一句能直接念给用户的话，
 * 校验注解的字段错误会走 GlobalExceptionHandler 的另一条分支，同一个「参数不对」出现两种 msg 格式。
 * 与 {@link CreatePostRequest} 同一口径，全部判据收在 {@code CommentService}。</p>
 *
 * @param content   评论内容，≤1000 字（DDL VARCHAR(1000)、FR4.4）
 * @param parentId  被回复的评论 id；为 null 表示发一级评论
 * @param anonymous 是否匿名发表。不跟随帖子类型自动置真：树洞帖下面实名说一句「抱抱你」
 *                  正是本产品的温度闭环，替用户决定匿名反而会劝退最想回应别人的人
 */
public record CommentCreateRequest(String content, Long parentId, Boolean anonymous) {
}

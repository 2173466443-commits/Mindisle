package com.mindisle.user.dto;

/**
 * 关注/取关入参（任务 3.6 · 需求 FR4.6 · 接口形态见需求 §9.1 的 unified actions 口径）。
 *
 * <p>与 {@code PostActionRequest} 同形：一个动作字符串，正向与负向挤在同一条 POST 路径上，
 * 而不是「POST 关注 + DELETE 取关」。理由不是省事，而是<b>取关在语义上不等于删除某条记录</b>——
 * 它是「把关系切到另一个状态」，幂等、可重放、不依赖调用者知道那行的 id。
 * 两条路径各写一套参数校验，就会各写一套错误文案（手册 §5.5 禁止的第二份真相）。</p>
 *
 * <p>刻意不加 {@code @NotBlank}：与 {@code PostActionRequest} 同一取舍，
 * 「参数不对」这句话只由服务层的白名单校验说一次。</p>
 *
 * @param action follow / unfollow
 */
public record FollowRequest(String action) {
}

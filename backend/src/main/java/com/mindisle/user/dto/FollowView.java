package com.mindisle.user.dto;

/**
 * 一次关注操作之后的关系状态（任务 3.6 · 需求 FR4.6）。
 *
 * <p>关注按钮同样是即时反馈控件，所以要一次回全：「现在到底关注上没有、对方还剩多少粉丝、
 * 我自己关注了几个人」，前端不用本地猜。{@code following} 以库里的关系行为准，
 * 不是「本次动作是不是 follow」——并发取关/重复点都会让它落到真实值上。</p>
 *
 * <p>{@code followerCnt} / {@code myFollowingCnt} 直接 COUNT 真表（user_follow），
 * 不读 user_profile 上那两列冗余值：冗余列是刷给管理端与数据大屏的（阶段 6 A2），
 * 读侧再拿它回给用户就会多出一条「冗余列落后」的可见故障面。</p>
 *
 * @param targetUserId   被关注者
 * @param action         本次被受理的动作（归一化后的小写值）
 * @param changed        这次请求有没有真的改变关系；幂等重复为 false
 * @param following      当前用户此刻是否关注着对方
 * @param followerCnt    对方的粉丝数（真相）
 * @param myFollowingCnt 当前用户关注的人数（真相）
 */
public record FollowView(
        Long targetUserId,
        String action,
        boolean changed,
        boolean following,
        long followerCnt,
        long myFollowingCnt) {
}

package com.mindisle.topic.dto;

/**
 * 一次「关注 / 取关话题」之后的关系状态（任务 3.8 · 手册 §6.1 行 3.8）。
 *
 * <p>形状与 {@code user.dto.FollowView} 逐字同构（动作、是否真变了、现在的状态、计数），
 * 差别只在少了「我关注了几个」那一维：话题页上那个数字对读者没有意义，
 * 而多一次 COUNT(*) 扫描就要在写路径上多付一次。同构的收益是前端的关注按钮
 * 可以一份乐观更新逻辑服务两种目标（先改界面 → 以回执覆盖 → 失败回滚），
 * 两套按钮各写各的回滚，是「按钮点第二下才生效」这类投诉的标准来源。</p>
 *
 * <p>{@code followCnt} 读的是重算之后的 {@code topic.follow_cnt}：这一列与
 * {@code topic_follow} 恒等（{@code TopicFollowMapper#refreshFollowCnt}），
 * 所以不需要再 COUNT 一次真相表 —— 与 {@code FollowView} 那边「读侧不信冗余列」的口径
 * 相反，理由是这边的冗余列本身就是<b>同步写在同一条链路里</b>刷的，读它不会读到落后值。</p>
 *
 * @param topicId   话题 id
 * @param action    本次被受理的动作（归一化后的小写值）
 * @param changed   这次请求有没有真的改变关系；幂等重复为 false
 * @param following 当前用户此刻是否关注着这个话题
 * @param followCnt 关注数
 */
public record TopicFollowView(
        Long topicId, String action, boolean changed, boolean following, Integer followCnt) {
}

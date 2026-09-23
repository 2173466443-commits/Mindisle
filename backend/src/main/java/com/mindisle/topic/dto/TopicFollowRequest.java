package com.mindisle.topic.dto;

/**
 * 关注/取关话题入参（任务 3.8 · 与 {@code user.dto.FollowRequest} 同形）。
 *
 * <p>同样是「一条 POST + body 里一个 action」，而不是 POST 关注 + DELETE 取关：
 * 取关在语义上是「把关系切到另一个状态」，不是「删除某条记录」——它必须幂等、可重放、
 * 且不要求调用方知道那行的 id。理由与 T3.6 逐字相同，两处不再各写一遍。</p>
 *
 * @param action follow / unfollow
 */
public record TopicFollowRequest(String action) {
}

package com.mindisle.pm.dto;

import java.time.LocalDateTime;

/**
 * 回执（任务 T5.2 的 {@code /user/queue/ack}）。
 *
 * <p>三种回执共用一个形状，靠 {@code kind} 区分：<b>sent</b>（服务端已落库，发送方的气泡
 * 从「发送中」变「已发送」）、<b>delivered</b>（已写进对方一个活跃会话）、
 * <b>read</b>（对方读到了）。三个词描述的是三个不同的事实，把它们混成一个「成功」
 * 就是 FR6.4「消息状态可见」没做——用户看不出「他收到了」和「他看了」的差别。</p>
 *
 * @param kind        sent / delivered / read
 * @param clientMsgId 对应发送时那个幂等 ID（发送方靠它把回执贴回正确的气泡上）
 * @param id          消息 id；落库失败时为 null
 * @param peerId      会话另一端
 * @param count       read 类回执：本次真的从非 read 变成 read 的行数；其余为 null
 * @param unread      接收方此刻还剩几条未读（给发送方看「对方还没读」时不展示，见字段说明）；
 *                    随回执一起给，省一次 unread 查询
 * @param at          服务端判定这个事实成立的时间
 * @param code        仅 error 类回执：服务端那套业务码（10001/30005/50001…），前端据此决定要不要再弹一次；其余为 null
 * @param tip         给用户看的一句话（错误回执的文案、发送被拒的原因）；与 {@code Result.msg} 同一口径，不拼技术术语
 */
public record PmAckView(String kind, String clientMsgId, Long id, Long peerId, Integer count,
                        Integer unread, LocalDateTime at, Integer code, String tip) {
}

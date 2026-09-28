package com.mindisle.pm;

import java.time.LocalDateTime;

import com.mindisle.entity.PrivateMessage;

/**
 * 一条私信<b>已经提交</b>这件事（任务 T5.2/T5.3 · 手册 §8.1「事务提交后再推」）。
 *
 * <p>为什么用事件而不是在 {@code PmService} 里直接调推送：那条顺序必须靠事务保证。
 * 若在 {@code @Transactional} 方法内部就 {@code convertAndSendToUser}，接收方会在
 * 事务提交前收到消息并立刻回一条已读上报，那条 UPDATE 撞上一个还没提交的行——
 * 轻则回执 0 行（前端气泡停在「送达中」），重则两个事务互相等待。
 * Spring 的 {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 是唯一不用引 MQ
 * 就能把顺序钉死的写法，也是手册 §8.1 那句「别引 MQ」的直接落点。</p>
 *
 * <p>{@code fallbackExecution} 在无事务上下文时也会触发（见监听器），因此<b>单测直接 new 事件
 * 发布</b>就能验证监听器，不必起一个事务。</p>
 *
 * @param senderId     发送方（= 消息的 from，冗余在这里是为了让监听器不再查一次库）
 * @param recipientId  接收方
 * @param row          已落库的那一行（含 id，回执要用）
 * @param senderName   发送方展示名，通知文案用；<b>在 Service 里算好带过来</b>，
 *                     这样监听器不需要第二次 findUser
 * @param crisisLevel  命中危机时的等级（L2/L3），未命中为 null
 * @param crisisExcerpt 危机工单里的证据摘录（给管理员看的那一条），未命中为 null
 * @param ticketId     危机工单 id，未建单为 null
 * @param at           服务端判定这条消息成立的时间（回执时间戳）
 */
public record PmSendEvent(long senderId, long recipientId, PrivateMessage row, String senderName,
                          String crisisLevel, String crisisExcerpt, Long ticketId,
                          LocalDateTime at) {
}

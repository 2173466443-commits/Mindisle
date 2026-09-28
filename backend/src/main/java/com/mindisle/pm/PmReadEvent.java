package com.mindisle.pm;

/**
 * 「某人把某个会话读到了某条」（任务 T5.2 的 {@code /app/read} → 对端回执）。
 *
 * <p>读事件与发送事件分开、而不是把已读也塞进 {@code PmSendEvent} 的一个 kind：
 * 两者的接收方相反——发送事件推给<b>收件人</b>，已读事件推给<b>发件人</b>。
 * 合成一个事件就要在监听器里再判一次「这次该发给谁」，而那一次判断出错的症状
 * 是「对方读了我的消息，我自己的屏幕上弹出已读回执」，属于最难自证的那种 bug。</p>
 *
 * @param readerId 读的人（当前登录者）
 * @param peerId   会话另一端，也就是<b>回执的接收人</b>
 * @param count    本次真的从非已读变成已读的行数；0 表示一条都没变（不该推回执）
 * @param upToId   读到的消息 id
 */
public record PmReadEvent(long readerId, long peerId, int count, long upToId) {
}

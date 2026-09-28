package com.mindisle.pm.dto;

import java.time.LocalDateTime;

/**
 * 一条私信的对外形状（任务 T5.6 的 U10 气泡与 U9 列表摘要共用）。
 *
 * <p>{@code mine} 是服务端算出来的布尔（{@code from_user_id == 我}），<b>不是</b>让前端比字符串：
 * 前端要自己判就得持有两个用户 id 才知道方向，而「对方 id」在列表页恰好是唯一没有的那一个。
 * 更实际的一条：气泡方向判错的直接后果是把自己的话显示成对方说的。</p>
 *
 * <p>{@code status} 原样透出（sent/delivered/read/failed），因为发送三态是它的唯一消费者；
 * 但 {@code readAt} 只在<b>对方读过我发的话</b>时给——我发出去的消息带自己的已读时间没有意义，
 * 而我收到的消息把「我什么时候读的」再回给我自己，是纯粹的载荷浪费。</p>
 *
 * <p>{@code riskLevel} 只透出 L0/L2/L3 的字面值，前端据此决定是否在气泡下方挂求助卡片
 * （FR6.6）。它<b>不</b>触发任何管理端可见性：工单在服务端已另落 alert_ticket，
 * 私信内容本身除了被举报那条之外不进审核台。</p>
 *
 * @param id        消息 id，同时是会话游标
 * @param fromUserId 发送方
 * @param toUserId  接收方
 * @param mine      这条是不是我发的
 * @param msgType   text / image / system
 * @param content   文本或图片地址
 * @param riskLevel L0/L2/L3（L1 只可能来自模型通道，私信默认不开，见 MindisleProperties.Pm）
 * @param status    sent / delivered / read / failed
 * @param readAt    对方首次读到它的时刻（仅 mine=true 时有值）
 * @param createdAt 发送时刻
 * @param alert     命中危机词时给接收方的求助提示（热线号），非危机为 null
 */
public record PmMessageView(Long id, Long fromUserId, Long toUserId, boolean mine, String msgType,
                            String content, String riskLevel, String status, LocalDateTime readAt,
                            LocalDateTime createdAt, String alert) {
}

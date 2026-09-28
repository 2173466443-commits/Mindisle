package com.mindisle.pm.dto;

import java.time.LocalDateTime;

/**
 * 我的黑名单里的一行（任务 T5.6 · 需求 FR6.7）。
 *
 * <p>只有<b>发起方</b>能读到这个列表（被拉黑的人永远看不到这条关系的存在，
 * 见 {@code ErrorCode#PM_BLOCKED} 的文案判据），所以这里带展示名：
 * 拉黑的人需要认出「我把谁关在外面了」，否则解除操作就变成了逐行盲删。</p>
 *
 * <p>{@code reason} 是选填的内部备注。它出现在这里但<b>不</b>出现在任何发给对方的响应里
 * ——{@code reason} 列的 DDL 注释与 {@code UserBlock} 类注释都写明它只给本人和管理端看。</p>
 *
 * @param peerId     被我拉黑的人
 * @param peerName   对方展示名（昵称空时兜底成「用户+id」，口径同 {@code PostService#displayNameOf}）
 * @param peerAvatar 对方头像，可为 null
 * @param reason     我当时填的理由，可为 null
 * @param blockedAt  拉黑时间
 */
public record PmBlockItem(Long peerId, String peerName, String peerAvatar, String reason,
                          LocalDateTime blockedAt) {
}

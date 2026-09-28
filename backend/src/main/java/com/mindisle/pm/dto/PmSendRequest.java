package com.mindisle.pm.dto;

/**
 * 发送私信的入参（任务 T5.2：目的地 {@code /app/private} 与 HTTP 兜底 {@code POST /api/pm/send}
 * 共用同一个形状，两条通道只差传输协议，不差字段）。
 *
 * <p><b>这里没有 fromUserId</b>：发送方只来自 JWT／STOMP Principal。
 * 一个「客户端说自己是谁」的字段在需求 BR4/A9 面前是不成立的——任何人都能替别人发私信，
 * 而私信带着「对方可能正处于危机中」的语义，冒充一次就足以造成真实的伤害。
 * 这条判据与点赞、评论、关注、举报四处完全一致。</p>
 *
 * <p>命名走全站默认的 camelCase（与 {@link com.mindisle.notify.dto.MarkReadRequest} 同一口径，
 * 不引入 {@code @JsonNaming}：多一套蛇形约定就等于前后端两套字段名要对账）。</p>
 *
 * @param toUserId    接收方 user.id
 * @param content     文本内容，或 msgType=image 时的<b>本站</b>图片地址（以 /uploads/ 开头）
 * @param clientMsgId 客户端幂等 ID（UUID，≤64）；缺失即 400，服务端不自造（自造等于关掉幂等）
 * @param msgType     text / image，缺省按 text
 */
public record PmSendRequest(Long toUserId, String content, String clientMsgId, String msgType) {
}

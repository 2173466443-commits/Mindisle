package com.mindisle.pm.dto;

import java.time.LocalDateTime;

/**
 * 会话列表里的一行（任务 T5.6 的 U9 · 需求 FR6.2）。
 *
 * @param peerId      对方 user.id
 * @param peerName    对方展示名
 * @param peerAvatar  对方头像，可为 null
 * @param lastMsgId   最后一条消息 id（也是进入详情页时「读到哪儿」的初值）
 * @param lastContent 最后一条的内容摘要；图片消息在这里显示占位文案而不是 URL
 * @param lastMsgType text / image / system
 * @param lastAt      最后一条的时间
 * @param lastMine    最后一条是不是我发的（列表摘要前缀「我：」的唯一依据）
 * @param unreadCnt   对方发给我的未读条数，无未读时为 0（补零在服务层，不在 SQL）
 * @param online      对方此刻在不在线；{@code presence-enabled=false} 时恒为 false，
 *                    不是「查不到」而是「本功能关闭」，前端据此不显示绿点
 */
public record PmConversationItem(Long peerId, String peerName, String peerAvatar, Long lastMsgId,
                                 String lastContent, String lastMsgType, LocalDateTime lastAt,
                                 boolean lastMine, int unreadCnt, boolean online) {
}

package com.mindisle.notify.dto;

import java.time.LocalDateTime;

/**
 * 通知列表里的一条（任务 T3.11-b · 需求 FR9.1、FR9.2）。
 *
 * <p>字段与 notify_message 一一对应，<b>刻意不透 deleted、也不透「触发者 user_id」</b>：
 * 本表根本没有 actor 列（文案即身份），而将来加列时也不能顺手把它给出去——
 * 匿名评论触发的通知里如果带着真实 user_id，就等于给对方一条反查通道（FR1.4）。</p>
 *
 * @param id        通知主键，同时是游标分页的 nextCursor 取值
 * @param type      like / comment / follow / pm / system / audit / crisis / report
 * @param typeLabel 中文标签（「赞」「评论」「关注」…），前端列表分组用，不在前端再维护一份映射
 * @param title     主文案，「谁 + 做了什么」
 * @param content   辅助行（帖子标题或评论摘录），可为 null
 * @param refType   跳转对象类型：post / comment / user / report / conversation，可为 null
 * @param refId     跳转对象主键，可为 null
 * @param read      true 表示已读（DDL 的 is_read=1）
 * @param createdAt 服务端写入时间
 */
public record NotifyItem(Long id, String type, String typeLabel, String title, String content,
        String refType, Long refId, boolean read, LocalDateTime createdAt) {
}

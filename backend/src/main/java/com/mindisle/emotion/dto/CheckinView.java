package com.mindisle.emotion.dto;

/**
 * 一次打卡的结果（写入响应与「近 N 天打卡列表」共用一个形状）。
 *
 * @param id         记录 id
 * @param recordDate 归属日期（{@code yyyy-MM-dd}）
 * @param emotion    标签
 * @param emotionZh  中文名，直接来自 {@code EmotionPrior}，前端不再维护第二份映射表
 * @param intensity  强度 1-5
 * @param valence    效价 -1/0/1，由标签推得（用户只选标签，效价不是第二个自由度）
 * @param sleepBucket睡眠档位，可空
 * @param note       用户那一句话（已清洗截断）
 * @param source     checkin / chat / post——列表里能分开「问出来的」和「看出来的」（FR3.1/FR3.2）
 * @param channel    manual / dict / llm
 * @param appended   true = 这一天已有打卡，本次是追加的新版本（FR3.1「不可覆盖历史」）
 * @param createdAt  写入时间
 */
public record CheckinView(Long id, String recordDate, String emotion, String emotionZh,
                          int intensity, int valence, Integer sleepBucket, String note,
                          String source, String channel, boolean appended, String createdAt) {
}

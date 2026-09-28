package com.mindisle.emotion.dto;

/**
 * 情绪打卡入参（任务 T4.9 · 需求 FR3.1 · 界面 U3）。
 *
 * <p><b>为什么日期是字符串而不是 {@code LocalDate}</b>：前端传的是 {@code yyyy-MM-dd}，
 * 而 Jackson 对 {@code LocalDate} 的解析依赖全局格式配置。本项目
 * {@code Jackson2Config} 没给日期类型定死格式，一旦哪天动了 mapper 配置，
 * 「补卡」会静默变成「记到今天」——这是那种编译期与运行期都不报错、
 * 只在周报里露馅的错法。收字符串 + 服务层自己 parse，失败就明确回 10001。</p>
 *
 * <p>字段全部可空由服务层判：{@code emotion} 与 {@code intensity} 缺失是参数错误，
 * 而 {@code note} 与 {@code sleepBucket} 缺失是「用户没写」，语义不同，不能混成一个默认值。</p>
 *
 * @param emotion     7 类标签之一：joy / trust / anger / sadness / fear / disgust / neutral。
 *                    取值域以 {@code EmotionPrior.LABELS} 为唯一出处（<b>没有 surprise</b>，
 *                    需求 §1.5 的七类里不含 Plutchik 的惊讶），前端也不再自己维护第二份映射。
 * @param intensity   强度 1-5，用户自选
 * @param sleepBucket 睡眠档位 0 不足4h / 1 4-6h / 2 6-8h / 3 8h 以上；可空
 * @param note        一句话，可空；落库前清洗零宽字符并截断到 200
 * @param recordDate  补卡日期 {@code yyyy-MM-dd}；空 = 今天。只允许今天与过去 30 天内
 */
public record CheckinRequest(String emotion, Integer intensity, Integer sleepBucket,
                             String note, String recordDate) {
}

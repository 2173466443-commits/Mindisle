package com.mindisle.emotion.dto;

import java.util.Map;

/**
 * 情绪周报（任务 T4.20 · 需求 FR3.5 · 界面 U8 的「我的情绪周报」卡片）。
 *
 * <p>{@code generator} 原样透出 {@code llm} / {@code template}：模型不可用、超时、
 * 解析失败、预算熔断四种情况都会走模板，但周报仍然要能出——FR3.5 要的是「稳定产出」，
 * 不是「必须有 AI 文案」。把生成方式显示出来，是为了不让模板冒充模型结论。</p>
 *
 * <p>{@code accumulating} 与 BR12 同源：数据点不足 3 天时 {@code summaryText} 只说
 * 「数据还在积累」，不做趋势判断（这条同时写进了 {@code prompts/weekly_report_v1.txt}
 * 的第 4 条硬要求，模型与模板两侧口径一致）。</p>
 *
 * @param id            周报行 id，新建时为 null 的情况不存在（一律 upsert 后回读）
 * @param weekStart     本周一（ISO 周口径）
 * @param weekEnd       本周日
 * @param checkinDays   本周打卡天数
 * @param recordCount   本周情绪记录条数
 * @param dominantLabel 主导情绪标签；无记录时为 null
 * @param dominantZh    主导情绪中文名
 * @param avgIntensity  可信记录的平均强度，1.00-5.00；无可信记录为 null
 * @param positiveRatio 正向效价占比 0.000-1.000
 * @param trendDelta    平均效价与上周之差，可负；上周无数据为 null
 * @param counts        各情绪可信条数（进 insight JSON，前端柱图直接用）
 * @param summaryText   结论正文（LLM 或模板）
 * @param generator     llm / template
 * @param accumulating  本周有记录天数 &lt; 3
 * @param createdAt     本周报的生成时间
 * @param shared        这份周报是否已经被本人去标识分享过（任务 T4.20 ③）。给前端按钮的读数用：
 *                    界面上写「已分享 · 去看那条帖」，而不是让人再点一次去撞幂等
 * @param sharedPostId 已分享时那条帖子的 id；未分享为 null
 */
public record WeeklyReportView(Long id, String weekStart, String weekEnd, int checkinDays,
                               int recordCount, String dominantLabel, String dominantZh,
                               Double avgIntensity, double positiveRatio, Double trendDelta,
                               Map<String, Integer> counts, String summaryText, String generator,
                               boolean accumulating, String createdAt, boolean shared,
                               Long sharedPostId) {
}

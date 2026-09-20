package com.mindisle.audit.dto;

import java.util.List;

import com.mindisle.audit.SensitiveWordEngine.CheckResult;

/**
 * 内容预检出参。
 *
 * <p>刻意<b>不回传命中的词面</b>：把「哪几个字违规」原样列给前端，等于给绕过者一份可迭代的
 * 探测反馈（改一个字试一次就能把词库整个钓出来）。因此只给位置区间、分组与处置，
 * 界面按区间画波浪线，文案统一是「内容可能需要调整」。</p>
 *
 * @param hit         是否命中
 * @param category    主因分组（政治违法/自伤自杀/……），未命中为 null
 * @param level       black 硬拦 / grey 进人审 / risk 危机标记
 * @param action      BLOCK 拦下 / REVIEW 转人审 / TAG 放行但打标
 * @param hitCount    命中条数
 * @param positions   原文下标区间 [start,end)，与前端 textarea 的 selectionStart 同一坐标系
 * @param dictVersion 本次生效的词库版本，前端可据此判断提醒是否比发帖时更新
 * @param hotline     非空即「需要求助入口」：仅 risk 组命中时给出，界面必须把它显示成可拨打的卡片
 * @param tip         给用户看的一句话，不含词面
 */
public record PrecheckView(
        boolean hit,
        String category,
        String level,
        String action,
        int hitCount,
        List<int[]> positions,
        String dictVersion,
        String hotline,
        String tip
) {

    /**
     * 由引擎结果映射。
     *
     * @param hotline     求助热线（mindisle.crisis.hotline）
     * @param riskAsCare  命中 risk 组时是否附带求助入口；只有「用户自己写的文本」该带 true，
     *                  检测模型输出时不该因为模型复述了风险词就给用户弹求助卡
     */
    public static PrecheckView of(CheckResult result, String hotline, boolean riskAsCare) {
        boolean needCare = riskAsCare && "risk".equals(result.level());
        return new PrecheckView(result.hit(), result.category(), result.level(), result.action(),
                result.hitCount(), result.positions(), result.dictVersion(),
                needCare ? hotline : null, tipOf(result, needCare));
    }

    private static String tipOf(CheckResult result, boolean needCare) {
        if (!result.hit()) {
            return null;
        }
        if (needCare) {
            return "你说的这些我们很在意，可以先看看右边的求助入口";
        }
        return "内容里有些表达可能需要调整，改一改再发也可以";
    }
}

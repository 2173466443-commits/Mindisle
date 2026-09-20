package com.mindisle.audit.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mindisle.audit.SensitiveWordEngine.CheckResult;
import com.mindisle.audit.SensitiveWordEngine.Hit;

/**
 * 预检出参的「求助卡」映射（需求 FR8.4、§18.3 · 创新点 ③ 的入口条件）。
 *
 * <p>这个类的存在理由是一次真实 HTTP 冒烟：同一条文本里既有自伤表达又有手机号时，
 * 引擎按处置强度把「隐私泄露（REVIEW）」排成主因，于是旧实现
 * {@code "risk".equals(result.level())} 判成 false，12356 求助卡直接不出现。
 * 也就是说，越是把处境写清楚（留了联系方式希望被联系上）的求助者，越拿不到求助入口。
 * 现在改成看「命中集合里有没有 risk」，主因只影响文案归类，不影响是否给出口。</p>
 */
class PrecheckViewTest {

    private static final String HOTLINE = "12356";

    private static Hit hit(String word, String category, String level, String action, int start, int end) {
        return new Hit(word, category, level, action, "both", start, end);
    }

    private static CheckResult result(String category, String level, String action, List<Hit> hits) {
        return new CheckResult(!hits.isEmpty(), category, level, action, hits.size(), hits,
                hits.stream().map(h -> new int[] {h.start(), h.end()}).toList(), "v0.1");
    }

    @Test
    @DisplayName("自伤表达与手机号同时命中：主因是隐私泄露，但求助卡必须照样给")
    void careCardSurvivesBeingOutranked() {
        CheckResult mixed = result("隐私泄露", "grey", "REVIEW", List.of(
                hit("13800138000", "隐私泄露", "grey", "REVIEW", 30, 41),
                hit("伤害自己", "自伤自杀", "risk", "TAG", 8, 12)));

        assertThat(mixed.level()).isEqualTo("grey");
        assertThat(mixed.riskTouched()).isTrue();

        PrecheckView view = PrecheckView.of(mixed, HOTLINE, true);
        assertThat(view.hotline()).isEqualTo(HOTLINE);
        assertThat(view.tip()).contains("求助入口");
        // 主因与处置仍如实回传，管理端队列按它分派，不因给了求助卡就伪装成 risk
        assertThat(view.category()).isEqualTo("隐私泄露");
        assertThat(view.action()).isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("只有隐私命中时不给求助卡，免得每次都弹一次热线把人训烦")
    void privacyOnlyGetsNoHotline() {
        CheckResult only = result("隐私泄露", "grey", "REVIEW", List.of(
                hit("13800138000", "隐私泄露", "grey", "REVIEW", 2, 13)));

        assertThat(only.riskTouched()).isFalse();
        assertThat(PrecheckView.of(only, HOTLINE, true).hotline()).isNull();
    }

    @Test
    @DisplayName("检测模型输出（riskAsCare=false）时复述危机词也不给用户弹求助卡")
    void aiSideNeverShowsCareCard() {
        CheckResult risk = result("自伤自杀", "risk", "TAG", List.of(
                hit("伤害自己", "自伤自杀", "risk", "TAG", 0, 4)));

        assertThat(risk.riskTouched()).isTrue();
        assertThat(PrecheckView.of(risk, HOTLINE, false).hotline()).isNull();
        assertThat(PrecheckView.of(risk, HOTLINE, true).hotline()).isEqualTo(HOTLINE);
    }

    @Test
    @DisplayName("未命中的空结果：riskTouched 为 false，出参字段全空且 tip 为 null")
    void emptyResultIsNotRisk() {
        CheckResult none = result(null, null, null, List.of());

        assertThat(none.riskTouched()).isFalse();
        PrecheckView view = PrecheckView.of(none, HOTLINE, true);
        assertThat(view.hit()).isFalse();
        assertThat(view.hotline()).isNull();
        assertThat(view.tip()).isNull();
        assertThat(view.dictVersion()).isEqualTo("v0.1");
    }
}

package com.mindisle.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 隐式评分器单测（任务 T7.1 · 手册 §11.1 点名类 · 需求 §8.2.1）。
 *
 * <p><b>为什么要单独钉这三个函数</b>：R(u,i) 是整条协同过滤的输入，它一旦算错，
 * 错的是「谁和谁是邻居」而不是「某一条推荐准不准」——那种错误在页面上看不出来，
 * 只在离线指标上表现为 Recall 长期偏低，而人会把责任推给「数据太少」。
 * 所以这里不测「像不像」，直接对着公式钉数字。</p>
 *
 * <p>三条不变量分别对应三类真实事故：衰减因子不许 &gt; 1（时钟回拨会让老行为比新行为更值钱）；
 * 夹取下限 0（负反馈不许把分数压成负数，否则下游与质量分相加时会互相抵消，融合权重全部作废）；
 * {@code normalize} 单调（不单调的话 Top-K 会优先端上「中等偏好」而压掉「强偏好」）。</p>
 */
class ImplicitScorerTest {

    @Test
    @DisplayName("时间衰减：当天 1、一个半衰期 0.5、两个半衰期 0.25，未来时间按当天算")
    void decayHalvesByPeriod() {
        assertThat(ImplicitScorer.decay(0L)).isEqualTo(1d);
        assertThat(ImplicitScorer.decay(RecConstants.DECAY_HALFLIFE_DAYS))
            .isCloseTo(0.5d, within(1e-12));
        assertThat(ImplicitScorer.decay(RecConstants.DECAY_HALFLIFE_DAYS * 2L))
            .isCloseTo(0.25d, within(1e-12));
        // 时钟回拨 / 埋点写入用了未来时间：按 0 天处理，绝不许 >1
        assertThat(ImplicitScorer.decay(-9L)).isEqualTo(1d);
        assertThat(ImplicitScorer.decay(-9L)).isLessThanOrEqualTo(1d);
    }

    @Test
    @DisplayName("衰减单调递减：越近的行为权重越高，不许出现锯齿")
    void decayIsMonotonic() {
        for (int d = 1; d <= 60; d++) {
            assertThat(ImplicitScorer.decay(d)).isLessThan(ImplicitScorer.decay(d - 1L));
        }
    }

    @Test
    @DisplayName("单条贡献：null 权重记 0 而不是 NPE，负权重衰减后仍为负")
    void contributionKeepsSignAndHandlesNull() {
        assertThat(ImplicitScorer.contribution(null, 0L)).isZero();
        assertThat(ImplicitScorer.contribution(BigDecimal.valueOf(5d), 0L)).isEqualTo(5d);
        // 不感兴趣 = −3，过了一个半衰期只剩 −1.5：旧负反馈自己退场，不需要清理任务
        assertThat(ImplicitScorer.contribution(BigDecimal.valueOf(-3d),
            RecConstants.DECAY_HALFLIFE_DAYS)).isCloseTo(-1.5d, within(1e-12));
        assertThat(ImplicitScorer.contribution(BigDecimal.valueOf(-3d), 0L)).isNegative();
    }

    @Test
    @DisplayName("夹取：负分压到 0、超上限压到 10，区间内原样")
    void clampToZeroTen() {
        assertThat(ImplicitScorer.clamp(-7.5d)).isZero();
        assertThat(ImplicitScorer.clamp(0d)).isZero();
        assertThat(ImplicitScorer.clamp(3.2d)).isEqualTo(3.2d);
        assertThat(ImplicitScorer.clamp(RecConstants.SCORE_MAX)).isEqualTo(10d);
        assertThat(ImplicitScorer.clamp(999d)).isEqualTo(10d);
    }

    @Test
    @DisplayName("归一化 s/(s+1)：0→0、1→0.5、10→0.909，且严格单调")
    void normalizeIsBoundedAndMonotonic() {
        assertThat(ImplicitScorer.normalize(-4d)).isZero();
        assertThat(ImplicitScorer.normalize(0d)).isZero();
        assertThat(ImplicitScorer.normalize(1d)).isCloseTo(0.5d, within(1e-12));
        assertThat(ImplicitScorer.normalize(10d)).isCloseTo(10d / 11d, within(1e-12));
        // 夹取上限之后不会再增大：这是「normalize 永不超过 1」的根据
        assertThat(ImplicitScorer.normalize(50d)).isEqualTo(ImplicitScorer.normalize(10d));
        double previous = -1d;
        for (double s = 0d; s <= 10d; s += 0.5d) {
            double normalized = ImplicitScorer.normalize(s);
            assertThat(normalized).isBetween(0d, 1d);
            assertThat(normalized).isGreaterThan(previous);
            previous = normalized;
        }
    }

    @Test
    @DisplayName("新鲜度半衰期 7 天；daysBetween 按日期而非时刻，同日多次不算两天")
    void freshnessAndDayCount() {
        assertThat(ImplicitScorer.freshness(0L)).isEqualTo(1d);
        assertThat(ImplicitScorer.freshness(7L)).isCloseTo(0.5d, within(1e-12));
        assertThat(ImplicitScorer.freshness(-1L)).isEqualTo(1d);

        LocalDate base = LocalDate.of(2026, 9, 29);
        assertThat(ImplicitScorer.daysBetween(base, base)).isZero();
        assertThat(ImplicitScorer.daysBetween(base, base.plusDays(3))).isEqualTo(3L);
        assertThat(ImplicitScorer.daysBetween(base, base.minusDays(1))).isEqualTo(-1L);

        LocalDateTime morning = LocalDateTime.of(2026, 9, 29, 8, 0);
        LocalDateTime night = LocalDateTime.of(2026, 9, 29, 23, 59);
        assertThat(ImplicitScorer.daysBetween(morning, night)).as("同一天差 16 小时仍算 0 天")
            .isZero();
        assertThat(ImplicitScorer.daysBetween(morning, night.plusDays(1))).isEqualTo(1L);
    }
}

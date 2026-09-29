package com.mindisle.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 情绪感知加权单测（任务 T7.4 · 创新点② · 手册 §10.2 7.4 · 需求 FR5.3 FR5.9）。
 *
 * <p><b>本类最有价值的一条断言不是公式，而是「心情为 0 不启用」</b>。
 * 0 同时是「没采集到」和「今天很平静」的公共取值，把它当低落处理会大面积误伤；
 * 反过来对心情好的用户启用，会把安抚型内容推到他们面前——那等于用算法制造低沉，
 * 是需求 §8.3 伦理自查里明确拒掉的写法。这条边界写在代码里只有一行，
 * 但它决定了这个功能在答辩时是「创新」还是「事故」。</p>
 *
 * <p>另一件要钉的事是 null 与 0 的区别必须一路保留：{@code normalize(null)} 返回 null 而不是 0，
 * {@code comfortFromReactions(0, 0)} 返回 null 而不是 −5。折叠成数字会让「没数据」
 * 变成「这条内容很伤人心」，一个负权就把新帖永久压死了。</p>
 */
class EmotionBoostTest {

    @Test
    @DisplayName("效价归一化 −5..+5 → −1..+1，越界夹住，null 原样保留")
    void normalizeKeepsUnknownApartFromZero() {
        assertThat(EmotionBoost.normalize(null)).isNull();
        assertThat(EmotionBoost.normalize(0)).isEqualTo(0d);
        assertThat(EmotionBoost.normalize(RecConstants.VALENCE_RANGE)).isEqualTo(1d);
        assertThat(EmotionBoost.normalize(-RecConstants.VALENCE_RANGE)).isEqualTo(-1d);
        assertThat(EmotionBoost.normalize(-3)).isCloseTo(-0.6d, within(1e-12));
        assertThat(EmotionBoost.normalize(99)).as("脏数据夹到上限").isEqualTo(1d);
        assertThat(EmotionBoost.normalize(-99)).isEqualTo(-1d);
    }

    @Test
    @DisplayName("匹配度 1−|Δ| 夹在 0..1：完全同向为 1，反向为 0，缺失记 0")
    void matchIsClampedSimilarity() {
        assertThat(EmotionBoost.match(-5, -5)).isEqualTo(1d);
        assertThat(EmotionBoost.match(0, 0)).isEqualTo(1d);
        assertThat(EmotionBoost.match(-5, 5)).as("最低落 vs 最阳光 → 差 2，夹到 0").isZero();
        assertThat(EmotionBoost.match(-2, -3)).isCloseTo(0.8d, within(1e-12));
        assertThat(EmotionBoost.match(5, -5)).isZero();
        assertThat(EmotionBoost.match(null, -5)).isZero();
        assertThat(EmotionBoost.match(-5, null)).isZero();
        assertThat(EmotionBoost.match(null, null)).isZero();
    }

    @Test
    @DisplayName("只对心情低落者启用：null / 0 / 正数一律 0，负数才给权重")
    void boostOnlyForLowMood() {
        assertThat(EmotionBoost.boost(null, -5)).isZero();
        assertThat(EmotionBoost.boost(0, -5)).as("0 是「未采集」与「平静」的公共值，不当低落处理")
            .isZero();
        assertThat(EmotionBoost.boost(3, 3)).isZero();
        assertThat(EmotionBoost.boost(5, -5)).isZero();
        assertThat(EmotionBoost.boost(-5, -5)).isEqualTo(1d);
        assertThat(EmotionBoost.boost(-5, 5)).as("低落但内容不安抚 → 不加权").isZero();
        assertThat(EmotionBoost.boost(-1, null)).as("帖子没被验证过 → 不加权").isZero();
    }

    @Test
    @DisplayName("安抚效价由评论区正向反应映射：全正 +5、全负 −5、无评论 null")
    void comfortMapsReactionRatioToValence() {
        assertThat(EmotionBoost.comfortFromReactions(0, 0)).as("没评论＝不知道，不是很差").isNull();
        assertThat(EmotionBoost.comfortFromReactions(7, 0)).isNull();
        assertThat(EmotionBoost.comfortFromReactions(-1, 5)).as("脏数据不给分").isNull();
        assertThat(EmotionBoost.comfortFromReactions(0, 5)).isEqualTo(-5);
        assertThat(EmotionBoost.comfortFromReactions(5, 5)).isEqualTo(5);
        assertThat(EmotionBoost.comfortFromReactions(2, 4)).as("一半正向 → 中性").isEqualTo(0);
        assertThat(EmotionBoost.comfortFromReactions(1, 5)).isEqualTo(-3);
        assertThat(EmotionBoost.comfortFromReactions(4, 5)).isEqualTo(3);
        assertThat(EmotionBoost.comfortFromReactions(9, 5)).as("比例越界夹到 +5").isEqualTo(5);
    }

    @Test
    @DisplayName("映射后的帖子侧效价与用户心情一致时，加权必须给满分")
    void comfortThenBoostChain() {
        Integer comfort = EmotionBoost.comfortFromReactions(5, 5);
        assertThat(comfort).isEqualTo(5);
        assertThat(EmotionBoost.boost(-5, comfort)).as("反向组合不该加分").isZero();
        Integer gloomy = EmotionBoost.comfortFromReactions(0, 5);
        assertThat(EmotionBoost.boost(-5, gloomy)).as("低落推安抚型 → 满分").isEqualTo(1d);
    }

    @Test
    @DisplayName("进融合公式的权重是 w4·boost，且永不为负")
    void weightedUsesEmotionCoefficient() {
        assertThat(EmotionBoost.weighted(-5, -5))
            .isCloseTo(RecConstants.W_EMOTION, within(1e-12));
        assertThat(EmotionBoost.weighted(0, -5)).isZero();
        assertThat(EmotionBoost.weighted(null, null)).isZero();
        for (int mood = -6; mood <= 6; mood++) {
            for (int comfort = -6; comfort <= 6; comfort++) {
                assertThat(EmotionBoost.weighted(mood, comfort))
                    .as("mood=%s comfort=%s", mood, comfort)
                    .isBetween(0d, RecConstants.W_EMOTION);
            }
        }
    }

    @Test
    @DisplayName("默认打散与默认权重不打架：情绪加权不会把分数顶出 0..1")
    void boostStaysInsideFusionRange() {
        double qualityAndCfMax = RecConstants.W_CF + RecConstants.W_QUALITY
            + RecConstants.W_FRESH;
        assertThat(qualityAndCfMax + RecConstants.W_EMOTION)
            .as("四项正向权重之和必须 ≤ 1，否则 DECIMAL(8,6) 会被静默夹住")
            .isLessThanOrEqualTo(1d + 1e-9);
        assertThat(EmotionBoost.weighted(-5, -5)).isLessThanOrEqualTo(RecConstants.W_EMOTION);
    }

    @Test
    @DisplayName("效价量程常量单一来源：±5 与 ENUM 一致，改这里必须同时改库")
    void valenceRangeIsFive() {
        assertThat(RecConstants.VALENCE_RANGE).isEqualTo(5);
        assertThat(EmotionBoost.normalize(RecConstants.VALENCE_RANGE)).isEqualTo(1d);
        assertThat(EmotionBoost.comfortFromReactions(5, 5)).isEqualTo(RecConstants.VALENCE_RANGE);
    }
}

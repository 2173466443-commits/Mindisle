package com.mindisle.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 冷启动策略单测（任务 T7.5 · 手册 §10.2 7.5 · 需求 FR5.5）。
 *
 * <p><b>阈值 20 不是玄学，是一条失败形态的分界线</b>：低于它时 UserCF 的邻居表几乎必然为空
 * （共同帖数下限会把候选邻居筛光），走 CF 的表现不是「推得不准」而是<b>整屏空白</b>，
 * 新用户读到的是「这个社区没人发帖」。所以这里先钉阈值的两侧，再钉「没有话题信号时只剩热度」。</p>
 *
 * <p>另一件必须钉住的事：六个通道名与 {@code recommend_result.recall_channel} 的 ENUM 逐字一致。
 * 写错一个字母 MySQL 不报错、静默存成默认值，于是需求 §6.4 的六组对照指标会永久失真，
 * 而且只有等到写论文时才会发现——那种错误没有回滚的余地。</p>
 */
class ColdStartTest {

    @Test
    @DisplayName("CF 资格线：≥20 次才交给协同过滤，19 次不算（宁可退标签召回）")
    void cfEligibilityBoundary() {
        assertThat(RecConstants.CF_MIN_INTERACTIONS).isEqualTo(20);
        assertThat(ColdStart.cfEligible(0L)).isFalse();
        assertThat(ColdStart.cfEligible(19L)).isFalse();
        assertThat(ColdStart.cfEligible(20L)).isTrue();
        assertThat(ColdStart.cfEligible(21L)).isTrue();
        assertThat(ColdStart.cfEligible(9_999L)).isTrue();
    }

    @Test
    @DisplayName("首屏通道：够数走 usercf，不够数有标签走 content，两者都没有走 hot")
    void firstChannelDecisionTable() {
        assertThat(ColdStart.firstChannel(20L, true)).isEqualTo(ColdStart.CHANNEL_USERCF);
        assertThat(ColdStart.firstChannel(20L, false))
            .as("够 CF 数就不该再被标签牵着走").isEqualTo(ColdStart.CHANNEL_USERCF);
        assertThat(ColdStart.firstChannel(19L, true)).isEqualTo(ColdStart.CHANNEL_CONTENT);
        assertThat(ColdStart.firstChannel(0L, true)).isEqualTo(ColdStart.CHANNEL_CONTENT);
        assertThat(ColdStart.firstChannel(19L, false)).isEqualTo(ColdStart.CHANNEL_HOT);
        assertThat(ColdStart.firstChannel(0L, false)).isEqualTo(ColdStart.CHANNEL_HOT);
    }

    @Test
    @DisplayName("探索位只对够数的用户开放，且一屏按 1/5 向下取整")
    void exploreGatingAndSlots() {
        assertThat(ColdStart.allowExplore(19L)).as("冷启动期先别乱撒").isFalse();
        assertThat(ColdStart.allowExplore(20L)).isTrue();
        assertThat(ColdStart.exploreSlots(0)).isZero();
        assertThat(ColdStart.exploreSlots(RecConstants.EXPLORE_EVERY - 1)).isZero();
        assertThat(ColdStart.exploreSlots(RecConstants.EXPLORE_EVERY)).isEqualTo(1);
        assertThat(ColdStart.exploreSlots(11)).isEqualTo(2);
        assertThat(ColdStart.exploreSlots(RecConstants.FEED_DEFAULT_SIZE)).isEqualTo(4);
        assertThat(ColdStart.exploreSlots(RecConstants.FEED_MAX_SIZE)).isEqualTo(10);
    }

    @Test
    @DisplayName("六个通道名逐字钉死：它们必须等于 recommend_result.recall_channel 的 ENUM")
    void channelNamesAreEnumLiteral() {
        assertThat(ColdStart.CHANNELS).containsExactlyInAnyOrder("usercf", "itemcf", "content",
            "hot", "explore", "emotion");
        assertThat(ColdStart.CHANNELS).hasSize(6);
        for (String channel : ColdStart.CHANNELS) {
            assertThat(ColdStart.isKnownChannel(channel)).as(channel).isTrue();
        }
    }

    @Test
    @DisplayName("ENUM 校验拒掉错拼/大小写/空串/null：拼错通道 MySQL 会静默存默认值")
    void unknownChannelsRejected() {
        assertThat(ColdStart.isKnownChannel("UserCF")).isFalse();
        assertThat(ColdStart.isKnownChannel("user_cf")).isFalse();
        assertThat(ColdStart.isKnownChannel("cf")).as("cf 是分流模式，不是召回通道").isFalse();
        assertThat(ColdStart.isKnownChannel("")).isFalse();
        assertThat(ColdStart.isKnownChannel(null)).as("脏值不许抛 NPE 把整批重算干掉").isFalse();
    }

    @Test
    @DisplayName("通道常量与 RecMode 取值不重叠：两套枚举混用会让对照组失真")
    void channelAndModeNamespacesDoNotCollide() {
        assertThat(ColdStart.CHANNELS).doesNotContain(RecMode.MODE_CF, RecMode.MODE_AB);
        assertThat(ColdStart.CHANNELS).as("hot 既是通道名又是对照组名，是本项目唯一的命名重叠点，"
            + "因此两处取值都必须显式转换")
            .contains(ColdStart.CHANNEL_HOT);
        assertThat(RecMode.MODE_HOT).isEqualTo(ColdStart.CHANNEL_HOT);
    }
}

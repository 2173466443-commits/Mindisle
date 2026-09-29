package com.mindisle.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 推荐理由文案单测（任务 T7.7 · 手册 §10.2 7.7 · 需求 FR5.8）。
 *
 * <p><b>理由是算法对用户的唯一自述</b>：答辩上最能证明「推荐真的在跑」的不是分数，而是卡片上那句
 * 「为什么推给我」。所以这里钉三件事：</p>
 * <ol>
 *   <li>每路召回各说各的话。六条理由长得一模一样 = 把埋点攒出来的解释力又丢了，
 *       而且手册 §10.6 点名的「同一句理由在页面上出现 6 次」就是这么来的；</li>
 *   <li>拿不到信号时不许瞎编。topic 与 emotion 同时缺失只允许收起「#话题名」这半句，
 *       不许拼出「因为你关注了 #null」，更不许整句改口去借热度榜那句「被很多屿民读完」——
 *       用户看到 null 会以为系统在偷读他的隐私标签，看到假的热度句则会以为推荐在骗他；</li>
 *   <li>截断按<b>码点</b>而不是 char。列宽 VARCHAR(200)，话题名可能全是中文，
 *       按 UTF-16 单元截会把代理对劈成半个字符，MySQL 直接报编码错、整批预计算跟着回滚。</li>
 * </ol>
 */
class ReasonBuilderTest {

    @Test
    @DisplayName("六路召回各说各话：给同一个话题名，六条理由互不重复")
    void eachChannelHasItsOwnWording() {
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_ITEMCF, "失眠", null, 3))
            .isEqualTo("看过这篇的屿民也看过 #失眠（3 人共鸣）");
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_USERCF, "失眠", null, 0))
            .isEqualTo("和你口味相近的屿民正在看 #失眠");
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_CONTENT, "失眠", null, 0))
            .isEqualTo("因为你关注了 #失眠");
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_EMOTION, "失眠", "被治愈", 0))
            .isEqualTo("今天你的心情偏低落，这条被很多人标记为「被治愈」");
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_EXPLORE, "失眠", null, 0))
            .isEqualTo("换个口味：#失眠 里一篇还没被读过的帖子");
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_HOT, "失眠", null, 0))
            .isEqualTo("社区今天讨论最多的 #失眠");

        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_ITEMCF, "失眠", null, 3))
            .isNotEqualTo(ReasonBuilder.of(ColdStart.CHANNEL_USERCF, "失眠", null, 3));
    }

    @Test
    @DisplayName("itemcf 没有共读人数时换一套措辞：不许写「N 人共鸣」而 N 是 0")
    void itemcfFallsBackWithoutCoReaderCount() {
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_ITEMCF, "考研", null, 0))
            .isEqualTo("和你读过的内容很像：考研");
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_ITEMCF, "考研", null, -1))
            .isEqualTo("和你读过的内容很像：考研");
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_ITEMCF, "考研", null, 1))
            .isEqualTo("看过这篇的屿民也看过 #考研（1 人共鸣）");
    }

    @Test
    @DisplayName("信号缺失的三种形态：都缺走通用文案，只缺话题走占位名，通道未知走兜底")
    void missingSignalsDegradeHonestly() {
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_HOT, null, null, 0))
            .isEqualTo("社区里最近被很多屿民读完的内容");
        assertThat(ReasonBuilder.of(null, "  ", "", 0))
            .as("空白话题名等同没话题").isEqualTo("社区里最近被很多屿民读完的内容");
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_USERCF, null, "焦虑", 0))
            .isEqualTo("和你口味相近的屿民正在看 #屿民推荐");
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_EMOTION, "失眠", null, 0))
            .as("情绪通道缺标签时用默认标签「被治愈」，而不是拼出 null")
            .isEqualTo("今天你的心情偏低落，这条被很多人标记为「被治愈」");
        assertThat(ReasonBuilder.of("whatever", "失眠", null, 0)).isEqualTo("社区推荐：失眠");
        assertThat(ReasonBuilder.of(null, "失眠", null, 0)).isEqualTo("社区推荐：失眠");
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_HOT, "  失眠  ", null, 0))
            .as("话题名两侧空白被 trim，不许出现「# 失眠 」").isEqualTo("社区今天讨论最多的 #失眠");
    }

    @Test
    @DisplayName("理由里不许出现 null / 空白 / 未 trim 的占位符（前端直接渲染这一段文字）")
    void neverLeaksNullToken() {
        for (String channel : ColdStart.CHANNELS) {
            assertThat(ReasonBuilder.forStore(channel, null, null, 0)).doesNotContain("null");
            assertThat(ReasonBuilder.forStore(channel, "失眠", null, 0)).doesNotContain("null");
            assertThat(ReasonBuilder.forStore(channel, null, "被治愈", 0)).doesNotContain("null");
        }
    }

    @Test
    @DisplayName("按码点截断：中文不劈半、代理对不成对、空串归 null")
    void cutIsCodePointSafe() {
        assertThat(ReasonBuilder.cut(null, 10)).isNull();
        assertThat(ReasonBuilder.cut("", 10)).isNull();
        assertThat(ReasonBuilder.cut("   ", 10)).isNull();
        assertThat(ReasonBuilder.cut("abc", 3)).isEqualTo("abc");
        assertThat(ReasonBuilder.cut("abcd", 3)).isEqualTo("abc");
        assertThat(ReasonBuilder.cut("失眠焦虑", 4)).isEqualTo("失眠焦虑");
        assertThat(ReasonBuilder.cut("失眠焦虑", 2)).isEqualTo("失眠");

        String emoji = "🙂🙂🙂🙂";
        String cutted = ReasonBuilder.cut(emoji, 3);
        assertThat(cutted).as("3 个码点 = 6 个 UTF-16 单元").hasSize(6);
        assertThat(cutted.codePointCount(0, cutted.length())).isEqualTo(3);
        assertThat(Character.isHighSurrogate(cutted.charAt(cutted.length() - 1)))
            .as("结尾不许是半个代理对").isFalse();
    }

    @Test
    @DisplayName("落库出口 forStore 恒定不超过列宽 VARCHAR(200)")
    void forStoreRespectsColumnWidth() {
        assertThat(RecConstants.REASON_MAX).isEqualTo(200);
        String longTopic = "失".repeat(300);
        for (String channel : ColdStart.CHANNELS) {
            String reason = ReasonBuilder.forStore(channel, longTopic, "被治愈", 9_999);
            assertThat(reason.codePointCount(0, reason.length()))
                .as("通道 %s", channel).isLessThanOrEqualTo(RecConstants.REASON_MAX);
            assertThat(Character.isHighSurrogate(reason.charAt(reason.length() - 1))).isFalse();
        }
        String boundary = "A".repeat(RecConstants.REASON_MAX);
        assertThat(ReasonBuilder.forStore(ColdStart.CHANNEL_HOT, boundary, null, 0))
            .hasSize(RecConstants.REASON_MAX);
    }

    @Test
    @DisplayName("探索位文案要诚实说「还没被读过」，热度兜底不许冒充个性化理由")
    void wordingDoesNotOverclaim() {
        String explore = ReasonBuilder.of(ColdStart.CHANNEL_EXPLORE, "绘画", null, 0);
        assertThat(explore).contains("换个口味").contains("#绘画");
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_HOT, "绘画", null, 0))
            .as("热度组不许出现「因为你…」这种因果句")
            .doesNotContain("因为你");
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_CONTENT, "绘画", null, 0))
            .as("content 通道才有资格说「因为你关注了」").isEqualTo("因为你关注了 #绘画");
    }

    @Test
    @DisplayName("Gate7 抓到的早退：个性化通道两个信号都缺，也不许冒充足人读的热度句")
    void personalizedChannelsNeverBorrowHotFallback() {
        String[] personalized = {ColdStart.CHANNEL_ITEMCF, ColdStart.CHANNEL_USERCF,
            ColdStart.CHANNEL_CONTENT, ColdStart.CHANNEL_EMOTION, ColdStart.CHANNEL_EXPLORE};
        java.util.List<String> noSignal = new java.util.ArrayList<>();
        for (String channel : personalized) {
            String reason = ReasonBuilder.of(channel, null, null, 0);
            assertThat(reason).as("通道 %s 无信号时不许借用热度兜底句", channel)
                .isNotEqualTo(ReasonBuilder.NO_SIGNAL_HOT);
            assertThat(reason).as("通道 %s 不许声称「很多屿民读过」这种只有热度榜才敢说的事实", channel)
                .doesNotContain("很多屿民");
            noSignal.add(reason);
        }
        assertThat(noSignal).as("五路个性化召回即使都没有信号，五句话仍然各说各话")
            .doesNotHaveDuplicates();
        // 反向钉住：兜底句本身仍然留给 hot 与未知通道，别把这一句整条删掉。
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_HOT, null, null, 0))
            .isEqualTo(ReasonBuilder.NO_SIGNAL_HOT);
        assertThat(ReasonBuilder.of("mystery", null, null, 0))
            .isEqualTo(ReasonBuilder.NO_SIGNAL_HOT);
    }

    @Test
    @DisplayName("共读人数是真实数字：不许把 0 说成「很多人」")
    void coReaderCountIsLiteral() {
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_ITEMCF, "失眠", null, 42))
            .contains("42 人共鸣");
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_ITEMCF, "失眠", null, 1234567))
            .contains("1234567 人共鸣");
        assertThat(ReasonBuilder.of(ColdStart.CHANNEL_USERCF, "失眠", null, 42))
            .as("只有 itemcf 用得上共读人数").doesNotContain("42");
    }
}

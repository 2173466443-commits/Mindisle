package com.mindisle.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mindisle.audit.TextNormalizer.Normalized;

/**
 * 归一化单测（任务 3.2 的地基，手册 §6.3 点名的三类变体在这里先钉一层）。
 *
 * <p>重点不是「能不能删掉空格」，而是<b>删完之后位置还对不对</b>：
 * 前端拿 positions 画高亮，偏移错位比不提示更糟——它会把正常的字标红。</p>
 */
class TextNormalizerTest {

    @Test
    @DisplayName("全半角与空白：全角字母数字折成半角小写，空格/全角空格删除")
    void foldsWidthAndSpace() {
        assertThat(TextNormalizer.normalize("\uFF21\u3000\uFF22 1").text()).isEqualTo("ab1");
    }

    @Test
    @DisplayName("繁简与同形字：槍→枪、彈→弹、西里尔 р→拉丁 p")
    void foldsLookalike() {
        assertThat(TextNormalizer.normalize("\u69cd\u652f\u5f48\u85e5").text()).isEqualTo("\u67aa\u652f\u5f39\u836f");
        assertThat(TextNormalizer.normalize("\u04402\u0440").text()).isEqualTo("p2p");
    }

    @Test
    @DisplayName("分隔型字符整体删除：emoji、零宽、中点、连字符、变体选择符")
    void dropsSeparators() {
        assertThat(TextNormalizer.normalize("\u6c6a\uD83D\uDE0A\u742a").text()).isEqualTo("\u6c6a\u742a");
        assertThat(TextNormalizer.normalize("\u611f\u200B\u89c9").text()).isEqualTo("\u611f\u89c9");
        assertThat(TextNormalizer.normalize("\u654f\u00b7\u611f\u8bcd").text()).isEqualTo("\u654f\u611f\u8bcd");
        assertThat(TextNormalizer.normalize("a-\u00ADb\uFE0Fc").text()).isEqualTo("abc");
    }

    @Test
    @DisplayName("位置回写：区间覆盖词内被删分隔符，但不吞掉命中之后的内容")
    void mapsBackToRawOffsets() {
        String raw = "x\u6c6a\uD83D\uDE0A\u742a y";
        Normalized normalized = TextNormalizer.normalize(raw);
        // 归一化串 = "x汪琪y"，「汪」在原文 1、表情符号占 2..3、「琪」在 4、空格 5、y 在 6
        assertThat(normalized.text()).isEqualTo("x\u6c6a\u742ay");
        assertThat(normalized.sourceIndex()).containsExactly(0, 1, 4, 6);
        // 命中「汪琪」=归一化下标 [1,3)，切片必须连中间的表情符号一起覆盖
        int[] hit = normalized.toRawRange(1, 3);
        assertThat(raw.substring(hit[0], hit[1])).isEqualTo("\u6c6a\uD83D\uDE0A\u742a");
        // 延伸到末尾：终点是「最后一个命中字符的末尾」，尾随空格不算进来
        assertThat(normalized.toRawRange(3, 4)).containsExactly(6, 7);
        assertThat(normalized.toRawRange(1, 4)).containsExactly(1, 7);
        // 越界与退化区间只做夹紧，不抛异常（位置只是高亮提示，不该把接口打成 500）
        assertThat(normalized.toRawRange(0, 99)).containsExactly(0, 7);
        assertThat(normalized.toRawRange(2, 2)).containsExactly(2, 2);
    }

    @Test
    @DisplayName("null 与空串安全：返回空结果而不是抛异常")
    void handlesNullAndBlank() {
        assertThat(TextNormalizer.normalize(null).text()).isEmpty();
        assertThat(TextNormalizer.normalize("").sourceIndex()).isEmpty();
        assertThat(TextNormalizer.normalize("   ").text()).isEmpty();
    }
}

package com.mindisle.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * 搜索关键词入参归一（任务 3.9 · 需求 FR4.8、NFR10）。
 *
 * <p>两条判据各自对应一种真实故障：<b>空</b>——前端漏判时会让搜索接口退化成分页列表，
 * 广场能看到的私密旁路径就多出一个入口；<b>超长</b>——一条 {@code content LIKE '%…3000 字%'}
 * 是全表扫，任何人都能用它把数据库拖慢，而它看起来完全像个正常请求。</p>
 *
 * <p>另一个主张是「超长报错而不是截断」。截断看似宽容，实际是悄悄换了用户要查的东西：
 * 「我明明搜了这句话」却拿到另一个词的结果，比一句明确的提示更难解释，也更难复现。</p>
 */
class KeywordTest {

    private static final int MAX = 64;

    private void assertParamInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            String expectedMsgFragment) {
        assertThatExceptionOfType(BizException.class).isThrownBy(call).satisfies(e -> {
            // BizException 只有 getErrorCode()：断言码而不是断言 HTTP 状态，状态归 GlobalExceptionHandler 管
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
            assertThat(e.getMessage()).contains(expectedMsgFragment);
        });
    }

    @Test
    @DisplayName("正常关键词只去首尾空白，中间空格原样保留")
    void trimsOnlyTheEnds() {
        assertThat(Keyword.normalize("  最近 睡不好  ", MAX)).isEqualTo("最近 睡不好");
        assertThat(Keyword.normalize("焦虑", MAX)).isEqualTo("焦虑");
    }

    @Test
    @DisplayName("null、空串、纯空白（含制表与换行）都是 10001「请输入搜索关键词」")
    void blankIsRejected() {
        for (String raw : new String[] { null, "", " ", "   ", "\t", "\n", " \t\n " }) {
            assertParamInvalid(() -> Keyword.normalize(raw, MAX), "请输入搜索关键词");
        }
    }

    @Test
    @DisplayName("正好到上限放过、超一个字符就报错：不静默截断")
    void overlongIsRejectedNotTruncated() {
        String exact = "想".repeat(MAX);
        assertThat(Keyword.normalize(exact, MAX)).isEqualTo(exact);
        assertThat(Keyword.normalize("  " + exact + "  ", MAX)).isEqualTo(exact);

        String over = exact + "了";
        assertParamInvalid(() -> Keyword.normalize(over, MAX), "64");
    }

    @Test
    @DisplayName("上限来自入参而不是写死在函数里（NFR10：配置项不硬编码）")
    void maxCharsComesFromTheArgument() {
        assertThat(Keyword.normalize("abc", 3)).isEqualTo("abc");
        assertParamInvalid(() -> Keyword.normalize("abcd", 3), "最长 3 字");
    }

    @Test
    @DisplayName("判的是字符数不是字节数：一个汉字不该按三个字节把配额吃掉一半")
    void countsCharsNotBytes() {
        String chinese = "焦虑" .repeat(32); // 64 个 char / 192 个 UTF-8 字节
        assertThat(Keyword.normalize(chinese, MAX)).isEqualTo(chinese);
        assertThat(chinese).hasSize(MAX);
    }
}
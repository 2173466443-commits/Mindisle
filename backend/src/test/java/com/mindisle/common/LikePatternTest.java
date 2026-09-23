package com.mindisle.common;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIKE 模式串的转义（任务 3.9 · 需求 FR4.8 · 手册 §14「转义符与 SQL 逐字配对」）。
 *
 * <p><b>本类只有一条主张</b>：用户输入的东西永远只能是「要匹配的字」，不能是「匹配规则」。
 * 转义漏一次的实际后果不是返回 0 条，而是返回<b>全部</b>条——搜 {@code %} 命中全站正文，
 * 一个搜索框就此变成一条没有 WHERE 的全表扫描。这条主张值得单独立一个类，
 * 而不是当作搜索域某个测试里的一句 contains。</p>
 *
 * <p>SQL 那半边（{@code ESCAPE '!'} 是否真拼上、模式串是否走占位符）在
 * {@code PostSearchSqlConditionTest} 与 {@code SearchServiceTest} 里钉，两边合起来才是完整的
 * 「转义生效」；任何一边单独绿都不算。</p>
 */
class LikePatternTest {

    /** 首尾各有一个刻意的 {@code %}，检查正文时先剥掉。 */
    private static String body(String pattern) {
        return pattern.substring(1, pattern.length() - 1);
    }

    /** 正文里是否存在「未被转义符引领」的通配符——逐字符扫，遇到转义符就跳过一个。 */
    private static boolean hasBareWildcard(String pattern) {
        String inner = body(pattern);
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == LikePattern.ESCAPE) {
                i++;
                continue;
            }
            if (c == '%' || c == '_') {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("普通关键词两侧包 %：子串包含语义")
    void plainKeywordIsWrappedInPercent() {
        assertThat(LikePattern.contains("焦虑")).isEqualTo("%焦虑%");
        assertThat(LikePattern.contains("sleep")).isEqualTo("%sleep%");
    }

    @Test
    @DisplayName("百分号与下划线被转义：搜 % 不再等于列出全站")
    void wildcardsAreEscaped() {
        assertThat(LikePattern.contains("%")).isEqualTo("%!%%");
        assertThat(LikePattern.contains("_")).isEqualTo("%!_%");
        assertThat(LikePattern.contains("100%")).isEqualTo("%100!%%");
        assertThat(LikePattern.contains("a_b")).isEqualTo("%a!_b%");
    }

    @Test
    @DisplayName("转义符自身加倍，且加倍必须排在转义其它字符之前")
    void escapeCharIsDoubledFirst() {
        assertThat(LikePattern.contains("!")).isEqualTo("%!!%");
        // 顺序反了就错：先转义 % 会得到 "!%"，再把 ! 加倍成 "!!%"，
        // SQL 读出来是「一个字面 ! 后面跟着通配符」——转义静默失效，且失效方向是「放宽」。
        assertThat(LikePattern.contains("!%_")).isEqualTo("%!!!%!_%");
        assertThat(LikePattern.contains("100%_!x")).isEqualTo("%100!%!_!!x%");
    }

    @Test
    @DisplayName("任意含元字符的输入，转义后正文里都不再有裸通配符")
    void noBareWildcardSurvives() {
        List<String> nasty = List.of("%", "_", "!", "%%", "__", "!!", "%_", "_!", "!%_", "100%", "a%b_c!",
                "%", "\\", "\"", "'", "a b", "' OR '1'='1");
        for (String raw : nasty) {
            assertThat(hasBareWildcard(LikePattern.contains(raw)))
                    .as("输入 %s 转义后仍有裸通配符", raw)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("转义符与 SQL 侧的 ESCAPE '!' 逐字一致")
    void escapeCharMatchesTheSqlLiteral() {
        // 改这里必须同时改 PostQueryService / SearchService 里的 SQL 文本，两边不匹配时
        // MySQL 报的是「Incorrect arguments to ESCAPE」——一个用户输入 ! 就能撞上的 500。
        assertThat(LikePattern.ESCAPE).isEqualTo('!');
    }
}
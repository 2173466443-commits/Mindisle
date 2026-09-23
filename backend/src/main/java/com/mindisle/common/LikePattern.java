package com.mindisle.common;

/**
 * LIKE 模式串（任务 3.9 · 需求 FR4.8）。
 *
 * <p><b>转义不是可选项</b>：{@code %} 与 {@code _} 在 LIKE 右侧是元字符。用户输入一个
 * {@code %}，不转义的实现就把它当模式发下去 —— 于是「搜索」命中全站内容，退化成
 * 一条没有 WHERE 的列表查询。这条通道同时还是给压测与拖库留的门（一次请求扫全表正文）。</p>
 *
 * <p><b>转义符为什么选 {@code !} 而不是反斜杠</b>：MySQL 字符串字面量里的 {@code '\'}
 * 会被当成转义序列的起始，要写成 {@code '\\'}，而它到底该写几个反斜杠还取决于
 * {@code NO_BACKSLASH_ESCAPES} —— 一个跨配置的坑，用不着往里跳。{@code !} 在 SQL 里
 * 是普通字符，在用户输入里几乎不出现，出现了也能被本函数正确加倍。</p>
 *
 * <p>放在 common 的理由与 {@link Keyword} 相同：post 域与 search 域都用它，谁都不该抄第二份。</p>
 */
public final class LikePattern {

    /** 与 SQL 里 {@code ESCAPE '!'} 逐字一致，改这里必须同时改两处 SQL 文本。 */
    public static final char ESCAPE = '!';

    private LikePattern() {
    }

    /** 转义 {@code !}、{@code %}、{@code _}，再包两侧 {@code %}（子串包含语义）。 */
    public static String contains(String keyword) {
        String e = String.valueOf(ESCAPE);
        String escaped = keyword.replace(e, e + e).replace("%", e + "%").replace("_", e + "_");
        return "%" + escaped + "%";
    }
}
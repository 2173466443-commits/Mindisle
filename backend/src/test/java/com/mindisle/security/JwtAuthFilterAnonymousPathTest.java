package com.mindisle.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 匿名路径判定测试（任务 T3.8 补）。"哪些请求根本不去解析令牌"这件事只由过滤器里那两份清单决定，
 * 而它写错的表现为「带着合法令牌也说你没登录」——从前端看像是后端压根没做这个接口。
 *
 * <p><b>为什么不并进 {@code JwtServiceTest}</b>：那个类测「令牌本身有效吗」，
 * 本类测「这道请求会不会被拿去验令牌」，是链路上相邻但正交的两件事。混在一个类里，
 * 下次白名单再改时没人知道该改哪组断言。</p>
 *
 * <p><b>真踩到的两个坑（本类存在的直接原因，都是 T3.8 冒烟脚本实测出来的，不是推演）</b>：
 * ① 话题墙上线时把 {@code "/api/topics"} 当**前缀**写进白名单，于是 T3.8 新增的
 * {@code /api/topics/{id}}、{@code /api/topics/{id}/posts}、{@code /api/topics/{id}/follow}
 * 三条全被跳过解析令牌，合法 JWT 照样 401/10002；
 * ② 修成"整串相等"之后仍然不够——创建话题是 {@code POST /api/topics}，
 * <b>和游客可逛的墙共用同一个 URI</b>，只按 URI 比就必然二选一：
 * 要么匿名也能建话题，要么登录也建不了（实测是后者）。所以判定必须带方法一起问。</p>
 *
 * <p>表里成对出现的行就是判据本身：同一个 URI、只有方法不同、期望相反。
 * 白名单一旦退回"只看 URI"，第 8~10 行会立刻变 true（等于把创建口开给游客），
 * 第 11~16 行会立刻变 false（等于登录用户进不了话题页）。</p>
 */
class JwtAuthFilterAnonymousPathTest {

    /** 判据表：{HTTP 方法, URI, 是否匿名（不解析令牌）}。 */
    private static final String[][] CASES = {
            {"GET", "/api/topics", "true"},
            {"HEAD", "/api/topics", "true"},
            {"GET", "/api/auth/login", "true"},
            {"POST", "/api/auth/login", "true"},
            {"GET", "/api/system/health", "true"},
            {"GET", "/uploads/2026-01-01/a.png", "true"},
            {"GET", "/doc.html", "true"},
            {"POST", "/api/topics", "false"},
            {"PUT", "/api/topics", "false"},
            {"DELETE", "/api/topics", "false"},
            {"GET", "/api/topics/1", "false"},
            {"GET", "/api/topics/1/posts", "false"},
            {"POST", "/api/topics/1/follow", "false"},
            {"GET", "/api/topics/abc", "false"},
            {"GET", "/api/topicsXXX", "false"},
            {"POST", "/api/topicsXXX/follow", "false"},
            {"GET", "/api/posts", "false"},
            {"GET", "/api/users/me", "false"},
            {"GET", "/api/feed/following", "false"},
    };

    @Test
    @DisplayName("话题墙只放行读方法；它下面挂的三条话题接口与同 URI 上的创建口一律要解析令牌")
    void anonymousWhitelistIsMethodAware() {
        for (String[] c : CASES) {
            assertThat(JwtAuthFilter.isAnonymous(c[0], c[1]))
                    .as("method=%s uri=%s 期望匿名=%s", c[0], c[1], c[2])
                    .isEqualTo(Boolean.parseBoolean(c[2]));
        }
    }

    @Test
    @DisplayName("方法名大小写不敏感；URI 或方法为 null 时判为非匿名（宁可多验一次令牌，也不放开一个口）")
    void nullAndCaseHandling() {
        assertThat(JwtAuthFilter.isAnonymous("get", "/api/topics")).isTrue();
        assertThat(JwtAuthFilter.isAnonymous("GET", null)).isFalse();
        assertThat(JwtAuthFilter.isAnonymous(null, "/api/topics")).isFalse();
    }
}

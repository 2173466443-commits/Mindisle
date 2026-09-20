package com.mindisle.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mindisle.cache.CaffeineCacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.User;

/**
 * 发帖/评论频率单测（任务 T3.12 · 需求 BR4、BR5、BR6）。
 *
 * <p>本类刻意不碰数据库、不用 Mockito：PostingQuotaService 的全部状态都在 CacheService 里，
 * 而 CaffeineCacheService 自带 public 无参构造，直接 new 就是最真实的 fake——
 * 它连「incr 存的是 Long、peek 用 Long.class 读」这个契约一起验了，
 * 换成打桩的 mock 反而会把这条契约的破坏藏住。</p>
 *
 * <p>时间全部走固定入参 {@link #NOW}：服务内部不读系统时钟，所以「跨自然日重置」
 * 这种规则能一次跑完，不需要等到半夜。</p>
 */
class PostingQuotaServiceTest {

    /** 2026-09-20 10:00，避开整点与跨日边界，防「凑巧对上」。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 10, 0);

    private CaffeineCacheService cache;
    private MindisleProperties properties;
    private PostingQuotaService service;

    @BeforeEach
    void setUp() {
        cache = new CaffeineCacheService();
        properties = new MindisleProperties();
        service = new PostingQuotaService(cache, properties);
    }

    private static User user(long id, String status, LocalDateTime createdAt) {
        User user = new User();
        user.setId(id);
        user.setStatus(status);
        user.setCreatedAt(createdAt);
        return user;
    }

    /** 1 小时前注册：仍在新手保护期。 */
    private static User newbie(long id) {
        return user(id, "ACTIVE", NOW.minusHours(1));
    }

    /** 30 天前注册：常规用户。 */
    private static User veteran(long id) {
        return user(id, "ACTIVE", NOW.minusDays(30));
    }

    private static ErrorCode codeOf(Runnable call) {
        BizException error = assertThrows(BizException.class, call::run);
        return error.getErrorCode();
    }

    /** 把额度用满：连发 n 帖并逐条计数。 */
    private void postNTimes(User user, int times) {
        for (int i = 0; i < times; i++) {
            service.assertCanPost(user, NOW);
            service.recordPostCreated(user, NOW);
        }
    }

    @Test
    @DisplayName("BR5：新注册 24 小时内每天 5 帖，第 6 帖 429/10010")
    void newbieHitsFivePostLimit() {
        User guy = newbie(1L);
        assertThat(service.isNewbie(guy, NOW)).isTrue();
        assertThat(service.dailyPostLimit(guy, NOW)).isEqualTo(5);
        postNTimes(guy, 5);
        assertThat(codeOf(() -> service.assertCanPost(guy, NOW))).isEqualTo(ErrorCode.RATE_LIMITED);
        assertThat(service.remainingPosts(guy, NOW)).isZero();
    }

    @Test
    @DisplayName("BR5：老用户每天 20 帖，第 19 帖还能发、第 21 帖被拦")
    void veteranGetsTwentyPosts() {
        User gal = veteran(2L);
        assertThat(service.isNewbie(gal, NOW)).isFalse();
        assertThat(service.dailyPostLimit(gal, NOW)).isEqualTo(20);
        postNTimes(gal, 19);
        assertThat(service.remainingPosts(gal, NOW)).isEqualTo(1);
        postNTimes(gal, 1);
        assertThat(service.remainingPosts(gal, NOW)).isZero();
        assertThat(codeOf(() -> service.assertCanPost(gal, NOW))).isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("保护期边界：满 24 小时即按老用户，注册时间缺失按新手（宁严勿松）")
    void newbieWindowBoundaryAndMissingCreatedAt() {
        assertThat(service.isNewbie(user(3L, "ACTIVE", NOW.minusHours(23).minusMinutes(59)), NOW)).isTrue();
        assertThat(service.isNewbie(user(4L, "ACTIVE", NOW.minusHours(24)), NOW)).isFalse();
        User noCreatedAt = user(5L, "ACTIVE", null);
        assertThat(service.isNewbie(noCreatedAt, NOW)).isTrue();
        assertThat(service.dailyPostLimit(noCreatedAt, NOW)).isEqualTo(5);
    }

    @Test
    @DisplayName("限额按自然日重置：23 点用满、次日 00:10 恢复（不是滚动 24 小时）")
    void quotaResetsOnCalendarDay() {
        User guy = veteran(6L);
        LocalDateTime lateNight = LocalDateTime.of(2026, 9, 20, 23, 0);
        postNTimesAt(guy, 20, lateNight);
        assertThat(codeOf(() -> service.assertCanPost(guy, lateNight))).isEqualTo(ErrorCode.RATE_LIMITED);

        LocalDateTime nextMorning = LocalDateTime.of(2026, 9, 21, 0, 10);
        assertDoesNotThrow(() -> service.assertCanPost(guy, nextMorning));
        assertThat(service.remainingPosts(guy, nextMorning)).isEqualTo(20);
    }

    private void postNTimesAt(User user, int times, LocalDateTime at) {
        for (int i = 0; i < times; i++) {
            service.assertCanPost(user, at);
            service.recordPostCreated(user, at);
        }
    }

    @Test
    @DisplayName("BR6：禁言只夺「说」的权利，403/10003 且不消耗额度")
    void mutedCanReadButCannotSpeak() {
        User muted = user(7L, "MUTED", NOW.minusDays(30));
        assertThat(codeOf(() -> service.assertCanPost(muted, NOW))).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(codeOf(() -> service.assertCanComment(muted, 99L, NOW))).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(codeOf(() -> service.assertStatusAllowsWrite(muted))).isEqualTo(ErrorCode.FORBIDDEN);
        // 前置检查只 peek 不自增：被拦下的请求不该占掉额度
        assertThat(service.remainingPosts(muted, NOW)).isEqualTo(20);
    }

    @Test
    @DisplayName("BR6：封禁/注销/脏状态一律 20003；大小写不敏感")
    void bannedAndDirtyStatusFailClosed() {
        assertThat(codeOf(() -> service.assertCanPost(user(8L, "BANNED", null), NOW)))
                .isEqualTo(ErrorCode.USER_DISABLED);
        assertThat(codeOf(() -> service.assertCanPost(user(9L, "deleted", null), NOW)))
                .isEqualTo(ErrorCode.USER_DISABLED);
        assertThat(codeOf(() -> service.assertCanPost(user(10L, "  Mutated  ", null), NOW)))
                .isEqualTo(ErrorCode.USER_DISABLED);
        assertThat(codeOf(() -> service.assertCanComment(user(11L, "BANNED", null), 1L, NOW)))
                .isEqualTo(ErrorCode.USER_DISABLED);
    }

    @Test
    @DisplayName("状态缺省（null/空串）按 ACTIVE 放行——DDL 里该列 NOT NULL，只有单测会构造出来")
    void missingStatusIsTreatedAsActive() {
        assertDoesNotThrow(() -> service.assertStatusAllowsWrite(user(12L, null, null)));
        assertDoesNotThrow(() -> service.assertStatusAllowsWrite(user(13L, "  ", null)));
        assertDoesNotThrow(() -> service.assertStatusAllowsWrite(user(14L, "active", null)));
    }

    @Test
    @DisplayName("空用户与无 id 用户：分别 404/20001 与 IllegalArgumentException，不掺 500")
    void invalidSubjectsFailWithExplicitCodes() {
        assertThat(codeOf(() -> service.assertStatusAllowsWrite(null))).isEqualTo(ErrorCode.USER_NOT_FOUND);
        User anonymous = new User();
        anonymous.setStatus("ACTIVE");
        assertThatThrownIllegalArgumentException(() -> service.assertCanPost(anonymous, NOW));
    }

    private static void assertThatThrownIllegalArgumentException(Runnable call) {
        assertThrows(IllegalArgumentException.class, call::run);
    }

    @Test
    @DisplayName("BR4：同一用户对同一帖 20 条评论封顶，换一帖不受影响")
    void commentsAreCappedPerPost() {
        User guy = veteran(15L);
        for (int i = 0; i < 20; i++) {
            service.assertCanComment(guy, 1001L, NOW);
            service.recordCommentCreated(guy, 1001L, NOW);
        }
        assertThat(codeOf(() -> service.assertCanComment(guy, 1001L, NOW)))
                .isEqualTo(ErrorCode.COMMENT_TOO_MANY);
        assertDoesNotThrow(() -> service.assertCanComment(guy, 1002L, NOW));
    }

    @Test
    @DisplayName("配额按用户隔离：A 用满不影响 B")
    void quotaIsPerUser() {
        User a = newbie(16L);
        User b = newbie(17L);
        postNTimes(a, 5);
        assertThat(codeOf(() -> service.assertCanPost(a, NOW))).isEqualTo(ErrorCode.RATE_LIMITED);
        assertDoesNotThrow(() -> service.assertCanPost(b, NOW));
    }

    @Test
    @DisplayName("发帖与评论用不同计数键，互不占用")
    void postAndCommentCountersAreIndependent() {
        User guy = veteran(18L);
        postNTimes(guy, 20);
        assertThat(codeOf(() -> service.assertCanPost(guy, NOW))).isEqualTo(ErrorCode.RATE_LIMITED);
        // 发帖刷满不影响评论：两条计数键不同（quota:post:uid:day 与 quota:comment:uid:postId:day）
        assertDoesNotThrow(() -> service.assertCanComment(guy, 2001L, NOW));

        User girl = veteran(19L);
        for (int i = 0; i < 20; i++) {
            service.assertCanComment(girl, 5001L, NOW);
            service.recordCommentCreated(girl, 5001L, NOW);
        }
        assertThat(codeOf(() -> service.assertCanComment(girl, 5001L, NOW)))
                .isEqualTo(ErrorCode.COMMENT_TOO_MANY);
        assertDoesNotThrow(() -> service.assertCanPost(girl, NOW));
    }

    @Test
    @DisplayName("配置可覆盖：改 quota 阈值立刻生效，说明没有魔法数字")
    void limitsComeFromConfiguration() {
        properties.getQuota().setNewbieDailyPosts(2);
        properties.getQuota().setDailyPosts(3);
        properties.getQuota().setDailyCommentsPerPost(1);
        properties.getQuota().setNewbieWindowHours(0);

        User guy = newbie(19L);
        // 保护期设为 0 小时后所有人都算老用户，限额应为 3
        assertThat(service.dailyPostLimit(guy, NOW)).isEqualTo(3);
        postNTimes(guy, 3);
        assertThat(codeOf(() -> service.assertCanPost(guy, NOW))).isEqualTo(ErrorCode.RATE_LIMITED);
        service.assertCanComment(guy, 4001L, NOW);
        service.recordCommentCreated(guy, 4001L, NOW);
        assertThat(codeOf(() -> service.assertCanComment(guy, 4001L, NOW)))
                .isEqualTo(ErrorCode.COMMENT_TOO_MANY);
    }
}

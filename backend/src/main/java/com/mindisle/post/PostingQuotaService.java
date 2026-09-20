package com.mindisle.post;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Service;

import com.mindisle.cache.CacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.User;

/**
 * 发帖/评论频率与禁言语义（任务 3.12 · 需求 BR4、BR5、BR6 · 手册 §6.1 行 3.12）。
 *
 * <p><b>BR6 的三条口径最容易做错，这里逐条钉住</b>：</p>
 * <ul>
 *   <li>MUTED（禁言）= <b>只夺「说」的权利</b>：发帖、评论、私信全部 403；
 *       浏览、点赞、收藏照常（被禁言的人连「看别人怎么说」都不许，属于额外惩罚，需求没写就不做）；</li>
 *   <li>BANNED / DELETED（封禁、注销）= 全不可，返回 20003 而不是 10003，
 *       前端据此跳「账号已被停用」页而不是弹一句「稍后再试」；</li>
 *   <li>状态字符串不认识（脏数据）时按封禁处理，宁可误拦一次让管理员复核，不可放过一次灌水；
 *       只有 {@code null} 与空串按 ACTIVE 放行——{@code user.status} 在 DDL 里是
 *       {@code NOT NULL DEFAULT 'active'}，出现空值只可能是单测手工构造的对象，
 *       真实库里不存在「状态为空」这条数据。</li>
 * </ul>
 *
 * <p><b>BR5 的「24 小时」和「每天」是两件事</b>：新注册 24 小时内属于新手期，
 * 限额取 newbieDailyPosts（5）；无论新老，限额都按<b>自然日</b>重置（不是滚动 24 小时窗口），
 * 因为「每天 ≤20 帖」的字面含义就是自然日，滚动窗口会让人半夜连发两帖跨天计数。</p>
 *
 * <p><b>计数落在缓存，因此有两个必须说清的边界</b>：</p>
 * <ol>
 *   <li>进程重启（或 redis 被清）当日计数归零。这是缓存层限流的固有代价，
 *       等 T3.3 把发帖接上真实库后，发帖事务内会用 {@code SELECT COUNT(*)} 校准一次；
 *       在那之前本类是唯一的计数器，别把它当审计数据用。</li>
 *   <li>{@link #assertCanPost} 与 {@link #recordPostCreated} 之间不是原子的，
 *       同一用户并发提交理论上能多过 1 帖。防灌水是粗粒度闸门，不为这 1 帖引入分布式锁。</li>
 * </ol>
 */
@Service
public class PostingQuotaService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String SCENE_POST = "post";
    private static final String SCENE_COMMENT = "comment";
    /** 跨日瞬间 ttl 可能算出 0，兜一个最小窗口，免得写出永不过期的键。 */
    private static final Duration MIN_TTL = Duration.ofMinutes(1);

    private final CacheService cacheService;
    private final MindisleProperties properties;

    public PostingQuotaService(CacheService cacheService, MindisleProperties properties) {
        this.cacheService = cacheService;
        this.properties = properties;
    }

    /**
     * 发帖前置检查（BR5 + BR6）。不通过直接抛业务异常，通过则什么都不做——
     * 真正消耗额度要等落库成功后调 {@link #recordPostCreated}。
     */
    public void assertCanPost(User user, LocalDateTime now) {
        assertStatusAllowsWrite(user);
        MindisleProperties.Quota quota = properties.getQuota();
        int limit = dailyPostLimit(user, now);
        int used = usedCount(postKey(user.getId(), now));
        if (used >= limit) {
            throw new BizException(ErrorCode.RATE_LIMITED,
                    String.format("今日已发 %d 帖，达到上限 %d 条%s，明天再来吧", used, limit,
                            isNewbie(user, now) ? "（新注册 24 小时内限 " + quota.getNewbieDailyPosts() + " 帖）" : ""));
        }
    }

    /** 发帖成功后计数 +1。 */
    public void recordPostCreated(User user, LocalDateTime now) {
        cacheService.incr(postKey(user.getId(), now), windowTtl(now));
    }

    /** 评论前置检查（BR4 的「单用户单帖 ≤20 条/天」 + BR6）。 */
    public void assertCanComment(User user, long postId, LocalDateTime now) {
        assertStatusAllowsWrite(user);
        int limit = properties.getQuota().getDailyCommentsPerPost();
        int used = usedCount(commentKey(user.getId(), postId, now));
        if (used >= limit) {
            throw new BizException(ErrorCode.COMMENT_TOO_MANY,
                    String.format("你今天在这条内容下已经评论 %d 次，先歇一会儿", used));
        }
    }

    /** 评论成功后计数 +1。 */
    public void recordCommentCreated(User user, long postId, LocalDateTime now) {
        cacheService.incr(commentKey(user.getId(), postId, now), windowTtl(now));
    }

    /**
     * BR6 判定：当前账号还能不能「说」。
     *
     * <p>公开是因为私信（阶段 5）、举报（T3.11）要走同一套口径，而不是各写一遍 switch。</p>
     */
    public void assertStatusAllowsWrite(User user) {
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        String status = user.getStatus() == null ? "" : user.getStatus().trim().toUpperCase();
        if (status.isEmpty() || "ACTIVE".equals(status)) {
            return;
        }
        if ("MUTED".equals(status)) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号处于禁言期，可以看和点赞，暂时不能发布内容");
        }
        // BANNED、DELETED 以及任何看不懂的状态一律按停用处理（失败关闭）。
        throw new BizException(ErrorCode.USER_DISABLED);
    }

    /** 今日发帖上限：新手期 5，常规 20（BR5）。 */
    public int dailyPostLimit(User user, LocalDateTime now) {
        return isNewbie(user, now)
                ? properties.getQuota().getNewbieDailyPosts()
                : properties.getQuota().getDailyPosts();
    }

    /** 是否仍在新注册保护期内；注册时间缺失按新手处理（宁可多限一天，不可放过刷号）。 */
    public boolean isNewbie(User user, LocalDateTime now) {
        LocalDateTime registeredAt = user == null ? null : user.getCreatedAt();
        if (registeredAt == null) {
            return true;
        }
        int hours = properties.getQuota().getNewbieWindowHours();
        return registeredAt.isAfter(now.minusHours(hours));
    }

    /** 剩余可发条数，U5 发布页的「今日还可发 N 帖」提示用它。 */
    public int remainingPosts(User user, LocalDateTime now) {
        return Math.max(0, dailyPostLimit(user, now) - usedCount(postKey(user.getId(), now)));
    }

    private int usedCount(String key) {
        Long stored = cacheService.get(key, Long.class);
        return stored == null ? 0 : stored.intValue();
    }

    private String postKey(Long userId, LocalDateTime now) {
        return key(SCENE_POST, userId, 0L, now);
    }

    private String commentKey(Long userId, long postId, LocalDateTime now) {
        return key(SCENE_COMMENT, userId, postId, now);
    }

    private static String key(String scene, Long userId, long postId, LocalDateTime now) {
        if (userId == null) {
            throw new IllegalArgumentException("计数需要真实用户 id");
        }
        return "quota:" + scene + ":" + userId + (postId > 0L ? ":" + postId : "") + ":" + date(now);
    }

    private static String date(LocalDateTime now) {
        return localDate(now).format(DAY);
    }

    /** 窗口到「当日 24:00」为止，所以限额是自然日口径。 */
    private static Duration windowTtl(LocalDateTime now) {
        LocalDateTime midnight = localDate(now).plusDays(1).atStartOfDay();
        Duration ttl = Duration.between(now, midnight);
        return ttl.compareTo(MIN_TTL) < 0 ? MIN_TTL : ttl;
    }

    /**
     * 统一取日期：入参 {@code now} 由调用方给出（Controller 传 {@code LocalDateTime.now()}，
     * 单测传固定时刻），本类内部绝不偷偷调系统时钟，否则「跨天重置」这条规则永远测不了。
     */
    private static LocalDate localDate(LocalDateTime now) {
        return (now == null ? LocalDateTime.now() : now).toLocalDate();
    }
}

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
 *   <li>进程重启（或 redis 被清）当日计数归零。<b>2026-09-20 任务 3.3 落地时评估过
 *       「发帖事务内补一次 {@code SELECT COUNT(*)} 校准」，最终没有做</b>：
 *       ① 这条规则是防灌水的粗粒度闸门，不是账务，少算几帖的代价由 BR5 的次日重置自然兜住；
 *       ② 校准要按「自然日 + 含 REJECTED/已删除」的口径写 SQL 才和缓存数对得上，
 *          口径写错反而会让正常用户被误限，比归零更糟；
 *       ③ 每次发帖多一次范围扫描，代价落在最热的写路径上。
 *       <b>所以本类是唯一的计数器，别把它当审计数据用</b>；真要收紧，先补索引与单测再动。</li>
 *   <li>{@link #assertCanPost} 与 {@link #recordPostCreated} 之间不是原子的，
 *       同一用户并发提交理论上能多过 1 帖。防灌水是粗粒度闸门，不为这 1 帖引入分布式锁。</li>
 * </ol>
 */
@Service
public class PostingQuotaService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String SCENE_POST = "post";
    private static final String SCENE_COMMENT = "comment";
    /**
     * 场景 3：创建话题（任务 3.8 · 手册 §6.1 行 3.8「创建需 audit_status=待审（防刷）」）。
     *
     * <p>它和前两个场景共用同一个键形状与同一个自然日窗口，所以「防刷」这一条
     * 在跨日重置、进程重启归零这两件事上的行为与发帖完全一致 —— 不额外解释一遍，
     * 也不额外多一个坑。</p>
     */
    private static final String SCENE_TOPIC = "topic";
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
     * 创建话题前置检查（手册 §6.1 行 3.8「防刷」+ BR6）。
     *
     * <p>走 {@link #assertStatusAllowsWrite} 而不是 allowsInteract：话题名和简介是<b>会公开出现在
     * 广场帖子上方的标签</b>的内容，本质是「说」，不是「点」。所以 MUTED 账号可以关注话题、
     * 不能建话题（需求 BR6 的分工在这里一次都不用重新解释）。</p>
     *
     * <p>限额读 {@code mindisle.topic.max-create-per-day}，不读发帖那个 20：
     * 一天 20 帖正常，一天 20 个新话题就是灌水。</p>
     */
    public void assertCanCreateTopic(User user, LocalDateTime now) {
        assertStatusAllowsWrite(user);
        int limit = properties.getTopic().getMaxCreatePerDay();
        int used = usedCount(topicKey(user.getId(), now));
        if (used >= limit) {
            throw new BizException(ErrorCode.RATE_LIMITED,
                    String.format("今天已经创建了 %d 个话题，达到上限 %d 个，先逛逛已有的话题吧", used, limit));
        }
    }

    /** 话题创建成功后计数 +1。落库失败（重名撞唯一键）时不会走到这里。 */
    public void recordTopicCreated(User user, LocalDateTime now) {
        cacheService.incr(topicKey(user.getId(), now), windowTtl(now));
    }

    /**
     * BR6 判定（下半句）：当前账号还能不能「看和点」——浏览、点赞、收藏、关注。
     *
     * <p>这是全站<b>唯一</b>的互动资格判据。MUTED 在这里是<b>放行</b>的：禁言夺的是「说」的权利，
     * 把点赞收藏一起收走属于需求没写的额外惩罚（类注释第 1 条）。BANNED / DELETED
     * 以及任何看不懂的状态照旧失败关闭。</p>
     */
    public void assertStatusAllowsInteract(User user) {
        String status = statusOf(user);
        if (status.isEmpty() || "ACTIVE".equals(status) || "MUTED".equals(status)) {
            return;
        }
        throw new BizException(ErrorCode.USER_DISABLED);
    }

    /**
     * BR6 判定：当前账号还能不能「说」。
     *
     * <p>公开是因为私信（阶段 5）、举报（T3.11）要走同一套口径，而不是各写一遍 switch。</p>
     *
     * <p><b>实现上先过互动判据、再单独处理 MUTED</b>：这样「能说的必定能点」是结构保证，
     * 而不是两份 switch 抄得恰好一样的巧合。判据如果各写一份，将来给互动新增一档限制
     * （比如「被举报冻结期间禁止收藏」）时，写路径一定漏改——那种 bug 单测发现不了，
     * 因为两边各自都能自证一致。</p>
     */
    public void assertStatusAllowsWrite(User user) {
        assertStatusAllowsInteract(user);
        if (muteActiveNow(user, LocalDateTime.now())) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号处于禁言期，可以看和点赞，暂时不能发布内容");
        }
    }

    /**
     * 禁言是否仍在生效（任务 T6.4 · 需求 FR8.3 的自愈分支）。
     *
     * <p><b>为什么读侧还要再判一次 {@code mute_until}</b>：{@code MuteExpiryJob} 只是把库里的
     * 事实追平，它每分钟跑一次，而禁言到期的那一秒用户就可能正在点「发布」。
     * 只信 {@code user.status} 会让到期后的第一分钟成为「管理员没解锁我就永远发不了」的窗口；
     * 判据落在读侧，作业迟到也不影响用户，作业本身只是让库里的事实与判据一致。</p>
     *
     * <p>{@code mute_until} 为空＝无限期禁言（管理员手动处置且未给期限），<b>不</b>自愈：
     * 自动作业无权替人推定一次处分什么时候结束。</p>
     */
    static boolean muteActiveNow(User user, LocalDateTime now) {
        if (!"MUTED".equals(statusOf(user))) {
            return false;
        }
        LocalDateTime until = user.getMuteUntil();
        return until == null || until.isAfter(now);
    }

    /**
     * 状态归一化。账号为空即「查无此人」（USER_NOT_FOUND）——两个判据都要在第一时间挡住它，
     * 否则后面拿 user.getId() 会直接 NPE 变成 500。
     */
    private static String statusOf(User user) {
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return user.getStatus() == null ? "" : user.getStatus().trim().toUpperCase();
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

    /** 话题配额按「人 + 自然日」算，没有目标维度（话题还不存在，没有 id 可拼）。 */
    private String topicKey(Long userId, LocalDateTime now) {
        return key(SCENE_TOPIC, userId, 0L, now);
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

package com.mindisle.topic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

import com.mindisle.audit.SensitiveWordEngine;
import com.mindisle.audit.SensitiveWordEngine.CheckResult;
import com.mindisle.auth.AuthService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.Topic;
import com.mindisle.entity.User;
import com.mindisle.mapper.TopicFollowMapper;
import com.mindisle.mapper.TopicMapper;
import com.mindisle.post.PostingQuotaService;
import com.mindisle.topic.dto.TopicCard;
import com.mindisle.topic.dto.TopicCreateRequest;
import com.mindisle.topic.dto.TopicCreateView;
import com.mindisle.topic.dto.TopicFollowView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 话题创建与关注（任务 3.8 · 需求 FR1.7、FR4.5、FR8.6 · 手册 §6.1 行 3.8、§6.2 U6）。
 *
 * <p><b>需求原文就两句，本类逐句对齐</b>：FR4.5「话题（如 #期末破防瞬间#）：<b>创建需管理员审核
 * （防刷屏）</b>，聚合页展示话题下热帖与参与人数」；FR8.6 把「<b>话题预审开关</b>」列进系统配置。
 * 前者落成 {@link #create} 里那条「预审开则一律 PENDING」，后者落成
 * {@code mindisle.topic.require-pre-review} 这一个配置项 —— 它是需求里唯一一处
 * 「产品行为本身可以按环境改」的要求，所以它必须是配置而不是常量。</p>
 *
 * <p>🔴 <b>本任务最要紧的一条诚实边界（答辩必须主动说，不能等人问）</b>：
 * {@code audit_task.target_type} 的 ENUM 是
 * {@code post / comment / pm / hole / ai_reply / image}，<b>没有 topic 这一档</b>
 * （{@code sql/04_community.sql} 表 20）。也就是说今天<b>没有任何一条通道</b>能把一个
 * PENDING 话题放行成 APPROVED —— 那条通道叫管理端，属任务 6.1。所以默认配置
 * （预审开）下，用户新建话题的真实结局是：创建成功、拿到 id、然后详情页回 409/30004。
 * 这不是 bug，而是需求写的流程缺了后半段。本类为此做了三件事：
 * ① {@link #requireReadable} 把「待审」与「不存在」严格分开回话（409 而不是 404，
 *    手册 L803 的口径：用户此刻能做的是等，不是改）；
 * ② 创建回执带一句 {@code tip} 讲清「还在审核、通过后这里就有内容」；
 * ③ <b>不</b>给 PENDING 话题伪造 {@code audit_task} 工单 —— 那会让管理端上线时以为
 *    工单池里有真活干，而它连 target_type 都不认识这个值。
 * 因此任务 3.8 在手册 §15 只能标 ◐，冒烟与 DOM 探针的取证一律用种子里那 20 个
 * 已过审的官方话题，不用新建的。</p>
 *
 * <p><b>为什么机审仍然要跑，哪怕预审开着、结果不改判</b>：一是命中 BLOCK 时直接拒掉不落库
 * （省掉一条永远不会有第二个人看、却要永久占住 {@code uk_name} 与一个每日配额的垃圾行）；
 * 二是词库结论要进日志 —— T6.1 的管理员在放行前需要看到「这个话题名词库判了什么」，
 * 那份信息只能在建的时候采，事后重建就得再拼一遍归一化逻辑（第二份真相）。</p>
 */
@Service
public class TopicService {

    private static final Logger log = LoggerFactory.getLogger(TopicService.class);

    /** 与 topic.audit_status 的 ENUM 逐字一致（另一份定义在 DDL 与 {@code PostService#resolveTopics}）。 */
    static final String AUDIT_PENDING = "PENDING";
    static final String AUDIT_APPROVED = "APPROVED";
    static final String AUDIT_REJECTED = "REJECTED";

    /** 敏感词处置档位，与 sensitive_word.action 的 ENUM 逐字一致。 */
    static final String ACTION_BLOCK = "BLOCK";
    static final String ACTION_REVIEW = "REVIEW";

    /** 关注动作白名单，取值与 {@code user.dto.FollowRequest} 完全相同（同一种关系不配两套词）。 */
    static final Set<String> FOLLOW_ACTIONS = Set.of("follow", "unfollow");

    private final TopicMapper topicMapper;
    private final TopicFollowMapper topicFollowMapper;
    private final AuthService authService;
    private final PostingQuotaService quotaService;
    private final SensitiveWordEngine engine;
    private final MindisleProperties properties;

    public TopicService(TopicMapper topicMapper,
                        TopicFollowMapper topicFollowMapper,
                        AuthService authService,
                        PostingQuotaService quotaService,
                        SensitiveWordEngine engine,
                        MindisleProperties properties) {
        this.topicMapper = topicMapper;
        this.topicFollowMapper = topicFollowMapper;
        this.authService = authService;
        this.quotaService = quotaService;
        this.engine = engine;
        this.properties = properties;
    }

    /**
     * 创建一个话题（需求 FR1.7「用户可创建兴趣话题」+ FR4.5「创建需管理员审核」）。
     *
     * <p><b>判定顺序与发帖同构，且顺序本身就是设计</b>：账号资格与配额 → 字段合规 → 机审 →
     * 重名 → 落库 → 记配额。配额放在最前是沿用 {@code PostService} 那条「最便宜的判断先做」；
     * 机审放在重名之前，是为了让「一个已经存在的名字」不该白烧一次 DFA 扫描 ——
     * 顺序反过来时，脚本号反复拿同一个黑词话题名重试，每次都要先付一次全量匹配。</p>
     *
     * <p><b>不自动让创建者关注自己的话题</b>。看着像贴心（「你建的自然在你这儿」），实际是
     * 让 {@code follow_cnt} 从一开始就说谎：需求要这个数是「有多少人在跟这个话题」，
     * 而创建者随时能撤掉自己建的话题，他那一票不是参与。想跟就自己点关注，一个按钮的事，
     * 换来的是这一列在任何时刻都恒等于真相表的行数（手册 §18 Gate3 的计数口径）。</p>
     *
     * <p><b>不建危机工单</b>。话题名里出现「抑郁」「焦虑」这类 risk 词完全正常 ——
     * 「和抑郁和解」正是一个该被鼓励的圈子。危机识别的对象是<b>某个具体的人在某一时刻的
     * 求助表达</b>，一个圈子名字不是；{@code alert_ticket.source_type} 也没有 topic 这一档。
     * 需求 §18.3 那条「命中必须给求助卡片」在帖子与对话上成立，不在话题上成立。</p>
     *
     * @param userId 创建者。Controller 已保证 current 非空，这里再查一次账号真相（BR6 状态判据要读 user 行）
     * @param req    话题名与简介
     * @param now    当日配额的自然日基准，由调用方传入以便单测固定时钟
     * @return 创建回执，含「现在能不能用」这个前端唯一需要的布尔
     */
    @Transactional
    public TopicCreateView create(long userId, TopicCreateRequest req, LocalDateTime now) {
        User user = authService.requireUser(userId);
        quotaService.assertCanCreateTopic(user, now);

        String name = normalizeName(req == null ? null : req.name());
        String desc = normalizeDesc(req == null ? null : req.desc());

        // 名与简介合成一份文本送检：分开两次调用的话，「名字干净、简介塞黑词」的话题
        // 会只凭简介那次命中被拒，而两次调用给出的主因还可能不同。判据要的是
        // 「这批命中里有没有 BLOCK」（见 CheckResult#hasAction），一次扫描就够。
        CheckResult result = engine.check(name + "\n" + desc, "user");
        if (result.hasAction(ACTION_BLOCK)) {
            // 不落库：黑词话题一旦占住 uk_name 就永久不可用（软删也占着），
            // 拦下的是一条违规内容，不该顺手惩罚后来所有想建这个话题的人。
            log.info("话题创建被机审拦截 userId={} name={} category={} action={}",
                    userId, name, result.category(), result.action());
            throw new BizException(ErrorCode.CONTENT_REJECTED,
                    "这个话题的名字或简介里有平台不允许发布的内容，换个说法再试");
        }

        boolean requirePreReview = properties.getTopic().isRequirePreReview();
        String auditStatus = resolveAuditStatus(requirePreReview, result);

        if (topicMapper.findByName(name) != null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "这个话题已经有人建过了，直接去参与它吧");
        }

        Topic topic = new Topic();
        topic.setName(name);
        topic.setDescTxt(desc);
        topic.setAuditStatus(auditStatus);
        // isOfficial 恒 0：官方标识是运营手工盖的角标，用户建不出「官方话题」。
        // 种子里那 20 个官方话题是 SQL 造的（sql/09_seed.sql），不是这条路的产物。
        topic.setIsOfficial(0);
        topic.setPostCnt(0);
        topic.setFollowCnt(0);
        // 热度写 0 而不是留 null：hot_score 是 DECIMAL(10,4) NOT NULL DEFAULT 0，
        // 而话题墙按它倒序排；留 null 会让这一行在 ORDER BY 里落到不确定的位置。
        topic.setHotScore(BigDecimal.ZERO);
        topic.setDeleted(0);
        // cover 留 null：U6 的「头图」今天没有上传通道，前端按占位渐变渲染（见 TopicCard 注释）。
        try {
            topicMapper.insert(topic);
        } catch (DuplicateKeyException e) {
            // 上面那次 findByName 挡不住并发：两个请求同时查、都没查到、同时插，后一个撞 1062。
            // 也挡不住「同名话题已被逻辑删除」——那种行 selectById 查不到，而 findByName
            // 的裸 SQL 查得到，所以走到这里的基本是并发。两种情形给同一句话，
            // 因为用户能做的事完全一样：换个名字，或者去参与已有的那个。
            log.info("话题重名并发冲突 userId={} name={} : {}", userId, name, e.getMessage());
            throw new BizException(ErrorCode.PARAM_INVALID, "这个话题已经有人建过了，直接去参与它吧");
        }

        quotaService.recordTopicCreated(user, now);
        boolean usable = AUDIT_APPROVED.equals(auditStatus);
        String tip = usable ? "话题已创建，现在就能进去发帖了" : "话题已提交，通过审核后就能进去发帖了，通常很快";
        log.info("话题创建成功 id={} userId={} auditStatus={} requirePreReview={} dictHit={}",
                topic.getId(), userId, auditStatus, requirePreReview, result.hit());
        return new TopicCreateView(topic.getId(), name, desc, auditStatus, usable, tip, 0, 0);
    }

    /**
     * 审核状态判据（FR8.6 那个开关在这里生效）。
     *
     * <p><b>开关打开（默认）时结果与机审无关</b>，这是照抄 FR4.5 的字面：「创建<b>需管理员审核</b>」。
     * 此时机审唯一还改变结果的地方是上层的 BLOCK 拦截 —— 那是「违规内容不给落库」，
     * 不是「审核结论」，两者别混为一谈。关掉开关才走机审直通：REVIEW 转待审、干净直接过审。</p>
     *
     * <p>单独抽成静态方法，是为了让「开关 x 机审结论 -> 状态」这张表能被单测逐格钉住：
     * 它要是长在 create 的流程里，每改一格都得重新拼一遍配额、重名、INSERT 的桩。</p>
     */
    static String resolveAuditStatus(boolean requirePreReview, CheckResult result) {
        if (requirePreReview) {
            return AUDIT_PENDING;
        }
        return result != null && result.hasAction(ACTION_REVIEW) ? AUDIT_PENDING : AUDIT_APPROVED;
    }

    /**
     * 关注 / 取关一个话题（手册 §6.1 行 3.8「关注话题」）。
     *
     * <p><b>MUTED 账号可以关注</b>：走 {@link PostingQuotaService#assertStatusAllowsInteract}
     * 而不是 allowsWrite。禁言夺的是「说」的权利，关注一个圈子是「点」，
     * 需求 BR6 没写要连这个一起收（口径与帖子点赞、收藏逐字相同，不另立判据）。</p>
     *
     * <p><b>先过 {@link #requireReadable}</b>：一个还在待审的话题不该能被关注 ——
     * 关注数会攒出一群「等人来」的零，而它连详情页都进不去。驳回与不存在同样挡住。</p>
     *
     * <p><b>幂等且回 changed</b>：重复点「关注」第二次什么都不写，但仍回 200 与当前状态，
     * 让前端的乐观更新有回执可覆盖（与 T3.6 的点赞、T3.11-a 的人关注同一套契约）。
     * 不发通知：{@code notify_message} 的模板表里没有话题这一档，
     * 「有人关注了你的话题」要等 T3.16 一起设计。</p>
     *
     * @param actorId   操作者
     * @param topicId   话题 id
     * @param requested follow / unfollow；大小写与首尾空白都收，白名单外 10001
     */
    @Transactional
    public TopicFollowView follow(long actorId, long topicId, String requested) {
        String action = normalizeAction(requested);
        User actor = authService.requireUser(actorId);
        quotaService.assertStatusAllowsInteract(actor);
        Topic topic = requireReadable(topicMapper.selectById(topicId));

        boolean forward = "follow".equals(action);
        boolean already = topicFollowMapper.isFollowing(actorId, topicId) > 0L;
        boolean changed;
        if (forward) {
            // 已关注就不再发第二条 INSERT：IGNORE 本来也不写，但省一次往返，
            // 更重要的是让 changed 的语义是「这次真的改了这个人的状态」，
            // 而不是「SQL 执行成功了」—— 前端按钮的提示文案读的是前者。
            changed = !already && topicFollowMapper.insertIgnore(actorId, topicId) > 0;
        } else {
            changed = topicFollowMapper.deletePair(actorId, topicId) > 0;
        }

        // 关注态重新查一次真相表，而不是拿上面的 already 取反：同一事务里结果当然一样，
        // 但「回给用户的状态来自一次读」这句话就只在一条路径上成立，
        // 打桩与真库的差别不会再有机会把这两者错开。
        boolean following = topicFollowMapper.isFollowing(actorId, topicId) > 0L;
        Topic latest = topic;
        if (changed) {
            topicFollowMapper.refreshFollowCnt(topicId);
            latest = topicMapper.selectById(topicId);
        }
        return new TopicFollowView(topicId, action, changed, following,
                latest == null ? topic.getFollowCnt() : latest.getFollowCnt());
    }

    /**
     * 话题详情页的资料头（手册 §6.2 U6「头图、参与数、发帖入口」里前两项的数据来源）。
     *
     * <p>只回资料，帖流走 {@code PostQueryService#topicPosts}：两件事的翻页口径不同 ——
     * 一个是单对象、一个是游标列表，硬塞进一个响应体就得让首屏为一屏帖子付组装成本，
     * 而 U6 的头图区域要能在帖流加载完之前先画出来。</p>
     */
    public TopicCard detail(long viewerId, long topicId) {
        Topic topic = requireReadable(topicMapper.selectById(topicId));
        boolean following = topicFollowMapper.isFollowing(viewerId, topicId) > 0L;
        return TopicCard.of(topic, following);
    }

    /**
     * 全站<b>唯一</b>的「这个话题现在能不能被读」判据。详情页、关注、挂帖、话题页帖流都走它。
     *
     * <p>手册 L803 那条口径的实现处：「话题未过审给 409/30004 而不是 400：用户此刻能做的是等，
     * 不是改」。而驳回与不存在统一 404/90006，不给「存在但被拒」留枚举通道 ——
     * 与帖子那边「不可见一律 30001/404、不区分二者」是同一条教义。</p>
     *
     * <p><b>public 静态而不是私有实例方法</b>：{@code PostService#resolveTopics} 与
     * {@code PostQueryService#topicPosts} 都要判同一件事，抄一份就迟早判歪。
     * 先例见 {@code PostService.displayNameOf} 被 {@code RelationshipService} 调用 ——
     * 静态方法跨包调用不产生 Bean 依赖，不会造出 Spring 的循环依赖。</p>
     */
    public static Topic requireReadable(Topic topic) {
        if (topic == null || AUDIT_REJECTED.equals(topic.getAuditStatus())) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!AUDIT_APPROVED.equals(topic.getAuditStatus())) {
            throw new BizException(ErrorCode.TOPIC_PENDING,
                    "话题「" + topic.getName() + "」还在审核中，通过后这里就有内容了");
        }
        return topic;
    }

    /**
     * 话题名归一化：去首尾空白 + 把内部连续空白（含全角空格）压成一个半角空格。
     *
     * <p><b>为什么不替用户剥 {@code #}</b>：需求 FR4.5 举的例子是「#期末破防瞬间#」，那个井号是
     * 话题在正文里的引用写法，不是名字的一部分。但服务端替用户删掉它就成了「用户输入 A、
     * 系统存成 B」—— 名字要在标签上原样给用户看，悄悄改过的字面量会让人以为系统吞字。
     * 前端在标签渲染时统一加井号（# + name），存储与展示各管一头。</p>
     *
     * <p><b>重名判据是两层的，别在 Java 里再加 toLowerCase</b>：第一层是这里的空白折叠，
     * 第二层是库的 collation —— {@code topic.name} 在 {@code utf8mb4_0900_ai_ci} 下
     * 大小写与重音都不敏感，所以「#AI#」与「ai」会撞同一条 {@code uk_name}。
     * 如果应用层先 toLowerCase 再判重，就会造出「应用层认为不重名、库判重名」的缝，
     * 那条缝最后只能靠 {@code DuplicateKeyException} 兜。所以这里只折叠空白，大小写交给库一处说。</p>
     *
     * <p>长度按<b>码点</b>数而不是 char 数，与 {@code PostService} 的正文裁剪同一口径：
     * 一个 emoji 占 2 个 char 但只算 1 个字，用 length 判会让「带四个表情的话题名」
     * 莫名其妙地被判超长。DDL 的 VARCHAR(32) 本身也按字符算，两边口径一致。</p>
     */
    String normalizeName(String raw) {
        String value = collapseWhitespace(raw);
        if (value.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "话题名不能为空");
        }
        int max = properties.getTopic().getMaxNameChars();
        int chars = value.codePointCount(0, value.length());
        if (chars > max) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "话题名最长 " + max + " 字，当前 " + chars + " 字，短一点更好记");
        }
        return value;
    }

    /**
     * 简介归一化：可空（DDL 是 {@code NOT NULL DEFAULT ''}，所以空值统一存成空串而不是 null），
     * 同样折叠空白。这里连内部空白也压成单空格，是因为简介在话题页上是单行摘要展示，
     * 用户敲的两个换行在界面上会变成一段突兀的空隙。
     */
    String normalizeDesc(String raw) {
        String value = collapseWhitespace(raw);
        int max = properties.getTopic().getMaxDescChars();
        int chars = value.codePointCount(0, value.length());
        if (chars > max) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "话题简介最长 " + max + " 字，当前 " + chars + " 字，话题页只显示前几行");
        }
        return value;
    }

    /** 动作归一化：与关注某人那套逐字相同（trim + Locale.ROOT 转小写 + 白名单外 10001）。 */
    static String normalizeAction(String requested) {
        String value = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        if (!FOLLOW_ACTIONS.contains(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "action 只能是 follow 或 unfollow");
        }
        return value;
    }

    /**
     * 空白折叠。<b>全角空格（U+3000）必须显式带上</b>：Java 的 {@code \\s} 不含它，
     * 而中文输入法打出来的空格恰恰是 U+3000 —— 不处理的话「考研　加油站」与「考研 加油站」
     * 会成两个话题，而库里 {@code utf8mb4_0900_ai_ci} 也不把这两种空格视为等价，
     * 那时重名判据就真的只剩 Java 这一层了。
     */
    static String collapseWhitespace(String raw) {
        return raw == null ? "" : raw.replaceAll("[\\s\\u3000]+", " ").trim();
    }
}

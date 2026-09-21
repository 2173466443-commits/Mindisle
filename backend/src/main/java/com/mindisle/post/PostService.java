package com.mindisle.post;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.mindisle.audit.SensitiveWordEngine;
import com.mindisle.audit.SensitiveWordEngine.CheckResult;
import com.mindisle.audit.SensitiveWordEngine.Hit;
import com.mindisle.auth.AuthService;
import com.mindisle.cache.CacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.AlertTicket;
import com.mindisle.entity.AnonymousAlias;
import com.mindisle.entity.Post;
import com.mindisle.entity.PostImage;
import com.mindisle.entity.PostStatusLog;
import com.mindisle.entity.PostTopic;
import com.mindisle.entity.Topic;
import com.mindisle.entity.User;
import com.mindisle.mapper.AlertTicketMapper;
import com.mindisle.mapper.PostImageMapper;
import com.mindisle.mapper.PostMapper;
import com.mindisle.mapper.PostStatusLogMapper;
import com.mindisle.mapper.PostTopicMapper;
import com.mindisle.mapper.TopicMapper;
import com.mindisle.post.dto.CreatePostRequest;
import com.mindisle.post.dto.PostView;
import com.mindisle.upload.ImageUploadService;
import com.mindisle.upload.ImageUploadService.StoredImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发帖与状态机（任务 3.3 · 需求 FR4.1、FR4.2、FR7.3 · 手册 §6.1 行 3.3）。
 *
 * <p><b>整条链路的判定顺序（顺序本身就是设计，答辩会问「为什么是这个顺序」）</b>：</p>
 * <ol>
 *   <li>账号状态与当日配额（BR5/BR6）——最便宜的判断放在最前，被禁言的账号不该白消耗后面的读盘与算力；</li>
 *   <li>字段合规（长度、类型、可见性）；</li>
 *   <li>配图一致性（FR4.1 单帖 ≤9 张、共 ≤20MB）；</li>
 *   <li>话题存在且已过审（FR4.5）；</li>
 *   <li>落 {@code DRAFT} 行 → 记 {@code post_status_log}；</li>
 *   <li>DFA 机审 → 决定 {@code PUBLISHED / HUMAN_REVIEW / REJECTED} + {@code L0..L3}；</li>
 *   <li>危机命中则<b>同时</b>建 {@code alert_ticket}；</li>
 *   <li>更新终态 + 第二条流转日志。</li>
 * </ol>
 *
 * <p><b>创新点 3 在本类里的那一句</b>：需求 §18.3 对自伤类词条的处置写得很死——
 * 「<b>放行但触发 L2/L3 + 卡片 + 工单（不删除！删除等于把人推回沉默）</b>」。
 * 所以本类的分支优先级是「危机放行 &gt; 灰词转人审」，
 * 而不是按 DFA 主因的严重度排序走。2026-09-20 用真实 HTTP 打预检接口时，
 * 「既写自伤又留手机号」的文本主因被判成隐私泄露，如果按主因走，
 * 最需要求助卡片的那类人恰好拿不到卡片——这个顺序就是把那次事故钉在代码里。</p>
 *
 * <p><b>黑词例外</b>：涉政涉黄涉诈这类 black/BLOCK 命中仍然拦下内容，
 * 但<b>工单与求助卡片照发</b>——拦的是一条违规内容，不是一个求助的人。</p>
 */
@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);

    /** 状态机 8 态里本任务会写到的 5 个（APPEALING/TAKEDOWN/DELETED 属任务 3.15、6.x）。 */
    static final String STATUS_DRAFT = "DRAFT";
    static final String STATUS_MACHINE_REVIEW = "MACHINE_REVIEW";
    static final String STATUS_HUMAN_REVIEW = "HUMAN_REVIEW";
    static final String STATUS_PUBLISHED = "PUBLISHED";
    static final String STATUS_REJECTED = "REJECTED";

    /** 与 post.type 的 ENUM 逐字一致（FR4.2 三种形式）。 */
    static final String TYPE_NORMAL = "normal";
    static final String TYPE_HOLE = "hole";
    static final String TYPE_HELP = "help";
    static final Set<String> TYPES = Set.of(TYPE_NORMAL, TYPE_HOLE, TYPE_HELP);

    /** 与 post.visibility 的 ENUM 对齐，friends 属 FR4.6「好友可见」，本期不开放。 */
    static final Set<String> VISIBILITIES = Set.of("public", "private");

    /** alert_ticket.trigger_words 列宽 VARCHAR(200)。 */
    private static final int TRIGGER_WORDS_MAX = 200;

    /** post_status_log.reason 列宽 VARCHAR(255)，留 5 个字符余量。 */
    private static final int REASON_MAX = 250;

    /** 遮罩用的字符：与中文同宽，长度 1，替换后偏移量不变（能按原下标把标题与正文再切开）。 */
    private static final char MASK_CHAR = '\uFF0A';

    private static final String CATEGORY_PRIVACY = "隐私泄露";

    private final AuthService authService;
    private final SensitiveWordEngine engine;
    private final CacheService cacheService;
    private final PostingQuotaService quotaService;
    private final AnonymousAliasService aliasService;
    private final ImageUploadService imageUploadService;
    private final MindisleProperties properties;
    private final PostMapper postMapper;
    private final PostImageMapper postImageMapper;
    private final PostTopicMapper postTopicMapper;
    private final PostStatusLogMapper statusLogMapper;
    private final TopicMapper topicMapper;
    private final AlertTicketMapper alertTicketMapper;

    public PostService(AuthService authService,
                       SensitiveWordEngine engine,
                       CacheService cacheService,
                       PostingQuotaService quotaService,
                       AnonymousAliasService aliasService,
                       ImageUploadService imageUploadService,
                       MindisleProperties properties,
                       PostMapper postMapper,
                       PostImageMapper postImageMapper,
                       PostTopicMapper postTopicMapper,
                       PostStatusLogMapper statusLogMapper,
                       TopicMapper topicMapper,
                       AlertTicketMapper alertTicketMapper) {
        this.authService = authService;
        this.engine = engine;
        this.cacheService = cacheService;
        this.quotaService = quotaService;
        this.aliasService = aliasService;
        this.imageUploadService = imageUploadService;
        this.properties = properties;
        this.postMapper = postMapper;
        this.postImageMapper = postImageMapper;
        this.postTopicMapper = postTopicMapper;
        this.statusLogMapper = statusLogMapper;
        this.topicMapper = topicMapper;
        this.alertTicketMapper = alertTicketMapper;
    }

    // ================================================================ 文案常量

    /**
     * 面向用户的提示语。三条硬规矩：
     * ① 不出现任何命中词面（否则等于把词库公开给用户，FR7.1 的拦截会变成填空题答案）；
     * ② 不写「你违规了」这种定性表述，只写「发生了什么 + 接下来怎么办」；
     * ③ 电话号码不写进文案，走 {@code hotline} 字段——号码是可配置项（FR10.3 校中心电话由管理员改），
     *    写死在字符串里迟早和配置不一致。
     */
    private static final String TIP_REJECT = "内容里有不能公开的部分，这条没有发出去；改一改再发也来得及。";
    private static final String TIP_REJECT_CARE =
            "这条内容里有不能公开的部分，已经被拦下；但你在里面写的那句话我们看见了。"
                    + "改一改还能再发，也可以直接打下面的电话，不用先跟任何人解释。";
    private static final String TIP_CARE_PUBLISHED =
            "已经发出去了。你在写这句话的时候可能正很难受——下面的电话 24 小时有人接，"
                    + "不需要说出真实身份，也不用先想好要讲什么。";
    private static final String TIP_HUMAN_REVIEW =
            "这条内容进入了人工审核，通过后会自动公开，不需要你重复发一遍。";
    private static final String TIP_CONTACT_MASKED =
            "为保护你的匿名身份，正文里的联系方式已经用 ＊ 遮住（只有你自己知道原来写的是什么）。";
    private static final String TIP_HELP_TAB =
            "这里是求助分区，求助入口常驻：除了等回帖，也可以直接打下面的电话。";

    /** DFA 命中的处置档位，与 sensitive_word.action 的 ENUM 逐字一致。 */
    private static final String ACTION_BLOCK = "BLOCK";
    private static final String ACTION_REVIEW = "REVIEW";

    // ================================================================ 对外主流程

    /**
     * 发帖：配额与账号状态 → 字段合规 → 配图/话题一致性 → 落 DRAFT → DFA 机审 → 终态 → 危机建单。
     *
     * <p><b>{@code now} 由调用方传入而不是方法内部取系统时间</b>：新手期判定、发布时间、
     * SLA 倒计时三处必须落在同一个时间基准上，各取一次会在跨秒/跨天的边界上给出互相矛盾的结论
     * （配额说还在新手期、SLA 说已经超时）。顺带也让单测能固定时间轴。</p>
     *
     * <p><b>被拦下的帖子仍然留下一行 status=REJECTED 的记录</b>，这不是脏数据：
     * 申诉（FR7.4）、举报阈值、危机工单都要能指回同一条内容；
     * 所以 REJECTED 走「落库 + 打标」，只有用户主动删除与到期销毁才动 {@code deleted}。</p>
     */
    @Transactional
    public PostView publish(long userId, CreatePostRequest req, LocalDateTime now) {
        MindisleProperties.Post cfg = properties.getPost();
        MindisleProperties.Crisis crisis = properties.getCrisis();

        // 1 账号状态 + 当日配额（BR5/BR6）：最便宜的判断放在最前，禁言账号不该白消耗后面的读盘与算力
        User author = authService.requireUser(userId);
        quotaService.assertCanPost(author, now);

        // 2 字段合规
        String type = normalizeType(req.type());
        String title = normalizeNewlines(req.title()).trim();
        String content = normalizeNewlines(req.content());
        if (title.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标题不能只写空格");
        }
        assertNotTooLong("标题", title, cfg.getMaxTitleChars());
        assertNotTooLong("正文", content, cfg.getMaxContentChars());
        boolean anonymous = TYPE_HOLE.equals(type) || Boolean.TRUE.equals(req.anonymous());
        String visibility = normalizeVisibility(req.visibility(), cfg.getDefaultVisibility());
        if (!TYPE_HOLE.equals(type) && req.autoDestroyHours() != null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "到期销毁只对树洞开放，普通帖与求助帖请留空");
        }

        // 3 配图：张数与合计字节数只有在「发帖这一刻」才判得准（FR4.1，上传接口逐张永远拦不住分 10 次传）
        List<StoredImage> images = resolveImages(req.images());
        // 4 话题：必须存在且已过审（FR4.5）
        List<Topic> topics = resolveTopics(req.topicIds(), cfg.getMaxTopics());
        // 马甲提前到插入之前取：alias_id 一次写进 INSERT，省掉「先插入再回填」的第二条 UPDATE
        AnonymousAlias alias = anonymous ? aliasService.resolve(userId, sceneFor(type)) : null;

        // 5 落 DRAFT
        Post post = new Post();
        post.setUserId(userId);
        post.setType(type);
        post.setTitle(title);
        post.setContent(content);
        post.setVisibility(visibility);
        post.setIsAnonymous(anonymous ? 1 : 0);
        post.setAliasId(alias == null ? null : alias.getId());
        post.setStatus(STATUS_DRAFT);
        post.setRiskLevel(CrisisGrader.L0);
        post.setCreatedAt(now);
        if (TYPE_HOLE.equals(type)) {
            post.setFloorNo(postMapper.nextHoleFloor());
            post.setAutoDestroyAt(holeDestroyAt(req.autoDestroyHours(), now, cfg));
        }
        postMapper.insert(post);

        // 6 第一条流转日志：reason 带上词库版本，半年后复核时能知道「当时是哪一版规则判的」
        logStatus(post.getId(), STATUS_DRAFT, STATUS_MACHINE_REVIEW, "system|dict=" + engine.version());

        // 7 机审 → 终态。检测文本是「标题 + 换行 + 正文」，命中偏移量都以这条拼接串为准
        engine.refreshIfStale(cacheService, properties.getAudit().getDictVersionKey());
        String checkText = title + "\n" + content;
        int bodyFrom = title.length() + 1;
        CheckResult result = engine.check(checkText, "user");
        String level = CrisisGrader.levelOf(result);
        MachineDecision decision = decide(result, level, CrisisGrader.needsTicket(level));

        boolean contactMasked = false;
        if (anonymous) {
            String masked = maskNonRisk(checkText, result);
            contactMasked = !masked.equals(checkText);
            post.setTitle(masked.substring(0, title.length()));
            post.setContent(masked.substring(bodyFrom));
        }
        post.setStatus(decision.status());
        post.setRiskLevel(decision.riskLevel());
        if (STATUS_PUBLISHED.equals(decision.status())) {
            post.setPublishedAt(now);
        }
        postMapper.updateById(post);
        logStatus(post.getId(), STATUS_MACHINE_REVIEW, decision.status(), decision.reason());

        // 8 内容处置与危机处置是两条独立的线：内容被拦（REJECTED）也照样建单
        if (decision.care()) {
            // source_type 里 hole 与 post 分开：管理员从工单点进来源走的是两条路——
            // 树洞要经匿名映射，普通帖直接落到作者主页，混成一类就会在错误的入口找人。
            AlertTicket ticket = newTicket(userId, TYPE_HOLE.equals(post.getType()) ? "hole" : "post",
                    post.getId(), checkText, result, decision, crisis, now);
            alertTicketMapper.insert(ticket);
            // 这行日志是「工单没弹出来」这类投诉的唯一现场证据，级别用 warn：发帖量里它是小概率但高价值事件
            log.warn("危机工单已生成 postId={} userId={} level={} sourceType={} slaAt={} evidenceChars={}",
                    post.getId(), userId, ticket.getLevel(), ticket.getSourceType(),
                    ticket.getSlaAt(), ticket.getEvidenceText() == null ? 0 : ticket.getEvidenceText().length());
        }
        insertImages(post.getId(), images);
        linkTopics(post.getId(), topics, decision.status());

        // 9 配额：REJECTED 也消耗——否则可以拿发帖接口无限试探拦截边界，那正是词库最怕的事
        quotaService.recordPostCreated(author, now);

        StringBuilder tip = new StringBuilder();
        appendTip(tip, decision.tip());
        if (contactMasked) {
            appendTip(tip, TIP_CONTACT_MASKED);
        }
        // hotline 非空即「前端必须显示求助卡片」，与预检接口同一契约。
        // 求助帖即使词面没命中也给入口：FR10.3 只强制 L2/L3，这一条是产品判断——
        // 人已经走进求助分区了，不能因为「话说得不够重」就把电话藏起来。
        boolean showCard = decision.care() || TYPE_HELP.equals(type);
        if (showCard && !decision.care()) {
            appendTip(tip, TIP_HELP_TAB);
        }
        String displayName = alias != null ? alias.getAliasName() : displayNameOf(author);
        List<String> topicNames = topics.stream().map(Topic::getName).toList();
        List<PostView.ImageBrief> briefs = images.stream()
                .map(image -> new PostView.ImageBrief(image.url(), image.width(), image.height()))
                .toList();
        return new PostView(post.getId(), post.getStatus(), post.getType(), post.getTitle(), post.getContent(),
                post.getVisibility(), anonymous, displayName, topicNames, briefs,
                showCard ? crisis.getHotline() : null, tip.isEmpty() ? null : tip.toString(),
                post.getPublishedAt(), post.getAutoDestroyAt(), post.getCreatedAt());
    }

    // ================================================================ 机审判定

    /**
     * 机审结论：终态 + 风险等级 + 入库原因（给审核员看） + 人话提示（给用户看） + 是否建单。
     *
     * @param status    post.status 终态
     * @param riskLevel post.risk_level，与 alert_ticket.level 同源，两边不一致工单就没法定级
     * @param reason    写进 post_status_log.reason，可以出现「black/grey」这类内部口径
     * @param tip       写进出参给用户的解释，不含词面
     * @param care      是否生成危机工单
     */
    record MachineDecision(String status, String riskLevel, String reason, String tip, boolean care) {
    }

    /**
     * 分支优先级写死为「拦截 &gt; 危机放行 &gt; 灰词转人审 &gt; 直发」，四条理由：
     * <ol>
     *   <li>black/BLOCK 最前：涉黄涉诈涉赌不会因为「同时像求助」就公开发出去；</li>
     *   <li>但 BLOCK <b>不影响建单</b>——{@code care} 独立算，拦的是内容不是人。
     *       需求 §18.3 对自伤类词条写的是「放行不删除」，黑词是这条规则唯一的例外，
     *       而例外也只例外到「不发出去」，不例外到「不救助」；</li>
     *   <li>危机放行排在转人审之前：L2/L3 一旦先进人审队列，求助卡片就得等到人工点通过那一刻才出现，
     *       而最危险的那几十分钟恰好等不起；</li>
     *   <li>以上都不成立才直发。</li>
     * </ol>
     *
     * <p>包级可见是为了让单测直接打分支矩阵，不必造一整个 Spring 上下文。</p>
     */
    static MachineDecision decide(CheckResult result, String level, boolean care) {
        if (hasAction(result, ACTION_BLOCK)) {
            return new MachineDecision(STATUS_REJECTED, level,
                    care ? "机审命中拦截词(black)，且同时命中危机词:内容拦下、工单照建"
                            : "机审命中拦截词(black)，内容已拦下",
                    care ? TIP_REJECT_CARE : TIP_REJECT, care);
        }
        if (care) {
            return new MachineDecision(STATUS_PUBLISHED, level,
                    "危机命中(" + level + ")按需求 §18.3 放行:删除等于把人推回沉默",
                    TIP_CARE_PUBLISHED, true);
        }
        if (hasAction(result, ACTION_REVIEW)) {
            return new MachineDecision(STATUS_HUMAN_REVIEW, CrisisGrader.L0,
                    "机审命中待复核词(grey)，转人工审核", TIP_HUMAN_REVIEW, false);
        }
        return new MachineDecision(STATUS_PUBLISHED, CrisisGrader.L0, "机审通过:DFA 无拦截与复核命中", null, false);
    }

    /**
     * 命中的处置档位里没有「按主因查表」这条路，必须自己扫一遍。
     *
     * <p>{@code CheckResult.action} 是主因的处置，而主因按严重度排序取第一个——
     * 于是「既写自伤又留手机号」会返回 grey/REVIEW，黑词与危机词都可能被压在后面看不见。
     * 2026-09-20 打真实预检接口时踩到的就是这个坑（dev-log 有记录），所以这里只认 hits。</p>
     */
    static boolean hasAction(CheckResult result, String action) {
        if (result == null || result.hits() == null) {
            return false;
        }
        for (Hit hit : result.hits()) {
            if (action.equals(hit.action())) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ 遮罩与建单

    /**
     * 匿名帖的联系方式遮罩：把<b>非危机</b>命中逐字符换成全角 ＊，危机命中原文保留。
     *
     * <p><b>为什么只遮非 risk</b>：管理员复核要看原句，把「伤害自己」遮成 ＊＊＊＊，
     * 工单证据就变成猜谜；隐私类命中反过来必须遮——匿名身份泄露大多不是靠署名，
     * 而是靠正文里那串 11 位数字（NFR8 数据最小化）。</p>
     *
     * <p><b>为什么长度必须 1:1 不变</b>：调用方要在遮罩之后按 {@code title.length() + 1}
     * 把标题与正文重新切开，字符数一变，切点就落错，用户的正文会串进标题。
     * 所以这里只替换，不删除、不转义、不压缩空白。</p>
     *
     * <p><b>代理对边界</b>：命中区间如果正好把一个 emoji 的高/低代理对劈开，
     * 遮罩后会剩下孤立代理项，Jackson 序列化直接抛错（前端表现为整个接口 500）。
     * 这里把整对一起遮，宁可多遮一个字符。</p>
     */
    static String maskNonRisk(String text, CheckResult result) {
        char[] buf = text.toCharArray();
        for (Hit hit : result.hits()) {
            if ("risk".equals(hit.level())) {
                continue;
            }
            int from = Math.max(0, hit.start());
            int to = Math.min(buf.length, hit.end());
            if (from >= to) {
                continue;
            }
            if (from > 0 && Character.isSurrogatePair(buf[from - 1], buf[from])) {
                from--;
            }
            if (to < buf.length && Character.isHighSurrogate(buf[to - 1]) && Character.isLowSurrogate(buf[to])) {
                to++;
            }
            for (int i = from; i < to; i++) {
                buf[i] = MASK_CHAR;
            }
        }
        return new String(buf);
    }

    /**
     * 组一张危机工单（FR10.5）。SLA 直接落库，不靠管理端「当前时间减一减」现算。
     *
     * <p><b>包级 static 而不是 private 实例方法</b>：评论（任务 3.7）也要建同一种工单，
     * 抄第二份的话，SLA 算法、风险分基准值、触发词截断长度三处只要有一处漂了，
     * 两条通道的工单定级就不一致——而管理员看到的是同一张队列。来源维度改成入参
     * （sourceType / sourceId），而不是继续吃整个 Post 对象：评论建单时手里只有 post_id。</p>
     */
    static AlertTicket newTicket(long userId, String sourceType, long sourceId, String checkText,
                                CheckResult result, MachineDecision decision,
                                MindisleProperties.Crisis crisis, LocalDateTime now) {
        boolean urgent = CrisisGrader.L3.equals(decision.riskLevel());
        AlertTicket ticket = new AlertTicket();
        ticket.setLevel(decision.riskLevel());
        ticket.setUserId(userId);
        ticket.setSourceType(sourceType);
        ticket.setSourceId(sourceId);
        ticket.setEvidenceText(CrisisGrader.evidence(checkText, result, crisis.getEvidenceChars()));
        // 词面通道只有一个布尔「像不像危机」，分数是配置里的定级基准值（不是模型输出的连续概率），
        // 存小数是为了和阶段 4 模型通道的 DECIMAL(4,3) 同构，届时取两路高分即可，字段不用再改。
        double score = urgent ? crisis.getL3Score() : crisis.getL2Score();
        ticket.setRiskScore(BigDecimal.valueOf(score).setScale(3, RoundingMode.HALF_UP));
        ticket.setTriggerWords(CrisisGrader.triggerWords(result, TRIGGER_WORDS_MAX));
        ticket.setStatus("pending");
        ticket.setSlaAt(now.plus(urgent
                ? Duration.ofMinutes(crisis.getL3SlaMinutes())
                : Duration.ofHours(crisis.getL2SlaHours())));
        ticket.setCreatedAt(now);
        return ticket;
    }

    // ================================================================ 入参归一与前置校验

    /** null 与空串按 normal（FR4.2 默认形式）；白名单外的值直接拒，不猜用户想发什么。 */
    static String normalizeType(String raw) {
        String value = raw == null || raw.isBlank() ? TYPE_NORMAL : raw.trim();
        if (!TYPES.contains(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "内容形式只能是普通帖、树洞或求助");
        }
        return value;
    }

    /**
     * 可见性：null/空走配置默认。DDL 里的 friends 属 FR4.6 好友可见，本期不开放——
     * 传进来明确拒绝，而不是静默改成 public：静默改写等于把用户设为「仅自己」的内容公开了。
     */
    static String normalizeVisibility(String raw, String defaultValue) {
        String value = raw == null || raw.isBlank() ? defaultValue : raw.trim();
        if (!VISIBILITIES.contains(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "可见范围暂时只有「公开」和「仅自己」两种");
        }
        return value;
    }

    /**
     * Windows 客户端粘贴进来的 \r\n 一律归一成 \n：机审偏移量、遮罩切点、前端渲染
     * 都以 \n 为唯一换行符，留着 \r 会让「同一段文本在两边算出的字符数不一样」。
     */
    static String normalizeNewlines(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * 字数上限走配置（NFR10 运营可调），所以不能写进 DTO 的 {@code @Size}（注解值必须是编译期常量）。
     *
     * <p>计数用<b>码点</b>而不是 char：一个 emoji 在 UTF-16 里占 2 个 char，
     * 按 char 计数会让带表情的正文凭空少掉一半额度，而 FR4.1 明确支持 emoji。</p>
     *
     * <p>配置值必须 ≤ DDL 列宽（title 是 VARCHAR(100)），调大配置前先复核 schema。</p>
     */
    private static void assertNotTooLong(String field, String value, int maxChars) {
        int length = value.codePointCount(0, value.length());
        if (length > maxChars) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    field + "最多 " + maxChars + " 字（标点和 emoji 都算一个字），现在有 " + length + " 字");
        }
    }

    /** 单帖配图：张数与合计字节数都在这里判（FR4.1 ≤9 张 / 共 ≤20MB），URL 必须能在服务端读回真文件。 */
    private List<StoredImage> resolveImages(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return List.of();
        }
        MindisleProperties.Upload upload = properties.getUpload();
        Set<String> distinct = new LinkedHashSet<>(urls);
        if (distinct.size() > upload.getMaxImagesPerPost()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "一条帖子最多配 " + upload.getMaxImagesPerPost()
                    + " 张图，现在挂了 " + distinct.size() + " 张");
        }
        List<StoredImage> stored = new ArrayList<>(distinct.size());
        long total = 0L;
        for (String url : distinct) {
            // 同一个 URL 传两次只算一张（去重在上面做了），所以这里不会重复读盘
            StoredImage image = imageUploadService.inspect(url);
            total += image.bytes();
            if (total > upload.getMaxTotalBytes()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "配图合计不能超过 "
                        + upload.getMaxTotalBytes() / 1048576 + "MB，当前已选到 " + total / 1048576 + "MB");
            }
            stored.add(image);
        }
        return stored;
    }

    /** 话题必须存在且已过审（FR4.5）。未过审回 30004 而不是 400：用户能做的是等，不是改。 */
    private List<Topic> resolveTopics(List<Long> topicIds, int maxTopics) {
        if (topicIds == null || topicIds.isEmpty()) {
            return List.of();
        }
        Set<Long> distinct = new LinkedHashSet<>(topicIds);
        if (distinct.size() > maxTopics) {
            throw new BizException(ErrorCode.PARAM_INVALID, "一条帖子最多关联 " + maxTopics + " 个话题");
        }
        List<Topic> topics = new ArrayList<>(distinct.size());
        for (Long topicId : distinct) {
            Topic topic = topicId == null ? null : topicMapper.selectById(topicId);
            if (topic == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "这个话题不存在（id=" + topicId + "），请重新选择");
            }
            if (!"APPROVED".equals(topic.getAuditStatus())) {
                throw new BizException(ErrorCode.TOPIC_PENDING,
                        "话题「" + topic.getName() + "」还没通过审核，通过后就能挂了");
            }
            topics.add(topic);
        }
        return topics;
    }

    /**
     * 树洞到期销毁时间（FR4.2 默认 7 天；需求 §5.1 规则 3「作者可选择」）。
     *
     * <p>{@code 0} 是作者主动选「不销毁」，返回 null；不传则用配置默认 168。
     * <b>这里只写列，不做扫描</b>：到期置 DELETED 的定时任务属任务 3.15，
     * 现在把 job 塞进来只会让 T3.3 的验收面变宽，而它在本次验收里一次也跑不到。</p>
     */
    private static LocalDateTime holeDestroyAt(Integer requested, LocalDateTime now, MindisleProperties.Post cfg) {
        if (requested == null) {
            return now.plusHours(cfg.getHoleDefaultDestroyHours());
        }
        if (requested == 0) {
            return null;
        }
        if (!cfg.getHoleDestroyOptions().contains(requested)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "树洞的保留时长只能选 "
                    + cfg.getHoleDestroyOptions() + " 小时，或者选 0 表示不销毁");
        }
        return now.plusHours(requested);
    }

    /** 马甲场景，与 anonymous_alias.scene 的 ENUM 对齐；普通帖用全域马甲 ALL。 */
    static String sceneFor(String type) {
        if (TYPE_HOLE.equals(type)) {
            return "HOLE";
        }
        if (TYPE_HELP.equals(type)) {
            return "HELP";
        }
        return "ALL";
    }

    /** 匿名内容联系方式被遮过时给用户的那句话（任务 3.7 的匿名评论复用同一文案）。 */
    static String tipContactMasked() {
        return TIP_CONTACT_MASKED;
    }

    /**
     * 展示名口径，任务 3.3（发帖回显）与任务 3.5（列表/详情）共用。
     *
     * <p>public 而不是 private：{@code PostQueryService} 要给出与发帖时<b>逐字相同</b>的名字，
     * 任务 3.6 的{@code user.RelationshipService} 主页资料卡要给出与帖子<b>逐字相同</b>的名字。
     * 复制一份实现就会出现「发帖显示昵称、进列表变成登录名、主页又变成第三种」的口径分裂；
     * 也不为此起一个 DisplayNames 工具类——那只是给同一条规则换个住处，跨包可见性是同样的代价。</p>
     */
    public static String displayNameOf(User user) {
        String nickname = user.getNickname();
        return nickname == null || nickname.isBlank() ? user.getUsername() : nickname.trim();
    }

    private void logStatus(long postId, String fromStatus, String toStatus, String reason) {
        PostStatusLog row = new PostStatusLog();
        row.setPostId(postId);
        row.setFromStatus(fromStatus);
        row.setToStatus(toStatus);
        row.setReason(cut(reason, REASON_MAX));
        statusLogMapper.insert(row);
    }

    private void insertImages(long postId, List<StoredImage> images) {
        int sort = 0;
        for (StoredImage image : images) {
            PostImage row = new PostImage();
            row.setPostId(postId);
            row.setUrl(image.url());
            row.setSort(sort++);
            // 宽高一律取服务端读盘真值，客户端连「这张图多大」都没机会谎报（见 ImageUploadService#inspect）
            row.setWidth(image.width());
            row.setHeight(image.height());
            // hash 先留空串：MD5 秒传与重复上传去重属任务 3.15 的编辑链路，V1 不做
            row.setHash("");
            postImageMapper.insert(row);
        }
    }

    private void linkTopics(long postId, List<Topic> topics, String finalStatus) {
        for (Topic topic : topics) {
            PostTopic link = new PostTopic();
            link.setPostId(postId);
            link.setTopicId(topic.getId());
            postTopicMapper.insert(link);
            // 关联照常写（人审通过后不用回头补），但 post_cnt 只在真发布成功时自增：
            // 进人审队列的帖子还没露面，先把话题计数加上去会让「热度很高、点进去没内容」。
            if (STATUS_PUBLISHED.equals(finalStatus)) {
                topicMapper.increasePostCnt(topic.getId());
            }
        }
    }

    private static void appendTip(StringBuilder sb, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(value);
    }

    private static String cut(String value, int maxChars) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}

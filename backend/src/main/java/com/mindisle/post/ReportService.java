package com.mindisle.post;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mindisle.audit.SensitiveWordEngine;
import com.mindisle.audit.SensitiveWordEngine.CheckResult;
import com.mindisle.cache.CacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.AuditTask;
import com.mindisle.entity.ContentReport;
import com.mindisle.entity.Post;
import com.mindisle.entity.User;
import com.mindisle.post.dto.ReportRequest;
import com.mindisle.post.dto.ReportView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容举报（任务 T3.11 · 需求 FR4.7、FR4.4、FR7.3 · 手册 §6.1 行 3.11、§6.5 第 6 条）。
 *
 * <p>一次举报做的事就四件：<b>落一行 content_report → 把 post.report_cnt 按真相重算 →
 * 确保这条内容有一张待审 audit_task（已有则只在风险更高时升优先级）→ 举报人数达阈值就把
 * 帖子转 HUMAN_REVIEW</b>，最后回一句给用户看得懂的话。举报<b>不</b>下架内容，
 * 也不改内容本身——「举报成立」是一个需要人来下的结论（需求 §7.2 把处置权限给了管理员）。</p>
 *
 * <p><b>为什么本类住在 com.mindisle.post 而不是 com.mindisle.audit</b>（方案上一版写的是 audit 包，
 * 开工前推翻）：它必须复用三条<b>包级可见</b>的既有判据——{@link PostQueryService#visibleTo}
 * （全系统唯一一份「这条内容对这个人可见吗」）、{@link PostService} 的状态常量、
 * 以及「不可见一律 404/30001、绝不 403」这条防枚举口径。挪进 audit 包只有两种结局：
 * 把那三样改成 public（等于把内容状态机的访问控制权交出去），或者在举报里抄第二份可见性判据
 * （等于制造第二条判据，这恰恰是需求 FR7.3 最不能容忍的事）。反过来看，audit 包现在的成员
 * （{@link SensitiveWordEngine}、TextNormalizer）是<b>不碰数据库的纯算法</b>，
 * 把写链路塞进去会让算法层反向依赖 post 包，层次只会更差。</p>
 *
 * <p><b>为什么理由为 self-harm 的举报不自动建 alert_ticket</b>：两个理由，第二个更硬。
 * ① {@code alert_ticket.source_type} 的 ENUM 只有 chat/post/hole/pm（DDL 定稿在阶段 2），
 * 加值要改表，而这条链路的收益撑不起一次 schema 变更（已记进手册 §14，与评论工单那条合并处理）。
 * ② 发帖建单的判据是「<b>这段话是他自己写的</b>」，而举报是<b>针对别人内容</b>发起的信号：
 * 自动开一张 30 分钟时限的危机工单，等于把「用举报骚扰同学、顺手消耗干预资源」的成本降到一次点击，
 * 还会挤占真实自伤预警的处理顺序——需求 §18.3 要的恰恰是别让资源被假的挤掉。
 * 所以这里升级的是 {@code audit_task} 的优先级与 SLA：确实涉自伤的内容会带着 L3 + 30 分钟
 * 排在队列最前面，由<b>看完内容之后</b>的管理员决定是否开单（T6.1）。</p>
 *
 * <p><b>为什么重复举报回 200 + duplicated=true，而不是 4xx</b>：与发帖、评论、点赞同一口径
 * （判据见 {@link ReportView} 与 {@link com.mindisle.post.dto.PostView} 的类注释）。
 * 「你已经举报过了」和「这次举报成功了」对前端来说是<b>同一个成功响应的两种数据</b>——
 * 按钮都该变成「已举报」，只是下一句话不同。用 409 会把这条链路写成 try/catch 才能走完的正常流程。</p>
 *
 * <p><b>为什么不复用 {@code POST /posts/{id}/actions}</b>（需求 §9.1 原本把举报并进那个端点，
 * 本任务改口，理由写在这里）：举报自带 reason / 描述 / 证据三段载荷，并且写自己的真相表、
 * 派生处置工单，它不是 {@code post_like} 那种「关系开关态」。并进 /actions 会让
 * {@link com.mindisle.post.dto.PostActionRequest} 变成五个字段的「什么都收一点」请求体，
 * 还要让两套互不相干的白名单（like/unlike/collect/uncollect 与 spam/abuse/...）共用一处校验，
 * 任何一边放宽都会污染另一边。另起 {@code POST /posts/{id}/report} 才是各说各话。</p>
 *
 * <p><b>没有举报日配额</b>：唯一键已经把「同一个人对同一条内容」压在 1 行，剩下的滥用面
 * （一个人一天举报一百条不同内容）由 {@code RateLimitInterceptor} 的 60 次/分钟兜着，
 * 这是本阶段所有写接口的共同边界。要不要单独加一条「举报配额」属产品决策，已写进手册 §14 待办，
 * 不在这里偷偷新增一条没人要求的限制。</p>
 */
@Service
public class ReportService {

    /**
     * 六类举报理由：码 → 中文名（需求 FR4.7、手册 §6.5 第 6 条，与 content_report.reason 的
     * ENUM 逐字一致，改这里必须同时改 DDL 与前端 {@code POST_REPORT_REASONS}）。
     *
     * <p><b>必须是 LinkedHashMap</b>：非法理由的报错文案是 {@code String.join(" / ", keySet())}，
     * 用 {@code Set.of}/{@code Map.of} 会让顺序随进程变化，那句提示和它的单测就会随机漂。</p>
     *
     * <p>FR4.7 原文给的是「攻击辱骂 / 色情低俗 / 违法 / 泄露隐私 / 自伤风险 / 广告」六个词，
     * 这里把「违法」折进 {@code other}（中文标签写作「违法或其他」）：它是兜底桶而不是并列分类，
     * 真按字面拆开会剩下「什么都能往里装」的语义重叠。省下的一格给 {@code spam}（广告引流），
     * 那是社区里数量最大、也最需要独立统计的一类。</p>
     */
    static final Map<String, String> REASONS = reasons();

    /** 触发「更高优先级 + 求助卡片」的那一类，单独抽常量：它是本类里唯一被读两次的理由码。 */
    static final String SELF_HARM = "self-harm";

    /** audit_task.remark 列宽余量内的实际上限（与 DDL VARCHAR(500) 同宽）。 */
    static final int TASK_REMARK_MAX = 500;
    /** post_status_log.reason 上限，与 {@code PostService} 的 REASON_MAX 同宽（两边必须一致，否则日志被截成两套长度）。 */
    static final int LOG_REASON_MAX = 250;
    /** 单条证据地址长度上限（列宽 1000 留给三张图，单条 200 足够放下 /uploads/yyyy/MM/dd/uuid.jpg）。 */
    static final int EVIDENCE_ITEM_MAX = 200;
    /** 逗号拼接之后的总长上限，与 content_report.evidence_urls 的 VARCHAR(1000) 逐字对齐。 */
    static final int EVIDENCE_TOTAL_MAX = 1000;

    /**
     * 证据图片唯一允许的地址前缀——也就是 {@code POST /api/files/image} 回给前端的那串相对路径。
     *
     * <p><b>只收本站上传回来的相对地址</b>：管理员在审核台上会点开这些链接，收外链等于让举报人
     * 决定管理员的浏览器去哪里（钓鱼与恶意文件），也等于给 {@code data:}/{@code javascript:}
     * 留位置。前端拿到的是相对路径，渲染时自己拼 base，所以这条限制对用户是零成本。</p>
     */
    static final String EVIDENCE_PREFIX = "/uploads/";

    /**
     * 存储端口（与 {@link CommentService.CommentStore}、{@link PostInteractionService.InteractionStore}
     * 同一套路）：本类只管规则，SQL 全推到接口外面。这样「重复举报不叠加」「只升不降」
     * 「CAS 抢输就不写日志」这几条只能在构造边界场景时才暴露的规则，能在单测里逐条钉住。
     * 真环境适配器见 {@link ReportStoreAdapter}。
     */
    public interface ReportStore {

        /** 被举报帖子原始行；可见性判断留在服务层，本方法不过滤任何状态。 */
        Post findPost(long postId);

        /** 举报人账号行；查不到即「不存在或已注销」。 */
        User findUser(long userId);

        /** 幂等插入一行举报；返回影响行数，0 表示这个人对这条内容早就举报过。 */
        int insertReport(ContentReport row);

        /** 把 post.report_cnt 刷成真相（按 content_report 重算的覆盖写，不做 INCR）。 */
        void refreshPostReportCnt(long postId);

        /** 真相：多少个<b>人</b>举报过这个帖子。 */
        long countPostReporters(long postId);

        /** 该目标当前唯一那张待审任务（DDL 的 uk_target_pending 保证最多一条），没有则 null。 */
        AuditTask findPendingTask(String targetType, long targetId);

        /** 幂等插入待审任务；实现方负责回填自增 id，返回 0 表示已有别人的待办。 */
        int insertAuditTask(AuditTask task);

        /** 升优先级：只改风险三件套与时间戳；返回 false 表示任务在这两次读之间被人处置掉了。 */
        boolean escalateTask(long id, String level, BigDecimal score, LocalDateTime sla, LocalDateTime now);

        /** 比较改写 PUBLISHED → HUMAN_REVIEW，返回影响行数（0 = 状态已被别人改走）。 */
        int markPostHumanReview(long postId);

        /** 状态流转留痕 post_status_log，与发帖的 {@code logStatus} 同一张表、同一列宽口径。 */
        void logPostStatus(long postId, String fromStatus, String toStatus, String reason);
    }

    private final ReportStore store;
    private final PostingQuotaService quotaService;
    private final SensitiveWordEngine engine;
    private final CacheService cacheService;
    private final MindisleProperties properties;

    public ReportService(ReportStore store, PostingQuotaService quotaService,
                         SensitiveWordEngine engine, CacheService cacheService,
                         MindisleProperties properties) {
        this.store = store;
        this.quotaService = quotaService;
        this.engine = engine;
        this.cacheService = cacheService;
        this.properties = properties;
    }

    private static Map<String, String> reasons() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("spam", "广告");
        map.put("abuse", "攻击辱骂");
        map.put("sexual", "色情低俗");
        map.put("privacy", "泄露隐私");
        map.put(SELF_HARM, "自伤风险");
        map.put("other", "违法或其他");
        return Collections.unmodifiableMap(map);
    }

    /**
     * 提交一次针对帖子的举报。
     *
     * <p><b>校验顺序是设计，不是随手写的</b>：理由合法 → 帖可见 → 人有账号 → 人有说话资格
     * → 不能举报自己 → 描述与证据合法 → 落举报行 → 重算计数 → 机审复扫 → 处置任务 → 阈值转人审 → 回执。
     * 把最便宜的字符串判断放在最前面、把最贵的 DFA 全文扫描放在写库之后，
     * 是因为复扫的结论<b>只用来给审核员排队</b>，它没有否决这次举报的权力——
     * 顺序上让它靠后，也避免「机审说没问题于是举报不成立」这种误读。</p>
     *
     * @param reporterId 举报人，来自 token；<b>不</b>走马甲（谁举报的必须可查，见 DDL 注释）
     * @param postId     被举报帖子
     * @param req        理由 + 描述 + 证据，可整体为 null（那就是「只点了一下举报」）
     * @param now        时间基准，由调用方传入；本类内部绝不读系统时钟
     */
    @Transactional
    public ReportView report(long reporterId, long postId, ReportRequest req, LocalDateTime now) {
        MindisleProperties.Report config = properties.getReport();
        int threshold = config.getAutoReviewThreshold();
        String reason = normalizeReason(req == null ? null : req.reason());

        Post post = store.findPost(postId);
        if (post == null || !PostQueryService.visibleTo(post, reporterId, now)) {
            // 不存在、已删除、树洞到期、别人的私密帖，全部同一个 404/30001：
            // 与详情接口和评论区逐字一致。报 403 等于替举报人确认「这条存在」，
            // 而举报接口天生就是一个问「有没有这条」的口子。
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }
        User reporter = store.findUser(reporterId);
        if (reporter == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        // BR6：封禁与注销账号不能发起任何互动。这里用 assertStatusAllowsInteract 而不是
        // assertCanComment——后者会连带读评论配额键，举报没有配额，不该去碰评论的计数缓存。
        quotaService.assertStatusAllowsInteract(reporter);
        if (PostQueryService.isOwner(post, reporterId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不能举报自己的内容，删掉它就够了");
        }

        String description = normalizeDescription(req == null ? null : req.description(), config);
        String evidenceUrls = normalizeEvidence(req == null ? null : req.evidenceUrls(), config);

        ContentReport row = new ContentReport();
        row.setReporterId(reporterId);
        row.setTargetType(ContentReport.TARGET_POST);
        row.setTargetId(postId);
        row.setPostId(postId);
        row.setAuthorId(post.getUserId());
        row.setReason(reason);
        row.setDescription(description);
        row.setEvidenceUrls(evidenceUrls);
        row.setStatus(ContentReport.STATUS_PENDING);
        row.setDeleted(0);
        boolean duplicated = store.insertReport(row) == 0;

        // 重复举报也重算：report_cnt 是一个可以从真相表推出来的数，
        // 「顺手把它自愈一次」比「只在插入成功时更新」更便宜，也更不容易留下漂移。
        store.refreshPostReportCnt(postId);
        long cnt = store.countPostReporters(postId);

        CheckResult result = rescan(post);
        String verdict = result.hit() ? result.action() : "PASS";
        // 优先级取「机审复扫看到的」与「举报人说的」里更高的那一个：两边都可能错，
        // 而这条队列的代价不对称——升错了是一次多余的点击，降错了是一个被压住的预警。
        String level = higher(CrisisGrader.levelOf(result),
                SELF_HARM.equals(reason) ? CrisisGrader.L3 : CrisisGrader.L0);

        Long taskId = ensureTask(postId, reason, reporterId, cnt, threshold, verdict, level, now);

        boolean escalated = false;
        if (cnt >= threshold && PostService.STATUS_PUBLISHED.equals(post.getStatus())
                && store.markPostHumanReview(postId) == 1) {
            escalated = true;
            store.logPostStatus(postId, PostService.STATUS_PUBLISHED, PostService.STATUS_HUMAN_REVIEW,
                    cut("system|report|cnt=" + cnt + "/threshold=" + threshold + "|reason=" + reason,
                            LOG_REASON_MAX));
        }

        StringBuilder tip = new StringBuilder();
        if (duplicated) {
            appendTip(tip, "你已经举报过这条内容了，它已经在我们的待审队列里，不用重复举报。");
        } else {
            appendTip(tip, "举报已提交，这条内容已进入人工审核队列；有结果会通知你。");
            if (cnt > 1) {
                appendTip(tip, "目前有 " + cnt + " 个人举报过它。");
            }
        }
        if (escalated) {
            appendTip(tip, "这条内容已被多个人举报，达到阈值，已先转入人工审核并暂时对其他屿民隐藏。");
        }
        if (SELF_HARM.equals(reason)) {
            // 文案里不出现号码，号码走 hotline 字段：全站三条文案规矩之一（同一句提示在两处
            // 出现不同号码是迟早的事，把号码收在配置里只留一个出口）。
            appendTip(tip, "如果你担心对方有即时危险，请立刻联系老师或校中心，下面的电话 24 小时有人接。");
        }
        String hotline = SELF_HARM.equals(reason) ? properties.getCrisis().getHotline() : null;
        return new ReportView(postId, reason, REASONS.get(reason), duplicated, cnt, threshold,
                escalated, taskId, hotline, tip.length() == 0 ? null : tip.toString());
    }

    // ================================================================ 入参归一化

    /**
     * 理由必须在白名单里，且<b>不做大小写宽容</b>：ENUM 落库本来就是大小写敏感的比较，
     * 前端传 Spm 之类的一律拒掉，让错误当场以一句人话暴露，而不是靠 MySQL 的宽松转换
     * 悄悄写成一个空值（那才是查不清的数据）。
     */
    private static String normalizeReason(String raw) {
        String reason = raw == null ? "" : raw.strip();
        if (!REASONS.containsKey(reason)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "举报理由只能是 " + String.join(" / ", REASONS.keySet()) + " 之一");
        }
        return reason;
    }

    /**
     * 描述：去首尾空白，按<b>码点</b>而不是 char 计数（一个 emoji 是一个码点两个 char，
     * 按 char 算会让「带表情的描述」被提前拦下，而 {@code description} 是 VARCHAR(500) 字符列，
     * MySQL 数的也是字符）。空串一律存 null：null 才是「没写」，空串在报表里会算成「写了但没内容」。
     */
    private String normalizeDescription(String raw, MindisleProperties.Report config) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            return null;
        }
        int chars = text.codePointCount(0, text.length());
        int max = config.getMaxDescriptionChars();
        if (chars > max) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "举报描述最长 " + max + " 字，现在是 " + chars + " 字");
        }
        return text;
    }

    /**
     * 证据地址：去空、去重、限张数、限形状，最后逗号拼接落 {@code evidence_urls}。
     *
     * <p>去重排在张数比较<b>之前</b>：同一个人把同一张截图塞四遍不是四份证据，
     * 这种输入更可能是前端重复绑定而不是故意刷屏，拒掉他不如默默收下三份一样的东西里的一份。</p>
     */
    private String normalizeEvidence(List<String> raw, MindisleProperties.Report config) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Set<String> kept = new LinkedHashSet<>();
        for (String item : raw) {
            String url = item == null ? "" : item.strip();
            if (url.isEmpty()) {
                continue;
            }
            if (!url.startsWith(EVIDENCE_PREFIX) || url.contains("://") || url.contains("\\")
                    || url.contains("..") || url.length() > EVIDENCE_ITEM_MAX) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "证据截图必须是先上传到本站再回填的地址（以 " + EVIDENCE_PREFIX + " 开头），不接受外链");
            }
            kept.add(url);
        }
        if (kept.isEmpty()) {
            return null;
        }
        int max = config.getMaxEvidenceImages();
        if (kept.size() > max) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "证据截图最多 " + max + " 张，现在是 " + kept.size() + " 张");
        }
        String joined = String.join(",", kept);
        if (joined.length() > EVIDENCE_TOTAL_MAX) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "证据截图地址总长超过 " + EVIDENCE_TOTAL_MAX + " 字，请少传几张");
        }
        return joined;
    }

    // ================================================================ 机审复扫与处置任务

    /**
     * 对<b>被举报内容</b>重跑一次 DFA。
     *
     * <p>这不是多余的开销，是三件事的交点：① 发帖时可能跑的是<b>旧版词库</b>（词库是热更新的），
     * ② 举报给了一条合法理由之外的信号（「有人说这是色情内容」），复扫能把它变成
     * 审核台上可读的 category/level，③ 让 {@code audit_task.channel} 名副其实——
     * DDL 的三个通道值描述的是「哪一路机审产出了这条结论」，复扫之后这一行的结论确实来自 DFA。</p>
     *
     * <p>拼接方式与 {@code PostService} 第 7 步逐字相同（title + 换行 + content）：
     * 复扫的意义是「用同一把尺子再量一次」，换一种拼法就不是同一条文本了，
     * 命中偏移量与当初发帖时的记录也对不上。</p>
     */
    private CheckResult rescan(Post post) {
        engine.refreshIfStale(cacheService, properties.getAudit().getDictVersionKey());
        return engine.check(post.getTitle() + "\n" + post.getContent(), "user");
    }

    /**
     * 确保这条内容在审核队列里，并且<b>只往上升、不往下降</b>。
     *
     * <p>重复举报也调这个方法：第 2 个举报人如果带来更高的风险等级（例如第一个报「广告」、
     * 第二个报「自伤风险」），他要能把已存在的待办任务抬上来，而不是被「已经有了」挡在门外。</p>
     *
     * @return 这张待审任务的 id；极端并发下拿不到时为 null（回执字段允许为空，不影响举报本身成功）
     */
    private Long ensureTask(long postId, String reason, long reporterId, long cnt, int threshold,
                            String verdict, String level, LocalDateTime now) {
        AuditTask existing = store.findPendingTask(ContentReport.TARGET_POST, postId);
        if (existing == null) {
            AuditTask task = new AuditTask();
            task.setTargetType(ContentReport.TARGET_POST);
            task.setTargetId(postId);
            task.setSource(AuditTask.SOURCE_REPORT);
            task.setChannel(AuditTask.CHANNEL_DFA);
            task.setResult(verdict);
            task.setRiskLevel(level);
            task.setRiskScore(scoreOf(level));
            task.setStatus(AuditTask.STATUS_PENDING);
            task.setSlaAt(slaOf(level, now));
            task.setDeleted(0);
            task.setRemark(cut("举报 FR4.7｜理由=" + REASONS.get(reason) + "(" + reason + ")"
                    + "｜举报人=" + reporterId
                    + "｜累计=" + cnt + "次/阈值=" + threshold
                    + "｜机审复扫=" + verdict
                    + "｜词库=" + engine.version(), TASK_REMARK_MAX));
            if (store.insertAuditTask(task) > 0) {
                return task.getId();
            }
            // 并发：另一个人刚好也为这条内容建了待办。INSERT IGNORE 照样消耗自增值，
            // 回填进实体的 id 不可信，必须重新定位那条真实存在的任务。
            existing = store.findPendingTask(ContentReport.TARGET_POST, postId);
            if (existing == null) {
                return null;
            }
        }
        if (rankOf(level) > rankOf(existing.getRiskLevel())) {
            store.escalateTask(existing.getId(), level, scoreOf(level), slaOf(level, now), now);
        }
        return existing.getId();
    }

    /** 两个等级取更高的那个（{@link CrisisGrader} 只产 L0/L2/L3，但库里的列允许 L0–L3）。 */
    static String higher(String current, String candidate) {
        return rankOf(candidate) > rankOf(current) ? candidate : current;
    }

    /**
     * 等级排序。
     *
     * <p>{@code L1} 在这里是<b>认得但不会自己产出</b>：{@link CrisisGrader} 的词面通道输出域是
     * {L0, L2, L3}（L1 需要同一用户的时间序列，见其类注释），可 {@code audit_task.risk_level}
     * 和 {@code post.risk_level} 的 ENUM 里有 L1，别处写进来的值本方法必须能比。
     * 未知值一律当 0：认不出等级的行不该获得任何升级，宁可不升。</p>
     */
    static int rankOf(String level) {
        if (CrisisGrader.L3.equals(level)) {
            return 3;
        }
        if (CrisisGrader.L2.equals(level)) {
            return 2;
        }
        if ("L1".equals(level)) {
            return 1;
        }
        return 0;
    }

    /** 风险分基准值复用危机配置，与 {@code alert_ticket.risk_score} 同一把尺子（L0 记 0.000 而不是 null：列是 NOT NULL，且「没有风险」也是一个结论）。 */
    private BigDecimal scoreOf(String level) {
        MindisleProperties.Crisis crisis = properties.getCrisis();
        double value = CrisisGrader.L3.equals(level) ? crisis.getL3Score()
                : CrisisGrader.L2.equals(level) ? crisis.getL2Score() : 0d;
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }

    /** 处理时限：危机等级走 {@code crisis} 的 30 分钟 / 4 小时，其余走举报默认时限（默认 24 小时）。 */
    private LocalDateTime slaOf(String level, LocalDateTime now) {
        MindisleProperties.Crisis crisis = properties.getCrisis();
        if (CrisisGrader.L3.equals(level)) {
            return now.plusMinutes(crisis.getL3SlaMinutes());
        }
        if (CrisisGrader.L2.equals(level)) {
            return now.plusHours(crisis.getL2SlaHours());
        }
        return now.plusHours(properties.getReport().getSlaHours());
    }

    // ================================================================ 小工具

    /** 与 {@code PostService#appendTip} 同一形状：多条提示用空格接成一句话，空提示不占位。 */
    private static void appendTip(StringBuilder sb, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(value);
    }

    /** 按 char 截断：这里截的全是服务端自己拼出来的 ASCII+汉字串，没有代理对风险。 */
    private static String cut(String value, int maxChars) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}

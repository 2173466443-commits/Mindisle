package com.mindisle.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.mindisle.audit.SensitiveWordEngine;
import com.mindisle.cache.CaffeineCacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.AuditTask;
import com.mindisle.entity.ContentReport;
import com.mindisle.entity.Post;
import com.mindisle.entity.User;
import com.mindisle.post.dto.ReportRequest;
import com.mindisle.post.dto.ReportView;

/**
 * 举报单测（任务 T3.11 · 需求 FR4.7、FR4.4、BR2、BR6、BR10）。
 *
 * <p><b>为什么能这么测</b>：{@link ReportService} 把落库全部推到 {@code ReportStore} 端口后面，
 * 于是「重复举报不叠加」「阈值只数人数」「只升不降」「CAS 抢输就不写流转日志」
 * 「举报不自动建危机工单」这几条只在并发或边界上才露头的规则，这里全部不连库、不起 Spring 就能钉死。
 * 机审与资格用<b>真的</b> {@link SensitiveWordEngine} 与 {@link PostingQuotaService}，
 * 所以「提交举报顺手复扫一次」「禁言能举报、封禁不能」这两条跨类规则是真跑通了一遍。</p>
 *
 * <p><b>本类刻意不测的东西</b>：{@code ReportStoreAdapter} 那 11 个方法拼出的 SQL、
 * {@code refreshReportCnt} 那条相关子查询过不过滤软删行、{@code INSERT IGNORE} 撞
 * {@code uk_target_pending} 时 MySQL 到底回填什么——那是「接线错」而不是「规则错」，
 * 由 docs/smoke.mjs 第 17 步打真 HTTP + 真库取证负责。在这里假装测过 SQL，
 * 就等于把两类缺陷混进同一份绿。</p>
 */
class ReportServiceTest {

    /** 固定时间基准：SLA 的 30 分钟 / 4 小时 / 24 小时全靠它算，服务内部不读系统时钟。 */
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 9, 21, 10, 0);

    private static final long POST_ID = 801L;
    private static final long AUTHOR_ID = 900L;
    private static final long ME = 901L;
    private static final long OTHER = 902L;
    private static final long THIRD = 903L;

    /** 与 SensitiveWordEngineTest 同一套词库格式，只留本类要用的四组。 */
    private static final String FIXTURE = String.join("\n",
            "#version=test-report-1",
            row("政治违法", "black", "BLOCK", "both", "contains", "枪支弹药"),
            row("辱骂攻击", "grey", "REVIEW", "user", "contains", "傻逼"),
            row("自伤自杀", "risk", "TAG", "both", "contains", "伤害自己"),
            row("自伤自杀", "risk", "TAG", "both", "contains", "割腕"));

    private FakeStore store;
    private MindisleProperties properties;
    private ReportService service;

    private static String row(String group, String level, String action, String scope,
                             String matchType, String word) {
        return String.join("\t", group, level, action, scope, matchType, word);
    }

    @BeforeEach
    void setUp() {
        SensitiveWordEngine engine = new SensitiveWordEngine(new MindisleProperties(),
                new DefaultResourceLoader());
        engine.reload(FIXTURE);
        properties = new MindisleProperties();
        PostingQuotaService quota = new PostingQuotaService(new CaffeineCacheService(), properties);
        store = new FakeStore();
        store.posts.put(POST_ID, publicPost(POST_ID, AUTHOR_ID, "正常的求助帖", "有人知道怎么办好吗"));
        store.users.put(AUTHOR_ID, activeUser(AUTHOR_ID, "楼主"));
        store.users.put(ME, activeUser(ME, "我"));
        store.users.put(OTHER, activeUser(OTHER, "路人甲"));
        store.users.put(THIRD, activeUser(THIRD, "路人乙"));
        service = new ReportService(store, quota, engine, new CaffeineCacheService(), properties);
    }

    // ------------------------------------------------------------------ 造数据

    /** post.title 在 DDL 里是 NOT NULL，复扫要读它，所以 fake 必须给。 */
    private static Post publicPost(long id, long userId, String title, String content) {
        Post post = new Post();
        post.setId(id);
        post.setUserId(userId);
        post.setType("normal");
        post.setStatus(PostService.STATUS_PUBLISHED);
        post.setVisibility(PostQueryService.VISIBILITY_PUBLIC);
        post.setIsAnonymous(0);
        post.setTitle(title);
        post.setContent(content);
        // 计数列一律按 DDL 的 NOT NULL DEFAULT 0 初始化：替身太宽松会让断言假绿（T3.6 踩过）
        post.setViewCnt(0);
        post.setLikeCnt(0);
        post.setCommentCnt(0);
        post.setCollectCnt(0);
        post.setReportCnt(0);
        return post;
    }

    private static User activeUser(long id, String nickname) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setNickname(nickname);
        user.setStatus("ACTIVE");
        user.setCreatedAt(DAY.minusDays(60));
        return user;
    }

    private ReportView report(long reporterId, String reason) {
        return service.report(reporterId, POST_ID, new ReportRequest(reason, null, null), DAY);
    }

    private ReportView reportFull(long reporterId, String reason, String description,
                                 List<String> evidence) {
        return service.report(reporterId, POST_ID,
                new ReportRequest(reason, description, evidence), DAY);
    }

    /** 最近一次写入的那一行举报（按自增 id 最大取，与真库 uk 一样一人一条）。 */
    private ContentReport lastReport() {
        return store.byKey.values().stream()
                .max(Comparator.comparingLong(ContentReport::getId))
                .orElseThrow();
    }

    private static ErrorCode codeOf(Runnable call) {
        return assertThrows(BizException.class, call::run).getErrorCode();
    }

    private static String messageOf(Runnable call) {
        return assertThrows(BizException.class, call::run).getMessage();
    }

    // ------------------------------------------------------------------ 理由白名单（FR4.7）

    @Test
    @DisplayName("六类理由与手册 §6.5 第 6 条逐字一致，且顺序稳定（报错文案直接 join 这个顺序）")
    void reasonListIsTheManualsList() {
        assertThat(ReportService.REASONS.keySet())
                .containsExactly("spam", "abuse", "sexual", "privacy", "self-harm", "other");
        assertThat(ReportService.REASONS.values())
                .containsExactly("广告", "攻击辱骂", "色情低俗", "泄露隐私", "自伤风险", "违法或其他");
    }

    @Test
    @DisplayName("理由不在白名单：400/10001，且一句报错就把六个可选项全念出来；一行都不写")
    void illegalReasonIsRejectedBeforeAnyWrite() {
        int inserts = store.insertCalls;
        assertThat(codeOf(() -> report(ME, "harassment"))).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(messageOf(() -> report(ME, "harassment")))
                .contains("spam / abuse / sexual / privacy / self-harm / other");
        assertThat(store.insertCalls).as("被拒的举报不许留下半行数据").isEqualTo(inserts);
        assertThat(store.refreshCalls).isEmpty();
        assertThat(store.tasks).isEmpty();
    }

    @Test
    @DisplayName("整个请求体缺失（null）= 没有理由，走同一句 10001，不是 500")
    void nullRequestIsBadRequestNotInternalError() {
        assertThat(codeOf(() -> service.report(ME, POST_ID, null, DAY)))
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("理由去首尾空白，但不做大小写宽容：ENUM 本身敏感，悄悄兼容等于把脏值写进库")
    void reasonIsTrimmedButNotCaseFolded() {
        assertThat(report(ME, "  abuse  ").reason()).isEqualTo("abuse");
        assertThat(codeOf(() -> report(OTHER, "Abuse"))).isEqualTo(ErrorCode.PARAM_INVALID);
    }

    // ------------------------------------------------------------------ 可见性与资格

    @Test
    @DisplayName("私密帖、查无此帖、树洞到期三种情况同一个 404/30001：举报接口不能用来探测内容是否存在")
    void invisiblePostIsIndistinguishableFromMissingPost() {
        Post privatePost = publicPost(810L, OTHER, "仅自己可见", "这段话只给自己看");
        privatePost.setVisibility("private");
        store.posts.put(810L, privatePost);
        Post expired = publicPost(811L, OTHER, "树洞", "到点就该消失");
        expired.setType("hole");
        expired.setAutoDestroyAt(DAY.minusMinutes(1));
        store.posts.put(811L, expired);
        int inserts = store.insertCalls;

        assertThat(codeOf(() -> service.report(ME, 810L, new ReportRequest("abuse", null, null), DAY)))
                .isEqualTo(ErrorCode.POST_NOT_FOUND);
        assertThat(codeOf(() -> service.report(ME, 811L, new ReportRequest("abuse", null, null), DAY)))
                .isEqualTo(ErrorCode.POST_NOT_FOUND);
        assertThat(codeOf(() -> service.report(ME, 999999L,
                new ReportRequest("abuse", null, null), DAY))).isEqualTo(ErrorCode.POST_NOT_FOUND);
        assertThat(store.insertCalls).as("判 404 之前不许写任何东西").isEqualTo(inserts);
    }

    @Test
    @DisplayName("举报自己的帖是 400/10001，不是 404：这是本人，存在性根本不是秘密")
    void ownerReportingOwnPostIsRejected() {
        assertThat(codeOf(() -> service.report(AUTHOR_ID, POST_ID,
                new ReportRequest("spam", null, null), DAY))).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(messageOf(() -> service.report(AUTHOR_ID, POST_ID,
                new ReportRequest("spam", null, null), DAY))).contains("不能举报自己的内容");
        assertThat(store.insertCalls).isZero();
    }

    @Test
    @DisplayName("封禁账号不能举报（403/20003），禁言账号可以：禁言夺的是公开发言权，不是求助权")
    void bannedReporterIsBlockedButMutedIsNot() {
        store.users.get(OTHER).setStatus("BANNED");
        assertThat(codeOf(() -> report(OTHER, "abuse"))).isEqualTo(ErrorCode.USER_DISABLED);
        assertThat(store.insertCalls).isZero();

        store.users.get(ME).setStatus("MUTED");
        assertThat(report(ME, "abuse").reportCnt()).isEqualTo(1L);
    }

    @Test
    @DisplayName("查无此人（已注销）是 20001，与「帖不存在」的 30001 分得开：前者是账号问题")
    void deletedReporterIsUserNotFound() {
        store.users.remove(ME);
        assertThat(codeOf(() -> report(ME, "abuse"))).isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThat(store.insertCalls).isZero();
    }

    // ------------------------------------------------------------------ 描述与证据

    @Test
    @DisplayName("描述按码点算上限：200 个 emoji 过、201 个才拒，且落库的是 trim 过的原文")
    void descriptionIsCodePointCapped() {
        String emoji = new String(Character.toChars(0x1F6AA));
        assertThat(emoji.length()).as("一个增补平面 emoji 占两个 char").isEqualTo(2);

        ReportView ok = reportFull(ME, "abuse", " " + emoji.repeat(200) + " ", null);
        assertThat(ok.reason()).isEqualTo("abuse");
        assertThat(lastReport().getDescription()).hasSize(400);

        assertThat(codeOf(() -> reportFull(OTHER, "abuse", "啊".repeat(201), null)))
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(messageOf(() -> reportFull(OTHER, "abuse", "啊".repeat(201), null)))
                .contains("200");
    }

    @Test
    @DisplayName("描述空白等价于没写：存 null 而不是空串，报表才分得清「没写」和「写了个空」")
    void blankDescriptionIsStoredAsNull() {
        reportFull(ME, "spam", "   \n  ", null);
        assertThat(lastReport().getDescription()).isNull();
    }

    @Test
    @DisplayName("证据只收本站上传地址：外链、data:、javascript:、目录穿越一律 400，不给审核台点开钓鱼图的入口")
    void evidenceMustBeUploadsRelativePaths() {
        for (String bad : List.of("http://evil.example/a.jpg", "https://evil.example/a.jpg",
                "data:image/png;base64,AAAA", "javascript:alert(1)", "//evil.example/a.jpg",
                "/uploads/../../etc/passwd", "/uploads/..%2f..%2f", "/upload/2026/a.jpg")) {
            assertThat(codeOf(() -> reportFull(ME, "sexual", null, List.of(bad))))
                    .as("非法证据地址应当被拒：" + bad)
                    .isEqualTo(ErrorCode.PARAM_INVALID);
        }
        // 同一张图，换成本站上传回来的相对地址就应当通过：证明挡的是形状，不是举报本身
        assertThat(reportFull(ME, "sexual", null,
                List.of("/uploads/2026/09/21/aaa.jpg")).reportCnt()).isEqualTo(1L);
        assertThat(store.insertCalls).as("八条外链全被拒，只有最后那条合法的写了一行").isEqualTo(1);
    }

    @Test
    @DisplayName("反斜杠也挡：不带 .. 的 Windows 形式路径同样不收")
    void evidenceRejectsBackslashes() {
        assertThat(codeOf(() -> reportFull(ME, "spam", null,
                List.of("/uploads/2026\\09\\aaa.jpg")))).isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("证据先按集合去重再数张数：同一张图塞四遍算一张，三张不同的才通过，第四张才拒")
    void evidenceIsDedupedThenCappedAtThree() {
        String a = "/uploads/2026/09/21/aaa.jpg";
        String b = "/uploads/2026/09/21/bbb.jpg";
        String c = "/uploads/2026/09/21/ccc.jpg";
        String d = "/uploads/2026/09/21/ddd.jpg";

        reportFull(ME, "spam", null, List.of(a, a, a, a));
        assertThat(lastReport().getEvidenceUrls()).isEqualTo(a);

        reportFull(OTHER, "spam", null, List.of(a, b, c));
        assertThat(lastReport().getEvidenceUrls()).isEqualTo(a + "," + b + "," + c);

        assertThat(codeOf(() -> reportFull(THIRD, "spam", null, List.of(a, b, c, d))))
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(messageOf(() -> reportFull(THIRD, "spam", null, List.of(a, b, c, d))))
                .contains("最多 3 张");
    }

    @Test
    @DisplayName("证据列表全空（只有空串）等于没传：存 null，不存空串")
    void emptyEvidenceListIsStoredAsNull() {
        reportFull(ME, "spam", null, List.of("  ", ""));
        assertThat(lastReport().getEvidenceUrls()).isNull();
    }

    @Test
    @DisplayName("单条证据地址超长即拒（列宽 1000 是拼接后的总宽，单条另有 200）")
    void evidenceItemLengthIsCapped() {
        String tooLong = "/uploads/" + "x".repeat(200);
        assertThat(codeOf(() -> reportFull(ME, "spam", null, List.of(tooLong))))
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    // ------------------------------------------------------------------ 首次举报：行 + 计数 + 工单

    @Test
    @DisplayName("FR4.7 主链路：落一行 PENDING 举报、report_cnt 重算成 1、即刻派生 audit_task(source=report)")
    void firstReportWritesRowCounterAndTask() {
        ReportView view = report(ME, "abuse");

        ContentReport row = lastReport();
        assertThat(row.getReporterId()).isEqualTo(ME);
        assertThat(row.getTargetType()).isEqualTo(ContentReport.TARGET_POST);
        assertThat(row.getTargetId()).isEqualTo(POST_ID);
        assertThat(row.getPostId()).isEqualTo(POST_ID);
        assertThat(row.getAuthorId()).isEqualTo(AUTHOR_ID);
        assertThat(row.getReason()).isEqualTo("abuse");
        assertThat(row.getStatus()).isEqualTo(ContentReport.STATUS_PENDING);

        assertThat(view.duplicated()).isFalse();
        assertThat(view.reportCnt()).isEqualTo(1L);
        assertThat(view.reasonLabel()).isEqualTo("攻击辱骂");
        assertThat(view.autoReviewThreshold()).isEqualTo(3);
        assertThat(view.escalated()).isFalse();
        assertThat(view.hotline()).isNull();
        assertThat(view.tip()).contains("举报已提交");
        assertThat(store.posts.get(POST_ID).getReportCnt()).as("冗余列与回执同源").isEqualTo(1);
        assertThat(store.refreshCalls).containsExactly(POST_ID);

        AuditTask task = store.tasks.get(view.auditTaskId());
        assertThat(task).isNotNull();
        assertThat(task.getSource()).isEqualTo(AuditTask.SOURCE_REPORT);
        assertThat(task.getChannel()).isEqualTo(AuditTask.CHANNEL_DFA);
        assertThat(task.getStatus()).isEqualTo(AuditTask.STATUS_PENDING);
        assertThat(task.getTargetType()).isEqualTo(ContentReport.TARGET_POST);
        assertThat(task.getTargetId()).isEqualTo(POST_ID);
        assertThat(task.getResult()).isEqualTo("PASS");
        assertThat(task.getRemark())
                .contains("举报 FR4.7", "理由=攻击辱骂(abuse)", "举报人=" + ME,
                        "累计=1次/阈值=3", "机审复扫=PASS", "词库=test-report-1");
    }

    @Test
    @DisplayName("BR2 幂等：同一人重复举报不新增记录、不叠加计数，也不新建工单，回 duplicated=true")
    void duplicateReportDoesNotStack() {
        ReportView first = report(ME, "abuse");
        ReportView second = report(ME, "abuse");

        assertThat(second.duplicated()).isTrue();
        assertThat(second.reportCnt()).isEqualTo(1L);
        assertThat(second.auditTaskId()).isEqualTo(first.auditTaskId());
        assertThat(store.byKey).hasSize(1);
        assertThat(store.tasks).hasSize(1);
        assertThat(store.insertCalls).as("端口照样被调用，但重复那次影响行数是 0").isEqualTo(2);
        assertThat(second.tip()).contains("已经举报过");
    }

    // ------------------------------------------------------------------ 阈值转人审（FR4.4）

    @Test
    @DisplayName("三个人独立举报即达阈值：帖子 CAS 转 HUMAN_REVIEW，并写一条 post_status_log")
    void threeReportersFlipThePostToHumanReview() {
        report(ME, "spam");
        report(OTHER, "spam");
        ReportView third = report(THIRD, "spam");

        assertThat(third.escalated()).isTrue();
        assertThat(third.reportCnt()).isEqualTo(3L);
        assertThat(third.tip()).contains("达到阈值").contains("人工审核");
        assertThat(store.posts.get(POST_ID).getStatus()).isEqualTo(PostService.STATUS_HUMAN_REVIEW);
        assertThat(store.statusLogs).hasSize(1);
        String[] log = store.statusLogs.get(0);
        assertThat(log[1]).isEqualTo("PUBLISHED");
        assertThat(log[2]).isEqualTo("HUMAN_REVIEW");
        assertThat(log[3]).contains("system|report|cnt=3/threshold=3|reason=spam");
    }

    @Test
    @DisplayName("阈值只数「多少人」：一个人反复点举报永远打不到阈值（否则一个人就能灭掉别人的帖）")
    void repeatedReportsByOnePersonNeverReachThreshold() {
        for (int i = 0; i < 8; i++) {
            report(ME, "spam");
        }
        assertThat(store.posts.get(POST_ID).getReportCnt()).isEqualTo(1);
        assertThat(store.posts.get(POST_ID).getStatus()).isEqualTo(PostService.STATUS_PUBLISHED);
        assertThat(store.statusLogs).isEmpty();
    }

    @Test
    @DisplayName("阈值走配置而不是写死：改成 2 之后两个人就够（NFR10）")
    void thresholdIsConfigurable() {
        properties.getReport().setAutoReviewThreshold(2);
        report(ME, "spam");
        ReportView second = report(OTHER, "spam");
        assertThat(second.autoReviewThreshold()).isEqualTo(2);
        assertThat(second.escalated()).isTrue();
        assertThat(store.posts.get(POST_ID).getStatus()).isEqualTo(PostService.STATUS_HUMAN_REVIEW);
    }

    @Test
    @DisplayName("CAS 抢输（管理员刚好先动了这条帖）时 escalated=false 且不写流转日志：日志条数=真实流转次数")
    void lostCasWritesNoStatusLog() {
        store.casLoses = true;
        report(ME, "spam");
        report(OTHER, "spam");
        ReportView third = report(THIRD, "spam");

        assertThat(third.escalated()).isFalse();
        assertThat(third.reportCnt()).isEqualTo(3L);
        assertThat(third.tip()).doesNotContain("达到阈值");
        assertThat(store.statusLogs).isEmpty();
        assertThat(store.posts.get(POST_ID).getStatus()).isEqualTo(PostService.STATUS_PUBLISHED);
    }

    @Test
    @DisplayName("已经转人审的帖子对别人不可见，于是第四次举报是 404：「暂时隐藏」的效果在这里闭环")
    void humanReviewPostIsNoLongerReportableByOthers() {
        report(ME, "spam");
        report(OTHER, "spam");
        report(THIRD, "spam");
        long before = store.insertCalls;
        assertThat(codeOf(() -> service.report(910L, POST_ID, new ReportRequest("spam", null, null), DAY)))
                .isEqualTo(ErrorCode.POST_NOT_FOUND);
        assertThat(store.insertCalls).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 复扫与优先级（FR4.7 + §5.2）

    @Test
    @DisplayName("普通理由 + 干净内容：任务定 L0、分数 0.000、时限按举报默认 24 小时")
    void cleanReportDefaultsToL0AndTwentyFourHours() {
        ReportView view = report(ME, "spam");
        AuditTask task = store.tasks.get(view.auditTaskId());
        assertThat(task.getRiskLevel()).isEqualTo(CrisisGrader.L0);
        assertThat(task.getRiskScore()).isEqualByComparingTo(new BigDecimal("0.000"));
        assertThat(task.getSlaAt()).isEqualTo(DAY.plusHours(24));
    }

    @Test
    @DisplayName("自伤类举报直接顶到 L3：SLA 30 分钟、hotline 非空，且提示文案里不出现号码")
    void selfHarmReasonGradesL3AndShowsHotline() {
        ReportView view = report(ME, "self-harm");
        AuditTask task = store.tasks.get(view.auditTaskId());

        assertThat(task.getRiskLevel()).isEqualTo(CrisisGrader.L3);
        assertThat(task.getRiskScore()).isEqualByComparingTo(new BigDecimal("0.900"));
        assertThat(task.getSlaAt()).isEqualTo(DAY.plusMinutes(30));
        assertThat(view.hotline()).isEqualTo("12356");
        assertThat(view.tip()).contains("老师或校中心").doesNotContain("12356");
    }

    @Test
    @DisplayName("复扫能把任务顶得比举报人说的更高：他报「广告」，内容里却有方式级自伤词")
    void machineRescanCanOutrankTheReportersClaim() {
        store.posts.put(POST_ID, publicPost(POST_ID, AUTHOR_ID, "深夜发帖", "我可能想割腕了"));
        ReportView view = report(ME, "spam");
        AuditTask task = store.tasks.get(view.auditTaskId());
        assertThat(task.getRiskLevel()).isEqualTo(CrisisGrader.L3);
        assertThat(task.getResult()).isEqualTo("TAG");
        assertThat(task.getSlaAt()).isEqualTo(DAY.plusMinutes(30));
        // 举报人自己没说自伤，所以不该给他弹危机热线：hotline 只跟「这次举报的理由」走
        assertThat(view.hotline()).isNull();
    }

    @Test
    @DisplayName("risk 组但不含方式词 = L2：分数 0.600、时限 4 小时（分级不是一命中就拉满）")
    void riskWordWithoutMethodIsL2WithFourHours() {
        store.posts.put(POST_ID, publicPost(POST_ID, AUTHOR_ID, "很难受", "我真的想伤害自己"));
        ReportView view = report(ME, "abuse");
        AuditTask task = store.tasks.get(view.auditTaskId());
        assertThat(task.getRiskLevel()).isEqualTo(CrisisGrader.L2);
        assertThat(task.getRiskScore()).isEqualByComparingTo(new BigDecimal("0.600"));
        assertThat(task.getSlaAt()).isEqualTo(DAY.plusHours(4));
    }

    @Test
    @DisplayName("自伤举报不自动下架、不自动建危机工单：ReportStore 端口里根本没有写 alert_ticket 的方法，这是编译期保证")
    void selfHarmReportDoesNotOpenCrisisTicket() {
        store.posts.put(POST_ID, publicPost(POST_ID, AUTHOR_ID, "很担心同学", "他说过想伤害自己"));
        ReportView view = report(ME, "self-harm");
        assertThat(store.posts.get(POST_ID).getStatus()).as("下架要管理员来定，不能由一次点击完成")
                .isEqualTo(PostService.STATUS_PUBLISHED);
        assertThat(store.statusLogs).isEmpty();
        assertThat(view.auditTaskId()).as("只派生一张待审任务，开不开危机单是 T6.1 的判断").isNotNull();
    }

    @Test
    @DisplayName("已有待办且风险更低时只升不降：等级、分数、SLA 被抬上来，remark 一个字节都不改")
    void lowerPriorityTaskIsEscalatedWithoutRewritingRemark() {
        seedTask(POST_ID, CrisisGrader.L0, "机审转人审｜原备注");
        int inserts = store.taskInsertCalls;

        ReportView view = report(ME, "self-harm");
        AuditTask task = store.tasks.get(view.auditTaskId());
        assertThat(store.escalateCalls).isEqualTo(1);
        assertThat(store.taskInsertCalls).isEqualTo(inserts);
        assertThat(task.getRiskLevel()).isEqualTo(CrisisGrader.L3);
        assertThat(task.getSlaAt()).isEqualTo(DAY.plusMinutes(30));
        assertThat(task.getRemark()).isEqualTo("机审转人审｜原备注");
        assertThat(task.getSource()).isEqualTo(AuditTask.SOURCE_MACHINE);
    }

    @Test
    @DisplayName("已有待办且风险更高时绝不降级：拿 L3 的队列不会被一条「广告」举报改成 L0")
    void higherPriorityTaskIsNotDowngraded() {
        seedTask(POST_ID, CrisisGrader.L3, "高危待办");
        ReportView view = report(ME, "spam");
        AuditTask task = store.tasks.get(view.auditTaskId());
        assertThat(store.escalateCalls).isZero();
        assertThat(task.getRiskLevel()).isEqualTo(CrisisGrader.L3);
        assertThat(view.auditTaskId()).isEqualTo(8000L);
    }

    @Test
    @DisplayName("并发抢建工单：插入返回 0 时不信任回填 id，改回查定位那条真实存在的任务")
    void taskIdIsRecoveredWhenAnotherReporterWinsTheRace() {
        seedTask(POST_ID, CrisisGrader.L0, "别人刚建的待办");
        store.loseTaskRace = true;

        ReportView view = report(ME, "abuse");
        assertThat(store.taskInsertCalls).as("确实尝试过插入，只是撞了唯一键").isEqualTo(1);
        assertThat(store.tasks).hasSize(1);
        assertThat(view.auditTaskId()).isEqualTo(8000L);
    }

    // ------------------------------------------------------------------ 分级工具与匿名边界

    @Test
    @DisplayName("认得 L1（别的通道写进来的），但未知值一律当最低：认不出等级的行不配获得升级")
    void rankOfHandlesL1AndUnknown() {
        assertThat(ReportService.rankOf("L1")).isEqualTo(1);
        assertThat(ReportService.rankOf("L9")).isZero();
        assertThat(ReportService.rankOf(null)).isZero();
        assertThat(ReportService.higher(CrisisGrader.L2, CrisisGrader.L0)).isEqualTo(CrisisGrader.L2);
        assertThat(ReportService.higher(CrisisGrader.L0, "L1")).isEqualTo("L1");
    }

    @Test
    @DisplayName("匿名树洞照旧可举报：真相表记真实作者便于处置，但回执里一个身份字段都不带")
    void anonymousHoleReportKeepsAttributionInternal() {
        Post hole = publicPost(820L, AUTHOR_ID, "匿名的求救", "有人在吗");
        hole.setType("hole");
        hole.setIsAnonymous(1);
        store.posts.put(820L, hole);

        ReportView view = service.report(ME, 820L, new ReportRequest("self-harm", null, null), DAY);
        ContentReport row = lastReport();
        assertThat(row.getPostId()).isEqualTo(820L);
        assertThat(row.getAuthorId()).as("管理员要能找到人").isEqualTo(AUTHOR_ID);
        assertThat(view.toString()).as("回执不能泄漏作者昵称").doesNotContain("楼主");
        assertThat(view.postId()).isEqualTo(820L);
    }

    // ------------------------------------------------------------------ 手工塞种子

    private AuditTask seedTask(long postId, String level, String remark) {
        AuditTask task = new AuditTask();
        task.setId(8000L);
        task.setTargetType(ContentReport.TARGET_POST);
        task.setTargetId(postId);
        task.setSource(AuditTask.SOURCE_MACHINE);
        task.setChannel(AuditTask.CHANNEL_DFA);
        task.setResult("REVIEW");
        task.setRiskLevel(level);
        task.setRiskScore(BigDecimal.ZERO);
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setSlaAt(DAY.plusHours(1));
        task.setRemark(remark);
        task.setDeleted(0);
        store.tasks.put(task.getId(), task);
        return task;
    }

    // ------------------------------------------------------------------ 内存 fake

    /**
     * 存储端口的内存实现。
     *
     * <p><b>它唯一的价值是「像不像本体」</b>：{@code uk_reporter_target} 与
     * {@code uk_target_pending} 两条唯一键是真挡得住重复的（服务层的 duplicated 判据完全依赖
     * 「影响行数 0」这个信号，替身要是来者不拒，那条最重要的幂等规则就等于没测）；
     * 计数按 DDL 的 {@code NOT NULL DEFAULT 0} 初始化；状态改写按 CAS 语义只在
     * PUBLISHED 时才成功。至于 {@code post_id} 外键与 ENUM 列宽，这里只实现服务层真依赖的部分。</p>
     */
    private final class FakeStore implements ReportService.ReportStore {

        private final Map<Long, Post> posts = new LinkedHashMap<>();
        private final Map<Long, User> users = new LinkedHashMap<>();
        private final Map<String, ContentReport> byKey = new LinkedHashMap<>();
        private final Map<Long, AuditTask> tasks = new LinkedHashMap<>();
        private final List<String[]> statusLogs = new ArrayList<>();
        private final List<Long> refreshCalls = new ArrayList<>();

        private long nextReportId = 7000L;
        private long nextTaskId = 8000L;
        private int insertCalls;
        private int taskInsertCalls;
        private int escalateCalls;
        private int pendingLookups;
        /** 模拟 CAS 抢输：状态是 PUBLISHED，但 UPDATE 就是影响 0 行（别人先动了这一行）。 */
        private boolean casLoses;
        /** 模拟「第一次查没有、插入时别人的事务刚好提交了」这一串时序。 */
        private boolean loseTaskRace;

        private static String key(long reporterId, String targetType, long targetId) {
            return reporterId + "|" + targetType + "|" + targetId;
        }

        @Override
        public Post findPost(long postId) {
            return posts.get(postId);
        }

        @Override
        public User findUser(long userId) {
            return users.get(userId);
        }

        @Override
        public int insertReport(ContentReport row) {
            insertCalls++;
            String key = key(row.getReporterId(), row.getTargetType(), row.getTargetId());
            if (byKey.containsKey(key)) {
                return 0;
            }
            row.setId(++nextReportId);
            byKey.put(key, row);
            return 1;
        }

        @Override
        public void refreshPostReportCnt(long postId) {
            refreshCalls.add(postId);
            Post post = posts.get(postId);
            if (post != null) {
                post.setReportCnt((int) countPostReporters(postId));
            }
        }

        @Override
        public long countPostReporters(long postId) {
            Set<Long> people = new LinkedHashSet<>();
            for (ContentReport row : byKey.values()) {
                boolean live = row.getDeleted() == null || row.getDeleted() == 0;
                if (live && ContentReport.TARGET_POST.equals(row.getTargetType())
                        && row.getTargetId() != null && row.getTargetId().longValue() == postId) {
                    people.add(row.getReporterId());
                }
            }
            return people.size();
        }

        @Override
        public AuditTask findPendingTask(String targetType, long targetId) {
            pendingLookups++;
            if (loseTaskRace && pendingLookups == 1) {
                return null;
            }
            return tasks.values().stream()
                    .filter(task -> targetType.equals(task.getTargetType())
                            && task.getTargetId() != null && task.getTargetId().longValue() == targetId
                            && AuditTask.STATUS_PENDING.equals(task.getStatus()))
                    .max(Comparator.comparingLong(AuditTask::getId))
                    .orElse(null);
        }

        @Override
        public int insertAuditTask(AuditTask task) {
            taskInsertCalls++;
            boolean pendingExists = tasks.values().stream().anyMatch(existing ->
                    AuditTask.STATUS_PENDING.equals(existing.getStatus())
                            && existing.getTargetType().equals(task.getTargetType())
                            && existing.getTargetId().longValue() == task.getTargetId().longValue());
            if (pendingExists) {
                // 撞 uk_target_pending：不回填 id，也不落库（与真库 INSERT IGNORE 影响 0 行一致）
                return 0;
            }
            task.setId(++nextTaskId);
            tasks.put(task.getId(), task);
            return 1;
        }

        @Override
        public boolean escalateTask(long id, String level, BigDecimal score, LocalDateTime sla,
                                    LocalDateTime now) {
            escalateCalls++;
            AuditTask task = tasks.get(id);
            if (task == null) {
                return false;
            }
            task.setRiskLevel(level);
            task.setRiskScore(score);
            task.setSlaAt(sla);
            task.setUpdatedAt(now);
            return true;
        }

        @Override
        public int markPostHumanReview(long postId) {
            Post post = posts.get(postId);
            if (post == null || casLoses || !PostService.STATUS_PUBLISHED.equals(post.getStatus())) {
                return 0;
            }
            post.setStatus(PostService.STATUS_HUMAN_REVIEW);
            return 1;
        }

        @Override
        public void logPostStatus(long postId, String fromStatus, String toStatus, String reason) {
            statusLogs.add(new String[] {String.valueOf(postId), fromStatus, toStatus, reason});
        }
    }
}

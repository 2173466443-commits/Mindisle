package com.mindisle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mindisle.admin.AdminOpLogService.Ctx;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.entity.AdminOpLog;
import com.mindisle.entity.NotifyMessage;
import com.mindisle.entity.Post;
import com.mindisle.entity.PostAppeal;
import com.mindisle.entity.PostStatusLog;
import com.mindisle.mapper.AdminOpLogMapper;
import com.mindisle.mapper.PostAppealMapper;
import com.mindisle.mapper.PostMapper;
import com.mindisle.mapper.PostStatusLogMapper;
import com.mindisle.notify.NotifyService;
import com.mindisle.notify.RecordingNotifyService;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 帖子申诉单测（任务 T6.7 · 需求 FR4.5 一次性申诉 / FR7.6 申诉可回溯与结果回执 · 手册 §9.1 第 6 条）。
 *
 * <p><b>本类真正在钉的三条规则</b>：
 * ① <b>四道校验门的顺序</b>——「是不是你的帖子」必须排在状态与申诉次数之前。反过来写就等于
 *    把一个帖子的处置历史泄露给无关的人：陌生人只要试一次就能从报错文案里读出「这条已经被驳回过」。
 *    判据不是「报错了」，而是<b>前四道门一次都不许碰 post_appeal</b>（{@code countFinalCalls}/
 *    {@code findPendingAppealCalls}/{@code appealInserts} 全部为 0），配 {@code 非本人 + 状态也不可申诉}
 *    这种「两个门都会命中」的输入，顺序错立刻显形。
 * ② <b>驳回回的是「申诉前那个状态」，不是写死 REJECTED</b>——被人工下架和被判定违规是两种事，
 *    前者申诉成功后不该被降级成后者。前一个状态来自 {@code post_status_log} 的尾部回溯，
 *    于是留痕既是审计也是事实来源；查不到才回落 REJECTED（宁严不松）。
 * ③ <b>申诉办结与帖子改状态解耦</b>——CAS 抢不过别的通道时，申诉终态、回执、审计三件照样落，
 *    只有状态留痕不写，返回体里 {@code postStatus} 回当前真实状态。申诉人「有没有拿到结论」
 *    必须是确定的，帖子归状态机管。</p>
 *
 * <p><b>替身复刻的契约</b>：{@code adjudicate} 与 {@code compareAndSetStatus} 都按<b>真 SQL 的 WHERE</b>
 * 判定影响行数（{@code status='PENDING'} / {@code status=fromStatus}），改不动就返回 0，
 * 所以「重复裁定」「并发抢占」这两类场景不需要布尔开关去伪造，而是由假库的行状态自然产生。
 * {@code compareAndSetStatus} 之前还会执行 {@link #onCas} 钩子，用来模拟真库里那个
 * 「selectById 之后、CAS 之前」的窗口被别的通道插队。</p>
 */
class AppealServiceTest {

    private static final Ctx ADMIN = new Ctx(77L, "SUPER", "203.0.113.9", "Mozilla/5.0 (Gate6-Appeal)");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 10, 30);
    private static final long POST_ID = 501L;
    private static final long AUTHOR = 495L;
    private static final long APPEAL_ID = 9001L;

    // ================================================================ 假库
    private final Map<Long, PostAppeal> appealStore = new LinkedHashMap<>();
    private final Map<Long, Post> postStore = new LinkedHashMap<>();
    private final List<PostStatusLog> logStore = new ArrayList<>();

    // ================================================================ 观察点
    private final List<PostAppeal> appealInserts = new ArrayList<>();
    private final List<Object[]> adjudicateCalls = new ArrayList<>();
    private final List<Object[]> casCalls = new ArrayList<>();
    /** postMapper.stampPublishedAt 的调用记录：采纳必须把发布时间一起补齐（D4 读取侧前提）。 */
    private final List<Object[]> stampCalls = new ArrayList<>();
    private final List<PostStatusLog> logInserts = new ArrayList<>();
    private final List<AdminOpLog> opLogInserts = new ArrayList<>();
    /** 申诉史查询（appealMapper.listByPost）。 */
    private final List<Object[]> appealHistoryCalls = new ArrayList<>();

    /** 状态留痕查询次数（statusLogMapper.listByPost）：尾部回溯的判据靠它。 */
    private int logLookupCalls;

    private int countFinalCalls;
    private int findPendingAppealCalls;
    private int appealSelectByIdCalls;
    private int postSelectByIdCalls;
    private int pageCalls;
    private int countCalls;

    // ================================================================ 打桩返回值
    private long countFinalReturn;
    private PostAppeal findPendingReturn;
    private List<PostAppeal> pageRows = List.of();
    private long pageTotal;
    private Runnable onCas = () -> {
    };

    private String lastCountFilter;
    private String lastPageFilter;
    private long lastOffset;
    private int lastSize;

    private RecordingNotifyService recorder;
    private AppealService service;

    @BeforeEach
    void setUp() {
        PostAppealMapper appealMapper = (PostAppealMapper) Proxy.newProxyInstance(
                PostAppealMapper.class.getClassLoader(), new Class<?>[] {PostAppealMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "insert" -> {
                        PostAppeal row = (PostAppeal) args[0];
                        row.setId((long) appealStore.size() + 1L);
                        appealStore.put(row.getId(), row);
                        appealInserts.add(row);
                        yield 1;
                    }
                    case "selectById" -> {
                        appealSelectByIdCalls++;
                        PostAppeal found = appealStore.get(((Number) args[0]).longValue());
                        yield found == null ? null : copyAppeal(found);
                    }
                    case "countFinalByPost" -> {
                        countFinalCalls++;
                        yield countFinalReturn;
                    }
                    case "findPending" -> {
                        findPendingAppealCalls++;
                        yield findPendingReturn;
                    }
                    case "listByPost" -> {
                        appealHistoryCalls.add(args);
                        yield filterAppeals(((Number) args[0]).longValue());
                    }
                    case "pageAppeals" -> {
                        pageCalls++;
                        lastPageFilter = (String) args[0];
                        lastOffset = ((Number) args[1]).longValue();
                        lastSize = ((Number) args[2]).intValue();
                        yield pageRows;
                    }
                    case "countAppeals" -> {
                        countCalls++;
                        lastCountFilter = (String) args[0];
                        yield pageTotal;
                    }
                    case "adjudicate" -> {
                        adjudicateCalls.add(args);
                        PostAppeal row = appealStore.get(((Number) args[0]).longValue());
                        // UPDATE ... WHERE id = ? AND status = 'PENDING' AND deleted = 0
                        if (row != null && PostAppeal.STATUS_PENDING.equals(row.getStatus())
                                && !Integer.valueOf(1).equals(row.getDeleted())) {
                            row.setStatus((String) args[2]);
                            row.setHandlerId(((Number) args[1]).longValue());
                            row.setResultNote((String) args[3]);
                            row.setHandledAt((LocalDateTime) args[4]);
                            row.setUpdatedAt((LocalDateTime) args[4]);
                            yield 1;
                        }
                        yield 0;
                    }
                    default -> throw new UnsupportedOperationException("未打桩：" + method.getName());
                });

        PostMapper postMapper = (PostMapper) Proxy.newProxyInstance(
                PostMapper.class.getClassLoader(), new Class<?>[] {PostMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectById" -> {
                        postSelectByIdCalls++;
                        Post found = postStore.get(((Number) args[0]).longValue());
                        yield found == null ? null : copyPost(found);
                    }
                    case "compareAndSetStatus" -> {
                        casCalls.add(args);
                        onCas.run();
                        Post post = postStore.get(((Number) args[0]).longValue());
                        // UPDATE post SET status = ? WHERE id = ? AND status = ? AND deleted = 0
                        if (post != null && String.valueOf(args[1]).equals(post.getStatus())) {
                            post.setStatus((String) args[2]);
                            yield 1;
                        }
                        yield 0;
                    }
                    case "stampPublishedAt" -> {
                        stampCalls.add(args);
                        Post target = postStore.get(((Number) args[0]).longValue());
                        // UPDATE ... WHERE id = ? AND status = 'PUBLISHED' AND published_at IS NULL
                        if (target != null && "PUBLISHED".equals(target.getStatus())
                                && target.getPublishedAt() == null) {
                            target.setPublishedAt((LocalDateTime) args[1]);
                            yield 1;
                        }
                        yield 0;
                    }
                    default -> throw new UnsupportedOperationException("未打桩：" + method.getName());
                });

        PostStatusLogMapper statusLogMapper = (PostStatusLogMapper) Proxy.newProxyInstance(
                PostStatusLogMapper.class.getClassLoader(), new Class<?>[] {PostStatusLogMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "insert" -> {
                        PostStatusLog row = (PostStatusLog) args[0];
                        row.setId((long) logStore.size() + 1L);
                        logStore.add(row);
                        logInserts.add(row);
                        yield 1;
                    }
                    case "listByPost" -> {
                        logLookupCalls++;
                        yield filterLogs(((Number) args[0]).longValue());
                    }
                    default -> throw new UnsupportedOperationException("未打桩：" + method.getName());
                });

        AdminOpLogMapper opLogMapper = (AdminOpLogMapper) Proxy.newProxyInstance(
                AdminOpLogMapper.class.getClassLoader(), new Class<?>[] {AdminOpLogMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "insert" -> {
                        AdminOpLog row = (AdminOpLog) args[0];
                        row.setId((long) opLogInserts.size() + 1L);
                        opLogInserts.add(row);
                        yield 1;
                    }
                    default -> throw new UnsupportedOperationException("未打桩：" + method.getName());
                });

        recorder = new RecordingNotifyService();
        NotifyService notifyService = recorder.service();
        service = new AppealService(appealMapper, postMapper, statusLogMapper, notifyService,
                new AdminOpLogService(opLogMapper));
    }
    // ================================================================ 造数据与断言小工具
    private Post givenPost(long id, long authorId, String status) {
        Post post = new Post();
        post.setId(id);
        post.setUserId(authorId);
        post.setTitle("帖子-" + id);
        post.setStatus(status);
        postStore.put(id, post);
        return post;
    }

    private PostAppeal givenAppeal(long id, long postId, Long userId, String status) {
        PostAppeal row = new PostAppeal();
        row.setId(id);
        row.setPostId(postId);
        row.setUserId(userId);
        row.setReason("为什么被驳回");
        row.setStatus(status);
        row.setDeleted(0);
        row.setCreatedAt(NOW.minusDays(1));
        row.setUpdatedAt(NOW.minusDays(1));
        appealStore.put(id, row);
        return row;
    }

    /** 往留痕表里追加一条（id 自增，顺序即发生顺序，与 listByPost 的 ORDER BY id 一致）。 */
    private void givenStatusLog(long postId, String from, String to) {
        PostStatusLog row = new PostStatusLog();
        row.setId((long) logStore.size() + 1L);
        row.setPostId(postId);
        row.setFromStatus(from);
        row.setToStatus(to);
        row.setReason("造数");
        row.setCreatedAt(NOW.minusHours(2));
        logStore.add(row);
    }

    private List<PostStatusLog> filterLogs(long postId) {
        List<PostStatusLog> rows = new ArrayList<>();
        for (PostStatusLog row : logStore) {
            if (Long.valueOf(postId).equals(row.getPostId())) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static Post copyPost(Post src) {
        Post copy = new Post();
        copy.setId(src.getId());
        copy.setUserId(src.getUserId());
        copy.setTitle(src.getTitle());
        copy.setStatus(src.getStatus());
        return copy;
    }

    private static PostAppeal copyAppeal(PostAppeal src) {
        PostAppeal copy = new PostAppeal();
        copy.setId(src.getId());
        copy.setPostId(src.getPostId());
        copy.setUserId(src.getUserId());
        copy.setReason(src.getReason());
        copy.setStatus(src.getStatus());
        copy.setHandlerId(src.getHandlerId());
        copy.setHandledAt(src.getHandledAt());
        copy.setResultNote(src.getResultNote());
        copy.setDeleted(src.getDeleted());
        copy.setCreatedAt(src.getCreatedAt());
        copy.setUpdatedAt(src.getUpdatedAt());
        return copy;
    }

    private AdminOpLog lastOpLog() {
        return opLogInserts.get(opLogInserts.size() - 1);
    }

    private NotifyMessage lastNotify() {
        List<NotifyMessage> rows = recorder.rows();
        return rows.get(rows.size() - 1);
    }

    /** 按码点截断的正解：绝不允许留下半个代理对（真 MySQL 收到会直接报编码错，业务连带回滚）。 */
    private static void assertNoLoneSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                boolean paired = i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1));
                assertThat(paired).as("位 %d 是高代理但后面没有低代理", i).isTrue();
                i++;
            } else {
                assertThat(Character.isLowSurrogate(c)).as("位 %d 出现孤立低代理", i).isFalse();
            }
        }
    }

    // ================================================================ submit：四道门的顺序
    @Test
    @DisplayName("查无此帖与已彻底删除合并为 POST_NOT_FOUND，且不许碰申诉表（否则等于把处置历史挂在外面上）")
    void submitRejectsMissingPostBeforeAnyAppealLookup() {
        BizException e = assertThrows(BizException.class,
                () -> service.submit(999999L, AUTHOR, "我认为判定有误", NOW));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.POST_NOT_FOUND);

        givenPost(POST_ID, AUTHOR, "DELETED");
        assertThat(assertThrows(BizException.class,
                () -> service.submit(POST_ID, AUTHOR, "我认为判定有误", NOW)).getErrorCode())
                        .isEqualTo(ErrorCode.POST_NOT_FOUND);

        assertThat(countFinalCalls).isZero();
        assertThat(findPendingAppealCalls).isZero();
        assertThat(appealInserts).isEmpty();
    }

    @Test
    @DisplayName("「是不是你的帖子」排在状态门之前：两个门都命中的输入必须报 FORBIDDEN 而不是状态文案")
    void submitChecksOwnershipBeforeStatus() {
        givenPost(POST_ID, AUTHOR + 1, "PUBLISHED");
        countFinalReturn = 3;

        BizException e = assertThrows(BizException.class,
                () -> service.submit(POST_ID, AUTHOR, "帮朋友问问", NOW));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.POST_FORBIDDEN);
        assertThat(e.getMessage()).isEqualTo("只能申诉自己的帖子");
        assertThat(countFinalCalls).as("非本人绝不能探到申诉次数").isZero();
        assertThat(findPendingAppealCalls).isZero();
        assertThat(appealInserts).isEmpty();
    }

    @Test
    @DisplayName("状态门只放开 REJECTED / TAKEDOWN，报错要把当前状态念出来（管理员与作者看同一条文案）")
    void submitBlocksUnappealableStatus() {
        givenPost(POST_ID, AUTHOR, "MACHINE_REVIEW");

        BizException e = assertThrows(BizException.class,
                () -> service.submit(POST_ID, AUTHOR, "机器误判了", NOW));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(e.getMessage()).isEqualTo("当前状态不能申诉：MACHINE_REVIEW（只有被驳回或被下架的帖子可以）");
        assertThat(countFinalCalls).isZero();
        assertThat(appealInserts).isEmpty();
        assertThat(AppealService.APPEALABLE_STATUSES).containsExactlyInAnyOrder("REJECTED", "TAKEDOWN");
    }

    @Test
    @DisplayName("空理由不进库：报错发生在数历史之前")
    void submitRequiresReason() {
        givenPost(POST_ID, AUTHOR, AuditQueueService.POST_REJECTED);

        BizException e = assertThrows(BizException.class, () -> service.submit(POST_ID, AUTHOR, "   ", NOW));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(e.getMessage()).isEqualTo("申诉必须写清楚理由");
        assertThat(countFinalCalls).isZero();
        assertThat(appealInserts).isEmpty();
    }

    @Test
    @DisplayName("一次性申诉是硬约束：已有终态就拒绝，并且不再去查并行 PENDING")
    void submitBlocksSecondAppeal() {
        givenPost(POST_ID, AUTHOR, AuditQueueService.POST_TAKEDOWN);
        countFinalReturn = 1;

        BizException e = assertThrows(BizException.class, () -> service.submit(POST_ID, AUTHOR, "换个说法再诉一次", NOW));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(e.getMessage()).contains("只给一次申诉机会");
        assertThat(findPendingAppealCalls).as("终态门先于并行门，省掉一次无谓查询").isZero();
        assertThat(appealInserts).isEmpty();
    }

    @Test
    @DisplayName("同帖已有 PENDING 时挡住：uk_post_pending 冲突前先给人话提示")
    void submitBlocksParallelPending() {
        givenPost(POST_ID, AUTHOR, AuditQueueService.POST_REJECTED);
        findPendingReturn = givenAppeal(APPEAL_ID, POST_ID, AUTHOR, PostAppeal.STATUS_PENDING);

        BizException e = assertThrows(BizException.class, () -> service.submit(POST_ID, AUTHOR, "还没出结果我再补一句", NOW));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(e.getMessage()).isEqualTo("已经有一条申诉正在处理中，请耐心等待结果");
        assertThat(appealInserts).isEmpty();
    }
    // ================================================================ submit：过门之后
    @Test
    @DisplayName("申诉成功链路写三件事：PENDING 申诉行 + 帖子转 APPEALING + 状态留痕（留痕理由带「作者申诉|」前缀）")
    void submitWritesAppealAndStatusChain() {
        givenPost(POST_ID, AUTHOR, AuditQueueService.POST_REJECTED);

        AppealService.AppealView view = service.submit(POST_ID, AUTHOR, "  引用的是旧版规范，请复核  ", NOW);

        assertThat(appealInserts).hasSize(1);
        PostAppeal row = appealInserts.get(0);
        assertThat(row.getPostId()).isEqualTo(POST_ID);
        assertThat(row.getUserId()).isEqualTo(AUTHOR);
        assertThat(row.getStatus()).isEqualTo(PostAppeal.STATUS_PENDING);
        assertThat(row.getReason()).as("blankToNull 已 trim").isEqualTo("引用的是旧版规范，请复核");
        assertThat(row.getDeleted()).isZero();
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);

        assertThat(casCalls).hasSize(1);
        assertThat(casCalls.get(0)).containsExactly(POST_ID, "REJECTED", "APPEALING");
        assertThat(postStore.get(POST_ID).getStatus()).isEqualTo("APPEALING");

        assertThat(logInserts).hasSize(1);
        PostStatusLog log = logInserts.get(0);
        assertThat(log.getPostId()).isEqualTo(POST_ID);
        assertThat(log.getFromStatus()).isEqualTo("REJECTED");
        assertThat(log.getToStatus()).isEqualTo("APPEALING");
        assertThat(log.getOperatorId()).isEqualTo(AUTHOR);
        assertThat(log.getReason()).isEqualTo("作者申诉|引用的是旧版规范，请复核");
        assertThat(log.getCreatedAt()).isEqualTo(NOW);

        assertThat(view.postTitle()).isEqualTo("帖子-" + POST_ID);
        assertThat(view.postStatus()).isEqualTo(AppealService.POST_APPEALING);
    }

    @Test
    @DisplayName("人工下架的帖子走同一条救济通道：留痕的 from 是 TAKEDOWN 而不是统一写成 REJECTED")
    void submitAllowsTakedownPosts() {
        givenPost(POST_ID, AUTHOR, AuditQueueService.POST_TAKEDOWN);

        AppealService.AppealView view = service.submit(POST_ID, AUTHOR, "下架依据的是已废止的条款", NOW);

        assertThat(casCalls.get(0)).containsExactly(POST_ID, "TAKEDOWN", "APPEALING");
        assertThat(logInserts.get(0).getFromStatus()).isEqualTo("TAKEDOWN");
        assertThat(view.postStatus()).isEqualTo("APPEALING");
    }

    @Test
    @DisplayName("理由按码点截 500：600 个 emoji 必须留下完整 500 个，绝不能劈开代理对")
    void submitCutsReasonByCodePoints() {
        givenPost(POST_ID, AUTHOR, AuditQueueService.POST_REJECTED);
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            word.append("\uD83D\uDE42");
        }

        service.submit(POST_ID, AUTHOR, word.toString(), NOW);

        String reason = appealInserts.get(0).getReason();
        assertThat(reason.codePointCount(0, reason.length())).isEqualTo(AppealService.REASON_MAX);
        assertThat(reason.length()).as("500 个 emoji 占 1000 个 UTF-16 单元").isEqualTo(1_000);
        assertNoLoneSurrogate(reason);
        assertThat(logInserts.get(0).getReason()).isEqualTo("作者申诉|" + reason.substring(0, 200));
    }

    @Test
    @DisplayName("留痕理由只带前 100 个码点：整段长申诉不该把 post_status_log.reason(255) 撑爆")
    void submitTrimsStatusLogReasonToHundredCodePoints() {
        givenPost(POST_ID, AUTHOR, AuditQueueService.POST_REJECTED);
        StringBuilder word = new StringBuilder("申诉说明：");
        for (int i = 0; i < 300; i++) {
            word.append("理由");
        }

        service.submit(POST_ID, AUTHOR, word.toString(), NOW);

        String logged = logInserts.get(0).getReason();
        int prefix = "作者申诉|".codePointCount(0, "作者申诉|".length());
        assertThat(logged.codePointCount(0, logged.length())).isEqualTo(prefix + 100);
        assertThat(logged).startsWith("作者申诉|申诉说明：");
        assertThat(logged.codePointCount(0, logged.length()))
                .isLessThanOrEqualTo(AuditQueueService.STATUS_LOG_REASON_MAX);
    }

    @Test
    @DisplayName("CAS 抢输窗口期：申诉照常受理、不写留痕、返回体回帖子当前真实状态")
    void submitStillAcceptedWhenStatusRaceLost() {
        Post post = givenPost(POST_ID, AUTHOR, AuditQueueService.POST_REJECTED);
        onCas = () -> post.setStatus("PUBLISHED");

        AppealService.AppealView view = service.submit(POST_ID, AUTHOR, "同一时间管理员已经改判了", NOW);

        assertThat(appealInserts).as("申诉单是作者的救济，不能被状态机抢走").hasSize(1);
        assertThat(casCalls).hasSize(1);
        assertThat(casCalls.get(0)).containsExactly(POST_ID, "REJECTED", "APPEALING");
        assertThat(logInserts).as("没改成状态就不许留一条假流转").isEmpty();
        // 返回体里的状态是「自己读到的那一份」，抢输时不回查：申诉接口只保证申诉单成立，
        // 帖子真状态以队列表下次 SELECT 为准（A5 列表每次都会重读，不会停在旧值上）。
        assertThat(view.postStatus()).isEqualTo(AuditQueueService.POST_REJECTED);
    }

    // ================================================================ adjudicate：门与顺序
    @Test
    @DisplayName("没有登录身份不许裁定：ctx 为 null 或 operatorId 为 null 都先 401，一条 SQL 都不发")
    void adjudicateRequiresOperator() {
        givenAppeal(APPEAL_ID, POST_ID, AUTHOR, PostAppeal.STATUS_PENDING);

        BizException e = assertThrows(BizException.class, () -> service.adjudicate(APPEAL_ID, null, true, "复核成立", NOW));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(e.getMessage()).isEqualTo("管理端操作需要登录身份");

        Ctx anonymous = new Ctx(null, "ADMIN", "203.0.113.9", "Mozilla/5.0");
        assertThat(assertThrows(BizException.class,
                () -> service.adjudicate(APPEAL_ID, anonymous, true, "复核成立", NOW)).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED);

        assertThat(adjudicateCalls).isEmpty();
        assertThat(opLogInserts).isEmpty();
    }

    @Test
    @DisplayName("空说明先报错且不许查库：说明是回执原文，缺了就只能退回表单而不是硬裁")
    void adjudicateRequiresNote() {
        givenAppeal(APPEAL_ID, POST_ID, AUTHOR, PostAppeal.STATUS_PENDING);

        BizException e = assertThrows(BizException.class,
                () -> service.adjudicate(APPEAL_ID, ADMIN, false, "   ", NOW));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(e.getMessage()).contains("裁定必须填写说明");
        assertThat(appealSelectByIdCalls).as("连申诉行都不该去查").isZero();
        assertThat(adjudicateCalls).isEmpty();
        assertThat(opLogInserts).isEmpty();
    }

    @Test
    @DisplayName("查无此单与已软删合并为 404：不给「这条申诉存在但被删了」留探测面")
    void adjudicateRequiresExistingAppeal() {
        BizException e = assertThrows(BizException.class,
                () -> service.adjudicate(999999L, ADMIN, true, "复核成立", NOW));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(e.getMessage()).isEqualTo("申诉记录不存在：id=999999");

        PostAppeal gone = givenAppeal(APPEAL_ID, POST_ID, AUTHOR, PostAppeal.STATUS_PENDING);
        gone.setDeleted(1);
        assertThat(assertThrows(BizException.class,
                () -> service.adjudicate(APPEAL_ID, ADMIN, true, "复核成立", NOW)).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        assertThat(adjudicateCalls).isEmpty();
        assertThat(appealInserts).isEmpty();
    }

    @Test
    @DisplayName("重复裁定被 WHERE 挡下：留 DENIED 审计 + 403，且一次都不许动帖子状态")
    void adjudicateBlocksAlreadyHandled() {
        givenAppeal(APPEAL_ID, POST_ID, AUTHOR, PostAppeal.STATUS_ACCEPTED);
        givenPost(POST_ID, AUTHOR, PostAppeal.POST_RESTORE_STATUS);

        BizException e = assertThrows(BizException.class,
                () -> service.adjudicate(APPEAL_ID, ADMIN, false, "换个管理员再裁一次", NOW));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(e.getMessage()).isEqualTo("这条申诉已经被裁定过了，请刷新队列");

        AdminOpLog denied = lastOpLog();
        assertThat(denied.getAction()).isEqualTo(AdminOpLog.ACTION_APPEAL_HANDLE);
        assertThat(denied.getResult()).isEqualTo("DENIED");
        assertThat(denied.getTarget()).isEqualTo("appeal:" + APPEAL_ID);
        assertThat(denied.getTargetId()).isEqualTo(APPEAL_ID);
        assertThat(denied.getDetail()).isEqualTo("该申诉已办结：ACCEPTED");

        assertThat(casCalls).isEmpty();
        assertThat(recorder.size()).isZero();
    }
    private List<PostAppeal> filterAppeals(long postId) {
        List<PostAppeal> rows = new ArrayList<>();
        for (PostAppeal row : appealStore.values()) {
            if (Long.valueOf(postId).equals(row.getPostId())) {
                rows.add(copyAppeal(row));
            }
        }
        rows.sort((a, b) -> b.getId().compareTo(a.getId()));
        return rows;
    }

    // ================================================================ adjudicate：采纳
    @Test
    @DisplayName("采纳＝APPEALING → PUBLISHED：一行 UPDATE 里办完终态，五件事（终态/状态/留痕/回执/审计）各一次")
    void adjudicateAcceptRestoresPublished() {
        PostAppeal stored = givenAppeal(APPEAL_ID, POST_ID, AUTHOR, PostAppeal.STATUS_PENDING);
        givenPost(POST_ID, AUTHOR, AppealService.POST_APPEALING);

        AppealService.Adjudication r = service.adjudicate(APPEAL_ID, ADMIN, true, "复核成立，条款已废止", NOW);

        assertThat(adjudicateCalls.get(0)).containsExactly(APPEAL_ID, 77L, "ACCEPTED", "复核成立，条款已废止", NOW);
        assertThat(stored.getStatus()).isEqualTo(PostAppeal.STATUS_ACCEPTED);
        assertThat(stored.getHandlerId()).isEqualTo(77L);
        assertThat(stored.getResultNote()).isEqualTo("复核成立，条款已废止");
        assertThat(stored.getHandledAt()).isEqualTo(NOW);

        assertThat(casCalls).hasSize(1);
        assertThat(casCalls.get(0)).containsExactly(POST_ID, "APPEALING", PostAppeal.POST_RESTORE_STATUS);
        assertThat(stampCalls).as("采纳那一刻才是这条灰词帖真正的发布时刻").hasSize(1);
        assertThat(stampCalls.get(0)).containsExactly(POST_ID, NOW);
        assertThat(postStore.get(POST_ID).getPublishedAt())
                .as("只改状态不写发布时间＝状态说它上线了、读取侧当它没发布，D4 只完成一半").isEqualTo(NOW);
        assertThat(logInserts).hasSize(1);
        assertThat(logInserts.get(0).getReason()).isEqualTo("申诉采纳|复核成立，条款已废止");
        assertThat(logInserts.get(0).getOperatorId()).isEqualTo(77L);

        assertThat(r.appealStatus()).isEqualTo("ACCEPTED");
        assertThat(r.postStatus()).isEqualTo("PUBLISHED");
        assertThat(r.postChanged()).isTrue();
        assertThat(r.opLogId()).isEqualTo(1L);
        assertThat(r.appealId()).isEqualTo(APPEAL_ID);
        assertThat(r.postId()).isEqualTo(POST_ID);

        assertThat(recorder.size()).isEqualTo(1);
        NotifyMessage notice = lastNotify();
        assertThat(notice.getUserId()).isEqualTo(AUTHOR);
        assertThat(notice.getType()).isEqualTo(NotifyMessage.TYPE_AUDIT);
        assertThat(notice.getRefType()).isEqualTo(NotifyMessage.REF_POST);
        assertThat(notice.getRefId()).isEqualTo(POST_ID);
        assertThat(notice.getTitle()).isEqualTo("你的申诉已通过");
        assertThat(notice.getContent()).contains("复核成立，条款已废止").contains("内容已恢复可见");

        assertThat(lastOpLog().getResult()).isEqualTo("SUCCESS");
        assertThat(lastOpLog().getDetail()).isEqualTo(
                "post=501|result=ACCEPTED|帖子=PUBLISHED|改状态=true|说明=复核成立，条款已废止");
    }

    @Test
    @DisplayName("驳回不许顺手把发布时间清掉，也不许给一条从没发布过的 REJECTED 帖补发布时间")
    void adjudicateRejectLeavesPublishedAtAlone() {
        givenAppeal(APPEAL_ID, POST_ID, AUTHOR, PostAppeal.STATUS_PENDING);
        givenPost(POST_ID, AUTHOR, AppealService.POST_APPEALING).setPublishedAt(NOW.minusDays(3));

        AppealService.Adjudication r = service.adjudicate(APPEAL_ID, ADMIN, false, "维持原判", NOW);

        assertThat(r.postStatus()).isEqualTo("REJECTED");
        assertThat(stampCalls).as("驳回这条分支一次都不许碰发布时间").isEmpty();
        assertThat(postStore.get(POST_ID).getPublishedAt())
                .as("第一次发布的时刻是历史事实，驳回不改写").isEqualTo(NOW.minusDays(3));
    }

    @Test
    @DisplayName("采纳但发布时间早就写过：闸门挡住第二次写入，发布时间不许被挪到这次裁定上")
    void adjudicateAcceptDoesNotMoveExistingPublishedAt() {
        givenAppeal(APPEAL_ID, POST_ID, AUTHOR, PostAppeal.STATUS_PENDING);
        givenPost(POST_ID, AUTHOR, AppealService.POST_APPEALING).setPublishedAt(NOW.minusDays(3));

        AppealService.Adjudication r = service.adjudicate(APPEAL_ID, ADMIN, true, "复核成立", NOW);

        assertThat(r.postChanged()).isTrue();
        assertThat(stampCalls).as("UPDATE 照样发出去，幂等靠 SQL 里的 published_at IS NULL 兜").hasSize(1);
        assertThat(postStore.get(POST_ID).getPublishedAt())
                .as("发布时间是第一次发布那一刻，不是第二次点通过这一刻").isEqualTo(NOW.minusDays(3));
    }

    @Test
    @DisplayName("驳回回的是留痕尾部那条 APPEALING 的 from：TAKEDOWN 与 REJECTED 两种前状态不能被合并成一个")
    void adjudicateRejectReturnsToLatestPreAppealStatus() {
        givenAppeal(APPEAL_ID, POST_ID, AUTHOR, PostAppeal.STATUS_PENDING);
        givenPost(POST_ID, AUTHOR, AppealService.POST_APPEALING);
        givenStatusLog(POST_ID, AuditQueueService.POST_TAKEDOWN, AppealService.POST_APPEALING);
        givenStatusLog(POST_ID, AuditQueueService.POST_REJECTED, AppealService.POST_APPEALING);

        AppealService.Adjudication r = service.adjudicate(APPEAL_ID, ADMIN, false, "维持原判", NOW);

        assertThat(logLookupCalls).as("只有驳回才需要回溯，采纳不许多扫一次留痕").isEqualTo(1);
        assertThat(casCalls.get(0)).containsExactly(POST_ID, "APPEALING", "REJECTED");
        assertThat(r.postStatus()).isEqualTo("REJECTED");
        assertThat(logInserts.get(0).getReason()).isEqualTo("申诉驳回|维持原判");
        assertThat(lastNotify().getTitle()).isEqualTo("你的申诉未通过");
        assertThat(lastNotify().getContent()).contains("这是本次申诉的最终结论");
        assertThat(stampCalls).as("驳回不许写发布时间：它根本没上架，凭什么有发布时刻").isEmpty();
        assertThat(lastOpLog().getDetail()).contains("result=REJECTED|帖子=REJECTED|改状态=true");
    }

    @Test
    @DisplayName("尾部往头扫时跳过 from 为空的留痕：脏数据不能把驳回目标改成 null")
    void adjudicateRejectSkipsNullFromLog() {
        givenAppeal(APPEAL_ID, POST_ID, AUTHOR, PostAppeal.STATUS_PENDING);
        givenPost(POST_ID, AUTHOR, AppealService.POST_APPEALING);
        givenStatusLog(POST_ID, AuditQueueService.POST_REJECTED, AppealService.POST_APPEALING);
        givenStatusLog(POST_ID, null, AppealService.POST_APPEALING);

        AppealService.Adjudication r = service.adjudicate(APPEAL_ID, ADMIN, false, "第二次进入申诉但没记前状态", NOW);

        assertThat(r.postStatus()).isEqualTo("REJECTED");
        assertThat(casCalls.get(0)).containsExactly(POST_ID, "APPEALING", "REJECTED");
    }

    @Test
    @DisplayName("留痕查不到时回落 REJECTED（宁严不松），而不是把申诉中的帖子直接放回公开列表")
    void adjudicateRejectFallsBackToRejected() {
        givenAppeal(APPEAL_ID, POST_ID, AUTHOR, PostAppeal.STATUS_PENDING);
        givenPost(POST_ID, AUTHOR, AppealService.POST_APPEALING);

        AppealService.Adjudication r = service.adjudicate(APPEAL_ID, ADMIN, false, "申诉理由不成立", NOW);

        assertThat(r.postStatus()).isEqualTo(AuditQueueService.POST_REJECTED);
        assertThat(casCalls.get(0)).containsExactly(POST_ID, "APPEALING", "REJECTED");
        assertThat(postStore.get(POST_ID).getStatus()).isEqualTo("REJECTED");
    }

    // ================================================================ adjudicate：帖子那边出岔子
    @Test
    @DisplayName("帖子早被别的通道改成 PUBLISHED：驳回不动它（目标＝当前状态），申诉照样办结")
    void adjudicateSkipsCasWhenTargetEqualsCurrent() {
        givenAppeal(APPEAL_ID, POST_ID, AUTHOR, PostAppeal.STATUS_PENDING);
        givenPost(POST_ID, AUTHOR, PostAppeal.POST_RESTORE_STATUS);

        AppealService.Adjudication r = service.adjudicate(APPEAL_ID, ADMIN, false, "已经由审核通道恢复", NOW);

        assertThat(casCalls).as("from==to 不许发 UPDATE").isEmpty();
        assertThat(logInserts).isEmpty();
        assertThat(r.postStatus()).isEqualTo("PUBLISHED");
        assertThat(r.postChanged()).isFalse();
        assertThat(recorder.size()).isEqualTo(1);
        assertThat(lastOpLog().getDetail()).contains("帖子=PUBLISHED|改状态=false");
    }

    @Test
    @DisplayName("采纳时 CAS 抢输：终态/回执/审计照落，只有状态留痕不写，postStatus 回目标状态供界面提示")
    void adjudicateAcceptLosingCas() {
        Post post = givenPost(POST_ID, AUTHOR, AppealService.POST_APPEALING);
        givenAppeal(APPEAL_ID, POST_ID, AUTHOR, PostAppeal.STATUS_PENDING);
        onCas = () -> post.setStatus(AuditQueueService.POST_TAKEDOWN);

        AppealService.Adjudication r = service.adjudicate(APPEAL_ID, ADMIN, true, "复核成立", NOW);

        assertThat(appealStore.get(APPEAL_ID).getStatus()).isEqualTo(PostAppeal.STATUS_ACCEPTED);
        assertThat(casCalls).hasSize(1);
        assertThat(logInserts).isEmpty();
        assertThat(recorder.size()).isEqualTo(1);
        assertThat(r.postChanged()).isFalse();
        assertThat(r.postStatus()).isEqualTo("PUBLISHED");
        assertThat(lastOpLog().getDetail()).contains("改状态=false");
    }

    @Test
    @DisplayName("帖子已被物理清理：查不到 post 就当没有状态可改，回执与审计仍要发出去")
    void adjudicateToleratesMissingPost() {
        givenAppeal(APPEAL_ID, 777L, AUTHOR, PostAppeal.STATUS_PENDING);

        AppealService.Adjudication r = service.adjudicate(APPEAL_ID, ADMIN, false, "原帖已清除", NOW);

        assertThat(casCalls).isEmpty();
        assertThat(logInserts).isEmpty();
        assertThat(r.postId()).isEqualTo(777L);
        assertThat(r.postStatus()).isNull();
        assertThat(r.postChanged()).isFalse();
        assertThat(recorder.size()).isEqualTo(1);
        assertThat(lastNotify().getRefId()).isEqualTo(777L);
        assertThat(lastOpLog().getDetail()).isEqualTo("post=777|result=REJECTED|帖子=null|改状态=false|说明=原帖已清除");
    }

    @Test
    @DisplayName("没有作者 id 的申诉单不发消息：回执口不允许对着 null 用户写通知")
    void adjudicateSkipsNotifyWithoutAuthor() {
        givenAppeal(APPEAL_ID, POST_ID, null, PostAppeal.STATUS_PENDING);
        givenPost(POST_ID, AUTHOR, AppealService.POST_APPEALING);

        AppealService.Adjudication r = service.adjudicate(APPEAL_ID, ADMIN, true, "复核成立", NOW);

        assertThat(recorder.size()).isZero();
        assertThat(r.postChanged()).isTrue();
        assertThat(lastOpLog().getResult()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("说明落库按码点截 500、审计里只留 100：回执与留痕两个口径必须同时成立")
    void adjudicateCutsNoteForBothDestinations() {
        givenAppeal(APPEAL_ID, POST_ID, AUTHOR, PostAppeal.STATUS_PENDING);
        givenPost(POST_ID, AUTHOR, AppealService.POST_APPEALING);
        StringBuilder note = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            note.append("说明").append("\uD83D\uDE42");
        }

        service.adjudicate(APPEAL_ID, ADMIN, true, note.toString(), NOW);

        String stored = (String) adjudicateCalls.get(0)[3];
        assertThat(stored.codePointCount(0, stored.length())).isEqualTo(AppealService.NOTE_MAX);
        assertNoLoneSurrogate(stored);
        String first100 = note.substring(0, note.offsetByCodePoints(0, 100));
        assertThat(lastOpLog().getDetail()).endsWith("|说明=" + first100);
    }
    // ================================================================ 队列与只读口
    private static PostAppeal queueRow(long id, Long postId) {
        PostAppeal row = new PostAppeal();
        row.setId(id);
        row.setPostId(postId);
        row.setUserId(AUTHOR);
        row.setStatus(PostAppeal.STATUS_PENDING);
        row.setReason("理由");
        row.setDeleted(0);
        return row;
    }

    @Test
    @DisplayName("队列筛错状态值当场报错且一条 SQL 都不发：静默返回空列表会被读成「今天没人申诉」")
    void pageRejectsUnknownStatus() {
        BizException e = assertThrows(BizException.class, () -> service.page("DONE", new PageQuery()));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(e.getMessage()).isEqualTo("申诉状态只能是 ACCEPTED/PENDING/REJECTED");
        assertThat(countCalls).isZero();
        assertThat(pageCalls).isZero();
    }

    @Test
    @DisplayName("空与不传等于「看全量」：filter 必须以 null 传给 count 与 list 两条 SQL（口径不能一条带一条不带）")
    void pageTreatsBlankStatusAsNoFilter() {
        pageTotal = 137L;
        pageRows = List.of(queueRow(5L, POST_ID));

        PageResult<AppealService.AppealView> page = service.page("   ", new PageQuery());

        assertThat(lastCountFilter).isNull();
        assertThat(lastPageFilter).isNull();
        assertThat(countCalls).isEqualTo(1);
        assertThat(pageCalls).isEqualTo(1);
        assertThat(page.getTotal()).isEqualTo(137L);
        assertThat(page.getList()).hasSize(1);
        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(PageQuery.DEFAULT_SIZE);
    }

    @Test
    @DisplayName("分页参数交给 PageQuery 夹取：page=3/size=80 变成 offset 60 + size 50，绝不让前端把队列拖全表")
    void pagePassesNormalizedOffsetAndSize() {
        PageQuery query = new PageQuery();
        query.setPage(3);
        query.setSize(80);
        pageRows = List.of();

        service.page(PostAppeal.STATUS_PENDING, query);

        assertThat(lastPageFilter).isEqualTo(PostAppeal.STATUS_PENDING);
        assertThat(lastCountFilter).isEqualTo(PostAppeal.STATUS_PENDING);
        // offset 必须用「夹过的 size」算：夹取发生在 normalize()，而 offset() 会再 normalize 一次，
        // 所以 page=3/size=80 ⇒ offset=(3-1)*50=100。若按 80 算就会留下 50 行的跳页空洞。
        assertThat(lastOffset).isEqualTo(100L);
        assertThat(lastSize).isEqualTo(PageQuery.MAX_SIZE);
        assertThat(query.getSize()).as("夹取要回写调用方持有的对象，前端下一页才能拿到同一口径").isEqualTo(PageQuery.MAX_SIZE);
    }

    @Test
    @DisplayName("行内 postId 为空或帖子查不到时标题与状态留空：队列不能因为一条脏行整页 500")
    void pageMapsMissingPostToNullTitleAndStatus() {
        givenPost(POST_ID, AUTHOR, AppealService.POST_APPEALING);
        pageRows = List.of(queueRow(1L, null), queueRow(2L, 999999L), queueRow(3L, POST_ID));
        pageTotal = 3L;

        PageResult<AppealService.AppealView> page = service.page(PostAppeal.STATUS_PENDING, new PageQuery());

        assertThat(page.getList()).hasSize(3);
        assertThat(page.getList().get(0).postTitle()).isNull();
        assertThat(page.getList().get(0).postStatus()).isNull();
        assertThat(page.getList().get(1).postTitle()).isNull();
        assertThat(page.getList().get(2).postTitle()).isEqualTo("帖子-" + POST_ID);
        assertThat(page.getList().get(2).postStatus()).isEqualTo(AppealService.POST_APPEALING);
    }

    @Test
    @DisplayName("A3 工作台红点只数 PENDING：把三个状态混着数会让红点永远不归零")
    void pendingCountCountsOnlyPending() {
        pageTotal = 4L;

        assertThat(service.pendingCount()).isEqualTo(4L);
        assertThat(lastCountFilter).isEqualTo(PostAppeal.STATUS_PENDING);
        assertThat(countCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("申诉史直接委托 listByPost（FR7.6 可回溯）：Java 侧不再补排序，顺序由 SQL 负责")
    void historyDelegatesToMapper() {
        givenAppeal(APPEAL_ID, POST_ID, AUTHOR, PostAppeal.STATUS_ACCEPTED);
        appealStore.put(APPEAL_ID + 1, givenAppeal(APPEAL_ID + 1, POST_ID, AUTHOR, PostAppeal.STATUS_PENDING));
        givenAppeal(APPEAL_ID + 2, POST_ID + 1, AUTHOR, PostAppeal.STATUS_PENDING);

        List<PostAppeal> history = service.historyOfPost(POST_ID);

        assertThat(history).extracting(PostAppeal::getId).containsExactly(APPEAL_ID + 1, APPEAL_ID);
        assertThat(appealHistoryCalls).hasSize(1);
        assertThat(appealHistoryCalls.get(0)).containsExactly((Object) POST_ID);
    }

    @Test
    @DisplayName("常量口径钉住：可申诉集合、APPEALING、恢复目标与两处 500 上限不许悄悄漂移")
    void pinsStateMachineConstants() {
        assertThat(AppealService.APPEALABLE_STATUSES).containsExactlyInAnyOrder("REJECTED", "TAKEDOWN");
        assertThat(AppealService.POST_APPEALING).isEqualTo("APPEALING");
        assertThat(AppealService.APPEAL_STATUSES).containsExactlyInAnyOrder("PENDING", "ACCEPTED", "REJECTED");
        assertThat(PostAppeal.POST_RESTORE_STATUS).isEqualTo("PUBLISHED");
        assertThat(AppealService.REASON_MAX).isEqualTo(500);
        assertThat(AppealService.NOTE_MAX).isEqualTo(500);
        assertThat(AuditQueueService.STATUS_LOG_REASON_MAX).isEqualTo(255);
    }
}
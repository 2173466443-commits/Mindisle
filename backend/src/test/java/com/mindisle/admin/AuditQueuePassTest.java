package com.mindisle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mindisle.admin.AdminOpLogService.Ctx;
import com.mindisle.audit.SensitiveWordEngine;
import com.mindisle.common.BizException;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.AdminOpLog;
import com.mindisle.entity.AuditRecord;
import com.mindisle.entity.AuditTask;
import com.mindisle.entity.NotifyMessage;
import com.mindisle.entity.Post;
import com.mindisle.entity.PostStatusLog;
import com.mindisle.mapper.AdminOpLogMapper;
import com.mindisle.mapper.AuditRecordMapper;
import com.mindisle.mapper.AuditTaskMapper;
import com.mindisle.mapper.PostImageMapper;
import com.mindisle.mapper.PostMapper;
import com.mindisle.mapper.PostStatusLogMapper;
import com.mindisle.mapper.UserMapper;
import com.mindisle.notify.NotifyService;
import com.mindisle.notify.RecordingNotifyService;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * 人审裁决单测（任务 T6.6 · 需求 FR4.4 FR7.3 FR7.5 · 手册 §9.1 第 3 条与 §9.4 D4）。
 *
 * <p><b>本类为什么必须存在</b>：Gate6 判据 D4「人审通过后多久别人能读到」在 v1.3.0 之前只断言了
 * 状态码，于是「人审通过＝已发布」是一个假成功——{@code compareAndSetStatus} 只改 status，
 * 灰词帖（MACHINE_REVIEW → HUMAN_REVIEW → PUBLISHED）通过之后带着 {@code published_at = NULL}
 * 上线，而三个读取方全按发布时间说话：广场游标 {@code order by published_at desc} 在 MySQL
 * DESC 语义下把 NULL 排在最后，推荐新帖池 {@code published_at >= #{since}} 对 NULL 恒不成立，
 * 热池排序同样把它压到末尾。<b>状态机说它上线了，读取侧一致认定它没发布。</b></p>
 *
 * <p><b>本类钉住的六条</b>：①通过给「从没发布过」的帖子写下发布时刻，且这个时刻就是裁决那一刻；
 * ②驳回一次都不许碰发布时间；③图片抽审那条分支（本来就 PUBLISHED）连 CAS 都不许发出去，
 * 发布时间保持原值——第一次发布是历史事实，不是第二次点击；④存量脏数据（带着旧发布时间的
 * HUMAN_REVIEW 帖）由 SQL 里的 {@code published_at IS NULL} 闸门挡住第二次写入，回填标记回
 * false，而不是「看起来做了其实没做」；⑤那条闸门写在 SQL 里而不是 Java 里判一下，
 * 所以用反射把 {@code @Update} 原文钉住，防止后人把它「简化」成无条件 UPDATE；
 * ⑥裁决权只属于受理人，隔手改单时留痕/状态/通知/审计四件事一件都不许发生。</p>
 *
 * <p><b>替身契约</b>与 {@link AppealServiceTest} 同源：{@code taskMapper.adjudicate} 按真 SQL 的
 * {@code WHERE status='PROCESSING' AND assignee_id=?} 决定影响行数，改不动就返回 0，
 * 于是「不允许两人审同一条」不需要布尔开关伪造，由假库的行状态自然产生；
 * {@code stampPublishedAt} 复刻 {@code AND status='PUBLISHED' AND published_at IS NULL}。
 * {@code postImageMapper}/{@code userMapper} 刻意不打桩——裁决路径不碰它们，碰了立刻
 * {@link UnsupportedOperationException}，这是「人审裁决不越界读图读人」的反向证明。</p>
 */
class AuditQueuePassTest {

    private static final Ctx ADMIN = new Ctx(77L, "SUPER", "203.0.113.9", "Mozilla/5.0 (Gate6-AuditPass)");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 10, 30);
    private static final long TASK_ID = 4100L;
    private static final long POST_ID = 1302L;
    private static final long AUTHOR = 506L;

    // ================================================================ 假库
    private final Map<Long, AuditTask> taskStore = new LinkedHashMap<>();
    private final Map<Long, Post> postStore = new LinkedHashMap<>();

    // ================================================================ 观察点
    private final List<Object[]> adjudicateCalls = new ArrayList<>();
    private final List<Object[]> casCalls = new ArrayList<>();
    /** postMapper.stampPublishedAt 的调用记录：本轮真 bug 就藏在这个口子的有无上。 */
    private final List<Object[]> stampCalls = new ArrayList<>();
    private final List<AuditRecord> recordInserts = new ArrayList<>();
    private final List<PostStatusLog> logInserts = new ArrayList<>();
    private final List<AdminOpLog> opLogInserts = new ArrayList<>();

    private SensitiveWordEngine engine;
    private RecordingNotifyService recorder;
    private AuditQueueService service;

    @BeforeEach
    void setUp() {
        // Proxy 不能代理类，SensitiveWordEngine 又只在 humanRecord 里被读一个 version()，
        // 所以这里挂真词库（与 SafetyGuardTest 同一份 v0.1 快照），留痕版本才有对拍价值。
        engine = new SensitiveWordEngine(new MindisleProperties(), new DefaultResourceLoader());
        engine.afterPropertiesSet();
        assertThat(engine.version()).as("必须加载到线上那份 v0.1 词库，否则留痕版本断言没有意义")
                .isEqualTo("v0.1");

        AuditTaskMapper taskMapper = (AuditTaskMapper) Proxy.newProxyInstance(
                AuditTaskMapper.class.getClassLoader(), new Class<?>[] {AuditTaskMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectById" -> {
                        AuditTask found = taskStore.get(((Number) args[0]).longValue());
                        yield found == null ? null : copyTask(found);
                    }
                    case "adjudicate" -> {
                        adjudicateCalls.add(args);
                        AuditTask row = taskStore.get(((Number) args[0]).longValue());
                        // UPDATE ... WHERE id=? AND status='PROCESSING' AND assignee_id=? AND deleted=0
                        if (row != null && AuditQueueService.STATUS_PROCESSING.equals(row.getStatus())
                                && Long.valueOf(((Number) args[1]).longValue()).equals(row.getAssigneeId())
                                && !Integer.valueOf(1).equals(row.getDeleted())) {
                            row.setStatus((String) args[2]);
                            row.setRemark((String) args[3]);
                            row.setUpdatedAt((LocalDateTime) args[4]);
                            yield 1;
                        }
                        yield 0;
                    }
                    default -> throw new UnsupportedOperationException("未打桩：" + method.getName());
                });

        AuditRecordMapper recordMapper = (AuditRecordMapper) Proxy.newProxyInstance(
                AuditRecordMapper.class.getClassLoader(), new Class<?>[] {AuditRecordMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "insert" -> {
                        AuditRecord row = (AuditRecord) args[0];
                        row.setId((long) recordInserts.size() + 1L);
                        recordInserts.add(row);
                        yield 1;
                    }
                    default -> throw new UnsupportedOperationException("未打桩：" + method.getName());
                });

        PostMapper postMapper = (PostMapper) Proxy.newProxyInstance(
                PostMapper.class.getClassLoader(), new Class<?>[] {PostMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectById" -> {
                        Post found = postStore.get(((Number) args[0]).longValue());
                        yield found == null ? null : copyPost(found);
                    }
                    case "compareAndSetStatus" -> {
                        casCalls.add(args);
                        Post post = postStore.get(((Number) args[0]).longValue());
                        // UPDATE post SET status=? WHERE id=? AND status=? AND deleted=0
                        if (post != null && String.valueOf(args[1]).equals(post.getStatus())) {
                            post.setStatus((String) args[2]);
                            yield 1;
                        }
                        yield 0;
                    }
                    case "stampPublishedAt" -> {
                        stampCalls.add(args);
                        Post target = postStore.get(((Number) args[0]).longValue());
                        // UPDATE ... WHERE id=? AND status='PUBLISHED' AND published_at IS NULL AND deleted=0
                        if (target != null && "PUBLISHED".equals(target.getStatus())
                                && target.getPublishedAt() == null) {
                            target.setPublishedAt((LocalDateTime) args[1]);
                            yield 1;
                        }
                        yield 0;
                    }
                    default -> throw new UnsupportedOperationException("未打桩：" + method.getName());
                });

        PostImageMapper postImageMapper = (PostImageMapper) Proxy.newProxyInstance(
                PostImageMapper.class.getClassLoader(), new Class<?>[] {PostImageMapper.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("人审裁决不该读图片表：" + method.getName());
                });

        UserMapper userMapper = (UserMapper) Proxy.newProxyInstance(
                UserMapper.class.getClassLoader(), new Class<?>[] {UserMapper.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("人审裁决不该读用户表：" + method.getName());
                });

        PostStatusLogMapper statusLogMapper = (PostStatusLogMapper) Proxy.newProxyInstance(
                PostStatusLogMapper.class.getClassLoader(), new Class<?>[] {PostStatusLogMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "insert" -> {
                        PostStatusLog row = (PostStatusLog) args[0];
                        row.setId((long) logInserts.size() + 1L);
                        logInserts.add(row);
                        yield 1;
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
        service = new AuditQueueService(taskMapper, recordMapper, postMapper, postImageMapper,
                statusLogMapper, userMapper, engine, notifyService,
                new AdminOpLogService(opLogMapper), new MindisleProperties());
    }

    // ================================================================ 造数据小工具
    /** 建一条「已被当前账号领取」的任务：裁决入口只认 PROCESSING + assignee 是自己。 */
    private AuditTask givenTask() {
        return givenTaskAssignedTo(77L);
    }

    private AuditTask givenTaskAssignedTo(long assigneeId) {
        AuditTask task = new AuditTask();
        task.setId(TASK_ID);
        task.setTargetType(AuditQueueService.TARGET_POST);
        task.setTargetId(POST_ID);
        task.setSource(AuditTask.SOURCE_MACHINE);
        task.setChannel(AuditTask.CHANNEL_DFA);
        task.setResult(AuditRecord.DECISION_REVIEW);
        task.setRiskLevel(AuditQueueService.LEVEL_L2);
        task.setAssigneeId(assigneeId);
        task.setStatus(AuditQueueService.STATUS_PROCESSING);
        task.setDeleted(0);
        task.setCreatedAt(NOW.minusMinutes(3));
        task.setUpdatedAt(NOW.minusMinutes(3));
        taskStore.put(TASK_ID, task);
        return task;
    }

    private Post givenPost(String status, LocalDateTime publishedAt) {
        Post post = new Post();
        post.setId(POST_ID);
        post.setUserId(AUTHOR);
        post.setTitle("帖子-" + POST_ID);
        post.setStatus(status);
        post.setPublishedAt(publishedAt);
        postStore.put(POST_ID, post);
        return post;
    }

    private static Post copyPost(Post src) {
        Post copy = new Post();
        copy.setId(src.getId());
        copy.setUserId(src.getUserId());
        copy.setTitle(src.getTitle());
        copy.setStatus(src.getStatus());
        copy.setPublishedAt(src.getPublishedAt());
        return copy;
    }

    private static AuditTask copyTask(AuditTask src) {
        AuditTask copy = new AuditTask();
        copy.setId(src.getId());
        copy.setTargetType(src.getTargetType());
        copy.setTargetId(src.getTargetId());
        copy.setSource(src.getSource());
        copy.setChannel(src.getChannel());
        copy.setResult(src.getResult());
        copy.setRiskLevel(src.getRiskLevel());
        copy.setAssigneeId(src.getAssigneeId());
        copy.setStatus(src.getStatus());
        copy.setRemark(src.getRemark());
        copy.setDeleted(src.getDeleted());
        copy.setCreatedAt(src.getCreatedAt());
        copy.setUpdatedAt(src.getUpdatedAt());
        return copy;
    }

    private AdminOpLog lastOpLog() {
        return opLogInserts.get(opLogInserts.size() - 1);
    }

    // ================================================================ 用例
    @Test
    @DisplayName("人审通过灰词帖：状态改 PUBLISHED 的同一条裁决里补写发布时刻，回填标记与审计 detail 一起说真话")
    void humanPassStampsPublishedAtForNeverPublishedPost() {
        AuditTask task = givenTask();
        Post stored = givenPost(AuditQueueService.POST_HUMAN_REVIEW, null);

        AuditQueueService.Adjudication r =
                service.adjudicate(TASK_ID, ADMIN, true, "复核未发现风险", NOW);

        assertThat(adjudicateCalls).hasSize(1);
        assertThat(adjudicateCalls.get(0)).containsExactly(TASK_ID, 77L, "PASSED", "复核未发现风险", NOW);
        assertThat(task.getStatus()).isEqualTo("PASSED");
        assertThat(task.getUpdatedAt()).isEqualTo(NOW);

        assertThat(casCalls).hasSize(1);
        assertThat(casCalls.get(0)).containsExactly(POST_ID, "HUMAN_REVIEW", "PUBLISHED");
        assertThat(stampCalls).as("灰词帖的发布时刻就是人审放行这一刻").hasSize(1);
        assertThat(stampCalls.get(0)).containsExactly(POST_ID, NOW);
        assertThat(stored.getStatus()).isEqualTo("PUBLISHED");
        assertThat(stored.getPublishedAt())
                .as("只改状态不写发布时间＝状态说它上线了、三个读取方当它没发布，D4 只完成一半")
                .isEqualTo(NOW);

        assertThat(r.taskId()).isEqualTo(TASK_ID);
        assertThat(r.taskStatus()).isEqualTo("PASSED");
        assertThat(r.postId()).isEqualTo(POST_ID);
        assertThat(r.postStatus()).isEqualTo("PUBLISHED");
        assertThat(r.postStatusChanged()).isTrue();
        assertThat(r.publishedAtStamped()).isTrue();

        assertThat(recordInserts).hasSize(1);
        AuditRecord record = recordInserts.get(0);
        assertThat(record.getTaskId()).isEqualTo(TASK_ID);
        assertThat(record.getTargetType()).isEqualTo(AuditQueueService.TARGET_POST);
        assertThat(record.getTargetId()).isEqualTo(POST_ID);
        assertThat(record.getChannel()).isEqualTo(AuditTask.CHANNEL_DFA);
        assertThat(record.getDecision()).isEqualTo(AuditRecord.DECISION_HUMAN_PASS);
        assertThat(record.getEngineVersion()).isEqualTo(AuditQueueService.ENGINE_VERSION);
        assertThat(record.getWordlibVersion()).isEqualTo(engine.version());
        assertThat(record.getOperatorId()).isEqualTo(77L);
        assertThat(record.getReason()).isEqualTo("人审通过：复核未发现风险");

        assertThat(logInserts).hasSize(1);
        assertThat(logInserts.get(0).getFromStatus()).isEqualTo("HUMAN_REVIEW");
        assertThat(logInserts.get(0).getToStatus()).isEqualTo("PUBLISHED");
        assertThat(logInserts.get(0).getOperatorId()).isEqualTo(77L);
        assertThat(logInserts.get(0).getReason()).isEqualTo("人审|通过|复核未发现风险");

        assertThat(recorder.size()).isEqualTo(1);
        NotifyMessage notice = recorder.rows().get(0);
        assertThat(notice.getUserId()).isEqualTo(AUTHOR);
        assertThat(notice.getType()).isEqualTo(NotifyMessage.TYPE_AUDIT);
        assertThat(notice.getRefId()).isEqualTo(POST_ID);
        assertThat(notice.getTitle()).isEqualTo("你的帖子已通过审核");

        assertThat(opLogInserts).hasSize(1);
        assertThat(lastOpLog().getAction()).isEqualTo(AdminOpLog.ACTION_AUDIT_PASS);
        assertThat(lastOpLog().getResult()).isEqualTo("SUCCESS");
        assertThat(lastOpLog().getTarget()).isEqualTo("audit_task:" + TASK_ID);
        assertThat(lastOpLog().getDetail())
                .as("审计里必须能读出「这次到底有没有把发布时间补上」，不许留模糊地带")
                .isEqualTo("post=1302|改状态=true|回填发布时间=true|理由=复核未发现风险");
    }

    @Test
    @DisplayName("人审驳回：一次都不许碰发布时间，回填标记 false")
    void humanRejectNeverTouchesPublishedAt() {
        givenTask();
        Post stored = givenPost(AuditQueueService.POST_HUMAN_REVIEW, null);

        AuditQueueService.Adjudication r =
                service.adjudicate(TASK_ID, ADMIN, false, "含具体自伤方法", NOW);

        assertThat(stampCalls).as("驳回这条分支不许顺手写发布时间").isEmpty();
        assertThat(casCalls.get(0)).containsExactly(POST_ID, "HUMAN_REVIEW", "REJECTED");
        assertThat(stored.getStatus()).isEqualTo("REJECTED");
        assertThat(stored.getPublishedAt()).as("从没发布过的帖子，驳回之后仍然没有发布时刻").isNull();

        assertThat(r.postStatus()).isEqualTo("REJECTED");
        assertThat(r.postStatusChanged()).isTrue();
        assertThat(r.publishedAtStamped()).isFalse();

        assertThat(recordInserts.get(0).getDecision()).isEqualTo(AuditRecord.DECISION_HUMAN_REJECT);
        assertThat(recorder.rows().get(0).getTitle()).isEqualTo("你的帖子未通过审核");
        assertThat(recorder.rows().get(0).getContent()).contains("申诉");
        assertThat(lastOpLog().getAction()).isEqualTo(AdminOpLog.ACTION_AUDIT_REJECT);
        assertThat(lastOpLog().getDetail())
                .isEqualTo("post=1302|改状态=true|回填发布时间=false|理由=含具体自伤方法");
    }

    @Test
    @DisplayName("图片抽审帖本来就是 PUBLISHED：通过时连 CAS 都不许发出去，发布时间保持第一次那一刻")
    void imageSpotCheckPassTouchesNeitherStatusNorPublishedAt() {
        givenTask();
        LocalDateTime first = NOW.minusDays(5);
        Post stored = givenPost(AuditQueueService.POST_PUBLISHED, first);

        AuditQueueService.Adjudication r =
                service.adjudicate(TASK_ID, ADMIN, true, "图与文一致，无违规", NOW);

        assertThat(casCalls).as("from 与 target 都是 PUBLISHED，发 UPDATE 就是白写一次还要多留一条状态日志")
                .isEmpty();
        assertThat(stampCalls).isEmpty();
        assertThat(logInserts).isEmpty();
        assertThat(stored.getStatus()).isEqualTo("PUBLISHED");
        assertThat(stored.getPublishedAt()).isEqualTo(first);

        assertThat(r.postStatusChanged()).isFalse();
        assertThat(r.publishedAtStamped()).isFalse();
        assertThat(r.postStatus()).isEqualTo("PUBLISHED");
        assertThat(recordInserts).as("不改状态也要办结留痕，否则队列只进不出").hasSize(1);
        assertThat(recorder.size()).isEqualTo(1);
        assertThat(lastOpLog().getDetail())
                .isEqualTo("post=1302|改状态=false|回填发布时间=false|理由=图与文一致，无违规");
    }

    @Test
    @DisplayName("带着旧发布时间的 HUMAN_REVIEW 帖（存量脏数据）：UPDATE 照样发出去，闸门把它挡成 0 行")
    void passOnPostWithExistingPublishedAtIsBlockedBySqlGate() {
        givenTask();
        LocalDateTime legacy = NOW.minusDays(3);
        Post stored = givenPost(AuditQueueService.POST_HUMAN_REVIEW, legacy);

        AuditQueueService.Adjudication r =
                service.adjudicate(TASK_ID, ADMIN, true, "复核未发现风险", NOW);

        assertThat(casCalls).hasSize(1);
        assertThat(stampCalls)
                .as("幂等靠 SQL 里的 published_at IS NULL 兜，不是靠 Java 少发一条语句")
                .hasSize(1);
        assertThat(stampCalls.get(0)).containsExactly(POST_ID, NOW);
        assertThat(stored.getPublishedAt())
                .as("发布时间是第一次发布那一刻，不许被挪到第二次点通过这一刻").isEqualTo(legacy);
        assertThat(r.postStatusChanged()).isTrue();
        assertThat(r.publishedAtStamped()).as("没写进去就老实回 false").isFalse();
        assertThat(lastOpLog().getDetail()).contains("回填发布时间=false");
    }

    @Test
    @DisplayName("两条 UPDATE 各写各的列：闸门写在 SQL 里，状态机不许顺手改发布时间")
    void stampSqlKeepsTheIdempotencyGateAndCasStaysBlindToPublishedAt() throws Exception {
        Method stamp = PostMapper.class.getMethod("stampPublishedAt", long.class,
                LocalDateTime.class);
        Update stampSql = stamp.getAnnotation(Update.class);
        assertThat(stampSql).as("没有 @Update 的 mapper 方法在 MyBatis 里等于没有实现").isNotNull();
        String sql = String.join(" ", stampSql.value());
        assertThat(sql)
                .contains("SET published_at = #{publishedAt}")
                .contains("status = 'PUBLISHED'")
                .contains("published_at IS NULL")
                .contains("deleted = 0");

        Method cas = PostMapper.class.getMethod("compareAndSetStatus", long.class,
                String.class, String.class);
        assertThat(String.join(" ", cas.getAnnotation(Update.class).value()))
                .as("回填是独立的一次写，不能靠给 CAS 加参数实现——加了就没人记得灰词帖该不该写")
                .doesNotContain("published_at");
    }

    @Test
    @DisplayName("隔手改单：任务不在自己手里时 0 行，留痕/状态/通知/审计四件事一件都不许发生")
    void adjudicateBySomeoneElsesTaskIsRefusedAndWritesNothing() {
        givenTaskAssignedTo(78L);
        Post stored = givenPost(AuditQueueService.POST_HUMAN_REVIEW, null);

        BizException ex = assertThrows(BizException.class,
                () -> service.adjudicate(TASK_ID, ADMIN, true, "复核未发现风险", NOW));

        assertThat(ex.getMessage()).contains("审核不允许隔手改单");
        assertThat(adjudicateCalls).hasSize(1);
        assertThat(casCalls).isEmpty();
        assertThat(stampCalls).isEmpty();
        assertThat(recordInserts).isEmpty();
        assertThat(logInserts).isEmpty();
        assertThat(recorder.size()).isZero();
        assertThat(stored.getStatus()).isEqualTo("HUMAN_REVIEW");
        assertThat(stored.getPublishedAt()).isNull();

        assertThat(opLogInserts).hasSize(1);
        assertThat(lastOpLog().getResult()).isEqualTo("DENIED");
        assertThat(lastOpLog().getAction()).isEqualTo(AdminOpLog.ACTION_AUDIT_PASS);
    }
}

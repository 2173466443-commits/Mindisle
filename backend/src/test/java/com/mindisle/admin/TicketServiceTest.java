package com.mindisle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mindisle.admin.AdminOpLogService.Ctx;
import com.mindisle.admin.dto.ClosedRow;
import com.mindisle.admin.dto.OverdueRow;
import com.mindisle.admin.dto.StatusCountRow;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.AdminOpLog;
import com.mindisle.entity.AlertTicket;
import com.mindisle.entity.SysConfig;
import com.mindisle.entity.User;
import com.mindisle.mapper.AdminOpLogMapper;
import com.mindisle.mapper.AlertTicketMapper;
import com.mindisle.mapper.SensitiveWordMapper;
import com.mindisle.mapper.SysConfigMapper;
import com.mindisle.mapper.UserMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.lang.reflect.Proxy;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 危机工单六态状态机单测（任务 T6.2 · 需求 FR8.5 FR10.5 BR3 · 手册 §9.1 第 6 条）。
 *
 * <p><b>本类真正钉住的是三件事</b>：
 * ① <b>SLA 不许因为「等级写错」变成「没有时限」</b>——工单卡上的「还剩几分钟」一旦渲染成
 * 「无时限」，管理员就会把一条真正超时的 L3 当成不急的单放过，错误展示比报错更危险，
 * 所以非法等级回落到最宽的 L2 而不是 null（源码注释把这条写成了显式决定）；
 * ② <b>只有 closed 回填 sensitive_word.hit_cnt</b>——把 false_positive 也计成命中热度，
 * 等于让误报词永远出不去，词库会越改越差；
 * ③ <b>每一次「被挡下的流转」都要留下一行 DENIED</b>——隔手接手、并发抢占、重复办结，
 * 这三类在界面上都只是一句红字，只有审计能回答「谁在什么时候试图越过流程」。</p>
 *
 * <p><b>替身是「会动的假库」而不是「记号的假库」</b>：claim/startDoing/close 直接改内存里的
 * AlertTicket，并按真 SQL 的 WHERE 条件决定返回 0 还是 1。这样服务末尾那句
 * {@code require(ticketId)} 回读到的就是处置后的真相，判据才有意义——
 * 只记录调用不模拟条件写，「闭环后回读状态」这条断言就会白写。</p>
 */
class TicketServiceTest {

    private static final Ctx SUPER = new Ctx(486L, "SUPER", "203.0.113.9", "JUnit/Gate6");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 29, 10, 0);

    private final Map<Long, AlertTicket> tickets = new LinkedHashMap<>();
    private final List<AdminOpLog> opLogs = new ArrayList<>();
    private final List<List<String>> bumpedWords = new ArrayList<>();
    private Object[] lastPageArgs;
    private Object[] lastCountArgs;
    private SysConfig sysConfigRow;
    private List<User> users = List.of();
    private OverdueRow overdueRow;
    private ClosedRow closedRow;
    private List<StatusCountRow> statusRows = List.of();
    private int bumpReturn = 0;
    private Integer lastTimelineLimit;
    private Integer lastPageLimit;
    /** 打开它，写操作一律按「已被别人抢先」返回 0，用来复现并发窗口。 */
    private boolean raceTheWriter;

    private TicketService service;

    @BeforeAll
    static void initTableInfo() {
        // views() 里要拼 LambdaQueryWrapper<User>；少了这一步，MyBatis-Plus 直接抛
        // "can not find lambda cache for this entity"（与 PostListSqlConditionTest 同一套起手式）。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            User.class);
    }

    /** UPDATE 的语义：换一个新对象放回假库，服务手里那个旧实例保持改写前的状态。 */
    private static AlertTicket copy(AlertTicket t) {
        AlertTicket c = new AlertTicket();
        c.setId(t.getId());
        c.setLevel(t.getLevel());
        c.setUserId(t.getUserId());
        c.setSourceType(t.getSourceType());
        c.setSourceId(t.getSourceId());
        c.setEvidenceText(t.getEvidenceText());
        c.setRiskScore(t.getRiskScore());
        c.setTriggerWords(t.getTriggerWords());
        c.setStatus(t.getStatus());
        c.setAssigneeId(t.getAssigneeId());
        c.setClaimAt(t.getClaimAt());
        c.setCloseAt(t.getCloseAt());
        c.setSlaAt(t.getSlaAt());
        c.setHandleNote(t.getHandleNote());
        c.setFollowupAt(t.getFollowupAt());
        c.setDeleted(t.getDeleted());
        c.setCreatedAt(t.getCreatedAt());
        c.setUpdatedAt(t.getUpdatedAt());
        return c;
    }

    private static AlertTicket row(long id, String status, String level, Long userId,
                                   LocalDateTime slaAt, String triggerWords) {
        AlertTicket t = new AlertTicket();
        t.setId(id);
        t.setStatus(status);
        t.setLevel(level);
        t.setUserId(userId);
        t.setSourceType("post");
        t.setSourceId(9000L + id);
        t.setSlaAt(slaAt);
        t.setTriggerWords(triggerWords);
        t.setDeleted(0);
        t.setCreatedAt(NOW.minusHours(1));
        return t;
    }

    @BeforeEach
    void setUp() {
        tickets.clear();
        opLogs.clear();
        bumpedWords.clear();
        lastPageArgs = null;
        lastCountArgs = null;
        sysConfigRow = null;
        users = List.of();
        overdueRow = null;
        closedRow = null;
        statusRows = List.of();
        bumpReturn = 0;
        raceTheWriter = false;

        AlertTicketMapper ticketMapper = (AlertTicketMapper) Proxy.newProxyInstance(
            AlertTicketMapper.class.getClassLoader(), new Class<?>[] {AlertTicketMapper.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "selectById" -> tickets.get((Long) args[0]);
                case "claim" -> {
                    AlertTicket t = tickets.get((Long) args[0]);
                    if (t == null || raceTheWriter || !AlertTicket.STATUS_PENDING.equals(t.getStatus())) {
                        yield 0;
                    }
                    AlertTicket after = copy(t);
                    after.setStatus(AlertTicket.STATUS_CLAIMED);
                    after.setAssigneeId((Long) args[1]);
                    after.setClaimAt((LocalDateTime) args[2]);
                    tickets.put(after.getId(), after);
                    yield 1;
                }
                case "startDoing" -> {
                    AlertTicket t = tickets.get((Long) args[0]);
                    if (t == null || raceTheWriter || !AlertTicket.STATUS_CLAIMED.equals(t.getStatus())) {
                        yield 0;
                    }
                    AlertTicket after = copy(t);
                    after.setStatus(AlertTicket.STATUS_DOING);
                    tickets.put(after.getId(), after);
                    yield 1;
                }
                case "close" -> {
                    AlertTicket t = tickets.get((Long) args[0]);
                    if (t == null || raceTheWriter
                        || !TicketService.OPEN_STATUSES.contains(t.getStatus())) {
                        yield 0;
                    }
                    String toStatus = (String) args[2];
                    AlertTicket after = copy(t);
                    after.setStatus(toStatus);
                    after.setHandleNote((String) args[3]);
                    after.setFollowupAt((LocalDateTime) args[4]);
                    after.setCloseAt((LocalDateTime) args[5]);
                    if (args[1] != null) {
                        after.setAssigneeId((Long) args[1]);
                    }
                    tickets.put(after.getId(), after);
                    yield 1;
                }
                case "pageTickets" -> {
                    lastPageArgs = args;
                    lastPageLimit = args == null || args.length < 8 ? null : (Integer) args[7];
                    yield new ArrayList<>(tickets.values());
                }
                case "countTickets" -> {
                    lastCountArgs = args;
                    yield (long) tickets.size();
                }
                case "countGroupByStatus" -> statusRows;
                case "overdueStats" -> overdueRow;
                case "closedStatsSince" -> closedRow;
                case "timelineByUser" -> {
                    long userId = (Long) args[0];
                    lastTimelineLimit = (Integer) args[1];
                    List<AlertTicket> out = new ArrayList<>();
                    for (AlertTicket t : tickets.values()) {
                        if (t.getUserId() != null && t.getUserId() == userId) {
                            out.add(t);
                        }
                    }
                    yield out;
                }
                default -> throw new IllegalStateException("单测没替这条方法打桩：" + method.getName());
            });

        SensitiveWordMapper wordMapper = (SensitiveWordMapper) Proxy.newProxyInstance(
            SensitiveWordMapper.class.getClassLoader(), new Class<?>[] {SensitiveWordMapper.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "bumpHitCnt" -> {
                    @SuppressWarnings("unchecked")
                    List<String> words = (List<String>) args[0];
                    bumpedWords.add(new ArrayList<>(words));
                    yield bumpReturn;
                }
                default -> throw new IllegalStateException("单测没替这条方法打桩：" + method.getName());
            });

        SysConfigMapper configMapper = (SysConfigMapper) Proxy.newProxyInstance(
            SysConfigMapper.class.getClassLoader(), new Class<?>[] {SysConfigMapper.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "findByKey" -> sysConfigRow;
                default -> throw new IllegalStateException("单测没替这条方法打桩：" + method.getName());
            });

        UserMapper userMapper = (UserMapper) Proxy.newProxyInstance(
            UserMapper.class.getClassLoader(), new Class<?>[] {UserMapper.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "selectList" -> new ArrayList<>(users);
                default -> throw new IllegalStateException("单测没替这条方法打桩：" + method.getName());
            });

        AdminOpLogMapper logMapper = (AdminOpLogMapper) Proxy.newProxyInstance(
            AdminOpLogMapper.class.getClassLoader(), new Class<?>[] {AdminOpLogMapper.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "insert" -> {
                    AdminOpLog log = (AdminOpLog) args[0];
                    log.setId((long) opLogs.size() + 1L);
                    opLogs.add(log);
                    yield 1;
                }
                default -> throw new IllegalStateException("单测没替这条方法打桩：" + method.getName());
            });

        service = new TicketService(ticketMapper, wordMapper, configMapper, userMapper,
            new MindisleProperties(), new AdminOpLogService(logMapper));
    }

    private AdminOpLog lastLog() {
        assertThat(opLogs).isNotEmpty();
        return opLogs.get(opLogs.size() - 1);
    }

    private static SysConfig cfg(String key, String value) {
        SysConfig c = new SysConfig();
        c.setCfgKey(key);
        c.setCfgValue(value);
        return c;
    }

    // ------------------------------------------------------------------ SLA

    @Test
    @DisplayName("SLA 默认值：L3 按分钟、L2 按小时，两者都从 application.yml 取")
    void slaUsesYmlDefaults() {
        assertThat(service.slaFor("L3", NOW)).isEqualTo(NOW.plusMinutes(30));
        assertThat(service.slaFor("L2", NOW)).isEqualTo(NOW.plusHours(4));
    }

    @Test
    @DisplayName("sys_config 优先于 yml：管理员把 L3 改成 15 分钟，工单排序立刻按 15 分钟算")
    void sysConfigOverridesYml() {
        sysConfigRow = cfg(TicketService.CFG_L3_MINUTES, "15");
        assertThat(service.slaFor("L3", NOW)).isEqualTo(NOW.plusMinutes(15));
        sysConfigRow = cfg(TicketService.CFG_L2_HOURS, "2");
        assertThat(service.slaFor("L2", NOW)).isEqualTo(NOW.plusHours(2));
    }

    @Test
    @DisplayName("配置漂移不许让工单没时限：非数值/非正数/空值一律回落 yml 而不是抛错或给 null")
    void badSysConfigFallsBack() {
        for (String raw : List.of("很热", "0", "-5", "  ", "3.5")) {
            sysConfigRow = cfg(TicketService.CFG_L3_MINUTES, raw);
            assertThat(service.slaFor("L3", NOW))
                .as("sys_config=%s 必须回落到默认的 30 分钟", raw)
                .isEqualTo(NOW.plusMinutes(30));
        }
        sysConfigRow = cfg(TicketService.CFG_L3_MINUTES, "45");
        assertThat(service.slaFor("L3", NOW)).isEqualTo(NOW.plusMinutes(45));
    }

    @Test
    @DisplayName("非法等级回落到最宽的 L2 且绝不返回 null（null 会让界面显示「无时限」，比报错更危险）")
    void illegalLevelNeverReturnsNull() {
        for (String level : List.of("", "l3", "L1", "L0", "L4", "  L3 ")) {
            assertThat(service.slaFor(level, NOW))
                .as("等级 [%s] 试算不能是 null", level)
                .isEqualTo(NOW.plusHours(4));
        }
        assertThat(service.slaFor(null, NOW)).isEqualTo(NOW.plusHours(4));
        // 正例对照：写对的 L3 仍然按分钟，说明上面的回落没有把一切抹平。
        assertThat(service.slaFor("L3", NOW)).isEqualTo(NOW.plusMinutes(30));
    }

    // ------------------------------------------------------------------ 列表与视图

    @Test
    @DisplayName("列表的 status 过白名单：筛错状态必须报参数错，不能安静地返回一张空表")
    void listRejectsUnknownStatus() {
        tickets.put(1L, row(1L, AlertTicket.STATUS_PENDING, "L3", 100L, NOW.minusMinutes(1), null));
        BizException e = assertThrows(BizException.class, () -> service.list("PENDING", null,
            null, null, false, new PageQuery(), NOW));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(e).hasMessageContaining("pending").hasMessageContaining("closed");
        assertThat(lastPageArgs).isNull();

        service.list("pending", null, null, null, false, new PageQuery(), NOW);
        assertThat(lastPageArgs[0]).isEqualTo("pending");
    }

    @Test
    @DisplayName("终态也能作为筛选值（看板要能把已办结的翻出来复盘），但等级只有 L2/L3")
    void listAllowsTerminalStatusRejectsOtherLevel() {
        service.list("closed", null, null, null, false, new PageQuery(), NOW);
        assertThat(lastPageArgs[0]).isEqualTo("closed");
        Object[] closedArgs = lastPageArgs;

        BizException e = assertThrows(BizException.class, () -> service.list(null, "L1",
            null, null, false, new PageQuery(), NOW));
        assertThat(e).hasMessageContaining("L2/L3");
        // 白名单必须在打 SQL 之前挡住：SQL 参数还停留在上一次那个对象上，说明这一次压根没打库。
        assertThat(lastPageArgs).isSameAs(closedArgs);
    }

    @Test
    @DisplayName("视图三态：未办结且已过 SLA 才算超时；已办结的老单不算超时；剩余分钟数可为负")
    void viewsComputeOverdueOnlyForOpenTickets() {
        tickets.put(1L, row(1L, AlertTicket.STATUS_DOING, "L3", 100L, NOW.minusMinutes(7), null));
        tickets.put(2L, row(2L, AlertTicket.STATUS_CLOSED, "L3", 200L, NOW.minusHours(9), null));
        tickets.put(3L, row(3L, AlertTicket.STATUS_PENDING, "L2", 300L, null, null));
        User u = new User();
        u.setId(100L);
        u.setNickname("小屿");
        users = List.of(u);

        PageResult<TicketService.TicketView> r = service.list(null, null, null, null,
            false, new PageQuery(), NOW);
        assertThat(r.getList()).hasSize(3);
        TicketService.TicketView v1 = r.getList().get(0);
        assertThat(v1.overdue()).isTrue();
        assertThat(v1.slaMinutesLeft()).isEqualTo(-7L);
        assertThat(v1.nickname()).isEqualTo("小屿");
        // 已办结的工单 SLA 早过了，但它不该再出现在「超时待处置」里。
        assertThat(r.getList().get(1).overdue()).isFalse();
        assertThat(r.getList().get(1).nickname()).isNull();
        // slaAt 为空＝还没有时限，剩余分钟必须是 null 而不是 0（0 会被界面读成「刚好到期」）。
        assertThat(r.getList().get(2).slaMinutesLeft()).isNull();
        assertThat(r.getList().get(2).overdue()).isFalse();
    }

    @Test
    @DisplayName("看板三个数：SQL 返回 null 行时全部按 0 处理，不能 NPE")
    void boardSurvivesNullRows() {
        TicketService.Board b = service.board(NOW);
        assertThat(b.overdueCnt()).isZero();
        assertThat(b.worstMinutes()).isZero();
        assertThat(b.todayCreatedCnt()).isZero();
        assertThat(b.todayClosedCnt()).isZero();
        assertThat(b.statusCounts()).isEmpty();

        statusRows = List.of(countRow("pending", 3L));
        overdueRow = new OverdueRow();
        overdueRow.setCnt(2L);
        overdueRow.setWorstMinutes(45L);
        closedRow = new ClosedRow();
        closedRow.setCnt(7L);
        closedRow.setClosedCnt(5L);
        TicketService.Board b2 = service.board(NOW);
        assertThat(b2.overdueCnt()).isEqualTo(2L);
        assertThat(b2.worstMinutes()).isEqualTo(45L);
        assertThat(b2.todayCreatedCnt()).isEqualTo(7L);
        assertThat(b2.todayClosedCnt()).isEqualTo(5L);
        assertThat(b2.statusCounts()).hasSize(1);
    }

    private static StatusCountRow countRow(String status, Long cnt) {
        StatusCountRow r = new StatusCountRow();
        r.setStatus(status);
        r.setCnt(cnt);
        return r;
    }

    @Test
    @DisplayName("limit 有上下界：0 或负数回落到默认值，超界截到上限（防止一次拉空整张表）")
    void limitsAreClamped() {
        tickets.put(1L, row(1L, AlertTicket.STATUS_PENDING, "L3", 100L, null, null));
        service.timeline(100L, 0);
        assertThat(lastTimelineLimit).isEqualTo(20);
        service.timeline(100L, 5);
        assertThat(lastTimelineLimit).isEqualTo(5);
        service.timeline(100L, 500);
        assertThat(lastTimelineLimit)
            .as("时间线一次最多 100 条，界面上的「我曾得到的关怀」不需要整张表")
            .isEqualTo(100);
        service.overdue(NOW, 0);
        assertThat(lastPageLimit).isEqualTo(50);
        service.overdue(NOW, 999);
        assertThat(lastPageLimit).isEqualTo(200);
    }

    @Test
    @DisplayName("trigger_words 解析：逗号分隔要 trim、空段丢掉、超 200 字的词面不进 SQL")
    void triggerWordsParsing() {
        assertThat(TicketService.triggerWords(null)).isEmpty();
        assertThat(TicketService.triggerWords("  ")).isEmpty();
        assertThat(TicketService.triggerWords("想死, 活着没意思 ,,切断")).containsExactly("想死", "活着没意思", "切断");
        String tooLong = "字".repeat(TicketService.WORDS_MAX + 1);
        assertThat(TicketService.triggerWords(tooLong + ",想死")).containsExactly("想死");
    }

    // ------------------------------------------------------------------ 认领 / 转处置

    @Test
    @DisplayName("认领：pending 才能领，认领成功写 claimed 并留下一行 SUCCESS 审计")
    void claimHappyPath() {
        tickets.put(1L, row(1L, AlertTicket.STATUS_PENDING, "L3", 100L, NOW.plusMinutes(20), null));
        AlertTicket t = service.claim(1L, SUPER, NOW);
        assertThat(t.getStatus()).isEqualTo(AlertTicket.STATUS_CLAIMED);
        assertThat(t.getAssigneeId()).isEqualTo(486L);
        assertThat(t.getClaimAt()).isEqualTo(NOW);
        assertThat(lastLog().getAction()).isEqualTo(AdminOpLog.ACTION_TICKET_HANDLE);
        assertThat(lastLog().getResult()).isEqualTo(AdminOpLog.RESULT_SUCCESS);
        assertThat(lastLog().getDetail()).contains("认领 level=L3").contains("user=100");
    }

    @Test
    @DisplayName("责任唯一：已被别人领走的工单不能隔手接手，而且必须留下 DENIED（谁试图越过流程）")
    void claimRejectsTakenTicket() {
        tickets.put(2L, row(2L, AlertTicket.STATUS_CLAIMED, "L2", 100L, null, null));
        tickets.get(2L).setAssigneeId(487L);
        BizException e = assertThrows(BizException.class, () -> service.claim(2L, SUPER, NOW));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(e).hasMessageContaining("claimed");
        assertThat(opLogs).hasSize(1);
        assertThat(lastLog().getResult()).isEqualTo(AdminOpLog.RESULT_DENIED);
        assertThat(lastLog().getDetail()).contains("不是待认领");
        assertThat(tickets.get(2L).getAssigneeId()).isEqualTo(487L);
    }

    @Test
    @DisplayName("并发抢占：SQL 影响 0 行时报错并留 DENIED，不能假装认领成功")
    void claimLosesRace() {
        tickets.put(3L, row(3L, AlertTicket.STATUS_PENDING, "L3", 100L, null, null));
        raceTheWriter = true;
        BizException e = assertThrows(BizException.class, () -> service.claim(3L, SUPER, NOW));
        assertThat(e).hasMessageContaining("其他管理员");
        assertThat(lastLog().getDetail()).contains("并发抢占失败");
        assertThat(tickets.get(3L).getStatus()).isEqualTo(AlertTicket.STATUS_PENDING);
    }

    @Test
    @DisplayName("没有登录身份就没有处置：ctx 为空直接 401，一条工单都不许被改")
    void claimRequiresOperator() {
        tickets.put(4L, row(4L, AlertTicket.STATUS_PENDING, "L3", 100L, null, null));
        BizException e = assertThrows(BizException.class,
            () -> service.claim(4L, new Ctx(null, "SUPER", null, null), NOW));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(opLogs).isEmpty();
        assertThat(tickets.get(4L).getStatus()).isEqualTo(AlertTicket.STATUS_PENDING);
    }

    @Test
    @DisplayName("转处置中只有 claimed 能进；pending 直接跳 doing 被拒并留痕")
    void startDoingOnlyFromClaimed() {
        tickets.put(5L, row(5L, AlertTicket.STATUS_CLAIMED, "L2", 100L, null, null));
        assertThat(service.startDoing(5L, SUPER, NOW).getStatus()).isEqualTo(AlertTicket.STATUS_DOING);
        assertThat(lastLog().getDetail()).contains("转处置中 operator=486");

        tickets.put(6L, row(6L, AlertTicket.STATUS_PENDING, "L2", 100L, null, null));
        BizException e = assertThrows(BizException.class, () -> service.startDoing(6L, SUPER, NOW));
        assertThat(e).hasMessageContaining("请先认领");
        assertThat(opLogs).hasSize(2);
        assertThat(lastLog().getResult()).isEqualTo(AdminOpLog.RESULT_DENIED);
    }

    // ------------------------------------------------------------------ 闭环

    @Test
    @DisplayName("闭环的两种非法入参：处置结果不在三种终态里、处置记录为空，都不许打到 SQL")
    void closeRejectsBadArguments() {
        tickets.put(7L, row(7L, AlertTicket.STATUS_PENDING, "L3", 100L, null, null));
        BizException badStatus = assertThrows(BizException.class,
            () -> service.close(7L, SUPER, "CLOSED", "已联系本人", null, NOW));
        assertThat(badStatus.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(badStatus).hasMessageContaining("CLOSED");

        BizException blankNote = assertThrows(BizException.class,
            () -> service.close(7L, SUPER, "closed", "   ", null, NOW));
        assertThat(blankNote).hasMessageContaining("处置记录必填");

        assertThat(tickets.get(7L).getStatus()).isEqualTo(AlertTicket.STATUS_PENDING);
        assertThat(opLogs).isEmpty();
        assertThat(bumpedWords).isEmpty();
    }

    @Test
    @DisplayName("工单不存在必须 90006，而不是凭空造一张工单")
    void closeMissingTicket() {
        BizException e = assertThrows(BizException.class,
            () -> service.close(999L, SUPER, "closed", "已按流程处置", null, NOW));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("已办结的工单不能重复处置：拒绝 + DENIED 留痕，处置记录也不许被改写")
    void closeRejectsAlreadyClosed() {
        tickets.put(8L, row(8L, AlertTicket.STATUS_CLOSED, "L3", 100L, null, null));
        tickets.get(8L).setHandleNote("第一次写的记录");
        BizException e = assertThrows(BizException.class,
            () -> service.close(8L, SUPER, "false_positive", "想改口径", null, NOW));
        assertThat(e).hasMessageContaining("已办结");
        assertThat(lastLog().getResult()).isEqualTo(AdminOpLog.RESULT_DENIED);
        assertThat(tickets.get(8L).getHandleNote()).isEqualTo("第一次写的记录");
    }

    @Test
    @DisplayName("只有 closed 回填词库热度：真命中的触发词进 hit_cnt，并写进审计的行数")
    void closeAsClosedBumpsWordHits() {
        tickets.put(9L, row(9L, AlertTicket.STATUS_DOING, "L3", 100L, null, "想死,活着没意思"));
        bumpReturn = 2;
        TicketService.Outcome o = service.close(9L, SUPER, "closed", "已联系学院心理老师，本人同意面谈",
            NOW.plusDays(1), NOW);
        assertThat(o.toStatus()).isEqualTo("closed");
        assertThat(o.hitWordsBumped()).isEqualTo(2);
        assertThat(bumpedWords).hasSize(1);
        assertThat(bumpedWords.get(0)).containsExactly("想死", "活着没意思");
        assertThat(o.ticket().getStatus()).isEqualTo("closed");
        assertThat(o.ticket().getCloseAt()).isEqualTo(NOW);
        assertThat(lastLog().getDetail()).contains("doing->closed").contains("回填词热=2").contains("回访=2026");
    }

    @Test
    @DisplayName("误报绝不能进命中热度：false_positive 回填 0 次词库，否则误报词永远出不去")
    void falsePositiveNeverBumpsWords() {
        tickets.put(10L, row(10L, AlertTicket.STATUS_PENDING, "L2", 100L, null, "想死,失眠"));
        bumpReturn = 99;
        TicketService.Outcome o = service.close(10L, SUPER, "false_positive", "上下文是歌词，机器误判",
            null, NOW);
        assertThat(bumpedWords).isEmpty();
        assertThat(o.hitWordsBumped()).isZero();
        assertThat(lastLog().getDetail()).contains("回填词热=0").contains("回访=无");
    }

    @Test
    @DisplayName("closed 但触发词为空时不去动词库（不拿空列表拼 IN ()）")
    void closeWithoutTriggerWordsSkipsMapper() {
        tickets.put(11L, row(11L, AlertTicket.STATUS_PENDING, "L3", 100L, null, "  "));
        assertThat(service.close(11L, SUPER, "closed", "已转介", null, NOW).hitWordsBumped()).isZero();
        assertThat(bumpedWords).isEmpty();
    }

    @Test
    @DisplayName("处置记录按码点截到 1000：中文长文本进 handle_note 不能被劈成半个字")
    void closeTruncatesNoteByCodePoints() {
        tickets.put(12L, row(12L, AlertTicket.STATUS_PENDING, "L3", 100L, null, null));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < TicketService.NOTE_MAX + 50; i++) {
            sb.append('记');
        }
        service.close(12L, SUPER, "expired", sb.toString(), null, NOW);
        assertThat(tickets.get(12L).getHandleNote()).hasSize(TicketService.NOTE_MAX);
        assertThat(tickets.get(12L).getHandleNote()).startsWith("记");
    }

    @Test
    @DisplayName("未认领的 L3 可以直接闭环：SQL 用 COALESCE 保留值班人，Java 侧不许强迫先认领")
    void closeAllowsUnclaimedTicket() {
        tickets.put(13L, row(13L, AlertTicket.STATUS_PENDING, "L3", 100L, null, null));
        TicketService.Outcome o = service.close(13L, SUPER, "closed", "值班直接处置", null, NOW);
        assertThat(o.ticket().getStatus()).isEqualTo("closed");
        assertThat(opLogs).hasSize(1);
        assertThat(lastLog().getResult()).isEqualTo(AdminOpLog.RESULT_SUCCESS);
    }

    @Test
    @DisplayName("并发闭环：状态被别人抢先改走时影响 0 行，报错并留 DENIED 而不是写一行假的成功")
    void closeLosesRace() {
        tickets.put(14L, row(14L, AlertTicket.STATUS_PENDING, "L3", 100L, null, null));
        raceTheWriter = true;
        BizException e = assertThrows(BizException.class,
            () -> service.close(14L, SUPER, "closed", "已被抢先", null, NOW));
        assertThat(e).hasMessageContaining("请刷新后重试");
        assertThat(lastLog().getResult()).isEqualTo(AdminOpLog.RESULT_DENIED);
        assertThat(lastLog().getDetail()).contains("并发流转失败");
        assertThat(bumpedWords).isEmpty();
    }
}

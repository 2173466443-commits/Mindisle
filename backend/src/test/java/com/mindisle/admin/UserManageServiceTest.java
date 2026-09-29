package com.mindisle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mindisle.admin.AdminOpLogService.Ctx;
import com.mindisle.admin.UserManageService.RevealResult;
import com.mindisle.admin.UserManageService.UserDetail;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.AdminOpLog;
import com.mindisle.entity.AlertTicket;
import com.mindisle.entity.AnonymousAlias;
import com.mindisle.entity.NotifyMessage;
import com.mindisle.entity.Post;
import com.mindisle.entity.User;
import com.mindisle.mapper.AdminOpLogMapper;
import com.mindisle.mapper.AlertTicketMapper;
import com.mindisle.mapper.AnonymousAliasMapper;
import com.mindisle.mapper.PostMapper;
import com.mindisle.mapper.UserMapper;
import com.mindisle.notify.RecordingNotifyService;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 账号处置与匿名解匿单测（任务 T6.4 / T6.5 · 需求 FR8.3 FR8.4 · 手册 §9.1 第 5 条）。
 *
 * <p><b>本类钉住的是三条会在真实事故里出现的规则</b>：
 * ① <b>解匿的鉴权必须先于查库</b>——Gate6 第 63 轮的真 bug 是「ADMIN 越权解匿、aliasId 又写错」时
 * 代码先在 404 上返回，一次越权探测连一行 DENIED 都不留；而 FR8.4 要的恰恰是
 * 「解匿的一切尝试 100% 留痕」，所以这里直接断言 alias 表<b>一次都没被查过</b>
 * （既留得住证据，也不把「这个 aliasId 存在吗」泄露给没资格的人）；
 * ② <b>禁言只压写操作、不压登录</b>——mute 写的必须只是 status=MUTED + mute_until 这一对事实，
 * 阶段 5 那次把 MUTED 挡在登录闸门上，等于把危机期用户从社区里抹掉；
 * ③ <b>被封禁要显式清掉 mute_until</b>——留着旧的禁言到期时间，前端会显示「X 日后自动恢复」，
 * 而真相是永久封禁，这是错误展示而不是少显示一个字段。</p>
 *
 * <p><b>替身是「会动的假库」</b>：写操作一律 copy-on-write（真 SQL 的 UPDATE 不会让已读出的旧行变更），
 * 所以服务末尾那句 {@code require(userId)} 回读到的是处置后的真相；
 * releaseMute / restoreActive 按真 WHERE 条件返回 0 或 1，并发抢占那条分支才测得出来。
 * 分页这一路的判据读的是<b>拼出来那一刻</b>的 SQL 片段：count 不能带 limit（带了 total 就变成页大小），
 * list 必须带 limit/offset，两者共用同一个条件——这也是源码为什么先 {@code clone()} 再 {@code last()}。</p>
 */
class UserManageServiceTest {

    private static final Ctx SUPER = new Ctx(486L, "SUPER", "203.0.113.9", "JUnit/Gate6");
    private static final Ctx ADMIN = new Ctx(487L, "ADMIN", "203.0.113.9", "JUnit/Gate6");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 29, 10, 0);

    private final Map<Long, User> users = new LinkedHashMap<>();
    private final Map<Long, AnonymousAlias> aliases = new LinkedHashMap<>();
    private final Map<Long, Post> posts = new LinkedHashMap<>();
    private final List<AdminOpLog> opLogs = new ArrayList<>();

    private final List<Object[]> releaseMuteCalls = new ArrayList<>();
    private final List<Object[]> restoreActiveCalls = new ArrayList<>();
    /** 马甲回写发生的时刻，库里已经躺着几行审计：钉的是「先留痕、后回写」这个顺序。 */
    private final List<Integer> aliasUpdateSeenLogs = new ArrayList<>();
    private final List<AlertTicket> timelineRows = new ArrayList<>();

    private RecordingNotifyService notify;
    private UserManageService service;

    private int selectCountCalls;
    private int selectListCalls;
    private int updateByIdCalls;
    private int aliasSelectByIdCalls;
    private int aliasUpdateCalls;
    private long totalReturn;
    private long publicPostCnt;
    private long receivedLikeCnt;
    private Integer lastTimelineLimit;
    private String lastCountSql;
    private String lastListSql;
    private List<Object> lastListParams = List.of();
    /** 打开它，所有写口按「已被别人抢先」返回 0，用来复现并发窗口。 */
    private boolean raceWrites;

    @BeforeAll
    static void initTableInfo() {
        // page() 用 LambdaQueryWrapper<User> 拼条件；少了这一步 getSqlSegment() 直接抛
        // "can not find lambda cache for this entity"（与 PostListSqlConditionTest 同一套起手式）。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, User.class);
        TableInfoHelper.initTableInfo(assistant, AnonymousAlias.class);
    }

    /** UPDATE 的语义：换一个新对象放回假库，服务手里那个旧实例保持改写前的状态。 */
    private static User copy(User u) {
        User c = new User();
        c.setId(u.getId());
        c.setUsername(u.getUsername());
        c.setNickname(u.getNickname());
        c.setStatus(u.getStatus());
        c.setRole(u.getRole());
        c.setMuteUntil(u.getMuteUntil());
        c.setDeactivateAt(u.getDeactivateAt());
        c.setPurgeAt(u.getPurgeAt());
        c.setDeleted(u.getDeleted());
        c.setCreatedAt(u.getCreatedAt());
        c.setUpdatedAt(u.getUpdatedAt());
        return c;
    }

    private static AnonymousAlias copy(AnonymousAlias a) {
        AnonymousAlias c = new AnonymousAlias();
        c.setId(a.getId());
        c.setUserId(a.getUserId());
        c.setAliasName(a.getAliasName());
        c.setScene(a.getScene());
        c.setRevealedLogId(a.getRevealedLogId());
        c.setCreatedAt(a.getCreatedAt());
        c.setUpdatedAt(a.getUpdatedAt());
        return c;
    }

    private static User user(long id, String username, String status) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setNickname(username + "的昵称");
        u.setStatus(status);
        u.setRole("USER");
        u.setDeleted(0);
        u.setCreatedAt(NOW.minusDays(3));
        return u;
    }

    private static AnonymousAlias alias(long id, Long userId, String aliasName, Long revealedLogId) {
        AnonymousAlias a = new AnonymousAlias();
        a.setId(id);
        a.setUserId(userId);
        a.setAliasName(aliasName);
        a.setScene("tree_hole");
        a.setRevealedLogId(revealedLogId);
        a.setCreatedAt(NOW.minusDays(2));
        return a;
    }

    /** 拼出来那一刻的 SQL 片段（MyBatis-Plus 的 getSqlSegment() 已含 last() 追加的部分）。 */
    private static String sqlOf(Object wrapper) {
        return ((LambdaQueryWrapper<?>) wrapper).getSqlSegment().replaceAll("\\s+", " ").trim();
    }

    private static List<Object> paramsOf(Object wrapper) {
        return new ArrayList<>(((LambdaQueryWrapper<?>) wrapper).getParamNameValuePairs().values());
    }
    @BeforeEach
    void setUp() {
        users.clear();
        aliases.clear();
        posts.clear();
        opLogs.clear();
        timelineRows.clear();
        releaseMuteCalls.clear();
        restoreActiveCalls.clear();
        aliasUpdateSeenLogs.clear();
        selectCountCalls = 0;
        selectListCalls = 0;
        updateByIdCalls = 0;
        aliasSelectByIdCalls = 0;
        aliasUpdateCalls = 0;
        totalReturn = 0L;
        publicPostCnt = 0L;
        receivedLikeCnt = 0L;
        lastTimelineLimit = null;
        lastCountSql = null;
        lastListSql = null;
        lastListParams = List.of();
        raceWrites = false;

        UserMapper userMapper = (UserMapper) Proxy.newProxyInstance(
            UserMapper.class.getClassLoader(), new Class<?>[] {UserMapper.class},
            (proxy, method, args) -> switch (method.getName()) {
                // 读口给的是「新取的行」，与真库一致：服务改它不会影响假库，写口才算数。
                    case "selectById" -> {
                        User found = users.get((Long) args[0]);
                        yield found == null ? null : copy(found);
                    }
                case "selectCount" -> {
                    selectCountCalls++;
                    // 条件必须在调用当场取走：base.clone() 与 base 之后还会被 last() 改写。
                    lastCountSql = sqlOf(args[0]);
                    yield totalReturn;
                }
                case "selectList" -> {
                    selectListCalls++;
                    lastListSql = sqlOf(args[0]);
                    lastListParams = paramsOf(args[0]);
                    yield new ArrayList<>(users.values());
                }
                case "updateById" -> {
                    updateByIdCalls++;
                    if (raceWrites) {
                        yield 0;
                    }
                    User in = (User) args[0];
                    users.put(in.getId(), copy(in));
                    yield 1;
                }
                case "releaseMute" -> {
                    long id = (Long) args[0];
                    String fromStatus = (String) args[1];
                    String toStatus = (String) args[2];
                    User cur = users.get(id);
                    releaseMuteCalls.add(new Object[] {id, fromStatus, toStatus, args[3]});
                    if (cur == null || raceWrites || !fromStatus.equals(cur.getStatus())) {
                        yield 0;
                    }
                    User after = copy(cur);
                    after.setStatus(toStatus);
                    after.setMuteUntil(null);
                    after.setUpdatedAt((LocalDateTime) args[3]);
                    users.put(id, after);
                    yield 1;
                }
                case "restoreActive" -> {
                    long id = (Long) args[0];
                    String fromStatus = (String) args[1];
                    String toStatus = (String) args[2];
                    User cur = users.get(id);
                    restoreActiveCalls.add(new Object[] {id, fromStatus, toStatus});
                    if (cur == null || raceWrites || !fromStatus.equals(cur.getStatus())) {
                        yield 0;
                    }
                    User after = copy(cur);
                    after.setStatus(toStatus);
                    after.setMuteUntil(null);
                    after.setDeactivateAt(null);
                    after.setPurgeAt(null);
                    after.setUpdatedAt(NOW);
                    users.put(id, after);
                    yield 1;
                }
                default -> throw new IllegalStateException("单测没替这条方法打桩：" + method.getName());
            });

        PostMapper postMapper = (PostMapper) Proxy.newProxyInstance(
            PostMapper.class.getClassLoader(), new Class<?>[] {PostMapper.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "selectById" -> posts.get((Long) args[0]);
                case "countPublicPosts" -> publicPostCnt;
                case "sumReceivedLikes" -> receivedLikeCnt;
                default -> throw new IllegalStateException("单测没替这条方法打桩：" + method.getName());
            });

        AnonymousAliasMapper aliasMapper = (AnonymousAliasMapper) Proxy.newProxyInstance(
            AnonymousAliasMapper.class.getClassLoader(), new Class<?>[] {AnonymousAliasMapper.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "selectById" -> {
                    aliasSelectByIdCalls++;
                    AnonymousAlias found = aliases.get((Long) args[0]);
                    yield found == null ? null : copy(found);
                }
                case "updateById" -> {
                    aliasUpdateCalls++;
                    aliasUpdateSeenLogs.add(opLogs.size());
                    if (raceWrites) {
                        yield 0;
                    }
                    AnonymousAlias in = (AnonymousAlias) args[0];
                    aliases.put(in.getId(), copy(in));
                    yield 1;
                }
                case "listByUser" -> {
                    long userId = (Long) args[0];
                    List<AnonymousAlias> out = new ArrayList<>();
                    for (AnonymousAlias a : aliases.values()) {
                        if (a.getUserId() != null && a.getUserId() == userId) {
                            out.add(copy(a));
                        }
                    }
                    yield out;
                }
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
                // 与 @Select 逐字同语义：只数 SUCCESS 的解匿行（DENIED 是越权证据，不是解匿次数）。
                case "countReveals" -> opLogs.stream()
                    .filter(l -> AdminOpLog.ACTION_REVEAL_ANONYMOUS.equals(l.getAction())
                        && AdminOpLog.RESULT_SUCCESS.equals(l.getResult()))
                    .count();
                default -> throw new IllegalStateException("单测没替这条方法打桩：" + method.getName());
            });

        AlertTicketMapper alertMapper = (AlertTicketMapper) Proxy.newProxyInstance(
            AlertTicketMapper.class.getClassLoader(), new Class<?>[] {AlertTicketMapper.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "timelineByUser" -> {
                    lastTimelineLimit = (Integer) args[1];
                    yield new ArrayList<>(timelineRows);
                }
                default -> throw new IllegalStateException("单测没替这条方法打桩：" + method.getName());
            });

        notify = new RecordingNotifyService();
        AdminOpLogService opLogService = new AdminOpLogService(logMapper);
        // 详情抽屉要用 TicketService.timeline：这里走真 TicketService（不是匿名子类），
        // 为的是「limit 传 20 且被夹到 [1,100]」这条也在断言范围内；其余协作者它碰不到，传 null。
        TicketService ticketService = new TicketService(alertMapper, null, null, null,
            new MindisleProperties(), opLogService);
        service = new UserManageService(userMapper, postMapper, aliasMapper, notify.service(),
            opLogService, ticketService);
    }

    private AdminOpLog lastLog() {
        assertThat(opLogs).as("留痕条数=%s", notify.dump()).isNotEmpty();
        return opLogs.get(opLogs.size() - 1);
    }

    private List<AdminOpLog> logsOf(String result) {
        return opLogs.stream().filter(l -> result.equals(l.getResult())).toList();
    }

    private User stored(long id) {
        return users.get(id);
    }

    // ------------------------------------------------------------------ A6 检索

    @Test
    @DisplayName("列表筛状态先过白名单：非法值报参数错，一条 SQL 都不许打")
    void pageRejectsUnknownStatusBeforeTouchingSql() {
        users.put(1L, user(1L, "gate6_user", "ACTIVE"));
        totalReturn = 1L;
        BizException e = assertThrows(BizException.class,
            () -> service.page(null, "ACTIVEE", new PageQuery()));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        // 文案要把四个合法值一次报全：管理员是照着这句话去改下拉框的
        assertThat(e).hasMessageContaining("ACTIVE/BANNED/DELETED/MUTED");
        assertThat(selectCountCalls).isZero();
        assertThat(selectListCalls).isZero();
        // 正例对照：写对的状态照样筛得动，说明上面的拦截没把筛选整个废掉。
        totalReturn = 7L;
        PageResult<User> ok = service.page(null, "MUTED", new PageQuery());
        assertThat(ok.getTotal()).isEqualTo(7L);
        assertThat(lastCountSql).contains("status =");
    }

    @Test
    @DisplayName("空串与纯空格等于「不筛」：漏掉 blankToNull 会拼出 status = ''，整页凭空消失")
    void blankStatusMeansNoFilter() {
        users.put(1L, user(1L, "gate6_user", "MUTED"));
        totalReturn = 1L;
        service.page(null, "   ", new PageQuery());
        assertThat(lastCountSql).doesNotContain("status");
        assertThat(lastListParams).isEmpty();
        service.page(null, null, new PageQuery());
        assertThat(lastCountSql).doesNotContain("status");
        // 正例对照
        service.page(null, "BANNED", new PageQuery());
        assertThat(lastCountSql).contains("status =");
        assertThat(lastListParams).contains("BANNED");
    }

    @Test
    @DisplayName("总数不带 limit、列表才带 limit/offset：反了分页器会把每页数当成全站总数")
    void totalCountsFullSetWhileListCarriesPaging() {
        users.put(1L, user(1L, "gate6_user", "ACTIVE"));
        totalReturn = 137L;
        PageQuery q = new PageQuery();
        q.setPage(3);
        q.setSize(10);
        PageResult<User> page = service.page("小", "ACTIVE", q);
        assertThat(page.getTotal()).isEqualTo(137L);
        assertThat(lastCountSql).doesNotContain("limit");
        assertThat(lastListSql).contains("limit 10 offset 20");
        assertThat(lastListSql).contains("ORDER BY id DESC");
        // 正例对照：默认第 1 页、每页 20
        service.page(null, null, new PageQuery());
        assertThat(lastListSql).contains("limit 20 offset 0");
        // 归一化：非法页码页大小收敛到安全区间，而不是把 -5 拼进 SQL
        PageQuery bad = new PageQuery();
        bad.setPage(0);
        bad.setSize(9999);
        service.page(null, null, bad);
        assertThat(lastListSql).contains("limit 50 offset 0");
    }

    @Test
    @DisplayName("检索关键词按码点截到 64：超长不进 LIKE，第 64 位上的 emoji 也不许被劈成半个代理对")
    void pageKeywordTruncatedAtCodePointBoundary() {
        users.put(1L, user(1L, "gate6_user", "ACTIVE"));
        totalReturn = 1L;
        service.page("a".repeat(64) + "OVER", null, new PageQuery());
        assertThat(lastListParams).anyMatch(v -> String.valueOf(v).contains("a".repeat(64)));
        assertThat(lastListParams).noneMatch(v -> String.valueOf(v).contains("OVER"));
        // 正例对照：正好 64 码点不截，落在第 64 位的哨兵必须还在
        service.page("a".repeat(63) + "K", null, new PageQuery());
        assertThat(lastListParams).anyMatch(v -> String.valueOf(v).contains("a".repeat(63) + "K"));
        // 边界：63 个 a + 1 个 emoji（占 2 个 UTF-16 单元）+ TAIL = 68 码点、69 单元。
        // 按单元截会把 emoji 劈成非法字符（真库侧是 Incorrect string value 或静默变 ?），
        // 按码点截则完整保留表情、只丢掉后面的 TAIL——这一条就是本轮改源码的理由。
        service.page("a".repeat(63) + "\uD83D\uDE42TAIL", null, new PageQuery());
        assertThat(lastListParams).anyMatch(v -> String.valueOf(v).contains("a".repeat(63) + "\uD83D\uDE42"));
        assertThat(lastListParams).noneMatch(v -> String.valueOf(v).contains("TAIL"));
        assertThat(lastListParams).allSatisfy(v -> {
            String s = String.valueOf(v);
            for (int i = 0; i < s.length(); i++) {
                if (Character.isHighSurrogate(s.charAt(i))) {
                    boolean paired = i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
                    assertThat(paired).as("截断处留下了半个代理对：[%s]", s).isTrue();
                    i++;
                }
            }
        });
    }

    // ------------------------------------------------------------------ A6 详情

    @Test
    @DisplayName("详情四路聚合：账号本体 + 公开帖数 + 被点赞数 + 工单时间线(最近 20) + 用过的马甲")
    void detailAggregatesPostsLikesTicketsAndAliases() {
        users.put(495L, user(495L, "gate6_target", "ACTIVE"));
        publicPostCnt = 12L;
        receivedLikeCnt = 340L;
        AlertTicket ticket = new AlertTicket();
        ticket.setId(178L);
        ticket.setUserId(495L);
        timelineRows.add(ticket);
        aliases.put(88L, alias(88L, 495L, "匿名小鲸", null));
        UserDetail detail = service.detail(495L);
        assertThat(detail.user().getUsername()).isEqualTo("gate6_target");
        assertThat(detail.publicPostCnt()).isEqualTo(12L);
        assertThat(detail.receivedLikeCnt()).isEqualTo(340L);
        assertThat(detail.tickets()).hasSize(1);
        assertThat(detail.aliases()).extracting(AnonymousAlias::getAliasName)
            .containsExactly("匿名小鲸");
        // 20 是服务写死的窗口：传错会让抽屉里「最近危机记录」变成全量刷屏
        assertThat(lastTimelineLimit).isEqualTo(20);
    }

    @Test
    @DisplayName("查不到的账号直接 USER_NOT_FOUND，不返回一份字段全 null 的「空详情」")
    void detailUnknownUserIsNotFound() {
        BizException e = assertThrows(BizException.class, () -> service.detail(9999L));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    // ------------------------------------------------------------------ A6 禁言

    @Test
    @DisplayName("禁言时长只认 1/7/30：非法值先报错，此时还轮不到「理由为空」那行 DENIED")
    void muteRejectsIllegalDaysBeforeAnyAudit() {
        users.put(495L, user(495L, "gate6_target", "ACTIVE"));
        for (int days : new int[] {0, 3, 15, 31, -1}) {
            opLogs.clear();
            updateByIdCalls = 0;
            BizException e = assertThrows(BizException.class,
                () -> service.mute(495L, days, "刷屏骚扰", SUPER, NOW), "天数 " + days + " 不该被接受");
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
            assertThat(e).hasMessageContaining("禁言时长只能是 1/7/30 天");
            // 这条顺序是刻意的：先验天数、再验理由，所以此处连一行 DENIED 都不该有
            assertThat(opLogs).isEmpty();
            assertThat(updateByIdCalls).isZero();
        }
        // 正例对照
        assertThat(service.mute(495L, 1, "刷屏骚扰", SUPER, NOW).getStatus()).isEqualTo("MUTED");
    }

    @Test
    @DisplayName("没写依据就想禁言：先落一行 DENIED 再抛错，库里状态一个字节都不动")
    void muteWithoutReasonWritesDeniedThenThrows() {
        users.put(495L, user(495L, "gate6_target", "ACTIVE"));
        for (String blank : new String[] {null, "", "   "}) {
            opLogs.clear();
            updateByIdCalls = 0;
            notify.clear();
            BizException e = assertThrows(BizException.class,
                () -> service.mute(495L, 7, blank, SUPER, NOW));
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
            assertThat(e).hasMessageContaining("必须填写理由");
            assertThat(opLogs).hasSize(1);
            assertThat(lastLog().getResult()).isEqualTo(AdminOpLog.RESULT_DENIED);
            assertThat(lastLog().getAction()).isEqualTo(AdminOpLog.ACTION_MUTE_USER);
            assertThat(lastLog().getTarget()).isEqualTo("user:495");
            assertThat(lastLog().getDetail()).isEqualTo("处置理由为空，已拒绝执行");
            assertThat(updateByIdCalls).isZero();
            assertThat(stored(495L).getStatus()).isEqualTo("ACTIVE");
            assertThat(notify.size()).isZero();
        }
    }

    @Test
    @DisplayName("禁言写成 status=MUTED 与 mute_until 这一对事实，并当场通知当事人能做什么不能做什么")
    void muteWritesStatusUntilAndNotifiesAccount() {
        users.put(495L, user(495L, "gate6_target", "ACTIVE"));
        User back = service.mute(495L, 7, "连续 12 条人身攻击", SUPER, NOW);
        User row = stored(495L);
        assertThat(row.getStatus()).isEqualTo(UserManageService.STATUS_MUTED);
        assertThat(row.getMuteUntil()).isEqualTo(NOW.plusDays(7));
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
        // 服务返回的是回读结果而不是手里那个旧实例：前端拿它刷新表格，不能还是改写前的样子
        assertThat(back.getStatus()).isEqualTo("MUTED");
        assertThat(back.getMuteUntil()).isEqualTo(NOW.plusDays(7));
        assertThat(updateByIdCalls).isEqualTo(1);

        List<NotifyMessage> system = notify.ofType(NotifyMessage.TYPE_SYSTEM);
        assertThat(system).hasSize(1);
        assertThat(system.get(0).getUserId()).isEqualTo(495L);
        assertThat(system.get(0).getTitle()).isEqualTo("你的账号已被限制发布 7 天");
        // FR8.3 的可测口径：能读、能点赞，禁的是写
        assertThat(system.get(0).getContent()).contains("不能发帖、评论、发私信")
            .contains("仍可浏览社区、点赞与收藏");

        assertThat(opLogs).hasSize(1);
        AdminOpLog log = lastLog();
        assertThat(log.getResult()).isEqualTo(AdminOpLog.RESULT_SUCCESS);
        assertThat(log.getAction()).isEqualTo(AdminOpLog.ACTION_MUTE_USER);
        assertThat(log.getOperatorId()).isEqualTo(486L);
        assertThat(log.getOperatorRole()).isEqualTo("SUPER");
        assertThat(log.getTargetId()).isEqualTo(495L);
        assertThat(log.getDetail()).contains("禁言 7天 至 " + NOW.plusDays(7))
            .contains("理由=连续 12 条人身攻击");
    }

    @Test
    @DisplayName("处置理由按码点截到 500：超长依据不许原样流进审计文案与当事人通知")
    void muteCutsReasonTo500CodePoints() {
        users.put(495L, user(495L, "gate6_target", "ACTIVE"));
        service.mute(495L, 30, "依".repeat(500) + "OVER", SUPER, NOW);
        assertThat(lastLog().getDetail()).contains("依".repeat(500)).doesNotContain("OVER");
        assertThat(notify.ofType(NotifyMessage.TYPE_SYSTEM).get(0).getContent())
            .doesNotContain("OVER");
        // 正例对照：正好 500 码点不截，落在第 500 位的哨兵必须还在
        users.put(496L, user(496L, "gate6_other", "ACTIVE"));
        service.mute(496L, 1, "依".repeat(499) + "K", SUPER, NOW);
        assertThat(lastLog().getDetail()).contains("依".repeat(499) + "K");
    }

    @Test
    @DisplayName("注销冷静期的账号不参与禁言：改它会打乱保留期作业，且不留下一行 SUCCESS")
    void muteRefusesAccountInDeletionCoolingPeriod() {
        users.put(495L, user(495L, "gate6_ghost", UserManageService.STATUS_DELETED));
        BizException e = assertThrows(BizException.class,
            () -> service.mute(495L, 7, "仍然骚扰", SUPER, NOW));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(e).hasMessageContaining("注销冷静期");
        assertThat(updateByIdCalls).isZero();
        assertThat(opLogs).isEmpty();
        assertThat(notify.size()).isZero();
        assertThat(stored(495L).getStatus()).isEqualTo(UserManageService.STATUS_DELETED);
    }

    @Test
    @DisplayName("禁言一个不存在的账号：USER_NOT_FOUND，既不写通知也不留成功审计")
    void muteUnknownUserLeavesNoTrace() {
        BizException e = assertThrows(BizException.class,
            () -> service.mute(9999L, 7, "查无此人", SUPER, NOW));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThat(updateByIdCalls).isZero();
        assertThat(opLogs).isEmpty();
        assertThat(notify.size()).isZero();
    }

    // ------------------------------------------------------------------ A6 封禁与恢复

    @Test
    @DisplayName("封禁必须把 mute_until 显式清掉：留着旧到期时间，界面会显示「几天后自动恢复」")
    void banClearsMuteUntilSoStatusIsTheOnlyTruth() {
        User muted = user(495L, "gate6_target", UserManageService.STATUS_MUTED);
        muted.setMuteUntil(NOW.plusDays(30));
        users.put(495L, muted);
        User back = service.ban(495L, "多次发布自伤引导内容", ADMIN, NOW);
        assertThat(back.getStatus()).isEqualTo(UserManageService.STATUS_BANNED);
        assertThat(stored(495L).getMuteUntil()).isNull();
        assertThat(notify.ofType(NotifyMessage.TYPE_SYSTEM).get(0).getTitle())
            .isEqualTo("你的账号已被封禁");
        assertThat(lastLog().getAction()).isEqualTo(AdminOpLog.ACTION_BAN_USER);
        assertThat(lastLog().getOperatorRole()).isEqualTo("ADMIN");
        assertThat(lastLog().getDetail()).startsWith("封禁|理由=");
    }

    @Test
    @DisplayName("封禁同样要书面依据：空理由先 DENIED 再抛，库里仍是 ACTIVE")
    void banWithoutReasonDenied() {
        users.put(495L, user(495L, "gate6_target", "ACTIVE"));
        BizException e = assertThrows(BizException.class,
            () -> service.ban(495L, "  ", SUPER, NOW));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(opLogs).hasSize(1);
        assertThat(lastLog().getAction()).isEqualTo(AdminOpLog.ACTION_BAN_USER);
        assertThat(lastLog().getResult()).isEqualTo(AdminOpLog.RESULT_DENIED);
        assertThat(updateByIdCalls).isZero();
        assertThat(stored(495L).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("解禁走 releaseMute 而不是 updateById：四个参数一个都不能少，恢复时间与状态对得上")
    void restoreFromMutedUsesReleaseMuteOnly() {
        User muted = user(495L, "gate6_target", UserManageService.STATUS_MUTED);
        muted.setMuteUntil(NOW.plusDays(3));
        users.put(495L, muted);
        User back = service.restore(495L, "申诉核实为误判", SUPER, NOW);
        assertThat(releaseMuteCalls).hasSize(1);
        assertThat(releaseMuteCalls.get(0)).containsExactly(495L,
            UserManageService.STATUS_MUTED, UserManageService.STATUS_ACTIVE, NOW);
        assertThat(restoreActiveCalls).isEmpty();
        assertThat(back.getStatus()).isEqualTo(UserManageService.STATUS_ACTIVE);
        assertThat(stored(495L).getMuteUntil()).isNull();
        assertThat(updateByIdCalls).isZero();
        assertThat(notify.ofType(NotifyMessage.TYPE_SYSTEM).get(0).getTitle())
            .isEqualTo("你的账号已恢复正常");
        assertThat(lastLog().getDetail()).isEqualTo("恢复 MUTED->ACTIVE|理由=申诉核实为误判");
        assertThat(lastLog().getAction()).isEqualTo(AdminOpLog.ACTION_RESTORE_USER);
    }

    @Test
    @DisplayName("解封走 restoreActive：它把 deactivate_at/purge_at 一起清空的语义已经钉在 SQL 里")
    void restoreFromBannedUsesRestoreActive() {
        users.put(495L, user(495L, "gate6_target", UserManageService.STATUS_BANNED));
        User back = service.restore(495L, "心理委员复核通过", ADMIN, NOW);
        assertThat(restoreActiveCalls).hasSize(1);
        assertThat(restoreActiveCalls.get(0)).containsExactly(495L,
            UserManageService.STATUS_BANNED, UserManageService.STATUS_ACTIVE);
        assertThat(releaseMuteCalls).isEmpty();
        assertThat(back.getStatus()).isEqualTo(UserManageService.STATUS_ACTIVE);
        assertThat(lastLog().getDetail()).startsWith("恢复 BANNED->ACTIVE|理由=");
    }

    @Test
    @DisplayName("只有禁言或封禁才需要恢复：对 ACTIVE/DELETED 下手先留 DENIED，也不碰任何写口")
    void restoreRefusesOtherStatusesWithDenied() {
        for (String status : new String[] {"ACTIVE", UserManageService.STATUS_DELETED}) {
            users.put(495L, user(495L, "gate6_target", status));
            opLogs.clear();
            releaseMuteCalls.clear();
            restoreActiveCalls.clear();
            notify.clear();
            BizException e = assertThrows(BizException.class,
                () -> service.restore(495L, "看起来正常也想点一下恢复", SUPER, NOW));
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
            assertThat(e).hasMessageContaining("当前 " + status);
            assertThat(opLogs).hasSize(1);
            assertThat(lastLog().getResult()).isEqualTo(AdminOpLog.RESULT_DENIED);
            assertThat(lastLog().getDetail()).isEqualTo("当前状态 " + status + "，不是禁言或封禁");
            assertThat(releaseMuteCalls).isEmpty();
            assertThat(restoreActiveCalls).isEmpty();
            assertThat(updateByIdCalls).isZero();
            assertThat(notify.size()).isZero();
            assertThat(stored(495L).getStatus()).isEqualTo(status);
        }
    }

    @Test
    @DisplayName("恢复撞上并发：影响 0 行要留 FAIL（不是 DENIED）、不发通知、不假装成功")
    void restoreRaceWritesFailAndDoesNotNotify() {
        users.put(495L, user(495L, "gate6_target", UserManageService.STATUS_MUTED));
        raceWrites = true;
        BizException e = assertThrows(BizException.class,
            () -> service.restore(495L, "误判", SUPER, NOW));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(releaseMuteCalls).hasSize(1);
        assertThat(logsOf(AdminOpLog.RESULT_DENIED)).isEmpty();
        assertThat(logsOf(AdminOpLog.RESULT_SUCCESS)).isEmpty();
        assertThat(logsOf(AdminOpLog.RESULT_FAIL)).hasSize(1);
        assertThat(lastLog().getDetail()).isEqualTo("状态并发变更，恢复未生效");
        assertThat(notify.size()).isZero();
        // 假库里的状态没被改写：并发方把它改成了别的，这次恢复一个字都没落
        assertThat(stored(495L).getStatus()).isEqualTo(UserManageService.STATUS_MUTED);
    }

    @Test
    @DisplayName("恢复也要书面依据：空理由先 DENIED，此时还不该出现「当前状态不对」那行")
    void restoreWithoutReasonDeniedFirst() {
        users.put(495L, user(495L, "gate6_target", UserManageService.STATUS_MUTED));
        BizException e = assertThrows(BizException.class,
            () -> service.restore(495L, "", SUPER, NOW));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(opLogs).hasSize(1);
        assertThat(lastLog().getDetail()).isEqualTo("处置理由为空，已拒绝执行");
        assertThat(releaseMuteCalls).isEmpty();
        assertThat(updateByIdCalls).isZero();
    }

    @Test
    @DisplayName("恢复一个不存在的账号：USER_NOT_FOUND，且不会留下一行 SUCCESS 冒充处置过")
    void restoreUnknownUserLeavesNoSuccessTrace() {
        BizException e = assertThrows(BizException.class,
            () -> service.restore(9999L, "查无此人", SUPER, NOW));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThat(opLogs).isEmpty();
        assertThat(releaseMuteCalls).isEmpty();
    }

    // ------------------------------------------------------------------ T6.5 解匿

    @Test
    @DisplayName("非 SUPER 想解匿：先留 DENIED 再抛，且马甲表一次都没被查过（Gate6 第 63 轮的真 bug）")
    void revealByNonSuperIsDeniedBeforeTouchingTheAliasTable() {
        // aliasId 故意写一个不存在的：旧实现先查库，于是在 404 上返回，越权探测一行证据都不留
        BizException e = assertThrows(BizException.class,
            () -> service.revealAnonymous(9999L, "学校书面申请编号 3", ADMIN));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(e).hasMessageContaining("解匿仅限超级管理员");
        assertThat(aliasSelectByIdCalls).isZero();
        assertThat(opLogs).hasSize(1);
        AdminOpLog log = lastLog();
        assertThat(log.getAction()).isEqualTo(AdminOpLog.ACTION_REVEAL_ANONYMOUS);
        assertThat(log.getResult()).isEqualTo(AdminOpLog.RESULT_DENIED);
        assertThat(log.getOperatorId()).isEqualTo(487L);
        assertThat(log.getOperatorRole()).isEqualTo("ADMIN");
        assertThat(log.getTarget()).isEqualTo("alias:9999");
        assertThat(log.getTargetId()).isEqualTo(9999L);
        assertThat(log.getDetail()).isEqualTo("非 SUPER 角色尝试解匿，当前角色=ADMIN");
        // 正例对照：同一件事由 SUPER 来做就查得到记录（资格判定没有把合法调用一起挡掉）
        aliases.put(88L, alias(88L, 495L, "匿名小鲸", null));
        users.put(495L, user(495L, "gate6_target", "ACTIVE"));
        assertThat(service.revealAnonymous(88L, "涉生命安全", SUPER).aliasId()).isEqualTo(88L);
        assertThat(aliasSelectByIdCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("连身份上下文都没有的调用也留痕：角色写成 null，而不是安静地跳过审计")
    void revealWithNullCtxStillDeniesAndRecordsRoleNull() {
        aliases.put(88L, alias(88L, 495L, "匿名小鲸", null));
        BizException e = assertThrows(BizException.class,
            () -> service.revealAnonymous(88L, "有依据也不给", null));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(aliasSelectByIdCalls).isZero();
        assertThat(lastLog().getDetail()).isEqualTo("非 SUPER 角色尝试解匿，当前角色=null");
        assertThat(lastLog().getOperatorRole()).isNull();
    }

    @Test
    @DisplayName("SUPER 但没填依据：DENIED 写「理由为空」，并且同样不去查马甲表")
    void revealWithoutReasonDeniedBeforeLookup() {
        aliases.put(88L, alias(88L, 495L, "匿名小鲸", null));
        for (String blank : new String[] {null, "", "   "}) {
            opLogs.clear();
            aliasSelectByIdCalls = 0;
            BizException e = assertThrows(BizException.class,
                () -> service.revealAnonymous(88L, blank, SUPER));
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
            assertThat(e).hasMessageContaining("一个字符都不能少");
            assertThat(aliasSelectByIdCalls).isZero();
            assertThat(opLogs).hasSize(1);
            assertThat(lastLog().getResult()).isEqualTo(AdminOpLog.RESULT_DENIED);
            assertThat(lastLog().getDetail()).isEqualTo("理由为空，拒绝解匿");
        }
    }

    @Test
    @DisplayName("SUPER 且理由充分但别名不存在：404，这种「没看到任何东西」的读不需要留痕")
    void revealUnknownAliasIsNotFoundAndWritesNoAudit() {
        BizException e = assertThrows(BizException.class,
            () -> service.revealAnonymous(9999L, "学校书面申请编号 7", SUPER));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(aliasSelectByIdCalls).isEqualTo(1);
        assertThat(opLogs).isEmpty();
        assertThat(aliasUpdateCalls).isZero();
    }

    @Test
    @DisplayName("解匿成功：审计先落库、拿到行 id 再回写 revealed_log_id，界面文案与服务端同源")
    void revealSuccessWritesAuditFirstThenBackfillsLogId() {
        aliases.put(88L, alias(88L, 495L, "匿名小鲸", null));
        users.put(495L, user(495L, "gate6_target", "ACTIVE"));
        RevealResult result = service.revealAnonymous(88L, "学校心理咨询中心书面申请 2026-0929", SUPER);
        assertThat(result.aliasId()).isEqualTo(88L);
        assertThat(result.aliasName()).isEqualTo("匿名小鲸");
        assertThat(result.userId()).isEqualTo(495L);
        assertThat(result.username()).isEqualTo("gate6_target");
        assertThat(result.nickname()).isEqualTo("gate6_target的昵称");
        assertThat(result.notice()).isEqualTo(UserManageService.REVEAL_NOTICE);

        assertThat(opLogs).hasSize(1);
        AdminOpLog log = lastLog();
        assertThat(log.getResult()).isEqualTo(AdminOpLog.RESULT_SUCCESS);
        assertThat(log.getAction()).isEqualTo(AdminOpLog.ACTION_REVEAL_ANONYMOUS);
        assertThat(log.getOperatorRole()).isEqualTo("SUPER");
        assertThat(log.getDetail()).contains("解匿别名 匿名小鲸 -> user 495")
            .contains("依据=学校心理咨询中心书面申请 2026-0929")
            .contains(UserManageService.REVEAL_NOTICE);
        // 顺序判据：回写那一刻，审计已经躺在库里（反过来自愈不了「已解匿却没日志」）
        assertThat(aliasUpdateCalls).isEqualTo(1);
        assertThat(aliasUpdateSeenLogs).containsExactly(1);
        assertThat(aliases.get(88L).getRevealedLogId()).isEqualTo(log.getId());
        assertThat(result.opLogId()).isEqualTo(log.getId());
    }

    @Test
    @DisplayName("二次解匿不覆盖首次留痕 id：revealed_log_id 记的是「第一次被揭开」，但审计每行都写")
    void revealOnAlreadyRevealedAliasKeepsOldLogIdAndStillAudits() {
        aliases.put(88L, alias(88L, 495L, "匿名小鲸", 77L));
        users.put(495L, user(495L, "gate6_target", "ACTIVE"));
        RevealResult result = service.revealAnonymous(88L, "同一案件二次核对", SUPER);
        assertThat(aliasUpdateCalls).isZero();
        assertThat(aliases.get(88L).getRevealedLogId()).isEqualTo(77L);
        assertThat(opLogs).hasSize(1);
        assertThat(result.opLogId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("别名后面没有真人（历史脏数据）：返回 0 与 null，不抛 NPE、也不去查用户表")
    void revealWithoutUserIdReturnsZeroAndNoIdentityFields() {
        aliases.put(89L, alias(89L, null, "匿名孤舟", null));
        RevealResult result = service.revealAnonymous(89L, "排查空指向", SUPER);
        assertThat(result.userId()).isZero();
        assertThat(result.nickname()).isNull();
        assertThat(result.username()).isNull();
        assertThat(result.aliasName()).isEqualTo("匿名孤舟");
        assertThat(lastLog().getDetail()).contains("解匿别名 匿名孤舟 -> user null");
        assertThat(aliasUpdateSeenLogs).containsExactly(1);
    }

    @Test
    @DisplayName("解匿依据同样先 trim 再按码点截 500：空格与超长申请正文都不进审计")
    void revealTrimsAndCutsReasonTo500CodePoints() {
        aliases.put(88L, alias(88L, 495L, "匿名小鲸", null));
        users.put(495L, user(495L, "gate6_target", "ACTIVE"));
        service.revealAnonymous(88L, "   书面申请   ", SUPER);
        assertThat(lastLog().getDetail()).contains("|依据=书面申请|");
        service.revealAnonymous(88L, "依".repeat(500) + "OVER", SUPER);
        assertThat(lastLog().getDetail()).contains("依".repeat(500)).doesNotContain("OVER");
    }

    // ------------------------------------------------------------------ 其它只读口

    @Test
    @DisplayName("全站解匿次数只数 SUCCESS 行：越权被挡下的那些进 §17 的 DENIED 口径，不算解匿")
    void revealCountCountsOnlySuccessRows() {
        aliases.put(88L, alias(88L, 495L, "匿名小鲸", null));
        users.put(495L, user(495L, "gate6_target", "ACTIVE"));
        assertThrows(BizException.class, () -> service.revealAnonymous(88L, "试试", ADMIN));
        assertThat(service.revealCount()).isZero();
        service.revealAnonymous(88L, "学校书面申请", SUPER);
        assertThat(service.revealCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("帖子指向的别名：查得到就回 alias_id，帖子不存在回 null（A7 的解匿按钮靠它决定显不显示）")
    void aliasIdOfPostMapsPostToAlias() {
        Post anonymous = new Post();
        anonymous.setId(1286L);
        anonymous.setAliasId(88L);
        posts.put(1286L, anonymous);
        Post normal = new Post();
        normal.setId(1288L);
        posts.put(1288L, normal);
        assertThat(service.aliasIdOfPost(1286L)).isEqualTo(88L);
        assertThat(service.aliasIdOfPost(1288L)).isNull();
        assertThat(service.aliasIdOfPost(9999L)).isNull();
    }
}

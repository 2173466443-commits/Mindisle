package com.mindisle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mindisle.admin.dto.StatusCountRow;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.entity.AdminOpLog;
import com.mindisle.mapper.AdminOpLogMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 管理端特权操作审计单测（任务 T6.6 · 需求 BR10「特权操作 100% 留痕」· 手册 §9.1/§9.4）。
 *
 * <p><b>为什么值得单独钉住</b>：{@code admin_op_log} 是整张治理系统里唯一回答
 * 「当时他有没有权力做这件事」的表。它出问题的方式不是报错，而是<b>安静地写进一行查不到的数据</b>：
 * 动作码拼错一个字母，A9 按 action 筛选永远筛不到它，界面上一句「今天没有解匿操作」听起来完全成立，
 * 真相是审计丢了。所以本类的核心判据不是「写进去了」，而是
 * ① 写错动作码必须当场抛，并且<b>一次都不许落到 mapper</b>；
 * ② 白名单必须与 {@link AdminOpLog} 里的常量<b>双向相等</b>（新增常量忘了进白名单、
 *    或白名单里留着已删除的常量，两种脱节都在这一条里暴露）。</p>
 *
 * <p><b>替身复刻的契约</b>：{@link AdminOpLogMapper} 用 {@link Proxy} 假实现，
 * 因为本类只关心「传进来的那一行长什么样」；列宽截断（64/128/45/255/32）逐字抄自
 * {@code sql/08_config.sql} 与 {@code sql/07_audit.sql} 的 DDL，
 * 并按<b>码点</b>而不是 UTF-16 单元截——审计文案是中文，还有 emoji UA，
 * 按 char 截会把代理对劈成半个字符，MySQL 收到直接报编码错，那就不是「截断」而是「写入失败回滚业务」。</p>
 *
 * <p><b>本类刻意不测的东西</b>：{@code pageFiltered}/{@code countFiltered} 那两条 {@code <script>} SQL
 * 的 WHERE 形状（MyBatis 的 {@code <if>} 与 {@code &gt;=} 转义）、Controller 怎么从
 * {@code AuthUser} 与 {@code HttpServletRequest} 拼 {@link AdminOpLogService.Ctx}——
 * 那是「接线错」不是「规则错」，由 {@code frontend/probe/admingate6.mjs} 的 P13 留痕自检
 * 与 {@code admin-smoke.mjs} 打真 HTTP 取证。</p>
 */
class AdminOpLogServiceTest {

    /** 捕获下来的每一次 insert，顺序即调用顺序。 */
    private final List<AdminOpLog> inserted = new ArrayList<>();

    /** 最近一次 pageFiltered 的入参，用来验 offset/size 与白名单前置。 */
    private Object[] lastPageArgs;

    private final LocalDateTime from = LocalDateTime.of(2026, 9, 1, 0, 0);

    private AdminOpLogService service;

    private static final AdminOpLogService.Ctx CTX =
        new AdminOpLogService.Ctx(77L, "SUPER", "203.0.113.9", "Mozilla/5.0 (Gate6)");

    @BeforeEach
    void setUp() {
        AdminOpLogMapper mapper = (AdminOpLogMapper) Proxy.newProxyInstance(
            AdminOpLogMapper.class.getClassLoader(), new Class<?>[] {AdminOpLogMapper.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "insert" -> {
                    AdminOpLog row = (AdminOpLog) args[0];
                    row.setId((long) inserted.size() + 1L);
                    inserted.add(row);
                    yield 1;
                }
                case "pageFiltered" -> {
                    lastPageArgs = args;
                    yield new ArrayList<>(inserted);
                }
                case "countFiltered" -> (long) inserted.size();
                case "countGroupByAction" -> List.of(new StatusCountRow());
                case "countReveals" -> 3L;
                default -> throw new IllegalStateException("单测没替这条方法打桩：" + method.getName());
            });
        service = new AdminOpLogService(mapper);
    }

    // ------------------------------------------------------------------ 白名单

    @Test
    @DisplayName("白名单与实体常量双向相等：新增动作忘了进白名单，在这里就红")
    void whitelistMatchesEntityConstants() {
        Set<String> declared = new LinkedHashSet<>();
        for (Field f : AdminOpLog.class.getFields()) {
            if (f.getName().startsWith("ACTION_") && f.getType() == String.class) {
                try {
                    declared.add(String.valueOf(f.get(null)));
                } catch (IllegalAccessException e) {
                    throw new AssertionError("ACTION_ 常量应当是 public static final，不该取不到", e);
                }
            }
        }
        assertThat(declared).isNotEmpty();
        assertThat(AdminOpLogService.ACTIONS).containsExactlyInAnyOrderElementsOf(declared);
        assertThat(AdminOpLogService.ACTIONS).hasSize(18);
    }

    @Test
    @DisplayName("未知动作码必须抛，而且一次都不许落库（宁可失败也不留一条查不到的审计）")
    void unknownActionThrowsAndNeverInserts() {
        BizException e = assertThrows(BizException.class,
            () -> service.success(CTX, AdminOpLog.ACTION_REVEAL_ANONYMOUS + "X", "post:1", 1L, "多打一个字母：白名单里没有这个码"));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(e).hasMessageContaining(AdminOpLog.ACTION_REVEAL_ANONYMOUS + "X");
        assertThat(inserted).isEmpty();
        // 正例对照：多一个字母被挡，拼写完整就必须落库——
        // 少了这一句，「白名单」其实把一切全挡掉也会照样绿。
        service.success(CTX, AdminOpLog.ACTION_REVEAL_ANONYMOUS, "alias:1", 1L, "拼写完整的正例");
        assertThat(inserted).hasSize(1);
        assertThat(inserted.get(0).getResult()).isEqualTo(AdminOpLog.RESULT_SUCCESS);
    }

    @Test
    @DisplayName("null 与空串动作码同样被挡（空串进 SQL 等于不加筛选，会把整表当成一次操作的审计）")
    void nullAndBlankActionRejected() {
        assertThrows(BizException.class, () -> service.success(CTX, null, null, null, null));
        assertThrows(BizException.class, () -> service.success(CTX, "", null, null, null));
        assertThat(inserted).isEmpty();
    }

    // ------------------------------------------------------------------ 三态

    @Test
    @DisplayName("SUCCESS / DENIED / FAIL 三态都写得出，result 分别是三个常量")
    void threeResultsAreAllWritable() {
        service.success(CTX, AdminOpLog.ACTION_REVEAL_ANONYMOUS, "alias:12", 12L, "危机干预需要联系本人");
        service.denied(CTX, AdminOpLog.ACTION_REVEAL_ANONYMOUS, "alias:12", 12L, "ADMIN 越权");
        service.fail(CTX, AdminOpLog.ACTION_MUTE_USER, "user:3", 3L, "数据库超时");
        assertThat(inserted).hasSize(3);
        assertThat(inserted).extracting(AdminOpLog::getResult)
            .containsExactly(AdminOpLog.RESULT_SUCCESS, AdminOpLog.RESULT_DENIED, AdminOpLog.RESULT_FAIL);
    }

    @Test
    @DisplayName("DENIED 是一等公民：越权尝试必须留痕，且带得上操作人、角色快照、IP、UA")
    void deniedCarriesIdentity() {
        service.denied(CTX, AdminOpLog.ACTION_POST_TAKEDOWN, "post:88", 88L, "非 ADMIN 角色");
        AdminOpLog row = inserted.get(0);
        assertThat(row.getOperatorId()).isEqualTo(77L);
        assertThat(row.getOperatorRole()).isEqualTo("SUPER");
        assertThat(row.getIp()).isEqualTo("203.0.113.9");
        assertThat(row.getUserAgent()).isEqualTo("Mozilla/5.0 (Gate6)");
        assertThat(row.getTarget()).isEqualTo("post:88");
        assertThat(row.getTargetId()).isEqualTo(88L);
        assertThat(row.getDetail()).isEqualTo("非 ADMIN 角色");
        assertThat(row.getDeleted()).isZero();
    }

    @Test
    @DisplayName("ctx 为 null 不许 NPE：审计写不出来时，宁可留一行空身份也不要整笔事务炸掉")
    void nullCtxIsNotANpe() {
        assertThatCode(() -> service.success(null, AdminOpLog.ACTION_EXPORT_CSV, null, null, "csv:tickets"))
            .doesNotThrowAnyException();
        AdminOpLog row = inserted.get(0);
        assertThat(row.getOperatorId()).isNull();
        assertThat(row.getOperatorRole()).isNull();
        assertThat(row.getIp()).isNull();
        assertThat(row.getUserAgent()).isNull();
        assertThat(row.getAction()).isEqualTo(AdminOpLog.ACTION_EXPORT_CSV);
    }

    @Test
    @DisplayName("返回的 id 来自 mapper（调用方要用它写第二跳留痕，返回 null 就等于丢主键）")
    void returnsInsertedId() {
        assertThat(service.success(CTX, AdminOpLog.ACTION_AUDIT_CLAIM, "task:1", 1L, "认领")).isEqualTo(1L);
        assertThat(service.success(CTX, AdminOpLog.ACTION_AUDIT_PASS, "task:1", 1L, "通过")).isEqualTo(2L);
    }

    // ------------------------------------------------------------------ 截断

    @Test
    @DisplayName("五列各自按 DDL 宽度截断：action 64 / target 128 / ip 45 / ua 255 / role 32")
    void cutsToColumnWidths() {
        service.success(new AdminOpLogService.Ctx(1L, repeat("R", 40), repeat("9", 60), repeat("U", 400)),
            AdminOpLog.ACTION_AUDIT_PASS, repeat("T", 300), 1L, "detail");
        AdminOpLog row = inserted.get(0);
        // action 列宽这条只能在纯函数上验：白名单会先把不存在的动作码挡掉，
        // 永远不可能有一个 100 个字符的合法动作码走到截断那一步。
        assertThat(AdminOpLogService.cut(repeat("A", 100), AdminOpLogService.ACTION_MAX)).hasSize(64);
        assertThat(row.getTarget()).hasSize(AdminOpLogService.TARGET_MAX);
        assertThat(row.getIp()).hasSize(AdminOpLogService.IP_MAX);
        assertThat(row.getUserAgent()).hasSize(AdminOpLogService.UA_MAX);
        assertThat(row.getOperatorRole()).hasSize(AdminOpLogService.ROLE_MAX);
    }

    @Test
    @DisplayName("detail 不截：它是 TEXT 列，也是「为什么解匿」的唯一证据，截掉等于销毁理由")
    void detailIsNotCut() {
        String longReason = repeat("事由", 300);
        service.success(CTX, AdminOpLog.ACTION_REVEAL_ANONYMOUS, "alias:1", 1L, longReason);
        assertThat(inserted.get(0).getDetail()).isEqualTo(longReason);
    }

    @Test
    @DisplayName("按码点截断：中文不占两个单元，emoji 代理对不被劈成半个字符")
    void cutsByCodePointNotUtf16Unit() {
        String cn = repeat("心屿", 40);
        assertThat(AdminOpLogService.cut(cn, 64)).hasSize(64).startsWith("心屿心屿");
        // 16 个 emoji = 16 码点 = 32 个 UTF-16 单元；截到 8 码点必须还是 8 个完整 emoji
        String emoji = repeat("🙂", 16);
        String cut = AdminOpLogService.cut(emoji, 8);
        assertThat(cut).isEqualTo(repeat("🙂", 8));
        assertThat(cut.length()).isEqualTo(16);
        // 判据要问的是「这一刀有没有落在码点边界上」，而不是「结尾是不是代理面字符」——
        // 一个完整的 emoji 结尾本来就是下半截（低代理），原来那两句把方向写反了，
        // 恰好证明它们从没在正确的实现上绿过。
        char last = cut.charAt(cut.length() - 1);
        assertThat(Character.isLowSurrogate(last)).isTrue();
        assertThat(Character.isHighSurrogate(last)).isFalse();
        // 反例：按 UTF-16 单元切 9 个会留下一个孤立的高代理，插进 MySQL 直接报编码错。
        assertThat(Character.isHighSurrogate(emoji.substring(0, 9).charAt(8))).isTrue();
        assertThat(Character.isLowSurrogate(emoji.substring(0, 9).charAt(8))).isFalse();
        // 按码点切 9 个则是 18 个单元，一个字符都不少。
        assertThat(AdminOpLogService.cut(emoji, 9).length()).isEqualTo(18);
    }

    @Test
    @DisplayName("cut 的边界：null / 空串 / 正好等宽 / 超长 1 个码点")
    void cutEdges() {
        assertThat(AdminOpLogService.cut(null, 8)).isNull();
        assertThat(AdminOpLogService.cut("", 8)).isEmpty();
        assertThat(AdminOpLogService.cut("abcdefg", 7)).isEqualTo("abcdefg");
        assertThat(AdminOpLogService.cut("abcdefgh", 7)).isEqualTo("abcdefg");
        assertThat(AdminOpLogService.cut("ab", 0)).isEqualTo("");
    }

    // ------------------------------------------------------------------ 查询侧

    @Test
    @DisplayName("page 的 action 也过白名单：筛一个不存在的动作码必须报参数错，不能给一张空表")
    void pageRejectsUnknownActionFilter() {
        BizException e = assertThrows(BizException.class,
            () -> service.page(null, "NOT_A_REAL_ACTION", null, from, null, new PageQuery()));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(lastPageArgs).isNull();
    }
    @Test
    @DisplayName("page 的 result 同样过白名单：非法结果码报参数错，合法值原样进 SQL 第 3 个参数")
    void pageFiltersByResultWhitelist() {
        BizException e = assertThrows(BizException.class,
            () -> service.page(null, AdminOpLog.ACTION_REVEAL_ANONYMOUS, "MAYBE", null, null, new PageQuery()));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        // 白名单必须在打 SQL 之前挡住：放到 SQL 里筛，非法值会安静地返回一张空表，
        // 而 A6 深链带错 result 时界面会说「没有这条留痕」——那正是 FR8.4 最怕的歧义。
        assertThat(lastPageArgs).isNull();

        service.page(null, null, AdminOpLog.RESULT_DENIED, null, null, new PageQuery());
        assertThat(lastPageArgs[2]).isEqualTo(AdminOpLog.RESULT_DENIED);
    }

    @Test
    @DisplayName("page 允许 action 为 null 或空串（空串在 Mapper 的 <if> 里等价于不加筛选）")
    void pageAllowsBlankActionFilter() {
        PageResult<AdminOpLog> r1 = service.page(null, null, null, from, null, new PageQuery());
        PageResult<AdminOpLog> r2 = service.page(null, "", null, from, null, new PageQuery());
        assertThat(r1.getTotal()).isZero();
        assertThat(r2.getTotal()).isZero();
        assertThat(lastPageArgs).isNotNull();
    }

    @Test
    @DisplayName("page 把归一化后的 offset/size 交给 SQL：size 上限 50，page 最小 1")
    void pageNormalizesBeforeSql() {
        PageQuery over = new PageQuery();
        over.setPage(0);
        over.setSize(9999);
        service.page(77L, AdminOpLog.ACTION_REVEAL_ANONYMOUS, null, from, from.plusDays(1), over);
        assertThat(lastPageArgs[5]).isEqualTo(0L);
        assertThat(lastPageArgs[6]).isEqualTo(PageQuery.MAX_SIZE);

        PageQuery third = new PageQuery();
        third.setPage(3);
        third.setSize(10);
        service.page(77L, null, null, null, null, third);
        assertThat(lastPageArgs[5]).isEqualTo(20L);
        assertThat(lastPageArgs[6]).isEqualTo(10);
    }

    @Test
    @DisplayName("page 的 total 来自 countFiltered，与 list 同一套 WHERE")
    void pageCarriesTotal() {
        service.success(CTX, AdminOpLog.ACTION_MUTE_USER, "user:3", 3L, "禁言");
        service.denied(CTX, AdminOpLog.ACTION_MUTE_USER, "user:4", 4L, "越权");
        PageResult<AdminOpLog> r = service.page(null, null, null, null, null, new PageQuery());
        assertThat(r.getList()).hasSize(2);
        assertThat(r.getTotal()).isEqualTo(2L);
        assertThat(r.getPage()).isEqualTo(1);
        assertThat(r.getSize()).isEqualTo(PageQuery.DEFAULT_SIZE);
        assertThat(r.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("动作分布与解匿总数直接透传：A9 顶部的分布图和 §17 的红线指标共用这一条")
    void statsPassThrough() {
        assertThat(service.actionStats(from)).hasSize(1);
        assertThat(service.revealCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("词库重载借 UPDATE_CONFIG 留痕：target 前缀 wordlib: 是判据，不是装饰")
    void dictReloadReusesUpdateConfig() {
        service.success(CTX, AdminOpLog.ACTION_UPDATE_CONFIG, "wordlib:v0.11", null,
            "热更新 v0.4 -> v0.11|词条=140|落盘=false|路径=");
        AdminOpLog row = inserted.get(0);
        assertThat(row.getTarget()).startsWith("wordlib:");
        assertThat(row.getTargetId()).isNull();
    }

    private static String repeat(String unit, int times) {
        return String.join("", java.util.Collections.nCopies(times, unit));
    }
}
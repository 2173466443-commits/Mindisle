package com.mindisle.admin;

import com.mindisle.admin.dto.StatusCountRow;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.entity.AdminOpLog;
import com.mindisle.mapper.AdminOpLogMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理端特权操作审计（任务 T6.6 · 需求 BR10「特权操作 100% 留痕」· 手册 §9.1 解匿那条）。
 *
 * <p>本类是 {@code admin_op_log} 唯一的写入口。之所以收成一个类而不是各处 {@code mapper.insert}：
 * ① 列宽截断（{@code action 64 / target 128 / ip 45 / user_agent 255 / result 16}）只该有一份真相，
 * 审计写失败不该把业务事务拖回去，但<b>越界截断</b>必须是可预期的同一套规则；
 * ② {@code action} 白名单——写错动作码的审计行在 A9 的筛选里永远查不到，
 * 那是最坏的一种「审计看起来有、实际上丢了」；
 * ③ 角色是快照（见 {@link AdminOpLog} 类注释），只有本类知道去 {@link Ctx} 里取而不是事后 join。</p>
 *
 * <p><b>三档留痕的事务语义不一样，这一条是 Gate6 第 63 轮逼出来的</b>：
 * {@link #success} 刻意<b>不</b>开新事务——「操作成功了但没有审计」是 FR8.4 最坏的形态，
 * 所以审计插入失败必须带着业务写操作一起回滚，两行要么都在、要么都不在。
 * 而 {@link #denied} 与 {@link #fail} 必须走 {@code REQUIRES_NEW}：它们的调用点全是
 * 「先写留痕、紧接着抛 BizException」，外层事务回滚会把刚插进去的那行证据一起带走，
 * 于是「越权 100% 留痕」在库里一条都数不出来（方法注释里留了完整复盘）。</p>
 * 需求把 BR10 写成硬约束：一次没有留痕的解匿不是「少一条日志」，而是一次不可追责的越权。</p>
 */
@Service
public class AdminOpLogService {

  /** 列宽上限，逐字抄自 sql/08_config.sql 与 sql/07_audit.sql 的 DDL。 */
  static final int ACTION_MAX = 64;
  static final int TARGET_MAX = 128;
  static final int IP_MAX = 45;
  static final int UA_MAX = 255;
  static final int RESULT_MAX = 16;
  static final int ROLE_MAX = 32;

  /** A9 的 action 筛选白名单：只允许这些值被写入或被查询（新增动作必须同时改这里）。 */
  static final Set<String> ACTIONS = actions();

  /** A9 的 result 筛选白名单：三态与写入口 {@link #write} 用的是同一组常量。 */
  static final Set<String> RESULTS = Set.of(
      AdminOpLog.RESULT_SUCCESS, AdminOpLog.RESULT_FAIL, AdminOpLog.RESULT_DENIED);

  private static Set<String> actions() {
    Set<String> set = new LinkedHashSet<>();
    set.add(AdminOpLog.ACTION_REVEAL_ANONYMOUS);
    set.add(AdminOpLog.ACTION_AUDIT_PASS);
    set.add(AdminOpLog.ACTION_AUDIT_REJECT);
    set.add(AdminOpLog.ACTION_AUDIT_CLAIM);
    set.add(AdminOpLog.ACTION_TICKET_HANDLE);
    set.add(AdminOpLog.ACTION_APPEAL_HANDLE);
    set.add(AdminOpLog.ACTION_REPORT_HANDLE);
    set.add(AdminOpLog.ACTION_MUTE_USER);
    set.add(AdminOpLog.ACTION_UNMUTE_USER);
    set.add(AdminOpLog.ACTION_BAN_USER);
    set.add(AdminOpLog.ACTION_RESTORE_USER);
    set.add(AdminOpLog.ACTION_POST_TOP);
    set.add(AdminOpLog.ACTION_POST_FEATURE);
    set.add(AdminOpLog.ACTION_POST_TAKEDOWN);
    set.add(AdminOpLog.ACTION_POST_RESTORE);
    set.add(AdminOpLog.ACTION_UPDATE_CONFIG);
    set.add(AdminOpLog.ACTION_EXPORT_CSV);
    set.add(AdminOpLog.ACTION_READ_PM);
    return Set.copyOf(set);
  }

  private final AdminOpLogMapper mapper;

  public AdminOpLogService(AdminOpLogMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * 一次操作的身份上下文（谁、什么角色、从哪个 IP、用什么客户端）。
   *
   * <p>由控制器从 {@code AuthUser} 与 {@code HttpServletRequest} 拼好再传进来，服务层不碰 Servlet API——
   * 这条分界与阶段 2 起的全部服务层一致，为的是单测不需要造 MockHttpServletRequest。</p>
   */
  public record Ctx(Long operatorId, String role, String ip, String userAgent) {
  }

  /**
   * 写一条 SUCCESS 审计。
   *
   * @param action 取 {@link AdminOpLog#ACTION_AUDIT_PASS} 一类常量，未知动作码直接抛
   *               {@link com.mindisle.common.BizException}（宁可让操作失败，不许写一条查不到的审计）
   * @param target 被操作对象的描述，形如 {@code post:1024}；全局操作传 null
   */
  public Long success(Ctx ctx, String action, String target, Long targetId, String detail) {
    return write(ctx, action, target, targetId, detail, AdminOpLog.RESULT_SUCCESS);
  }

  /**
   * 被拒绝的特权操作同样必须留痕（需求 §12 规范 5；DENIED 才是越权探测的证据）。
   *
   * <p><b>为什么是 {@code REQUIRES_NEW} 而不是跟着外层事务走</b>：Gate6 第 63 轮跑出来的真红。
   * {@code UserManageService.revealAnonymous} 的鉴权分支是「先写 DENIED，再抛 BizException」，
   * 而它本身是 {@code @Transactional} 的方法、{@code BizException} 是 RuntimeException，
   * 于是回滚把刚插进去的 DENIED 行一起带走了——{@code admin_op_log} 里
   * {@code REVEAL_ANONYMOUS} 只有 SUCCESS，一条 DENIED 都没有。FR8.4 要的是
   * 「解匿的一切尝试 100% 留痕」，而恰恰「被拒绝的那几次」最需要被看见：
   * 它通常意味着有人在试探边界。拒绝分支没有业务变更要保护，
   * 那行审计本身就是这次调用的全部产出，所以必须独立提交。</p>
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Long denied(Ctx ctx, String action, String target, Long targetId, String detail) {
    return write(ctx, action, target, targetId, detail, AdminOpLog.RESULT_DENIED);
  }

  /**
   * 执行失败的留痕：异常分支调用，detail 里放一句人能看懂的原因。
   *
   * <p>与 {@link #denied} 同理走 {@code REQUIRES_NEW}：调用它的那一行紧接着就抛异常，
   * 外层事务必然回滚，留在同一个事务里等于没写。典型场景是
   * {@code UserManageService.restore} 的「影响 0 行」并发分支与
   * {@code AuditQueueService.claim} 的「已被别人认领」分支。</p>
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Long fail(Ctx ctx, String action, String target, Long targetId, String detail) {
    return write(ctx, action, target, targetId, detail, AdminOpLog.RESULT_FAIL);
  }

  private Long write(Ctx ctx, String action, String target, Long targetId, String detail, String result) {
    if (action == null || !ACTIONS.contains(action)) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "未知的审计动作码：" + action);
    }
    AdminOpLog row = new AdminOpLog();
    row.setOperatorId(ctx == null ? null : ctx.operatorId());
    row.setOperatorRole(cut(ctx == null ? null : ctx.role(), ROLE_MAX));
    row.setAction(cut(action, ACTION_MAX));
    row.setTarget(cut(target, TARGET_MAX));
    row.setTargetId(targetId);
    row.setIp(cut(ctx == null ? null : ctx.ip(), IP_MAX));
    row.setUserAgent(cut(ctx == null ? null : ctx.userAgent(), UA_MAX));
    row.setDetail(detail);
    row.setResult(cut(result, RESULT_MAX));
    row.setDeleted(0);
    mapper.insert(row);
    return row.getId();
  }

  /**
   * A9 审计日志分页（手册 §9.2 A9）。
   *
   * <p>{@code action} 非空时先过白名单再进 SQL：这条判据是为了消掉「空表歧义」——
   * 写错动作码时 SQL 会安静地返回空列表，界面上一句「今天没有解匿操作」听起来完全成立，
   * 而真相是筛错了一个根本不存在的值。宁可当场报参数错。</p>
   */
  public PageResult<AdminOpLog> page(Long operatorId, String action, String result,
                                     LocalDateTime from, LocalDateTime to, PageQuery query) {
    if (action != null && !action.isEmpty() && !ACTIONS.contains(action)) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "审计动作码不在白名单内，请从下拉里选：" + action);
    }
    if (result != null && !result.isEmpty() && !RESULTS.contains(result)) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "留痕结果不在白名单内（SUCCESS/FAIL/DENIED），请从下拉里选：" + result);
    }
    PageQuery q = query.normalize();
    List<AdminOpLog> list = mapper.pageFiltered(operatorId, action, result, from, to, q.offset(), q.getSize());
    long total = mapper.countFiltered(operatorId, action, result, from, to);
    return PageResult.of(list, total, q);
  }

  public List<StatusCountRow> actionStats(LocalDateTime from) {
    return mapper.countGroupByAction(from);
  }

  /** 全站解匿总次数（§17 FR8.4 的红线指标，答辩要能当场报数）。 */
  public long revealCount() {
    return mapper.countReveals();
  }

  /** 码点计数截断：中文审计文案按字符数而不是 UTF-16 单元算。 */
  static String cut(String value, int maxCodePoints) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    int count = value.codePointCount(0, value.length());
    if (count <= maxCodePoints) {
      return value;
    }
    return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
  }
}
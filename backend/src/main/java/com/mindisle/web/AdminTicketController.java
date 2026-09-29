package com.mindisle.web;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.admin.TicketService;
import com.mindisle.admin.TicketService.Board;
import com.mindisle.admin.TicketService.Outcome;
import com.mindisle.admin.TicketService.TicketView;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.common.Result;
import com.mindisle.entity.AlertTicket;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * A5 危机干预工单（任务 T6.4 · 需求 FR8.5、FR9）。
 *
 * <p>六态状态机（PENDING → CLAIMED → DOING → CLOSED / FOLLOWUP → ARCHIVED）的每一步都单独成接口，
 * 是因为「认领」「开始处置」「办结」在真实干预流程里是三件事、可能三个不同的人、三个时间点：
 * 值班同学认领、心理老师开始处置、结束后由管理员办结或挂随访。
 * 合并成一条大接口就没法留下「谁在哪一步、几点几分」的痕迹，而 FR8.5 要检验的正是这条时间线。</p>
 *
 * <p>{@code close} 的目标状态由服务层白名单校验，控制器不重复判：
 * 一处规则两处写，漂移时最先崩掉的是审计数据的可信度。</p>
 */
@RestController
@RequestMapping("/api/admin/tickets")
@Tag(name = "5 管理端-危机工单", description = "工单看板、六态流转、超时预警、用户危机时间线")
public class AdminTicketController {

  private final TicketService ticketService;

  public AdminTicketController(TicketService ticketService) {
    this.ticketService = ticketService;
  }

  /** 认领 / 开始处置的入参。 */
  public record TicketRef(Long ticketId) {
  }

  /** 办结入参：toStatus 目标终态、note 处置记录、followupAt 随访提醒时间（可空）。 */
  public record CloseReq(Long ticketId, String toStatus, String note, LocalDateTime followupAt) {
  }

  @GetMapping
  @Operation(summary = "工单分页（可按状态/等级/认领人/用户过滤，overdueOnly 只看超 SLA；L2/L3 置顶见 FR8.5）")
  public Result<PageResult<TicketView>> list(
      @RequestParam(name = "status", required = false) String status,
      @Parameter(description = "L1 / L2 / L3") @RequestParam(name = "level", required = false) String level,
      @RequestParam(name = "assigneeId", required = false) Long assigneeId,
      @RequestParam(name = "userId", required = false) Long userId,
      @RequestParam(name = "overdueOnly", required = false) Boolean overdueOnly,
      PageQuery page, @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    AdminSupport.ctxOf(current, http);
    return Result.ok(ticketService.list(status, level, assigneeId, userId,
        Boolean.TRUE.equals(overdueOnly), page, AdminSupport.now()));
  }

  @GetMapping("/board")
  @Operation(summary = "工单看板（六态计数 + 超时数 + 最长等待分钟 + 今日新增/办结）")
  public Result<Board> board(@AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(ticketService.board(AdminSupport.now()));
  }

  @GetMapping("/overdue")
  @Operation(summary = "超时未处置工单（SLA 由等级决定，L3 最短）")
  public Result<List<AlertTicket>> overdue(@RequestParam(name = "limit", required = false) Integer limit,
      @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(ticketService.overdue(AdminSupport.now(), limit == null ? 0 : limit));
  }

  @GetMapping("/{id:\\d+}")
  @Operation(summary = "单条工单详情")
  public Result<AlertTicket> detail(@PathVariable("id") long id, @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(ticketService.require(id));
  }

  @GetMapping("/timeline/{userId:\\d+}")
  @Operation(summary = "某用户的危机触发时间线（干预回访时看「他历史上什么时候说过什么」）")
  public Result<List<AlertTicket>> timeline(@PathVariable("userId") long userId,
      @RequestParam(name = "limit", required = false) Integer limit, @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(ticketService.timeline(userId, limit == null ? 0 : limit));
  }

  @PostMapping("/claim")
  @Operation(summary = "认领工单（并发抢单：只有 PENDING 能认领，抢到 0 行即报错）")
  public Result<AlertTicket> claim(@RequestBody TicketRef ref,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (ref == null || ref.ticketId() == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "ticketId 不能为空");
    }
    return Result.ok(ticketService.claim(ref.ticketId(), AdminSupport.ctxOf(current, http), AdminSupport.now()));
  }

  @PostMapping("/start")
  @Operation(summary = "开始处置（CLAIMED -> DOING，记录处置开始时间）")
  public Result<AlertTicket> start(@RequestBody TicketRef ref,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (ref == null || ref.ticketId() == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "ticketId 不能为空");
    }
    return Result.ok(ticketService.startDoing(ref.ticketId(), AdminSupport.ctxOf(current, http), AdminSupport.now()));
  }

  @PostMapping("/close")
  @Operation(summary = "办结 / 转随访（写工单终态 + admin_op_log，并把触发词命中次数回灌词库）")
  public Result<Outcome> close(@RequestBody CloseReq req,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (req == null || req.ticketId() == null || req.toStatus() == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "ticketId 与 toStatus 必填");
    }
    return Result.ok(ticketService.close(req.ticketId(), AdminSupport.ctxOf(current, http),
        req.toStatus(), req.note(), req.followupAt(), AdminSupport.now()));
  }

  @GetMapping("/sla")
  @Operation(summary = "查询某等级的 SLA 截止时间（前端把「还剩几分钟」显示在列表上）")
  public Result<LocalDateTime> sla(@RequestParam(name = "level") String level,
      @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(ticketService.slaFor(level, AdminSupport.now()));
  }
}
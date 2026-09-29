package com.mindisle.web;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.admin.AuditQueueService;
import com.mindisle.admin.AuditQueueService.Adjudication;
import com.mindisle.admin.AuditQueueService.SyncStat;
import com.mindisle.admin.AuditQueueService.TaskView;
import com.mindisle.admin.dto.AssigneeStatRow;
import com.mindisle.admin.dto.StatusCountRow;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.common.Result;
import com.mindisle.entity.AuditTask;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * A4 人工审核台（任务 T6.2 / T6.4 · 需求 FR7.3、FR7.4、FR7.5）。
 *
 * <p>接口形状是照审核员的动作设计的，不是照数据库表设计的：审核员做的是
 * 「刷新队列 → 认领一条 → 看原文和机审命中 → 按 Y/N 裁决」，
 * 所以 claim 与 adjudicate 是两个独立请求，而不是一条「处理任务」的大接口。
 * 分开之后「认领超时」才有意义——一个认领了却迟迟不裁决的任务，
 * 能被 release-timeout 放回队列，别的审核员才能接手。</p>
 *
 * <p>两个 sync 接口是「手动补机审」的应急通道，正常情况由 {@code AuditQueueSyncJob} 定时跑。
 * 之所以留手动口：FR7.5 要求「没有第三方 API key 时抽审队列仍有内容」，
 * 演示时不能干等一个 cron 周期。</p>
 */
@RestController
@RequestMapping("/api/admin/audit")
@Tag(name = "5 管理端-审核", description = "审核队列、认领、裁决、机审同步、超时释放")
public class AdminAuditController {

  private final AuditQueueService queueService;

  public AdminAuditController(AuditQueueService queueService) {
    this.queueService = queueService;
  }

  /** 认领入参。 */
  public record TaskRef(Long taskId) {
  }

  /** 裁决入参：pass=true 通过、false 驳回，reason 驳回时必填（服务层校验并截断到 255）。 */
  public record AdjudicateReq(Long taskId, Boolean pass, String reason) {
  }

  @GetMapping("/tasks")
  @Operation(summary = "审核队列分页（可按状态/风险档/目标类型/认领人过滤，overdueOnly 只看超时未处理）")
  public Result<PageResult<TaskView>> list(
      @Parameter(description = "PENDING / CLAIMED / PASSED / REJECTED，不传为全部")
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "riskLevel", required = false) String riskLevel,
      @RequestParam(name = "targetType", required = false) String targetType,
      @RequestParam(name = "assigneeId", required = false) Long assigneeId,
      @Parameter(description = "true 只看认领超时的任务")
      @RequestParam(name = "overdueOnly", required = false) Boolean overdueOnly,
      PageQuery page, @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    AdminSupport.ctxOf(current, http);
    return Result.ok(queueService.list(status, riskLevel, targetType, assigneeId,
        Boolean.TRUE.equals(overdueOnly), page, AdminSupport.now()));
  }

  @GetMapping("/status-counts")
  @Operation(summary = "队列各状态计数（审核台顶部四个数字，FR7.4 清空耗时靠它）")
  public Result<List<StatusCountRow>> statusCounts(@AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(queueService.statusCounts());
  }

  @GetMapping("/assignee-stats")
  @Operation(summary = "人均处理量（按审核员聚合，答辩讲「工作量分配」时用的就是这条）")
  public Result<List<AssigneeStatRow>> assigneeStats(@AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(queueService.assigneeStats());
  }

  @GetMapping("/author/{userId}")
  @Operation(summary = "该作者近期送审记录（裁决时右侧面板：惯犯与新手的处置口径不该一样）")
  public Result<List<AuditTask>> authorHistory(@PathVariable("userId") long userId,
      @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(queueService.authorHistory(userId));
  }

  @PostMapping("/tasks/claim")
  @Operation(summary = "认领任务（并发安全：SQL 上带 status='PENDING' 条件，抢不到就报错）")
  public Result<AuditTask> claim(@RequestBody TaskRef ref,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (ref == null || ref.taskId() == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "taskId 不能为空");
    }
    return Result.ok(queueService.claim(ref.taskId(), AdminSupport.ctxOf(current, http), AdminSupport.now()));
  }

  @PostMapping("/tasks/adjudicate")
  @Operation(summary = "人工裁决（一次写满四件事：任务终态 + audit_record + 帖子状态与流转日志 + 通知作者）")
  public Result<Adjudication> adjudicate(@RequestBody AdjudicateReq req,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (req == null || req.taskId() == null || req.pass() == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "taskId 与 pass 必填");
    }
    return Result.ok(queueService.adjudicate(req.taskId(), AdminSupport.ctxOf(current, http),
        req.pass(), req.reason(), AdminSupport.now()));
  }

  @PostMapping("/sync-posts")
  @Operation(summary = "手动同步文本帖进审核队列（机审命中灰/黑的帖子才会入队）")
  public Result<SyncStat> syncPosts(@AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(queueService.syncPostsToTasks(AdminSupport.now()));
  }

  @PostMapping("/sync-images")
  @Operation(summary = "手动抽审带图帖子（FR7.5：无第三方图像 key 时也要让队列有内容）")
  public Result<SyncStat> syncImages(@RequestParam(name = "limit", required = false) Integer limit,
      @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(queueService.syncImagePostsToTasks(AdminSupport.now(), limit == null ? 0 : limit));
  }

  @PostMapping("/release-timeout")
  @Operation(summary = "释放认领超时的任务回队列（审核员中途下班的兜底，返回释放条数）")
  public Result<Integer> releaseTimeout(@AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(queueService.releaseTimedOut(AdminSupport.now()));
  }
}
package com.mindisle.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.admin.ContentManageService;
import com.mindisle.admin.ContentManageService.ActionOutcome;
import com.mindisle.admin.ContentManageService.Chain;
import com.mindisle.admin.ContentManageService.FlagOutcome;
import com.mindisle.admin.ContentManageService.ReportOutcome;
import com.mindisle.admin.ContentManageService.ReportView;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.common.Result;
import com.mindisle.entity.Post;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * A7 内容管理（任务 T6.6 · 需求 FR7.7 处置链、FR4 举报处置）。
 *
 * <p>{@code GET /chain/{postId}} 是 FR7.7 的落地：一条请求把 post、post_status_log、
 * audit_record、content_report、post_appeal 五张表按时间摊平。
 * 之所以做成「读一条链」而不是「五张表各一个接口让前端拼」：
 * 处置链的价值在于「任何人拿一条帖子 id 就能复现它被怎么处理的」，
 * 拼装的活儿交给前端就意味着每台机器上的拼法都不一样，答辩现场少一张表就成了「数据不全」。</p>
 *
 * <p>下架与恢复是两个接口而不是一个带布尔值的开关，因为它们的状态机方向不同、
 * 通知文案不同、可前置状态集合也不同（下架要求当前可见，恢复要求当前是 TAKEDOWN/REJECTED）。</p>
 */
@RestController
@RequestMapping("/api/admin/content")
@Tag(name = "5 管理端-内容", description = "帖子检索与下架/恢复、置顶加精、举报处置、完整处置链")
public class AdminContentController {

  private final ContentManageService contentService;

  public AdminContentController(ContentManageService contentService) {
    this.contentService = contentService;
  }

  /** 下架 / 恢复入参。 */
  public record PostActionReq(Long postId, String reason) {
  }

  /** 置顶 / 加精开关入参。 */
  public record FlagReq(Long postId, String flag, Boolean on) {
  }

  /** 举报办结入参：accepted=true 采纳（连带下架帖子），false 驳回。 */
  public record ReportHandleReq(Long reportId, Boolean accepted, String note) {
  }

  @GetMapping("/posts")
  @Operation(summary = "帖子分页检索（关键字 + 状态 + 作者；状态口径含 APPEALING 与 TAKEDOWN）")
  public Result<PageResult<Post>> pagePosts(
      @RequestParam(name = "keyword", required = false) String keyword,
      @Parameter(description = "DRAFT/PENDING/PUBLISHED/REJECTED/TAKEDOWN/APPEALING/DELETED")
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "userId", required = false) Long userId,
      PageQuery page, @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    AdminSupport.ctxOf(current, http);
    return Result.ok(contentService.pagePosts(keyword, status, userId, page));
  }

  @GetMapping("/posts/{id:\\d+}")
  @Operation(summary = "帖子原文（含已被下架的内容，管理端要能看到「被下架的到底是什么」）")
  public Result<Post> postDetail(@PathVariable("id") long id, @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(contentService.requirePost(id));
  }

  @PostMapping("/post/takedown")
  @Operation(summary = "下架（写 post_status_log + 通知作者「还有一次申诉机会」+ admin_op_log）")
  public Result<ActionOutcome> takedown(@RequestBody PostActionReq req,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (req == null || req.postId() == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "postId 必填");
    }
    return Result.ok(contentService.takedown(req.postId(), req.reason(),
        AdminSupport.ctxOf(current, http), AdminSupport.now()));
  }

  @PostMapping("/post/restore")
  @Operation(summary = "恢复上架（只有 TAKEDOWN / REJECTED 可恢复，返回 from->to 供界面提示）")
  public Result<ActionOutcome> restore(@RequestBody PostActionReq req,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (req == null || req.postId() == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "postId 必填");
    }
    return Result.ok(contentService.restore(req.postId(), req.reason(),
        AdminSupport.ctxOf(current, http), AdminSupport.now()));
  }

  @PostMapping("/post/flag")
  @Operation(summary = "置顶 / 加精（flag=top|feature，只写 admin_op_log，不进状态机流转表）")
  public Result<FlagOutcome> flag(@RequestBody FlagReq req,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (req == null || req.postId() == null || req.flag() == null || req.on() == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "postId、flag 与 on 必填");
    }
    return Result.ok(contentService.flag(req.postId(), req.flag(), req.on(), AdminSupport.ctxOf(current, http)));
  }

  @GetMapping("/reports")
  @Operation(summary = "举报分页（状态 + 目标类型过滤，带举报人昵称与被举报帖标题）")
  public Result<PageResult<ReportView>> pageReports(
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "targetType", required = false) String targetType,
      PageQuery page, @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    AdminSupport.ctxOf(current, http);
    return Result.ok(contentService.pageReports(status, targetType, page));
  }

  @GetMapping("/reports/pending-count")
  @Operation(summary = "待处理举报数（侧边栏红点，FR4.4「举报 24 小时内响应」的第一道提醒）")
  public Result<Long> pendingReportCount(@AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(contentService.pendingReportCount());
  }

  @PostMapping("/report/handle")
  @Operation(summary = "举报办结（采纳时连带下架该帖；帖子已不可见时只办结举报行，不让队列卡死）")
  public Result<ReportOutcome> handleReport(@RequestBody ReportHandleReq req,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (req == null || req.reportId() == null || req.accepted() == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "reportId 与 accepted 必填");
    }
    return Result.ok(contentService.handleReport(req.reportId(), req.accepted(), req.note(),
        AdminSupport.ctxOf(current, http), AdminSupport.now()));
  }

  @GetMapping("/chain/{postId:\\d+}")
  @Operation(summary = "FR7.7 处置链一次读全：帖子 + 状态流转 + 机审人审记录 + 举报 + 申诉")
  public Result<Chain> chain(@PathVariable("postId") long postId, @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(contentService.chain(postId));
  }
}
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

import com.mindisle.admin.AppealService;
import com.mindisle.admin.AppealService.Adjudication;
import com.mindisle.admin.AppealService.AppealView;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.common.Result;
import com.mindisle.entity.PostAppeal;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 申诉的管理端与作者端（任务 T6.7 · 需求 FR7.6「申诉可回溯、结果回写并通知」）。
 *
 * <p>两类身份两种路径，写在同一个类里靠 URL 前缀区分：
 * {@code /api/posts/{id}/appeal} 是作者提申诉（任意登录用户，服务层判归属），
 * {@code /api/admin/appeals/**} 是管理员裁申诉（SecurityConfig 已按角色拦截）。
 * 把作者入口塞进 {@code /api/admin} 前缀下会让普通用户 403，
 * 而把裁申诉留在非 admin 前缀下等于任何登录用户都能撤销下架——两条路都不能走。</p>
 */
@RestController
@RequestMapping("/api/admin/appeals")
@Tag(name = "5 管理端-申诉", description = "申诉列表与裁定（FR7.6：一次机会、结果回写、全程可回溯）")
public class AdminAppealController {

  private final AppealService appealService;

  public AdminAppealController(AppealService appealService) {
    this.appealService = appealService;
  }

  /** 裁定入参：accepted=true 采纳并恢复可见，false 维持原处置。 */
  public record AdjudicateReq(Long appealId, Boolean accepted, String note) {
  }

  @GetMapping
  @Operation(summary = "申诉分页（按状态过滤，带帖子标题与当前状态，管理员不用另开一页查帖子）")
  public Result<PageResult<AppealView>> page(
      @Parameter(description = "PENDING / ACCEPTED / REJECTED") @RequestParam(name = "status", required = false) String status,
      PageQuery page, @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    AdminSupport.ctxOf(current, http);
    return Result.ok(appealService.page(status, page));
  }

  @GetMapping("/pending-count")
  @Operation(summary = "待裁申诉数（A4 队列旁的第二个红点：申诉是有时限的承诺）")
  public Result<Long> pendingCount(@AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(appealService.pendingCount());
  }

  @GetMapping("/post/{postId:\\d+}")
  @Operation(summary = "某帖的申诉历史（一次性申诉的判据来源，也用于「这帖被裁过几次」）")
  public Result<List<PostAppeal>> historyOfPost(@PathVariable("postId") long postId,
      @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(appealService.historyOfPost(postId));
  }

  @GetMapping("/{id:\\d+}")
  @Operation(summary = "单条申诉详情")
  public Result<PostAppeal> detail(@PathVariable("id") long id, @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(appealService.require(id));
  }

  @PostMapping("/adjudicate")
  @Operation(summary = "裁定申诉（采纳->帖子恢复 PUBLISHED；驳回->回进入申诉前的状态，通知作者并留痕）")
  public Result<Adjudication> adjudicate(@RequestBody AdjudicateReq req,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (req == null || req.appealId() == null || req.accepted() == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "appealId 与 accepted 必填");
    }
    return Result.ok(appealService.adjudicate(req.appealId(), AdminSupport.ctxOf(current, http),
        req.accepted(), req.note(), AdminSupport.now()));
  }
}
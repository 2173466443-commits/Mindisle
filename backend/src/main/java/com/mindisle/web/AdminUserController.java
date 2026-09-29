package com.mindisle.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.admin.UserManageService;
import com.mindisle.admin.UserManageService.RevealResult;
import com.mindisle.admin.UserManageService.UserDetail;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.common.Result;
import com.mindisle.entity.User;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * A6 用户管理（任务 T6.5 · 需求 FR8.3 禁言、FR8.4 解匿留痕）。
 *
 * <p>三条特权各走各的路径（mute / ban / restore），而不是一个 {@code /status} 接口改状态：
 * 它们的留痕动作码不同、通知文案不同、恢复时的「从哪个状态回 ACTIVE」也不同，
 * 揉成一个接口就只能靠 if-else 分发，而 A6 界面上恰恰要一眼看出「这次点的是禁言还是封禁」。</p>
 *
 * <p>解匿只在这里暴露给 SUPER：{@code UserManageService.revealAnonymous} 内部会为非 SUPER
 * 写一条 DENIED 审计再抛 20002。<b>DENIED 比 SUCCESS 重要</b>——FR8.4 的「100% 留痕」不只是
 * 「解了几次」，还包括「谁试图解、被挡住了」，那是越权探测的唯一证据。</p>
 */
@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "5 管理端-用户", description = "用户检索、详情、禁言、封禁、恢复、匿名身份还原")
public class AdminUserController {

  private final UserManageService userManageService;

  public AdminUserController(UserManageService userManageService) {
    this.userManageService = userManageService;
  }

  /** 禁言入参：days 只接受 1/7/30（服务层集合校验）。 */
  public record MuteReq(Long userId, Integer days, String reason) {
  }

  /** 封禁 / 恢复共用入参。 */
  public record AccountReq(Long userId, String reason) {
  }

  /** 解匿入参：aliasId 来自匿名帖，reason 必填。 */
  public record RevealReq(Long aliasId, String reason) {
  }

  @GetMapping
  @Operation(summary = "用户分页检索（昵称/用户名关键字 + 账号状态）")
  public Result<PageResult<User>> page(
      @Parameter(description = "昵称或用户名模糊匹配") @RequestParam(name = "keyword", required = false) String keyword,
      @Parameter(description = "ACTIVE / MUTED / BANNED / DELETED") @RequestParam(name = "status", required = false) String status,
      PageQuery page, @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    AdminSupport.ctxOf(current, http);
    return Result.ok(userManageService.page(keyword, status, page));
  }

  @GetMapping("/{id:\\d+}")
  @Operation(summary = "用户档案（含发帖数、获赞数、危机工单、他名下的匿名别名）")
  public Result<UserDetail> detail(@PathVariable("id") long id, @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(userManageService.detail(id));
  }

  @PostMapping("/mute")
  @Operation(summary = "禁言（FR8.3：禁言期能看不能写，写操作 403 由 PostingQuotaService 判定）")
  public Result<User> mute(@RequestBody MuteReq req,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (req == null || req.userId() == null || req.days() == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "userId 与 days 必填");
    }
    return Result.ok(userManageService.mute(req.userId(), req.days(), req.reason(),
        AdminSupport.ctxOf(current, http), AdminSupport.now()));
  }

  @PostMapping("/ban")
  @Operation(summary = "封禁账号（登录闸门拒绝，历史内容保留但不再出现在广场）")
  public Result<User> ban(@RequestBody AccountReq req,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (req == null || req.userId() == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "userId 必填");
    }
    return Result.ok(userManageService.ban(req.userId(), req.reason(),
        AdminSupport.ctxOf(current, http), AdminSupport.now()));
  }

  @PostMapping("/restore")
  @Operation(summary = "恢复账号（MUTED 走解除禁言，BANNED 走解除封禁，两者通知文案不同）")
  public Result<User> restore(@RequestBody AccountReq req,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (req == null || req.userId() == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "userId 必填");
    }
    return Result.ok(userManageService.restore(req.userId(), req.reason(),
        AdminSupport.ctxOf(current, http), AdminSupport.now()));
  }

  @PostMapping("/reveal-anonymous")
  @Operation(summary = "匿名身份还原（仅限 SUPER，理由必填，先写审计再回写别名，FR8.4）")
  public Result<RevealResult> reveal(@RequestBody RevealReq req,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    if (req == null || req.aliasId() == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "aliasId 必填");
    }
    return Result.ok(userManageService.revealAnonymous(req.aliasId(), req.reason(),
        AdminSupport.ctxOf(current, http)));
  }

  @GetMapping("/reveal-count")
  @Operation(summary = "全站解匿次数（Gate6 判据：答辩要能当场报这个数，并且每一次都能在日志里查到）")
  public Result<Long> revealCount(@AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(userManageService.revealCount());
  }

  @GetMapping("/alias-of-post/{postId:\\d+}")
  @Operation(summary = "帖子 -> 匿名别名 id（A7 在匿名帖旁边放「解匿」按钮用，不做 alias->user 反查）")
  public Result<Long> aliasOfPost(@PathVariable("postId") long postId, @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(userManageService.aliasIdOfPost(postId));
  }
}
package com.mindisle.web;

import java.time.LocalDateTime;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.Result;
import com.mindisle.notify.NotifyService;
import com.mindisle.notify.dto.MarkReadRequest;
import com.mindisle.notify.dto.MarkReadView;
import com.mindisle.notify.dto.NotifyPage;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 通知中心的对外接口（任务 T3.11-b · 需求 FR9.1、FR9.2 · 手册 §6.1 行 3.11、§6.2 U13）。
 *
 * <p><b>为什么是新的一批路径而不是塞进 {@code /api/users/**}</b>：本资源的主语是「我的通知」，
 * 路径里根本不该出现别人的 id——而 {@code /api/users/{id}/...} 那一族的主语是被查看的人。
 * 挂在 /api/users/me/notifications 下确实能挤进既有分组，但那会让「02-user 用户与隐私」
 * 的 @Tag 描述变成一句不完全为真的话，而三处描述必须逐字一致的坑已经踩过一次
 * （见 {@link RelationshipController} 的注释）。所以这里开第 8 个分组 {@code 08-notify 站内通知}。</p>
 *
 * <p><b>两个接口都不接受 user_id</b>：收件人只来自令牌（{@code current.id()}），
 * 需求 BR4/A9 的口径与点赞关注一致。写接口在 SQL 层的 WHERE 里也带 user_id，
 * 于是「前端传了别人的通知 id」最多是影响 0 行，不会替别人清掉红点。</p>
 *
 * <p><b>未登录判法照 {@link RelationshipController}</b>：SecurityConfig 已经把 {@code /api/**}
 * 全量要求认证，{@code current == null} 在正常配置下不可达；这里仍然兜一手，
 * 是为了白名单被人改宽时通知列表不会变成全站可读——通知正文里带着别人的昵称和评论摘录。</p>
 */
@RestController
@RequestMapping("/api/notifications")
@Tag(name = "8 通知", description = "站内通知列表与已读标记（赞 / 评论 / 关注，WebSocket 推送口留到阶段 5）")
public class NotificationController {

  private final NotifyService notifyService;

  public NotificationController(NotifyService notifyService) {
    this.notifyService = notifyService;
  }

  /**
   * 我的通知列表（需求 FR9.1 的红点与 FR9.2 的列表）。
   *
   * <p>游标分页而不是页码分页，理由与帖子列表完全相同（{@code NotifyPage} 的类注释里写了一份）：
   * 通知是只增的流水，翻页途中插入新行会让页码错位。{@code unreadCount} 每页都带，
   * 是为了让红点只有一个数据来源。</p>
   */
  @GetMapping
  @Operation(summary = "我的通知列表（游标倒序，返回体同时带未读总数）")
  public Result<NotifyPage> list(@Parameter(description = "取比它更旧的一页，缺省为最新一页")
      @RequestParam(value = "beforeId", required = false) Long beforeId,
      @Parameter(description = "每页条数，默认 20，最大 50；超出上限按上限取，不报错")
      @RequestParam(value = "size", required = false) Integer size,
      @AuthenticationPrincipal AuthUser current) {
    requireLogin(current);
    return Result.ok(notifyService.list(current.id(), beforeId, size));
  }

  /**
   * 标记已读（需求 FR9.2「一键已读」）。
   *
   * <p>{@code all=true} 与 {@code ids} 二选一、都不给报 400/10001，判据写在
   * {@link NotifyService#markRead}：静默把「没传字段」当成「全部已读」是最坏的一种宽容。</p>
   */
  @PostMapping("/read")
  @Operation(summary = "标记通知已读（按 id 批量，或 all=true 一键全部已读）")
  public Result<MarkReadView> markRead(@RequestBody(required = false) MarkReadRequest request,
      @AuthenticationPrincipal AuthUser current) {
    requireLogin(current);
    return Result.ok(notifyService.markRead(current.id(), request, LocalDateTime.now()));
  }

  private static void requireLogin(AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
  }
}

package com.mindisle.web;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.Result;
import com.mindisle.pm.PmService;
import com.mindisle.pm.dto.PmAckView;
import com.mindisle.pm.dto.PmBlockItem;
import com.mindisle.pm.dto.PmBlockView;
import com.mindisle.pm.dto.PmConversationPage;
import com.mindisle.pm.dto.PmMessageView;
import com.mindisle.pm.dto.PmPresenceView;
import com.mindisle.pm.dto.PmReadRequest;
import com.mindisle.pm.dto.PmReportRequest;
import com.mindisle.pm.dto.PmReportView;
import com.mindisle.pm.dto.PmSendRequest;
import com.mindisle.pm.dto.PmThreadPage;
import com.mindisle.pm.dto.PmUnreadView;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 私信对外 REST（任务 T5.2–T5.7 · 需求 FR6 · 手册 §6.1 行 5.x、§8）。
 *
 * <p><b>这批端点和 STOMP 是同一套判据的两个入口</b>：{@code POST /api/pm/send} 与
 * {@code /app/private} 都落到 {@link PmService#send}，差别只有「返回体」和「ack」。
 * 这不是冗余，而是阶段 5 的核心可用性判据——WebSocket 挂掉、代理不支持升级、
 * 用户在内网被网关吃掉长连接时，前端必须还能把话发出去（手册 §8.2 第 5 条「降级可用」）。
 * 反过来，如果 REST 是一套判据、WS 是另一套，那么降级的那一刻业务规则也一起降级了，
 * 「拉黑之后还能发消息」这类事故就是这么产生的。</p>
 *
 * <p><b>所有接口都不接受 {@code from_user_id}</b>：主语只来自令牌（需求 A9），
 * 与点赞、关注、通知同一口径。{@code requireLogin} 兜一手 UNAUTHORIZED 的理由
 * 与 {@link NotificationController} 相同——白名单被人改宽时，私信列表不能变成全站可读。</p>
 *
 * <p><b>第 13 个 {@code @Tag} 分组</b>：分组数会进 OpenAPI 文档的读数，
 * 上一阶段已经因为「新开分组让分组数假性 +1」踩过一次，这里加组是<b>有意</b>的，
 * 描述与 {@code config/OpenApiConfig} 里 {@code 13-pm} 那条逐字一致，第三处是手册 §6.1。</p>
 */
@RestController
@RequestMapping("/api/pm")
@Tag(name = "13 私信", description = "站内私信的发送、历史、未读、在线、拉黑与举报（REST 与 STOMP 同一套判据）")
public class PmController {

  private final PmService pmService;

  public PmController(PmService pmService) {
    this.pmService = pmService;
  }

  /** 会话列表（U9）：每条会话最后一句 + 未读角标 + 在线点。 */
  @GetMapping("/conversations")
  @Operation(summary = "我的会话列表（游标倒序，返回体带未读总数）")
  public Result<PmConversationPage> conversations(
      @Parameter(description = "取比它更旧的一页，缺省为最新一页")
      @RequestParam(value = "beforeId", required = false) Long beforeId,
      @Parameter(description = "每页条数，默认 20，上限由 mindisle.pm.fetch-max 控制")
      @RequestParam(value = "size", required = false) Integer size,
      @AuthenticationPrincipal AuthUser current) {
    requireLogin(current);
    return Result.ok(pmService.conversations(current.id(), beforeId, size));
  }

  /**
   * 与某个人的历史（U10）。返回<b>时间正序</b>，前端直接往上拼。
   *
   * <p>断线补拉也走这一条：重连成功后按本地已知的最小 id 传 {@code beforeId}，
   * 服务端不需要知道「你拉到哪了」。</p>
   */
  @GetMapping("/thread/{peerId}")
  @Operation(summary = "与某人的私信历史（游标倒序取、时间正序返回）")
  public Result<PmThreadPage> thread(
      @Parameter(description = "对方用户 id") @PathVariable("peerId") Long peerId,
      @Parameter(description = "取比它更旧的一页") @RequestParam(value = "beforeId", required = false) Long beforeId,
      @Parameter(description = "每页条数") @RequestParam(value = "size", required = false) Integer size,
      @AuthenticationPrincipal AuthUser current) {
    requireLogin(current);
    if (peerId == null || peerId.longValue() <= 0L) {
      throw new BizException(ErrorCode.PARAM_INVALID, "会话对象 id 不合法");
    }
    return Result.ok(pmService.thread(current.id(), peerId.longValue(), beforeId, size));
  }

  /**
   * 发一条私信（REST 入口）。<b>与 STOMP 唯一的差别是这里同步返回落库那一行</b>，
   * 前端因此可以在 WS 不可用时把「已发送」直接画成服务端的时间与 id。
   */
  @PostMapping("/send")
  @Operation(summary = "发送私信（幂等键可选，重复提交返回同一条）")
  public Result<PmMessageView> send(@RequestBody(required = false) PmSendRequest request,
      @AuthenticationPrincipal AuthUser current) {
    requireLogin(current);
    return Result.ok(pmService.send(current.id(), request, LocalDateTime.now()));
  }

  /** 已读上报（FR6.4 回执）。REST 与 STOMP 共用 {@link PmService#markRead}，返回同一条 ack。 */
  @PostMapping("/read")
  @Operation(summary = "把与某人的会话标记为已读（可指定读到哪一条）")
  public Result<PmAckView> read(@RequestBody(required = false) PmReadRequest request,
      @AuthenticationPrincipal AuthUser current) {
    requireLogin(current);
    return Result.ok(pmService.markRead(current.id(), request, LocalDateTime.now()));
  }

  /**
   * 未读读数（FR9.1 角标）。<b>前端 WS 降级时轮询的就是这一条</b>，
   * 手册 §8.4 原文写的是 {@code /api/notifications/unread}，那个端点不存在，
   * 已按偏离记进 §8。
   */
  @GetMapping("/unread")
  @Operation(summary = "我的私信未读总数与分人未读")
  public Result<PmUnreadView> unread(@AuthenticationPrincipal AuthUser current) {
    requireLogin(current);
    return Result.ok(pmService.unreadView(current.id()));
  }

  /**
   * 在线状态（FR6.5）。<b>只回答「你问的这些人里谁在线」</b>：
   * 不做「全站在线名单」，那是把行踪做成公开接口（需求 BR11）。
   *
   * <p>{@code peerIds} 用重复参数而不是逗号串：{@code ?peerIds=1&peerIds=2}
   * 在 Spring 侧绑成 List 是标准写法，逗号串还要自己拆，而且拆法一旦和前端不一致就会静默漏人。</p>
   */
  @GetMapping("/online")
  @Operation(summary = "查询若干人当前是否在线（只返回在线的那些）")
  public Result<PmPresenceView> online(
      @Parameter(description = "要问的人，重复传；上限由前端按当前会话页内的人数给")
      @RequestParam(value = "peerIds", required = false) List<Long> peerIds,
      @AuthenticationPrincipal AuthUser current) {
    requireLogin(current);
    List<Long> ids = peerIds == null ? new ArrayList<>() : peerIds;
    return Result.ok(pmService.presenceOf(current.id(), ids));
  }

  /**
   * 举报一条私信（FR6.8）。<b>返回的 taskId 可能为 null</b>：
   * 并发下别人的举报已经把这条送进队列、而那张任务刚好被办结时，取不到待办 id。
   * 举报本身已经成立，所以不报错——让前端看到 500 才是真的误导。
   */
  @PostMapping("/report")
  @Operation(summary = "举报一条私信（进审核队列，不写 content_report）")
  public Result<PmReportView> report(@RequestBody(required = false) PmReportRequest request,
      @AuthenticationPrincipal AuthUser current) {
    requireLogin(current);
    return Result.ok(pmService.report(current.id(), request, LocalDateTime.now()));
  }

  /** 拉黑一个人（FR6.7）。幂等：已经在名单里返回 {@code changed=false} 而不是 409。 */
  @PostMapping("/block/{userId}")
  @Operation(summary = "拉黑某人（双向不可发私信，历史保留）")
  public Result<PmBlockView> block(
      @Parameter(description = "要拉黑的人") @PathVariable("userId") Long userId,
      @Parameter(description = "可选原因，最长由 mindisle.pm.block-reason-max 控制")
      @RequestParam(value = "reason", required = false) String reason,
      @AuthenticationPrincipal AuthUser current) {
    requireLogin(current);
    return Result.ok(pmService.block(current.id(), userId, reason));
  }

  /** 解除拉黑。物理删除，{@code changed=false} 表示本来就没拉黑。 */
  @DeleteMapping("/block/{userId}")
  @Operation(summary = "解除拉黑")
  public Result<PmBlockView> unblock(
      @Parameter(description = "要放开的人") @PathVariable("userId") Long userId,
      @AuthenticationPrincipal AuthUser current) {
    requireLogin(current);
    return Result.ok(pmService.unblock(current.id(), userId));
  }

  /** 我的黑名单（U9 设置项）。 */
  @GetMapping("/blocks")
  @Operation(summary = "我拉黑的人（对方已注销也会列出来）")
  public Result<List<PmBlockItem>> blocks(@AuthenticationPrincipal AuthUser current) {
    requireLogin(current);
    return Result.ok(pmService.blocks(current.id()));
  }

  private static void requireLogin(AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
  }
}

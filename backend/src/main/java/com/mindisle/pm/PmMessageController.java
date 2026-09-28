package com.mindisle.pm;

import java.security.Principal;
import java.time.Duration;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import com.mindisle.cache.CacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.pm.dto.PmAckView;
import com.mindisle.pm.dto.PmMessageView;
import com.mindisle.pm.dto.PmReadRequest;
import com.mindisle.pm.dto.PmSendRequest;

/**
 * STOMP 入站口（任务 T5.2 / T5.3 · 手册 §8.1 目的地表）。
 *
 * <p><b>身份只从 {@link Principal} 取</b>：那是 {@link WsAuthHandshakeInterceptor} 在握手时
 * 用 JWT 验过、再由 {@link StompPrincipalHandshakeHandler} 定成用户 id 的东西。
 * 载荷里一旦允许出现 {@code fromUserId}，就等于把「我是谁」交给客户端声明——
 * REST 侧同一个判据是 {@code @AuthenticationPrincipal}，两边必须同一条来源。</p>
 *
 * <p><b>回复统一走 {@code @SendToUser}，目的地都带 {@code /user} 前缀</b>：
 * Spring 会把它拼成 {@code /user/{sessionId}/queue/ack}，只回到当前这个连接。
 * 这里刻意不用 {@code convertAndSendToUser} 手工点名，因为那会把「回给谁」变成
 * 一个由本类计算的字符串——而 {@code @SendToUser} 的「回给发起这次调用的人」
 * 是框架保证的，写不出「把别人的 ack 发给你的 bug」。</p>
 *
 * <p><b>三个入口都吃 {@link BizException} 并回错误码而不是抛回连接</b>（手册 §8.3）：
 * STOMP 的异常默认会让 broker 断开这条连接，用户看到的是「聊着聊着就掉线」。
 * 一个业务拒绝（被拉黑、内容违规、限流）<b>不该让前端重连一次</b>，
 * 它要的是一个能画在气泡上的错误码，于是这里统一转成 {@code kind="error"} 的 ack。</p>
 */
@Controller
public class PmMessageController {

  private static final Logger log = LoggerFactory.getLogger(PmMessageController.class);

  /** 重投与心跳之外的键前缀；与 {@code web.RateLimitInterceptor} 的 {@code rl:} 同一命名空间但分桶，
   *  因为 WS 这侧按「连接用户」计，HTTP 那侧按「令牌身份 + 路径桶」计，混在一个桶里会互相偷额度。 */
  private static final String WS_RATE_PREFIX = "rl:ws:u";

  private final PmService pmService;
  private final CacheService cacheService;
  private final MindisleProperties properties;

  public PmMessageController(PmService pmService, CacheService cacheService,
      MindisleProperties properties) {
    this.pmService = pmService;
    this.cacheService = cacheService;
    this.properties = properties;
  }

  /**
   * 发一条私信（{@code /app/private}）。落库与推给对方都在 {@link PmService} 里，
   * 本方法只负责「回给发送方一个能对上号的 ack」。
   *
   * <p>{@code mine=true} 的视图原样带回：前端本地那条 pending 气泡要拿服务端那行
   * 去替换（真实 id、真实时间、真实 risk_level），否则刷新页面之后
   * 「已送达」的标记会找不到对应消息而永远停在转圈。</p>
   */
  @MessageMapping("/private")
  @SendToUser(PmPushGateway.DEST_ACK)
  public PmAckView send(@Payload PmSendRequest request, Principal principal) {
    long senderId = requireUid(principal);
    rateLimit(senderId);
    PmMessageView view = pmService.send(senderId, request, LocalDateTime.now());
    return new PmAckView("sent", request.clientMsgId(), view.id(), view.toUserId(),
        null, null, view.createdAt(), null, null);
  }

  /** 已读上报（{@code /app/read}）。回执里带剩余未读，前端不用再发一次 unread 请求。 */
  @MessageMapping("/read")
  @SendToUser(PmPushGateway.DEST_ACK)
  public PmAckView read(@Payload PmReadRequest request, Principal principal) {
    long userId = requireUid(principal);
    return pmService.markRead(userId, request, LocalDateTime.now());
  }

  /**
   * 心跳（{@code /app/ping}）。
   *
   * <p>Spring 的 STOMP 心跳本身已经在协议层跑（{@code WebSocketConfig} 里 30 秒），
   * 这个应用层 ping 解决的是另一件事：协议层活着不等于业务侧的在线表活着。
   * 前端拿它顺便验证「这条连接还能不能收到我自己的 ack」，
   * 收不到就说明这个会话在 broker 里已经不是 current user 了（多标签页轮换Principal 的现场）。</p>
   */
  @MessageMapping("/ping")
  @SendToUser(PmPushGateway.DEST_PONG)
  public PmAckView ping(Principal principal) {
    long userId = requireUid(principal);
    return new PmAckView("pong", null, null, null, null, null, LocalDateTime.now(), null, null);
  }

  /**
   * 业务异常 → 错误 ack（手册 §8.3 的「WS 侧也吃 ErrorCode」）。
   *
   * <p>返回 {@link PmAckView} 而不是一个错误包装：前端只订阅了 {@code /user/queue/ack}，
   * 让它在一个 handler 里按 {@code kind} 分流，比让它再订阅一条「错误队列」要短。
   * 代价是 {@code clientMsgId} 必须由参数里的原始请求带回来——这里拿不到入参，
   * 所以 {@code clientMsgId} 为空，前端按「最近一条 pending」归位。</p>
   */
  @MessageExceptionHandler(BizException.class)
  @SendToUser(PmPushGateway.DEST_ACK)
  public PmAckView handleBiz(BizException e) {
    ErrorCode code = e.getErrorCode();
    log.info("私信 WS 业务拒绝 code={} msg={}", code.getCode(), e.getMessage());
    return new PmAckView("error", null, null, null, null, null, LocalDateTime.now(),
        code.getCode(), e.getMessage() == null ? code.getMsg() : e.getMessage());
  }

  /** 兜住没预料到的异常：不报错也绝不断连接。 */
  @MessageExceptionHandler(Exception.class)
  @SendToUser(PmPushGateway.DEST_ACK)
  public PmAckView handleOther(Exception e) {
    log.warn("私信 WS 未预期异常 type={} msg={}", e.getClass().getSimpleName(), e.getMessage());
    ErrorCode code = ErrorCode.INTERNAL_ERROR;
    return new PmAckView("error", null, null, null, null, null, LocalDateTime.now(),
        code.getCode(), code.getMsg());
  }

  /**
   * Principal 就是用户 id 字符串（{@link StompPrincipalHandshakeHandler} 定死的口径）。
   * 解析不出来即「这条连接没有可信身份」，回 401 而不是 500。
   */
  private static long requireUid(Principal principal) {
    if (principal == null || principal.getName() == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED, "私信连接缺少身份，请重新登录");
    }
    try {
      return Long.parseLong(principal.getName().trim());
    } catch (NumberFormatException e) {
      throw new BizException(ErrorCode.UNAUTHORIZED, "私信连接的身份无法识别");
    }
  }

  /**
   * WS 侧限流（手册 §8.3）。HTTP 的 {@code RateLimitInterceptor} 挂在 servlet 上，
   * STOMP 帧不走它，所以这里补一道同款固定窗；键与 HTTP 分桶，理由见 {@link #WS_RATE_PREFIX}。
   */
  private void rateLimit(long userId) {
    long bucket = System.currentTimeMillis() / 60_000L;
    long hits = cacheService.incr(WS_RATE_PREFIX + userId + ":" + bucket, Duration.ofSeconds(60));
    if (hits > properties.getRateLimit().getUserPerMinute()) {
      throw new BizException(ErrorCode.RATE_LIMITED);
    }
  }
}

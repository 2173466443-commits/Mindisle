package com.mindisle.pm;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import com.mindisle.common.BizException;
import com.mindisle.security.AuthUser;
import com.mindisle.security.JwtService;

/**
 * STOMP 握手期鉴权（任务 T5.1 · 需求 FR6.1「JWT 握手鉴权」· 手册 §8.1）。
 *
 * <p><b>为什么不能在 {@code /app/private} 里校验令牌</b>：STOMP 的 CONNECT 帧在浏览器里
 * 拿不到 Authorization 头（原生 WebSocket API 不允许自定义请求头，SockJS 只在第一次
 * HTTP 请求带上）。所以身份只能挂在握手那次 HTTP 请求上。若把校验放到 CONNECT 之后，
 * 匿名连接就已经进了在线表，别人能据此判断「这个号存在且此刻在线」，
 * 那是隐私面的扩大，不是功能。</p>
 *
 * <p><b>令牌的三种来路（按优先级）</b>：① {@code ?token=}（前端 SockJS 用的就是这条，
 * 浏览器原生 WS 唯一可行的通道）；② {@code ?access_token=}（兼容 SockJS/OAuth2 习惯写法）；
 * ③ {@code Authorization: Bearer}（原生 WS 客户端与非浏览器探针用）。
 * 令牌出现在 URL 查询串里会被写进访问日志，这是这条通道的固有代价，
 * 已经写进手册 §8 的收工口径：**只接受短期 access token（2h），refresh token 一律拒绝**，
 * 且 {@code JwtService#validate} 内部就带着类型校验，走不到「拿刷新令牌当访问令牌」那条路。</p>
 *
 * <p><b>失败必须显式写 403</b>：{@link HandshakeInterceptor#beforeHandshake} 返回 false 时，
 * Spring 只按 javadoc 说「拒绝握手」，并不会替你设置状态码 —— 不写这一句，
 * 客户端看到的是 200 之后连接被立刻关闭，前端会把它当成「服务器故障」而无限重连。
 * 403 而不是 401：这里没有「重试也许能成」的语义，令牌就在请求里，它无效／过期就是无效。</p>
 */
public class WsAuthHandshakeInterceptor implements HandshakeInterceptor {

  private static final Logger log = LoggerFactory.getLogger(WsAuthHandshakeInterceptor.class);

  private static final String QUERY_TOKEN = "token";
  private static final String QUERY_ACCESS_TOKEN = "access_token";
  private static final String HEADER = "Authorization";
  private static final String BEARER = "Bearer ";

  private final JwtService jwtService;

  public WsAuthHandshakeInterceptor(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                 WebSocketHandler wsHandler, Map<String, Object> attributes) {
    String token = resolveToken(request);
    if (token == null || token.isBlank()) {
      // 不打令牌内容，也不打长度（长度能帮忙猜出口令强度），只打「哪条通道没拿到」
      log.warn("WS 握手缺少令牌 uri={} 无 token", request.getURI().getPath());
      response.setStatusCode(HttpStatus.FORBIDDEN);
      return false;
    }
    AuthUser user;
    try {
      user = jwtService.validate(token);
    } catch (BizException e) {
      // 过期与伪造合成一条日志：两者对客户端的处置一样，而区分它们会给探测者反馈
      log.warn("WS 握手令牌无效 uri={} code={}", request.getURI().getPath(), e.getErrorCode().getCode());
      response.setStatusCode(HttpStatus.FORBIDDEN);
      return false;
    }
    if (user == null || user.id() == null) {
      log.warn("WS 握手令牌缺少主体 uri={}", request.getURI().getPath());
      response.setStatusCode(HttpStatus.FORBIDDEN);
      return false;
    }
    attributes.put(StompPrincipalHandshakeHandler.ATTR_AUTH_USER, user);
    // 这条 INFO 是在线状态排障的起点：出现「接口 200 但收不到推送」时，
    // 先看这行有没有 —— 没有就是握手没通，有就是目的地寻址错了（Principal name 不等于 id）。
    log.info("WS 握手通过 uid={} path={}", user.id(), request.getURI().getPath());
    return true;
  }

  @Override
  public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                             WebSocketHandler wsHandler, Exception exception) {
    if (exception != null) {
      // 到这里身份已经验过，异常只可能来自升级过程本身（代理截断、超时）。
      // 记一条带 uid 的日志，方便把「某人总是连不上」和「全校都在断」分开。
      log.warn("WS 升级异常 uri={}", request.getURI().getPath(), exception);
    }
  }

  /** 三条来路按优先级取第一条命中的；全都没有返回 null（不是空串，便于日志区分「没带」与「带错」）。 */
  private static String resolveToken(ServerHttpRequest request) {
    String query = request.getURI().getRawQuery();
    String fromQuery = fromQuery(query);
    if (fromQuery != null && !fromQuery.isBlank()) {
      return fromQuery;
    }
    String header = request.getHeaders().getFirst(HEADER);
    if (header != null && header.startsWith(BEARER)) {
      return header.substring(BEARER.length()).trim();
    }
    return null;
  }

  /**
   * 手写查询串解析而不用 {@code UriComponentsBuilder}：这里只认两个固定键，
   * 引一个构建器反而把「未知参数被忽略」这件事变得不显眼。
   * 同名键出现多次时取第一次 —— 与浏览器 {@code URLSearchParams#get} 一致，
   * 不引入「两个 token 到底信哪个」这种没人能验证的行为。</p>
   */
  private static String fromQuery(String rawQuery) {
    if (rawQuery == null || rawQuery.isEmpty()) {
      return null;
    }
    for (String pair : rawQuery.split("&")) {
      int eq = pair.indexOf(61);
      if (eq <= 0) {
        continue;
      }
      String key = pair.substring(0, eq);
      if (QUERY_TOKEN.equals(key) || QUERY_ACCESS_TOKEN.equals(key)) {
        return java.net.URLDecoder.decode(pair.substring(eq + 1),
                java.nio.charset.StandardCharsets.UTF_8);
      }
    }
    return null;
  }

}

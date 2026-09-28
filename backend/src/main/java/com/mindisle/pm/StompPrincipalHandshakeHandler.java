package com.mindisle.pm;

import java.security.Principal;
import java.util.Map;

import org.springframework.web.socket.WebSocketHandler;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import com.mindisle.security.AuthUser;

/**
 * 把握手期解析出的登录身份变成 STOMP 的 {@code Principal}（任务 T5.1 · 需求 FR6.1）。
 *
 * <p><b>为什么 Principal 的 name 必须是用户 id 的字符串形式</b>：Spring 的用户级目的地
 * {@code /user/queue/xxx} 按 {@code Principal#getName()} 寻址，
 * {@code convertAndSendToUser(uid, "/queue/private", payload)} 的第一个参数必须和它逐字相等。
 * 用 username 也能跑，但有三个坑：① 用户名可改，改了之后所有在途推送的目标就失效了；
 * ② 用户名是对外可见的标识，不该流进消息路由层；③ 大小写与空格式的差异会让
 * 「同一个人两个名字」这类 bug 几乎无法定位。id 是不可变主键，最稳。</p>
 *
 * <p><b>不覆写 {@code determineUser} 会怎样</b>：{@link DefaultHandshakeHandler} 在拿不到
 * SecurityContext 时（{@code /ws/**} 是 permitAll，握手请求本身不带认证信息）会退化成用
 * {@code WebSocketSession.getId()} 当 Principal 名。于是每个连接都是「独立用户」，
 * 点对点推送永远发不到人，症状是「接口全 200、消息就是不出现」，看起来像前端 bug。</p>
 *
 * <p>因此本类与 {@link WsAuthHandshakeInterceptor} 是一对：拦截器负责把没有身份的握手
 * 挡在门外，本类负责把有身份的握手接上真实用户。缺任何一个都不成立 ——
 * 只留拦截器，Spring 仍然会用 session id 兜底；只留本类，未登录连接会挤进在线表。</p>
 */
public class StompPrincipalHandshakeHandler extends DefaultHandshakeHandler {

  /** session attributes 里存放握手身份的键，与 {@link WsAuthHandshakeInterceptor} 写入的是同一个。 */
  static final String ATTR_AUTH_USER = "mindisleAuthUser";

  @Override
  protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
                                    Map<String, Object> attributes) {
    Object raw = attributes == null ? null : attributes.get(ATTR_AUTH_USER);
    if (!(raw instanceof AuthUser authUser) || authUser.id() == null) {
      // 走到这里说明拦截器放行了却没有身份：宁可返回 null 让 Spring 拒绝本次握手，
      // 也不要伪造一个 Principal —— 那会让「未登录但已连接」的幽灵会话进在线表，
      // 进而把「某人在线」这件事推给不该看到它的其他人。
      return null;
    }
    return new UserIdPrincipal(authUser.id());
  }

  /**
   * 只用 id 的 Principal。
   *
   * <p>不复用 {@code UsernamePasswordAuthenticationToken}：在线表与目的地寻址只关心
   * 「这是哪个 id」，把权限集合带进 WS 层，将来只会有一个误导 ——
   * 让人以为 STOMP 目的地按角色隔离（它不隔离，隔离在 {@code PmService} 的判据里）。</p>
   */
  record UserIdPrincipal(long userId) implements Principal {

    @Override
    public String getName() {
      return String.valueOf(userId);
    }


    @Override
    public String toString() {
      return "pm-user:" + userId;
    }
  }

}

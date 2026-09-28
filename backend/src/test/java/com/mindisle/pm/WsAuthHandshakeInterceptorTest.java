package com.mindisle.pm;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.mindisle.cache.CaffeineCacheService;
import com.mindisle.config.MindisleProperties;
import com.mindisle.security.AuthUser;
import com.mindisle.security.JwtService;

/**
 * 握手鉴权单测（任务 T5.1 · 需求 FR6.1、BR9）。
 *
 * <p>这里钉的是<b>「谁被允许建立这条连接」</b>：{@code /ws/**} 在安全配置里是 permitAll，
 * 唯一的闸门就是本拦截器。四件事必须成立：① 三条来路（?token / ?access_token / Authorization）
 * 都能用，因为原生 WebSocket、SockJS 轮询、fetch 带凭证的能力各不相同；② 没带、空白、伪造、
 * 类型是 refresh、已被强制下线 —— 一律 403 且<b>不在 attributes 里留身份</b>；
 * ③ 放行时把 AuthUser 交给 session attributes；④ Principal 的名字就是用户 id 字符串，
 * 否则 {@code /user/queue/private} 永远投递不到人（症状是「接口全 200，消息就是不出现」）。</p>
 *
 * <p>用 {@link ServletServerHttpRequest} 包 {@link MockHttpServletRequest} 而不是手搓
 * {@code ServerHttpRequest}：spring-test 里没有 servlet 版的 MockServerHttpRequest，
 * 而拦截器只读 URI、query 和三个请求头，包一层最便宜。</p>
 */
class WsAuthHandshakeInterceptorTest {

    /** 与 JwtServiceTest 同一把测试密钥：只满足 HS256 的 32 字节下限，不与任何环境共用。 */
    private static final String TEST_SECRET = "mindisle-unit-test-secret-0123456789abcdef";

    private static final WebSocketHandler NOOP_HANDLER = new TextWebSocketHandler();

    private JwtService jwtService;
    private WsAuthHandshakeInterceptor interceptor;
    private String accessToken;
    private String refreshToken;
    private MockHttpServletResponse lastResponse;

    @BeforeEach
    void setUp() {
        MindisleProperties properties = new MindisleProperties();
        properties.getJwt().setSecret(TEST_SECRET);
        jwtService = new JwtService(properties, new CaffeineCacheService());
        interceptor = new WsAuthHandshakeInterceptor(jwtService);
        JwtService.TokenPair pair = jwtService.issue(701L, "xiaoyu", "USER");
        accessToken = pair.accessToken();
        refreshToken = pair.refreshToken();
    }

    // ---------------------------------------------------------------- 造请求

    private boolean handshake(String query, String bearer, Map<String, Object> attributes) {
        MockHttpServletRequest raw = new MockHttpServletRequest();
        raw.setRequestURI("/ws");
        if (query != null) {
            raw.setQueryString(query);
        }
        if (bearer != null) {
            raw.addHeader("Authorization", bearer);
        }
        lastResponse = new MockHttpServletResponse();
        return interceptor.beforeHandshake(new ServletServerHttpRequest(raw),
                new ServletServerHttpResponse(lastResponse), NOOP_HANDLER, attributes);
    }

    private AuthUser acceptedUser(Map<String, Object> attributes) {
        Object raw = attributes.get(StompPrincipalHandshakeHandler.ATTR_AUTH_USER);
        assertThat(raw).isInstanceOf(AuthUser.class);
        return (AuthUser) raw;
    }

    private Principal principalOf(Map<String, Object> attributes) {
        MockHttpServletRequest raw = new MockHttpServletRequest();
        raw.setRequestURI("/ws");
        return new StompPrincipalHandshakeHandler()
                .determineUser(new ServletServerHttpRequest(raw), NOOP_HANDLER, attributes);
    }

    // ---------------------------------------------------------------- 三条来路

    @Test
    @DisplayName("查询串里的 token 能换到身份，并把 AuthUser 交给 session attributes")
    void tokenQueryParamIsAccepted() {
        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake("token=" + accessToken, null, attributes)).isTrue();
        assertThat(acceptedUser(attributes).id()).isEqualTo(701L);
        assertThat(acceptedUser(attributes).username()).isEqualTo("xiaoyu");
    }

    @Test
    @DisplayName("SockJS 用的 access_token 也算数：只认 token 会让 XHR 轮询这条路整段失灵")
    void accessTokenQueryParamIsAccepted() {
        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake("access_token=" + accessToken, null, attributes)).isTrue();
        assertThat(acceptedUser(attributes).id()).isEqualTo(701L);
    }

    @Test
    @DisplayName("只有 Authorization 头也能过：原生 WebSocket 之外的另一种带凭证方式")
    void bearerHeaderIsAccepted() {
        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake(null, "Bearer " + accessToken, attributes)).isTrue();
        assertThat(acceptedUser(attributes).role()).isEqualTo("USER");
    }

    @Test
    @DisplayName("查询串优先于请求头：两条都带时以 query 为准，坏头不能把好凭证顶掉")
    void queryTokenWinsOverHeader() {
        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake("token=" + accessToken, "Bearer 坏掉的头", attributes)).isTrue();
        assertThat(acceptedUser(attributes).id()).isEqualTo(701L);
    }

    @Test
    @DisplayName("token 排在别的参数后面也找得到：真实前端拼的就是 ?eid=9&token=xxx")
    void tokenIsFoundAmongOtherQueryParams() {
        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake("eid=9&token=" + accessToken, null, attributes)).isTrue();
        assertThat(acceptedUser(attributes).id()).isEqualTo(701L);
    }

    // ---------------------------------------------------------------- 四道拒绝

    @Test
    @DisplayName("什么都没带：false + 403 + attributes 一个键都不留")
    void missingCredentialIsForbidden() {
        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake(null, null, attributes)).isFalse();
        assertThat(lastResponse.getStatus()).isEqualTo(403);
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("token= 空值、只有 Bearer 前缀、别的认证方案都算「没带」，不许退化成匿名连接")
    void blankTokenIsForbidden() {
        assertThat(handshake("token=", null, new HashMap<>())).isFalse();
        assertThat(handshake(null, "Bearer ", new HashMap<>())).isFalse();
        assertThat(handshake(null, "Basic Zm9vOmJhcg==", new HashMap<>())).isFalse();
        assertThat(lastResponse.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("另一套密钥签的与格式都不对的都归 403：不区分原因，免得给探测者反馈")
    void forgedTokenIsForbidden() {
        MindisleProperties other = new MindisleProperties();
        other.getJwt().setSecret("another-unit-test-secret-0123456789abcdefghij");
        String foreign = new JwtService(other, new CaffeineCacheService())
                .issue(701L, "xiaoyu", "USER").accessToken();
        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake("token=" + foreign, null, attributes)).isFalse();
        assertThat(handshake("token=abc.def.ghi", null, attributes)).isFalse();
        assertThat(lastResponse.getStatus()).isEqualTo(403);
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("拿 refresh 令牌建 WS 一律 403：长期凭证不能变成一条能被别人接着用的常驻连接")
    void refreshTokenCannotOpenSocket() {
        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake("token=" + refreshToken, null, attributes)).isFalse();
        assertThat(handshake(null, "Bearer " + refreshToken, attributes)).isFalse();
        assertThat(lastResponse.getStatus()).isEqualTo(403);
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("退出后白名单已删，旧 access 令牌连不上：FR1.2 的强制下线对 WS 同样有效")
    void revokedSessionCannotHandshake() {
        jwtService.revoke(701L);
        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake("token=" + accessToken, null, attributes)).isFalse();
        assertThat(attributes).isEmpty();
    }

    // ---------------------------------------------------------------- Principal

    @Test
    @DisplayName("Principal 的名字就是用户 id 字符串：/user/queue/private 按它寻址，用昵称会全员错投递")
    void principalNameIsTheUserIdString() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(StompPrincipalHandshakeHandler.ATTR_AUTH_USER,
                new AuthUser(701L, "xiaoyu", "USER", "jti"));
        Principal principal = principalOf(attributes);
        assertThat(principal).isNotNull();
        assertThat(principal.getName()).isEqualTo("701");
        assertThat(principal.toString()).isEqualTo("pm-user:701");
    }

    @Test
    @DisplayName("拦截器放行了却没身份时返回 null 让 Spring 拒掉：不伪造 Principal，免得幽灵会话进在线表")
    void principalNeedsAnAuthUser() {
        assertThat(principalOf(new HashMap<>())).isNull();
        assertThat(principalOf(null)).isNull();

        Map<String, Object> noId = new HashMap<>();
        noId.put(StompPrincipalHandshakeHandler.ATTR_AUTH_USER, new AuthUser(null, "xiaoyu", "USER", "jti"));
        assertThat(principalOf(noId)).isNull();

        Map<String, Object> wrongType = new HashMap<>();
        wrongType.put(StompPrincipalHandshakeHandler.ATTR_AUTH_USER, "xiaoyu");
        assertThat(principalOf(wrongType)).isNull();
    }

    @Test
    @DisplayName("升级阶段抛异常时 afterHandshake 只记日志不外抛：那时已经改变不了给客户端的结果")
    void afterHandshakeSwallowsUpgradeErrors() {
        MockHttpServletRequest raw = new MockHttpServletRequest();
        raw.setRequestURI("/ws");
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.afterHandshake(new ServletServerHttpRequest(raw),
                new ServletServerHttpResponse(response), NOOP_HANDLER,
                new IllegalStateException("代理把连接截断了"));
        assertThat(response.getStatus()).isEqualTo(200);
    }
}

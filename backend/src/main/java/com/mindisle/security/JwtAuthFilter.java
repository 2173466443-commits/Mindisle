package com.mindisle.security;

import java.io.IOException;
import java.util.List;

import com.mindisle.common.BizException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 认证过滤器（任务 T2.5 · 手册 §5.6）。
 *
 * <p><b>注册方式的坑</b>：本类由 SecurityConfig 用 @Bean 暴露给 addFilterBefore，
 * 同时必须用 FilterRegistrationBean 关掉容器级自动注册，
 * 否则 Boot 会把同一个 Filter 再挂进 servlet 链一次，导致请求被解析两遍、限流计数翻倍。
 * 因此本类<b>不加</b> @Component。</p>
 *
 * <p>校验失败不在这里写响应：把「未登录/凭证失效」统一交给 SecurityConfig 的
 * AuthenticationEntryPoint 输出，保证所有 401 的响应体形状一致。</p>
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    /**
     * 与 SecurityConfig 的 permitAll 保持同一份清单，避免「安全链放行却被本过滤器拦下」的错位。
     *
     * <p><b>这份清单错位过一次，方向和这句注释正好相反</b>：话题墙 {@code GET /api/topics} 上线时把
     * {@code "/api/topics"} 当成**前缀**写了进来，于是 T3.8 新增的四条**要求登录**的端点
     * （{@code /api/topics/{id}}、{@code /api/topics/{id}/posts}、{@code /api/topics/{id}/follow}、
     * {@code POST /api/topics}）全被跳过解析令牌——带着合法 JWT 也拿回 401/10002。
     * 这不是推断，是冒烟脚本第 21 步打出来的实测值，用户侧现象是「点进话题页就说我没登录」。</p>
     *
     * <p>所以前缀匹配在这里不是省事而是漏口：<b>白名单条目本身是精确路径的，就必须按精确路径比</b>。
     * 话题墙由此挪进 {@link #ANONYMOUS_EXACT}；其余条目本来就是带尾斜杠的目录前缀，保持原样。
     * 「墙可匿名、话题四条要登录」这条判据由 {@code JwtAuthFilterAnonymousPathTest} 逐条钉住，
     * 下次再往白名单里加条目时它会先响。</p>
     */
    private static final String[] ANONYMOUS_PREFIXES = {
            "/api/auth/", "/api/system/", "/api/admin/auth/",
            "/doc.html", "/v3/api-docs", "/swagger-ui", "/webjars/",
            "/actuator/", "/uploads/", "/ws", "/error"
    };

    /**
     * 只有读方法才匿名的精确路径：<b>话题墙与创建话题共用同一个 URI</b>——
     * {@code GET /api/topics} 是游客可逛的墙（{@code FeedController}），
     * {@code POST /api/topics} 是登录才能打的创建口（{@code TopicController}）。
     * 于是「这条 URI 匿名吗」这个问题本身就问错了，必须连方法一起问：
     * 只按 URI 比，要么游客进不了墙（把 GET 也要求登录），要么没登录也能建话题
     * （把 POST 也放行）——今天实测到的是前者的镜像：带着合法 JWT 打 POST，
     * 令牌在这一层就被跳过解析，Service 收到的 current 永远是 null，稳定 401/10002。
     * 只有 {@code /api/topics} 这一条有这种读写同 URI 的形状，其余匿名条目都是整棵目录子树。
     */
    private static final String[] ANONYMOUS_READ_EXACT = { "/api/topics" };

    /** 视为「读」的请求方法：HEAD 跟着 GET 走，其余（POST/PUT/PATCH/DELETE）一律要解析令牌。 */
    private static final List<String> READ_METHODS = List.of("GET", "HEAD");

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!isAnonymous(request.getMethod(), path)) {
            String token = resolveToken(request);
            if (StringUtils.hasText(token)) {
                authenticate(token);
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * OPTIONS 预检直接跳过：带 Authorization 的预检若不跳过，会被当成未认证请求，
     * 浏览器只报 CORS 错误而看不到真实原因，是前端联调最难查的一类现象。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    private void authenticate(String token) {
        try {
            AuthUser authUser = jwtService.validate(token);
            List<SimpleGrantedAuthority> authorities =
                    List.of(new SimpleGrantedAuthority("ROLE_" + authUser.role()));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(authUser, token, authorities));
        } catch (BizException e) {
            SecurityContextHolder.clearContext();
            log.debug("令牌校验未通过 code={}", e.getErrorCode().getCode());
        }
    }

    private static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length()).trim();
        }
        // EventSource(SSE) 无法自定义请求头，阶段 4 的流式对话用 ?access_token= 兜底；
        // 只取值、绝不打印，避免令牌进日志（NFR5）。
        String param = request.getParameter("access_token");
        return StringUtils.hasText(param) ? param : null;
    }

    /**
     * 包级可见而非 private：同包测试直接打它，不用反射（照 SensitiveWordEngine 的先例）。
     *
     * <p>方法参数是 {@code String} 而不是 {@code HttpMethod}：过滤器这里拿的是
     * {@code HttpServletRequest.getMethod()}（本来就是字符串），为了类型好看再转一次枚举，
     * 只会给一条 null 方法的路径多引出一个转换异常。</p>
     */
    static boolean isAnonymous(String method, String path) {
        if (path == null) {
            return false;
        }
        for (String exact : ANONYMOUS_READ_EXACT) {
            if (path.equals(exact) && method != null && READ_METHODS.contains(method.toUpperCase())) {
                return true;
            }
        }
        for (String prefix : ANONYMOUS_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

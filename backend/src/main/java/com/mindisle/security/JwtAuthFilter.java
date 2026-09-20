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

    /** 与 SecurityConfig 的 permitAll 保持同一份清单，避免「安全链放行却被本过滤器拦下」的错位。 */
    private static final String[] ANONYMOUS_PREFIXES = {
            "/api/auth/", "/api/system/", "/api/admin/auth/", "/api/topics",
            "/doc.html", "/v3/api-docs", "/swagger-ui", "/webjars/",
            "/actuator/", "/uploads/", "/ws", "/error"
    };

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!isAnonymous(path)) {
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

    private static boolean isAnonymous(String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : ANONYMOUS_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

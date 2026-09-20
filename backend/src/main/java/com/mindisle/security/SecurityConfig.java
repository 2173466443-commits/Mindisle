package com.mindisle.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.Result;
import com.mindisle.config.MindisleProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * 安全装配（手册 §5.6 的 7 写法，lambda DSL）。
 *
 * <p>相对手册原文的两处必要增补，均已记入 docs/dev-log.md：
 * 一是关掉 httpBasic/formLogin —— 不关的话 401 会被重定向到 Spring Security 自带的登录页，
 * 前端 axios 拿到一坨 HTML 而报「解析失败」；
 * 二是 permitAll 追加 /api/admin/auth/login 与 /api/system/**，
 * 前者是管理员登录入口本身必须匿名可达，后者给「后端连接正常」指示灯和免登录的求助页用。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** 匿名可访问的路径（§5.6 原文 + 上面说明的两处增补）。 */
    private static final String[] PUBLIC_MATCHERS = {
            "/api/auth/**",
            "/api/admin/auth/login",
            "/api/system/**",
            "/api/topics",
            "/doc.html",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/webjars/**",
            "/ws/**",
            "/uploads/**",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/error"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter,
                                                   ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_MATCHERS).permitAll()
                        .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "SUPER")
                        .anyRequest().authenticated())
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(objectMapper, response, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, deniedException) ->
                                writeError(objectMapper, response, ErrorCode.FORBIDDEN)))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public JwtAuthFilter jwtAuthFilter(JwtService jwtService) {
        return new JwtAuthFilter(jwtService);
    }

    /**
     * JwtAuthFilter 是 @Bean 只为了让 Security 链能拿到它；
     * 但 Boot 会把任何 Filter 类型的 Bean 自动注册进 servlet 容器，同一个请求就会被过滤两遍
     * （表现为限流计数翻倍、traceId 被重置）。这里显式关掉容器级注册。
     */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter jwtAuthFilter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(jwtAuthFilter);
        registration.setEnabled(false);
        return registration;
    }

    /** 口令散列：BCrypt（§5.6 最后一条），强度用默认 10 轮。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 占位的空账号仓库。
     *
     * <p>本项目是无状态 JWT，认证在 JwtAuthFilter 里完成，不该存在任何「服务端内置账号」。
     * 不提供这个 Bean 时，Spring Boot 会自动造一个内存用户并在启动日志里打印随机口令，
     * 既污染 Gate2 的「日志无异常输出」判据，也容易被误当成后门账号。</p>
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }

    /**
     * CORS 白名单（§5.6）：只允许 .env / application.yml 里显式列出的源。
     *
     * <p>刻意不用 allowedOriginPatterns 星号通配，也刻意不开 allowCredentials：
     * 令牌走 Authorization 头而非 Cookie，关掉 credentials 就等于关掉了 CSRF 的主要攻击面。
     * 生产同域部署时把 mindisle.cors.allowed-origins 留空即可彻底关闭跨域。</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(MindisleProperties properties) {
        final List<String> allowed = new ArrayList<>(properties.getCors().getAllowedOrigins());
        final CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.copyOf(allowed));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Trace-Id"));
        configuration.setExposedHeaders(List.of("X-Trace-Id", "X-RateLimit-Limit", "X-RateLimit-Remaining"));
        configuration.setMaxAge(3600L);
        return request -> {
            String origin = request.getHeader(HttpHeaders.ORIGIN);
            if (origin == null || origin.isBlank() || !allowed.contains(origin)) {
                return null;
            }
            return configuration;
        };
    }

    /**
     * 认证/授权失败的统一出口：响应体与业务接口完全同构（code/msg/data/traceId），
     * 前端只需一套拦截逻辑。注意这里不抛异常——已经在过滤器链上，再抛就没人接了
     * （GlobalExceptionHandler 只处理 MVC handler 里抛出的 AccessDenied）。
     */
    private static void writeError(ObjectMapper objectMapper, HttpServletResponse response, ErrorCode errorCode)
            throws IOException {
        response.setStatus(errorCode.getHttpStatus());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Result.fail(errorCode));
    }
}

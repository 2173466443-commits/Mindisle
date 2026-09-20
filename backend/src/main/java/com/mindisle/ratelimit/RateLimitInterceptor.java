package com.mindisle.ratelimit;

import java.time.Duration;

import com.mindisle.cache.CacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 接口限流（任务 T2.8 · NFR7：普通接口 60 次/分，AI 接口 6 次/分）。
 *
 * <p>用「固定窗口计数」而不是令牌桶：本项目要防的是脚本刷 AI 接口造成成本雪崩（FR6.7），
 * 固定窗口最简单且误差只有一个窗口的量级，符合 §12 反对过度设计的红线。
 * 窗口边界突发的理论放大效应写进论文第 7 章「局限性」，而不是用复杂度去掩盖。</p>
 *
 * <p>身份优先取登录态（AuthUser.id），未登录退化为客户端 IP；两者分开计数，
 * 否则同一个 NAT 出口下的整间宿舍会被互相拖累。</p>
 *
 * <p>本类由 {@link com.mindisle.config.WebMvcConfig} 手动 new，不是 Bean，
 * 因此依赖走构造注入。</p>
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    /** AI 前缀单独限额：一次对话动辄上千 token，6 次/分是成本与体验的折中。 */
    private static final String AI_PREFIX = "/api/ai/";

    private static final String KEY_PREFIX = "rl:";

    /** 窗口长度 60s，与「次/分钟」的语义直接对应。 */
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private static final long WINDOW_MILLIS = 60_000L;

    private final CacheService cacheService;
    private final MindisleProperties properties;

    public RateLimitInterceptor(CacheService cacheService, MindisleProperties properties) {
        this.cacheService = cacheService;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();
        MindisleProperties.RateLimit config = properties.getRateLimit();
        int limit = uri != null && uri.startsWith(AI_PREFIX) ? config.getAiPerMinute() : config.getUserPerMinute();
        String identity = resolveIdentity(request);
        long bucket = System.currentTimeMillis() / WINDOW_MILLIS;
        long count = cacheService.incr(KEY_PREFIX + identity + ":" + bucket, WINDOW);

        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0L, (long) limit - count)));

        if (count > limit) {
            log.warn("限流触发 identity={} uri={} count={} limit={}", identity, uri, count, limit);
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
        return true;
    }

    private String resolveIdentity(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthUser authUser && authUser.id() != null) {
            return "u" + authUser.id();
        }
        return "ip" + clientIp(request);
    }

    /** 取真实来源 IP：同域部署后走 Nginx，X-Forwarded-For 的第一跳才是客户端。 */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(44);
            return sanitize(comma > 0 ? forwarded.substring(0, comma) : forwarded);
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return sanitize(realIp);
        }
        return sanitize(request.getRemoteAddr());
    }

    /** IP 只作为缓存 key 的一部分：过滤分隔符，避免伪造出别人的计数桶。 */
    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && sb.length() < 64; i++) {
            char c = raw.charAt(i);
            boolean digit = c >= 48 && c <= 57;
            boolean letter = (c >= 65 && c <= 90) || (c >= 97 && c <= 122);
            if (digit || letter || c == 58 || c == 46 || c == 45) {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }
}

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

    /**
     * 需要按「AI 成本」限流的路径前缀。
     *
     * <p><b>为什么不是整条 {@code /api/ai/}</b>：NFR7 写「AI 接口 6 次/分」，紧挨着的半句是
     * 「普通接口 60 次/分」，它要防的是「脚本刷模型造成成本雪崩」（FR6.7），不是限制用户看自己的会话。
     * 若按前缀一刀切，一次 U7 页面加载就要吃掉配额：列表 1 次 + 每条历史消息各 1 次 +
     * 每发言 1 次 + 每次赞踩 1 次，用户在演示里发第四句话就会莫名其妙收到 429 —— 而这与花钱无关。
     * 所以严格限额只作用在真正调用大模型的 {@code /api/ai/chat/} 上，其余 AI 路径走 60 次/分。</p>
     *
     * <p>该口径偏离已作为「契约漂移」记入 docs/dev-log.md，答辩口径：按成本分级限流。</p>
     */
    private static final String[] COSTLY_AI_PREFIXES = { "/api/ai/chat/" };

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
        boolean costly = isCostlyAi(uri);
        int limit = costly ? config.getAiPerMinute() : config.getUserPerMinute();
        String identity = resolveIdentity(request);
        long bucket = System.currentTimeMillis() / WINDOW_MILLIS;
        // 🔴 计数器必须按成本分级，否则上面那句「只有 chat 走 6 次/分」是假的。
        // 原实现的 key 是 rl:<身份>:<窗口>，全站所有接口共用一个计数，只是「比较时用哪个上限」不同：
        // 于是打开 /ai 页面要拉的会话列表、每条历史的回看、每次赞踩，全都被记进同一条计数里，
        // 等用户真正发言时 count 早已 >6，第四五句话直接 429 —— 而这跟模型成本一点关系都没有。
        // 2026-09-24 真链路实测：一分钟内 count 一路涨到 13，limit=6，被拒的 5 次全是普通读写。
        // 把档位写进 key 之后，AI 严格配额只统计真正打模型的调用，普通调用回到它自己的 60 次/分桶里。
        String countKey = KEY_PREFIX + (costly ? "ai:" : "") + identity + ":" + bucket;
        long count = cacheService.incr(countKey, WINDOW);

        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0L, (long) limit - count)));

        if (count > limit) {
            log.warn("限流触发 identity={} uri={} count={} limit={}", identity, uri, count, limit);
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
        return true;
    }

    /** 只有会调用大模型的端点才吃严格配额。 */
    private static boolean isCostlyAi(String uri) {
        if (uri == null) {
            return false;
        }
        for (String prefix : COSTLY_AI_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
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

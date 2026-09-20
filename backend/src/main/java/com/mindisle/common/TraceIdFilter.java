package com.mindisle.common;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 链路追踪过滤器（手册 §5.5）：生成或透传 X-Trace-Id 并写入 MDC，finally 必清理。
 *
 * <p>Order 设为最高优先级，保证后续任何过滤器或拦截器抛出的异常都能带上同一个 traceId。
 * 入站 header 会做长度与字符合规化：直接信任客户端任意字符串会让日志被污染（NFR7）。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    private static final int MAX_LEN = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = normalize(request.getHeader(HEADER));
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(Result.TRACE_ID_KEY, traceId);
        try {
            response.setHeader(HEADER, traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(Result.TRACE_ID_KEY);
        }
    }

    /** 只接受可见 ASCII，其余一律重新生成。 */
    private String normalize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > MAX_LEN) {
            value = value.substring(0, MAX_LEN);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return null;
            }
        }
        return value;
    }
}

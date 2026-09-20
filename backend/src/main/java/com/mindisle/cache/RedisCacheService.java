package com.mindisle.cache;

import java.time.Duration;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 实现（任务 T2.7 · 生产/多实例形态）。
 *
 * <p>统一走 StringRedisTemplate + Jackson：值序列化成 JSON 而不是 Java 原生序列化，
 * 好处是 redis-cli 里能直接读到明文，答辩演示和排障时不必再配序列化器；
 * 代价是 get 时必须知道类型，因此接口设计成 get(key, Class)。</p>
 *
 * <p>Redis 连不上时不做运行期静默降级：直接抛 90003，让 GlobalExceptionHandler 返回明确错误码。
 * 「降级」由部署时改 mindisle.cache.mode 决定，而不是运行期偷偷换实现——
 * 否则多实例下会出现「一半实例在本地计数」这种最难查的限流失效。</p>
 */
@Component
@ConditionalOnProperty(name = "mindisle.cache.mode", havingValue = "redis")
public class RedisCacheService implements CacheService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisCacheService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        String json = redis.opsForValue().get(key);
        return json == null ? null : read(json, type);
    }

    @Override
    public void set(String key, Object value, Duration ttl) {
        String json = write(value);
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            redis.opsForValue().set(key, json);
        } else {
            redis.opsForValue().set(key, json, ttl);
        }
    }

    @Override
    public <T> T getAndDelete(String key, Class<T> type) {
        String json = redis.opsForValue().getAndDelete(key);
        return json == null ? null : read(json, type);
    }

    @Override
    public long incr(String key, Duration ttl) {
        Long count = redis.opsForValue().increment(key);
        long value = count == null ? 0L : count;
        // 只在首次创建时设窗口，保证固定窗口语义（与 CaffeineCacheService 一致）
        if (value == 1L) {
            redis.expire(key, ttl == null ? Duration.ofDays(1) : ttl);
        }
        return value;
    }

    @Override
    public boolean expire(String key, Duration ttl) {
        return Boolean.TRUE.equals(redis.expire(key, ttl));
    }

    @Override
    public void del(String key) {
        redis.delete(key);
    }

    @Override
    public long sadd(String key, Duration ttl, String... members) {
        Long added = redis.opsForSet().add(key, members);
        long count = added == null ? 0L : added;
        if (count > 0L) {
            redis.expire(key, ttl == null ? Duration.ofDays(1) : ttl);
        }
        return count;
    }

    @Override
    public long srem(String key, String... members) {
        Long removed = redis.opsForSet().remove(key, (Object[]) members);
        return removed == null ? 0L : removed;
    }

    @Override
    public Set<String> members(String key) {
        Set<String> set = redis.opsForSet().members(key);
        return set == null ? Set.of() : set;
    }

    @Override
    public void zadd(String key, String member, double score) {
        redis.opsForZSet().add(key, member, score);
    }

    @Override
    public String mode() {
        return "redis";
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.CACHE_UNAVAILABLE, "缓存值序列化失败：" + e.getMessage());
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new BizException(ErrorCode.CACHE_UNAVAILABLE, "缓存值反序列化失败：" + e.getMessage());
        }
    }
}

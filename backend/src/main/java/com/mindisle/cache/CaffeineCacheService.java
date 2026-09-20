package com.mindisle.cache;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地降级实现（任务 T2.7 · 需求 §11.2：Redis 不可用时单机跑通全部功能）。
 *
 * <p>逐条 ttl 靠 Caffeine 的自定义 {@link Expiry} 实现：Entry 存绝对到期时刻，
 * 三个回调都换算成「还剩多久」，这样 incr 反复自增不会把固定窗口无限续期
 * ——那正是限流最忌讳的「滑动窗口伪装成固定窗口」。</p>
 *
 * <p>提供 public 无参构造，测试里可以直接 new 一个当 fake，
 * 不必为了验证登录失败锁定而去拉一个 Redis。</p>
 */
@Component
@ConditionalOnProperty(name = "mindisle.cache.mode", havingValue = "local", matchIfMissing = true)
public class CaffeineCacheService implements CacheService {

    /** 单机兜底：容量上限按「验证码 + 限流键 + 少量接口缓存」估算，2 万条足够且不会 OOM。 */
    private static final long MAX_ENTRIES = 20_000L;

    private final Cache<String, Entry> cache = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfter(new Expiry<String, Entry>() {
                @Override
                public long expireAfterCreate(String key, Entry value, long currentTime) {
                    return value.remainingNanos(currentTime);
                }

                @Override
                public long expireAfterUpdate(String key, Entry value, long currentTime, long currentDuration) {
                    return value.remainingNanos(currentTime);
                }

                @Override
                public long expireAfterRead(String key, Entry value, long currentTime, long currentDuration) {
                    // 读不续期：否则热点 key 永不过期，与 Redis 语义不一致
                    return currentDuration;
                }
            })
            .build();

    @Override
    public <T> T get(String key, Class<T> type) {
        Entry entry = cache.getIfPresent(key);
        if (entry == null || entry.value == null) {
            return null;
        }
        return type.cast(entry.value);
    }

    @Override
    public void set(String key, Object value, Duration ttl) {
        cache.put(key, Entry.of(value, ttl));
    }

    @Override
    public <T> T getAndDelete(String key, Class<T> type) {
        Entry removed = cache.asMap().remove(key);
        if (removed == null) {
            return null;
        }
        return type.cast(removed.value);
    }

    @Override
    public long incr(String key, Duration ttl) {
        Entry updated = cache.asMap().compute(key, (k, old) -> {
            if (old == null || !(old.value instanceof Long)) {
                return Entry.of(1L, ttl);
            }
            return old.withValue(((Long) old.value) + 1L);
        });
        return (Long) updated.value;
    }

    @Override
    public boolean expire(String key, Duration ttl) {
        return cache.asMap().computeIfPresent(key, (k, old) -> old.withTtl(ttl)) != null;
    }

    @Override
    public void del(String key) {
        cache.invalidate(key);
    }

    @Override
    public long sadd(String key, Duration ttl, String... members) {
        long[] added = new long[1];
        cache.asMap().compute(key, (k, old) -> {
            Set<String> set = old != null && old.value instanceof Set ? newSet(stringSet(old.value)) : newSet(null);
            for (String member : members) {
                if (set.add(member)) {
                    added[0]++;
                }
            }
            return old == null ? Entry.of(set, ttl) : old.withValue(set);
        });
        return added[0];
    }

    @Override
    public long srem(String key, String... members) {
        long[] removed = new long[1];
        cache.asMap().computeIfPresent(key, (k, old) -> {
            if (!(old.value instanceof Set)) {
                return null;
            }
            Set<String> set = stringSet(old.value);
            for (String member : members) {
                if (set.remove(member)) {
                    removed[0]++;
                }
            }
            return old.withValue(set);
        });
        return removed[0];
    }

    @Override
    public Set<String> members(String key) {
        Entry entry = cache.getIfPresent(key);
        if (entry == null || !(entry.value instanceof Set)) {
            return Set.of();
        }
        return Set.copyOf(stringSet(entry.value));
    }

    @Override
    public void zadd(String key, String member, double score) {
        // 本地模式刻意不支持：有序集合是跨 key 的聚合结构，用 Caffeine 硬写等于伪造一个 Redis。
        // 多实例部署（排行榜、在线列表）必须把 mindisle.cache.mode 切回 redis，见需求 §11.2。
        throw new BizException(ErrorCode.CACHE_OP_UNSUPPORTED_LOCAL,
                "本地缓存降级模式不支持有序集合（zadd），排行榜类功能请设置 MINDISLE_CACHE_MODE=redis");
    }

    @Override
    public String mode() {
        return "local";
    }

    @SuppressWarnings("unchecked")
    private static Set<String> stringSet(Object value) {
        return (Set<String>) value;
    }

    private static Set<String> newSet(Set<String> seed) {
        Set<String> set = ConcurrentHashMap.newKeySet();
        if (seed != null) {
            set.addAll(seed);
        }
        return set;
    }

    /** 值 + 绝对到期时刻（nanoTime 基准，与 Caffeine 的时间轴一致）。 */
    private static final class Entry {

        private final Object value;
        private final long expireAtNanos;

        private Entry(Object value, long expireAtNanos) {
            this.value = value;
            this.expireAtNanos = expireAtNanos;
        }

        private static Entry of(Object value, Duration ttl) {
            return new Entry(value, System.nanoTime() + nanos(ttl));
        }

        private static long nanos(Duration ttl) {
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                // 没给 ttl 时给一天，而不是「永不过期」：缓存里堆垃圾比提前失效更难排查
                return Duration.ofDays(1).toNanos();
            }
            return Math.max(1L, ttl.toNanos());
        }

        private Entry withValue(Object newValue) {
            return new Entry(newValue, expireAtNanos);
        }

        private Entry withTtl(Duration ttl) {
            return new Entry(value, System.nanoTime() + nanos(ttl));
        }

        private long remainingNanos(long currentTime) {
            long remaining = expireAtNanos - currentTime;
            return remaining > 0L ? remaining : 1L;
        }
    }
}

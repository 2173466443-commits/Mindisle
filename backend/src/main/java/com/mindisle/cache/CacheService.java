package com.mindisle.cache;

import java.time.Duration;
import java.util.Set;

/**
 * 缓存抽象（手册 §5.5 第 6 行 · 任务 T2.7）。
 *
 * <p>全站不直接用 RedisTemplate，也不直接用 Caffeine，一律依赖本接口：
 * 需求 §11.2 的降级口要求「Redis 不可用时单机仍能跑通全部功能」，
 * 因此在部署形态是单机 dev（本机 Redis 为便携版、随时可能没起）时，
 * 把 mindisle.cache.mode 从 redis 改成 local 即可，业务代码零改动。</p>
 *
 * <p>接口只保留本项目真正需要的方法，刻意不做「通用缓存框架」——
 * 需求 §12 的过度设计红线：凡是没有明确调用方的能力一律不写。</p>
 */
public interface CacheService {

    /** 读；不存在或已过期返回 null。type 用于把「拿错 key」这类 bug 在读取点就暴露出来。 */
    <T> T get(String key, Class<T> type);

    /** 写并设置存活时间；ttl 为空表示按默认最长存活处理。 */
    void set(String key, Object value, Duration ttl);

    /**
     * 原子地「取出并删除」，对应 §5.11 T2.16 的一次性验证码：
     * 校验通过后必须立刻销毁，否则同一个 captchaId 可被重放。
     */
    <T> T getAndDelete(String key, Class<T> type);

    /**
     * 计数并返回自增后的值，key 不存在时从 1 开始并按 ttl 过期。
     * 这是限流（NFR7）与登录失败锁定（T2.16）的唯一原语。
     */
    long incr(String key, Duration ttl);

    /** 重置存活时间，返回 key 是否存在。 */
    boolean expire(String key, Duration ttl);

    void del(String key);

    /** 向集合加入成员，返回「新增」的数量（已存在的成员不计）。 */
    long sadd(String key, Duration ttl, String... members);

    /** 从集合移除成员，返回实际移除的数量。 */
    long srem(String key, String... members);

    /** 读取集合全部成员；key 不存在时返回空集，调用方不必判 null。 */
    Set<String> members(String key);

    /**
     * 有序集合打分，供 T7.x「情绪排行榜」使用。
     * local 降级模式不支持（Caffeine 没有跨 key 的有序聚合），实现方会抛 90005。
     */
    void zadd(String key, String member, double score);

    /** 当前生效的实现，/api/system/info 会暴露它，便于答辩演示降级开关。 */
    String mode();
}

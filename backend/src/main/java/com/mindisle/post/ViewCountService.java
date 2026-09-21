package com.mindisle.post;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import com.mindisle.cache.CacheService;

/**
 * 浏览量计数（任务 3.5 · 手册 §6.1 3.5 行「详情页 view_cnt 走缓存 + 每 5 分钟回写」）。
 *
 * <p><b>为什么不让每次打开详情都 UPDATE 一次库</b>：浏览量是全系统写得最频繁的字段，
 * 读多写多且单条值毫无业务含义（没人因为差 1 个数改变行为）。逐次 UPDATE 会把热点行的行锁
 * 串成一条队，列表页刷十屏就打满了。所以计数先进缓存，<b>每帖每 5 分钟最多回写一次</b>，
 * 回写用 {@code view_cnt = view_cnt + delta} 的库内累加，不用「读出来加一再写回去」。</p>
 *
 * <p><b>展示值恒等于「库值 + 未回写增量」</b>：回写只是把同一个数字从缓存搬到库里，
 * 搬完缓存清零、库加 delta，两边相加不变——所以用户永远看不到数字倒退，
 * 也看不到「回写那一刻突然 +7」。这条不变式由 {@code ViewCountServiceTest} 逐次调用钉住。</p>
 *
 * <p><b>窗口判定为什么不用 TTL 过期</b>：本地降级实现 Caffeine 的 {@code asMap().compute}
 * 在条目「逻辑过期但尚未清理」时仍可能看到旧值，若把「key 不存在」当窗口边界，
 * 最坏结果是窗口永不翻转、delta 一直堆在缓存里不落库——那比丢几个数更糟。
 * 这里改成显式存「本窗口起始秒」并由<b>调用方传入 now</b>（与 {@code PostService.publish}
 * 同一口径），判定只依赖比较，不依赖任何缓存实现的过期语义。</p>
 *
 * <p><b>已知的丢数窗口，写白不藏</b>：未回写的增量只存在于缓存里，
 * 进程重启或缓存被容量挤掉就会丢（最多一个帖一个窗口）。与任务 3.12「配额计数重启归零」
 * 同类，属答辩局限清单。真实场景要绝对不丢得引消息队列或定时落盘 job，超出毕设范围。</p>
 */
public class ViewCountService {

    /** 回写端口。真环境接 {@code PostMapper::increaseViewCnt}，单测里换成记账的假实现。 */
    public interface Flusher {
        int add(long postId, long delta);
    }

    /** 手册口径：每 5 分钟回写一次。 */
    static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);

    private static final String ACC_PREFIX = "post:view:acc:";
    private static final String GATE_PREFIX = "post:view:win:";

    /**
     * 未回写增量的存活上限。它只是「别让 key 永远占着缓存」的兜底，
     * 不参与窗口判定——判定靠 gate key 里存的起始秒。
     */
    private static final Duration ACC_TTL = Duration.ofDays(1);

    private final CacheService cache;
    private final Flusher flusher;
    private final Duration window;

    public ViewCountService(CacheService cache, Flusher flusher) {
        this(cache, flusher, DEFAULT_WINDOW);
    }

    ViewCountService(CacheService cache, Flusher flusher, Duration window) {
        this.cache = cache;
        this.flusher = flusher;
        this.window = window;
    }

    /**
     * 记一次浏览，并给出该帖当前的展示浏览量。
     *
     * @param postId        帖子 id
     * @param dbViewCnt     本次读到的库值（调用方已经在同一次查询里拿到，不再回查）
     * @param now           时间基准，由调用方传入
     * @return 展示值 = 库值 + 尚未回写的增量（含本次）
     */
    public long recordView(long postId, long dbViewCnt, LocalDateTime now) {
        long delta = cache.incr(ACC_PREFIX + postId, ACC_TTL);
        maybeFlush(postId, now);
        return dbViewCnt + delta;
    }

    /**
     * 距上次回写已满一个窗口时，把未回写增量整批搬进库里。
     *
     * <p>并发下两个线程可能同时判定「该回写了」，但 {@code getAndDelete} 是原子的：
     * 只有一个拿得到非空 delta，另一个拿到 null 就直接跳过，不会重复累加。</p>
     */
    void maybeFlush(long postId, LocalDateTime now) {
        String gateKey = GATE_PREFIX + postId;
        long nowEpoch = now.atZone(ZoneId.systemDefault()).toEpochSecond();
        Long windowStart = cache.get(gateKey, Long.class);
        if (windowStart != null && nowEpoch - windowStart < window.getSeconds()) {
            return;
        }
        // 先立窗口再搬数：反过来会让「搬数失败」留下一个没有窗口的空档，
        // 下一个请求立刻又判定该回写，故障时会被放大成写库风暴。
        cache.set(gateKey, nowEpoch, window.multipliedBy(2));
        Long pending = cache.getAndDelete(ACC_PREFIX + postId, Long.class);
        if (pending != null && pending > 0) {
            flusher.add(postId, pending);
        }
    }

    /** 读未回写增量；列表接口不用它（列表给库值即可），留给单测与管理端排查。 */
    public long pending(long postId) {
        Long v = cache.get(ACC_PREFIX + postId, Long.class);
        return v == null ? 0L : v;
    }
}

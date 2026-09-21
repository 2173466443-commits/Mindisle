package com.mindisle.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mindisle.cache.CaffeineCacheService;

/**
 * 浏览量「缓存累加 + 定时回写」单测（任务 3.5 · 手册 §6.1 行 3.5）。
 *
 * <p>本类不连库、不打桩：{@link FakeDb} 既是 Flusher 也扮演「库里的 view_cnt」，
 * 于是能逐次核对那条最关键的不变式——<b>展示值恒等于「已回写库值 + 未回写增量」，也就是真实总浏览次数</b>。
 * 回写只是把同一个数字从缓存搬进库，用户既不该看到数字倒退，也不该看到「回写那一刻凭空 +5」。
 * 这条不变式一旦破了就是双计或漏计，比偶发丢几个数严重得多。</p>
 *
 * <p>时间全部走固定入参：服务内部不读系统时钟（与 {@code PostService.publish} 同一口径），
 * 所以「五分钟窗口」在一毫秒内就跨完了，不需要 sleep，也不会因机器慢而偶发红。</p>
 */
class ViewCountServiceTest {

    /** 2026-09-20 10:00，避开整点与跨日边界，防「凑巧对上」。 */
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 20, 10, 0);

    private static final Duration WINDOW = Duration.ofMinutes(5);

    private static final long POST_ID = 7L;

    /**
     * 扮演数据库：{@code add} 就是那句 {@code UPDATE post SET view_cnt = view_cnt + delta}，
     * {@code viewCnt(id)} 就是当前库值。按帖子分开记账，好让「key 串台」这类错当场暴露。
     */
    private static final class FakeDb implements ViewCountService.Flusher {

        private final Map<Long, Long> viewCnt = new LinkedHashMap<>();
        private final List<Long> deltas = new ArrayList<>();
        private final List<Long> otherDeltas = new ArrayList<>();

        @Override
        public int add(long id, long delta) {
            viewCnt.merge(id, delta, Long::sum);
            (id == POST_ID ? deltas : otherDeltas).add(delta);
            return 1;
        }

        long viewCnt(long id) {
            return viewCnt.getOrDefault(id, 0L);
        }
    }

    private final CaffeineCacheService cache = new CaffeineCacheService();

    /**
     * 模拟一次详情页访问：调用方先读到库值，再交给 {@code recordView}。
     * 顺序不能反——真实链路里这两步是同一次 selectById 的产物。
     */
    private long view(ViewCountService service, FakeDb db, LocalDateTime now) {
        return service.recordView(POST_ID, db.viewCnt(POST_ID), now);
    }

    @Test
    @DisplayName("回写窗口默认 5 分钟，与手册 §6.1 逐字一致")
    void defaultWindowIsFiveMinutes() {
        assertThat(ViewCountService.DEFAULT_WINDOW).isEqualTo(Duration.ofMinutes(5));
        FakeDb db = new FakeDb();
        ViewCountService service = new ViewCountService(cache, db);
        assertThat(view(service, db, T0)).isEqualTo(1L);
        assertThat(view(service, db, T0.plusSeconds(299))).isEqualTo(2L);
        assertThat(db.deltas).as("299 秒时还没满窗口").containsExactly(1L);
        assertThat(view(service, db, T0.plusSeconds(300))).isEqualTo(3L);
        assertThat(db.deltas).as("满窗口才回写").containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("展示值恒等于真实浏览次数：跨两个窗口、只回写三批，既不倒退也不凭空多")
    void displayedCountAlwaysEqualsTrueTotal() {
        FakeDb db = new FakeDb();
        ViewCountService service = new ViewCountService(cache, db, WINDOW);
        long previous = 0L;
        for (int i = 1; i <= 12; i++) {
            long shown = view(service, db, T0.plusSeconds((i - 1L) * 60L));
            assertThat(shown).as("第 %d 次浏览的展示值", i).isEqualTo(i);
            assertThat(shown).as("第 %d 次浏览不能比上一次小", i).isGreaterThanOrEqualTo(previous);
            previous = shown;
        }
        // 首访立窗口即落 1 次，第 6 次、第 11 次各整批搬 5 次；12 次浏览只发 3 条 UPDATE
        assertThat(db.deltas).containsExactly(1L, 5L, 5L);
        assertThat(db.viewCnt(POST_ID)).isEqualTo(11L);
        assertThat(service.pending(POST_ID)).isEqualTo(1L);
        assertThat(db.viewCnt(POST_ID) + service.pending(POST_ID)).isEqualTo(12L);
    }

    @Test
    @DisplayName("整批搬移：回写后缓存归零，第二批是这批的总和而不是 1")
    void flushMovesTheWholeBatch() {
        FakeDb db = new FakeDb();
        ViewCountService service = new ViewCountService(cache, db, WINDOW);
        assertThat(view(service, db, T0)).as("首访没有窗口，立完窗口就把这 1 次落库").isEqualTo(1L);
        for (int i = 2; i <= 5; i++) {
            view(service, db, T0.plusSeconds(i * 30L)); // 两分钟内四次，全落在同一个窗口
        }
        assertThat(db.deltas).containsExactly(1L);
        assertThat(service.pending(POST_ID)).isEqualTo(4L);
        assertThat(view(service, db, T0.plusMinutes(5))).as("含本次共五次一起搬").isEqualTo(6L);
        assertThat(db.deltas).containsExactly(1L, 5L);
        assertThat(service.pending(POST_ID)).isZero();
        assertThat(db.viewCnt(POST_ID)).isEqualTo(6L);
    }

    @Test
    @DisplayName("每帖各记各的账，缓存 key 不串")
    void countsArePerPost() {
        FakeDb db = new FakeDb();
        ViewCountService service = new ViewCountService(cache, db, WINDOW);
        service.recordView(POST_ID, 0L, T0);
        service.recordView(POST_ID + 1, 0L, T0);
        service.recordView(POST_ID + 1, db.viewCnt(POST_ID + 1), T0.plusSeconds(60));
        assertThat(service.pending(POST_ID)).as("7 号帖首访已回写").isZero();
        assertThat(service.pending(POST_ID + 1)).as("8 号帖还在窗口内").isEqualTo(1L);
        assertThat(db.deltas).containsExactly(1L);
        assertThat(db.otherDeltas).containsExactly(1L);
        assertThat(db.viewCnt(POST_ID)).isEqualTo(1L);
        assertThat(db.viewCnt(POST_ID + 1)).isEqualTo(1L);
    }

    @Test
    @DisplayName("pending 是只读探针，不会把累计值顺手清掉")
    void pendingDoesNotConsumeTheAccumulator() {
        FakeDb db = new FakeDb();
        ViewCountService service = new ViewCountService(cache, db, WINDOW);
        view(service, db, T0);
        view(service, db, T0.plusMinutes(1));
        assertThat(service.pending(POST_ID)).isEqualTo(1L);
        assertThat(service.pending(POST_ID)).as("连读两次值不变").isEqualTo(1L);
        view(service, db, T0.plusMinutes(5));
        assertThat(db.deltas).as("第二次读没有吞掉计数，所以搬的是 2").containsExactly(1L, 2L);
    }
}

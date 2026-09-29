package com.mindisle.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 重排单测（任务 T7.6 · 手册 §10.2 7.6 · 需求 FR5.7 FR8.6）。
 *
 * <p>三件事各钉一条边界，它们的失败形态都长得不像 bug：</p>
 * <ul>
 *   <li><b>打散的窗口</b>：这里修过一个 off-by-one（窗口取 cap−1 且判据 {@code same >= cap−1}），
 *   实际效果是「最多连续 1 条同话题」，比需求严了一倍。它不会报错，只会让缓存批次变短、
 *   话题单一的新作者永远看不到自己的帖子被推出去。所以断言写成「第二条放行、第三条拦下」。</li>
 *   <li><b>探索位退化</b>：那一格没有不冲突的探索候选时按高分顶上，但<b>槽位照样消耗</b>，
 *   否则下一屏的探索位会往前挤，1/5 的比例就不成立了。</li>
 *   <li><b>不留空洞</b>与<b>不越约束</b>是一对矛盾：找不到满足打散的候选时收工，
 *   而不是硬塞一条违反约束的进去。</li>
 * </ul>
 */
class ReRankTest {

    private static ReRank.Candidate cand(long id, double score, String topic) {
        return new ReRank.Candidate(id, score, ColdStart.CHANNEL_ITEMCF, topic, false);
    }

    private static ReRank.Candidate explore(long id, double score, String topic) {
        return new ReRank.Candidate(id, score, ColdStart.CHANNEL_EXPLORE, topic, true);
    }

    private static List<Long> ids(List<ReRank.Candidate> list) {
        List<Long> out = new ArrayList<>();
        for (ReRank.Candidate candidate : list) {
            out.add(candidate.itemId());
        }
        return out;
    }

    @Test
    @DisplayName("入参为空、size 非正一律给空表，不抛异常（在线链路宁可少一屏也不要 500）")
    void emptyInputsYieldEmptyList() {
        assertThat(ReRank.rerank(null, 10)).isEmpty();
        assertThat(ReRank.rerank(List.of(), 10)).isEmpty();
        assertThat(ReRank.rerank(List.of(cand(1L, 0.9d, "t1")), 0)).isEmpty();
        assertThat(ReRank.rerank(List.of(cand(1L, 0.9d, "t1")), -3)).isEmpty();
    }

    @Test
    @DisplayName("同话题最多连排 2 条：第 3 条被后面的异话题顶替，但它没有被丢掉")
    void topicSpreadPushesThirdDown() {
        List<ReRank.Candidate> ranked = List.of(cand(1L, 0.9d, "t1"), cand(2L, 0.8d, "t1"),
            cand(3L, 0.7d, "t1"), cand(4L, 0.6d, "t2"));

        List<ReRank.Candidate> out = ReRank.rerank(ranked, 4);

        assertThat(ids(out)).containsExactly(1L, 2L, 4L, 3L);
        // 边界精确性：前两条同话题是允许的（cap=2），第三条才被打散
        assertThat(ReRank.violatesSpread(List.of(cand(1L, 0.9d, "t1")), "t1")).isFalse();
        assertThat(ReRank.violatesSpread(
            List.of(cand(1L, 0.9d, "t1"), cand(2L, 0.8d, "t1")), "t1")).isTrue();
    }

    @Test
    @DisplayName("spreadCap 是参数不是常量：cap=1 立刻拦第二条同话题，cap=3 放行三条")
    void spreadCapIsHotReloadable() {
        List<ReRank.Candidate> ranked = List.of(cand(1L, 0.9d, "t1"), cand(2L, 0.8d, "t1"),
            cand(3L, 0.7d, "t1"));

        assertThat(ids(ReRank.rerank(ranked, 3, 1))).containsExactly(1L);
        assertThat(ids(ReRank.rerank(ranked, 3, 2))).containsExactly(1L, 2L);
        assertThat(ids(ReRank.rerank(ranked, 3, 3))).containsExactly(1L, 2L, 3L);
        // cap<=0 按 1 处理，不许出现「谁都放不进来」的死配置
        assertThat(ids(ReRank.rerank(ranked, 3, 0))).containsExactly(1L);
        assertThat(ReRank.violatesSpread(List.of(cand(1L, 0.9d, "t1")), "t1", -5)).isTrue();
    }

    @Test
    @DisplayName("无话题的候选不参与打散：否则一堆 null 会被当成「同一个话题」互相挤掉")
    void nullAndBlankTopicsNeverSpreadBlocked() {
        List<ReRank.Candidate> ranked = List.of(cand(1L, 0.9d, null), cand(2L, 0.8d, null),
            cand(3L, 0.7d, "   "), cand(4L, 0.6d, ""));

        assertThat(ids(ReRank.rerank(ranked, 4, 1))).containsExactly(1L, 2L, 3L, 4L);
        assertThat(ReRank.violatesSpread(List.of(cand(1L, 0.9d, null)), null)).isFalse();
        assertThat(ReRank.violatesSpread(List.of(), "t1")).isFalse();
        assertThat(ReRank.violatesSpread(null, "t1")).isFalse();
    }

    @Test
    @DisplayName("每 5 格插 1 条探索位，且它取的是探索候选而不是全局最高分")
    void exploreSlotInsertedEveryFive() {
        List<ReRank.Candidate> ranked = List.of(cand(1L, 0.9d, "a"), cand(2L, 0.8d, "b"),
            cand(3L, 0.7d, "c"), cand(4L, 0.6d, "d"), cand(5L, 0.5d, "e"),
            explore(99L, 0.05d, "f"));

        List<ReRank.Candidate> out = ReRank.rerank(ranked, 5);

        assertThat(ids(out)).containsExactly(1L, 2L, 3L, 4L, 99L);
        assertThat(out.get(4).explore()).isTrue();
        assertThat(out.get(4).channel()).isEqualTo(ColdStart.CHANNEL_EXPLORE);
    }

    @Test
    @DisplayName("探索位找不到候选时退化成高分顶上，但槽位照样消耗，下一屏比例不前挤")
    void exploreSlotDegradesButStillConsumesSlot() {
        List<ReRank.Candidate> noExplore = new ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            noExplore.add(cand(id, 1d - id / 100d, "t" + id));
        }

        List<ReRank.Candidate> out = ReRank.rerank(noExplore, 10);

        // 没有探索候选 → 完全按原序出，不留空洞，也不改变次序
        assertThat(ids(out)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

        // 只有一条探索候选时：第 5 格给它，第 10 格之后没有可给的就照常按分数排
        List<ReRank.Candidate> withOneExplore = new ArrayList<>(noExplore.subList(0, 5));
        withOneExplore.add(explore(99L, 0.01d, "z"));
        withOneExplore.addAll(noExplore.subList(5, 10));
        List<ReRank.Candidate> mixed = ReRank.rerank(withOneExplore, 10);
        assertThat(ids(mixed).get(4)).as("第 5 格（下标 4）是探索位").isEqualTo(99L);
        assertThat(mixed).hasSize(10);
    }

    @Test
    @DisplayName("凑不出满足打散的候选就收工：宁短不违规，也不硬塞一条同话题进去")
    void stopsInsteadOfViolatingSpread() {
        List<ReRank.Candidate> ranked = List.of(cand(1L, 0.9d, "t1"), cand(2L, 0.8d, "t1"),
            cand(3L, 0.7d, "t1"), cand(4L, 0.6d, "t1"));

        List<ReRank.Candidate> out = ReRank.rerank(ranked, 4, 2);

        assertThat(ids(out)).containsExactly(1L, 2L);
        assertThat(out).hasSize(2);
        // 池子比 size 小时按池子收工，不死循环
        assertThat(ReRank.rerank(ranked, 99, 2)).hasSize(2);
    }
}

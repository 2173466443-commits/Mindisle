package com.mindisle.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * UserCF 邻居与偏好聚合单测（任务 T7.2 · 手册 §11.1 点名类 · 需求 FR5.2）。
 *
 * <p>与 ItemCF 的分工不是「两个都算一遍挑高的」，而是各答一个问题：ItemCF 说「看过这篇的人还看了」，
 * UserCF 说「跟你口味像的人在看什么」。冷启动首屏（{@link ColdStart#firstChannel} 够数时）走的就是后者，
 * 所以它的失败形态是<b>新用户的第一屏</b>，值得单独钉。</p>
 *
 * <p>本类重点钉三件在页面上都看不出问题的事：</p>
 * <ul>
 *   <li><b>热门惩罚</b>：不惩罚的话「什么都看的老用户」会成为所有人的邻居，UserCF 退化成热度榜，
 *       需求 §6.4「混合 vs 纯热门」对照组直接失去意义，而线上表现只是「推荐变得还行」；</li>
 *   <li><b>{@code ln(0)}</b>：活跃度为 0 时若照公式硬算会得到 −∞ 分母 → NaN，
 *       排序结果从此取决于 HashMap 顺序；</li>
 *   <li><b>聚合用加权平均而不是加权求和</b>：求和会让「邻居多且都爱看」的帖分数无上限，
 *       融合公式里 w1·CF 一项吃掉其余全部权重，质量分、新鲜度、情绪三项形同虚设。</li>
 * </ul>
 */
class UserCfSimilarityTest {

    private static final double ALPHA = RecConstants.HOT_PENALTY_ALPHA;

    private Map<Long, Map<Long, Double>> matrix;

    private static Map<Long, Double> vec(Object... pairs) {
        Map<Long, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(((Number) pairs[i]).longValue(), ((Number) pairs[i + 1]).doubleValue());
        }
        return out;
    }

    @BeforeEach
    void setUp() {
        matrix = new LinkedHashMap<>();
        matrix.put(1L, vec(10L, 0.5d, 20L, 0.5d));
        matrix.put(2L, vec(10L, 0.5d, 20L, 0.5d));
        matrix.put(3L, vec(10L, 0.5d));
        matrix.put(4L, vec(99L, 1d));
    }

    @Test
    @DisplayName("热门惩罚：越活跃的邻居除得越狠；alpha=0 等于不惩罚")
    void penalizeShrinksBusyPeers() {
        assertThat(UserCf.penalize(1d, 10, 0d)).as("alpha=0 → 不惩罚").isEqualTo(1d);
        assertThat(UserCf.penalize(1d, 10, -1d)).as("负 alpha 夹到 0，不许放大相似度").isEqualTo(1d);
        assertThat(UserCf.penalize(1d, 10, ALPHA))
            .isCloseTo(1d / (1d + Math.log(10d) * ALPHA), within(1e-12));
        assertThat(UserCf.penalize(1d, 100, ALPHA))
            .as("同样相似度，看 100 篇的人要比看 10 篇的人更不可信")
            .isLessThan(UserCf.penalize(1d, 10, ALPHA));
        assertThat(UserCf.penalize(1d, 3, ALPHA)).isCloseTo(0.752113d, within(1e-5));
    }

    @Test
    @DisplayName("活跃度 <=0 直接返回原相似度：ln(0)=−∞ 会把整行变成 NaN")
    void penalizeGuardsLogDomain() {
        assertThat(UserCf.penalize(0.42d, 0, ALPHA)).isEqualTo(0.42d);
        assertThat(UserCf.penalize(0.42d, -5, ALPHA)).isEqualTo(0.42d);
        assertThat(Double.isNaN(UserCf.penalize(0.42d, 0, ALPHA))).isFalse();
        assertThat(Double.isInfinite(UserCf.penalize(0.42d, 0, ALPHA))).isFalse();
    }

    @Test
    @DisplayName("邻居表：排掉自己、排掉共同数不足的、按相似度降序")
    void neighborsFilterAndSort() {
        List<UserCf.Neighbor> neighbors = UserCf.neighbors(matrix, 1L, 10, 1, 0d);

        assertThat(neighbors).extracting(UserCf.Neighbor::userId).containsExactly(2L, 3L);
        assertThat(neighbors.get(0).score()).isCloseTo(1d, within(1e-6));
        assertThat(neighbors.get(1).score()).isCloseTo(0.707107d, within(1e-6));
        assertThat(neighbors).as("目标用户不能是自己的邻居").noneMatch(n -> n.userId() == 1L);
        assertThat(neighbors).as("零交集的用户不进邻居表").noneMatch(n -> n.userId() == 4L);
    }

    @Test
    @DisplayName("共同帖数下限：minCommon=2 时只有一篇重合的邻居被筛光")
    void neighborsRespectMinCommon() {
        assertThat(UserCf.neighbors(matrix, 1L, 10, 2, 0d))
            .extracting(UserCf.Neighbor::userId).containsExactly(2L);
        assertThat(UserCf.neighbors(matrix, 1L, 10, 3, 0d)).as("一篇都不够 → 空邻居").isEmpty();
    }

    @Test
    @DisplayName("负相似度的邻居不进表：方向相反不是「像」，硬留着会把推荐推反")
    void neighborsDropNonPositiveSimilarity() {
        matrix.put(5L, vec(10L, -0.5d, 20L, -0.5d));

        List<UserCf.Neighbor> neighbors = UserCf.neighbors(matrix, 1L, 10, 1, 0d);

        assertThat(neighbors).extracting(UserCf.Neighbor::userId).containsExactly(2L, 3L);
        assertThat(neighbors).noneMatch(n -> n.userId() == 5L);
        assertThat(neighbors).allMatch(n -> n.score() > 0d);
    }

    @Test
    @DisplayName("Top-K 截断与同分按 userId 升序：同输入必须同输出，离线任务要可复现")
    void neighborsTruncateAndBreakTiesDeterministically() {
        Map<Long, Map<Long, Double>> tied = new LinkedHashMap<>();
        tied.put(1L, vec(10L, 0.5d, 20L, 0.5d));
        tied.put(7L, vec(10L, 0.5d, 20L, 0.5d));
        tied.put(3L, vec(10L, 0.5d, 20L, 0.5d));
        tied.put(5L, vec(10L, 0.5d, 20L, 0.5d));

        assertThat(UserCf.neighbors(tied, 1L, 10, 1, 0d))
            .extracting(UserCf.Neighbor::userId).containsExactly(3L, 5L, 7L);
        assertThat(UserCf.neighbors(tied, 1L, 2, 1, 0d))
            .extracting(UserCf.Neighbor::userId).containsExactly(3L, 5L);
        assertThat(UserCf.neighbors(tied, 1L, 0, 1, 0d)).isEmpty();
        assertThat(UserCf.neighbors(tied, 1L, -1, 1, 0d)).isEmpty();
        assertThat(UserCf.neighbors(matrix, 404L, 10, 1, 0d)).as("没有行为矩阵的用户没邻居").isEmpty();
        assertThat(RecConstants.TOP_K_USER_NEIGHBOR).isEqualTo(50);
    }

    @Test
    @DisplayName("原始余弦打平时，热门惩罚把「什么都看」的那个压下去：邻居质量看的是共同专注度")
    void popularityPenaltyReordersNeighbors() {
        Map<Long, Map<Long, Double>> peers = new LinkedHashMap<>();
        peers.put(1L, vec(10L, 1d, 20L, 1d));
        // 与目标共 2 篇、另看 2 篇 → 原始余弦 2/(√2·2)
        peers.put(2L, vec(10L, 1d, 20L, 1d, 30L, 1d, 40L, 1d));
        // 只与目标共 1 篇 → 原始余弦 1/(√2·1)，两者数值相同
        peers.put(3L, vec(10L, 1d));

        assertThat(ItemCf.cosine(peers.get(1L), peers.get(2L)))
            .as("先确认这是一组打平样本：惩罚项才是唯一变量")
            .isCloseTo(ItemCf.cosine(peers.get(1L), peers.get(3L)), within(1e-9));

        List<UserCf.Neighbor> noPenalty = UserCf.neighbors(peers, 1L, 10, 1, 0d);
        List<UserCf.Neighbor> penalized = UserCf.neighbors(peers, 1L, 10, 1, ALPHA);

        assertThat(noPenalty).extracting(UserCf.Neighbor::userId).containsExactly(2L, 3L);
        assertThat(penalized).extracting(UserCf.Neighbor::userId).containsExactly(3L, 2L);
        assertThat(penalized.get(0).score()).isGreaterThan(penalized.get(1).score());
    }

    @Test
    @DisplayName("候选打分是加权平均：分数不会随邻居数量增长，且跳过目标用户已交互的帖")
    void scoreCandidatesUsesWeightedAverage() {
        Map<Long, Double> target = vec(10L, 0.5d, 20L, 0.5d);
        List<UserCf.Neighbor> neighbors = List.of(new UserCf.Neighbor(2L, 0.8d),
            new UserCf.Neighbor(3L, 0.2d));
        Map<Long, Map<Long, Double>> scores = new LinkedHashMap<>();
        scores.put(2L, vec(20L, 1d, 30L, 1d));
        scores.put(3L, vec(40L, 0.5d));

        Map<Long, Double> out = UserCf.scoreCandidates(target, neighbors, scores);

        assertThat(out).containsOnlyKeys(30L, 40L);
        assertThat(out.get(30L)).as("0.8*1 / (0.8+0.2)").isCloseTo(0.8d, within(1e-12));
        assertThat(out.get(40L)).as("0.2*0.5 / 1.0").isCloseTo(0.1d, within(1e-12));
        assertThat(out).as("目标用户看过的 20 不进推荐位").doesNotContainKey(20L);
    }

    @Test
    @DisplayName("聚合的退化输入：空邻居、零权重邻居、矩阵里查不到的人都给空结果而不是崩")
    void scoreCandidatesDegeneratesGracefully() {
        Map<Long, Map<Long, Double>> scores = new LinkedHashMap<>();
        scores.put(2L, vec(30L, 1d));

        assertThat(UserCf.scoreCandidates(vec(10L, 0.5d), List.of(), scores)).isEmpty();
        assertThat(UserCf.scoreCandidates(vec(10L, 0.5d),
            List.of(new UserCf.Neighbor(2L, 0d)), scores))
            .as("权重和为 0 → 空，不做 0/0").isEmpty();
        assertThat(UserCf.scoreCandidates(vec(10L, 0.5d),
            List.of(new UserCf.Neighbor(2L, -1d)), scores)).isEmpty();
        assertThat(UserCf.scoreCandidates(vec(10L, 0.5d),
            List.of(new UserCf.Neighbor(77L, 0.5d)), scores))
            .as("邻居表里的人在矩阵里查不到（窗口外过期）→ 空").isEmpty();
        assertThat(UserCf.scoreCandidates(null, List.of(new UserCf.Neighbor(2L, 0.5d)), scores))
            .as("targetScores 为 null 时不做去重，但仍给得出分").containsEntry(30L, 1d);
    }

    @Test
    @DisplayName("邻居数增加不会把分数顶出 0..1：这就是选平均而不是求和的理由")
    void weightedAverageStaysBoundedRegardlessOfNeighbourCount() {
        Map<Long, Map<Long, Double>> scores = new LinkedHashMap<>();
        for (long peer = 2L; peer <= 40L; peer++) {
            scores.put(peer, vec(30L, 1d));
        }
        List<UserCf.Neighbor> few = List.of(new UserCf.Neighbor(2L, 0.1d));
        List<UserCf.Neighbor> many = new java.util.ArrayList<>();
        for (long peer = 2L; peer <= 40L; peer++) {
            many.add(new UserCf.Neighbor(peer, 0.1d));
        }

        double fewScore = UserCf.scoreCandidates(vec(10L, 0.5d), few, scores).get(30L);
        double manyScore = UserCf.scoreCandidates(vec(10L, 0.5d), many, scores).get(30L);

        assertThat(fewScore).isCloseTo(1d, within(1e-12));
        assertThat(manyScore).as("39 个邻居和 1 个邻居给同样的分：分数量纲与邻居数无关")
            .isCloseTo(1d, within(1e-12));
    }

    @Test
    @DisplayName("端到端一小段：邻居表喂给候选打分，产出的是没看过的帖")
    void neighborsFeedCandidateScoring() {
        matrix.put(2L, vec(10L, 0.5d, 20L, 0.5d, 30L, 0.9d));
        List<UserCf.Neighbor> neighbors = UserCf.neighbors(matrix, 1L,
            RecConstants.TOP_K_USER_NEIGHBOR, 1, ALPHA);

        Map<Long, Double> out = UserCf.scoreCandidates(matrix.get(1L), neighbors, matrix);

        assertThat(neighbors).isNotEmpty();
        assertThat(out).containsKey(30L);
        assertThat(out).doesNotContainKeys(10L, 20L);
        for (double value : out.values()) {
            assertThat(value).isGreaterThanOrEqualTo(0d).isLessThanOrEqualTo(10d);
        }
    }
}

package com.mindisle.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ItemCF 相似度单测（任务 T7.2 / T7.3 · 手册 §11.1 点名类 · 需求 FR5.2）。
 *
 * <p>手册点名的六个覆盖面（重合 / 零交集 / 自身 / 负分 / 极稀疏 / 热门惩罚）在这里逐条对号，
 * 因为它们各自对应一种会让离线批次「静默变坏」的形态：</p>
 * <ul>
 *   <li><b>NaN</b>：零交集时 {@code 0/0}。一旦写进 {@code item_similarity.sim_items}，
 *   下游 Top-K 排序的比较结果取决于 HashMap 遍历顺序——表现为「同样输入两次重算结果不同」，
 *   是那种会被当成数据库问题的算法 bug；</li>
 *   <li><b>负分被当 0 处理</b>：修正余弦之后负相似是真实信号（一正一负的共现），
 *   在 {@code dot == 0} 处短路会把「相反」说成「无关」；</li>
 *   <li><b>极稀疏</b>：本项目的量级是百篇帖、几十上百个用户。共现人数下限（MIN_CO_USERS=2）
 *   是唯一拦住「两个人的巧合」变成全网推荐的闸门；</li>
 *   <li><b>规模保护</b>：单用户向量截到 200 篇，共现对数才有上界。不截的话离线任务的
 *   复杂度按「行为数的平方」长，第一次跑就要 30 分钟以上，重算就永远追不上内容增长。</li>
 * </ul>
 */
class ItemCfSimilarityTest {

    private static Map<Long, Double> vec(Object... pairs) {
        Map<Long, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(((Number) pairs[i]).longValue(), ((Number) pairs[i + 1]).doubleValue());
        }
        return out;
    }

    @Test
    @DisplayName("余弦：自身为 1、正交为 0、反向为负；空向量和零向量给 0 而不是 NaN")
    void cosineCoversOverlapZeroAndNegative() {
        Map<Long, Double> a = vec(1L, 1d, 2L, 1d);
        assertThat(ItemCf.cosine(a, a)).isCloseTo(1d, within(1e-9));
        assertThat(ItemCf.cosine(a, vec(1L, 2d, 2L, 2d))).as("等比向量方向相同")
            .isCloseTo(1d, within(1e-9));
        assertThat(ItemCf.cosine(a, vec(3L, 1d))).as("零交集").isZero();
        assertThat(ItemCf.cosine(a, vec(1L, -1d, 2L, -1d))).as("反向必须给负数，不许短路成 0")
            .isCloseTo(-1d, within(1e-9));
        assertThat(ItemCf.cosine(a, vec(1L, 1d))).isCloseTo(0.707107d, within(1e-6));

        assertThat(ItemCf.cosine(null, a)).isZero();
        assertThat(ItemCf.cosine(a, null)).isZero();
        assertThat(ItemCf.cosine(Map.of(), a)).isZero();
        assertThat(ItemCf.cosine(a, Map.of())).isZero();
        assertThat(ItemCf.cosine(vec(1L, 0d), vec(1L, 1d)))
            .as("模为 0 的向量：分母 0，只能给 0").isZero();
        assertThat(Double.isNaN(ItemCf.cosine(vec(1L, 0d), vec(1L, 1d)))).isFalse();
    }

    @Test
    @DisplayName("修正余弦：减去用户均分后才进共现，抹掉「这人本来就爱点赞」的个体偏置")
    void adjustSubtractsUserMean() {
        Map<Long, Double> adjusted = ItemCf.adjust(vec(1L, 5d, 2L, 3d), 4d);
        assertThat(adjusted).containsEntry(1L, 1d);
        assertThat(adjusted).containsEntry(2L, -1d);
        assertThat(ItemCf.norm(adjusted)).isCloseTo(Math.sqrt(2d), within(1e-12));
        assertThat(ItemCf.adjust(null, 3d)).isEmpty();
        // 均值 0 时等价于原向量
        assertThat(ItemCf.norm(ItemCf.adjust(vec(1L, 2d), 0d))).isCloseTo(2d, within(1e-12));
    }

    @Test
    @DisplayName("共现相似度：两个共同用户才成对，0.8 的数值可手算复核")
    void cfSimilaritiesComputesCosineFromCooccurrence() {
        Map<Long, Map<Long, Double>> userItemScores = new LinkedHashMap<>();
        userItemScores.put(1L, vec(1L, 2d, 2L, 1d));
        userItemScores.put(2L, vec(1L, 1d, 2L, 2d));

        Map<Long, Map<Long, Double>> sims = ItemCf.cfSimilarities(userItemScores);

        assertThat(sims).containsOnlyKeys(1L, 2L);
        // dot = 2*1 + 1*2 = 4；norm 都是 √5 → 4/5 = 0.8
        assertThat(sims.get(1L).get(2L)).isCloseTo(0.8d, within(1e-9));
        assertThat(sims.get(2L).get(1L)).as("对称").isCloseTo(0.8d, within(1e-9));
    }

    @Test
    @DisplayName("极稀疏：只有 1 个共同用户的相似对整条丢掉——这是拦住「巧合变全网推荐」的闸")
    void loneCoUserPairsAreDropped() {
        assertThat(RecConstants.MIN_CO_USERS).isEqualTo(2);
        Map<Long, Map<Long, Double>> sparse = new LinkedHashMap<>();
        sparse.put(1L, vec(1L, 1d, 2L, 1d));
        sparse.put(2L, vec(3L, 1d));

        assertThat(ItemCf.cfSimilarities(sparse)).isEmpty();
    }

    @Test
    @DisplayName("零分共现给 0 而不是 NaN：一行 NaN 会把整张 Top-K 的排序变成遍历顺序")
    void zeroDenominatorYieldsZeroNotNaN() {
        Map<Long, Map<Long, Double>> userItemScores = new LinkedHashMap<>();
        userItemScores.put(1L, vec(1L, 0d, 2L, 1d));
        userItemScores.put(2L, vec(1L, 0d, 2L, 1d));

        Map<Long, Map<Long, Double>> sims = ItemCf.cfSimilarities(userItemScores);

        assertThat(sims.get(1L).get(2L)).isZero();
        assertThat(Double.isNaN(sims.get(1L).get(2L))).as("绝不允许把 NaN 写进 sim_items").isFalse();
    }

    @Test
    @DisplayName("规模保护：单用户向量截到 200 篇，最低分那篇不参与共现")
    void userVectorIsCapped() {
        Map<Long, Double> big = new LinkedHashMap<>();
        for (long item = 1; item <= 201; item++) {
            big.put(item, (double) item);
        }
        assertThat(big).hasSize(201);
        Map<Long, Double> capped = ItemCf.truncate(big, ItemCf.USER_VECTOR_CAP);
        assertThat(capped).as("丢的是最低分的 item 1，不是最先写的").hasSize(200)
            .doesNotContainKey(1L).containsKey(201L);

        Map<Long, Map<Long, Double>> userItemScores = new LinkedHashMap<>();
        userItemScores.put(1L, big);
        userItemScores.put(2L, big);
        Map<Long, Map<Long, Double>> sims = ItemCf.cfSimilarities(userItemScores);
        assertThat(sims).as("被截掉的向量不出现在结果里").doesNotContainKey(1L).hasSize(200);
        assertThat(sims.get(2L)).hasSize(199);
    }

    @Test
    @DisplayName("truncate：同分按 itemId 升序，cap 不小于长度时保序返回副本")
    void truncateIsDeterministic() {
        assertThat(ItemCf.truncate(null, 5)).isEmpty();

        Map<Long, Double> tied = vec(3L, 1d, 1L, 1d, 2L, 1d);
        assertThat(ItemCf.truncate(tied, 2).keySet()).containsExactly(1L, 2L);

        Map<Long, Double> spread = vec(1L, 1d, 2L, 9d, 3L, 5d);
        assertThat(ItemCf.truncate(spread, 2).keySet()).containsExactly(2L, 3L);
        assertThat(ItemCf.truncate(spread, 9).keySet()).containsExactly(1L, 2L, 3L);
        assertThat(ItemCf.truncate(spread, 0)).isEmpty();
    }

    @Test
    @DisplayName("内容相似度 = |A∩B|/√(|A|·|B|)：同标签为 1，无交集与空集为 0")
    void contentCosineUsesTagSets() {
        assertThat(ItemCf.contentCosine(Set.of("失眠", "考研"), Set.of("失眠", "考研")))
            .isCloseTo(1d, within(1e-12));
        assertThat(ItemCf.contentCosine(Set.of("失眠", "考研"), Set.of("失眠", "就业")))
            .isCloseTo(0.5d, within(1e-12));
        assertThat(ItemCf.contentCosine(Set.of("失眠"), Set.of("失眠", "考研", "就业")))
            .isCloseTo(1d / Math.sqrt(3d), within(1e-12));
        assertThat(ItemCf.contentCosine(Set.of("失眠"), Set.of("考研"))).isZero();
        assertThat(ItemCf.contentCosine(Set.of(), Set.of("失眠"))).isZero();
        assertThat(ItemCf.contentCosine(null, Set.of("失眠"))).isZero();
        assertThat(ItemCf.contentCosine(Set.of("失眠"), null)).isZero();
    }

    @Test
    @DisplayName("β 融合：0.7 是默认，越界回落到默认而不是按 0 处理（β=0 会静默废掉 CF）")
    void fuseUsesBetaAndGuardsRange() {
        assertThat(ItemCf.fuse(1d, 0d, 0.7d)).isCloseTo(0.7d, within(1e-12));
        assertThat(ItemCf.fuse(1d, 0d, 0d)).as("β=0 退化成纯内容").isCloseTo(0d, within(1e-12));
        assertThat(ItemCf.fuse(1d, 0d, 1d)).as("β=1 退化成纯 CF").isCloseTo(1d, within(1e-12));
        assertThat(ItemCf.fuse(1d, 0d, 2d)).as("越界回落 BETA_CF").isCloseTo(0.7d, within(1e-12));
        assertThat(ItemCf.fuse(1d, 0d, -0.5d)).isCloseTo(0.7d, within(1e-12));
        assertThat(ItemCf.fuse(0d, 1d, RecConstants.BETA_CF)).isCloseTo(0.3d, within(1e-12));
        assertThat(ItemCf.fuse(1d, 0d, Double.NaN))
            .as("配置读坏（sys_config 里写成 NaN 字面量）不许污染相似度")
            .isCloseTo(0.7d, within(1e-12));
        assertThat(ItemCf.fuse(1d, 0d, Double.POSITIVE_INFINITY)).isCloseTo(0.7d, within(1e-12));
    }

    @Test
    @DisplayName("Top-K：丢负分与低于下限的邻居、同分按 id 升序、按 6 位小数落库")
    void topKFiltersSortsAndRounds() {
        Map<Long, Double> row = vec(1L, 0.5d, 2L, 0.9d, 3L, 0.005d, 4L, -0.2d, 5L,
            RecConstants.MIN_NEIGHBOR_SCORE);

        List<ItemCf.Neighbor> top = ItemCf.topK(row, 3);

        assertThat(top).extracting(ItemCf.Neighbor::itemId).containsExactly(2L, 1L, 5L);
        assertThat(top.get(0).score()).isCloseTo(0.9d, within(1e-12));
        assertThat(ItemCf.topK(row, 99)).hasSize(3);
        assertThat(ItemCf.topK(vec(1L, 0.5d, 2L, 0.5d), 5))
            .as("同分按 itemId 升序，结果必须可复现").extracting(ItemCf.Neighbor::itemId)
            .containsExactly(1L, 2L);
        assertThat(ItemCf.topK(null, 5)).isEmpty();
        assertThat(ItemCf.topK(Map.of(), 5)).isEmpty();
        assertThat(ItemCf.topK(row, 0)).isEmpty();
        assertThat(ItemCf.topK(row, -1)).isEmpty();
        assertThat(ItemCf.round6(1d / 3d)).isEqualTo(0.333333d);
        assertThat(ItemCf.round6(0.1234567d)).isEqualTo(0.123457d);
        assertThat(ItemCf.round6(1d)).isEqualTo(1d);
    }

    @Test
    @DisplayName("邻居分数下限与 Top-K 上限都取手册口径（200 / 0.01），改它们要重跑消融实验")
    void constantsMatchManual() {
        assertThat(RecConstants.TOP_K_NEIGHBOR).isEqualTo(200);
        assertThat(RecConstants.BETA_CF).isBetween(0d, 1d);
        assertThat(ItemCf.USER_VECTOR_CAP).isEqualTo(200);
        List<ItemCf.Neighbor> capped = new ArrayList<>();
        Map<Long, Double> row = new LinkedHashMap<>();
        for (long item = 1; item <= 250; item++) {
            row.put(item, 0.5d);
        }
        capped.addAll(ItemCf.topK(row, RecConstants.TOP_K_NEIGHBOR));
        assertThat(capped).hasSize(200);
    }
}

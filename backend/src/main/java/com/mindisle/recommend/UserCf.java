package com.mindisle.recommend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UserCF：人—人相似度与邻居偏好聚合（任务 T7.2 · 手册 §10.2 7.2）。
 *
 * <p>与 ItemCF 的分工不是「两个都算一遍挑高的」，而是<b>各自回答不同的问题</b>：
 * ItemCF 说「看过这篇的人还看了」，UserCF 说「跟你口味像的人在看什么」。前者适合内容稳定、
 * 后者适合社区热点切换快，所以本项目两路都召，最后由 {@code recall_channel} 记住是谁供的货
 * （需求 §6.4 六组对照组要按通道出指标）。</p>
 *
 * <p><b>热门惩罚必须做</b>：不做的话「什么都看的老用户」会成为所有人的邻居，
 * 推荐退化成热度榜——这正是手册 §10.3 里「混合 vs 纯热门」对照组要抓的东西。</p>
 */
public final class UserCf {

  /** 邻居行：userId + 惩罚后的相似度。 */
  public record Neighbor(long userId, double score) {
  }

  private UserCf() {
  }

  /**
   * 热门惩罚：{@code sim / (1 + ln(邻居活跃度) · α)}（手册 T7.2 原文）。
   *
   * <p>活跃度为 0 时直接返回原相似度：{@code ln(0) = -∞}，写下去不是「惩罚失效」而是整行变 NaN，
   * 之后 Top-K 排序结果取决于 HashMap 的遍历顺序——那种 bug 在离线日志里长得像「随机重算结果不一样」。</p>
   */
  public static double penalize(double sim, int peerActivityCnt, double alpha) {
    if (peerActivityCnt <= 0) {
      return sim;
    }
    return sim / (1d + Math.log(peerActivityCnt) * Math.max(0d, alpha));
  }

  /**
   * 目标用户的邻居表。
   *
   * @param userItemScores userId -> (postId -> 隐式分，建议已归一化到 0–1)
   * @param minCommon      共同看过的帖子数下限，低于它这条「邻居关系」只是巧合
   */
  public static List<Neighbor> neighbors(Map<Long, Map<Long, Double>> userItemScores,
      long targetUserId, int topK, int minCommon, double alpha) {
    Map<Long, Double> target = userItemScores.get(targetUserId);
    if (target == null || target.isEmpty() || topK <= 0) {
      return List.of();
    }
    List<Neighbor> neighbors = new ArrayList<>();
    for (Map.Entry<Long, Map<Long, Double>> e : userItemScores.entrySet()) {
      long peer = e.getKey();
      if (peer == targetUserId || e.getValue() == null || e.getValue().isEmpty()) {
        continue;
      }
      int common = 0;
      for (Long item : e.getValue().keySet()) {
        if (target.containsKey(item)) {
          common++;
        }
      }
      if (common < minCommon) {
        continue;
      }
      double sim = penalize(ItemCf.cosine(target, e.getValue()), e.getValue().size(), alpha);
      if (sim > 0d) {
        neighbors.add(new Neighbor(peer, ItemCf.round6(sim)));
      }
    }
    neighbors.sort(Comparator.<Neighbor>comparingDouble(Neighbor::score).reversed()
        .thenComparingLong(Neighbor::userId));
    return neighbors.size() > topK ? new ArrayList<>(neighbors.subList(0, topK)) : neighbors;
  }

  /**
   * 邻居偏好聚合：{@code score(i) = Σ sim(u,v)·score(v,i) / Σ sim(u,v)}。
   *
   * <p>用加权平均而不是加权求和：求和会让「邻居多且都爱看」的帖子分数无上限，
   * 融合公式里的 {@code w1·CF} 就被这一项吃掉，质量分与情绪项形同虚设。</p>
   *
   * <p>目标用户已经交互过的帖子<b>不返回</b>：推荐流里出现自己点过的内容，
   * 用户读到的是「这系统没记住我看过」，比少推一篇更伤信任。</p>
   */
  public static Map<Long, Double> scoreCandidates(Map<Long, Double> targetScores,
      List<Neighbor> neighbors, Map<Long, Map<Long, Double>> userItemScores) {
    Map<Long, double[]> acc = new LinkedHashMap<>();
    double weightSum = 0d;
    for (Neighbor neighbor : neighbors) {
      Map<Long, Double> vector = userItemScores.get(neighbor.userId());
      if (vector == null || neighbor.score() <= 0d) {
        continue;
      }
      weightSum += neighbor.score();
      for (Map.Entry<Long, Double> e : vector.entrySet()) {
        if (targetScores != null && targetScores.containsKey(e.getKey())) {
          continue;
        }
        acc.computeIfAbsent(e.getKey(), k -> new double[1])[0] += neighbor.score() * e.getValue();
      }
    }
    Map<Long, Double> out = new LinkedHashMap<>();
    if (weightSum == 0d) {
      return out;
    }
    for (Map.Entry<Long, double[]> e : acc.entrySet()) {
      out.put(e.getKey(), e.getValue()[0] / weightSum);
    }
    return out;
  }
}
package com.mindisle.recommend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ItemCF：物品—物品相似度（任务 T7.2 与 T7.3 · 手册 §10.2 7.2 7.3）。
 *
 * <p><b>为什么在线只查表不在算</b>（手册 §7.5）：i–i 相似度是离线量，写进
 * {@code item_similarity.sim_items}（JSON Top-K），在线 {@code GET /api/posts/{id}/similar}
 * 与推荐召回都只是取一行 JSON。反过来在线算就等于把 O(用户数) 的点积放到 200ms 的 SLA 里。</p>
 *
 * <p><b>稀疏是本项目的常态而不是异常</b>：社区自有的数据量级是百篇帖、几十上百个用户，
 * 纯 CF 会出现大片零交集，所以 T7.3 用内容相似度融合兜底：
 * {@code sim_final = β·sim_cf + (1−β)·sim_content}。β=0.7 的取舍写进 {@link RecConstants}，
 * 消融实验要把 β 推到 0.5 / 0.9 各跑一条曲线（手册 §10.3）。</p>
 *
 * <p><b>规模保护（手册 T7.10）</b>：单个用户的行为向量先按分数截到 {@link #USER_VECTOR_CAP} 篇，
 * 共现对数就有一个上界；物品数超 5 000 时只算 ItemCF、跳过 UserCF，本类不做那次分支，
 * 分支在离线 Service 里（它才知道总物品数）。这是「实现可扩展性讨论」里真会被追问的一段。</p>
 */
public final class ItemCf {

  /** 邻居行：itemId + 相似度（已融合内容相似度）。 */
  public record Neighbor(long itemId, double score) {
  }

  /** 参与共现计算的单用户向量上限：超过就是给 CPU 加班，低分行为对相似度几乎没有贡献。 */
  public static final int USER_VECTOR_CAP = 200;

  private ItemCf() {
  }

  /**
   * 余弦相似度（可含负值，所以<b>不能</b>在 dot==0 时短路：0 也可能是正负抵消后的真实结果）。
   *
   * @param a 形如 {@code itemId -> score} 或 {@code userId -> score} 的稀疏向量
   * @return 零向量、空向量、或任一模为 0 时返回 0，绝不返回 NaN（NaN 会把 Top-K 排序整个打乱）
   */
  public static double cosine(Map<Long, Double> a, Map<Long, Double> b) {
    if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
      return 0d;
    }
    double normA = norm(a);
    double normB = norm(b);
    if (normA == 0d || normB == 0d) {
      return 0d;
    }
    Map<Long, Double> small = a.size() <= b.size() ? a : b;
    Map<Long, Double> large = small == a ? b : a;
    double dot = 0d;
    for (Map.Entry<Long, Double> e : small.entrySet()) {
      Double other = large.get(e.getKey());
      if (other != null) {
        dot += e.getValue() * other;
      }
    }
    return dot / (normA * normB);
  }

  /** 修正余弦的「修正」＝先减用户均分，把「这个人本来就爱点赞」这类个体偏置摘掉（手册 T7.2）。 */
  public static Map<Long, Double> adjust(Map<Long, Double> scores, double mean) {
    Map<Long, Double> adjusted = new LinkedHashMap<>();
    if (scores == null) {
      return adjusted;
    }
    for (Map.Entry<Long, Double> e : scores.entrySet()) {
      adjusted.put(e.getKey(), e.getValue() - mean);
    }
    return adjusted;
  }

  public static double norm(Map<Long, Double> vector) {
    double sum = 0d;
    if (vector != null) {
      for (double v : vector.values()) {
        sum += v * v;
      }
    }
    return Math.sqrt(sum);
  }

  /**
   * 由「用户—物品」评分构造每个物品的 CF 相似度（只算有共现的对，不构造 n×n 稠密阵）。
   *
   * <p>返回的 map 里每个物品的邻居<b>未截断、未融合内容相似度</b>：那两步留给调用方，
   * 因为 β 与 Top-K 都是实验变量，混在一条流水里就没法只改一个数跑对照。</p>
   *
   * @param userItemScores userId -> (postId -> 隐式分)
   * @return postId -> (邻帖 postId -> 余弦相似度)
   */
  public static Map<Long, Map<Long, Double>> cfSimilarities(
      Map<Long, Map<Long, Double>> userItemScores) {
    // 1) 倒排 itemId -> (userId -> score)，只为拿到每个物品自己的向量模（分母）与共现人数。
    Map<Long, Map<Long, Double>> itemVectors = new HashMap<>();
    for (Map.Entry<Long, Map<Long, Double>> u : userItemScores.entrySet()) {
      for (Map.Entry<Long, Double> i : truncate(u.getValue(), USER_VECTOR_CAP).entrySet()) {
        itemVectors.computeIfAbsent(i.getKey(), k -> new HashMap<>()).put(u.getKey(), i.getValue());
      }
    }
    Map<Long, Double> norms = new HashMap<>();
    for (Map.Entry<Long, Map<Long, Double>> e : itemVectors.entrySet()) {
      norms.put(e.getKey(), norm(e.getValue()));
    }
    // 2) 分子：只遍历「同一个人看过」的物品对，复杂度是 Σ min(行为数,200)² 而不是物品数的平方。
    Map<Long, Map<Long, Double>> dots = new HashMap<>();
    Map<Long, Map<Long, Integer>> coUsers = new HashMap<>();
    for (Map<Long, Double> capped : userItemScores.values()) {
      List<Map.Entry<Long, Double>> items = new ArrayList<>(truncate(capped, USER_VECTOR_CAP).entrySet());
      for (int x = 0; x < items.size(); x++) {
        for (int y = x + 1; y < items.size(); y++) {
          long i = items.get(x).getKey();
          long j = items.get(y).getKey();
          accumulate(dots, i, j, items.get(x).getValue() * items.get(y).getValue());
          accumulate(dots, j, i, items.get(x).getValue() * items.get(y).getValue());
          count(coUsers, i, j);
          count(coUsers, j, i);
        }
      }
    }
    // 3) 归一化 + 共现人数下限：只有 1 个共同用户的相似对是噪声（MIN_CO_USERS 可配成 1 做消融）。
    Map<Long, Map<Long, Double>> out = new HashMap<>();
    for (Map.Entry<Long, Map<Long, Double>> row : dots.entrySet()) {
      double normSelf = norms.getOrDefault(row.getKey(), 0d);
      Map<Long, Integer> peers = coUsers.getOrDefault(row.getKey(), Map.of());
      Map<Long, Double> sims = new LinkedHashMap<>();
      for (Map.Entry<Long, Double> n : row.getValue().entrySet()) {
        if (peers.getOrDefault(n.getKey(), 0) < RecConstants.MIN_CO_USERS) {
          continue;
        }
        double denominator = normSelf * norms.getOrDefault(n.getKey(), 0d);
        sims.put(n.getKey(), denominator == 0d ? 0d : n.getValue() / denominator);
      }
      if (!sims.isEmpty()) {
        out.put(row.getKey(), sims);
      }
    }
    return out;
  }
  private static void accumulate(Map<Long, Map<Long, Double>> table, long from, long to, double value) {
    table.computeIfAbsent(from, k -> new HashMap<>()).merge(to, value, Double::sum);
  }
  private static void count(Map<Long, Map<Long, Integer>> table, long from, long to) {
    table.computeIfAbsent(from, k -> new HashMap<>()).merge(to, 1, Integer::sum);
  }

  /** 按分数取前 cap 篇（分数相同按 itemId 升序，保证同输入同输出——离线任务必须可复现）。 */
  public static Map<Long, Double> truncate(Map<Long, Double> scores, int cap) {
    if (scores == null || scores.size() <= cap) {
      return scores == null ? new LinkedHashMap<>() : new LinkedHashMap<>(scores);
    }
    List<Map.Entry<Long, Double>> entries = new ArrayList<>(scores.entrySet());
    entries.sort(Comparator.<Map.Entry<Long, Double>>comparingDouble(Map.Entry::getValue).reversed()
        .thenComparing(Map.Entry::getKey));
    Map<Long, Double> kept = new LinkedHashMap<>();
    for (int i = 0; i < cap; i++) {
      kept.put(entries.get(i).getKey(), entries.get(i).getValue());
    }
    return kept;
  }

  /**
   * 内容相似度：标签集合（话题名 + 情绪标签）的二值向量余弦 ＝ {@code |A∩B| / √(|A|·|B|)}。
   *
   * <p>它不承担「懂内容」的责任，只承担一件事：CF 因为稀疏而给不出邻居时，
   * 同话题同情绪的帖子仍然要能被推到，而不是整屏热门榜。</p>
   */
  public static double contentCosine(Set<String> a, Set<String> b) {
    if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
      return 0d;
    }
    int intersection = 0;
    Set<String> small = a.size() <= b.size() ? a : b;
    Set<String> large = small == a ? b : a;
    for (String tag : small) {
      if (large.contains(tag)) {
        intersection++;
      }
    }
    return intersection / Math.sqrt((double) a.size() * b.size());
  }

  /**
   * 融合：{@code β·sim_cf + (1−β)·sim_content}（手册 T7.3）。
   *
   * <p>β 越界一律回落到 {@link RecConstants#BETA_CF}，<b>包括 NaN 与 ±∞</b>：这一路的 β 来自
   * {@code sys_config.rec_beta_cf}，而 {@code Double.parseDouble("NaN")} 是成功的，
   * 一条手抖的配置就会让整批相似度变成 NaN——Jackson 会把 NaN 写成非标准 JSON，
   * 在线读回来时解析失败，表现是「所有详情页都没有邻居」，而离线日志一切正常。
   * 用 {@code beta >= 0 && beta <= 1} 的合取判断，NaN 的两个比较都是 false，天然落到默认值。</p>
   */
  public static double fuse(double cfSim, double contentSim, double beta) {
    double safeBeta = beta >= 0d && beta <= 1d ? beta : RecConstants.BETA_CF;
    return safeBeta * cfSim + (1d - safeBeta) * contentSim;
  }

  /** 把一行相似度裁成 Top-K：负分与低于下限的邻居不进 JSON，同分按 itemId 升序。 */
  public static List<Neighbor> topK(Map<Long, Double> sims, int topK) {
    if (sims == null || sims.isEmpty() || topK <= 0) {
      return List.of();
    }
    List<Map.Entry<Long, Double>> entries = new ArrayList<>(sims.entrySet());
    entries.removeIf(e -> e.getValue() == null || e.getValue() < RecConstants.MIN_NEIGHBOR_SCORE);
    entries.sort(Comparator.<Map.Entry<Long, Double>>comparingDouble(Map.Entry::getValue).reversed()
        .thenComparing(Map.Entry::getKey));
    List<Neighbor> neighbors = new ArrayList<>();
    for (int i = 0; i < Math.min(topK, entries.size()); i++) {
      neighbors.add(new Neighbor(entries.get(i).getKey(), round6(entries.get(i).getValue())));
    }
    return neighbors;
  }

  /** {@code recommend_result.score} 是 DECIMAL(8,6)，邻居 JSON 同口径：写超过 6 位小数只是自找舍入差。 */
  public static double round6(double value) {
    return Math.round(value * 1_000_000d) / 1_000_000d;
  }
}
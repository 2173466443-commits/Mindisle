package com.mindisle.recommend;

import java.util.ArrayList;
import java.util.List;

/**
 * 重排（任务 T7.6 · 手册 §10.2 7.6 · 需求 FR5.7）。
 *
 * <p>三件事按顺序做，且都在纯内存里做，因为在线阶段它跑在 200ms SLA 内：</p>
 * <ol>
 *   <li><b>话题打散</b>：同一个话题连续出现不超过 {@link RecConstants#TOPIC_SPREAD_CAP} 条。
 *       社区里「失眠」这类话题一旦连屏，低落用户读到的是「整个社区都在讨论失眠」，
 *       这会放大情绪而不是缓解——打散不是美观问题，是情绪安全问题。</li>
 *   <li><b>探索位</b>：每 {@link RecConstants#EXPLORE_EVERY} 个位置插一条 explore 通道的内容，
 *       保证覆盖率（NFR 里的 Coverage ≥ 0.35 靠它，纯按分数排会把长尾永远压在屏幕下方）。</li>
 *   <li><b>不留空洞</b>：找不到满足打散约束的候选时，宁可用高分那条顶上，也不给出一屏比一屏短的结果。
 *       「推荐流比预期短」在页面上表现为「社区今天没什么新帖」，这是会被用户误读成事实的假信号。</li>
 * </ol>
 *
 * <p>曝光去重不在这里做：它要查 {@code user_action(expose)} 的近 7 天记录，属数据访问，
 * 放在离线 Service 的召回阶段，纯函数不碰库。</p>
 */
public final class ReRank {

  /** 一条待排候选：分数、通道、话题键（null = 无话题，不参与打散）、是否探索位候选。 */
  public record Candidate(long itemId, double score, String channel, String topicKey,
      boolean explore) {
  }

  private ReRank() {
  }

  public static List<Candidate> rerank(List<Candidate> ranked, int size) {
    return rerank(ranked, size, RecConstants.TOPIC_SPREAD_CAP);
  }

  /**
   * 同一套重排逻辑，但打散上限由调用方给出（任务 T7.6 / FR8.6）。
   *
   * <p>{@code rec.diversity_topic_max} 是 sys_config 里的热更项，手册 §8.2.4 的多样性实验要能
   * 只改这一个数复跑一遍，而不是改常量重新编译 —— 所以 {@code spreadCap} 必须是参数，
   * 两参重载只是给「按默认值跑」的调用方和单测留的入口。</p>
   */
  public static List<Candidate> rerank(List<Candidate> ranked, int size, int spreadCap) {
    if (ranked == null || ranked.isEmpty() || size <= 0) {
      return List.of();
    }
    int cap = Math.max(1, spreadCap);
    List<Candidate> pool = new ArrayList<>(ranked);
    List<Candidate> out = new ArrayList<>(Math.min(size, pool.size()));
    int slot = 0;
    while (out.size() < size && !pool.isEmpty()) {
      boolean wantExplore = slot % RecConstants.EXPLORE_EVERY == RecConstants.EXPLORE_EVERY - 1
          && !out.isEmpty();
      int pick = choose(pool, out, wantExplore, cap);
      if (pick < 0 && wantExplore) {
        // 探索位这一格没有不冲突的候选：退化成「照常按分数取」，但槽位照样消耗一次，
        // 于是下一屏的探索位不会往前挤，比例仍然稳定在 1/5。
        pick = choose(pool, out, false, cap);
      }
      if (pick < 0) {
        break;
      }
      out.add(pool.remove(pick));
      slot++;
    }
    return out;
  }

  /**
   * 在池子里找第一条「放进来不违反打散」的候选；{@code wantExplore} 时只认探索位候选。
   *
   * @return 命中下标；池子里一条都不满足时返回 -1（由调用方决定退让还是收工）
   */
  private static int choose(List<Candidate> pool, List<Candidate> placed, boolean wantExplore,
      int spreadCap) {
    for (int i = 0; i < pool.size(); i++) {
      Candidate candidate = pool.get(i);
      if (wantExplore && !candidate.explore()) {
        continue;
      }
      if (!violatesSpread(placed, candidate.topicKey(), spreadCap)) {
        return i;
      }
    }
    return -1;
  }

  /** 用默认打散上限判同一话题是否已经连排够数（保留两参入口给单测与旧调用方）。 */
  public static boolean violatesSpread(List<Candidate> placed, String topicKey) {
    return violatesSpread(placed, topicKey, RecConstants.TOPIC_SPREAD_CAP);
  }

  /**
   * 只看尾巴上 {@code spreadCap} 条：同话题在这段窗口里已经占满 cap 个就拦下，
   * 也就是「最多允许连续 cap 条」—— cap=2 时是第三条才被打散。
   *
   * <p>这里修掉过一个 off-by-one：窗口取 cap−1 且判据 {@code same >= cap − 1}，实际效果是
   * 「最多连续 1 条同话题」，比需求 FR5.7 严了一倍，话题单一的新帖会整屏进不了缓存。
   * 边界由 ReRankTest 钉住（第二条放行、第三条拦下）。</p>
   */
  public static boolean violatesSpread(List<Candidate> placed, String topicKey, int spreadCap) {
    if (placed == null || placed.isEmpty() || topicKey == null || topicKey.isBlank()) {
      return false;
    }
    int cap = Math.max(1, spreadCap);
    int from = Math.max(0, placed.size() - cap);
    int same = 0;
    for (int i = from; i < placed.size(); i++) {
      if (topicKey.equals(placed.get(i).topicKey())) {
        same++;
      }
    }
    return same >= cap;
  }
}
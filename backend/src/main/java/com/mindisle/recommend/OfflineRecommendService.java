package com.mindisle.recommend;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.entity.ItemSimilarity;
import com.mindisle.entity.RecommendResult;
import com.mindisle.entity.SysConfig;
import com.mindisle.mapper.ItemSimilarityMapper;
import com.mindisle.mapper.RecommendMapper;
import com.mindisle.mapper.RecommendResultMapper;
import com.mindisle.mapper.SysConfigMapper;
import com.mindisle.recommend.dto.ActionRow;
import com.mindisle.recommend.dto.ComfortRow;
import com.mindisle.recommend.dto.PostMetaRow;
import com.mindisle.recommend.dto.PostTagRow;
import com.mindisle.track.UserActionCatalog;

/**
 * 离线推荐重算作业（任务 T7.5 / T7.6 / T7.9 / T7.13 · 手册 §10.1 离线侧 · 需求 FR5.1–FR5.10）。
 *
 * <p><b>它是推荐链路唯一的写入者</b>：{@code item_similarity} 与 {@code recommend_result}
 * 两张缓存表都只在这条流水线里被写。在线侧（{@link FeedService} / {@link SimilarPostService}）
 * 只读，因此需求 §6.2 的「P95 ≤ 200ms」才成立 —— 协同过滤的全部代价都压在定时作业里。</p>
 *
 * <p><b>一轮做完的六步（顺序不能换）</b>：</p>
 * <ol>
 *   <li>回填 {@code post.quality_score} 与 {@code topic.hot_score}：它们既是本作业的输入
 *       （融合式里的 w2），也是话题墙与广场的排序键。先算它们，后面每一步读到的才是本轮的值，
 *       而不是上一轮遗留的旧值。</li>
 *   <li>读 30 天窗口内的行为，建「用户 × 帖子」隐式分矩阵（{@link ImplicitScorer}）。</li>
 *   <li>算 ItemCF 相似度，与内容相似度按 β 融合，截断 Top-K 写 {@code item_similarity}。</li>
 *   <li>逐用户召回：UserCF + ItemCF + 内容 + 热度 + 情绪 + 探索六路（{@link ColdStart}）。</li>
 *   <li>融合打分、危机过滤、打散重排（{@link ReRank}），按 (scene, mode) 先删后插写
 *       {@code recommend_result}，position 从 0 连续。</li>
 *   <li>打印各通道占比与耗时（需求 D7：日志里必须能看出「这一批是不是真算了」）。</li>
 * </ol>
 *
 * <p><b>为什么参数从 sys_config 读、读不到退回 {@link RecConstants}</b>：需求 §12 规范 1
 * 「阈值不写死」要求 {@code rec.*} 可热更（FR8.6），但消融实验要在单测里跑纯函数，
 * 单测不连库。所以这里是「配置优先、常量兜底」，且兜底值与 {@code 09_seed.sql} 的种子逐字一致，
 * 否则「库里的值」和「代码里的默认值」会给出两种推荐结果，而这两种都自称默认。</p>
 *
 * <p><b>不加事务</b>：一次重算是几百条语句的批处理，包在一个事务里会把 undo log 撑大、
 * 把行锁持有到作业结束。正确性靠「按用户先删后插」保证单用户原子可见，批次之间互不影响 ——
 * 最坏情况是某个用户这一轮没更新，在线侧读到上一批（TTL 到点自动退热度兜底）。</p>
 */
@Service
public class OfflineRecommendService {

  private static final Logger log = LoggerFactory.getLogger(OfflineRecommendService.class);

  /** 相似度批量写的行大小：一次 500 行 ≈ 一条 30KB 的多值 INSERT，不至于顶到 max_allowed_packet。 */
  static final int SIMILARITY_BATCH = 500;

  /** 话题热度的回看窗口（手册 §10.2 7.13 原文「近 7 天」）。 */
  static final int TOPIC_HOT_WINDOW_DAYS = 7;

  /** 邻居 JSON 的每一行结构，字段名就是存进 sim_items 的键名。 */
  record NeighborJson(long item, double score) {
  }

  private final RecommendMapper recommendMapper;
  private final ItemSimilarityMapper similarityMapper;
  private final RecommendResultMapper resultMapper;
  private final SysConfigMapper configMapper;
  private final ObjectMapper json;

  public OfflineRecommendService(RecommendMapper recommendMapper,
      ItemSimilarityMapper similarityMapper, RecommendResultMapper resultMapper,
      SysConfigMapper configMapper, ObjectMapper json) {
    this.recommendMapper = recommendMapper;
    this.similarityMapper = similarityMapper;
    this.resultMapper = resultMapper;
    this.configMapper = configMapper;
    this.json = json;
  }

  /**
   * 一轮重算的结果摘要（同时是 {@code GET /api/admin/rec/status} 与作业日志的数据源）。
   *
   * <p>{@code channelShare} 的 key 是六路通道名、value 是写进缓存的行数。需求 §6.4 的六组对照
   * 就按这一列出指标，所以它必须<b>按通道计数而不是按分数占比</b>：「emotion 通道贡献了多少分」
   * 无法回答「有多少条推荐是被情绪加权供的货」。</p>
   */
  public record Summary(int qualityRows, int topicRows, int similarityRows, int itemsWithNeighbors,
      int users, int resultRows, String mode, long calcMs, Map<String, Integer> channelShare) {

    /** 全零的摘要：作业被配置关掉或前置数据为空时用，绝不返回 null（调用方要打印它）。 */
    public static Summary empty(String reason, long calcMs) {
      return new Summary(0, 0, 0, 0, 0, 0, reason, calcMs, Map.of());
    }
  }

  /** 从 sys_config 读出来的一轮参数。读不到就用 RecConstants，字段语义见各常量注释。 */
  static final class Knobs {
    double beta;
    int topKNeighbor;
    int topKUserNeighbor;
    int exposeDedupDays;
    int topicSpreadCap;
    boolean emotionEnabled;
    int candidatePoolCap;
    int cacheRowsPerUser;
    /** 手册 §10.2 7.10 规模保护阈值；&lt;=0 表示这道保护被关掉。 */
    int itemScaleCap;
  }

  /**
   * 读 rec.* 参数（FR8.6 热更口）。
   *
   * <p>每一次解析失败都只影响<b>那一个参数</b>并退回常量，同时打 WARN：库里被人手写成
   * {@code abc} 时，作业不该整批停摆（停摆的代价是所有人的推荐都过期），
   * 但也不能静默按默认值跑（那样「我明明把 β 调成了 0.5」在日志里查无实据）。</p>
   */
  Knobs readKnobs() {
    Knobs knobs = new Knobs();
    knobs.beta = decimal("rec.emotion_beta", RecConstants.BETA_CF);
    knobs.topKNeighbor = (int) number("rec.topk_neighbor", RecConstants.TOP_K_NEIGHBOR);
    knobs.topKUserNeighbor = RecConstants.TOP_K_USER_NEIGHBOR;
    knobs.exposeDedupDays = (int) number("rec.expose_dedup_days", RecConstants.EXPOSE_DEDUP_DAYS);
    knobs.topicSpreadCap = (int) number("rec.diversity_topic_max", RecConstants.TOPIC_SPREAD_CAP);
    knobs.emotionEnabled = boolConfig("rec.emotion_boost.enabled", true);
    knobs.candidatePoolCap = RecConstants.CANDIDATE_POOL_CAP;
    knobs.cacheRowsPerUser = RecConstants.FEED_MAX_SIZE;
    knobs.itemScaleCap = (int) number("rec.scale_item_cap", RecConstants.ITEM_SCALE_CAP);
    return knobs;
  }

  private double number(String key, double fallback) {
    SysConfig row = configMapper.findByKey(key);
    if (row == null || row.getCfgValue() == null || row.getCfgValue().isBlank()) {
      return fallback;
    }
    try {
      return Double.parseDouble(row.getCfgValue().trim());
    } catch (NumberFormatException e) {
      log.warn("sys_config {}={} 解析失败，本轮退回默认值 {}", key, row.getCfgValue(), fallback);
      return fallback;
    }
  }

  private double decimal(String key, double fallback) {
    return number(key, fallback);
  }

  private boolean boolConfig(String key, boolean fallback) {
    SysConfig row = configMapper.findByKey(key);
    if (row == null || row.getCfgValue() == null) {
      return fallback;
    }
    String value = row.getCfgValue().trim();
    if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
      return true;
    }
    if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
      return false;
    }
    log.warn("sys_config {}={} 不是 bool，本轮退回默认值 {}", key, value, fallback);
    return fallback;
  }

  /**
   * 重算全部缓存（任务入口，{@code RecommendJob} 与 {@code POST /api/admin/rec/rebuild} 共用）。
   *
   * @param now 统一时间源。由调用方传入而不是本方法自取，是为了让单测能把「30 天窗口」钉死，
   *            也让日志里的 calc_at 与在线侧 TTL 判的是同一个时刻。
   */
  public Summary rebuildAll(LocalDateTime now) {
    return rebuildAll(now, 0);
  }

  /**
   * 同上，但可限制本轮最多处理多少个活跃用户（配置键
   * {@code mindisle.schedule.rec-rebuild-user-limit}，0 = 不限）。
   *
   * <p>它只改「这一轮处理多少人」，不改任何打分口径，所以需求 §6.4 的六组对照不受影响。
   * 存在的理由是压测：灌完模拟行为之后，一轮全量重算的耗时要能被压进可预期的区间，
   * 而不是靠临时改代码 —— 那种改完忘记回滚的限流，最后会变成「线上只给前 50 个人做推荐」。</p>
   */
  public Summary rebuildAll(LocalDateTime now, int userLimit) {
    long started = System.currentTimeMillis();
    Knobs knobs = readKnobs();

    int qualityRows = recommendMapper.backfillQualityScores();
    int topicRows = recommendMapper.recomputeTopicHotScores(
        now.minusDays(TOPIC_HOT_WINDOW_DAYS));

    Map<Long, Map<Long, Double>> userItemScores = buildUserItemScores(
        now.minusDays(RecConstants.ACTION_WINDOW_DAYS), now);
    if (userItemScores.isEmpty()) {
      log.info("推荐重算：窗口内没有任何帖子维度的行为，本轮跳过（社区还没人读过内容）");
      return Summary.empty("no-actions", System.currentTimeMillis() - started);
    }

    Map<Long, Set<String>> itemTopics = buildItemTopics();
    List<PostMetaRow> pool = recommendMapper.listRecommendablePosts(knobs.candidatePoolCap, now);
    if (pool.isEmpty()) {
      log.info("推荐重算：候选池为空（没有可公开发布的帖子），本轮跳过");
      return Summary.empty("no-candidates", System.currentTimeMillis() - started);
    }
    Map<Long, PostMetaRow> metaById = new HashMap<>();
    for (PostMetaRow row : pool) {
      metaById.put(row.getId(), row);
    }

    int similarityRows = rebuildSimilarity(userItemScores, itemTopics, metaById.keySet(), knobs, now);
    Map<Long, Map<Long, Double>> fusedSim = similarityFromCache(metaById.keySet());

    RecomputeContext ctx = new RecomputeContext(userItemScores, itemTopics, metaById, fusedSim,
        knobs, now);
    // 情绪通道（创新点②）的输入：帖子 → 评论安抚效价。少了这一行 comfortByItem 恒为空表，
    // emotion 通道占比 0%，而「混合 vs 混合−情绪项」两组消融会跑出逐字相同的指标 —— 那种
    // 「实验做了、结论是假的」比没做更糟，所以把它的接线点在这里写明。
    ctx.comfortByItem = buildComfortMap();
    // 规模保护（手册 §10.2 7.10）：这条判据必须<b>整轮只算一次并落进日志</b>。逐用户各判各的，
    // 就会出现「同一批缓存里一半人有 usercf 通道、一半人没有」，而通道占比统计正好是按条数累加的
    // —— 那时需求 §6.4「按通道出指标」的分母就不是一次决策的结果了。
    ctx.itemUniverse = countDistinctItems(userItemScores);
    if (ctx.userCfDisabled()) {
      log.info("推荐重算：窗口内物品规模{} 条 > 阈值{}（sys_config rec.scale_item_cap，代码默认 {}），"
          + "本轮只算 ItemCF、跳过 UserCF（手册 §10.2 7.10 规模保护）",
          ctx.itemUniverse, knobs.itemScaleCap, RecConstants.ITEM_SCALE_CAP);
    }
    List<Long> users = new ArrayList<>(
        recommendMapper.listActiveUserIds(now.minusDays(RecConstants.ACTION_WINDOW_DAYS)));
    if (userLimit > 0 && users.size() > userLimit) {
      log.info("推荐重算：窗口内活跃用户{}人，按 rec-rebuild-user-limit 截断为前{}人（仅影响本轮覆盖人数）",
          users.size(), userLimit);
      users = users.subList(0, userLimit);
    }
    int resultRows = 0;
    Map<String, Integer> share = new LinkedHashMap<>();
    for (Long userId : users) {
      if (userId == null) {
        continue;
      }
      UserBatch batch = scoreOneUser(userId, ctx);
      resultRows += writeUserBatch(userId, batch, now);
      for (Map.Entry<String, Integer> e : batch.channelShare.entrySet()) {
        share.merge(e.getKey(), e.getValue(), Integer::sum);
      }
    }

    long calcMs = System.currentTimeMillis() - started;
    int withNeighbors = (int) similarityMapper.countWithNeighbors();
    log.info("推荐重算完成：质量分{}行 话题热度{}行 相似度{}行(有邻居{}) 用户{}人 结果{}行 耗时{}ms 通道占比{}",
        qualityRows, topicRows, similarityRows, withNeighbors, users.size(), resultRows, calcMs,
        share);
    return new Summary(qualityRows, topicRows, similarityRows, withNeighbors, users.size(),
        resultRows, knobs.emotionEnabled ? "emotion-on" : "emotion-off", calcMs, share);
  }

  /**
   * 建「用户 → (帖子 → 隐式分 0–1)」矩阵（任务 T7.1 · 手册 §10.2 7.1）。
   *
   * <p>三步：<b>累加</b>同一个人对同一篇帖子的所有行为（各乘时间衰减）→
   * <b>夹到 [0,10]</b>（负反馈能压到 0，不会压成负数，因为下游要与质量分相加）→
   * <b>归一化到 0–1</b>（{@code s/(s+1)}）。最后截到 {@link ItemCf#USER_VECTOR_CAP} 篇，
   * 给共现对数一个上界。</p>
   *
   * <p>质量分侧的 {@code quality_score} 是 0–1，隐式分也归一到 0–1，融合式里
   * {@code w1·cf + w2·quality} 两项才量纲一致。库里那三个量纲（0–10 / 0–1 / 天数）
   * 混着相加是本类最容易写错的地方，所以归一化只在<b>这一处</b>做，别处一律假定「矩阵里就是 0–1」。</p>
   */
  Map<Long, Map<Long, Double>> buildUserItemScores(LocalDateTime since, LocalDateTime now) {
    List<ActionRow> rows = recommendMapper.listPostActions(since);
    Map<Long, Map<Long, Double>> raw = new HashMap<>();
    for (ActionRow row : rows) {
      if (row.getUserId() == null || row.getPostId() == null) {
        continue;
      }
      long daysAgo = ImplicitScorer.daysBetween(row.getCreatedAt() == null ? since
          : row.getCreatedAt(), now);
      raw.computeIfAbsent(row.getUserId(), k -> new HashMap<>()).merge(row.getPostId(),
          ImplicitScorer.contribution(row.getWeight(), daysAgo), Double::sum);
    }
    Map<Long, Map<Long, Double>> matrix = new HashMap<>();
    for (Map.Entry<Long, Map<Long, Double>> user : raw.entrySet()) {
      Map<Long, Double> normalized = new HashMap<>();
      for (Map.Entry<Long, Double> item : user.getValue().entrySet()) {
        double score = ImplicitScorer.normalize(ImplicitScorer.clamp(item.getValue()));
        if (score > 0d) {
          normalized.put(item.getKey(), ItemCf.round6(score));
        }
      }
      if (!normalized.isEmpty()) {
        matrix.put(user.getKey(), ItemCf.truncate(normalized, ItemCf.USER_VECTOR_CAP));
      }
    }
    return matrix;
  }

  /**
   * 建「帖子 → 已过审话题名集合」（内容相似度与推荐理由共用一份，任务 T7.3 / T7.7）。
   *
   * <p>用 {@link LinkedHashSet} 而不是 {@code HashSet}：话题名会被当理由里的
   * {@code #xxx#} 展示，也会被当成打散键（{@code topicKey}）。「取第一个话题」这个操作
   * 在 HashSet 上取决于哈希顺序，同一批数据重算两次会给出两种理由文案，
   * 而 SQL 侧已经 {@code ORDER BY t.name}，这里保持同样的确定性。</p>
   */
  Map<Long, Set<String>> buildItemTopics() {
    Map<Long, Set<String>> itemTopics = new HashMap<>();
    for (PostTagRow row : recommendMapper.listApprovedPostTags()) {
      if (row.getPostId() == null || row.getTopicName() == null || row.getTopicName().isBlank()) {
        continue;
      }
      itemTopics.computeIfAbsent(row.getPostId(), k -> new LinkedHashSet<>())
          .add(row.getTopicName().trim());
    }
    return itemTopics;
  }

  /**
   * 算并写 {@code item_similarity}（任务 T7.2 / T7.3 / T7.4 · 手册 §10.2 7.2 7.3）。
   *
   * <p>写库范围 = 「有共现历史的物品」∪「候选池内的物品」。前者保证 {@code /api/posts/{id}/similar}
   * 对一篇质量分挤不进前 300 的老帖也查得到邻居；后者保证候选池里每一帖都能被 ItemCF 召回用到。</p>
   *
   * <p>β 融合的两种退化情形都保留：某一对只有共现没有同话题（β·cf 生效），
   * 只有同话题没有共现（{@code (1−β)·content} 生效）。把后一种筛掉，冷启动的新帖就永远进不了
   * 相似位 —— 而那正是内容相似度这一路存在的唯一理由。</p>
   */
  int rebuildSimilarity(Map<Long, Map<Long, Double>> userItemScores,
      Map<Long, Set<String>> itemTopics, Set<Long> poolIds, Knobs knobs, LocalDateTime now) {
    Map<Long, Map<Long, Double>> cfSims = ItemCf.cfSimilarities(userItemScores);
    Set<Long> scope = new LinkedHashSet<>(cfSims.keySet());
    scope.addAll(poolIds);
    List<ItemSimilarity> rows = new ArrayList<>();
    LocalDateTime calcAt = now;
    int written = 0;
    for (Long itemId : scope) {
      if (itemId == null) {
        continue;
      }
      Map<Long, Double> cf = cfSims.getOrDefault(itemId, Map.of());
      Set<Long> candidates = new LinkedHashSet<>(cf.keySet());
      if (poolIds.contains(itemId)) {
        candidates.addAll(poolIds);
      }
      Set<String> myTopics = itemTopics.getOrDefault(itemId, Set.of());
      Map<Long, Double> fused = new HashMap<>();
      for (Long other : candidates) {
        if (other == null || other.equals(itemId)) {
          continue;
        }
        double content = ItemCf.contentCosine(myTopics,
            itemTopics.getOrDefault(other, Set.of()));
        double sim = ItemCf.fuse(cf.getOrDefault(other, 0d), content, knobs.beta);
        if (sim > 0d) {
          fused.put(other, sim);
        }
      }
      List<ItemCf.Neighbor> neighbors = ItemCf.topK(fused, knobs.topKNeighbor);
      rows.add(toSimilarityRow(itemId, neighbors, calcAt));
      if (rows.size() >= SIMILARITY_BATCH) {
        similarityMapper.batchUpsert(rows);
        written += rows.size();
        rows = new ArrayList<>();
      }
    }
    if (!rows.isEmpty()) {
      similarityMapper.batchUpsert(rows);
      written += rows.size();
    }
    // 返回「本轮一共写了多少行」，不是「最后一批有多少行」：分批写之后 rows 已被换成空表，
    // 直接 return rows.size() 会让 Summary.similarityRows 恒等于尾批那几行，
    // 而答辩时唯一能证明「ItemCF 真的算完了」的数字就是它。
    return written;
  }

  /** 把一帖的邻居列表装成缓存行。JSON 与 neighbor_cnt 在同一处生成，保证两者恒等（实体注释的硬约束）。 */
  private ItemSimilarity toSimilarityRow(Long itemId, List<ItemCf.Neighbor> neighbors,
      LocalDateTime calcAt) {
    List<NeighborJson> packed = new ArrayList<>(neighbors.size());
    for (ItemCf.Neighbor neighbor : neighbors) {
      packed.add(new NeighborJson(neighbor.itemId(), neighbor.score()));
    }
    String simItems;
    try {
      simItems = json.writeValueAsString(packed);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // 邻居是纯数字结构，理论上不可能序列化失败；真失败了也不能让整批作业停摆，
      // 写成空数组 = 这一帖没有邻居，在线侧自然退回话题兜底。
      log.warn("相似度 JSON 序列化失败 item={}，按无邻居写入：{}", itemId, e.toString());
      simItems = "[]";
    }
    ItemSimilarity row = new ItemSimilarity();
    row.setItemId(itemId);
    row.setSimItems(simItems);
    row.setNeighborCnt(packed.size());
    row.setCalcAt(calcAt);
    return row;
  }

  /**
   * 把刚写进缓存的邻居读回内存，供逐用户召回用（任务 T7.9 的 itemcf 路）。
   *
   * <p>为什么不直接复用 {@code rebuildSimilarity} 里算好的那份：那个循环已经把临时 map 丢了，
   * 而本方法只有几百个物品要读，一次按主键批查比把中间状态留成字段更干净 ——
   * 一个 Service 的字段一旦装下「上一轮的中间结果」，重入和并发就再也没法推理。</p>
   */
  Map<Long, Map<Long, Double>> similarityFromCache(Set<Long> poolIds) {
    Map<Long, Map<Long, Double>> out = new HashMap<>();
    for (Long itemId : poolIds) {
      ItemSimilarity row = similarityMapper.selectByItem(itemId);
      if (row == null || row.getNeighborCnt() == null || row.getNeighborCnt() <= 0) {
        continue;
      }
      Map<Long, Double> neighbors = new LinkedHashMap<>();
      for (NeighborJson neighbor : parseNeighbors(row.getSimItems())) {
        neighbors.put(neighbor.item(), neighbor.score());
      }
      if (!neighbors.isEmpty()) {
        out.put(itemId, neighbors);
      }
    }
    return out;
  }

  /** 解析邻居 JSON：脏数据兜成空表，不让一行坏数据把整批候选拖走。 */
  List<NeighborJson> parseNeighbors(String simItems) {
    if (simItems == null || simItems.isBlank()) {
      return List.of();
    }
    try {
      NeighborJson[] parsed = json.readValue(simItems, NeighborJson[].class);
      return parsed == null ? List.of() : List.of(parsed);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      log.warn("item_similarity.sim_items 解析失败，按无邻居处理：{}", e.toString());
      return List.of();
    }
  }

  /** 帖子侧的安抚效价表（创新点②的输入之一，全表只读一次，逐用户共享）。 */
  Map<Long, Integer> buildComfortMap() {
    Map<Long, Integer> comfort = new HashMap<>();
    for (ComfortRow row : recommendMapper.listCommentReactions()) {
      if (row.getPostId() == null) {
        continue;
      }
      Integer value = EmotionBoost.comfortFromReactions(
          row.getPositiveCnt() == null ? 0 : row.getPositiveCnt(),
          row.getTotalCnt() == null ? 0 : row.getTotalCnt());
      if (value != null) {
        comfort.put(row.getPostId(), value);
      }
    }
    return comfort;
  }

  /**
   * 一轮重算的共享上下文。
   *
   * <p>它存在是为了让 {@code scoreOneUser} 变成一个「只依赖入参 + 只查自己那一行」的函数：
   * 矩阵、话题表、候选池、邻居、参数、时刻全部一次性传进去。少了这一层，逐用户循环里
   * 就会出现「某一步顺手又查了一次全表」——几百个用户乘以一次全表扫描，是把 O(n) 写成 O(n²)
   * 最常见的方式。</p>
   */
  static final class RecomputeContext {

    final Map<Long, Map<Long, Double>> userItemScores;
    final Map<Long, Set<String>> itemTopics;
    final Map<Long, PostMetaRow> metaById;
    final Map<Long, Map<Long, Double>> neighborsByItem;
    final Knobs knobs;
    final LocalDateTime now;

    /** 帖子 → 安抚效价（−5..+5）。缺键 = 没被评论过 = 情绪通道对它沉默。 */
    Map<Long, Integer> comfortByItem = Map.of();

    /** 窗口内出现过的<b>不同</b>帖子数 —— 手册 §10.2 7.10 规模保护唯一的判据。 */
    int itemUniverse;

    /**
     * 本轮是否跳过 UserCF（手册 §10.2 7.10「物品 >5 000 时只算 ItemCF」）。
     *
     * <p>判据用「窗口内有行为的不同帖子数」而不是 {@code metaById.size()}：候选池被
     * {@link RecConstants#CANDIDATE_POOL_CAP} 截到 300，拿它当物品规模会永远不触发。
     * 也不用候选池条数以外的库表 count —— 那要多打一条 SQL，而矩阵本来就已经把
     * 「这一轮真的存在过哪些物品」算清楚了。</p>
     */
    boolean userCfDisabled() {
      return knobs.itemScaleCap > 0 && itemUniverse > knobs.itemScaleCap;
    }

    RecomputeContext(Map<Long, Map<Long, Double>> userItemScores, Map<Long, Set<String>> itemTopics,
        Map<Long, PostMetaRow> metaById, Map<Long, Map<Long, Double>> neighborsByItem, Knobs knobs,
        LocalDateTime now) {
      this.userItemScores = userItemScores;
      this.itemTopics = itemTopics;
      this.metaById = metaById;
      this.neighborsByItem = neighborsByItem;
      this.knobs = knobs;
      this.now = now;
    }

    /** 行为窗口起点：矩阵、活跃名单、负反馈回溯共用同一个窗口，别各算各的。 */
    LocalDateTime since() {
      return now.minusDays(RecConstants.ACTION_WINDOW_DAYS);
    }
  }

  /** 一个用户的重算产物（还没落库）。 */
  static final class UserBatch {

    final long userId;
    final String mode;
    final List<ReRank.Candidate> candidates;
    final Map<Long, String> reasonByItem;
    final Map<String, Integer> channelShare;

    UserBatch(long userId, String mode, List<ReRank.Candidate> candidates,
        Map<Long, String> reasonByItem, Map<String, Integer> channelShare) {
      this.userId = userId;
      this.mode = mode;
      this.candidates = candidates;
      this.reasonByItem = reasonByItem;
      this.channelShare = channelShare;
    }
  }

  /**
   * 给一个用户算一屏（任务 T7.6 / T7.9 / T7.10 · 手册 §10.1 融合式）。
   *
   * <p>六路召回先各自出一份证据，再进<b>同一个</b>融合式：
   * {@code w1·CF + w2·质量分 + w3·新鲜度 + w4·情绪匹配 − w5·重复曝光 − w6·同类负反馈}。
   * 「各通道各排一次序再拼接」是做不出对照实验的 —— 需求 §6.4 要的是「把某一项权重置零重跑」，
   * 那就必须所有通道都产同一量纲的分数。</p>
   *
   * <p>CF 这一项取 {@code max(itemCF, userCF)} 而不是相加，并把<b>取胜的那一路</b>记进
   * {@code recall_channel}：相加会让两路同时命中的帖子虚高，而通道归属也会变成「都说自己是主力」。
   * 六组对照要按通道出指标，诚实比分数好看重要。</p>
   *
   * <p>热度组（{@link RecMode#MODE_HOT}）把 CF 与情绪两项一起置零，只留质量分 + 新鲜度 ——
   * 这就是需求 §6.4「混合 vs 纯热门」的对照组，也是 A/B 里那三成用户实际看到的排序。</p>
   */
  UserBatch scoreOneUser(long userId, RecomputeContext ctx) {
    String mode = RecMode.forUser(userId);
    boolean hotMode = RecMode.isHot(mode);
    long interactionCnt = recommendMapper.countPostActionsOfUser(userId, ctx.since());
    Set<String> userTags = userTagSignals(userId);
    // FR5.5 的分支结论：这个用户这一轮走的是 CF、标签召回还是纯热度。它是需求 D7 的取证字段，
    // 也是 §6.4「新用户冷启动组」唯一的分组依据 —— 不落日志就等于没实现。
    String coldChannel = ColdStart.firstChannel(interactionCnt, !userTags.isEmpty());
    Map<Long, Double> myScores = ctx.userItemScores.getOrDefault(userId, Map.of());
    Set<Long> exposed = new HashSet<>(recommendMapper.listExposedPostIds(userId,
        ctx.now.minusDays(ctx.knobs.exposeDedupDays)));
    Set<Long> negative = new HashSet<>(recommendMapper.listNegativePostIds(userId, ctx.since()));
    Set<String> negativeTopics = new HashSet<>();
    for (Long disliked : negative) {
      negativeTopics.addAll(ctx.itemTopics.getOrDefault(disliked, Set.of()));
    }
    Integer mood = recommendMapper.latestMoodValence(userId);

    Map<Long, Double> itemCfScores = hotMode ? Map.of() : recallByItemCf(myScores, ctx);
    Map<Long, Double> userCfScores = hotMode ? Map.of()
        : recallByUserCf(userId, myScores, interactionCnt, ctx);
    Set<Long> exploreIds = ColdStart.allowExplore(interactionCnt)
        ? pickExploreIds(ctx, myScores, exposed, ColdStart.exploreSlots(ctx.knobs.cacheRowsPerUser))
        : Set.of();

    List<ReRank.Candidate> ranked = new ArrayList<>();
    Map<Long, String> reasonByItem = new LinkedHashMap<>();
    Map<String, Integer> channelShare = new LinkedHashMap<>();
    for (PostMetaRow meta : ctx.metaById.values()) {
      Long itemId = meta.getId();
      if (itemId == null) {
        continue;
      }
      // BR11 三条硬过滤：自己的帖、危机等级 L2/L3、被本人负反馈过的那条。
      if (userId == (meta.getUserId() == null ? -1L : meta.getUserId())
          || isCrisis(meta.getRiskLevel()) || negative.contains(itemId)) {
        continue;
      }
      double cfItem = itemCfScores.getOrDefault(itemId, 0d);
      double cfUser = userCfScores.getOrDefault(itemId, 0d);
      double cf = Math.max(cfItem, cfUser);
      double quality = meta.getQualityScore() == null ? 0d : meta.getQualityScore().doubleValue();
      double freshness = ImplicitScorer.freshness(meta.getPublishedAt() == null
          ? (long) RecConstants.FRESH_HALFLIFE_DAYS * 10L
          : ImplicitScorer.daysBetween(meta.getPublishedAt(), ctx.now));
      Integer comfort = ctx.comfortByItem.get(itemId);
      double emotion = ctx.knobs.emotionEnabled ? EmotionBoost.boost(mood, comfort) : 0d;
      boolean topicMatch = matchesUserTag(ctx.itemTopics.get(itemId), userTags);
      double repeat = exposed.contains(itemId) ? 1d : 0d;
      double negativePenalty = sharesNegativeTopic(ctx.itemTopics.get(itemId), negativeTopics) ? 1d : 0d;

      double raw = RecConstants.W_CF * cf + RecConstants.W_QUALITY * quality
          + RecConstants.W_FRESH * freshness + RecConstants.W_EMOTION * emotion
          - RecConstants.W_REPEAT * repeat - RecConstants.W_NEGATIVE * negativePenalty;
      double score = clamp01(ItemCf.round6(raw));

      boolean explore = exploreIds.contains(itemId);
      String channel = chooseChannel(explore, hotMode, cfItem, cfUser, emotion, topicMatch);
      ranked.add(new ReRank.Candidate(itemId, score, channel, firstTopic(itemId, ctx), explore));
      reasonByItem.put(itemId, buildReason(channel, itemId, meta, ctx));
    }
    ranked.sort(Comparator.<ReRank.Candidate>comparingDouble(ReRank.Candidate::score).reversed()
        .thenComparingLong(ReRank.Candidate::itemId));

    List<ReRank.Candidate> placed = ReRank.rerank(ranked, ctx.knobs.cacheRowsPerUser,
        ctx.knobs.topicSpreadCap);
    // 通道占比统计的是「真正写进缓存的那 ≤50 条」，而不是进了重排的全部候选：候选池一定被热度铺满，
    // 按候选计数会让 hot 占比虚高，而需求 D7 要求日志能看出「这一批到底推出去的是什么」。
    for (ReRank.Candidate candidate : placed) {
      channelShare.merge(candidate.channel(), 1, Integer::sum);
    }
    log.debug("推荐重算：用户{} mode={} 交互{}次 冷启动首通道={} 情绪={} 候选{}条 入缓存{}条 曝光去重{}条",
        userId, mode, interactionCnt, coldChannel, mood, ranked.size(), placed.size(), exposed.size());
    return new UserBatch(userId, mode, placed, reasonByItem, channelShare);
  }

  /**
   * ItemCF 召回：{@code score(i) = Σ_j sim(i,j)·r(u,j) / Σ_j |sim(i,j)|}，j 跑本人的读过的帖。
   *
   * <p>用的是刚写进 {@code item_similarity} 的那份<b>融合后</b>邻居，而不是重算一遍纯 CF：
   * 相似位（{@code /api/posts/{id}/similar}）和信息流必须给同一个「像不像」的答案，
   * 否则用户会在详情页看到「这篇和 A 很像」，回到推荐流里 A 却排在他看不见的位置。</p>
   */
  Map<Long, Double> recallByItemCf(Map<Long, Double> myScores, RecomputeContext ctx) {
    Map<Long, double[]> acc = new HashMap<>();
    for (Map.Entry<Long, Double> seen : myScores.entrySet()) {
      Map<Long, Double> neighbors = ctx.neighborsByItem.get(seen.getKey());
      if (neighbors == null) {
        continue;
      }
      for (Map.Entry<Long, Double> neighbor : neighbors.entrySet()) {
        long candidate = neighbor.getKey();
        if (myScores.containsKey(candidate) || !ctx.metaById.containsKey(candidate)) {
          continue;
        }
        double[] bucket = acc.computeIfAbsent(candidate, k -> new double[2]);
        bucket[0] += neighbor.getValue() * seen.getValue();
        bucket[1] += Math.abs(neighbor.getValue());
      }
    }
    Map<Long, Double> out = new HashMap<>();
    for (Map.Entry<Long, double[]> e : acc.entrySet()) {
      if (e.getValue()[1] > 0d) {
        out.put(e.getKey(), clamp01(e.getValue()[0] / e.getValue()[1]));
      }
    }
    return out;
  }

  /**
   * UserCF 召回（任务 T7.2 的人—人那一路）。
   *
   * <p>邻居门槛 {@code MIN_CO_USERS} 与热门惩罚 {@code HOT_PENALTY_ALPHA} 都传常量而不是让
   * {@link UserCf} 自己决定：这两个数是手册 §8.2.4 消融表里的变量，实验要能只改一个。</p>
   */
  Map<Long, Double> recallByUserCf(long userId, Map<Long, Double> myScores, long interactionCnt,
      RecomputeContext ctx) {
    if (ctx.userCfDisabled()) {
      // 规模保护先于冷启动门槛：这一支连「找邻居」的那次全表扫描都不该发生，
      // 否则「已经降级了」和「降级但照样扫了一遍」在日志里长得一模一样。
      return Map.of();
    }
    if (!ColdStart.cfEligible(interactionCnt)) {
      // 窗口内行为数不到 FR5.5 的门槛（默认 20 次）时，任何「口味相近」的结论都是噪声。
      // 门槛只用这一个数：别拿矩阵条数乘系数换算，矩阵是按用户归一化过的，与真实交互数不同量纲。
      return Map.of();
    }
    List<UserCf.Neighbor> neighbors = UserCf.neighbors(ctx.userItemScores, userId,
        ctx.knobs.topKUserNeighbor, RecConstants.MIN_CO_USERS, RecConstants.HOT_PENALTY_ALPHA);
    Map<Long, Double> scored = UserCf.scoreCandidates(myScores, neighbors, ctx.userItemScores);
    Map<Long, Double> out = new HashMap<>();
    for (Map.Entry<Long, Double> e : scored.entrySet()) {
      if (ctx.metaById.containsKey(e.getKey())) {
        out.put(e.getKey(), clamp01(e.getValue()));
      }
    }
    return out;
  }

  /**
   * 探索位候选（需求 §6.4 多样性、NFR 的 Coverage ≥ 0.35）。
   *
   * <p>取「质量分最低 + 这个人还没读过/没被曝光过」的那批：探索的意义就是把长尾递出去，
   * 从已经排在前面的高热帖里挑几条打不上 explore 标记是自欺。</p>
   *
   * <p>{@code need <= 0} 或池子里凑不出这么多条时返回实际数量 —— 不硬凑，
   * 因为把读过的塞进探索位会让用户读到「换口味：这篇我刚看过」。
   * 按 id 升序收尾，同质量分时结果可复现。</p>
   */
  Set<Long> pickExploreIds(RecomputeContext ctx, Map<Long, Double> myScores, Set<Long> exposed,
      int need) {
    if (need <= 0) {
      return Set.of();
    }
    List<PostMetaRow> tail = new ArrayList<>();
    for (PostMetaRow meta : ctx.metaById.values()) {
      if (meta.getId() == null || myScores.containsKey(meta.getId())
          || exposed.contains(meta.getId())) {
        continue;
      }
      tail.add(meta);
    }
    tail.sort(Comparator.<PostMetaRow>comparingDouble(
            row -> row.getQualityScore() == null ? 0d : row.getQualityScore().doubleValue())
        .thenComparingLong(PostMetaRow::getId));
    Set<Long> picked = new LinkedHashSet<>();
    for (PostMetaRow row : tail) {
      if (picked.size() >= need) {
        break;
      }
      picked.add(row.getId());
    }
    return picked;
  }

  /**
   * 通道归属：探索 > 热度组 > UserCF/ItemCF > 情绪 > 内容 > 热度。
   *
   * <p>顺序就是「这句话对用户的解释力」的顺序：能给出「和你读过的很像」的，不该被记成
   * 「社区今天讨论最多的」；而冷启动用户根本没有 CF 证据，只能诚实说明是热度或关注话题。</p>
   */
  static String chooseChannel(boolean explore, boolean hotMode, double cfItem, double cfUser,
      double emotion, boolean topicMatch) {
    if (explore) {
      return ColdStart.CHANNEL_EXPLORE;
    }
    if (hotMode) {
      return ColdStart.CHANNEL_HOT;
    }
    if (cfUser > 0d && cfUser >= cfItem) {
      return ColdStart.CHANNEL_USERCF;
    }
    if (cfItem > 0d) {
      return ColdStart.CHANNEL_ITEMCF;
    }
    if (emotion > 0d) {
      return ColdStart.CHANNEL_EMOTION;
    }
    return topicMatch ? ColdStart.CHANNEL_CONTENT : ColdStart.CHANNEL_HOT;
  }

  /** 写缓存（任务 T7.6 / T7.10）：按 (scene='feed', mode) 先删旧批再按 position 连续插新批。 */
  int writeUserBatch(long userId, UserBatch batch, LocalDateTime now) {
    if (batch.candidates.isEmpty()) {
      // 空批次不删旧行：删了在线侧就读不到任何东西，页面从「上一批的推荐」变成「今天没人发帖」。
      log.info("推荐重算：用户{} 本轮无候选，保留上一批缓存（mode={}）", userId, batch.mode);
      return 0;
    }
    List<RecommendResult> rows = new ArrayList<>(batch.candidates.size());
    int position = 0;
    for (ReRank.Candidate candidate : batch.candidates) {
      if (!ColdStart.isKnownChannel(candidate.channel())) {
        // ENUM 写错 MySQL 不报错，只会静默存成默认值，通道统计从此永久失真。宁可跳过这一条。
        log.warn("推荐重算：用户{} 候选{} 通道未知（{}），跳过", userId, candidate.itemId(),
            candidate.channel());
        continue;
      }
      RecommendResult row = new RecommendResult();
      row.setUserId(userId);
      row.setScene(UserActionCatalog.SCENE_FEED);
      row.setItemId(candidate.itemId());
      row.setScore(BigDecimal.valueOf(candidate.score()));
      row.setRecallChannel(candidate.channel());
      row.setReason(batch.reasonByItem.get(candidate.itemId()));
      row.setMode(batch.mode);
      row.setPosition(position++);
      row.setCalcAt(now);
      rows.add(row);
    }
    if (rows.isEmpty()) {
      return 0;
    }
    resultMapper.deleteBatch(userId, UserActionCatalog.SCENE_FEED, batch.mode);
    return resultMapper.batchInsert(rows);
  }

  // ---------------------------------------------------------------- 小工具

  private String buildReason(String channel, long itemId, PostMetaRow meta, RecomputeContext ctx) {
    String topicName = firstTopic(itemId, ctx);
    // coReaderCnt 传 0：共同阅读人数不落缓存（要落就得把 sim_items 从二元组扩成三元组，
    // 而在线侧和实验都不需要它），ReasonBuilder 自己有一条不编数字的 itemcf 文案。
    return ReasonBuilder.forStore(channel, topicName, meta.getEmotionPrimary(), 0);
  }

  /** 一帖的打散键与理由用同一个话题：两个地方各取一个「首个话题」，会出现理由与打散说的不是同一个词。 */
  static String firstTopic(long itemId, RecomputeContext ctx) {
    Set<String> topics = ctx.itemTopics.get(itemId);
    if (topics == null || topics.isEmpty()) {
      return null;
    }
    return topics.iterator().next();
  }

  /** 该帖是否命中用户的关注话题或注册时自选的兴趣标签（内容通道的唯一判据）。 */
  static boolean matchesUserTag(Set<String> postTopics, Set<String> userTags) {
    if (postTopics == null || postTopics.isEmpty() || userTags.isEmpty()) {
      return false;
    }
    for (String topic : postTopics) {
      if (userTags.contains(topic)) {
        return true;
      }
    }
    return false;
  }

  /** 是否与用户点过「不感兴趣」的帖子同话题 —— FR1.7「减少此类推荐」里的「此类」。 */
  static boolean sharesNegativeTopic(Set<String> postTopics, Set<String> negativeTopics) {
    if (postTopics == null || postTopics.isEmpty() || negativeTopics.isEmpty()) {
      return false;
    }
    for (String topic : postTopics) {
      if (negativeTopics.contains(topic)) {
        return true;
      }
    }
    return false;
  }

  /** L2 / L3 是危机干预级别（手册 §10.6 第 2 条），离线在线同一判据。 */
  static boolean isCrisis(String riskLevel) {
    return "L2".equals(riskLevel) || "L3".equals(riskLevel);
  }

  /**
   * 窗口内出现过的不同帖子数（手册 §10.2 7.10 规模保护的输入）。
   *
   * <p>空向量条目不计：{@link #buildUserItemScores} 会把「所有分都被负反馈压到下限」的用户
   * 留成空 Map，那些人不构成任何物品证据，把他们算进物品规模会让阈值提前触发。</p>
   */
  static int countDistinctItems(Map<Long, Map<Long, Double>> userItemScores) {
    Set<Long> items = new HashSet<>();
    for (Map<Long, Double> vector : userItemScores.values()) {
      if (vector != null && !vector.isEmpty()) {
        items.addAll(vector.keySet());
      }
    }
    return items.size();
  }

  /** 融合式输出必须在 0–1：{@code recommend_result.score} 是 DECIMAL(8,6)，写 9.7 会被 MySQL 静默夹成 99.999999。 */
  static double clamp01(double value) {
    return Math.min(1d, Math.max(0d, value));
  }

  /**
   * 用户侧的内容信号 = 关注话题 ∪ 注册时自选的兴趣标签（任务 T7.5 · 需求 FR5.5）。
   *
   * <p>两个来源必须合起来看：注册流程强制新用户选 3 个兴趣话题，但他们一条「关注话题」都还没点，
   * 只读 {@code topic_follow}（现库 0 行）会让标签召回对所有新用户退化成热度榜。</p>
   *
   * <p>{@code user_profile.interest_tags} 是 JSON 列，历史脏数据（空串、非数组）一律按
   * 「没有标签」处理而不是让整轮重算抛异常：几百个用户里一个坏 JSON 就让全站推荐停摆，
   * 是代价最不划算的失败。但必须打 WARN，否则「我明明勾过兴趣话题」查无实据。</p>
   */
  Set<String> userTagSignals(long userId) {
    Set<String> tags = new LinkedHashSet<>();
    for (String followed : recommendMapper.listFollowedTopicNames(userId)) {
      if (followed != null && !followed.isBlank()) {
        tags.add(followed.trim());
      }
    }
    String rawTags = recommendMapper.listInterestTags(userId);
    if (rawTags != null && !rawTags.isBlank()) {
      try {
        String[] parsed = json.readValue(rawTags, String[].class);
        if (parsed != null) {
          for (String tag : parsed) {
            if (tag != null && !tag.isBlank()) {
              tags.add(tag.trim());
            }
          }
        }
      } catch (Exception e) {
        log.warn("推荐重算：用户{} 的 interest_tags 不是合法 JSON（{}），按无标签处理", userId,
            e.getMessage());
      }
    }
    return tags;
  }
}
package com.mindisle.recommend;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.cache.CacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.entity.ItemSimilarity;
import com.mindisle.mapper.ItemSimilarityMapper;
import com.mindisle.mapper.RecommendMapper;
import com.mindisle.post.PostQueryService;
import com.mindisle.post.dto.PostListItem;
import com.mindisle.recommend.dto.PostMetaRow;

/**
 * 详情页「看了又看」（任务 T7.16 · 手册 §10.6 · 需求 FR5.6、U4）。
 *
 * <p><b>它和推荐流读的是同一份离线产物</b>：邻居来自 {@code item_similarity}（由
 * {@link OfflineRecommendService} 每 30 分钟写一次），所以这里一行相似度都不算。
 * 手册 §7.5 把「离线算 Top-K、在线只查表」当成 P95 ≤ 200ms 的前提，相似位是这条前提
 * 最容易被破坏的地方——一个"看起来只是多查一张表"的接口最容易被塞进在线计算。</p>
 *
 * <p><b>三条召回层，逐层降级且诚实标注通道</b>：① ItemCF 邻居；② 邻居不够（新帖、
 * {@code neighbor_cnt=0}）时回退同话题的高分帖，通道记 {@code content}；③ 两层都空
 * （这一帖连话题都没有）才用质量分榜补位，通道记 {@code hot}。手册 §10.6 第 1 条要求
 * 「不要返回空列表」，但补位的代价是必须让人看出这不是协同过滤的结果——所以
 * {@code recall_channel} 与理由文案都跟着换，而不是把热度榜伪装成"相似"。</p>
 *
 * <p><b>缓存里只放离线产物，安全判据每次都现查</b>（这是对 §10.6 第 4 条「缓存
 * {@code sim:post:{id}} TTL 30min」的一处有意收窄）：缓存的键是 {@code 邻居id+分数}，
 * 也就是"这一帖和谁像"；而"这一帖现在能不能被这个人看到"——危机等级 L2/L3、作者是否注销、
 * 帖子是否下架、用户是否点过不感兴趣——<b>每次请求都现判</b>。如果把过滤后的结果整份缓存 30 分钟，
 * 一条帖子在缓存窗口内被判定为 L2 自伤内容时，相似位还会继续把它推到别人屏幕上最多半小时。
 * 需求 §8.3 禁止的二次暴露正是这种"数据还在缓存里、判据已经变了"的窗口。多付的代价是
 * 每次请求一条按主键的 {@code IN} 查询，换来危机拦截的实时性，这笔账在心理社区里不需要犹豫。</p>
 *
 * <p><b>失效用批次版本号，而不是前缀批量 DEL</b>：键形如 {@code sim:post:{批次}:…}，批次号取
 * {@code item_similarity.MAX(calc_at)}（本身再缓存 60 秒）。{@code CacheService} 刻意没有
 * 「按前缀删」的能力——Redis 侧那要么 {@code KEYS}（阻塞）要么 {@code SCAN}（给在线路径加一次往返），
 * 两者都不该出现在 200ms SLA 的读接口上。版本键的效果是重算完成后最多 60 秒全体换键，
 * 旧键由 TTL 自行退场，比手册原写的「整批 DEL」只慢一分钟，且没有 SCAN。偏差记在这里，
 * 手册 §15 T7.16 的实测回写同段。</p>
 */
@Service
public class SimilarPostService {

  private static final Logger log = LoggerFactory.getLogger(SimilarPostService.class);

  /** §10.6 第 4 条：候选邻居缓存 30 分钟。 */
  static final Duration CANDIDATE_TTL = Duration.ofMinutes(30);

  /** 批次号自己的缓存时长：它决定「重算完成后多久全量换新键」，见类注释。 */
  static final Duration VERSION_TTL = Duration.ofSeconds(60);

  static final String KEY_PREFIX = "sim:post:";
  static final String VERSION_KEY = "sim:batch-version";

  /** 离线作业从没成功过时的批次占位（此时缓存里的行不会有任何危害：库里本来就没邻居）。 */
  static final String VERSION_NONE = "none";

  /**
   * 单帖最多带多少条候选进入组装。相似位最多出 {@link RecConstants#SIMILAR_MAX_SIZE} 条，
   * 40 是给「危机拦截 + 负反馈 + 可见性」三层筛完留的余量——不是给算法的召回上限，
   * 邻居表本身已经按 Top-K（{@code rec.topk_neighbor}，默认 200）截过一刀。
   */
  static final int CANDIDATE_CAP = 40;

  /** 同话题兜底最多看源帖的前几个话题：一篇帖挂五个话题时，第五个对"像不像"几乎没有解释力。 */
  static final int TOPIC_FALLBACK_CAP = 3;

  /**
   * 一条相似候选。
   *
   * @param itemId  候选帖 id
   * @param score   融合后的相似度；兜底层（content / hot）没有相似度可言，恒为 0
   * @param channel 召回通道，取值必须落在 {@code recommend_result.recall_channel} 的六值 ENUM 里
   */
  public record Candidate(long itemId, double score, String channel) {
  }

  private final RecommendMapper recommendMapper;
  private final ItemSimilarityMapper similarityMapper;
  private final PostQueryService postQueryService;
  private final CacheService cache;
  private final ObjectMapper json;

  public SimilarPostService(RecommendMapper recommendMapper,
      ItemSimilarityMapper similarityMapper, PostQueryService postQueryService,
      CacheService cache, ObjectMapper json) {
    this.recommendMapper = recommendMapper;
    this.similarityMapper = similarityMapper;
    this.postQueryService = postQueryService;
    this.cache = cache;
    this.json = json;
  }

  /** 出参上限：不传或传非正数按 6，最多 12（{@link RecConstants#SIMILAR_MAX_SIZE}）。 */
  public static int clampSize(Integer requested) {
    if (requested == null || requested <= 0) {
      return RecConstants.SIMILAR_DEFAULT_SIZE;
    }
    return Math.min(requested, RecConstants.SIMILAR_MAX_SIZE);
  }

  /**
   * 取相似帖。
   *
   * <p>返回的卡片形状与推荐流完全一致（{@link FeedService.FeedItem}：帖子 + 理由 + 通道 + 分），
   * 前端因此可以复用同一张 {@code PostCard}，不必为详情页写第二套卡片。</p>
   *
   * @throws BizException 源帖对当前用户不可见时抛 {@code POST_NOT_FOUND}(30001/404)——
   *   与 {@code PostQueryService#detail} 同一个错误码：「不存在」与「你不能看」在这里必须同形，
   *   否则这个端点就成了一枚探测别人私密帖的探针（详情接口 404、相似接口 200 空列表，
   *   差别本身泄露了答案）
   */
  public List<FeedService.FeedItem> similar(long viewerId, long postId, Integer size,
      LocalDateTime now) {
    int want = clampSize(size);
    // 源帖先走一遍与读侧同一份可见性判据，同时顺手拿到它的话题（兜底召回要用，省一次查询）。
    List<PostListItem> self = postQueryService.feedCards(viewerId, List.of(postId), now);
    if (self.isEmpty()) {
      throw new BizException(ErrorCode.POST_NOT_FOUND);
    }
    PostListItem source = self.get(0);

    LocalDateTime since = now.minusDays(RecConstants.EXPOSE_DEDUP_DAYS);
    Set<Long> negative = new HashSet<>(recommendMapper.listNegativePostIds(viewerId, since));
    Set<Long> exposed = new HashSet<>(recommendMapper.listExposedPostIds(viewerId, since));

    Set<Long> taken = new LinkedHashSet<>();
    taken.add(postId);
    List<Candidate> ranked = new ArrayList<>();
    for (Candidate candidate : neighborCandidates(postId, now)) {
      if (negative.contains(candidate.itemId()) || !taken.add(candidate.itemId())) {
        continue;
      }
      ranked.add(candidate);
    }
    if (ranked.size() < want) {
      for (Candidate candidate : topicCandidates(source, postId, now)) {
        if (negative.contains(candidate.itemId()) || !taken.add(candidate.itemId())) {
          continue;
        }
        ranked.add(candidate);
      }
    }

    List<Candidate> eligible = filterPool(ranked, viewerId, now);
    // 已读降权＝排到最后，不是删掉（§10.6 第 2 条）。看过的内容恰恰是"像"的最强证据，
    // 直接删会让相似位在老用户眼里越用越空；把它压到尾巴上，既让位给新内容又不装死。
    List<Candidate> unread = new ArrayList<>();
    List<Candidate> readTail = new ArrayList<>();
    for (Candidate candidate : eligible) {
      if (exposed.contains(candidate.itemId())) {
        readTail.add(candidate);
      } else {
        unread.add(candidate);
      }
    }
    List<Candidate> ordered = new ArrayList<>(unread);
    ordered.addAll(readTail);

    List<FeedService.FeedItem> items = assemble(viewerId, source, ordered, now);
    if (items.size() < want) {
      Set<Long> exclude = new LinkedHashSet<>(taken);
      for (FeedService.FeedItem item : items) {
        exclude.add(item.id());
      }
      List<Candidate> hot = filterPool(hotCandidates(exclude, now), viewerId, now);
      hot.removeIf(candidate -> exposed.contains(candidate.itemId()));
      items = new ArrayList<>(items);
      items.addAll(assemble(viewerId, source, hot, now));
      if (items.size() > want) {
        items = new ArrayList<>(items.subList(0, want));
      }
      log.info("相似位：帖{} 通道补齐后 {} 条（邻居+话题不足 {} 条，已用质量分榜补位）", postId,
          items.size(), want);
    } else if (items.size() > want) {
      items = new ArrayList<>(items.subList(0, want));
    }
    return items;
  }

  /**
   * 这一帖的邻居 id 列表，供「不感兴趣要把相似内容一起压掉」用（任务 T7.7 / 需求 FR5.7 D6）。
   *
   * <p>推荐流里点了「不感兴趣」之后，用户下一屏看到的应该是"和它像的那批也一起没了"，
   * 而不是"隔一轮重算才消失"。重算最坏情况下要等 30 分钟，而 D6 的验收是当场点当场没。</p>
   */
  public List<Long> neighborIds(long postId, LocalDateTime now) {
    return neighborCandidates(postId, now).stream().map(Candidate::itemId).toList();
  }

  /**
   * ItemCF 邻居（缓存 → {@code item_similarity}）。
   *
   * <p>空邻居也照样写缓存（值为空串）：一帖没有邻居是常态（库里 778 帖，共现对本来稀疏），
   * 不缓存空结果等于每次访问新帖详情都穿到 DB 再判一次"确实没有"。</p>
   */
  List<Candidate> neighborCandidates(long postId, LocalDateTime now) {
    String key = KEY_PREFIX + batchVersion() + ':' + postId;
    String cached = cache.get(key, String.class);
    if (cached != null) {
      return parseCandidates(cached);
    }
    List<Candidate> loaded = loadNeighbors(postId);
    cache.set(key, serialize(loaded), CANDIDATE_TTL);
    return loaded;
  }

  /** 读一行邻居 JSON 并截到 {@link #CANDIDATE_CAP}。脏数据与缺行都返回空表，不是异常。 */
  private List<Candidate> loadNeighbors(long postId) {
    ItemSimilarity row = similarityMapper.selectByItem(postId);
    if (row == null || row.getSimItems() == null || row.getSimItems().isBlank()) {
      return List.of();
    }
    List<Candidate> out = new ArrayList<>();
    try {
      JsonNode root = json.readTree(row.getSimItems());
      if (!root.isArray()) {
        return List.of();
      }
      for (JsonNode node : root) {
        JsonNode item = node.get("item");
        if (item == null || !item.canConvertToLong()) {
          continue;
        }
        JsonNode score = node.get("score");
        out.add(new Candidate(item.asLong(), score == null ? 0d : score.asDouble(),
            ColdStart.CHANNEL_ITEMCF));
        if (out.size() >= CANDIDATE_CAP) {
          break;
        }
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // 与离线侧同一口径：一行的坏 JSON 不能把详情页拖垮，退回话题兜底即可。
      log.warn("相似位：帖{} 的 sim_items 解析失败，按无邻居处理：{}", postId, e.toString());
      return List.of();
    }
    return out;
  }

  /** 话题兜底：源帖的前几个话题下，质量分最高的已发布公开帖（SQL 里已排掉源帖与 L2/L3）。 */
  List<Candidate> topicCandidates(PostListItem source, long postId, LocalDateTime now) {
    List<String> topics = source.topics();
    if (topics == null || topics.isEmpty()) {
      return List.of();
    }
    List<Candidate> out = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    for (int ti = 0; ti < topics.size() && ti < TOPIC_FALLBACK_CAP; ti++) {
      String topic = topics.get(ti);
      if (topic == null || topic.isBlank() || out.size() >= CANDIDATE_CAP) {
        continue;
      }
      for (Long id : recommendMapper.listPostsByTopicName(topic, postId, now, CANDIDATE_CAP)) {
        if (id == null || !seen.add(id)) {
          continue;
        }
        out.add(new Candidate(id, 0d, ColdStart.CHANNEL_CONTENT));
        if (out.size() >= CANDIDATE_CAP) {
          break;
        }
      }
    }
    return out;
  }

  /** 质量分榜补位（第三层）：只在没有邻居也没有可复用的同话题帖时才会被用到。 */
  List<Candidate> hotCandidates(Set<Long> exclude, LocalDateTime now) {
    List<Candidate> out = new ArrayList<>();
    for (PostMetaRow meta : recommendMapper.listRecommendablePosts(CANDIDATE_CAP, now)) {
      if (meta.getId() == null || exclude.contains(meta.getId())) {
        continue;
      }
      out.add(new Candidate(meta.getId(), 0d, ColdStart.CHANNEL_HOT));
      if (out.size() >= CANDIDATE_CAP) {
        break;
      }
    }
    return out;
  }

  /**
   * 池内过滤：一次按主键的 {@code IN} 查询，把「未发布 / 非公开 / 作者注销封禁 / 树洞已到期 /
   * 危机 L2L3 / 是本人发的」六条判据当场判掉。前五条与候选池 SQL 同一份，最后一条在 Java 侧判。
   */
  List<Candidate> filterPool(List<Candidate> ranked, long viewerId, LocalDateTime now) {
    if (ranked.isEmpty()) {
      return List.of();
    }
    List<Long> ids = ranked.stream().map(Candidate::itemId).toList();
    Map<Long, PostMetaRow> pool = new HashMap<>();
    for (PostMetaRow meta : recommendMapper.listRecommendableByIds(ids, now)) {
      if (meta.getId() != null) {
        pool.put(meta.getId(), meta);
      }
    }
    List<Candidate> out = new ArrayList<>(ranked.size());
    int droppedCrisis = 0;
    for (Candidate candidate : ranked) {
      PostMetaRow meta = pool.get(candidate.itemId());
      if (meta == null) {
        continue;
      }
      if (OfflineRecommendService.isCrisis(meta.getRiskLevel())) {
        droppedCrisis++;
        continue;
      }
      if (meta.getUserId() != null && meta.getUserId() == viewerId) {
        continue;
      }
      out.add(candidate);
    }
    if (droppedCrisis > 0) {
      log.info("相似位：拦截 {} 条 L2/L3 危机帖（需求 §8.3 二次暴露禁止），每次都现判不进缓存",
          droppedCrisis);
    }
    return out;
  }

  /**
   * 组装卡片。顺序由入参决定（{@code feedCards} 保持入参次序），被可见性判据筛掉的就跳过。
   */
  private List<FeedService.FeedItem> assemble(long viewerId, PostListItem source,
      List<Candidate> ordered, LocalDateTime now) {
    if (ordered.isEmpty()) {
      return List.of();
    }
    Map<Long, Candidate> byId = new LinkedHashMap<>();
    for (Candidate candidate : ordered) {
      byId.put(candidate.itemId(), candidate);
    }
    List<FeedService.FeedItem> out = new ArrayList<>();
    for (PostListItem card : postQueryService.feedCards(viewerId,
        new ArrayList<>(byId.keySet()), now)) {
      Candidate candidate = byId.get(card.id());
      if (candidate == null || card.id().equals(source.id())) {
        continue;
      }
      out.add(new FeedService.FeedItem(card, reasonFor(source, card, candidate),
          candidate.channel(),
          candidate.score() > 0d ? BigDecimal.valueOf(ItemCf.round6(candidate.score())) : null));
    }
    return out;
  }

  /**
   * 相似位的理由文案单独维护（手册 §10.6 第 3 条）。
   *
   * <p>不复用 {@link ReasonBuilder}：那套文案是"因为你…"（第一人称、对当前用户的心智模型负责），
   * 相似位说的是"这篇和这篇像"（第三人称、对内容负责）。混用会出现一句理由在页面上重复六遍，
   * 而六遍恰好是手册点名要避免的形态。这里带上两帖共享的话题名，让同一屏里的六条各不相同。</p>
   */
  static String reasonFor(PostListItem source, PostListItem card, Candidate candidate) {
    String shared = firstSharedTopic(source.topics(), card.topics());
    String reason = switch (candidate.channel()) {
      case ColdStart.CHANNEL_ITEMCF -> shared == null
          ? "看过这篇的屿民也看过它"
          : "和这篇一样，看过它的人在 #" + shared + " 里也点过";
      case ColdStart.CHANNEL_CONTENT -> "同属 #" + (shared == null ? "屿民推荐" : shared)
          + "，读完这篇的人常接着看";
      case ColdStart.CHANNEL_HOT -> "共读记录还太少，先给你社区里被读完最多的一篇";
      default -> "看过这篇的屿民也看过它";
    };
    return ReasonBuilder.cut(reason, RecConstants.REASON_MAX);
  }

  private static String firstSharedTopic(List<String> a, List<String> b) {
    if (a == null || b == null) {
      return null;
    }
    for (String topic : a) {
      if (topic != null && b.contains(topic)) {
        return topic;
      }
    }
    return null;
  }

  /** 批次号 = 最近一次成功写入的时刻（毫秒），取不到时用占位串并缓存 60 秒。 */
  private String batchVersion() {
    String cached = cache.get(VERSION_KEY, String.class);
    if (cached != null) {
      return cached;
    }
    LocalDateTime calcAt = similarityMapper.lastBatchCalcAt();
    String version = calcAt == null ? VERSION_NONE
        : String.valueOf(calcAt.toInstant(ZoneOffset.UTC).toEpochMilli());
    cache.set(VERSION_KEY, version, VERSION_TTL);
    return version;
  }

  /** 邻居列表的缓存编码：{@code id:score,id:score}。空表编成空串（空也要缓存，见上面注释）。 */
  static String serialize(List<Candidate> candidates) {
    StringBuilder sb = new StringBuilder();
    for (Candidate candidate : candidates) {
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append(candidate.itemId()).append(':').append(candidate.score()).append(':')
          .append(candidate.channel());
    }
    return sb.toString();
  }

  /** 解码。任何一段格式不对就丢掉那一段，不整表作废——缓存值坏了 worst case 是少几条候选。 */
  static List<Candidate> parseCandidates(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    List<Candidate> out = new ArrayList<>();
    for (String part : value.split(",")) {
      String[] cells = part.split(":");
      if (cells.length < 3) {
        continue;
      }
      try {
        out.add(new Candidate(Long.parseLong(cells[0]), Double.parseDouble(cells[1]), cells[2]));
      } catch (NumberFormatException e) {
        log.warn("相似位缓存段解析失败，丢弃该段：{}", part);
      }
    }
    return out;
  }
}
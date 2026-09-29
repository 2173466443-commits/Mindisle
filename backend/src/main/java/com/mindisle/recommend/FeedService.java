package com.mindisle.recommend;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.entity.RecommendResult;
import com.mindisle.mapper.RecommendMapper;
import com.mindisle.mapper.RecommendResultMapper;
import com.mindisle.post.PostQueryService;
import com.mindisle.post.dto.PostListItem;
import com.mindisle.recommend.dto.PostMetaRow;
import com.mindisle.track.UserActionCatalog;
import com.mindisle.track.UserActionRecorder;

/**
 * 在线推荐流（任务 T7.7 / T7.9 / T7.10 · 手册 §10.1「在线只读缓存」· 需求 FR1.7 FR5.1 FR5.7 FR5.8、
 * §6.2 P95 ≤ 200ms）。
 *
 * <p><b>这一层不发一条召回 SQL、不算一次余弦</b>：整条链路只有两次查询 —— 按
 * {@code (user_id, scene, mode, position)} 从 {@code recommend_result} 取一屏 id，
 * 再用这批 id 走 {@code PostQueryService#feedCards} 组装卡片。协同过滤的全部代价都在
 * {@link OfflineRecommendService} 的定时批次里。这就是需求 §6.2 那条 P95 的实现方式：
 * 不是「把算法写得更快」，而是「把它挪到不在请求路径上的地方」。</p>
 *
 * <p><b>三处退回热度兜底</b>（{@link #hotFallback}）：① 缓存批次为空（新用户还没被算过）；
 * ② 批次时间超过 {@link RecConstants#RESULT_TTL_MINUTES}（离线作业挂了或还没跑第二轮）；
 * ③ 缓存里有行、但 {@code feedCards} 按最新可见性判下来一条都不剩（半小时内全被下架）。
 * 三种情况的共同要求是<b>接口不能空手而归</b>：需求 FR5.8 写的是「推荐服务不可用时退回热度榜」，
 * 而用户看到的是「首页一条帖都没有 = 这个社区没人」——那是产品级事故，不是降级。</p>
 *
 * <p><b>分页按 position，不接受 beforeId</b>：缓存里这一批的次序是融合打分的结果，与 id 大小无关。
 * 用游标（「取 id 小于 X 的」）翻第二页会翻出完全无关的东西，甚至一条都没有。
 * 所以本方法只用 {@code page/size}，前端推荐流的翻页参数也是这两个。</p>
 *
 * <p><b>另一个入口是 {@link #dislike}</b>：它是这条读链路上唯一的写路径，因为它要同时改
 * 缓存行、改邻居、记行为三处（见该方法的注释）。相似位（{@link SimilarPostService}）
 * <b>不</b>记曝光：手册 §10.6 的去重窗口只管主 feed，详情页的「看了又看」是用户主动展开的
 * 区块，把它的曝光混进 {@code expose} 采样，等于用被动展示的位次灌满质量分榜的分子。</p>
 */
@Service
public class FeedService {

  private static final Logger log = LoggerFactory.getLogger(FeedService.class);

  private final RecommendResultMapper resultMapper;
  private final RecommendMapper recommendMapper;
  private final PostQueryService postQueryService;
  private final UserActionRecorder recorder;
  private final SimilarPostService similarPostService;

  public FeedService(RecommendResultMapper resultMapper, RecommendMapper recommendMapper,
      PostQueryService postQueryService, UserActionRecorder recorder,
      SimilarPostService similarPostService) {
    this.resultMapper = resultMapper;
    this.recommendMapper = recommendMapper;
    this.postQueryService = postQueryService;
    this.recorder = recorder;
    this.similarPostService = similarPostService;
  }

  /**
   * 「不感兴趣」的结果：压掉了几条本尊、几条邻居。
   *
   * <p>两个数都回给前端（前端只提示「已减少此类内容」），留在这儿是为了 Gate7 的 D6 断言
   * 能写成「点一次之后 recommend_result 里这一帖及其邻居都读不到」，而不是靠肉眼看下一屏。</p>
   */
  public record DismissResult(int removed, int removedSimilar) {
  }

  /**
   * 推荐流的一条卡片 = 广场那套 {@link PostListItem} 全量字段 + 推荐特有的三个字段。
   *
   * <p>不另开精简 DTO：需求 D6 的验收是「推荐位卡片与广场同一形状」，两套契约必然出现
   * 「推荐流里少了头像／匿名标记」这类前端渲染差异。反过来把 reason 塞进 PostListItem
   * 更糟——那条 record 被广场、关注、搜索、话题、详情共用五个入口，为了推荐流给它们都加一位空字段，
   * 等于让所有读接口的契约都为一个特例付钱。</p>
   *
   * <p>{@code id()} 是给 {@link PageResult#ofCursor} 取游标用的，别删。</p>
   */
  public record FeedItem(PostListItem post, String reason, String recallChannel, BigDecimal score) {

    public Long id() {
      return post == null ? null : post.id();
    }
  }

  /**
   * 取一屏推荐。
   *
   * @param viewerId 登录用户（推荐流不做游客态：没有行为矩阵就没有个性化，给游客「像推荐的样子」是骗人）
   * @param page     page/size 分页入参，size 上限沿用 {@link PageQuery#MAX_SIZE}
   * @param now      统一时间源，透传给可见性判据与曝光时刻
   */
  public PageResult<FeedItem> feed(long viewerId, PageQuery page, LocalDateTime now) {
    PageQuery safe = (page == null ? new PageQuery() : page).normalize();
    int size = safe.getSize();
    int offset = (int) Math.min(safe.offset(), (long) Integer.MAX_VALUE);
    String mode = RecMode.forUser(viewerId);

    List<RecommendResult> rows = resultMapper.selectByPosition(viewerId,
        UserActionCatalog.SCENE_FEED, mode, offset, size + 1);
    if (!rows.isEmpty() && isStale(rows.get(0).getCalcAt(), now)) {
      log.warn("推荐流：用户{} mode={} 的缓存批次已过期（calc_at={} 超过{}分钟），本轮退热度兜底",
          viewerId, mode, rows.get(0).getCalcAt(), RecConstants.RESULT_TTL_MINUTES);
      rows = List.of();
    }
    if (rows.isEmpty()) {
      return hotFallback(viewerId, offset, size, now, "cache-empty");
    }

    boolean batchHasMore = rows.size() > size;
    List<RecommendResult> taken = batchHasMore ? rows.subList(0, size) : rows;
    Map<Long, RecommendResult> byId = new LinkedHashMap<>();
    List<Long> orderedIds = new ArrayList<>(taken.size());
    for (RecommendResult row : taken) {
      // 只在「第一次见到这个 item」时登记：LinkedHashMap#put 会用后到的行覆盖前一行，
      // 于是 position 取的是前面那行、reason/recall_channel/score 却来自后面那行 ——
      // 卡片排在 cf 的位上却标着 hot 的通道，D7 的通道占比统计与 D6 的理由当场错位。
      if (row.getItemId() != null && !byId.containsKey(row.getItemId())) {
        byId.put(row.getItemId(), row);
        orderedIds.add(row.getItemId());
      }
    }
    List<PostListItem> cards = postQueryService.feedCards(viewerId, orderedIds, now);
    if (cards.isEmpty()) {
      // 缓存有行但一条都组装不出来 = 这半小时里它们全被下架/销毁/作者注销了。
      // 不报错，退热度榜：用户要的是「有内容可看」，不是「知道我们的缓存旧了」。
      return hotFallback(viewerId, offset, size, now, "all-invisible");
    }
    if (cards.size() < orderedIds.size()) {
      log.debug("推荐流：用户{} 缓存{}条、可见{}条（差额由 read 侧可见性判据过滤，属正常状态）",
          viewerId, orderedIds.size(), cards.size());
    }

    List<FeedItem> items = new ArrayList<>(cards.size());
    List<Long> sentIds = new ArrayList<>(cards.size());
    for (PostListItem card : cards) {
      RecommendResult row = byId.get(card.id());
      items.add(new FeedItem(card, row == null ? null : row.getReason(),
          row == null ? ColdStart.CHANNEL_HOT : row.getRecallChannel(),
          row == null ? null : row.getScore()));
      sentIds.add(card.id());
    }
    recordExposure(viewerId, sentIds, mode, now);

    PageResult<FeedItem> result = PageResult.ofCursor(items, size, FeedItem::id);
    // hasMore 以「缓存批次后面还有 position」为准，而不是「这一屏凑够 size 条没」：
    // 按后者判会让被可见性筛掉几条的批次提前显示「没有更多了」，用户翻到第三屏就以为社区到底了。
    result.setHasMore(batchHasMore);
    return result;
  }

  /**
   * 热度兜底：直接用质量分榜（{@code listRecommendablePosts} 的 ORDER BY quality_score DESC）。
   *
   * <p>这条路径要<b>自己再滤一次危机帖</b>：候选池 SQL 只判状态与可见性，{@code risk_level}
   * 是作为列取出来交给离线作业的 {@link OfflineRecommendService#isCrisis} 判的。兜底路径绕过了
   * 离线作业，如果不去抄这道判据，L2/L3 的自伤帖就会出现在「你可能也想看」的位置上 ——
   * 需求 §8.3 明令禁止的二次暴露，正是从这里漏出去的。</p>
   */
  PageResult<FeedItem> hotFallback(long viewerId, int offset, int size, LocalDateTime now,
      String reason) {
    int fetch = (int) Math.min((long) offset + size + 1L, RecConstants.CANDIDATE_POOL_CAP);
    if (fetch <= offset) {
      log.warn("推荐流：用户{} 偏移{}已超出兜底池上限，返回空页（page 太深，建议前端到底即停）",
          viewerId, offset);
      return PageResult.ofCursor(List.of(), size, FeedItem::id);
    }
    List<PostMetaRow> pool = recommendMapper.listRecommendablePosts(fetch, now);
    Set<Long> picked = new HashSet<>();
    List<Long> orderedIds = new ArrayList<>(size + 1);
    int skipped = 0;
    for (PostMetaRow meta : pool) {
      if (meta.getId() == null || isOwnOrCrisis(meta, viewerId) || !picked.add(meta.getId())) {
        continue;
      }
      if (skipped++ < offset) {
        continue;
      }
      orderedIds.add(meta.getId());
      if (orderedIds.size() > size) {
        break;
      }
    }
    // orderedIds 可能比 size 多一条，那一条是「下一页还有没有」的探针，不是发出去的内容：
    // 只有前 size 条进卡片组装，否则它会连同曝光一起被记账（见下），用户没见过它却被记成已曝光，
    // 于是 7 天去重窗口把它压掉、质量分榜的分子还多算一条。
    List<Long> shownIds = orderedIds.subList(0, Math.min(size, orderedIds.size()));
    List<PostListItem> cards = postQueryService.feedCards(viewerId, shownIds, now);
    List<FeedItem> items = new ArrayList<>(cards.size());
    List<Long> sentIds = new ArrayList<>(cards.size());
    for (PostListItem card : cards) {
      // 兜底通道诚实标注 hot、reason 留空：需求 D6 要的是「有理由就显示理由」，
      // 给热度榜编一句「因为你常看 X」是把对照组污染成实验组，§6.4 的六组指标当场作废。
      items.add(new FeedItem(card, null, ColdStart.CHANNEL_HOT, null));
      sentIds.add(card.id());
    }
    log.info("推荐流退热度兜底：用户{} 原因={} 兜底池{}条 出{}条", viewerId, reason, pool.size(),
        items.size());
    if (!sentIds.isEmpty()) {
      recorder.recordExposure(viewerId, sentIds, UserActionCatalog.SCENE_FEED, now);
    }
    PageResult<FeedItem> result = PageResult.ofCursor(items, size, FeedItem::id);
    result.setHasMore(orderedIds.size() > size);
    return result;
  }

  /**
   * 点「不感兴趣」（任务 T7.7 · 需求 FR1.7 / FR5.7 · Gate7 判据 D6「当场点当场没」）。
   *
   * <p><b>三步，各管一层，缺一不可</b>：</p>
   * <ol>
   *   <li>{@code recommend_result.dismiss} 逻辑删除该用户该场景里这一帖的行 —— 管的是
   *       「本批次不再把它发出去」；</li>
   *   <li>沿 {@code item_similarity} 把这帖的邻居一起压掉 —— 管的是
   *       「下一屏不再出现和它像的那批」。不做的后果很具体：协同过滤给一批相似帖打了
   *       相邻的 position，用户驳回一条，第二屏看到四条换了封面的同一种内容，
   *       而重算最坏要等 30 分钟，D6 的「当场」就没了；</li>
   *   <li>{@code user_action(dislike, weight=-3)} —— 管的是「下一批重算时真的降权」。
   *       前两步只改缓存，改不了矩阵；只有这条行为事实能让离线作业在下一次
   *       {@link OfflineRecommendService} 里把它算没了。</li>
   * </ol>
   *
   * <p><b>顺序不能反</b>：先记行为再删缓存，反过来会在「删完到写库之间」崩掉时留下
   * 「缓存没了但矩阵不记得用户拒绝过它」的状态，重算时它又会回来 —— 那是用户会
   * 明确感知的「我说过不感兴趣，它又出现了」。</p>
   *
   * <p>邻居列表来自 {@link SimilarPostService#neighborIds}，它自带缓存与批次版本，
   * 所以这一步不会在负反馈路径上现算余弦。取不到邻居（冷启动物、还没重算过）时
   * {@code removedSimilar=0}，不报错：驳回本尊这一步已经生效。</p>
   *
   * <p>不校验帖子可见性：用户在推荐流里能点到它，说明它刚被发出去过；
   * 在这里补一次「你不能驳回一条你看不见的内容」只会把一个消歧动作变成一次 404。</p>
   */
  public DismissResult dislike(long viewerId, long postId, LocalDateTime now) {
    recorder.record(viewerId, UserActionCatalog.ACTION_DISLIKE, UserActionCatalog.TARGET_POST,
        postId, UserActionCatalog.SCENE_FEED, now);
    int removed = resultMapper.dismiss(viewerId, UserActionCatalog.SCENE_FEED, postId);

    List<Long> neighbors = new ArrayList<>();
    for (Long neighborId : similarPostService.neighborIds(postId, now)) {
      if (neighborId != null && neighborId != postId) {
        neighbors.add(neighborId);
      }
    }
    int removedSimilar = neighbors.isEmpty() ? 0
        : resultMapper.dismissItems(viewerId, UserActionCatalog.SCENE_FEED, neighbors);
    log.info("负反馈：用户{} 驳回帖{}（缓存删{}条）并压掉邻居{}条（取到{}条候选邻居）",
        viewerId, postId, removed, removedSimilar, neighbors.size());
    return new DismissResult(removed, removedSimilar);
  }

  /** 曝光记账：先写 user_action(expose) 的采样事实，再置缓存行的 is_exposed（两件事互不替代）。 */
  private void recordExposure(long viewerId, List<Long> sentIds, String mode, LocalDateTime now) {
    if (sentIds.isEmpty()) {
      return;
    }
    int sampled = recorder.recordExposure(viewerId, sentIds, UserActionCatalog.SCENE_FEED, now);
    int marked = resultMapper.markExposed(viewerId, UserActionCatalog.SCENE_FEED, mode, sentIds, now);
    log.debug("推荐流曝光：用户{} mode={} 发出{}条 埋点采样{}条 缓存置位{}条", viewerId, mode,
        sentIds.size(), sampled, marked);
  }

  /** 自己的帖与 L2/L3 危机帖都不进兜底（BR11 的两条硬过滤，与离线侧同一判据）。 */
  private boolean isOwnOrCrisis(PostMetaRow meta, long viewerId) {
    return meta.getUserId() != null && meta.getUserId() == viewerId
        || OfflineRecommendService.isCrisis(meta.getRiskLevel());
  }

  /**
   * 批次是否过期。
   *
   * <p>用行的 {@code calc_at} 而不是再查一次 {@code lastCalcAt}：那一列本来就在取回来的行里，
   * 多一次查询只为了给 P95 加 3ms 的往返。</p>
   */
  static boolean isStale(LocalDateTime calcAt, LocalDateTime now) {
    if (calcAt == null || now == null) {
      return true;
    }
    return Duration.between(calcAt, now).toMinutes() > RecConstants.RESULT_TTL_MINUTES;
  }
}
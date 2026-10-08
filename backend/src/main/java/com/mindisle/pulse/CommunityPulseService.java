package com.mindisle.pulse;

import com.mindisle.admin.dto.HotTopicRow;
import com.mindisle.admin.dto.LabelCountRow;
import com.mindisle.cache.CacheService;
import com.mindisle.mapper.CommunityPulseMapper;
import com.mindisle.mapper.DashboardMapper;
import com.mindisle.post.PostQueryService;
import com.mindisle.post.dto.PostListItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 落地页「社区脉搏」装配层（任务 T3.13 结转项 U1 · 需求分析文档 §6 U1 行「品牌介绍、情绪指数氛围、进入社区/立即倾诉」）。
 *
 * <p><b>这是全站唯一一个对匿名访客开放的内容侧读口</b>，所以它的每一条判据都要比别的接口多想一层：
 * ① 只出聚合数与「已经公开」的卡片，绝不因为「游客也想看点什么」就绕过可见性判据；
 * ② 取不到数时如实返回 null + degraded，而不是补 0——「今日 0 篇」和「暂时取不到」是两句不同的话，
 * 拿 0 冒充前者是需求 §12「不许造假数」那条红线在这个页面上的具体形状；
 * ③ 匿名没有 user_id，所以 {@code featured} 走 {@code feedCards(0L, ...)}：
 * 0 不是某个真实用户的 id（自增从 1 起），applyVisible 的「自己的待审帖」那一支永远不成立，
 * 于是游客天然只剩「public 且 PUBLISHED」这一支。这不是巧合，是复用公共判据的直接结果。</p>
 *
 * <p><b>缓存只覆盖聚合块，不覆盖卡片</b>（{@code pulse:agg:{days}}，60 秒）：
 * 聚合数本来就是「氛围」，滞后一分钟没有任何后果；而 {@code featuredIds} 是 60 秒前算的，
 * 这分钟内帖子可能被下架、举报转 TAKEDOWN、树洞到期销毁、作者注销。
 * {@code PostQueryService#feedCards} 的类注释已经把这条纪律写死了——
 * <b>可见性判据必须在读侧生效一次</b>。把卡片整体缓存下来等于绕过这条纪律，
 * 所以这里缓存的是「数字」，卡片每次现算。</p>
 *
 * <p><b>时间窗口在 Java 侧算</b>，SQL 里不写 CURDATE()/NOW()，与 {@code DashboardService} 同一纪律，
 * 理由（单测可钉住当前时刻、避免各处各写各的当天）在那边已经写全，不重复。</p>
 */
@Service
public class CommunityPulseService {

  private static final Logger log = LoggerFactory.getLogger(CommunityPulseService.class);

  /**
   * 默认窗口 <b>30</b> 天，不是大屏那个 7 天。
   *
   * <p>这一处偏离 DashboardService 的口径是想过一轮实测之后才定的：落地页的数来自
   * 「相对今天」的窗口，而演示数据是若干天前种下去的——按 7 天取，2026-10-08 现量只剩
   * 2 篇帖、1 个签到人、0 次点赞，落地页会拍成一张近乎空白的墙（判据没错、代码没错，
   * 是窗口比数据新）。这正是手册里那条「取证时间炸弹」在首页上的形状。30 天既能让
   * 首屏有内容，也还是「这个月社区在发生什么」这句诚实的话；文案里的天数一律由
   * {@code windowDays} 字段回给前端渲染，前端不写死「近 7 日」，两处就不会再各说各话。</p>
   */
  static final int DEFAULT_DAYS = 30;

  /** 上限 90 天：落地页是给游客看氛围的，不是数据大屏，不允许把全表扫一遍当首页。 */
  static final int MAX_DAYS = 90;

  /** 卡墙 12 条：双列瀑布正好六行，再多首屏就要开始滚第二屏，落地页不是广场。 */
  static final int FEATURED_LIMIT = 12;

  /** 热门话题 10 个：chips 一行排开，超过十个就退化成「话题列表」，那是 U6 的职责。 */
  static final int HOT_TOPIC_LIMIT = 10;

  /** 聚合缓存 60 秒。见类注释：只缓数字，不缓卡片。 */
  private static final Duration AGG_TTL = Duration.ofSeconds(60);

  private final CommunityPulseMapper pulseMapper;
  private final DashboardMapper dashboardMapper;
  private final PostQueryService postQueryService;
  private final CacheService cacheService;

  public CommunityPulseService(CommunityPulseMapper pulseMapper, DashboardMapper dashboardMapper,
      PostQueryService postQueryService, CacheService cacheService) {
    this.pulseMapper = pulseMapper;
    this.dashboardMapper = dashboardMapper;
    this.postQueryService = postQueryService;
    this.cacheService = cacheService;
  }

  /**
   * 落地页一次性读数。
   *
   * @param daysRaw 窗口天数，null 或非正数取 {@link #DEFAULT_DAYS}，超过 {@link #MAX_DAYS} 夹紧
   * @param now     当前时刻，由调用方（Controller）给，便于单测注入固定时刻
   */
  public Map<String, Object> landing(Integer daysRaw, LocalDateTime now) {
    int days = clampDays(daysRaw);
    LocalDate fromDate = now.toLocalDate().minusDays(days - 1L);
    LocalDateTime fromTime = fromDate.atStartOfDay();
    LocalDateTime todayTime = now.toLocalDate().atStartOfDay();

    Map<String, Object> result = new LinkedHashMap<>();
    boolean degraded = false;
    boolean cached = false;

    // ---- 聚合块：先读缓存，未命中再打库并回写 ----
    String key = "pulse:agg:" + days;
    Map<String, Object> agg = null;
    try {
      agg = cacheService.get(key, Map.class);
      if (agg != null) {
        cached = true;
      } else {
        agg = loadAgg(fromDate, fromTime, todayTime, days);
        cacheService.set(key, agg, AGG_TTL);
      }
    } catch (RuntimeException e) {
      // 缓存本身坏掉（Redis 不可用会抛 90003）不算数据坏：继续现算，只是没有 cached 标记。
      log.warn("落地页聚合缓存不可用，改为现算 key={}", key);
      degraded = true;
      agg = null;
    }
    if (agg == null) {
      try {
        agg = loadAgg(fromDate, fromTime, todayTime, days);
      } catch (RuntimeException e) {
        // 库不可用：结构照常给全，数字写成 null，前端显式显示「-」。
        // 这里不抛 90002 是刻意的——落地页宁可显示「暂时读不到社区数据」也不能白屏，
        // 它同时是「我们后端活着」的招牌（前端拿 /api/system/info 判连接，判据不受本页影响）。
        log.warn("落地页聚合读数失败，返回降级结构", e);
        degraded = true;
        agg = emptyAgg(days);
      }
    }

    result.putAll(agg);
    result.put("windowDays", days);
    result.put("generatedAt", now.toString());

    // ---- 卡片：每次现算，可见性判据在读侧生效 ----
    List<PostListItem> featured = List.of();
    try {
      List<Long> ids = pulseMapper.featuredIds(fromTime, FEATURED_LIMIT);
      featured = postQueryService.feedCards(0L, ids, now);
    } catch (RuntimeException e) {
      log.warn("落地页精选卡片读取失败，返回空列表", e);
      degraded = true;
    }
    result.put("featured", featured);
    result.put("degraded", degraded);
    result.put("cached", cached);
    return result;
  }

  /** 聚合块的真读数：九个数 + 情绪分布 + 热门话题。 */
  private Map<String, Object> loadAgg(LocalDate fromDate, LocalDateTime fromTime,
      LocalDateTime todayTime, int days) {
    Map<String, Object> snap = pulseMapper.snapshot(fromTime, todayTime, fromDate);
    if (snap == null) {
      snap = Map.of();
    }
    Map<String, Object> week = new LinkedHashMap<>(snap);
    Object todayPostCnt = week.remove("todayPostCnt");

    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, Object> today = new LinkedHashMap<>();
    today.put("postCnt", todayPostCnt);
    out.put("today", today);
    out.put("week", week);
    out.put("emotions", emotions(fromDate));
    out.put("hotTopics", hotTopics());
    return out;
  }

  /** 情绪分布：后端只给原始 key 与计数，中文名与颜色由前端 EmotionPill / theme.css 负责。 */
  private List<Map<String, Object>> emotions(LocalDate fromDate) {
    List<LabelCountRow> rows = pulseMapper.emotionLabels(fromDate);
    long total = 0L;
    for (LabelCountRow row : rows) {
      total += row.getCnt() == null ? 0L : row.getCnt();
    }
    List<Map<String, Object>> list = new ArrayList<>(rows.size());
    for (LabelCountRow row : rows) {
      long cnt = row.getCnt() == null ? 0L : row.getCnt();
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("label", row.getLabel());
      item.put("cnt", cnt);
      // 分母为 0 时给 null 而不是 0：同 positiveRatio 的口径，「没有记录」不等于「占比 0%」。
      item.put("ratio", total == 0L ? null
          : BigDecimal.valueOf(cnt).divide(BigDecimal.valueOf(total), 3, RoundingMode.HALF_UP));
      list.add(item);
    }
    return list;
  }

  /**
   * 热门话题。
   *
   * <p>复用 {@code DashboardMapper#hotTopics}：口径是 APPROVED 话题 + join {@code post_topic}
   * 重算帖数（不读 {@code topic.post_cnt} 那列会漂移的冗余计数），大屏已经验过，这里不另写一句 SQL。
   * 字号只能取 {@code post_cnt}——{@code hot_score} 现量全为 0，见手册 §3.8 ③。</p>
   */
  private List<Map<String, Object>> hotTopics() {
    List<HotTopicRow> rows = dashboardMapper.hotTopics(HOT_TOPIC_LIMIT);
    List<Map<String, Object>> list = new ArrayList<>(rows.size());
    for (HotTopicRow row : rows) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", row.getId());
      item.put("name", row.getName());
      item.put("postCnt", row.getPostCnt());
      list.add(item);
    }
    return list;
  }

  /** 降级结构：字段一个不少，数字一律 null。这样前端不需要写「degraded 时换一套模板」。 */
  private Map<String, Object> emptyAgg(int days) {
    Map<String, Object> week = new LinkedHashMap<>();
    for (String col : new String[] {"postCnt", "holeCnt", "checkinUsers", "checkinCnt",
        "valenceAvg", "positiveRatio", "comfortCnt", "newUserCnt"}) {
      week.put(col, null);
    }
    Map<String, Object> today = new LinkedHashMap<>();
    today.put("postCnt", null);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("today", today);
    out.put("week", week);
    out.put("emotions", List.of());
    out.put("hotTopics", List.of());
    return out;
  }

  /** 夹紧窗口而不是报错：落地页的 days 只影响「近几天」这句文案，为它返 400 没有意义。 */
  private static int clampDays(Integer daysRaw) {
    if (daysRaw == null || daysRaw <= 0) {
      return DEFAULT_DAYS;
    }
    return Math.min(daysRaw, MAX_DAYS);
  }
}
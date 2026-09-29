package com.mindisle.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.cache.CacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.Result;
import com.mindisle.mapper.ItemSimilarityMapper;
import com.mindisle.mapper.RecommendMapper;
import com.mindisle.mapper.RecommendResultMapper;
import com.mindisle.recommend.OfflineRecommendService;
import com.mindisle.recommend.RecConstants;
import com.mindisle.recommend.RecommendJob;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * A9 推荐运维口（任务 T7.15 · 手册 §10.1 / §10.3 · Gate7 判据 D7「离线作业日志可查」）。
 *
 * <p><b>两个端点，都不写 admin_op_log</b>。这不是漏掉：{@code AdminOpLogService.ACTIONS}
 * 是一份严格白名单，加一个动作就要同步改枚举、白名单、前端筛选下拉三处，而这两个操作
 * 都不符合 FR8.4 要求留痕的那一类——「立即重算」是幂等的纯计算，不碰任何一条内容、
 * 不改任何一个用户状态，重算十次和一次的结果都是「最新一批候选」；「查状态」是只读。
 * 真正需要审计的是「谁把某条帖下架了」，那批动作已经在阶段 6 的八个口里各记各的。
 * 这条决策连同理由写在这里，是为了避免下一次评审时有人把它当成漏项补上——
 * 补它的正确做法是同时给 ACTIONS 加白名单项，而不是在这里悄悄插一行日志。</p>
 *
 * <p>可追溯性由三样东西承担，都不需要这张表：应用日志里那一轮 channel share 明细（D7 的验收物）、
 * {@code recommend_result.calc_at} 本身（每行都带批次时刻）、以及 {@link RecommendJob} 留在内存里的
 * {@code lastFailure}。代价是重启会丢内存摘要，所以 {@code /status} 里同时回 DB 侧的读数
 * （{@code cacheRows} / {@code similarityRows} / {@code similarityBatchAt}）——
 * 那三个数重启后依然能证明「作业确实写过库」。</p>
 */
@RestController
@RequestMapping("/api/admin/rec")
@Tag(name = "5 管理端-推荐运维", description = "离线重算手动触发与缓存健康度读数")
public class AdminRecController {

  private final RecommendJob job;
  private final RecommendResultMapper resultMapper;
  private final ItemSimilarityMapper similarityMapper;
  private final RecommendMapper recommendMapper;
  private final CacheService cache;

  public AdminRecController(RecommendJob job, RecommendResultMapper resultMapper,
      ItemSimilarityMapper similarityMapper, RecommendMapper recommendMapper,
      CacheService cache) {
    this.job = job;
    this.resultMapper = resultMapper;
    this.similarityMapper = similarityMapper;
    this.recommendMapper = recommendMapper;
    this.cache = cache;
  }

  /**
   * 手动跑一轮重算（T7.15）。
   *
   * <p><b>同步执行、返回摘要</b>，不做异步任务表：演示与验收等不起「提交后轮询」，
   * 而现在这一库规模（约五百用户 × 三百候选）一轮是几十毫秒到几秒。真要异步，
   * 就得给作业建状态表 + 进度查询，那是另一个任务，不该由一个按钮顺带决定。</p>
   *
   * <p>与定时轮共用 {@link RecommendJob} 里那把重入锁，所以连点不会叠两批交叉写。
   * 被占用时回 10010/429（与限流同一个码：语义就是「稍后再试」），
   * 真失败时回 90004/500 并带上 {@code lastFailure} —— 这里必须让管理员看见失败，
   * 定时轮那条路径反而是只 log 不外抛（理由见 {@code RecommendJob} 类注释第 4 条：
   * 外抛会永久停掉调度）。同一个方法在两个入口上有不同的错误处理，是刻意的。</p>
   */
  @PostMapping("/rebuild")
  @Operation(summary = "立即重算一轮推荐缓存（与定时轮共用重入锁）")
  public Result<OfflineRecommendService.Summary> rebuild(
      @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    if (job.isRunning()) {
      throw new BizException(ErrorCode.RATE_LIMITED, "上一轮重算还没结束，请稍后再试");
    }
    OfflineRecommendService.Summary summary = job.rebuildOnce();
    if (summary == null) {
      throw new BizException(ErrorCode.INTERNAL_ERROR,
          "重算失败：" + (job.lastFailure() == null ? "被并发占用或作业未返回" : job.lastFailure()));
    }
    return Result.ok(summary);
  }

  /**
   * 推荐链路健康度读数。
   *
   * <p>每个字段都对应一个具体的「坏了会怎样」，不是顺手把 mapper 里的数都倒出来：</p>
   * <ul>
   *   <li>{@code cacheRows = 0}：在线侧每个人都走热度兜底，等于个性化整个没生效（D1 会挂）；</li>
   *   <li>{@code similarityRows = 0}：相似位只剩话题兜底与质量分榜，D6 的邻居联压无从验证；</li>
   *   <li>{@code similarityBatchAt} 很旧而 {@code cacheRows} 不涨：作业在跑但写不进库，
   *       十有八九是被 {@code mindisle.schedule.rec-rebuild-user-limit} 卡住了；</li>
   *   <li>{@code cacheMode = caffeine}：多实例部署时各算各的邻居缓存，
   *       手册 §10.6 第 4 条的批次失效只在 redis 下才是全站一致（这条必须在验收前看见，
   *       而不是等上线后发现两台的相似位不一样）。</li>
   * </ul>
   *
   * <p>{@code topic} 参数是给「这个话题为什么热/为什么不热」这类现场排查用的：
   * 话题热度由 {@code recomputeTopicHotScores} 写，是 {@code topic.hot_score} 唯一的来源，
   * 后台的话题墙排序就按它排。查不到该话题时回 null 而不是 0——
   * 「热度是 0」和「没有这个话题」是两个完全不同的结论。</p>
   */
  @GetMapping("/status")
  @Operation(summary = "推荐缓存健康度：批次时刻、行量、通道占比、邻居缓存模式")
  public Result<Map<String, Object>> status(
      @Parameter(description = "可选：抽查某个话题的 hot_score")
      @RequestParam(name = "topic", required = false) String topic,
      @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    OfflineRecommendService.Summary summary = job.lastSummary();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("running", job.isRunning());
    data.put("lastFinishedAt", job.lastFinishedAt());
    data.put("lastFailure", job.lastFailure());
    data.put("lastSummary", summary);
    data.put("lastSummaryChannelShare", summary == null ? Map.of() : summary.channelShare());
    data.put("cacheRows", resultMapper.countActive());
    data.put("similarityRows", similarityMapper.countWithNeighbors());
    data.put("similarityBatchAt", similarityMapper.lastBatchCalcAt());
    data.put("resultTtlMinutes", RecConstants.RESULT_TTL_MINUTES);
    data.put("cacheMode", cache.mode());
    if (topic != null && !topic.isBlank()) {
      BigDecimal hot = recommendMapper.topicHotScore(topic.trim());
      data.put("topicName", topic.trim());
      data.put("topicHotScore", hot);
    }
    data.put("now", LocalDateTime.now());
    return Result.ok(data);
  }
}

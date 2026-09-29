package com.mindisle.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.common.Result;
import com.mindisle.entity.Topic;
import com.mindisle.mapper.TopicMapper;
import com.mindisle.mapper.UserFollowMapper;
import com.mindisle.post.PostQueryService;
import com.mindisle.post.dto.PostListItem;
import com.mindisle.recommend.FeedService;
import com.mindisle.security.AuthUser;
import com.mindisle.track.UserActionCatalog;
import com.mindisle.track.UserActionRecorder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 内容与推荐接口（手册 §5.7 T2.11 · 需求 FR1.6、FR1.7、FR2 推荐）。
 *
 * <p>/api/topics 落在 permitAll 白名单里（游客也能逛话题墙），其余需要登录。
 * 内容域在阶段 2 只开放话题墙这一个真接口：发帖牵涉内容安全链（先审后发 → 危机分流）
 * 与图片上传，未落地前一律返回 90001，而不是造假数据骗过前端联调。
 *
 * <p>2026-09-20 任务 3.3 落地后，<b>POST /api/posts 已迁至 {@link PostController}</b>：
 * 这里的桩必须删掉，否则两条 handler 映射到同一个 POST 路径，
 * Spring MVC 在启动期就报 ambiguous mapping，整个服务起不来（不是运行期才 500）。
 *
 * <p>2026-09-20 任务 3.5 落地后，<b>GET /api/posts 与 GET /api/posts/{id} 两个桩同样删除</b>，
 * 读接口现在在 {@link PostController}。本类只剩游客可访问的话题墙，以及仍属未实现的
 * 推荐流（阶段 4 情绪感知加权，届时再迁走）。
 *
 * <p><b>2026-09-29 任务 T7.9 / T7.10 落地：{@code GET /api/feed/recommend} 不再是 90001 的桩</b>，
 * 它现在读 {@link FeedService}（离线批次算好的 {@code recommend_result} 缓存 + 热度兜底），
 * 并新增 {@code POST /api/feed/dislike}（T7.7）。本类的定位因此从「阶段 2 的临时桩」
 * 变成「首页三条流的入口」：广场与详情在 {@link PostController}，关注流与推荐流在这里。
 *
 * <p><b>2026-09-23 任务 3.17 在本类加了 GET /api/feed/following（关注流）</b>。
 * 它放在这里而不是 PostController，是因为路径前缀就是 /api/feed：Swagger 分组、
 * 以及「首页三条流（广场 / 关注 / 推荐）」的归属都按前缀走。真正的判据仍然只有一份，
 * 在 {@code PostQueryService#following}，本类只多一个入口。
 * 注意它与 /api/topics 不同——<b>关注流不在 permitAll 白名单里</b>，未登录 401/10002。
 * 推荐流同口径，而且理由更硬：没有行为矩阵就没有个性化，给游客「长得像推荐的样子」
 * 是把对照组（{@code mode=hot}）当实验组卖，§6.4 的六组指标当场作废。
 */
@RestController
@Tag(name = "4 内容", description = "话题墙、推荐流与帖子发布（阶段 3 起逐步开放）")
public class FeedController {

  private final TopicMapper topicMapper;
  private final UserFollowMapper userFollowMapper;
  private final PostQueryService postQueryService;
  private final UserActionRecorder recorder;
  private final FeedService feedService;

  /**
   * 任务 3.17 起本类多两个依赖：user_follow 的读数与 post 的取数组装。
   * T7.9 再加 {@link FeedService}：推荐的全部算法在离线侧，本类只多一个入口和一句 401 判据。
   */
  public FeedController(TopicMapper topicMapper, UserFollowMapper userFollowMapper,
      PostQueryService postQueryService, UserActionRecorder recorder, FeedService feedService) {
    this.topicMapper = topicMapper;
    this.userFollowMapper = userFollowMapper;
    this.postQueryService = postQueryService;
    this.recorder = recorder;
    this.feedService = feedService;
  }

  @GetMapping("/api/topics")
  @Operation(summary = "官方话题墙（已过审，按热度倒序）")
  public Result<List<TopicBrief>> topics(
      @Parameter(description = "返回条数，取值 1-50")
      @RequestParam(name = "limit", defaultValue = "10") int limit) {
    int safe = Math.max(1, Math.min(limit, 50));
    return Result.ok(topicMapper.listOfficialApproved(safe).stream().map(TopicBrief::of).toList());
  }

  /**
   * 关注流时间线（任务 3.17 · 手册 §6.1 行 3.17 · 需求 FR4.6）。
   *
   * <p><b>作者 id 在这里取、取数在 {@code PostQueryService}</b>：本类负责「我关注了谁」这条
   * 关系读数，Service 负责「这批人的哪些帖我能看」这条可见性判据。反过来让 Service 自己去查
   * {@code user_follow}，它就得多依赖一个 Mapper——它已经有 8 个协作者了。</p>
   *
   * <p><b>未登录必 401，不退化成「给你看广场」</b>：把 current 为 null 兜底成「没关注任何人」
   * 看似友好，实际是这条路径变成了广场的第二入口，而广场那条是明写要登录的。
   * 与 PostController 同一口径：白名单被人改宽时，这里也还有一道自己的闸。</p>
   */
  @GetMapping("/api/feed/following")
  @Operation(summary = "关注流（我关注的人的公开实名帖，翻页与广场同一口径）")
  public Result<PageResult<PostListItem>> following(PageQuery page,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    List<Long> authorIds = userFollowMapper.listFollowingIds(current.id(),
        PostQueryService.FOLLOWING_AUTHOR_CAP);
    PageResult<PostListItem> result =
        postQueryService.following(current.id(), authorIds, page, LocalDateTime.now());
    // 关注流的曝光（任务 T3.10）。scene 用 following 而不是 feed，是为了阶段 7 能把
    // 「社交关系带来的曝光」和「广场算法带来的曝光」分开算分母——两条流推的内容来源本就不同。
    // 推荐流的曝光不在这里记：它由 FeedService 在真正发出那一屏时自己记（要带 mode 才判得对
    // 去重窗口与 AB 分组），两处各记各的，互不重复。
    recorder.recordExposure(current.id(), result.getList().stream().map(PostListItem::id).toList(),
        UserActionCatalog.SCENE_FOLLOWING, LocalDateTime.now());
    return Result.ok(result);
  }

  /**
   * 个性化推荐流（任务 T7.9 / T7.10 · 需求 FR5.1 FR5.8 · Gate7 判据 D1、D5、D7）。
   *
   * <p><b>入口只判登录，算法判据一条都不在这里</b>：批次是否过期、退回热度兜底、
   * 危机帖拦截、AB 分组全在 {@link FeedService}，本方法只多一句 401。
   * 这不是省事——手册 §10.4 把「读侧不重复实现判据」列为红线：同一条 L2/L3 拦截
   * 写两处，将来只改一处，另一处就成了「代码里看起来拦了、实际没拦」的假保险。</p>
   *
   * <p><b>返回体永不为空列表</b>（需求 FR5.8）：兜底路径在 Service 内，所以这里
   * 不需要 {@code RECOMMEND_EMPTY}。该错误码保留给「连热度榜都凑不出」的真·空库
   * （新装环境零帖），那种情况下前端显示「社区还很安静」比显示六条假推荐诚实。</p>
   */
  @GetMapping("/api/feed/recommend")
  @Operation(summary = "情绪感知加权推荐流（离线协同过滤 + 在线读缓存，空批次自动退热度榜）")
  public Result<PageResult<FeedService.FeedItem>> recommend(PageQuery page,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(feedService.feed(current.id(), page, LocalDateTime.now()));
  }

  /**
   * 点「不感兴趣」（任务 T7.7 · 需求 FR1.7 FR5.7 · Gate7 判据 D6）。
   *
   * <p>用 POST 而不是 DELETE：它不是「删这条帖」（那是作者的权利），而是「在我这儿降权」，
   * 是一次有副作用的计数写入（{@code user_action} +1），REST 语义上属于创建一条反馈记录。
   * 前端因此不必为它准备乐观更新回滚——失败时把按钮恢复即可，缓存行没动。</p>
   *
   * <p>出参回两个删除数是为了 D6 能自动断言（见 {@link FeedService.DismissResult}）；
   * 前端忽略它们，只显示一句「已减少此类内容」。幂等：第二次点只是删 0 行，
   * 行为侧走 {@code UserActionMapper#upsert}，同一帖同一天撞 {@code uk_action}（含
   * {@code day_bucket}）只覆盖不新增，所以连点不会把 -3 的负权重叠成 -6。</p>
   */
  @PostMapping("/api/feed/dislike")
  @Operation(summary = "对一条推荐帖点不感兴趣（连同相似帖一起压掉）")
  public Result<FeedService.DismissResult> dislike(@RequestBody(required = false) DislikeReq body,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    if (body == null || body.postId() == null || body.postId() <= 0) {
      throw new BizException(ErrorCode.PARAM_INVALID, "postId 必填且为正整数");
    }
    return Result.ok(feedService.dislike(current.id(), body.postId(), LocalDateTime.now()));
  }

  /** 不感兴趣的入参：只需要一条帖。用 record 而不是 Map，Swagger 才能生成字段说明。 */
  public record DislikeReq(Long postId) {
  }

  /**
   * 话题出参。
   *
   * <p>不透出 cover、deleted、auditStatus：封面地址涉及对象存储域名，逻辑删除位与
   * 审核状态属于运营信息，都不该出现在公开接口的返回体里。
   */
  public record TopicBrief(
      Long id, String name, String desc, Integer postCnt,
      Integer followCnt, BigDecimal hotScore, Integer isOfficial) {

    static TopicBrief of(Topic topic) {
      return new TopicBrief(topic.getId(), topic.getName(), topic.getDescTxt(),
          topic.getPostCnt(), topic.getFollowCnt(), topic.getHotScore(), topic.getIsOfficial());
    }
  }
}

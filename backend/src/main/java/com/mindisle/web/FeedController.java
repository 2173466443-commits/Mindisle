package com.mindisle.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
import com.mindisle.security.AuthUser;
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
 * <p><b>2026-09-23 任务 3.17 在本类加了 GET /api/feed/following（关注流）</b>。
 * 它放在这里而不是 PostController，是因为路径前缀就是 /api/feed：Swagger 分组、
 * 以及「首页三条流（广场 / 关注 / 推荐）」的归属都按前缀走。真正的判据仍然只有一份，
 * 在 {@code PostQueryService#following}，本类只多一个入口。
 * 注意它与 /api/topics 不同——<b>关注流不在 permitAll 白名单里</b>，未登录 401/10002。
 */
@RestController
@Tag(name = "4 内容", description = "话题墙、推荐流与帖子发布（阶段 3 起逐步开放）")
public class FeedController {

  private final TopicMapper topicMapper;
  private final UserFollowMapper userFollowMapper;
  private final PostQueryService postQueryService;

  /**
   * 任务 3.17 起本类多两个依赖：user_follow 的读数与 post 的取数组装。
   * {@code recommend} 那个 90001 的桩仍然不碰它们——推荐属阶段 4，别为了「看起来能用」提前接。
   */
  public FeedController(TopicMapper topicMapper, UserFollowMapper userFollowMapper,
      PostQueryService postQueryService) {
    this.topicMapper = topicMapper;
    this.userFollowMapper = userFollowMapper;
    this.postQueryService = postQueryService;
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
    return Result.ok(postQueryService.following(current.id(), authorIds, page, LocalDateTime.now()));
  }

  @GetMapping("/api/feed/recommend")
  @Operation(summary = "情绪感知加权推荐流（阶段 3 召回 + 阶段 4 加权）")
  public Result<Void> recommend() {
    throw BizException.notImplemented("阶段3 推荐召回与阶段4 情绪感知加权");
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

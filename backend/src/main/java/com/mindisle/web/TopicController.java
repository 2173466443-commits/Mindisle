package com.mindisle.web;

import java.time.LocalDateTime;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.common.Result;
import com.mindisle.post.PostQueryService;
import com.mindisle.post.dto.PostListItem;
import com.mindisle.security.AuthUser;
import com.mindisle.topic.TopicService;
import com.mindisle.topic.dto.TopicCard;
import com.mindisle.topic.dto.TopicCreateRequest;
import com.mindisle.topic.dto.TopicCreateView;
import com.mindisle.topic.dto.TopicFollowRequest;
import com.mindisle.topic.dto.TopicFollowView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 话题详情、话题页帖流、创建话题、关注话题（任务 3.8 · 手册 §6.1 行 3.8、§6.2 U6 · 需求 FR4.5）。
 *
 * <p><b>为什么单开一个 Controller 而不并进 {@link PostController}</b>：那边每一条端点都要先问
 * 「这条帖能不能被这个人看到」，判据是 {@code PostQueryService#visibleTo}；话题域问的是
 * 「这个话题现在能不能被读」，判据是 {@code TopicService#requireReadable}。两套判据混进一个类，
 * 迟早会出现「用帖子的 404 口径去回话题的待审」——那正是手册 L803 明令禁止的
 * （未过审话题该回 409/30004，回 404 会让人以为创建失败了）。</p>
 *
 * <p><b>为什么并进 {@link FeedController} 也不行</b>：它已经有 {@code GET /api/topics}（话题墙，
 * 游客可访问）。本类的四条路径全部要求登录，两者混在一起就会为了四条新端点去改
 * {@code SecurityConfig} 的白名单形状 —— 而白名单是全站唯一的一处，动它的风险远大于多一个类。
 * Spring MVC 允许 {@code /api/topics} 与 {@code /api/topics/{id:\d+}} 并存，
 * 精确路径优先，且这里带数字约束，{@code /api/topics/abc} 是 404/90006 而不是 500。</p>
 *
 * <p><b>Swagger 分组</b>：逐字复用「4 内容」这一组（与 FeedController、PostController 同一份
 * name + description）。话题与帖子在用户眼里是同一件事的两端（「话题页里的一条流」），
 * 分两组只会让接口文档的目录比实际领域多一层。这样 springdoc 顶层 tags 仍然是 9 组，
 * README 的接口分组计数不必改。</p>
 *
 * <p><b>四条端点都要求登录</b>：{@code /api/topics/*} 不在 permitAll 白名单里，未登录在过滤器
 * 处就 401；{@code current == null} 的兜底照抄 PostController —— 白名单将来被人改宽时，
 * 这里也还有一道自己的闸。唯一例外是 {@code GET /api/topics}（话题墙，游客可逛），它在
 * {@link FeedController} 里，本类不碰。</p>
 */
@RestController
@Tag(name = "4 内容", description = "话题墙、推荐流与帖子发布（阶段 3 起逐步开放）")
public class TopicController {

  private final TopicService topicService;
  private final PostQueryService postQueryService;

  public TopicController(TopicService topicService, PostQueryService postQueryService) {
    this.topicService = topicService;
    this.postQueryService = postQueryService;
  }

  /**
   * 话题详情（U6 头图区域的数据源）。
   *
   * <p>待审 409/30004、驳回与不存在 404/90006，两条都在 {@code TopicService#requireReadable} 里定，
   * 本类不解释状态码。</p>
   */
  @GetMapping("/api/topics/{id:\\d+}")
  @Operation(summary = "话题详情（资料头 + 当前用户是否已关注；未过审 409/30004）")
  public Result<TopicCard> detail(@PathVariable("id") long id,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(topicService.detail(current.id(), id));
  }

  /**
   * 话题下的帖子流（需求 FR4.5「聚合页展示话题下热帖」）。
   *
   * <p><b>可见性判据与广场同一条</b>：走 {@code PostQueryService#topicPosts}，它在
   * {@code applyVisible} 之上叠一个话题成员条件，而不是自己写一遍 status / visibility。
   * 否则就会出现「广场里已经看不到的帖，话题页还挂着」——手册 §14 第 27 条记的正是这种
   * 「同一判据两处实现」。</p>
   *
   * <p>sort 取值 latest / top，缺省 latest。这里要说清今天的名不副实之处：
   * top 只是「置顶帖排在前面」，而 {@code post.is_top} 只有管理员能写、管理端在 T6.1，
   * 所以库里 {@code is_top} 恒为 0，两种排序今天的序列完全相同。真正按 hot_score 的热度序
   * 属阶段 4。接口形状先按最终形态定下来，是为了让前端不必为了「将来加一档排序」改一次调用点。</p>
   */
  @GetMapping("/api/topics/{id:\\d+}/posts")
  @Operation(summary = "话题下的帖子列表（与广场同一可见性判据；sort=latest|top）")
  public Result<PageResult<PostListItem>> posts(
      @PathVariable("id") long id,
      @Parameter(description = "排序：latest 最新（缺省）/ top 置顶在前")
      @RequestParam(name = "sort", required = false) String sort,
      PageQuery page,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(postQueryService.topicPosts(id, current.id(), sort, page, LocalDateTime.now()));
  }

  /**
   * 创建话题（需求 FR1.7 + FR4.5）。
   *
   * <p><b>恒返 200，包括「进了待审」</b>：与发帖、评论同一口径 ——
   * 「创建成功但需要审核」是一次业务上完全成功的请求，用户需要的是原因和下一步
   * （回执里的 {@code usable} 与 {@code tip}），而不是一个和「你没登录」同类的 4xx。
   * 真正的入参错误才走 400/10001：空名、超长、重名；超配额走 429/10010。</p>
   */
  @PostMapping("/api/topics")
  @Operation(summary = "创建话题（默认需预审，回执 usable 告诉前端现在能不能进）")
  public Result<TopicCreateView> create(@RequestBody(required = false) TopicCreateRequest request,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(topicService.create(current.id(), request, LocalDateTime.now()));
  }

  /**
   * 关注 / 取关话题。
   *
   * <p>与关注人、点赞帖子三处同一形状：一条 POST + body 里一个 action，恒返 200 + changed。
   * 完整理由写在 {@code topic.dto.TopicFollowRequest} 的类注释里，此处不重复第二遍。</p>
   */
  @PostMapping("/api/topics/{id:\\d+}/follow")
  @Operation(summary = "关注 / 取关话题（follow|unfollow，幂等；返回状态与重算关注数）")
  public Result<TopicFollowView> follow(@PathVariable("id") long id,
      @RequestBody(required = false) TopicFollowRequest request,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(topicService.follow(current.id(), id, request == null ? null : request.action()));
  }
}

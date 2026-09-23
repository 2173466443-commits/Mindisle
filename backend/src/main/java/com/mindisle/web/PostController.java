package com.mindisle.web;

import java.time.LocalDateTime;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.common.Result;
import com.mindisle.post.CommentService;
import com.mindisle.post.PostInteractionService;
import com.mindisle.post.PostQueryService;
import com.mindisle.post.PostService;
import com.mindisle.post.ReportService;
import com.mindisle.post.dto.CommentCreateRequest;
import com.mindisle.post.dto.CommentCreateView;
import com.mindisle.post.dto.CommentThread;
import com.mindisle.post.dto.CreatePostRequest;
import com.mindisle.post.dto.PostActionRequest;
import com.mindisle.post.dto.PostActionView;
import com.mindisle.post.dto.PostDetailView;
import com.mindisle.post.dto.PostListItem;
import com.mindisle.post.dto.PostView;
import com.mindisle.post.dto.ReportRequest;
import com.mindisle.post.dto.ReportView;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 发帖接口（任务 3.3 对外口 · 手册 §6.2 U5 · 需求 FR4.1、FR4.2、FR7.3）。
 *
 * <p>本路径不在 SecurityConfig 的 permitAll 白名单里，未登录必 401/10002；
 * current == null 的兜底判断照抄 AuditController：白名单被人改宽时，
 * 发帖也不能变成匿名可打的接口（那等于把内容安全链的第一环交给攻击者）。</p>
 *
 * <p><b>状态码口径</b>：被机审拦下（REJECTED）与转人工（HUMAN_REVIEW）都回 <b>200</b>，
 * 处置结果在 {@code data.status} 与 {@code data.tip} 里。理由见 {@link PostView} 的类注释：
 * 「内容没通过审核」是一次业务上完全成功的请求，用户需要的是原因和下一步，
 * 而不是一个和「你没登录」同类的 4xx。真正的入参错误才走 400/10001。</p>
 *
 * <p><b>任务 3.5 把 GET /api/posts 与 /api/posts/{id}、任务 3.6 把 /{id}/actions 也收进这个类</b>：
 * 读接口和写接口动的是同一张 post 表、同一套可见性判据，拆在两个 Controller 里
 * 就会出现「谁负责 404、谁负责 403」的口径分裂。路径前缀本来就是同一个 {@code /api/posts}，
 * 读接口也照样要求登录——需求 FR7.3 的广场是「登录后可见」，游客只给话题墙。</p>
 *
 * <p><b>任务 3.7 的评论端点同样留在这个类里</b>：评论挂在帖子上，可见性判据要先问帖子
 * （{@code PostQueryService.visibleTo}）再问评论本身，与详情接口是同一条判据；另起一个
 * {@code CommentController} 只会把这条判据复制第二份。这里<b>不</b>是因为 Swagger 分组有限制：旧版注释写着「@Tag 分组数被
 * docs/openapi-check 断言写死」，那是假事实——仓库里没有这个脚本，smoke.mjs 也不校验 /v3/api-docs，
 * 手册 v1.2.1 已订正。分组数由 {@code OpenApiConfig} 的 pathsToMatch 决定，可以按需增加（T3.11-b 就是第 8 个）。</p>
 *
 * <p><b>任务 3.11 的举报端点同样留在这个类里</b>，理由与评论一样硬：它挂在帖子上，
 * 「这条内容能不能被举报」用的还是 {@code PostQueryService.visibleTo} 那一份判据，
 * 另起一个 Controller 就等于抄第二份可见性判据。但它<b>不</b>并进 {@code /actions}，
 * 改口的完整理由见 {@link #act} 与 {@code ReportService} 的类注释。</p>
 */
@RestController
@RequestMapping("/api/posts")
@Tag(name = "4 内容", description = "话题墙、推荐流与帖子发布（阶段 3 起逐步开放）")
public class PostController {

  private final PostService postService;
  private final PostQueryService postQueryService;
  private final PostInteractionService postInteractionService;
  private final CommentService commentService;
  private final ReportService reportService;

  public PostController(PostService postService, PostQueryService postQueryService,
                        PostInteractionService postInteractionService,
                        CommentService commentService, ReportService reportService) {
    this.postService = postService;
    this.postQueryService = postQueryService;
    this.postInteractionService = postInteractionService;
    this.commentService = commentService;
    this.reportService = reportService;
  }

  @PostMapping
  @Operation(summary = "发帖（普通/树洞/求助，服务端先审后发并做危机分流）")
  public Result<PostView> publish(@Valid @RequestBody CreatePostRequest request,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(postService.publish(current.id(), request, LocalDateTime.now()));
  }

  @GetMapping
  @Operation(summary = "广场帖子列表（游标分页 + 形式过滤，未发布与不可见的一律不出现）")
  public Result<PageResult<PostListItem>> list(
      @Parameter(description = "帖子形式：normal / hole / help，不传表示全部")
      @RequestParam(name = "type", required = false) String type,
      PageQuery page,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(postQueryService.list(current.id(), type, page, LocalDateTime.now()));
  }

  @GetMapping("/{id}")
  @Operation(summary = "帖子详情（非作者访问计一次浏览；不可见与不存在统一 30001/404）")
  public Result<PostDetailView> detail(@PathVariable("id") long id,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(postQueryService.detail(current.id(), id, LocalDateTime.now()));
  }

  /**
   * 点赞 / 取消点赞 / 收藏 / 取消收藏（任务 3.6 · 需求 FR4.4、BR2）。
   *
   * <p><b>路径带数字约束</b>：与主页那条 /{id:\d+}/posts 同一口径。不加约束时
   * {@code POST /api/posts/abc/actions} 会在 long 转换处炸成 500，加了约束才是 404/90006 ——
   * 一个不存在的资源，而不是「服务端有 bug」的假象。</p>
   *
   * <p><b>四个动作共用一条 POST</b>：<b>举报不并进这里</b>。需求 §9.1 原本把举报算作
   * 这个端点的第五个 action（{@code report}），任务 3.11 落地时改口，另起
   * {@link #report}。改口的理由：点赞与收藏是「关系开关态」，一次请求只带一个动作码、
   * 没有别的载荷，重复调用换个方向就完事；举报自带理由 / 描述 / 截图三段载荷，
   * 要写自己的真相表（content_report）并派生处置工单（audit_task），
   * 硬塞进来只会让 {@link PostActionRequest} 变成五字段的「什么都收一点」请求体，
   * 还要让两套互不相干的白名单（like/unlike/... 与 spam/abuse/...）共用一处校验——
   * 任何一边放宽都会污染另一边。同理，这四个动作也不按 HTTP 方法拆成两套：
   * 「POST 点赞 + DELETE 取消点赞」里的取消不是删一条属于用户的资源，
   * 它只是把同一对关系切到另一侧；拆成两个 HTTP 方法会把幂等语义变成两套
   * （DELETE 重复调用该回 204 还是 404，本来就没有共识）。</p>
   *
   * <p>恒返 200，包括「重复点赞」这种什么都没改的请求：前端要的是最新状态，
   * {@code data.changed=false} 已经把「这次没改动」说清楚了，再叠一层 4xx 只会让按钮更难写。</p>
   */
  @PostMapping("/{id:\\d+}/actions")
  @Operation(summary = "帖子互动（like / unlike / collect / uncollect，幂等；返回互动后的状态与重算计数）")
  public Result<PostActionView> act(@PathVariable("id") long id,
      @RequestBody(required = false) PostActionRequest request,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(postInteractionService.act(current.id(), id,
        request == null ? null : request.action(), LocalDateTime.now()));
  }

  /**
   * 发一条评论 / 回复（任务 3.7 · 需求 FR4.3、FR4.4、FR7.3）。
   *
   * <p><b>恒返 200</b>，与发帖同一口径：评论被机审拦下（REJECTED）、转人工（PUBLISHED 但待审）、
   * 匿名联系方式被遮罩，都是「请求成功、内容被处置」，处置结果在
   * {@code data.comment.status}、{@code data.tip} 与 {@code data.hotline} 里。
   * 只有真正的入参错误走 4xx：空内容与超字数 400/10001、父级评论不属于本帖 400/10001、
   * 帖子不可见与不存在统一 404/30001（不区分二者，否则就成了私密帖的枚举通道）。</p>
   *
   * <p><b>求助卡片只在命中危机时给</b>：{@code data.hotline} 非空即代表这一次评论触发了
   * 工单，前端据此渲染 12356 卡片；没命中就回 null，不是回一个空串——空串在 Vue 里
   * 是 falsy 但会被 v-if 之外的地方当成「有值」，null 才是「这次没有」。</p>
   */
  @PostMapping("/{id:\\d+}/comments")
  @Operation(summary = "发表评论或回复（先审后发；楼中楼压平成两级展示，危机词走同一套分级与工单）")
  public Result<CommentCreateView> comment(@PathVariable("id") long id,
      @RequestBody(required = false) CommentCreateRequest request,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(commentService.comment(current.id(), id, request, LocalDateTime.now()));
  }

  /**
   * 评论列表（任务 3.7 · 两级展示）。
   *
   * <p>不带 {@code rootId}：按一级评论正序翻页，每条带前 3 条回复预览与该子树回复总数；
   * 带 {@code rootId}：整棵子树一次给完（上限 500 条，边界见手册 §14）。</p>
   *
   * <p>待审评论（PENDING）只有作者自己看得见，所以<b>登录用户的身份参与 SQL 谓词</b>，
   * 这条接口不开放给游客。评论 id 非法或不属于本帖时是 400/10001，不是 404：
   * 帖子的存在性在这次请求里已经通过了，再拿 404 区分「不存在」与「被删」就是多余的泄漏。</p>
   */
  @GetMapping("/{id:\\d+}/comments")
  @Operation(summary = "评论列表（一级评论分页 + 楼中楼预览；rootId 非空时展开整棵子树）")
  public Result<PageResult<CommentThread>> comments(@PathVariable("id") long id,
      @Parameter(description = "要展开的一级评论 id；不传表示翻页取一级评论")
      @RequestParam(name = "rootId", required = false) Long rootId,
      PageQuery page,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(commentService.list(id, current.id(), rootId, page, LocalDateTime.now()));
  }

  /**
   * 举报一条帖子内容（任务 3.11 · 需求 FR4.7、FR4.4、FR7.3）。
   *
   * <p><b>恒返 200</b>，与发帖、评论、点赞同一口径：「这次没新增记录，因为你早就举报过了」
   * 和「举报成功、这条内容已进人审队列」是同一个成功响应的两种数据，
   * {@code data.duplicated} 与 {@code data.reportCnt} 已经把区别说清楚，
   * 前端要的是「按钮变成什么态、下一句说什么」，而不是一个需要 try/catch 的 4xx。
   * 真正的入参错误才走 4xx：理由不在六类白名单、描述超字数、证据张数超限或塞了外链 → 400/10001；
   * 帖子不存在、已删除、树洞到期、别人的私密帖一律 404/30001（不区分，否则这个接口
   * 就成了探测「某条私密内容是否存在」的通道）。</p>
   *
   * <p><b>与 /actions 分开一条路径</b>：需求 §9.1 原本把举报并进去，落地时改口了，
   * 完整理由见 {@link #act} 的注释与 {@code ReportService} 的类注释。
   * 一句话版：举报自带理由/描述/证据三段载荷并写自己的真相表，不是点赞那种开关态。</p>
   *
   * <p>{@code data.hotline} 非空即「必须显示求助卡片」，只在理由为 self-harm 时给；
   * 号码只从配置来，本方法的任何一句提示文案里都不出现号码（全站口径）。</p>
   */
  @PostMapping("/{id:\\d+}/report")
  @Operation(summary = "举报帖子（六类理由 + 可选描述与截图；达阈值自动转人工审核并暂时隐藏）")
  public Result<ReportView> report(@PathVariable("id") long id,
      @RequestBody(required = false) ReportRequest request,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(reportService.report(current.id(), id, request, LocalDateTime.now()));
  }
}

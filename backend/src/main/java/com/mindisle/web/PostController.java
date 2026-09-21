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
import com.mindisle.post.PostInteractionService;
import com.mindisle.post.PostQueryService;
import com.mindisle.post.PostService;
import com.mindisle.post.dto.CreatePostRequest;
import com.mindisle.post.dto.PostActionRequest;
import com.mindisle.post.dto.PostActionView;
import com.mindisle.post.dto.PostDetailView;
import com.mindisle.post.dto.PostListItem;
import com.mindisle.post.dto.PostView;
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
 */
@RestController
@RequestMapping("/api/posts")
@Tag(name = "4 内容", description = "话题墙、推荐流与帖子发布（阶段 3 起逐步开放）")
public class PostController {

  private final PostService postService;
  private final PostQueryService postQueryService;
  private final PostInteractionService postInteractionService;

  public PostController(PostService postService, PostQueryService postQueryService,
                        PostInteractionService postInteractionService) {
    this.postService = postService;
    this.postQueryService = postQueryService;
    this.postInteractionService = postInteractionService;
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
   * <p><b>四个动作共用一条 POST</b>：需求 §9.1 把举报也并进这个端点（举报属任务 3.11，
   * 届时在同一份白名单里加 {@code report}，而不是另起路径）。之所以不做成
   * 「POST 点赞 + DELETE 取消点赞」：取消一次点赞不是删一条属于用户的资源，
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
}

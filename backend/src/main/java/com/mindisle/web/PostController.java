package com.mindisle.web;

import com.mindisle.entity.Comment;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.admin.AuditQueueService;
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
import com.mindisle.post.dto.ReadProgressRequest;
import com.mindisle.post.dto.ReadProgressView;
import com.mindisle.post.dto.ReportView;
import com.mindisle.track.UserActionCatalog;
import com.mindisle.track.UserActionRecorder;
import com.mindisle.recommend.FeedService;
import com.mindisle.recommend.SimilarPostService;
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
  private final UserActionRecorder recorder;
  private final SimilarPostService similarPostService;
  private final AuditQueueService auditQueueService;

  public PostController(PostService postService, PostQueryService postQueryService,
                        PostInteractionService postInteractionService,
                        CommentService commentService, ReportService reportService,
                        UserActionRecorder recorder, SimilarPostService similarPostService,
                       AuditQueueService auditQueueService) {
    this.postService = postService;
    this.postQueryService = postQueryService;
    this.postInteractionService = postInteractionService;
    this.commentService = commentService;
    this.reportService = reportService;
    this.recorder = recorder;
    this.similarPostService = similarPostService;
    this.auditQueueService = auditQueueService;
  }

  @PostMapping
  @Operation(summary = "发帖（普通/树洞/求助，服务端先审后发并做危机分流）")
  public Result<PostView> publish(@Valid @RequestBody CreatePostRequest request,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    LocalDateTime now = LocalDateTime.now();
    PostView view = postService.publish(current.id(), request, now);
    // 灰词帖当场进审核队列（手册 §15 阶段 8 队列⑤ · 判据 D4）。改造前只有 cron 一分钟一轮的同步，
    // 「发帖 → 出现在审核台」实测 60 388ms，现场演示要在这一跳干等。放在控制器而不是发帖事务里，
    // 是因为这一步绝不能把已经成功的发布变成一次失败：建单失败只记日志，最差退回改造前的行为。
    // 只对 HUMAN_REVIEW 生效——直发（PUBLISHED）与被拦（REJECTED）都不该出现在人审队列里。
    auditQueueService.enqueueQuietly(view.id(), view.status(), now);
    return Result.ok(view);
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
    PageResult<PostListItem> result =
        postQueryService.list(current.id(), type, page, LocalDateTime.now());
    // 曝光埋点挂在这里（任务 T3.10 · 手册 §6.1 行 3.10）：广场是「服务端把一批帖子交给用户」
    // 的路径，scene 用 plaza。推荐流（T7.9 落地）不共用这一句——它要带 AB 分组 mode 才能判对
    // 去重窗口，所以由 FeedService 在发出那一屏时自己记，scene 用 feed。两条流的曝光分开算分母，
    // §6.4 的消融对比才有干净的口径。
    recordExpose(current.id(), result, UserActionCatalog.SCENE_PLAZA);
    return Result.ok(result);
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
   * 「看了又看」相似帖（任务 T7.16 · 手册 §10.6 · 需求 FR5.6）。
   *
   * <p><b>形状与推荐流同一条契约</b>（{@code List<FeedItem>}）：卡片、理由、召回通道三件套
   * 与首页完全一致，前端复用同一张 {@code PostCard}。差别只在两处：这里是<b>整页返回、不分页</b>
   * （相似位是详情页的一个区块，不是无限流），以及理由文案另写一套
   * （「和这篇一样…」而不是「因为你…」，手册 §10.6 第 3 条要求两者分开）。</p>
   *
   * <p>错误码用 30001/404 而不是 200 空列表：源帖本身不可见时，「不可见」与「不存在」
   * 必须同形（与 {@link #detail} 同一口径），否则这个端点会退化成一枚探测别人私密帖的探针。
   * 源帖可见但确实没有相似内容时，兜底通道会补出内容来，所以这里几乎不会出现空数组
   * （手册 §10.6 第 1 条：不返回空列表）。</p>
   *
   * <p>{@code size} 越界不报错，夹到区间内（默认 6、上限 12，见
   * {@link SimilarPostService#clampSize}）：它只是一个区块的展示条数，
   * 传 999 的动机是「多要一点」，不是攻击面，没必要为此回一个 400 打断详情页。</p>
   */
  @GetMapping("/{id:\\d+}/similar")
  @Operation(summary = "详情页相似帖（ItemCF 邻居 + 同话题兜底 + 质量分榜补位，整页返回）")
  public Result<List<FeedService.FeedItem>> similar(@PathVariable("id") long id,
      @Parameter(description = "返回条数，默认 6，最多 12")
      @RequestParam(name = "size", required = false) Integer size,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return Result.ok(similarPostService.similar(current.id(), id, size, LocalDateTime.now()));
  }

  /**
   * 上报一次停留（任务 T3.10 · 需求 FR5.1「停留时长 ≥3s 计 1 分、完读 2 分」）。
   *
   * <p><b>这条端点是需求 §9.1 接口清单里没有的</b>，属契约漂移，已记进 dev-log 与手册 §19。
   * 加它的唯一理由：FR5.1 的判据是「停留」，而停留只有浏览器量得到。
   * {@link #detail} 不写 user_action（那里只加 view_cnt），否则「打开一次详情页」
   * 会被记成一次 3 秒以上的浏览——列表页的自动预加载、点开就退出的秒退，
   * 全都会变成正样本，阶段 7 的召回质量就是这么被埋点口径毁掉的。</p>
   *
   * <p><b>完读同样要先满阈值</b>（{@code completed} 且停留 {@code >= 3s} 才记 read_through）：前端的
   * 「到底」判据量的是 {@code scrollHeight - innerHeight - scrollY}，内容不足一屏时它天生为真，
   * 短帖「打开就是全部」；上一版对 {@code completed} 没有任何时长下限，于是前端在正文排版完成之前
   * 误判到底、报出一条 {@code durationMs=1}，服务端照样把完读那 2 分记进 user_action ——
   * 上面那句「点开就退出的秒退全都会变成正样本」就是这么被绕过去的。阈值这条线在<b>服务端</b>
   * 再守一次：客户端怎么改都不该能把「一秒没读」刷成完读，这既是防刷，也是阶段 7 样本质量的下限。</p>
   *
   * <p><b>不够阈值也回 200</b>，且 data.viewRecorded=false 把「为什么没记」说清楚：
   * 这是一次完全成功的上报。回 4xx 会让前端在 pagehide 里收到一个无法处理的错误，
   * 而「你只看了 1.2 秒」既不是错误也不值得给用户弹提示。</p>
   *
   * <p><b>不可见的帖子静默收下不上报</b>（不记埋点，但仍回 200）：这里刻意不复用 30001/404，
   * 因为这条接口是前端在离开页面时打的，回 404 只会让人去查「为什么详情页能打开、
   * 上报却 404」。埋点是旁路，旁路的拒绝不该变成用户可见的错误。</p>
   */
  @PostMapping("/{id:\\d+}/read")
  @Operation(summary = "上报停留时长与是否读完（≥3s 计 view；completed 且≥3s 才计完读；不足阈值也回 200）")
  public Result<ReadProgressView> read(@PathVariable("id") long id,
      @RequestBody(required = false) ReadProgressRequest request,
      @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    LocalDateTime now = LocalDateTime.now();
    Integer durationMs = request == null ? null : request.durationMs();
    boolean completed = request != null && Boolean.TRUE.equals(request.completed());
    boolean enough = UserActionCatalog.isEnoughDwell(durationMs);
    if (!postQueryService.dwellCountableFor(current.id(), id, now)) {
      return Result.ok(new ReadProgressView(id, UserActionCatalog.VIEW_MIN_DURATION_MS,
          false, false));
    }
    if (enough) {
      recorder.recordWithDwell(current.id(), UserActionCatalog.ACTION_VIEW, id,
          UserActionCatalog.SCENE_DETAIL, durationMs, now);
    }
    boolean readThrough = completed && enough;
    if (readThrough) {
      recorder.recordWithDwell(current.id(), UserActionCatalog.ACTION_READ_THROUGH, id,
          UserActionCatalog.SCENE_DETAIL, durationMs, now);
    }
    return Result.ok(new ReadProgressView(id, UserActionCatalog.VIEW_MIN_DURATION_MS,
        enough, readThrough));
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
    PostActionView view = postInteractionService.act(current.id(), id,
        request == null ? null : request.action(), LocalDateTime.now());
    // 埋点读的是<b>回执里的状态</b>，不是请求里那个 action（任务 T3.10）。
    // 差别在两处：① 前端连点两次 like，第二次 view.liked 仍是 true，记一次幂等 upsert，
    // 而按请求记会出现「用户其实没改变任何东西、行为表里却多了一条新读数」；
    // ② unlike 之后必须把活动行软删，否则取消赞的人仍然在给这条内容加分。
    // 以状态为准的好处是：user_action 里「有没有 like 这行」与 post_like 的真值同构，
    // 阶段 7 无论读哪张表算出的正样本集合都一致。
    trackSwitch(current.id(), id, UserActionCatalog.ACTION_LIKE, view.liked());
    trackSwitch(current.id(), id, UserActionCatalog.ACTION_COLLECT, view.collected());
    return Result.ok(view);
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
    CommentCreateView view = commentService.comment(current.id(), id, request, LocalDateTime.now());
    // 只有真发出去的评论才记 comment 分（任务 T3.10）。PENDING 不记：它可能在一轮人审之后
    // 变成 REJECTED，那时这条内容根本不会出现在任何人的时间线上，
    // 而行为表里已经留下一条 +4 的正样本——CF 学不到「被驳回的评论也是互动」，只会学到噪声。
    if (view.comment() != null && Comment.STATUS_PUBLISHED.equals(view.comment().status())) {
      recorder.record(current.id(), UserActionCatalog.ACTION_COMMENT,
          UserActionCatalog.TARGET_POST, id, UserActionCatalog.SCENE_DETAIL, LocalDateTime.now());
    }
    return Result.ok(view);
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
    ReportView view = reportService.report(current.id(), id, request, LocalDateTime.now());
    // duplicated=true 也记：upsert 命中的是同一条 uk，重复举报不会多出一行，
    // 而「这个人举报过」这个事实第一次落库时就已经进去了
    recorder.record(current.id(), UserActionCatalog.ACTION_REPORT,
        UserActionCatalog.TARGET_POST, id, UserActionCatalog.SCENE_DETAIL, LocalDateTime.now());
    return Result.ok(view);
  }

  /**
   * 开关型行为（点赞、收藏）落埋点的唯一入口。
   *
   * <p>{@code on == true} 记一行、{@code false} 软删这个目标上的全部活动行。
   * 收成一个小函数是因为「两个动作 × 两个方向」一共四次判断，写开就会有第五处漏改。</p>
   */
  /**
   * 把这一页返回的帖子记成曝光（需求 FR5.1 的 30% 采样与 BR3 的日级去重都在
   * {@link UserActionRecorder#recordExposure} 里判，这里不重复一遍）。
   *
   * <p><b>「返回给用户」不等于「用户看见了」</b>：真正的可见性只有前端量得到，
   * 这里记的是「服务端把这条内容交给了他的屏幕」，权重因此取 0.10 而不是浏览的 1.00
   * （需求 §8.2.1）。前端将来接 IntersectionObserver 做真曝光时，改的是这一行的调用时机，
   * 不是权重。</p>
   */
  private void recordExpose(long userId, PageResult<PostListItem> result, String scene) {
    if (result == null || result.getList() == null) {
      return;
    }
    recorder.recordExposure(userId, result.getList().stream().map(PostListItem::id).toList(),
        scene, LocalDateTime.now());
  }

  private void trackSwitch(long userId, long postId, String actionType, boolean on) {
    if (on) {
      recorder.record(userId, actionType, UserActionCatalog.TARGET_POST, postId,
          UserActionCatalog.SCENE_DETAIL, LocalDateTime.now());
    } else {
      recorder.cancel(userId, actionType, UserActionCatalog.TARGET_POST, postId);
    }
  }
}

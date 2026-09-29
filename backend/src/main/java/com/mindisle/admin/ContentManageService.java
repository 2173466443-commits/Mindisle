package com.mindisle.admin;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindisle.admin.AdminOpLogService.Ctx;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.entity.AdminOpLog;
import com.mindisle.entity.AuditRecord;
import com.mindisle.entity.ContentReport;
import com.mindisle.entity.Post;
import com.mindisle.entity.PostAppeal;
import com.mindisle.entity.PostStatusLog;
import com.mindisle.entity.User;
import com.mindisle.mapper.AuditRecordMapper;
import com.mindisle.mapper.ContentReportMapper;
import com.mindisle.mapper.PostAppealMapper;
import com.mindisle.mapper.PostMapper;
import com.mindisle.mapper.PostStatusLogMapper;
import com.mindisle.mapper.UserMapper;
import com.mindisle.notify.NotifyService;

/**
 * 内容管理台与举报处置（手册 §9.1 第 4 条 · 需求 FR4.7 FR7.7 · 任务 T6.5）。
 *
 * <p><b>四类动作共用一条「改状态必留痕」的铁律</b>：{@code post.status} 的每一次真实改写都要落
 * {@code post_status_log}，且 {@code operator_id} 必填（DDL 注释原文「人工处置必填 BR10」）。
 * 留痕与否以 SQL 的影响行为准而不是以请求为准——{@link PostMapper#compareAndSetStatus} 返回 0
 * 说明状态已经被别的通道（机审、申诉、审核台裁决）改走，此时<b>不写日志</b>，
 * 于是「日志条数 = 真实流转次数」这条不变式在所有入口同时成立（与 ReportService、
 * AuditQueueService 三方共识）。</p>
 *
 * <p><b>举报处置不自动改帖子状态之外的任何事</b>：采纳举报＝下架；不采纳＝只办结举报行。
 * 采纳时若帖子已经不在可见态（例如早已 TAKEDOWN），CAS 返回 0，本类仍把举报判为 ACCEPTED
 * 并如实记录 {@code postChanged=false}：举报人的「有没有人理我」与帖子的「现在是什么状态」
 * 是两件事，混在一起就会出现「帖子早被下架、举报永远办结不了」的死锁队列。</p>
 *
 * <p><b>为什么 {@code report_cnt} 不在办结时重算</b>：它数的是「几个人举报过这条内容」
 * （{@code COUNT(DISTINCT reporter_id)}，与 deleted 位和 status 都无关），这个事实将来要喂给
 * 推荐质量分（需求 §8.2.1 举报 = -5）。办结若把它清零，作者被举报过的历史就随着管理员一次
 * 点击消失了，质量分会跟着后台操作漂。</p>
 */
@Service
public class ContentManageService {

  private static final Logger log = LoggerFactory.getLogger(ContentManageService.class);

  /**
   * 管理台可筛选、可处置的帖子状态白名单（真源是 04_community.sql 的 status ENUM）。
   *
   * <p>刻意不含 DRAFT（作者草稿，管理员无权检索其正文）与 DELETED（用户自删或注销冷静期，
   * 由保留期作业负责物理清除，管理台再去「恢复」等于绕过注销流程）。</p>
   */
  static final Set<String> MANAGEABLE_STATUSES = Set.of(
      AuditQueueService.POST_MACHINE_REVIEW, AuditQueueService.POST_HUMAN_REVIEW,
      AuditQueueService.POST_PUBLISHED, AuditQueueService.POST_REJECTED,
      "APPEALING", AuditQueueService.POST_TAKEDOWN);

  /** 举报处理状态白名单，同 {@link ContentReport} 的三个常量。 */
  static final Set<String> REPORT_STATUSES = Set.of(ContentReport.STATUS_PENDING,
      ContentReport.STATUS_ACCEPTED, ContentReport.STATUS_REJECTED);

  static final String FLAG_TOP = "top";
  static final String FLAG_FEATURE = "feature";
  static final Set<String> FLAGS = Set.of(FLAG_TOP, FLAG_FEATURE);

  /** post_status_log.reason 是 VARCHAR(255)，与审核队列共用同一个上限常量。 */
  static final int REASON_MAX = AuditQueueService.STATUS_LOG_REASON_MAX;
  /** content_report.result_note 是 VARCHAR(500)。 */
  static final int NOTE_MAX = 500;
  static final int KEYWORD_MAX = 64;

  /** 下架后作者能看到的那句「你还能申诉一次」由 NotifyService 统一措辞，本类不复制文案。 */
  private final PostMapper postMapper;
  private final PostStatusLogMapper statusLogMapper;
  private final ContentReportMapper reportMapper;
  private final PostAppealMapper appealMapper;
  private final AuditRecordMapper auditRecordMapper;
  private final UserMapper userMapper;
  private final NotifyService notifyService;
  private final AdminOpLogService opLogService;

  public ContentManageService(PostMapper postMapper, PostStatusLogMapper statusLogMapper,
      ContentReportMapper reportMapper, PostAppealMapper appealMapper,
      AuditRecordMapper auditRecordMapper, UserMapper userMapper,
      NotifyService notifyService, AdminOpLogService opLogService) {
    this.postMapper = postMapper;
    this.statusLogMapper = statusLogMapper;
    this.reportMapper = reportMapper;
    this.appealMapper = appealMapper;
    this.auditRecordMapper = auditRecordMapper;
    this.userMapper = userMapper;
    this.notifyService = notifyService;
    this.opLogService = opLogService;
  }

  // ================================================================ 帖子检索与处置

  /** 一次状态动作的结果：{@code changed=false} 表示状态没动（并发抢占或本来就是目标态）。 */
  public record ActionOutcome(long postId, String fromStatus, String toStatus, boolean changed,
      Long opLogId) {
  }

  /** 置顶/加精的返回：{@code on} 是写进去之后的值，前端据此翻转按钮。 */
  public record FlagOutcome(long postId, String flag, boolean on, Long opLogId) {
  }

  /** FR7.7「任一帖可导出完整处置链」：一次请求把五张表的事实按时间摊平。 */
  public record Chain(Post post, String authorNickname, List<PostStatusLog> statusLogs,
      List<AuditRecord> auditRecords, List<ContentReport> reports, List<PostAppeal> appeals) {
  }

  /**
   * 帖子列表：keyword 命中标题或正文，status 过白名单。
   *
   * <p>筛错状态在这里当场报错而不是返回空列表：空列表会被管理员读成「社区里没有下架帖」，
   * 而真实原因是他选了个 ENUM 里根本没有的值。</p>
   */
  public PageResult<Post> pagePosts(String keyword, String status, Long userId, PageQuery query) {
    String statusFilter = requireIn(status, MANAGEABLE_STATUSES, "帖子状态");
    String rawWord = AuditQueueService.blankToNull(keyword);
    String word = rawWord == null ? null
        : rawWord.substring(0, Math.min(rawWord.length(), KEYWORD_MAX));
    PageQuery q = query.normalize();
    LambdaQueryWrapper<Post> base = new LambdaQueryWrapper<Post>()
        .eq(statusFilter != null, Post::getStatus, statusFilter)
        .eq(userId != null, Post::getUserId, userId)
        .ne(Post::getStatus, "DRAFT")
        .ne(Post::getStatus, "DELETED")
        .and(word != null, w -> w.like(Post::getTitle, word).or().like(Post::getContent, word))
        .orderByDesc(Post::getId);
    long total = postMapper.selectCount(base.clone());
    List<Post> list = postMapper.selectList(
        base.last("limit " + q.getSize() + " offset " + q.offset()));
    return PageResult.of(list, total, q);
  }

  /** 管理台可见的帖子：只有 DELETED（含用户自删）算不存在，DRAFT 也不给看正文。 */
  public Post requirePost(long postId) {
    Post post = postMapper.selectById(postId);
    if (post == null || "DELETED".equals(post.getStatus())) {
      throw new BizException(ErrorCode.POST_NOT_FOUND);
    }
    return post;
  }

  /** 下架（FR4.7 处置动作之一）：理由必填，它会原样进通知、状态日志与审计。 */
  @Transactional
  public ActionOutcome takedown(long postId, String reason, Ctx ctx, LocalDateTime now) {
    return transition(postId, reason, ctx, now, AuditQueueService.POST_TAKEDOWN,
        AdminOpLog.ACTION_POST_TAKEDOWN, true);
  }

  /**
   * 恢复可见：只允许 TAKEDOWN → PUBLISHED。
   *
   * <p>为什么不许把 REJECTED 的帖子直接「恢复」成 PUBLISHED：那条帖子是人审判定违规的，
   * 让它重新可见的正确入口是申诉（{@link AppealService}），走申诉才会同时留下
   * {@code post_appeal} 的终态与回执。管理员在这里点一下就越过了作者的那一次申诉机会，
   * 也越过了 audit_record 的留痕。</p>
   */
  @Transactional
  public ActionOutcome restore(long postId, String reason, Ctx ctx, LocalDateTime now) {
    Post post = requirePost(postId);
    if (!AuditQueueService.POST_TAKEDOWN.equals(post.getStatus())) {
      throw new BizException(ErrorCode.FORBIDDEN,
          "只有已下架的帖子能恢复可见，当前状态是 " + post.getStatus() + "（驳回帖请走申诉队列）");
    }
    return transition(postId, reason, ctx, now, AuditQueueService.POST_PUBLISHED,
        AdminOpLog.ACTION_POST_RESTORE, false);
  }

  /**
   * 状态迁移的公共骨架：CAS 改状态 → 有影响行才写状态日志 → 通知作者 → 无论成败都写审计。
   *
   * @param notify 恢复时也要通知作者（{@code takenDown=false} 那条文案），下架时同理
   */
  private ActionOutcome transition(long postId, String reason, Ctx ctx, LocalDateTime now,
      String toStatus, String action, boolean takenDown) {
    long operatorId = requireOperator(ctx);
    String trimmed = AuditQueueService.blankToNull(reason);
    if (trimmed == null) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "处置必须填写理由，理由会同时给作者、状态留痕与审计");
    }
    Post post = requirePost(postId);
    String fromStatus = post.getStatus();
    if (toStatus.equals(fromStatus)) {
      throw new BizException(ErrorCode.FORBIDDEN, "帖子已经处于目标状态：" + fromStatus);
    }
    if ("DRAFT".equals(fromStatus)) {
      throw new BizException(ErrorCode.FORBIDDEN, "草稿只有作者本人能操作，管理台不介入");
    }
    boolean changed = postMapper.compareAndSetStatus(postId, fromStatus, toStatus) == 1;
    if (changed) {
      statusLogMapper.insert(statusLog(postId, fromStatus, toStatus, operatorId, trimmed, now));
      if (post.getUserId() != null) {
        notifyService.notifyContentAction(post.getUserId(), postId, takenDown, trimmed);
      }
    }
    Long opLogId = opLogService.success(ctx, action, "post:" + postId, postId,
        "from=" + fromStatus + "|to=" + toStatus + "|改状态=" + changed
            + "|理由=" + AuditQueueService.cut(trimmed, 120));
    if (!changed) {
      log.info("帖子 {} 状态已被其它通道改走（期望 {}），本次 {} 只留痕", postId, fromStatus, action);
    }
    return new ActionOutcome(postId, fromStatus, toStatus, changed, opLogId);
  }

  /**
   * 置顶 / 加精（闭手册 §3.8②：{@code post.is_top}、{@code is_featured} 建表后一直没人写）。
   *
   * <p>这两个开关不改状态，所以<b>故意不写 {@code post_status_log}</b>：那张表的语义是
   * 「状态机流转」，把「加精」塞进去会让 FR7.3「四态流转日志完整」的对账变成一堆噪声。
   * 审计仍照写，谁点的、什么时候点的，{@code admin_op_log} 里有。</p>
   */
  @Transactional
  public FlagOutcome flag(long postId, String flag, boolean on, Ctx ctx) {
    requireOperator(ctx);
    String key = requireFlag(flag);
    Post post = requirePost(postId);
    Post patch = new Post();
    patch.setId(post.getId());
    if (FLAG_TOP.equals(key)) {
      patch.setIsTop(on ? 1 : 0);
    } else {
      patch.setIsFeatured(on ? 1 : 0);
    }
    postMapper.updateById(patch);
    String action = FLAG_TOP.equals(key) ? AdminOpLog.ACTION_POST_TOP : AdminOpLog.ACTION_POST_FEATURE;
    Long opLogId = opLogService.success(ctx, action, "post:" + postId, postId,
        key + "=" + (on ? "on" : "off"));
    return new FlagOutcome(postId, key, on, opLogId);
  }

  // ================================================================ 举报队列

  /** 举报行 + 举报人昵称 + 被举报帖标题（列表页一次给全，省掉前端逐行补详情）。 */
  public record ReportView(ContentReport report, String reporterNickname, String postTitle) {
  }

  /** 举报处置结果：{@code postChanged} 与 {@code reportHandled} 分开，见类注释。 */
  public record ReportOutcome(ContentReport report, boolean postChanged, String postStatus,
      Long opLogId) {
  }

  /**
   * 举报队列：默认 PENDING 在前、其余按 id 倒序。
   *
   * <p>{@code targetType} 为 comment 的行不参与下架——评论的处置是「折叠/删除」，本期没有入口，
   * 所以服务层在 {@link #handleReport} 里拒绝并说明原因，而不是让管理员点了没反应。</p>
   */
  public PageResult<ReportView> pageReports(String status, String targetType, PageQuery query) {
    String statusFilter = requireIn(status, REPORT_STATUSES, "举报状态");
    String typeFilter = AuditQueueService.blankToNull(targetType);
    if (typeFilter != null && !ContentReport.TARGET_POST.equals(typeFilter)
        && !ContentReport.TARGET_COMMENT.equals(typeFilter)) {
      throw new BizException(ErrorCode.PARAM_INVALID, "举报对象只能是 post/comment");
    }
    PageQuery q = query.normalize();
    LambdaQueryWrapper<ContentReport> base = new LambdaQueryWrapper<ContentReport>()
        .eq(statusFilter != null, ContentReport::getStatus, statusFilter)
        .eq(typeFilter != null, ContentReport::getTargetType, typeFilter)
        .orderByAsc(ContentReport::getStatus)
        .orderByDesc(ContentReport::getId);
    long total = reportMapper.selectCount(base.clone());
    List<ContentReport> rows = reportMapper.selectList(
        base.last("limit " + q.getSize() + " offset " + q.offset()));
    Set<Long> userIds = new LinkedHashSet<>();
    Set<Long> postIds = new LinkedHashSet<>();
    for (ContentReport row : rows) {
      if (row.getReporterId() != null) {
        userIds.add(row.getReporterId());
      }
      if (row.getPostId() != null) {
        postIds.add(row.getPostId());
      }
    }
    List<ReportView> list = new ArrayList<>(rows.size());
    for (ContentReport row : rows) {
      list.add(new ReportView(row, nickname(userIds, row.getReporterId()),
          row.getPostId() == null ? null : title(postIds, row.getPostId())));
    }
    return PageResult.of(list, total, q);
  }

  /** 待办举报数（A3 工作台与侧边栏红点共用一个数，避免两处口径打架）。 */
  public long pendingReportCount() {
    Long cnt = reportMapper.selectCount(new LambdaQueryWrapper<ContentReport>()
        .eq(ContentReport::getStatus, ContentReport.STATUS_PENDING));
    return cnt == null ? 0L : cnt;
  }

  /**
   * 办结一条举报：采纳则下架被举报的帖子，两者都回执给举报人。
   *
   * <p>顺序固定为「CAS 办结举报 → 改帖状态 → 通知 → 审计」。举报行先落地是刻意的：
   * 如果反过来先下架、后办结，下架成功而办结失败（例如帖子状态被别人改走导致回滚判断出错），
   * 队列里会留下一条「内容已经没了却还在等人处理」的僵尸行，重跑时又会下架一次。</p>
   */
  @Transactional
  public ReportOutcome handleReport(long reportId, boolean accepted, String note, Ctx ctx,
      LocalDateTime now) {
    long operatorId = requireOperator(ctx);
    ContentReport report = reportMapper.selectById(reportId);
    if (report == null || Integer.valueOf(1).equals(report.getDeleted())) {
      throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "举报记录不存在：id=" + reportId);
    }
    if (!ContentReport.TARGET_POST.equals(report.getTargetType())) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "本期只开放帖子举报处置；评论举报的折叠动作没有入口，请先忽略它");
    }
    String trimmed = AuditQueueService.blankToNull(note);
    if (trimmed == null) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "处置说明必填，它会作为回执原文发给举报人");
    }
    String toStatus = accepted ? ContentReport.STATUS_ACCEPTED : ContentReport.STATUS_REJECTED;
    if (reportMapper.handle(reportId, operatorId, toStatus,
        AuditQueueService.cut(trimmed, NOTE_MAX), now) == 0) {
      opLogService.denied(ctx, AdminOpLog.ACTION_REPORT_HANDLE, "report:" + reportId, reportId,
          "该举报已办结：" + report.getStatus());
      throw new BizException(ErrorCode.FORBIDDEN, "这条举报已经有人办过了，请刷新队列");
    }
    boolean postChanged = false;
    String postStatus = null;
    Long postId = report.getPostId() == null ? report.getTargetId() : report.getPostId();
    if (accepted && postId != null) {
      Post post = postMapper.selectById(postId);
      if (post != null && !AuditQueueService.POST_TAKEDOWN.equals(post.getStatus())
          && !"DELETED".equals(post.getStatus()) && !"DRAFT".equals(post.getStatus())) {
        postStatus = post.getStatus();
        postChanged = postMapper.compareAndSetStatus(postId, postStatus,
            AuditQueueService.POST_TAKEDOWN) == 1;
        if (postChanged) {
          statusLogMapper.insert(statusLog(postId, postStatus,
              AuditQueueService.POST_TAKEDOWN, operatorId,
              "举报采纳|" + trimmed, now));
          if (post.getUserId() != null) {
            notifyService.notifyContentAction(post.getUserId(), postId, true, trimmed);
          }
        }
      } else if (post != null) {
        postStatus = post.getStatus();
      }
    }
    if (report.getReporterId() != null) {
      notifyService.notifyReportResult(report.getReporterId(), reportId,
          postId == null ? 0L : postId, accepted, trimmed);
    }
    ContentReport handled = reportMapper.selectById(reportId);
    Long opLogId = opLogService.success(ctx, AdminOpLog.ACTION_REPORT_HANDLE,
        "report:" + reportId, reportId,
        "result=" + toStatus + "|post=" + postId + "|下架=" + postChanged
            + "|说明=" + AuditQueueService.cut(trimmed, 100));
    return new ReportOutcome(handled, postChanged, postStatus, opLogId);
  }

  // ================================================================ FR7.7 处置链

  /**
   * 一个帖子的完整处置链（FR7.7）：状态流转 + 机审/人审留痕 + 举报 + 申诉。
   *
   * <p>五张表全部按 {@code created_at} 升序返回，前端直接铺成一条时间线。<b>不排序合并</b>是
   * 有意的：各表的时钟列精度与语义不同（audit_record.created_at 是引擎判定时刻，
   * post_status_log 是改写时刻），前端按类别分栏看比把五条流拧成一条更容易看出「谁先动的手」。</p>
   */
  public Chain chain(long postId) {
    Post post = requirePost(postId);
    List<PostStatusLog> statusLogs = statusLogMapper.listByPost(postId);
    List<AuditRecord> records = auditRecordMapper.selectList(
        new LambdaQueryWrapper<AuditRecord>()
            .eq(AuditRecord::getTargetType, AuditQueueService.TARGET_POST)
            .eq(AuditRecord::getTargetId, postId)
            .orderByAsc(AuditRecord::getCreatedAt));
    List<ContentReport> reports = reportMapper.selectList(
        new LambdaQueryWrapper<ContentReport>()
            .eq(ContentReport::getTargetType, ContentReport.TARGET_POST)
            .eq(ContentReport::getTargetId, postId)
            .orderByAsc(ContentReport::getCreatedAt));
    List<PostAppeal> appeals = appealMapper.listByPost(postId);
    return new Chain(post, nicknameSingle(post.getUserId()), statusLogs, records, reports, appeals);
  }

  // ================================================================ 工具

  private PostStatusLog statusLog(long postId, String from, String to, long operatorId,
      String reason, LocalDateTime now) {
    PostStatusLog row = new PostStatusLog();
    row.setPostId(postId);
    row.setFromStatus(from);
    row.setToStatus(to);
    row.setOperatorId(operatorId);
    row.setReason(AuditQueueService.cut(reason, REASON_MAX));
    row.setCreatedAt(now);
    return row;
  }

  private static String requireFlag(String flag) {
    String key = AuditQueueService.blankToNull(flag);
    if (key == null || !FLAGS.contains(key)) {
      throw new BizException(ErrorCode.PARAM_INVALID, "flag 只能是 top/feature");
    }
    return key;
  }

  private static long requireOperator(Ctx ctx) {
    if (ctx == null || ctx.operatorId() == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED, "管理端操作需要登录身份");
    }
    return ctx.operatorId();
  }

  private static String requireIn(String value, Set<String> allowed, String label) {
    String trimmed = AuditQueueService.blankToNull(value);
    if (trimmed != null && !allowed.contains(trimmed)) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          label + "只能是 " + String.join("/", allowed.stream().sorted().toList()));
    }
    return trimmed;
  }

  private String nickname(Set<Long> ids, Long userId) {
    return userId == null ? null : (ids.contains(userId) ? nicknameSingle(userId) : null);
  }

  private String nicknameSingle(Long userId) {
    if (userId == null) {
      return null;
    }
    User user = userMapper.selectById(userId);
    return user == null ? null : user.getNickname();
  }

  private String title(Set<Long> ids, Long postId) {
    if (postId == null || !ids.contains(postId)) {
      return null;
    }
    Post post = postMapper.selectById(postId);
    return post == null ? null : post.getTitle();
  }
}
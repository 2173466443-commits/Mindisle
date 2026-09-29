package com.mindisle.admin;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mindisle.admin.AdminOpLogService.Ctx;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.entity.AdminOpLog;
import com.mindisle.entity.Post;
import com.mindisle.entity.PostAppeal;
import com.mindisle.entity.PostStatusLog;
import com.mindisle.mapper.PostAppealMapper;
import com.mindisle.mapper.PostMapper;
import com.mindisle.mapper.PostStatusLogMapper;
import com.mindisle.notify.NotifyService;

/**
 * 帖子申诉：作者提交 + 管理端裁定（手册 §9.1 第 6 条 · 需求 FR4.5 FR7.6 · 任务 T6.7）。
 *
 * <p><b>「一次性申诉」是硬约束，不是提示</b>：{@code countFinalByPost >= 1} 就当众拒绝。
 * 一个帖子反复申诉会把队列变成情绪出口，而社区规范需要的是一条能查证的结论。
 * 与之配套的是 {@code uk_post_pending}：同一条帖子同一时刻只允许一条 PENDING。</p>
 *
 * <p><b>申诉期间帖子进入 {@code APPEALING}</b>——这是 04_community.sql 状态机里唯一由作者驱动的
 * 转移，也解释了为什么管理台的 {@code MANAGEABLE_STATUSES} 要含它：作者按下申诉的那一刻，
 * 管理员必须能在队列里看见「有人在喊冤」，否则这条状态就是死态。</p>
 *
 * <p><b>驳回时回到「申诉前的那个状态」而不是写死 REJECTED</b>：被下架的帖子和被驳回的帖子
 * 是两种不同的事，前者是人工处置、后者是机审/人审判定。前一个状态从
 * {@code post_status_log} 里查（{@code to_status='APPEALING'} 的最后一条的 {@code from_status}），
 * 于是「状态链」既是留痕也是事实来源，不需要在 post_appeal 上再加一列 snapshot。</p>
 *
 * <p>DDL 注释里「通过则帖子回到 MACHINE_REVIEW 重审」已过期：V1 的实现是回到
 * {@link PostAppeal#POST_RESTORE_STATUS}（PUBLISHED），以实体常量为准，改 SQL 注释不如改文档。</p>
 */
@Service
public class AppealService {

  private static final Logger log = LoggerFactory.getLogger(AppealService.class);

  /** 只有这两种状态能被作者申诉：DRAFT/MACHINE_REVIEW/HUMAN_REVIEW 还没对外可见，无申诉对象。 */
  static final Set<String> APPEALABLE_STATUSES = Set.of(
      AuditQueueService.POST_REJECTED, AuditQueueService.POST_TAKEDOWN);
  static final String POST_APPEALING = "APPEALING";

  /** post_appeal.reason / result_note 都是 VARCHAR(500)。 */
  static final int REASON_MAX = 500;
  static final int NOTE_MAX = 500;

  /** 状态机白名单：申诉队列只处理这三种，筛别的值当场报错而不是返回空列表。 */
  static final Set<String> APPEAL_STATUSES = Set.of(PostAppeal.STATUS_PENDING,
      PostAppeal.STATUS_ACCEPTED, PostAppeal.STATUS_REJECTED);

  private final PostAppealMapper appealMapper;
  private final PostMapper postMapper;
  private final PostStatusLogMapper statusLogMapper;
  private final NotifyService notifyService;
  private final AdminOpLogService opLogService;

  public AppealService(PostAppealMapper appealMapper, PostMapper postMapper,
      PostStatusLogMapper statusLogMapper, NotifyService notifyService,
      AdminOpLogService opLogService) {
    this.appealMapper = appealMapper;
    this.postMapper = postMapper;
    this.statusLogMapper = statusLogMapper;
    this.notifyService = notifyService;
    this.opLogService = opLogService;
  }

  /** 队列里的一行：申诉本体 + 帖子标题 + 帖子当前状态（裁定前管理员要看这两样才敢点）。 */
  public record AppealView(PostAppeal appeal, String postTitle, String postStatus) {
  }

  /** 裁定结果：{@code postChanged=false} 表示帖子状态已被别的通道改走，只落了申诉终态与回执。 */
  public record Adjudication(long appealId, long postId, String appealStatus, String postStatus,
      boolean postChanged, Long opLogId) {
  }

  // ================================================================ 作者侧

  /**
   * 提交申诉（FR4.5）。作者本人、可申诉状态、没有历史终态、没有并行 PENDING——四道门全过才落库。
   *
   * <p>校验顺序刻意把「是不是你的帖子」放在最前：状态与申诉次数属于帖子治理信息，
   * 让旁人先探到「这条帖子已经被驳回过」等于把处置历史泄露给无关的人。</p>
   */
  @Transactional
  public AppealView submit(long postId, long userId, String reason, LocalDateTime now) {
    Post post = postMapper.selectById(postId);
    if (post == null || "DELETED".equals(post.getStatus())) {
      throw new BizException(ErrorCode.POST_NOT_FOUND);
    }
    if (!post.getUserId().equals(userId)) {
      throw new BizException(ErrorCode.POST_FORBIDDEN, "只能申诉自己的帖子");
    }
    if (!APPEALABLE_STATUSES.contains(post.getStatus())) {
      throw new BizException(ErrorCode.FORBIDDEN,
          "当前状态不能申诉：" + post.getStatus() + "（只有被驳回或被下架的帖子可以）");
    }
    String trimmed = AuditQueueService.blankToNull(reason);
    if (trimmed == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "申诉必须写清楚理由");
    }
    long finals = appealMapper.countFinalByPost(postId);
    if (finals >= 1) {
      throw new BizException(ErrorCode.FORBIDDEN, "这条帖子已经申诉过一次，社区规范只给一次申诉机会");
    }
    PostAppeal pending = appealMapper.findPending(postId);
    if (pending != null) {
      throw new BizException(ErrorCode.FORBIDDEN, "已经有一条申诉正在处理中，请耐心等待结果");
    }
    PostAppeal row = new PostAppeal();
    row.setPostId(postId);
    row.setUserId(userId);
    row.setReason(AuditQueueService.cut(trimmed, REASON_MAX));
    row.setStatus(PostAppeal.STATUS_PENDING);
    row.setDeleted(0);
    row.setCreatedAt(now);
    row.setUpdatedAt(now);
    appealMapper.insert(row);

    boolean changed = postMapper.compareAndSetStatus(postId, post.getStatus(), POST_APPEALING) == 1;
    if (changed) {
      statusLogMapper.insert(statusLog(postId, post.getStatus(), POST_APPEALING, userId,
          "作者申诉|" + AuditQueueService.cut(trimmed, 100), now));
    } else {
      log.info("帖子 {} 在申诉提交的窗口期内被其它通道改状态，申诉单照常受理", postId);
    }
    return new AppealView(row, post.getTitle(), changed ? POST_APPEALING : post.getStatus());
  }

  /** FR7.6「申诉可回溯」：作者与管理员都能按帖子翻出全部申诉历史（含已办结的）。 */
  public List<PostAppeal> historyOfPost(long postId) {
    return appealMapper.listByPost(postId);
  }

  // ================================================================ 管理侧

  /** 申诉队列：PENDING 置顶，同状态内新单在前（排序在 mapper 的 SQL 里，不在 Java 补）。 */
  public PageResult<AppealView> page(String status, PageQuery query) {
    String filter = AuditQueueService.blankToNull(status);
    if (filter != null && !APPEAL_STATUSES.contains(filter)) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "申诉状态只能是 " + String.join("/", APPEAL_STATUSES.stream().sorted().toList()));
    }
    PageQuery q = query.normalize();
    long total = appealMapper.countAppeals(filter);
    List<PostAppeal> rows = appealMapper.pageAppeals(filter, q.offset(), q.getSize());
    List<AppealView> list = new ArrayList<>(rows.size());
    for (PostAppeal row : rows) {
      Post post = row.getPostId() == null ? null : postMapper.selectById(row.getPostId());
      list.add(new AppealView(row, post == null ? null : post.getTitle(),
          post == null ? null : post.getStatus()));
    }
    return PageResult.of(list, total, q);
  }

  public PostAppeal require(long appealId) {
    PostAppeal row = appealMapper.selectById(appealId);
    if (row == null || Integer.valueOf(1).equals(row.getDeleted())) {
      throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "申诉记录不存在：id=" + appealId);
    }
    return row;
  }

  /**
   * 裁定申诉：一次事务里做完「申诉终态 + 帖子状态 + 状态留痕 + 回执 + 审计」五件事。
   *
   * <p>采纳＝{@code APPEALING → PUBLISHED}；驳回＝回到申诉前那个状态（见类注释）。
   * 帖子状态改写失败不影响申诉办结——理由与 {@link ContentManageService#handleReport} 相同：
   * 申诉人的「有没有结论」必须确定性拿到，帖子状态则听状态机的。</p>
   */
  @Transactional
  public Adjudication adjudicate(long appealId, Ctx ctx, boolean accepted, String note,
      LocalDateTime now) {
    long operatorId = requireOperator(ctx);
    String trimmed = AuditQueueService.blankToNull(note);
    if (trimmed == null) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "裁定必须填写说明，说明会作为回执原文发给申诉人");
    }
    PostAppeal appeal = require(appealId);
    String toStatus = accepted ? PostAppeal.STATUS_ACCEPTED : PostAppeal.STATUS_REJECTED;
    if (appealMapper.adjudicate(appealId, operatorId, toStatus,
        AuditQueueService.cut(trimmed, NOTE_MAX), now) == 0) {
      opLogService.denied(ctx, AdminOpLog.ACTION_APPEAL_HANDLE, "appeal:" + appealId, appealId,
          "该申诉已办结：" + appeal.getStatus());
      throw new BizException(ErrorCode.FORBIDDEN, "这条申诉已经被裁定过了，请刷新队列");
    }
    long postId = appeal.getPostId();
    Post post = postMapper.selectById(postId);
    String fromStatus = post == null ? null : post.getStatus();
    String target = accepted ? PostAppeal.POST_RESTORE_STATUS : statusBeforeAppeal(postId, fromStatus);
    boolean postChanged = false;
    if (post != null && target != null && !target.equals(fromStatus)) {
      postChanged = postMapper.compareAndSetStatus(postId, fromStatus, target) == 1;
      if (postChanged) {
        statusLogMapper.insert(statusLog(postId, fromStatus, target, operatorId,
            "申诉" + (accepted ? "采纳" : "驳回") + "|" + AuditQueueService.cut(trimmed, 100), now));
        // 申诉采纳＝这条帖子第一次真正发布。灰词帖从 HUMAN_REVIEW 被驳回时 published_at 一直是
        // NULL（转入人审不是发布），恢复上架若只改状态，它会以「已发布但没有发布时间」的形态
        // 沉在广场游标末尾、被推荐新帖池跳过——与 AuditQueueService 人审通过那条是同一个坑，
        // 所以同一把幂等闸门（stampPublishedAt 只在 published_at IS NULL 时写入）在这里复用。
        if (accepted) {
          postMapper.stampPublishedAt(postId, now);
        }
      }
    }
    if (appeal.getUserId() != null) {
      notifyService.notifyAppealResult(appeal.getUserId(), postId, accepted, trimmed);
    }
    Long opLogId = opLogService.success(ctx, AdminOpLog.ACTION_APPEAL_HANDLE,
        "appeal:" + appealId, appealId,
        "post=" + postId + "|result=" + toStatus + "|帖子=" + target + "|改状态=" + postChanged
            + "|说明=" + AuditQueueService.cut(trimmed, 100));
    return new Adjudication(appealId, postId, toStatus, target, postChanged, opLogId);
  }

  /** 待办申诉数（A3 工作台红点）。 */
  public long pendingCount() {
    return appealMapper.countAppeals(PostAppeal.STATUS_PENDING);
  }

  /** 从状态留痕里找回「进 APPEALING 之前是什么状态」；查不到就回落 REJECTED，宁严不松。 */
  private String statusBeforeAppeal(long postId, String currentStatus) {
    if (!POST_APPEALING.equals(currentStatus)) {
      return currentStatus;
    }
    List<PostStatusLog> logs = statusLogMapper.listByPost(postId);
    for (int i = logs.size() - 1; i >= 0; i--) {
      PostStatusLog row = logs.get(i);
      if (POST_APPEALING.equals(row.getToStatus()) && row.getFromStatus() != null) {
        return row.getFromStatus();
      }
    }
    log.warn("帖子 {} 处于 APPEALING 但找不到进入前的状态留痕，按 REJECTED 收口", postId);
    return AuditQueueService.POST_REJECTED;
  }

  private PostStatusLog statusLog(long postId, String from, String to, Long operatorId,
      String reason, LocalDateTime now) {
    PostStatusLog row = new PostStatusLog();
    row.setPostId(postId);
    row.setFromStatus(from);
    row.setToStatus(to);
    row.setOperatorId(operatorId);
    row.setReason(AuditQueueService.cut(reason, AuditQueueService.STATUS_LOG_REASON_MAX));
    row.setCreatedAt(now);
    return row;
  }

  private static long requireOperator(Ctx ctx) {
    if (ctx == null || ctx.operatorId() == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED, "管理端操作需要登录身份");
    }
    return ctx.operatorId();
  }
}
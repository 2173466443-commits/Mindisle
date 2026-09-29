package com.mindisle.admin;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindisle.admin.AdminOpLogService.Ctx;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.entity.AdminOpLog;
import com.mindisle.entity.AnonymousAlias;
import com.mindisle.entity.Post;
import com.mindisle.entity.User;
import com.mindisle.mapper.AnonymousAliasMapper;
import com.mindisle.mapper.PostMapper;
import com.mindisle.mapper.UserMapper;
import com.mindisle.notify.NotifyService;

/**
 * 用户处置与匿名解匿（任务 T6.4 / T6.5 · 手册 §9.1 第 5 条 · 需求 FR8.3 FR8.4）。
 *
 * <p><b>禁言不是「不让登录」</b>（FR8.3 是可测的三条：禁言后发帖 403、阅读仍可用、点赞仍可用）。
 * 真正的判据在 PostingQuotaService.statusOf，本类只负责把 user.status / mute_until 写成事实。
 * 阶段 5 那次踩坑（登录闸门把 MUTED 直接挡在门外，导致下面所有分支变成死代码）说明：
 * 处置的粒度必须落在「写操作」上，落在「登录」上就等于把用户从社区里抹掉了——
 * 对一个正处于危机期的用户，这是最坏的处置。</p>
 *
 * <p><b>解匿是全站最高敏感度的读操作</b>：它读的是「这个匿名发帖人是谁」，一旦泄漏，
 * 匿名机制本身就不成立了。因此三重门一道不能少——只有 SUPER 角色、理由必填、100% 写审计，
 * 并且把审计行 id 回写进 anonymous_alias.revealed_log_id，形成「谁在哪一次、因为什么、看了谁」
 * 的双向可查。前端还要有一行红字提醒（见 admin 的 UsersView），文案与这里同源。</p>
 */
@Service
public class UserManageService {

  /** 可选禁言时长（手册 §9.2 A6 的三个按钮，不做任意天数：任意值会让「按比例处置」失去可比性）。 */
  static final Set<Integer> MUTE_DAYS = Set.of(1, 7, 30);

  static final String STATUS_ACTIVE = "ACTIVE";
  static final String STATUS_MUTED = "MUTED";
  static final String STATUS_BANNED = "BANNED";
  static final String STATUS_DELETED = "DELETED";

  private static final Set<String> STATUSES =
      Set.of(STATUS_ACTIVE, STATUS_MUTED, STATUS_BANNED, STATUS_DELETED);

  /** 只有超级管理员能解匿（需求 §12 规范 5；ADMIN 也不行）。 */
  static final String ROLE_SUPER = "SUPER";

  /** 检索关键词与审计理由的软上限：超长文本进 detail（TEXT 列）没意义，还会淹没关键信息。 */
  static final int REASON_MAX = 500;
  static final int KEYWORD_MAX = 64;

  private final UserMapper userMapper;
  private final PostMapper postMapper;
  private final AnonymousAliasMapper aliasMapper;
  private final NotifyService notifyService;
  private final AdminOpLogService opLogService;
  private final TicketService ticketService;

  public UserManageService(UserMapper userMapper, PostMapper postMapper,
                           AnonymousAliasMapper aliasMapper, NotifyService notifyService,
                           AdminOpLogService opLogService, TicketService ticketService) {
    this.userMapper = userMapper;
    this.postMapper = postMapper;
    this.aliasMapper = aliasMapper;
    this.notifyService = notifyService;
    this.opLogService = opLogService;
    this.ticketService = ticketService;
  }

  // ================================================================ 检索与详情

  /** 用户列表：keyword 命中用户名或昵称，status 过白名单（筛错状态的「空列表」会被当成「没有这类账号」）。 */
  public PageResult<User> page(String keyword, String status, PageQuery query) {
    String statusFilter = AuditQueueService.blankToNull(status);
    if (statusFilter != null && !STATUSES.contains(statusFilter)) {
      throw new BizException(ErrorCode.PARAM_INVALID, "账号状态只能是 " + String.join("/",
          STATUSES.stream().sorted().toList()));
    }
    String word = AuditQueueService.blankToNull(keyword);
    if (word != null) {
      // 按码点截，不按 UTF-16 单元：substring(0, 64) 会把第 64 位上的 emoji 劈成半个代理对，
      // 传进 LIKE 就是一个非法字符（MySQL 侧报 Incorrect string value 或静默变成 ?），
      // 而管理员搜「带表情的昵称」恰恰是最常见的输入之一。cut() 与本文件理由截断共用同一份规则。
      word = AuditQueueService.cut(word, KEYWORD_MAX);
    }
    final String kw = word;
    PageQuery q = query.normalize();
    LambdaQueryWrapper<User> base = new LambdaQueryWrapper<User>()
        .eq(statusFilter != null, User::getStatus, statusFilter)
        .and(kw != null, w -> w.like(User::getUsername, kw).or().like(User::getNickname, kw))
        .orderByDesc(User::getId);
    long total = userMapper.selectCount(base.clone());
    List<User> list = userMapper.selectList(base.last("limit " + q.getSize()
        + " offset " + q.offset()));
    return PageResult.of(list, total, q);
  }

  /** 详情抽屉：账号本体 + 公开帖数 + 被点赞总数 + 危机工单时间线 + 用过的匿名别名。 */
  public record UserDetail(User user, long publicPostCnt, long receivedLikeCnt,
                           List<?> tickets, List<AnonymousAlias> aliases) {
  }

  public UserDetail detail(long userId) {
    User user = require(userId);
    long posts = postMapper.countPublicPosts(userId);
    long likes = postMapper.sumReceivedLikes(userId);
    return new UserDetail(user, posts, likes, ticketService.timeline(userId, 20),
        aliasMapper.listByUser(userId));
  }

  public User require(long userId) {
    User user = userMapper.selectById(userId);
    if (user == null) {
      throw new BizException(ErrorCode.USER_NOT_FOUND);
    }
    return user;
  }

  // ================================================================ 处置

  /**
   * 禁言（FR8.3）：写 status=MUTED 与 mute_until，并立刻通知当事人影响范围。
   *
   * <p>mute_until 是「库里的事实」，PostingQuotaService 的自愈判据读它；两者谁都不能少——
   * 只有 status 没有 mute_until，禁言就变成永久；只有 mute_until 没有 status，写闸门不生效。</p>
   */
  @Transactional
  public User mute(long userId, int days, String reason, Ctx ctx, LocalDateTime now) {
    if (!MUTE_DAYS.contains(days)) {
      throw new BizException(ErrorCode.PARAM_INVALID, "禁言时长只能是 1/7/30 天");
    }
    String cutReason = requireReason(reason, ctx, AdminOpLog.ACTION_MUTE_USER, "user:" + userId, userId);
    User user = require(userId);
    assertNotDeleted(user);
    LocalDateTime until = now.plusDays(days);
    user.setStatus(STATUS_MUTED);
    user.setMuteUntil(until);
    user.setUpdatedAt(now);
    userMapper.updateById(user);
    notifyService.notifyAccountAction(userId, "你的账号已被限制发布 " + days + " 天",
        "期间你仍可浏览社区、点赞与收藏；不能发帖、评论、发私信。依据：" + cutReason
            + "。到期自动恢复，如需申诉请在个人中心提工单。");
    opLogService.success(ctx, AdminOpLog.ACTION_MUTE_USER, "user:" + userId, userId,
        "禁言 " + days + "天 至 " + until + "|理由=" + cutReason);
    return require(userId);
  }

  /** 封禁：写操作全部拒绝，登录仍然放行（FR8.3 的「可阅读」口径不适用于 BANNED，但闸门仍在写侧）。 */
  @Transactional
  public User ban(long userId, String reason, Ctx ctx, LocalDateTime now) {
    String cutReason = requireReason(reason, ctx, AdminOpLog.ACTION_BAN_USER, "user:" + userId, userId);
    User user = require(userId);
    assertNotDeleted(user);
    user.setStatus(STATUS_BANNED);
    user.setMuteUntil(null);
    user.setUpdatedAt(now);
    userMapper.updateById(user);
    notifyService.notifyAccountAction(userId, "你的账号已被封禁",
        "你不能在社区内发布任何内容。依据：" + cutReason + "。如认为处置有误，可联系心理委员或管理员。");
    opLogService.success(ctx, AdminOpLog.ACTION_BAN_USER, "user:" + userId, userId,
        "封禁|理由=" + cutReason);
    return require(userId);
  }

  /**
   * 恢复：把 MUTED/BANNED 拉回 ACTIVE，并显式清空 mute_until。
   *
   * <p>走 UserMapper.restoreActive 而不是 updateById，是因为它把 deactivate_at/purge_at
   * 一起写回 NULL 的语义已经钉在 SQL 里；影响 0 行说明状态已被别人改过，当场报错而不是假装成功。</p>
   */
  @Transactional
  public User restore(long userId, String reason, Ctx ctx, LocalDateTime now) {
    String cutReason = requireReason(reason, ctx, AdminOpLog.ACTION_RESTORE_USER,
        "user:" + userId, userId);
    User user = require(userId);
    String from = user.getStatus();
    if (!STATUS_MUTED.equals(from) && !STATUS_BANNED.equals(from)) {
      opLogService.denied(ctx, AdminOpLog.ACTION_RESTORE_USER, "user:" + userId, userId,
          "当前状态 " + from + "，不是禁言或封禁");
      throw new BizException(ErrorCode.PARAM_INVALID, "只有禁言或封禁的账号需要恢复（当前 " + from + "）");
    }
    int changed = STATUS_MUTED.equals(from)
        ? userMapper.releaseMute(userId, from, STATUS_ACTIVE, now)
        : userMapper.restoreActive(userId, from, STATUS_ACTIVE);
    if (changed == 0) {
      opLogService.fail(ctx, AdminOpLog.ACTION_RESTORE_USER, "user:" + userId, userId,
          "状态并发变更，恢复未生效");
      throw new BizException(ErrorCode.FORBIDDEN, "账号状态已被变更，请刷新后重试");
    }
    notifyService.notifyAccountAction(userId, "你的账号已恢复正常",
        "你可以重新发帖、评论与发私信了。社区欢迎你的记录与表达。");
    opLogService.success(ctx, AdminOpLog.ACTION_RESTORE_USER, "user:" + userId, userId,
        "恢复 " + from + "->" + STATUS_ACTIVE + "|理由=" + cutReason);
    return require(userId);
  }

  // ================================================================ T6.5 解匿

  /** 解匿结果：审计行 id 一并回给前端，界面可以把它显示成「本次操作编号」，截图即证据。 */
  public record RevealResult(long aliasId, String aliasName, long userId, String nickname,
                             String username, Long opLogId, String notice) {
  }

  /** A6 界面那行红字的文案（服务端出一次，前端不再抄一份，避免两处措辞漂移）。 */
  static final String REVEAL_NOTICE =
      "解匿属高敏感操作，须有学校书面申请或涉生命安全依据；本次操作已完整留痕并通知当事人所在流程责任人。";

  /**
   * 匿名身份还原（FR8.4「解匿 100% 留痕」· Gate6 判据）。
   *
   * <p>顺序是刻意的：先写审计、拿到审计行 id，再把它回写进 alias.revealed_log_id。
   * 反过来的话一旦审计插入失败就没有留痕，而「已解匿却没日志」正是 FR8.4 要防的那件事；
   * 审计先落，即使后续 updateById 失败，日志里也只是多了一条「查过谁」的记录，不伤害合规。</p>
   */
  @Transactional
  public RevealResult revealAnonymous(long aliasId, String reason, Ctx ctx) {
    // 三段前置检查的顺序是刻意的：**先判「你有没有资格」，再判「参数写得对不对」，最后才查记录存不存在**。
    // 原来第一段是查库，于是 ADMIN 越权解匿时只要 aliasId 写错（或故意写 0）就在 404 上返回，
    // 一次越权尝试连一行 DENIED 都不留——而 FR8.4 要的是「解匿相关的一切尝试 100% 留痕」，
    // 「越权但目标恰好不存在」恰恰是最该被看见的那一类（它通常意味着有人在试探边界）。
    // 附带好处：不再把「这个 aliasId 存在吗」这个信息泄露给没有资格的人。
    if (ctx == null || !ROLE_SUPER.equals(ctx.role())) {
      opLogService.denied(ctx, AdminOpLog.ACTION_REVEAL_ANONYMOUS, "alias:" + aliasId, aliasId,
          "非 SUPER 角色尝试解匿，当前角色=" + (ctx == null ? "null" : ctx.role()));
      throw new BizException(ErrorCode.FORBIDDEN, "解匿仅限超级管理员（SUPER）");
    }
    if (reason == null || reason.isBlank()) {
      opLogService.denied(ctx, AdminOpLog.ACTION_REVEAL_ANONYMOUS, "alias:" + aliasId, aliasId,
          "理由为空，拒绝解匿");
      throw new BizException(ErrorCode.PARAM_INVALID, "解匿必须填写书面依据，一个字符都不能少");
    }
    AnonymousAlias alias = aliasMapper.selectById(aliasId);
    if (alias == null) {
      throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "匿名别名不存在：" + aliasId);
    }
    String cutReason = AuditQueueService.cut(reason.trim(), REASON_MAX);
    User target = alias.getUserId() == null ? null : userMapper.selectById(alias.getUserId());
    Long logId = opLogService.success(ctx, AdminOpLog.ACTION_REVEAL_ANONYMOUS,
        "alias:" + aliasId, aliasId, "解匿别名 " + alias.getAliasName() + " -> user "
            + alias.getUserId() + "|依据=" + cutReason + "|" + REVEAL_NOTICE);
    if (alias.getRevealedLogId() == null && logId != null) {
      alias.setRevealedLogId(logId);
      aliasMapper.updateById(alias);
    }
    return new RevealResult(aliasId, alias.getAliasName(),
        alias.getUserId() == null ? 0L : alias.getUserId(),
        target == null ? null : target.getNickname(), target == null ? null : target.getUsername(),
        logId, REVEAL_NOTICE);
  }

  /** 全站解匿次数（§17 FR8.4 的红线指标，答辩要能当场报数）。 */
  public long revealCount() {
    return opLogService.revealCount();
  }

  /** 帖子 -> 别名（供 A7 内容管理在匿名帖旁边放一个「解匿」按钮，不反查 alias->user）。 */
  public Long aliasIdOfPost(long postId) {
    Post post = postMapper.selectById(postId);
    return post == null ? null : post.getAliasId();
  }

  // ================================================================ 工具

  /**
   * 理由必填的统一入口：为空时先写一条 DENIED 再抛错——「试图在没依据的情况下处置用户」
   * 这件事本身就是需要被看见的行为（需求 §12 规范 5）。
   */
  private String requireReason(String reason, Ctx ctx, String action, String target, Long targetId) {
    if (reason == null || reason.isBlank()) {
      opLogService.denied(ctx, action, target, targetId, "处置理由为空，已拒绝执行");
      throw new BizException(ErrorCode.PARAM_INVALID, "处置用户必须填写理由，理由会写进审计与当事人通知");
    }
    return AuditQueueService.cut(reason.trim(), REASON_MAX);
  }

  /** 注销冷静期（DELETED）的账号不参与处置：它已经在倒计时物理清除，改状态会打乱保留期作业。 */
  private void assertNotDeleted(User user) {
    if (STATUS_DELETED.equals(user.getStatus())) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "该账号处于注销冷静期，不参与禁言或封禁处置（T5.x 保留期作业会按期清除）");
    }
  }
}

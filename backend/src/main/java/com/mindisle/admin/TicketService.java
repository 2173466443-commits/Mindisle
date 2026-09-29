package com.mindisle.admin;

import java.time.Duration;
import java.time.LocalDate;
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
import com.mindisle.admin.dto.ClosedRow;
import com.mindisle.admin.dto.OverdueRow;
import com.mindisle.admin.dto.StatusCountRow;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.AdminOpLog;
import com.mindisle.entity.AlertTicket;
import com.mindisle.entity.SysConfig;
import com.mindisle.entity.User;
import com.mindisle.mapper.AlertTicketMapper;
import com.mindisle.mapper.SensitiveWordMapper;
import com.mindisle.mapper.SysConfigMapper;
import com.mindisle.mapper.UserMapper;

/**
 * 危机工单六态状态机（任务 T6.2 · 手册 §9.1 第 6 条 · 需求 FR8.5 FR10.5 BR3）。
 *
 * <p><b>状态字面量全小写</b>：alert_ticket.status 的 ENUM 是
 * pending/claimed/doing/closed/false_positive/expired，而 audit_task.status 是全大写。
 * 两张表在同一段 Java 代码里出现时极易写串，MySQL 对 ENUM 越界值的行为是
 * <b>静默降级成第一个枚举项</b>（非严格模式下不报错），于是「closed」会被存成「pending」，
 * 一张办完的工单看起来还在等人处置。所以本类的每一条流转都先过 Java 白名单。</p>
 *
 * <p><b>处置记录必填，而且只写事实不写诊断</b>（BR3）：handle_note 里可以有
 * 「已联系学院心理老师，本人同意面谈」，不能有「该生患有抑郁症」。这不是格式要求，
 * 是这套系统的立项前提——它不是医疗器械，管理员更不是医生。</p>
 */
@Service
public class TicketService {

  private static final Logger log = LoggerFactory.getLogger(TicketService.class);

  /** 未办结三态（FR8.5 队列只看这三态，办结/误报/失效沉底）。 */
  static final Set<String> OPEN_STATUSES = Set.of(
      AlertTicket.STATUS_PENDING, AlertTicket.STATUS_CLAIMED, AlertTicket.STATUS_DOING);

  /** 允许被写入的终态：不是「当前状态」，而是「要点成什么」。 */
  static final Set<String> CLOSE_TARGETS = Set.of(
      AlertTicket.STATUS_CLOSED, AlertTicket.STATUS_FALSE_POSITIVE, AlertTicket.STATUS_EXPIRED);

  private static final Set<String> LEVELS = Set.of("L2", "L3");

  /** 列宽（07_audit.sql）：handle_note 1000 / trigger_words 200。 */
  static final int NOTE_MAX = 1000;
  static final int WORDS_MAX = 200;

  /** 认领后多久没动静算超时（与审核队列同一口径，界面上一起标红）。 */
  static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(30);

  /** sys_config 里的 SLA 覆盖键；读不到或转型失败一律回落 application.yml（配置漂移不能让工单没时限）。 */
  static final String CFG_L2_HOURS = "risk.sla_l2_hours";
  static final String CFG_L3_MINUTES = "risk.sla_l3_minutes";

  private final AlertTicketMapper ticketMapper;
  private final SensitiveWordMapper wordMapper;
  private final SysConfigMapper configMapper;
  private final UserMapper userMapper;
  private final MindisleProperties properties;
  private final AdminOpLogService opLogService;

  public TicketService(AlertTicketMapper ticketMapper, SensitiveWordMapper wordMapper,
                       SysConfigMapper configMapper, UserMapper userMapper,
                       MindisleProperties properties, AdminOpLogService opLogService) {
    this.ticketMapper = ticketMapper;
    this.wordMapper = wordMapper;
    this.configMapper = configMapper;
    this.userMapper = userMapper;
    this.properties = properties;
    this.opLogService = opLogService;
  }

  // ================================================================ 查询

  /** 工单行：本体 + 当事人昵称 + 是否超时 + 距 SLA 还剩几分钟（负数即已超时）。 */
  public record TicketView(AlertTicket ticket, String nickname, boolean overdue,
                           Long slaMinutesLeft) {
  }

  public PageResult<TicketView> list(String status, String level, Long assigneeId, Long userId,
                                     boolean overdueOnly, PageQuery query, LocalDateTime now) {
    String statusFilter = AuditQueueService.blankToNull(status);
    if (statusFilter != null && !OPEN_STATUSES.contains(statusFilter)
        && !CLOSE_TARGETS.contains(statusFilter)) {
      throw new BizException(ErrorCode.PARAM_INVALID, "工单状态只能是 "
          + String.join("/", java.util.stream.Stream.concat(OPEN_STATUSES.stream(),
              CLOSE_TARGETS.stream()).sorted().toList()));
    }
    String levelFilter = AuditQueueService.blankToNull(level);
    if (levelFilter != null && !LEVELS.contains(levelFilter)) {
      throw new BizException(ErrorCode.PARAM_INVALID, "危机工单只有 L2/L3 两级");
    }
    PageQuery q = query.normalize();
    List<AlertTicket> tickets = ticketMapper.pageTickets(statusFilter, levelFilter, assigneeId,
        userId, overdueOnly, now, q.offset(), q.getSize());
    long total = ticketMapper.countTickets(statusFilter, levelFilter, assigneeId, userId,
        overdueOnly, now);
    return PageResult.of(views(tickets, now), total, q);
  }

  private List<TicketView> views(List<AlertTicket> tickets, LocalDateTime now) {
    Set<Long> userIds = new LinkedHashSet<>();
    for (AlertTicket ticket : tickets) {
      if (ticket.getUserId() != null) {
        userIds.add(ticket.getUserId());
      }
    }
    List<User> users = userIds.isEmpty() ? List.of()
        : userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getId, userIds));
    List<TicketView> out = new ArrayList<>(tickets.size());
    for (AlertTicket ticket : tickets) {
      String nickname = null;
      for (User user : users) {
        if (user.getId() != null && user.getId().equals(ticket.getUserId())) {
          nickname = user.getNickname();
          break;
        }
      }
      boolean open = OPEN_STATUSES.contains(ticket.getStatus());
      boolean overdue = open && ticket.getSlaAt() != null && ticket.getSlaAt().isBefore(now);
      Long left = ticket.getSlaAt() == null ? null
          : Duration.between(now, ticket.getSlaAt()).toMinutes();
      out.add(new TicketView(ticket, nickname, overdue, left));
    }
    return out;
  }

  /** A5 工单看板顶部的三个数：各态计数、超时统计、今日新增/闭环（FR8.2 要求图上全是真数据）。 */
  public record Board(List<StatusCountRow> statusCounts, long overdueCnt, long worstMinutes,
                      long todayCreatedCnt, long todayClosedCnt) {
  }

  public Board board(LocalDateTime now) {
    List<StatusCountRow> counts = ticketMapper.countGroupByStatus();
    OverdueRow overdue = ticketMapper.overdueStats(now);
    LocalDate day = now.toLocalDate();
    ClosedRow closed = ticketMapper.closedStatsSince(day.atStartOfDay());
    long overdueCnt = overdue == null || overdue.getCnt() == null ? 0L : overdue.getCnt();
    long worst = overdue == null || overdue.getWorstMinutes() == null ? 0L : overdue.getWorstMinutes();
    return new Board(counts, overdueCnt, worst,
        closed == null || closed.getCnt() == null ? 0L : closed.getCnt(),
        closed == null || closed.getClosedCnt() == null ? 0L : closed.getClosedCnt());
  }

  /** 用户侧「我曾得到的关怀」时间线（FR10.5 可回溯），也用于 A6 用户详情抽屉。 */
  public List<AlertTicket> timeline(long userId, int limit) {
    return ticketMapper.timelineByUser(userId, limit <= 0 ? 20 : Math.min(limit, 100));
  }

  public AlertTicket require(long ticketId) {
    AlertTicket ticket = ticketMapper.selectById(ticketId);
    if (ticket == null) {
      throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "危机工单不存在：" + ticketId);
    }
    return ticket;
  }

  // ================================================================ 流转

  /** 认领：pending 才能领；已被领走的工单不允许隔手接手（责任唯一，出问题能追到人）。 */
  public AlertTicket claim(long ticketId, Ctx ctx, LocalDateTime now) {
    long operatorId = requireOperator(ctx);
    AlertTicket ticket = require(ticketId);
    if (!AlertTicket.STATUS_PENDING.equals(ticket.getStatus())) {
      denied(ctx, ticket, "当前状态 " + ticket.getStatus() + "，不是待认领");
      throw new BizException(ErrorCode.FORBIDDEN,
          "工单已被他人认领或已办结（当前 " + ticket.getStatus() + "），请刷新队列");
    }
    if (ticketMapper.claim(ticketId, operatorId, now) == 0) {
      denied(ctx, ticket, "并发抢占失败");
      throw new BizException(ErrorCode.FORBIDDEN, "工单已被其他管理员认领，请刷新后重选");
    }
    opLogService.success(ctx, AdminOpLog.ACTION_TICKET_HANDLE, "alert_ticket:" + ticketId, ticketId,
        "认领 level=" + ticket.getLevel() + " user=" + ticket.getUserId());
    return require(ticketId);
  }

  /** 进入处置中：claimed -> doing，用于「已联系本人、正在跟进」这一段。 */
  public AlertTicket startDoing(long ticketId, Ctx ctx, LocalDateTime now) {
    long operatorId = requireOperator(ctx);
    AlertTicket ticket = require(ticketId);
    if (!AlertTicket.STATUS_CLAIMED.equals(ticket.getStatus())) {
      denied(ctx, ticket, "只有已认领的工单能进入处置中（当前 " + ticket.getStatus() + "）");
      throw new BizException(ErrorCode.FORBIDDEN, "请先认领工单再标记处置中");
    }
    if (ticketMapper.startDoing(ticketId, now) == 0) {
      denied(ctx, ticket, "并发流转失败");
      throw new BizException(ErrorCode.FORBIDDEN, "工单状态已被他人变更，请刷新后重试");
    }
    opLogService.success(ctx, AdminOpLog.ACTION_TICKET_HANDLE, "alert_ticket:" + ticketId, ticketId,
        "转处置中 operator=" + operatorId);
    return require(ticketId);
  }

  /** 处置结果 + 本次回填的词库热度行数（真命中闭环时才有意义）。 */
  public record Outcome(AlertTicket ticket, String toStatus, int hitWordsBumped) {
  }

  /**
   * 闭环（FR8.5「可标记处置结果」· Gate6「L3 工单全程留痕」）。
   *
   * <p>三种终态各有含义，不能混用：closed＝真命中且已按流程处置完；false_positive＝机器误报，
   * 这一态是词库调优的输入；expired＝当事人已自行缓解或联系不上，超时失效。
   * <b>只有 closed 会回填 sensitive_word.hit_cnt</b>：把误报也计入命中热度，
   * 等于让误报词永远出不去，这是最容易把词库越改越差的一步。</p>
   *
   * <p>未认领的工单也能直接闭环——alert_ticket.close 的 SQL 用 COALESCE 保留 assignee_id/claim_at，
   * 于是 L3 值班时「看到就处理掉」不会被迫先表演一次认领。</p>
   */
  @Transactional
  public Outcome close(long ticketId, Ctx ctx, String toStatus, String note,
                       LocalDateTime followupAt, LocalDateTime now) {
    requireOperator(ctx);
    if (toStatus == null || !CLOSE_TARGETS.contains(toStatus)) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "处置结果只能是 closed/false_positive/expired，收到的是：" + toStatus);
    }
    if (note == null || note.isBlank()) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "处置记录必填：只写事实与转介去向，不写诊断结论（BR3）");
    }
    AlertTicket ticket = require(ticketId);
    if (!OPEN_STATUSES.contains(ticket.getStatus())) {
      denied(ctx, ticket, "已办结，状态 " + ticket.getStatus());
      throw new BizException(ErrorCode.FORBIDDEN, "工单已办结（" + ticket.getStatus() + "），不可重复处置");
    }
    String cutNote = AuditQueueService.cut(note, NOTE_MAX);
    if (ticketMapper.close(ticketId, ctx.operatorId(), toStatus, cutNote, followupAt, now) == 0) {
      denied(ctx, ticket, "并发流转失败");
      throw new BizException(ErrorCode.FORBIDDEN, "工单状态已被他人变更，请刷新后重试");
    }
    int bumped = 0;
    if (AlertTicket.STATUS_CLOSED.equals(toStatus)) {
      List<String> words = triggerWords(ticket.getTriggerWords());
      if (!words.isEmpty()) {
        bumped = wordMapper.bumpHitCnt(words, now);
      }
    }
    opLogService.success(ctx, AdminOpLog.ACTION_TICKET_HANDLE, "alert_ticket:" + ticketId, ticketId,
        "闭环 " + ticket.getStatus() + "->" + toStatus + "|回填词热=" + bumped
            + "|回访=" + (followupAt == null ? "无" : followupAt) + "|" + AuditQueueService.cut(cutNote, 100));
    return new Outcome(require(ticketId), toStatus, bumped);
  }

  /** 超时未认领/未处置的工单一律标 expired：这一步是人工确认后的动作，不是定时器自动改的。 */
  public List<AlertTicket> overdue(LocalDateTime now, int limit) {
    return ticketMapper.pageTickets(null, null, null, null, true, now, 0,
        limit <= 0 ? 50 : Math.min(limit, 200));
  }

  // ================================================================ 工具

  /**
   * 按级别算 SLA 截止时刻：sys_config 优先，回落 application.yml。
   *
   * <p>为什么两处都能配：yml 是发版态，sys_config 是运行态。需求 §7.2 #23 把风险阈值做成可热调参数，
   * 而 CrisisGrader 读的是 yml。工单侧必须先读库，否则「管理员把 L3 时限从 30 分钟改成 15 分钟」
   * 这件事在工单排序上不会生效——那是最典型的「配置界面骗人」。</p>
   *
   * <p><b>2026-09-29 起非法等级不再返回 null</b>：这个方法是给 A5 工单卡算「还剩几分钟」的，
   * 返回 null 会让前端渲染成「无时限」。等级写错（前端传了小写 l3、传了 L1、传了空串）属于
   * 调用方问题，但一旦显示成「无时限」，管理员就会把一条真正超时的 L3 单当成不急的单放过——
   * 错误的展示比报错更危险。因此这里统一回落到最宽的 L2 并打 warn：宁可把不该有的时限摆出来，
   * 也不让任何一条危机工单在界面上变成「不用管」。只有落库路径
   * （{@link AuditQueueService#slaForTask}）才允许 L0/L1 无 SLA，因为那里是「按等级决定是否建单」，
   * 而不是「拿一个可能写错的等级去显示」。</p>
   */
  public LocalDateTime slaFor(String level, LocalDateTime now) {
    if (level == null || !LEVELS.contains(level)) {
      log.warn("工单 SLA 试算收到非法等级 [{}]，按最宽的 L2 回落而不是返回 null", level);
      return now.plusHours(configInt(CFG_L2_HOURS, properties.getCrisis().getL2SlaHours()));
    }
    MindisleProperties.Crisis crisis = properties.getCrisis();
    if ("L3".equals(level)) {
      return now.plusMinutes(configInt(CFG_L3_MINUTES, crisis.getL3SlaMinutes()));
    }
    return now.plusHours(configInt(CFG_L2_HOURS, crisis.getL2SlaHours()));
  }

  private int configInt(String key, int fallback) {
    SysConfig config = configMapper.findByKey(key);
    String raw = config == null ? null : config.getCfgValue();
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      int value = Integer.parseInt(raw.trim());
      if (value <= 0) {
        log.warn("sys_config {}={} 不是正数，回落配置默认 {}", key, raw, fallback);
        return fallback;
      }
      return value;
    } catch (NumberFormatException e) {
      log.warn("sys_config {}={} 无法按整数解析，回落配置默认 {}", key, raw, fallback);
      return fallback;
    }
  }

  /** alert_ticket.trigger_words 是逗号分隔的词面串（PostService.newTicket 写入时的格式）。 */
  static List<String> triggerWords(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (String part : csv.split(",")) {
      String word = part.trim();
      if (!word.isEmpty() && word.length() <= WORDS_MAX) {
        out.add(word);
      }
    }
    return out;
  }

  private void denied(Ctx ctx, AlertTicket ticket, String detail) {
    opLogService.denied(ctx, AdminOpLog.ACTION_TICKET_HANDLE,
        "alert_ticket:" + ticket.getId(), ticket.getId(), detail);
  }

  private static long requireOperator(Ctx ctx) {
    if (ctx == null || ctx.operatorId() == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED, "管理端操作需要登录身份");
    }
    return ctx.operatorId();
  }
}
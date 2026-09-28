package com.mindisle.pm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mindisle.ai.RiskScorer;
import com.mindisle.audit.SensitiveWordEngine;
import com.mindisle.cache.CacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.Result;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.AlertTicket;
import com.mindisle.entity.AuditTask;
import com.mindisle.entity.PrivateMessage;
import com.mindisle.entity.User;
import com.mindisle.entity.UserBlock;
import com.mindisle.pm.dto.PmAckView;
import com.mindisle.pm.dto.PmBlockItem;
import com.mindisle.pm.dto.PmBlockView;
import com.mindisle.pm.dto.PmConversationItem;
import com.mindisle.pm.dto.PmConversationPage;
import com.mindisle.pm.dto.PmMessageView;
import com.mindisle.pm.dto.PmPresenceView;
import com.mindisle.pm.dto.PmReadRequest;
import com.mindisle.pm.dto.PmReportRequest;
import com.mindisle.pm.dto.PmReportView;
import com.mindisle.pm.dto.PmSendRequest;
import com.mindisle.pm.dto.PmThreadPage;
import com.mindisle.pm.dto.PmUnreadView;
import com.mindisle.post.CrisisGrader;
import com.mindisle.post.PostService;
import com.mindisle.post.PostingQuotaService;

/**
 * 私信域的判据层（任务 T5.2–T5.7 · 需求 FR6 私信与互助 · 手册 §8）。
 *
 * <p><b>本类只回答「这条私信能不能落库、落成什么样子」，不回答「怎么送到对方屏幕上」</b>：
 * 后者是 {@link PmRealtimeListener} 与 {@link PmPushGateway} 的事，中间用
 * {@link PmSendEvent} / {@link PmReadEvent} 两个事件隔开。隔开不是为了好看，
 * 而是因为「推送」必须发生在<b>事务提交之后</b>：如果在 {@code @Transactional} 里直接
 * {@code convertAndSendToUser}，接收方会在提交前收到消息并立刻回一条已读上报，
 * 那条 UPDATE 撞上一个还没提交的行——轻则回执 0 行（前端气泡停在「送达中」），
 * 重则两个事务互相等待。判据见 {@code @TransactionalEventListener(AFTER_COMMIT)}。</p>
 *
 * <p><b>落库优先（手册 §8.2 第 1 条）</b>：{@link #send} 的返回值就是那一行数据库内容，
 * WebSocket 只是它的一个下游订阅者。后端挂掉、前端降级轮询、用户换了设备，
 * 消息都还在 {@code private_message} 里，这比「推送成功过」重要得多。</p>
 *
 * <p><b>{@link #send} 的判定顺序是有意排出来的，不要重排</b>（每一步都写清了为什么在这一步）：</p>
 * <ol>
 *   <li>自发——后面所有文案都要用「对方」这个词，它必须在语义成立之前先被挡住；</li>
 *   <li>幂等键——它决定这一次是不是「同一句话的第二次努力」，越早识别越少做无用功；</li>
 *   <li>内容与类型——纯格式判定，不需要查库，失败就 400，绝不让坏内容进入后面的表；</li>
 *   <li>发信方资格（BR6）——禁言号不能「说」，私信也是「说」；</li>
 *   <li>收信方存在性——查无此人 / 已注销都是 404，不给「发给了鬼」留活路；</li>
 *   <li>词库 BLOCK——内容不过关就别占后面的工单和额度，也别说「已发送」；</li>
 *   <li>拉黑（FR6.7）——双向任一方向命中即 403；</li>
 *   <li>危机分级——<b>排在拉黑之后是有意的</b>，见 {@link #send} 第 8 步注释：
 *       被拉黑的那条如果踩了自伤词，仍然要建单。拦下私信和救下人是两条独立的线，
 *       与 {@code PostService} 里「内容被拒也照样建单」同一原则；</li>
 *   <li>落库 → 建单 → 发事件。</li>
 * </ol>
 *
 * <p><b>为什么不复用 {@code PostService} / {@code ReportService} 的建单代码</b>：
 * 那两处的 {@code newTicket}、{@code decide}、{@code MachineDecision}、{@code rankOf}
 * 全是包级可见（{@code post} 包内共用），{@code pm} 包拿不到，这是当初把它们留在各自包里的选择。
 * 本类因此自带一份 {@code rankOf/scoreOf/slaOf/newTicket}，<b>但算法逐字照 {@code ReportService}
 * 的同名方法写</b>（L3 取 {@code crisis.l3-score}、SLA 取 {@code l3-sla-minutes}，
 * 分数 {@code setScale(3, HALF_UP)}），使私信、帖子、评论三条通道的工单在管理员的同一张队列里可比。
 * 这条重复是本阶段已知的设计债，记在手册 §8 的偏离里，等到有第四个通道时再抽公共类。</p>
 */
@Service
public class PmService {

  private static final Logger log = LoggerFactory.getLogger(PmService.class);

  /** {@code audit_task.target_type} 与 {@code alert_ticket.source_type} 里的私信档（两个 ENUM 都已含 pm）。 */
  static final String TARGET_PM = "pm";

  /** 词库处置档位。<b>必须大写</b>：{@code sensitive_word.action} 存的是 BLOCK/REVIEW/TAG，写成小写永远匹配不到。 */
  static final String ACTION_BLOCK = "BLOCK";
  static final String ACTION_REVIEW = "REVIEW";
  static final String ACTION_TAG = "TAG";

  /** 只有这两档才生成对外可见的求助提示与危机工单（{@link CrisisGrader} 的词面通道不产 L1）。 */
  static final Set<String> TICKET_LEVELS = Set.of(CrisisGrader.L2, CrisisGrader.L3);

  /**
   * 收件人「查无此人」的状态。
   *
   * <p>{@code user.status} 的 ENUM 是 ACTIVE / MUTED / BANNED / DELETED（sql/01_account.sql:114），
   * <b>没有 DISABLED</b>，所以这里不能写 DISABLED——写了也不会报错，只会永远不命中。
   * MUTED 不在名单里：禁言的人仍然收得到私信（收信不是「说」）。
   * {@code deleted=1} 单独判，逻辑删除的用户即使状态还是 ACTIVE 也不能再收件。</p>
   */
  static final Set<String> GONE_STATUSES = Set.of("BANNED", "DELETED");

  /** 用户能发的类型白名单。system 是服务端留档（如「对方已开启匿名」），不允许用户自称系统。 */
  static final Set<String> USER_MSG_TYPES = Set.of(PrivateMessage.TYPE_TEXT, PrivateMessage.TYPE_IMAGE);

  /** 图片私信只认这个前缀（站内上传目录，前端 {@code <img :src>} 同源解析）。 */
  static final String UPLOAD_PREFIX = "/uploads/";

  static final String ACTION_ADD = "block";
  static final String ACTION_REMOVE = "unblock";

  /** 机审结论原文的长度上限（{@code audit_task.result} 是 VARCHAR(32)）。 */
  private static final int RESULT_MAX = 32;
  /** {@code alert_ticket.trigger_words} / {@code evidence_text} 的截断长度，与 {@code RiskScorer} 包内常量同值。 */
  private static final int TRIGGER_WORDS_MAX = 200;
  private static final int EVIDENCE_MAX = 500;
  /** 会话列表里的摘要长度，与 {@code NotifyService#EXCERPT_MAX} 同值（都是列表页一行放不下 100 字）。 */
  private static final int PREVIEW_MAX = 60;
  private static final String IMAGE_PREVIEW = "[图片]";

  /** 举报理由 → 对外中文标签。<b>LinkedHashMap：键序就是前端下拉的顺序</b>，用 HashMap 会随机漂。 */
  static final Map<String, String> REPORT_LABELS = reportLabels();

  private static Map<String, String> reportLabels() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("spam", "广告骚扰");
    map.put("abuse", "攻击辱骂");
    map.put("sexual", "色情低俗");
    map.put("privacy", "泄露隐私");
    map.put("self-harm", "自伤风险");
    map.put("other", "其他");
    // unmodifiable 而不是 Map.copyOf：copyOf 会丢掉插入顺序，而这个顺序就是前端下拉的顺序。
    return java.util.Collections.unmodifiableMap(map);
  }

  private final PmStore store;
  private final MindisleProperties properties;
  private final SensitiveWordEngine wordEngine;
  private final CacheService cacheService;
  private final PostingQuotaService quotaService;
  private final RiskScorer riskScorer;
  private final PresenceRegistry presence;
  private final ApplicationEventPublisher events;

  public PmService(PmStore store, MindisleProperties properties, SensitiveWordEngine wordEngine,
      CacheService cacheService, PostingQuotaService quotaService, RiskScorer riskScorer,
      PresenceRegistry presence, ApplicationEventPublisher events) {
    this.store = store;
    this.properties = properties;
    this.wordEngine = wordEngine;
    this.cacheService = cacheService;
    this.quotaService = quotaService;
    this.riskScorer = riskScorer;
    this.presence = presence;
    this.events = events;
  }

  // ================================================================ 发（T5.2 / T5.4）

  /**
   * 发一条私信。<b>返回的就是落库那一行</b>；重复提交（同一个 {@code clientMsgId}）返回已有的那一行，
   * 并且<b>不发事件</b>——不发第二遍推送、不写第二遍通知、不建第二张工单。
   *
   * <p>为什么幂等命中时直接返回而不抛 409：客户端重试的目的就是「让这句话到达」，
   * 它到达过一次就该报告成功；抛错会让前端把成功当成失败，进而再试一次，
   * 那是用错误码制造重试风暴。</p>
   *
   * @param senderId 发信人，只来自令牌（需求 A9：不接受入参里的 from_user_id）
   * @param request  收件人、内容、幂等键、类型
   * @param now      业务时间，由调用方给（单测要钉「建单 SLA = now + 30min」时不能靠墙上时钟）
   * @return 对外视图（{@code mine=true}）
   */
  @Transactional
  public PmMessageView send(long senderId, PmSendRequest request, LocalDateTime now) {
    MindisleProperties.Pm cfg = properties.getPm();
    if (request == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "私信内容不能是空的");
    }

    // 1 自发：FR6 的主语是「对方」。这一条排在最前，后面每一句文案里的「对方」才都成立。
    Long toUserId = request.toUserId();
    if (toUserId == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "收件人不能为空");
    }
    long recipientId = toUserId.longValue();
    if (recipientId == senderId) {
      throw new BizException(ErrorCode.PARAM_INVALID, "不能给自己发私信，写给自己可以用草稿箱");
    }

    // 2 幂等键（手册 §8.2 第 3 条）。空即服务端补随机键：等于「这次重试我不指望你去重」。
    String clientMsgId = normalizeClientMsgId(request.clientMsgId(), cfg);

    // 3 内容：按码点计长（代理对不半刀）。
    String content = normalizeContent(request.content(), cfg);

    // 4 类型：白名单之外直接 400；image 的内容必须是站内上传路径（外链会把人带出站）。
    String msgType = normalizeMsgType(request.msgType(), content);

    // 5 发信方资格（BR6）：禁言能看能点赞，但不能「说」——私信是说。
    User sender = store.findUser(senderId);
    quotaService.assertStatusAllowsWrite(sender);

    // 6 收信方存在性。已注销、被封禁、deleted=1 全部按「查无此人」，而不是「空昵称」。
    User recipient = store.findUser(recipientId);
    if (recipient == null || isGone(recipient)) {
      throw new BizException(ErrorCode.USER_NOT_FOUND, "对方不存在或已注销，这条私信没有收件人");
    }

    // 7 词库。refreshIfStale 先跑：管理员改了词库之后的第一次私信就该用新库，
    // 而不是等本节点那 60 秒的轮询窗口——帖子侧也是这个顺序。
    wordEngine.refreshIfStale(cacheService, properties.getAudit().getDictVersionKey());
    SensitiveWordEngine.CheckResult check = wordEngine.check(content, "user");
    if (check.hasAction(ACTION_BLOCK)) {
      throw new BizException(ErrorCode.CONTENT_REJECTED, "私信内容包含违规信息，已拦截");
    }

    // 8 拉黑（FR6.7 双向）。放在危机之前，但<b>不等于被拦下的内容就不再判危机</b>：
    //   一个人给「已经把自己拉黑」的对象发求助，这条私信不会送达，可那个人仍然需要被接住。
    //   所以 blocked 分支照样走危机分级，只是不入库、不建 audit_task（没有可审的 target）。
    boolean blocked = store.isBlockedAny(senderId, recipientId);

    // 9 危机分级：词面通道必走；模型通道按 pm.risk-model-enabled 决定（默认关 = 不为私信花 token）。
    String level = CrisisGrader.levelOf(check);
    if (cfg.isRiskModelEnabled()) {
      RiskScorer.Risk modelRisk = riskScorer.score(content, senderId, Result.currentTraceId());
      level = higher(level, modelRisk.level());
    }
    boolean care = CrisisGrader.needsTicket(level);

    if (blocked) {
      if (care) {
        // 无 msgId 可指，source_id 落 0：工单的证据全文就在 evidence_text 里，管理员不需要那条没送达的消息。
        AlertTicket ticket = newTicket(senderId, 0L, content, check, level, now);
        store.insertTicket(ticket);
        log.warn("私信被隐私拦截但命中危机，已单独建工单 senderId={} level={} ticketId={} evidenceChars={}",
            senderId, level, ticket.getId(), length(ticket.getEvidenceText()));
      }
      throw new BizException(ErrorCode.PM_BLOCKED);
    }

    // 10 落库优先：INSERT IGNORE 撞 uk_pair_msg 返回 0，此时 row.getId() 不可信，必须回查。
    PrivateMessage row = new PrivateMessage();
    row.setFromUserId(senderId);
    row.setToUserId(recipientId);
    row.setClientMsgId(clientMsgId);
    row.setMsgType(msgType);
    row.setContent(content);
    row.setRiskLevel(level);
    row.setStatus(PrivateMessage.STATUS_SENT);
    // 🔴 业务时间全站只有一把尺：调用方传进来的 now（工单、SLA、事件、通知都读它）。
    // 这一行不是为了「库里有时间」（DDL 默认值本来就会填），是为了「返回的那份对象里有时间」：
    // viewOf(row) 同时是 REST /send 的响应体和 WS 推送帧的数据源，createdAt 为空时
    // non_null 会把它删掉，前端气泡就没了分钟数、日期分组掉进「更早」。
    // 详见 PrivateMessageMapper#insertIgnore 的注释——那是 Gate5 第一次连真库跑出来的形状差。
    row.setCreatedAt(now);
    boolean created = store.insertMessage(row);
    if (!created) {
      PrivateMessage existing = store.findByClientMsg(clientMsgId, recipientId);
      if (existing == null) {
        // 撞了唯一键却回查为空：那条行只能是 deleted=1。这不是「用户重试」，是数据状态异常，
        // 报 90004 让它在日志里可见，而不是把一条读不到的行当成功返回。
        log.warn("幂等键撞库但回查为空（该行可能已被逻辑删除）clientMsgId={} recipientId={}",
            clientMsgId, recipientId);
        throw new BizException(ErrorCode.INTERNAL_ERROR);
      }
      return viewOf(existing, true);
    }

    // 11 建单在落库之后：工单要能指向那条真实存在的消息，管理员点开工单能看到原文。
    Long ticketId = null;
    String evidence = null;
    if (care) {
      AlertTicket ticket = newTicket(senderId, row.getId(), content, check, level, now);
      store.insertTicket(ticket);
      ticketId = ticket.getId();
      evidence = ticket.getEvidenceText();
      ensureMachineTask(row.getId(), level, verdictOf(check), now);
      log.warn("危机私信已建单 msgId={} senderId={} level={} ticketId={} evidenceChars={}",
          row.getId(), senderId, level, ticketId, length(evidence));
    }

    // 12 事件在事务内发布，消费方是 AFTER_COMMIT：提交之前一个字节都不会离开服务器。
    events.publishEvent(new PmSendEvent(senderId, recipientId, row,
        PostService.displayNameOf(sender), level, evidence, ticketId, now));
    return viewOf(row, true);
  }

  /**
   * 已读上报（需求 FR6.4 的「已读回执」）。返回一条 ack，REST 与 STOMP 共用同一份判据。
   *
   * <p>{@code upToId} 为空即「这个会话全部已读」：SQL 是 {@code id <= upToId}，
   * 传 {@code Long.MAX_VALUE} 就是恒真比较，不需要在端口里开第二条方法。</p>
   *
   * <p><b>只清「我收到的」那一半</b>：{@code WHERE to_user_id = 我 AND from_user_id = 对方}，
   * 所以我把自己发出去的气泡点成已读不可能改变任何行——这条判据写在 SQL 里而不是 Java 里，
   * 是因为它是「谁的数据谁能改」的最后一道防线（前端传别人的 peerId 也只是 0 行）。</p>
   */
  @Transactional
  public PmAckView markRead(long userId, PmReadRequest request, LocalDateTime now) {
    Long peerIdRaw = request == null ? null : request.peerId();
    if (peerIdRaw == null) {
      throw new BizException(ErrorCode.PARAM_INVALID, "缺少会话对象");
    }
    long peerId = peerIdRaw.longValue();
    if (peerId == userId) {
      throw new BizException(ErrorCode.PARAM_INVALID, "会话对象不能是自己");
    }
    long upToId = request.upToId() == null ? Long.MAX_VALUE : request.upToId().longValue();
    int count = store.markThreadRead(userId, peerId, upToId, now);
    if (count > 0) {
      // 只有真的翻行了才通知对方：两条标签页同时打开同一个会话时，
      // 第二条的上报是 0 行，不发事件，于是「已读」动画不会在同一秒播两遍。
      events.publishEvent(new PmReadEvent(userId, peerId, count, upToId));
    }
    Integer rest = store.unreadByPeers(userId, List.of(peerId)).get(peerId);
    return new PmAckView("read", null, null, peerId, count, rest == null ? 0 : rest, now, null, null);
  }

  // ================================================================ 读（T5.3 / T5.5）

  /** 会话列表（U9）：每条会话的最后一句 + 未读角标 + 在线点。 */
  public PmConversationPage conversations(long userId, Long beforeId, Integer size) {
    int limit = normalizeSize(size);
    List<PrivateMessage> tails = store.listConversationTails(userId, beforeId, limit + 1);
    boolean hasMore = tails.size() > limit;
    List<PrivateMessage> page = hasMore ? tails.subList(0, limit) : tails;

    List<Long> peerIds = new ArrayList<>(page.size());
    for (PrivateMessage tail : page) {
      peerIds.add(otherSide(tail, userId));
    }
    Map<Long, Integer> unread = store.unreadByPeers(userId, peerIds);
    List<PmConversationItem> items = new ArrayList<>(page.size());
    for (int i = 0; i < page.size(); i++) {
      PrivateMessage tail = page.get(i);
      long peerId = peerIds.get(i);
      User peer = store.findUser(peerId);
      Integer cnt = unread.get(peerId);
      items.add(new PmConversationItem(peerId, displayName(peer), avatarOf(peer),
          tail.getId(), preview(tail), tail.getMsgType(), tail.getCreatedAt(),
          isMine(tail, userId), cnt == null ? 0 : cnt, presence.isOnline(peerId)));
    }
    Long cursor = page.isEmpty() ? beforeId : page.get(page.size() - 1).getId();
    return new PmConversationPage(items, cursor, hasMore, (int) store.countUnreadTotal(userId));
  }

  /**
   * 单个会话的分页历史（U10），返回<b>时间正序</b>。
   *
   * <p>SQL 是 {@code id < beforeId ORDER BY id DESC}（从新的往旧翻），翻成正序只在这里做一次；
   * 前端拿到就能直接 append 到气泡数组上方，不需要再排序。</p>
   *
   * <p><b>被拉黑了还能看历史</b>：拉黑拦的是「以后不能再发」，不是「把已经说过的话抹掉」。
   * 需求 FR6.7 那句「双向不可见」在本项目落到 {@code blocked=true}（输入框禁用 + 顶部提示），
   * 而不是让聊天记录凭空消失——后者会让「我拉黑了一个正在支持我的人」变成一次失忆。
   * 这条口径写进手册 §8 的偏离清单。</p>
   */
  public PmThreadPage thread(long userId, long peerId, Long beforeId, Integer size) {
    if (peerId == userId) {
      throw new BizException(ErrorCode.PARAM_INVALID, "会话对象不能是自己");
    }
    User peer = store.findUser(peerId);
    if (peer == null || isGone(peer)) {
      throw new BizException(ErrorCode.USER_NOT_FOUND, "对方不存在或已注销");
    }
    int limit = normalizeSize(size);
    List<PrivateMessage> rows = store.pageThread(userId, peerId, beforeId, limit + 1);
    boolean hasMore = rows.size() > limit;
    List<PrivateMessage> page = hasMore ? rows.subList(0, limit) : rows;
    List<PmMessageView> list = new ArrayList<>(page.size());
    for (int i = page.size() - 1; i >= 0; i--) {
      PrivateMessage row = page.get(i);
      list.add(viewOf(row, isMine(row, userId)));
    }
    Long cursor = page.isEmpty() ? beforeId : page.get(page.size() - 1).getId();
    return new PmThreadPage(peerId, displayName(peer), avatarOf(peer), list, cursor, hasMore,
        store.isBlockedAny(userId, peerId), presence.isOnline(peerId), peer.getLastLoginAt());
  }

  /**
   * 未读视图（REST {@code GET /api/pm/unread}）。前端<b>降级轮询</b>与断线补拉都打这一个口。
   *
   * <p>{@code byPeer} 只带未读 &gt; 0 的人：这是给「列表页谁头上有红点」用的，
   * 把 0 也发过去会让一个有 200 个老同事的账号每次轮询拖 200 个键，
   * 而前端拿到 0 本来就该显示成「没有红点」。</p>
   */
  public PmUnreadView unreadView(long userId) {
    long total = store.countUnreadTotal(userId);
    Map<Long, Integer> byPeer = store.unreadByPeers(userId, store.listPeerIds(userId));
    Map<Long, Integer> nonzero = new LinkedHashMap<>();
    byPeer.forEach((peerId, cnt) -> {
      if (peerId != null && cnt != null && cnt.intValue() > 0) {
        nonzero.put(peerId, cnt);
      }
    });
    return PmUnreadView.of((int) total, nonzero);
  }

  /** 在线状态（需求 FR6.5）：只回答「你问的这些人里谁在线」，不广播名单。 */
  public PmPresenceView presenceOf(long userId, Collection<Long> peerIds) {
    return PmPresenceView.ofOnline(presence.filterOnline(peerIds));
  }

  /**
   * 断线补拉（手册 §8.2 第 4 条）：前端重连后拿 {@code beforeId} 往回翻页，
   * 与 {@link #thread} 同一个判据，因此<b>不需要服务端记「你拉到哪了」</b>。
   *
   * <p>这条刻意不缓存游标是有代价的：客户端恶意翻页会反复扫 {@code idx_pair}。
   * 代价可控（每页 ≤ 50，走覆盖索引），而换来的是「服务端没有会话状态」——
   * 一旦开始在服务端记进度，就多了一个需要清理的键和一个会漂移的读数。</p>
   */
  public PmThreadPage refill(long userId, long peerId, Long beforeId, Integer size) {
    return thread(userId, peerId, beforeId, size);
  }

  // ================================================================ 重投（T5.3）

  /**
   * 还没送达的候选行（{@code PmDeliveryRetryJob} 的唯一取数入口）。
   *
   * <p>{@code retry-grace-seconds} 是这一句的全部意义：刚发出的 0.5 秒内本来就在等 STOMP 回执，
   * 没有宽限期的话每一封私信都会被判成「没送达」再推一遍，
   * 于是「重投」变成「双倍推送」——那是这整条链路唯一会造成用户可见故障的做法。</p>
   */
  public List<PrivateMessage> undeliveredCandidates(LocalDateTime now) {
    MindisleProperties.Pm cfg = properties.getPm();
    return store.listUndelivered(now.minusSeconds(cfg.getRetryGraceSeconds()), cfg.getRetryBatchLimit());
  }

  /** 给作业用的视图（{@code mine=false}：重投是替<b>收件人</b>渲染的）。 */
  public PmMessageView viewForReceiver(PrivateMessage row) {
    return viewOf(row, false);
  }

  // ================================================================ 拉黑（T5.7 · 需求 FR6.7）

  /**
   * 拉黑一个人（幂等）。返回的 {@code changed=false} 表示「他早就在你的名单里」，
   * 这不是错误：重复点「拉黑」第二次也应当是成功，否则前端要把 409 翻译成人话。
   *
   * <p><b>拉黑不删历史</b>：只写 {@code user_block} 一行，{@code private_message} 一个字都不动。
   * 删历史等于让被拉黑的人失去「我当时说过什么」的凭证，而那恰恰是举报和申诉需要的东西。</p>
   */
  @Transactional
  public PmBlockView block(long userId, Long targetId, String reason) {
    long target = requireTarget(targetId, userId);
    MindisleProperties.Pm cfg = properties.getPm();
    String trimmed = reason == null ? "" : reason.trim();
    if (trimmed.length() > cfg.getBlockReasonMax()) {
      throw new BizException(ErrorCode.PARAM_INVALID, "拉黑原因最多 " + cfg.getBlockReasonMax() + " 个字");
    }
    boolean changed = store.insertBlock(userId, target, trimmed.isEmpty() ? null : trimmed);
    if (changed) {
      log.info("拉黑生效 uid={} target={} reason={}", userId, target, trimmed.isEmpty() ? "（未填）" : trimmed);
    }
    return new PmBlockView(target, ACTION_ADD, changed, true);
  }

  /** 解除拉黑（物理删除：{@code user_block} 没有 deleted 列，见类注释）。 */
  @Transactional
  public PmBlockView unblock(long userId, Long targetId) {
    long target = requireTarget(targetId, userId);
    boolean changed = store.deleteBlock(userId, target);
    if (changed) {
      log.info("解除拉黑 uid={} target={}", userId, target);
    }
    return new PmBlockView(target, ACTION_REMOVE, changed, false);
  }

  /** 我的名单（U9 设置项）。对方已注销也要列出来，否则「名单里那个 208 是谁」永远查不到。 */
  public List<PmBlockItem> blocks(long userId) {
    int limit = properties.getPm().getBlockListMax();
    List<UserBlock> rows = store.listMyBlocks(userId, limit);
    List<PmBlockItem> items = new ArrayList<>(rows.size());
    for (UserBlock row : rows) {
      long peerId = row.getBlockUserId() == null ? 0L : row.getBlockUserId().longValue();
      if (peerId <= 0L) {
        continue;
      }
      User peer = store.findUser(peerId);
      items.add(new PmBlockItem(peerId, displayName(peer), avatarOf(peer), row.getReason(),
          row.getCreatedAt()));
    }
    return items;
  }

  // ================================================================ 举报私信（T5.7 · FR6.8）

  /**
   * 举报一条私信。<b>不写 {@code content_report}</b>：那张表的 {@code target_type} ENUM 只有
   * post / comment（sql/12_report.sql），塞 'pm' 会直接被 MySQL 拒掉；
   * 而「同一目标只有一张待审任务」在 {@code audit_task} 上有 {@code uk_target_pending} 兜住，
   * 于是私信举报走「直接建/升级审核任务」这一条更短的路。
   *
   * <p>这个取舍写进手册 §8 的偏离清单：<b>代价是私信举报拿不到「第 N 次举报」的累计计数</b>
   * （那半套判据长在 content_report 上），要补齐得给 content_report 加 ENUM 值 + 一次迁移。</p>
   */
  @Transactional
  public PmReportView report(long reporterId, PmReportRequest request, LocalDateTime now) {
    Long messageId = request == null ? null : request.messageId();
    if (messageId == null || messageId.longValue() <= 0L) {
      throw new BizException(ErrorCode.PARAM_INVALID, "缺少被举报的消息 id");
    }
    PrivateMessage row = store.findMessage(messageId.longValue());
    if (row == null) {
      throw new BizException(ErrorCode.PM_NOT_FOUND);
    }
    // 只能举报「我参与的会话里」的消息：否则任何人拿一个自增 id 就能把别人的私信送进审核队列。
    // <b>otherSide 只回答「另一端是谁」，分不出「我是发信方」和「我根本不是当事人」</b>：
    // 少了下面这道显式核对，第三人举报会走到「peer = 发信方 ≠ 举报人」这条路上被放行。
    long fromId = row.getFromUserId() == null ? 0L : row.getFromUserId().longValue();
    long toId = row.getToUserId() == null ? 0L : row.getToUserId().longValue();
    if (reporterId != fromId && reporterId != toId) {
      throw new BizException(ErrorCode.PM_NOT_FOUND);
    }
    long peer = otherSide(row, reporterId);
    if (peer == reporterId) {
      throw new BizException(ErrorCode.PM_NOT_FOUND);
    }
    String reason = normalizeReason(request.reason());
    String description = normalizeDescription(request.description());
    String verdict = verdictOf(recheck(row.getContent()));
    String level = CrisisGrader.levelOf(recheck(row.getContent()));
    Long taskId = ensureReportTask(messageId.longValue(), reason, description, verdict, level, reporterId, peer, now);
    String tip = "已收到，我们会在 " + slaHours(level) + " 小时内看完这条私信。";
    log.info("私信举报已受理 msgId={} reporterId={} peerId={} reason={} taskId={}",
        messageId, reporterId, peer, reason, taskId);
    return new PmReportView(taskId, AuditTask.STATUS_PENDING, tip);
  }

  // ================================================================ 危机与审核（T5.4 · FR6.6）

  /**
   * 机审命中危机词 → 确保这条私信在审核队列里。
   *
   * <p>{@code INSERT IGNORE} 返回 0 意味着并发里别人已经为同一条消息建了待办，
   * 此时<b>回填进实体的 id 不可信</b>（MySQL 照样消耗自增值），必须重新定位那条真实存在的任务。
   * 这段判据与 {@code ReportService#ensureTask} 同构，逐字照抄是为了让三条通道的工单行为一致。</p>
   */
  private void ensureMachineTask(long messageId, String level, String verdict, LocalDateTime now) {
    AuditTask existing = store.findPendingAuditTask(TARGET_PM, messageId);
    if (existing == null) {
      AuditTask task = newTask(messageId, verdict, level, now,
          "私信机审｜等级=" + level + "｜结论=" + verdict + "｜词库=" + wordEngine.version());
      if (store.insertAuditTask(task)) {
        return;
      }
      existing = store.findPendingAuditTask(TARGET_PM, messageId);
      if (existing == null) {
        return;
      }
    }
    if (rankOf(level) > rankOf(existing.getRiskLevel())) {
      store.escalateAuditTask(existing.getId(), level, scoreOf(level), slaOf(level, now), now);
    }
  }

  /** 举报建单：同一目标已有待办时只<b>往上抬</b>等级，不降级、不新建第二张。 */
  private Long ensureReportTask(long messageId, String reason, String description, String verdict,
      String level, long reporterId, long peerId, LocalDateTime now) {
    String remark = "私信举报｜理由=" + REPORT_LABELS.getOrDefault(reason, reason) + "(" + reason + ")"
        + "｜举报人=" + reporterId
        + "｜会话对方=" + peerId
        + "｜机审复核=" + verdict + "/" + level
        + "｜补充=" + (description == null || description.isEmpty() ? "（无）" : description)
        + "｜词库=" + wordEngine.version();
    AuditTask existing = store.findPendingAuditTask(TARGET_PM, messageId);
    if (existing == null) {
      AuditTask task = newTask(messageId, verdict, level, now, remark);
      task.setSource(AuditTask.SOURCE_REPORT);
      if (store.insertAuditTask(task)) {
        return task.getId();
      }
      existing = store.findPendingAuditTask(TARGET_PM, messageId);
      if (existing == null) {
        // 唯一键撞了又查不到：极小概率（对方刚把这条任务办结）。举报本身已经成立，回执里 taskId 留空。
        return null;
      }
    }
    if (rankOf(level) > rankOf(existing.getRiskLevel())) {
      store.escalateAuditTask(existing.getId(), level, scoreOf(level), slaOf(level, now), now);
    }
    return existing.getId();
  }

  private AuditTask newTask(long messageId, String verdict, String level, LocalDateTime now, String remark) {
    AuditTask task = new AuditTask();
    task.setTargetType(TARGET_PM);
    task.setTargetId(messageId);
    task.setSource(AuditTask.SOURCE_MACHINE);
    task.setChannel(AuditTask.CHANNEL_DFA);
    task.setResult(verdict == null ? null : cut(verdict, RESULT_MAX));
    task.setRiskLevel(level);
    task.setRiskScore(scoreOf(level));
    task.setStatus(AuditTask.STATUS_PENDING);
    task.setSlaAt(slaOf(level, now));
    // 不 setDeleted(0)：audit_task.deleted 有 DDL 默认值 0，而 MyBatis-Plus 的 @TableLogic
    // 只负责读侧过滤。这一点与 ReportService 里的写法不同，差异已记进手册 §8 偏离清单。
    task.setRemark(cut(remark, properties.getPm().getReportRemarkMax()));
    return task;
  }

  /**
   * 危机工单。<b>{@code user_id} 是发信方</b>：需求 §5.2 的工单是「这个人是高危的」，
   * 不是「这个人是受害者」——收件人只是恰好被选中的人，把工单挂到 TA 名下会让干预找错对象。
   *
   * <p>等级、分数、SLA、触发词、证据摘录五个字段全部复用 {@code post.CrisisGrader} 的口径，
   * 这是帖子/评论/私信三处工单能进同一张队列还保持可比的前提。</p>
   */
  private AlertTicket newTicket(long senderId, long messageId, String content,
      SensitiveWordEngine.CheckResult check, String level, LocalDateTime now) {
    MindisleProperties.Crisis crisis = properties.getCrisis();
    boolean urgent = CrisisGrader.L3.equals(level);
    AlertTicket ticket = new AlertTicket();
    ticket.setLevel(level);
    ticket.setUserId(senderId);
    ticket.setSourceType(TARGET_PM);
    ticket.setSourceId(messageId);
    ticket.setEvidenceText(cut(CrisisGrader.evidence(content, check, crisis.getEvidenceChars()), EVIDENCE_MAX));
    double score = urgent ? crisis.getL3Score() : crisis.getL2Score();
    ticket.setRiskScore(BigDecimal.valueOf(score).setScale(3, RoundingMode.HALF_UP));
    ticket.setTriggerWords(cut(CrisisGrader.triggerWords(check, TRIGGER_WORDS_MAX), TRIGGER_WORDS_MAX));
    ticket.setStatus("pending");
    ticket.setSlaAt(now.plus(urgent
        ? Duration.ofMinutes(crisis.getL3SlaMinutes())
        : Duration.ofHours(crisis.getL2SlaHours())));
    ticket.setCreatedAt(now);
    return ticket;
  }

  /** 等级 → 分数（与 {@code ReportService#scoreOf} 同一把尺子；L0 记 0.000 而不是 null，列是 NOT NULL）。 */
  private BigDecimal scoreOf(String level) {
    MindisleProperties.Crisis crisis = properties.getCrisis();
    double value = CrisisGrader.L3.equals(level) ? crisis.getL3Score()
        : CrisisGrader.L2.equals(level) ? crisis.getL2Score() : 0d;
    return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
  }

  /** 等级 → 处理时限。L0/L1 走举报默认时限（私信里它代表「内容问题」而不是「危机」）。 */
  private LocalDateTime slaOf(String level, LocalDateTime now) {
    MindisleProperties.Crisis crisis = properties.getCrisis();
    if (CrisisGrader.L3.equals(level)) {
      return now.plusMinutes(crisis.getL3SlaMinutes());
    }
    if (CrisisGrader.L2.equals(level)) {
      return now.plusHours(crisis.getL2SlaHours());
    }
    return now.plusHours(properties.getReport().getSlaHours());
  }

  private int slaHours(String level) {
    MindisleProperties.Crisis crisis = properties.getCrisis();
    if (CrisisGrader.L3.equals(level)) {
      // 30 分钟对外说「0.5 小时」不像人话，这里统一按小时向上取整，最少 1。
      return Math.max(1, (int) Math.ceil(crisis.getL3SlaMinutes() / 60d));
    }
    if (CrisisGrader.L2.equals(level)) {
      return crisis.getL2SlaHours();
    }
    return properties.getReport().getSlaHours();
  }

  /** 等级比较。<b>自带一份是因为 {@code ReportService.rankOf} 是包级 static，pm 包拿不到</b>。 */
  static int rankOf(String level) {
    if (CrisisGrader.L3.equals(level)) {
      return 3;
    }
    if (CrisisGrader.L2.equals(level)) {
      return 2;
    }
    if ("L1".equals(level)) {
      return 1;
    }
    return 0;
  }

  private static String higher(String a, String b) {
    return rankOf(b) > rankOf(a) ? b : a;
  }

  /**
   * 机审结论原文（进 {@code audit_task.result}，管理员要看的是「当时哪一档拦的」）。
   *
   * <p>判据与 {@code PostService} 一致：<b>看「有没有」而不是「排第几」</b>——
   * {@code CheckResult.action()} 是按处置强度挑出来的主因，
   * 「既写自伤又留手机号」会返回 grey/REVIEW，黑词被压在后面看不见。</p>
   */
  static String verdictOf(SensitiveWordEngine.CheckResult check) {
    if (check == null) {
      return "PASS";
    }
    if (check.hasAction(ACTION_BLOCK)) {
      return ACTION_BLOCK;
    }
    if (check.hasAction(ACTION_REVIEW)) {
      return ACTION_REVIEW;
    }
    if (check.riskTouched()) {
      return "RISK";
    }
    if (check.hasAction(ACTION_TAG)) {
      return ACTION_TAG;
    }
    return check.hit() ? "HIT" : "PASS";
  }

  // ================================================================ 视图与工具

  /**
   * 落库行 → 对外视图。
   *
   * <p>{@code mine} 是<b>视角参数而不是字段</b>：同一行在发信方和收信方屏幕上方向相反，
   * 而 {@code alert}（求助卡）也只有收信方该看到「对方可能需要帮助」这句话。
   * 把视角固化进实体就会变成两张表或一个多余列。</p>
   */
  public PmMessageView viewOf(PrivateMessage row, boolean mine) {
    String alert = null;
    if (row.getRiskLevel() != null && TICKET_LEVELS.contains(row.getRiskLevel())) {
      alert = mine
          ? "你写的这句话让屿安很在意。已经悄悄帮你把这条留在待审队列里，也可以直接拨打 "
              + properties.getCrisis().getHotline() + "（24 小时有人接）。"
          : "对方此刻可能很需要支持。你不需要一个人扛，也可以拨打 "
              + properties.getCrisis().getHotline() + "。";
    }
    return new PmMessageView(row.getId(), row.getFromUserId(), row.getToUserId(), mine,
        row.getMsgType(), row.getContent(), row.getRiskLevel(), row.getStatus(),
        row.getReadAt(), row.getCreatedAt(), alert);
  }

  /** 会话列表里的摘要：图片消息只显示「[图片]」，正文按码点截 60（通知中心同口径）。 */
  static String preview(PrivateMessage tail) {
    if (tail == null || tail.getContent() == null) {
      return "";
    }
    if (PrivateMessage.TYPE_IMAGE.equals(tail.getMsgType())) {
      return IMAGE_PREVIEW;
    }
    return cut(tail.getContent(), PREVIEW_MAX);
  }

  private static String normalizeClientMsgId(String clientMsgId, MindisleProperties.Pm cfg) {
    String value = clientMsgId == null ? "" : clientMsgId.trim();
    if (value.isEmpty()) {
      return "srv-" + java.util.UUID.randomUUID();
    }
    if (value.length() > cfg.getClientMsgIdMaxChars()) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "幂等键最多 " + cfg.getClientMsgIdMaxChars() + " 个字符");
    }
    return value;
  }

  /**
   * 长度闸门按<b>码点</b>而不是 char：{@code "😀".length()} 是 2，
   * 用 char 数会把「一个笑」当两个字，用户数到 500 就发不出去，而库里明明还有空间。
   */
  private static String normalizeContent(String content, MindisleProperties.Pm cfg) {
    String value = content == null ? "" : content.trim();
    if (value.isEmpty()) {
      throw new BizException(ErrorCode.PARAM_INVALID, "私信内容不能是空的");
    }
    if (value.codePointCount(0, value.length()) > cfg.getMaxContentChars()) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "私信最多 " + cfg.getMaxContentChars() + " 个字，这条有 "
              + value.codePointCount(0, value.length()) + " 个");
    }
    return value;
  }

  private static String normalizeMsgType(String msgType, String content) {
    String value = msgType == null || msgType.isBlank() ? PrivateMessage.TYPE_TEXT : msgType.trim();
    if (!USER_MSG_TYPES.contains(value)) {
      throw new BizException(ErrorCode.PARAM_INVALID, "消息类型只能是文字或图片");
    }
    if (PrivateMessage.TYPE_IMAGE.equals(value) && !content.startsWith(UPLOAD_PREFIX)) {
      // 只认站内上传路径：外链图片会被第三方服务器统计谁看过它，而私信里出现的外部域名还常被用来钓鱼。
      throw new BizException(ErrorCode.PARAM_INVALID, "图片私信只能指向站内上传的图片");
    }
    return value;
  }

  private static String normalizeReason(String reason) {
    String value = reason == null || reason.isBlank() ? "other" : reason.trim();
    if (!REPORT_LABELS.containsKey(value)) {
      throw new BizException(ErrorCode.PARAM_INVALID, "举报理由只能是："
          + String.join(" / ", REPORT_LABELS.keySet()));
    }
    return value;
  }

  private String normalizeDescription(String description) {
    if (description == null) {
      return null;
    }
    return cut(description.trim(), properties.getPm().getReportRemarkMax());
  }

  private SensitiveWordEngine.CheckResult recheck(String content) {
    wordEngine.refreshIfStale(cacheService, properties.getAudit().getDictVersionKey());
    return wordEngine.check(content == null ? "" : content, "user");
  }

  private long requireTarget(Long targetId, long userId) {
    if (targetId == null || targetId.longValue() <= 0L) {
      throw new BizException(ErrorCode.PARAM_INVALID, "缺少目标用户 id");
    }
    long target = targetId.longValue();
    if (target == userId) {
      throw new BizException(ErrorCode.PARAM_INVALID, "不能拉黑自己");
    }
    if (store.findUser(target) == null) {
      throw new BizException(ErrorCode.USER_NOT_FOUND);
    }
    return target;
  }

  private int normalizeSize(Integer size) {
    MindisleProperties.Pm cfg = properties.getPm();
    if (size == null || size.intValue() <= 0) {
      return cfg.getDefaultSize();
    }
    return Math.min(size.intValue(), cfg.getFetchMax());
  }

  private static boolean isGone(User user) {
    if (user == null) {
      return true;
    }
    if (user.getDeleted() != null && user.getDeleted().intValue() == 1) {
      return true;
    }
    String status = user.getStatus() == null ? "" : user.getStatus().trim().toUpperCase();
    return GONE_STATUSES.contains(status);
  }

  /** 这条消息的「对方」是谁（相对 {@code userId} 而言）。同表双角色，不需要两份表。 */
  static long otherSide(PrivateMessage row, long userId) {
    long from = row.getFromUserId() == null ? 0L : row.getFromUserId().longValue();
    long to = row.getToUserId() == null ? 0L : row.getToUserId().longValue();
    return from == userId ? to : from;
  }

  private static boolean isMine(PrivateMessage row, long userId) {
    return row.getFromUserId() != null && row.getFromUserId().longValue() == userId;
  }

  private static String displayName(User user) {
    return user == null ? "已注销用户" : PostService.displayNameOf(user);
  }

  private static String avatarOf(User user) {
    return user == null || user.getAvatar() == null ? "" : user.getAvatar();
  }

  private static int length(String text) {
    return text == null ? 0 : text.length();
  }

  /**
   * 按<b>码点</b>截断，且不会把一个代理对切成两半。
   *
   * <p>{@code ai.SafetyGuard#cut} 与 {@code post.PostService#cut} 都是包级可见，pm 包借不到，
   * 所以这里自带第三份。三处实现同一条判据已写进手册 §14 的重复清单，
   * 将来抽 {@code TextUtils} 时要一起收掉，别只删这一处。</p>
   */
  static String cut(String text, int maxCodePoints) {
    if (text == null || maxCodePoints <= 0) {
      return "";
    }
    if (text.codePointCount(0, text.length()) <= maxCodePoints) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
  }
}

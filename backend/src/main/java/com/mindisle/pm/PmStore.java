package com.mindisle.pm;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.mindisle.entity.AlertTicket;
import com.mindisle.entity.AuditTask;
import com.mindisle.entity.PrivateMessage;
import com.mindisle.entity.User;
import com.mindisle.entity.UserBlock;

/**
 * 私信域唯一的数据出口（任务 T5.2–T5.6 · 需求 FR6）。
 *
 * <p>形状照 {@code RelationshipService.RelationStore} 与 {@code NotifyService.NotifyStore}：
 * Service 里只留判据，SQL 全推到适配器后面。这一层在本阶段<b>真正被用到</b>的地方是单测——
 * {@code PmFakeStore} 用内存 map 顶掉 MySQL，于是「幂等重发只落一行且不重复推送」「双向拉黑都 403」
 * 「已读不回刷 read_at」这三条判据可以在 1 秒内跑完，而不需要起库。</p>
 *
 * <p>端口里的方法名刻意带业务语义（{@code markDelivered} 而不是 {@code updateStatus}）：
 * 一旦端口退化成「把实体塞进 mapper 的任意列」，Service 就直接依赖了列名，
 * 换库或改字段时会把业务层一起改掉，那是这一层想解决的问题而不是它的产物。</p>
 */
public interface PmStore {

  // ================================================================ 用户

  /** 按 id 取用户；不存在返回 null（私信侧把它当成「收件人不存在」而不是「空昵称」）。 */
  User findUser(long userId);

  // ================================================================ 消息

  /**
   * 幂等插入。返回 {@code true} 表示<b>真的新增了一行</b>；{@code false} 表示撞了
   * {@code uk_pair_msg}（{@code INSERT IGNORE} 吃进 1062），此时调用方必须用
   * {@link #findByClientMsg} 回查那一条真实存在的行——{@code row.getId()} 在这条分支上不可信。
   */
  boolean insertMessage(PrivateMessage row);

  /** 按 (clientMsgId, 接收方) 回查，走 uk_pair_msg 全键。 */
  PrivateMessage findByClientMsg(String clientMsgId, long toUserId);

  /** 比 {@code beforeId} 更旧的 limit 条，id 倒序（时间正序由 Service 翻）。 */
  List<PrivateMessage> pageThread(long userId, long peerId, Long beforeId, int limit);

  /** 每条会话「最后一句」那一批行，按 last id 倒序；对方 id 由调用方折叠。 */
  List<PrivateMessage> listConversationTails(long userId, Long beforeId, int limit);

  /**
   * 这些人各自发给我的未读条数。<b>返回的 map 对每个请求过的 peerId 都有键</b>
   * （无未读是 0，不是缺键）——缺键会让调用方在每个使用点重学一次「没有就是 0」，
   * 而「没有就是 0」恰好是最容易写成 {@code map.get(..)} 然后拆箱 NPE 的地方。
   */
  Map<Long, Integer> unreadByPeers(long userId, List<Long> peerIds);

  /** 我的私信未读总数（角标）。 */
  long countUnreadTotal(long userId);

  /** 把「我收到的、来自这个人的、id 不超过 upToId 的、还没读的」置为已读，返回真的变过的行数。 */
  int markThreadRead(long userId, long peerId, long upToId, LocalDateTime now);

  /** sent → delivered，只改还没送达的行；返回 0 表示这条早已是 delivered/read。 */
  int markDelivered(long messageId);

  /** 重投候选：状态还是 sent 且创建时间早于 {@code beforeCreated}。 */
  List<PrivateMessage> listUndelivered(LocalDateTime beforeCreated, int limit);

  /** 与我有过私信的人（在线状态只通知这些人，见 {@code PrivateMessageMapper#listPeerIds} 注释）。 */
  List<Long> listPeerIds(long userId);

  /** 按 id 取一条消息；不存在或是别人的消息都由调用方判（本方法只负责读）。 */
  PrivateMessage findMessage(long messageId);

  // ================================================================ 拉黑

  /** 任一方向存在拉黑即为 true（FR6.7 的双向语义）。 */
  boolean isBlockedAny(long a, long b);

  /** 幂等拉黑，返回 true 表示本次真的写了行。 */
  boolean insertBlock(long userId, long targetId, String reason);

  /** 物理删除，返回 true 表示本次真的删了一行（本来没拉黑就是 false）。 */
  boolean deleteBlock(long userId, long targetId);

  /** 我拉黑了谁。 */
  List<UserBlock> listMyBlocks(long userId, int limit);

  // ================================================================ 预警与审核

  /** 危机工单（{@code alert_ticket}）；返回 true 表示落库成功。 */
  boolean insertTicket(AlertTicket ticket);

  /** 审核任务（{@code audit_task}）；INSERT IGNORE，返回 false 表示同目标已有待办。 */
  boolean insertAuditTask(AuditTask task);

  /** 这条目标是否已有待审任务（并发建单后回查用）。 */
  AuditTask findPendingAuditTask(String targetType, long targetId);

  /** 等级上升时把已存在的待审任务抬上来（不降）。 */
  void escalateAuditTask(long taskId, String riskLevel, BigDecimal riskScore, LocalDateTime slaAt, LocalDateTime now);

  /** 管理员 id 列表（危机私信要弹给他们）。 */
  List<Long> listAdminIds();
}

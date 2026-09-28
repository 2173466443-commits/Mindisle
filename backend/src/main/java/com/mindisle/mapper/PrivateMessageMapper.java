package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.PrivateMessage;
import com.mindisle.pm.dto.PmPeerUnreadRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 私信 Mapper（任务 T5.3/T5.4 · 需求 FR6.1–FR6.6 · sql/06_pm.sql 第 22 表）。
 *
 * <p><b>本类的每一条 {@code deleted = 0} 都是手写的</b>：自定义 {@code @Select}/{@code @Update}
 * 不会被 {@code @TableLogic} 拦截器改写（阶段 3 就踩过一次，判据已进全局日志）。
 * 私信上的软删目前是「注销后清除」那条链路才会用到，但少写一句就是把别人的私密内容
 * 算进未读数——那条 bug 的后果比帖子列表多一行要重得多。</p>
 *
 * <p><b>会话读取为什么是两条范围查询 UNION，而不是一条 {@code WHERE (a,b) OR (b,a)}</b>：
 * {@code idx_pair(from_user_id, to_user_id, id)} 只覆盖一个方向。写成 OR 时优化器只能选
 * 一条索引前缀甚至全表扫；拆成 UNION ALL 的两个分支各自走一次 {@code (from,to,id)} 范围扫，
 * 才是这条索引真正被建出来的理由。这一点在 explain 上能直接看到，别凭感觉改回去。</p>
 */
@Mapper
public interface PrivateMessageMapper extends BaseMapper<PrivateMessage> {

  /**
   * 幂等插入：撞 {@code uk_pair_msg(client_msg_id, to_user_id)} 返回 0 行而不是 1062 报错。
   *
   * <p>断线重发、双击发送、HTTP 兜底与 STOMP 同时到达，四种来源在用户侧都是「我发了一次」，
   * 落库成两条才是 bug。返回 0 时调用方必须回查那一条真实存在的行
   * （MySQL 照样消耗自增值，回填进实体的 id 不可信——与 {@link AuditTaskMapper#insertIgnore}
   * 同一条坑，口径不再重复实现第二遍）。</p>
   *
   * <p><b>为什么 created_at 在列清单里（2026-09-29 Gate5 实测改）</b>：DDL 的默认值只能保证
   * 「库里有时间」，保证不了「返回给调用方的那个对象里有时间」。改之前这条 INSERT 不写它，
   * 于是 {@code PmService#send} 用同一个 row 拼出的 REST 响应体与 WS 推送帧里 createdAt 是 null，
   * 而 {@code spring.jackson.default-property-inclusion: non_null} 会把 null 字段整个删掉——
   * 症状是「刚收到/刚发出的那条气泡没有分钟数、日期分组掉进『更早』」，前端只能兜本地时钟，
   * 翻页之后又跳回数据库时间。幂等重发那条分支走 findByClientMsg 回查、反倒带着时间：
   * 同一个接口两种形状，比单纯「没有」更难查。单测也照不出这条，因为 PmFakeStore 自己补了
   * createdAt（见它的 insertMessage：只在为 null 时补），库侧缺的这一段被假实现填平了。
   * updated_at 仍交给 DDL：它只由 markDelivered / markRead 那两条 UPDATE 触发。</p>
   */
  @Insert("INSERT IGNORE INTO private_message "
      + "(from_user_id, to_user_id, client_msg_id, msg_type, content, risk_level, status, "
      + "created_at) "
      + "VALUES (#{fromUserId}, #{toUserId}, #{clientMsgId}, #{msgType}, #{content}, "
      + "#{riskLevel}, #{status}, #{createdAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insertIgnore(PrivateMessage row);

  /** 按幂等键回查（{@code insertIgnore} 返回 0 之后用它定位那一条）。走 uk_pair_msg 全键。 */
  @Select("SELECT * FROM private_message WHERE client_msg_id = #{clientMsgId} "
      + "AND to_user_id = #{toUserId} AND deleted = 0 LIMIT 1")
  PrivateMessage findByClientMsg(@Param("clientMsgId") String clientMsgId,
      @Param("toUserId") long toUserId);

  /**
   * 会话翻页：取两个人之间比 {@code beforeId} 更旧的 {@code limit} 条（不含）。
   *
   * <p>排序键用自增 id 而不是 created_at——DDL 注释里那句「同毫秒不丢消息」说的就是这件事，
   * 与 notify_message、feed 游标三处同一个理由。</p>
   */
  @Select("SELECT * FROM ("
      + "SELECT id, from_user_id, to_user_id, client_msg_id, msg_type, content, risk_level, "
      + "status, read_at, deleted, created_at, updated_at FROM private_message "
      + "WHERE from_user_id = #{userId} AND to_user_id = #{peerId} AND deleted = 0 "
      + "UNION ALL "
      + "SELECT id, from_user_id, to_user_id, client_msg_id, msg_type, content, risk_level, "
      + "status, read_at, deleted, created_at, updated_at FROM private_message "
      + "WHERE from_user_id = #{peerId} AND to_user_id = #{userId} AND deleted = 0"
      + ") t WHERE t.id < #{beforeId} ORDER BY t.id DESC LIMIT #{limit}")
  List<PrivateMessage> pageThread(@Param("userId") long userId, @Param("peerId") long peerId,
      @Param("beforeId") long beforeId, @Param("limit") int limit);

  /**
   * 会话列表的「每条会话最后一句」：按对方分组取 {@code MAX(id)}，再把那些行本身读出来。
   *
   * <p>返回的是 {@link PrivateMessage}（列表页要的正是那一行的内容、时间与类型），
   * 对方 id 由调用方按 {@code from_user_id == 我 ? to_user_id : from_user_id} 折叠——
   * 这一步放在 Java 而不是 SQL，是因为它同时决定「这条消息在我这边算发出还是收到」，
   * 两件事必须用同一个判断，写在 SQL 里就变成两套口径。</p>
   */
  @Select("SELECT m.* FROM private_message m JOIN ("
      + "SELECT MAX(id) AS last_id FROM ("
      + "SELECT id, to_user_id AS peer_id FROM private_message WHERE from_user_id = #{userId} AND deleted = 0 AND id < #{beforeId} "
      + "UNION ALL "
      + "SELECT id, from_user_id AS peer_id FROM private_message WHERE to_user_id = #{userId} AND deleted = 0 AND id < #{beforeId}"
      + ") x GROUP BY peer_id) p ON p.last_id = m.id ORDER BY m.id DESC LIMIT #{limit}")
  List<PrivateMessage> listConversationTails(@Param("userId") long userId,
      @Param("beforeId") long beforeId, @Param("limit") int limit);

  /**
   * 一次给出多个对方的未读数（{@code GROUP BY from_user_id}）。
   *
   * <p><b>为什么按 id 列表批量而不是每个会话数一次</b>：会话列表页最多 50 条，
   * N+1 次 COUNT 就是 50 次往返，而它出现在「点开私信」这一下操作上，
   * 是全站最容易第一眼慢下来的地方。走 {@code idx_to_status(to_user_id, status, id)} 的前两列。</p>
   *
   * <p>没有未读行的那些 id <b>不会出现在结果里</b>，这是 SQL 的事实而不是遗漏，
   * 调用方必须自己补 0——把这个补零放到 mapper 里做不了，所以放在
   * {@link com.mindisle.pm.PmStoreAdapter}，并要求单测钉住「缺行 ≠ 报错」。</p>
   */
  @Select("<script>SELECT from_user_id AS peer_id, COUNT(*) AS unread_cnt FROM private_message "
      + "WHERE to_user_id = #{userId} AND deleted = 0 AND status &lt;&gt; 'read' "
      + "AND from_user_id IN "
      + "<foreach collection='peerIds' item='it' open='(' separator=',' close=')'>#{it}</foreach>"
      + " GROUP BY from_user_id</script>")
  List<PmPeerUnreadRow> listUnreadByPeer(@Param("userId") long userId,
      @Param("peerIds") List<Long> peerIds);

  /** 我的私信未读总数（角标）。走 idx_to_status 前三列。 */
  @Select("SELECT COUNT(*) FROM private_message WHERE to_user_id = #{userId} "
      + "AND deleted = 0 AND status <> 'read'")
  long countUnreadTotal(@Param("userId") long userId);

  /**
   * 标已读：只把「<b>我收到的</b>、来自这个人的、id 不超过 upToId 的、还没读的」置成 read。
   *
   * <p>{@code to_user_id = #{userId}} 是越权闸（缺了它任何人都能把别人的私信标成已读，
   * 而「已读」是不可逆的状态位，比多读一次严重）；{@code status <> 'read'} 让影响行数
   * 等于「这次真的变已读的行数」，回执里的数字才有意义——与 {@code NotifyMessageMapper#markRead}
   * 同一个理由。</p>
   *
   * <p>{@code read_at} 只在从未读到已读这一次写入，之后不再刷新：
   * 它是「对方第一次看到这句话」的时间，不是「对方最后一次打开页面」的时间。</p>
   */
  @Update("UPDATE private_message SET status = 'read', read_at = #{now} "
      + "WHERE to_user_id = #{userId} AND from_user_id = #{peerId} AND deleted = 0 "
      + "AND status <> 'read' AND id <= #{upToId}")
  int markThreadRead(@Param("userId") long userId, @Param("peerId") long peerId,
      @Param("upToId") long upToId, @Param("now") LocalDateTime now);

  /**
   * 送达（sent → delivered）：只在消息真的写进了对方一个活跃会话之后调用。
   *
   * <p>{@code AND status = 'sent'} 而不是无条件改：已读是比送达更强的状态，
   * 把已读改回送达等于把「对方看过了」这句真话抹掉。</p>
   */
  @Update("UPDATE private_message SET status = 'delivered' "
      + "WHERE id = #{id} AND status = 'sent' AND deleted = 0")
  int markDelivered(@Param("id") long id);

  /** 重投候选：还没送达、创建时间早于 {@code beforeCreated}（给对方一秒正常的接收窗口）的消息。 */
  @Select("SELECT * FROM private_message WHERE status = 'sent' AND deleted = 0 "
      + "AND created_at < #{beforeCreated} ORDER BY id ASC LIMIT #{limit}")
  List<PrivateMessage> listUndelivered(@Param("beforeCreated") LocalDateTime beforeCreated,
      @Param("limit") int limit);

  /**
   * 与我有过会话的所有人（在线状态要通知给谁）。
   *
   * <p>只给「曾经互发过私信的人」，不给全站、也不给关注关系：
   * 需求 FR6.3 的在线状态是「和我聊天的人在不在线」，把它扩成「谁都能查我在不在」
   * 是在私信里新增一处隐私扩散，而那一处没有任何一条需求要它。</p>
   */
  @Select("SELECT DISTINCT peer_id FROM ("
      + "SELECT to_user_id AS peer_id FROM private_message WHERE from_user_id = #{userId} AND deleted = 0 "
      + "UNION ALL "
      + "SELECT from_user_id AS peer_id FROM private_message WHERE to_user_id = #{userId} AND deleted = 0"
      + ") x WHERE peer_id <> #{userId}")
  List<Long> listPeerIds(@Param("userId") long userId);
}

package com.mindisle.pm;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.mindisle.entity.AlertTicket;
import com.mindisle.entity.AuditTask;
import com.mindisle.entity.PrivateMessage;
import com.mindisle.entity.User;
import com.mindisle.entity.UserBlock;

/**
 * {@link PmStore} 的内存替身（阶段 5 单测专用，任务 T5.2–T5.7）。
 *
 * <p><b>本类唯一的价值是逐字复刻 {@link PrivateMessageMapper} 与 {@link UserBlockMapper} 的谓词</b>。
 * 替身只要比 SQL 宽松，单测就会绿在一个不存在的行为上：把 {@code markThreadRead} 写成
 * 「不管状态一律改 read」，那条「已读行不再刷 read_at」的判据就永远不会被钉住；
 * 把 {@code unreadByPeers} 写成「只返回有未读的人」，Service 侧 {@code map.get(peer)} 的拆箱
 * NPE 就会在真库里发生而单测全绿。所以下面每个方法旁边都注明它复刻的是哪条 SQL、
 * 哪一个条件；改 SQL 时必须同步改这里——两处不一致的那一刻，这份替身就从资产变成负债。</p>
 *
 * <p><b>写入存副本、读出也给副本</b>：MySQL 不会让调用方握着「同一行对象」。
 * 不复刻这一条，测试里就会出现「先拿到的视图后来被别的写改了状态」这种线上不可能有的现象。</p>
 *
 * <p><b>刻意不复刻的东西</b>：索引、{@code INSERT IGNORE} 撞键时自增值照样被吃掉（它真正的
 * 后果是「回填的 id 不可信」，本类直接用「撞键不回填 id」表达，更贴近要钉的判据）、
 * ENUM/CHECK 约束（写错枚举在真库是 1265 报错、在这里静默通过——那条由 docs 里的 HTTP 探针负责，
 * 不假装单测测过 SQL）。</p>
 */
class PmFakeStore implements PmStore {

    /** 用户表：昵称、状态、软删位都由调用方给，本类不猜业务默认值。 */
    final Map<Long, User> users = new LinkedHashMap<>();

    /** 消息表，按 id 升序保存（与自增主键同序，翻历史时不需要再排序）。 */
    final List<PrivateMessage> messages = new ArrayList<>();

    /** 拉黑表。物理删除语义，没有 deleted 列。 */
    final List<UserBlock> blocks = new ArrayList<>();

    final List<AlertTicket> tickets = new ArrayList<>();

    final List<AuditTask> auditTasks = new ArrayList<>();

    /** {@code listAdminIds} 的返回值，默认两个管理员，测试可清空模拟「没人接」。 */
    final List<Long> adminIds = new ArrayList<>(List.of(1L, 2L));

    /** 新行的 created_at（复刻 DDL 的 CURRENT_TIMESTAMP；想让时间倒挂就改它）。 */
    LocalDateTime clock = LocalDateTime.of(2026, 9, 28, 9, 0);

    private long messageSeq;
    private long blockSeq;
    private long ticketSeq;
    private long taskSeq;

    // ---------------------------------------------------------------- 造数据入口

    PmFakeStore withUser(User user) {
        users.put(user.getId(), user);
        return this;
    }

    /** 直接放一条已存在的消息（跳过幂等键检查，用于构造历史会话与举报对象）。 */
    PrivateMessage seedMessage(long from, long to, String content, String msgType, String status,
            String riskLevel) {
        PrivateMessage row = new PrivateMessage();
        row.setId(++messageSeq);
        row.setFromUserId(from);
        row.setToUserId(to);
        row.setClientMsgId("seed-" + row.getId());
        row.setMsgType(msgType);
        row.setContent(content);
        row.setRiskLevel(riskLevel);
        row.setStatus(status);
        row.setDeleted(0);
        row.setCreatedAt(clock);
        row.setUpdatedAt(clock);
        messages.add(row);
        return row;
    }

    /** id 最大的一条（断言「刚刚落库那一行」用它，别在测试里写死 id）。 */
    PrivateMessage lastMessage() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    AlertTicket lastTicket() {
        return tickets.isEmpty() ? null : tickets.get(tickets.size() - 1);
    }

    AuditTask lastAuditTask() {
        return auditTasks.isEmpty() ? null : auditTasks.get(auditTasks.size() - 1);
    }

    // ---------------------------------------------------------------- 用户

    @Override
    public User findUser(long userId) {
        return users.get(userId);
    }

    // ---------------------------------------------------------------- 消息

    /**
     * 复刻 {@code INSERT IGNORE ... uk_pair_msg(client_msg_id, to_user_id)}：
     * 撞键返回 false，且<b>不覆盖已有行、不回填 id</b>。
     *
     * <p>唯一键看不见 {@code deleted}，所以软删行照样占住这个键——这正是
     * {@code PmService#send} 里「撞键但回查为空 → 90004」那条分支的成因。</p>
     */
    @Override
    public boolean insertMessage(PrivateMessage row) {
        for (PrivateMessage exist : messages) {
            if (Objects.equals(exist.getClientMsgId(), row.getClientMsgId())
                    && Objects.equals(exist.getToUserId(), row.getToUserId())) {
                return false;
            }
        }
        PrivateMessage copy = copy(row);
        copy.setId(++messageSeq);
        if (copy.getDeleted() == null) {
            copy.setDeleted(0);
        }
        if (copy.getCreatedAt() == null) {
            copy.setCreatedAt(clock);
        }
        if (copy.getUpdatedAt() == null) {
            copy.setUpdatedAt(clock);
        }
        messages.add(copy);
        row.setId(copy.getId());
        return true;
    }

    /** 复刻 {@code WHERE client_msg_id = ? AND to_user_id = ? AND deleted = 0 LIMIT 1}。 */
    @Override
    public PrivateMessage findByClientMsg(String clientMsgId, long toUserId) {
        for (PrivateMessage row : messages) {
            if (Objects.equals(row.getClientMsgId(), clientMsgId)
                    && Objects.equals(row.getToUserId(), toUserId) && alive(row)) {
                return copy(row);
            }
        }
        return null;
    }

    /** 复刻 UNION ALL 双向 + {@code id < beforeId} + {@code ORDER BY id DESC LIMIT n}。 */
    @Override
    public List<PrivateMessage> pageThread(long userId, long peerId, Long beforeId, int limit) {
        long cursor = beforeId == null ? Long.MAX_VALUE : beforeId;
        List<PrivateMessage> hit = new ArrayList<>();
        for (PrivateMessage row : messages) {
            if (!alive(row) || row.getId() >= cursor || !pairMatches(row, userId, peerId)) {
                continue;
            }
            hit.add(copy(row));
        }
        hit.sort(Comparator.comparingLong(PrivateMessage::getId).reversed());
        return truncate(hit, limit);
    }

    /** 复刻「按对方分组取 MAX(id) 的那些行，再按 m.id 倒序」。 */
    @Override
    public List<PrivateMessage> listConversationTails(long userId, Long beforeId, int limit) {
        long cursor = beforeId == null ? Long.MAX_VALUE : beforeId;
        Map<Long, Long> lastIdByPeer = new LinkedHashMap<>();
        for (PrivateMessage row : messages) {
            if (!alive(row) || row.getId() >= cursor) {
                continue;
            }
            Long peer = peerOf(row, userId);
            if (peer != null) {
                lastIdByPeer.merge(peer, row.getId(), Math::max);
            }
        }
        List<PrivateMessage> tails = new ArrayList<>();
        for (Long id : lastIdByPeer.values()) {
            for (PrivateMessage row : messages) {
                if (Objects.equals(row.getId(), id)) {
                    tails.add(copy(row));
                }
            }
        }
        tails.sort(Comparator.comparingLong(PrivateMessage::getId).reversed());
        return truncate(tails, limit);
    }

    /**
     * 复刻 {@code GROUP BY from_user_id} 的计数，并按 {@link PmStoreAdapter} 的契约
     * <b>为每个请求过的 peer 补 0</b>（键序 = 请求顺序，与适配器一致）。
     */
    @Override
    public Map<Long, Integer> unreadByPeers(long userId, List<Long> peerIds) {
        Map<Long, Integer> filled = new LinkedHashMap<>();
        if (peerIds == null) {
            return filled;
        }
        for (Long peerId : peerIds) {
            if (peerId != null) {
                filled.put(peerId, 0);
            }
        }
        for (Map.Entry<Long, Integer> entry : filled.entrySet()) {
            int count = 0;
            for (PrivateMessage row : messages) {
                if (alive(row) && Objects.equals(row.getToUserId(), userId)
                        && Objects.equals(row.getFromUserId(), entry.getKey())
                        && !PrivateMessage.STATUS_READ.equals(row.getStatus())) {
                    count++;
                }
            }
            entry.setValue(count);
        }
        return filled;
    }

    @Override
    public long countUnreadTotal(long userId) {
        long count = 0;
        for (PrivateMessage row : messages) {
            if (alive(row) && Objects.equals(row.getToUserId(), userId)
                    && !PrivateMessage.STATUS_READ.equals(row.getStatus())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 复刻 {@code to_user_id = 我 AND from_user_id = 对方 AND deleted = 0 AND status <> 'read'
     * AND id <= upToId}。
     *
     * <p><b>{@code status <> 'read'} 是本类最容易被写松的一句</b>：少了它，已读时间会在每次打开
     * 页面时被刷成最新，而那个字段的语义是「对方第一次看到这句话」的时间。</p>
     */
    @Override
    public int markThreadRead(long userId, long peerId, long upToId, LocalDateTime now) {
        int changed = 0;
        for (PrivateMessage row : messages) {
            if (alive(row) && Objects.equals(row.getToUserId(), userId)
                    && Objects.equals(row.getFromUserId(), peerId) && row.getId() <= upToId
                    && !PrivateMessage.STATUS_READ.equals(row.getStatus())) {
                row.setStatus(PrivateMessage.STATUS_READ);
                row.setReadAt(now);
                row.setUpdatedAt(now);
                changed++;
            }
        }
        return changed;
    }

    /** 复刻 {@code WHERE id = ? AND status = 'sent' AND deleted = 0}：已读行不会被改回送达。 */
    @Override
    public int markDelivered(long messageId) {
        for (PrivateMessage row : messages) {
            if (Objects.equals(row.getId(), messageId) && alive(row)
                    && PrivateMessage.STATUS_SENT.equals(row.getStatus())) {
                row.setStatus(PrivateMessage.STATUS_DELIVERED);
                return 1;
            }
        }
        return 0;
    }

    @Override
    public List<PrivateMessage> listUndelivered(LocalDateTime beforeCreated, int limit) {
        List<PrivateMessage> hit = new ArrayList<>();
        for (PrivateMessage row : messages) {
            if (alive(row) && PrivateMessage.STATUS_SENT.equals(row.getStatus())
                    && row.getCreatedAt() != null && row.getCreatedAt().isBefore(beforeCreated)) {
                hit.add(copy(row));
            }
        }
        hit.sort(Comparator.comparingLong(PrivateMessage::getId));
        return truncate(hit, limit);
    }

    @Override
    public List<Long> listPeerIds(long userId) {
        Set<Long> peers = new LinkedHashSet<>();
        for (PrivateMessage row : messages) {
            if (!alive(row)) {
                continue;
            }
            Long peer = peerOf(row, userId);
            if (peer != null && peer != userId) {
                peers.add(peer);
            }
        }
        return new ArrayList<>(peers);
    }

    /** 复刻 {@code selectById}：{@code @TableLogic} 会自动给它补上 {@code deleted = 0}。 */
    @Override
    public PrivateMessage findMessage(long messageId) {
        for (PrivateMessage row : messages) {
            if (Objects.equals(row.getId(), messageId) && alive(row)) {
                return copy(row);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 拉黑

    @Override
    public boolean isBlockedAny(long a, long b) {
        for (UserBlock row : blocks) {
            boolean forward = Objects.equals(row.getUserId(), a) && Objects.equals(row.getBlockUserId(), b);
            boolean reverse = Objects.equals(row.getUserId(), b) && Objects.equals(row.getBlockUserId(), a);
            if (forward || reverse) {
                return true;
            }
        }
        return false;
    }

    /** 复刻 {@code INSERT IGNORE INTO user_block} 撞 {@code uk_user_block(user_id, block_user_id)}。 */
    @Override
    public boolean insertBlock(long userId, long targetId, String reason) {
        if (ownBlockExists(userId, targetId)) {
            return false;
        }
        UserBlock row = new UserBlock();
        row.setId(++blockSeq);
        row.setUserId(userId);
        row.setBlockUserId(targetId);
        row.setReason(reason);
        row.setCreatedAt(clock);
        blocks.add(row);
        return true;
    }

    private boolean ownBlockExists(long userId, long targetId) {
        for (UserBlock row : blocks) {
            if (Objects.equals(row.getUserId(), userId) && Objects.equals(row.getBlockUserId(), targetId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean deleteBlock(long userId, long targetId) {
        return blocks.removeIf(row -> Objects.equals(row.getUserId(), userId)
                && Objects.equals(row.getBlockUserId(), targetId));
    }

    @Override
    public List<UserBlock> listMyBlocks(long userId, int limit) {
        List<UserBlock> mine = new ArrayList<>();
        for (UserBlock row : blocks) {
            if (Objects.equals(row.getUserId(), userId)) {
                mine.add(row);
            }
        }
        mine.sort(Comparator.comparingLong(UserBlock::getId).reversed());
        return truncate(mine, limit);
    }

    // ---------------------------------------------------------------- 工单与审核任务

    @Override
    public boolean insertTicket(AlertTicket ticket) {
        ticket.setId(++ticketSeq);
        if (ticket.getDeleted() == null) {
            ticket.setDeleted(0);
        }
        tickets.add(ticket);
        return true;
    }

    /** 复刻 {@code uk_target_pending}：同目标已有待办即撞键，且不回填 id。 */
    @Override
    public boolean insertAuditTask(AuditTask task) {
        if (findPendingAuditTask(task.getTargetType(), task.getTargetId()) != null) {
            return false;
        }
        task.setId(++taskSeq);
        if (task.getDeleted() == null) {
            task.setDeleted(0);
        }
        if (task.getCreatedAt() == null) {
            task.setCreatedAt(clock);
        }
        auditTasks.add(task);
        return true;
    }

    @Override
    public AuditTask findPendingAuditTask(String targetType, long targetId) {
        for (AuditTask task : auditTasks) {
            if (Objects.equals(task.getTargetType(), targetType)
                    && Objects.equals(task.getTargetId(), targetId)
                    && AuditTask.STATUS_PENDING.equals(task.getStatus())
                    && (task.getDeleted() == null || task.getDeleted() == 0)) {
                return task;
            }
        }
        return null;
    }

    @Override
    public void escalateAuditTask(long taskId, String riskLevel, BigDecimal riskScore,
            LocalDateTime slaAt, LocalDateTime now) {
        for (AuditTask task : auditTasks) {
            if (Objects.equals(task.getId(), taskId)) {
                task.setRiskLevel(riskLevel);
                task.setRiskScore(riskScore);
                task.setSlaAt(slaAt);
                task.setUpdatedAt(now);
                return;
            }
        }
    }

    @Override
    public List<Long> listAdminIds() {
        return new ArrayList<>(adminIds);
    }

    // ---------------------------------------------------------------- 内部工具

    private static boolean alive(PrivateMessage row) {
        return row.getDeleted() == null || row.getDeleted() == 0;
    }

    private static boolean pairMatches(PrivateMessage row, long a, long b) {
        return (Objects.equals(row.getFromUserId(), a) && Objects.equals(row.getToUserId(), b))
                || (Objects.equals(row.getFromUserId(), b) && Objects.equals(row.getToUserId(), a));
    }

    /** 这行消息相对「我」的对方；不是我参与的会话返回 null。 */
    private static Long peerOf(PrivateMessage row, long userId) {
        boolean sent = Objects.equals(row.getFromUserId(), userId);
        boolean received = Objects.equals(row.getToUserId(), userId);
        if (!sent && !received) {
            return null;
        }
        return sent ? row.getToUserId() : row.getFromUserId();
    }

    private static <T> List<T> truncate(List<T> rows, int limit) {
        return limit <= 0 || rows.size() <= limit ? rows : new ArrayList<>(rows.subList(0, limit));
    }

    private static PrivateMessage copy(PrivateMessage src) {
        PrivateMessage copy = new PrivateMessage();
        copy.setId(src.getId());
        copy.setFromUserId(src.getFromUserId());
        copy.setToUserId(src.getToUserId());
        copy.setClientMsgId(src.getClientMsgId());
        copy.setMsgType(src.getMsgType());
        copy.setContent(src.getContent());
        copy.setRiskLevel(src.getRiskLevel());
        copy.setStatus(src.getStatus());
        copy.setReadAt(src.getReadAt());
        copy.setDeleted(src.getDeleted());
        copy.setCreatedAt(src.getCreatedAt());
        copy.setUpdatedAt(src.getUpdatedAt());
        return copy;
    }
}

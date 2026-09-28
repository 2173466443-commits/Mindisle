package com.mindisle.pm;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mindisle.entity.AlertTicket;
import com.mindisle.entity.AuditTask;
import com.mindisle.entity.PrivateMessage;
import com.mindisle.entity.User;
import com.mindisle.entity.UserBlock;
import com.mindisle.mapper.AlertTicketMapper;
import com.mindisle.mapper.AuditTaskMapper;
import com.mindisle.mapper.PrivateMessageMapper;
import com.mindisle.mapper.UserBlockMapper;
import com.mindisle.mapper.UserMapper;
import com.mindisle.pm.dto.PmPeerUnreadRow;
import org.springframework.stereotype.Component;

/**
 * {@link PmStore} 的真库实现（任务 T5.2）。
 *
 * <p>本类唯一的职责是把端口翻译成 mapper 调用，一条业务判断都不做。唯一的例外是
 * {@link #unreadByPeers} 的补零：SQL 用 {@code GROUP BY} 天然不会为「没有未读的人」出行，
 * 而端口契约要求每个请求过的 id 都有键。这件事放在这里而不是 Service，
 * 是因为<b>只有本类知道 SQL 缺行</b>；让 Service 补零等于把「这条 SQL 的行为」泄漏到业务层，
 * 换一条 SQL（比如换成带 LEFT JOIN 的写法）时那个补零就成了多余的第二次补零。</p>
 */
@Component
public class PmStoreAdapter implements PmStore {

    private final PrivateMessageMapper pmMapper;
    private final UserBlockMapper blockMapper;
    private final UserMapper userMapper;
    private final AlertTicketMapper ticketMapper;
    private final AuditTaskMapper auditTaskMapper;

    public PmStoreAdapter(PrivateMessageMapper pmMapper, UserBlockMapper blockMapper,
            UserMapper userMapper, AlertTicketMapper ticketMapper,
            AuditTaskMapper auditTaskMapper) {
        this.pmMapper = pmMapper;
        this.blockMapper = blockMapper;
        this.userMapper = userMapper;
        this.ticketMapper = ticketMapper;
        this.auditTaskMapper = auditTaskMapper;
    }

    @Override
    public User findUser(long userId) {
        return userMapper.selectById(userId);
    }

    @Override
    public boolean insertMessage(PrivateMessage row) {
        return pmMapper.insertIgnore(row) > 0;
    }

    @Override
    public PrivateMessage findByClientMsg(String clientMsgId, long toUserId) {
        return pmMapper.findByClientMsg(clientMsgId, toUserId);
    }

    @Override
    public PrivateMessage findMessage(long messageId) {
        return pmMapper.selectById(messageId);
    }

    @Override
    public List<PrivateMessage> pageThread(long userId, long peerId, Long beforeId, int limit) {
        return pmMapper.pageThread(userId, peerId, cursor(beforeId), limit);
    }

    @Override
    public List<PrivateMessage> listConversationTails(long userId, Long beforeId, int limit) {
        return pmMapper.listConversationTails(userId, cursor(beforeId), limit);
    }

    @Override
    public Map<Long, Integer> unreadByPeers(long userId, List<Long> peerIds) {
        Map<Long, Integer> filled = new LinkedHashMap<>();
        if (peerIds == null || peerIds.isEmpty()) {
            return filled;
        }
        // 先按请求顺序补零：GROUP BY 只返回有未读的人，没未读的那些人不会出现在结果里。
        // 补零放在适配器而不是留给前端判 undefined，是因为「没有这个键」和「有键且为 0」
        // 在前端是两个不同的显示（前者会渲染「—」，后者就该是个空角标）。
        for (Long peerId : peerIds) {
            if (peerId != null) {
                filled.put(peerId, 0);
            }
        }
        for (PmPeerUnreadRow row : pmMapper.listUnreadByPeer(userId, peerIds)) {
            if (row != null && row.getPeerId() != null) {
                filled.put(row.getPeerId(), row.getUnreadCnt() == null ? 0 : row.getUnreadCnt());
            }
        }
        return filled;
    }

    @Override
    public long countUnreadTotal(long userId) {
        return pmMapper.countUnreadTotal(userId);
    }

    @Override
    public int markThreadRead(long userId, long peerId, long upToId, LocalDateTime now) {
        return pmMapper.markThreadRead(userId, peerId, upToId, now);
    }

    @Override
    public int markDelivered(long messageId) {
        return pmMapper.markDelivered(messageId);
    }

    @Override
    public List<PrivateMessage> listUndelivered(LocalDateTime beforeCreated, int limit) {
        return pmMapper.listUndelivered(beforeCreated, limit);
    }

    @Override
    public List<Long> listPeerIds(long userId) {
        return pmMapper.listPeerIds(userId);
    }

    @Override
    public boolean isBlockedAny(long a, long b) {
        return blockMapper.isBlockedAny(a, b);
    }

    @Override
    public boolean insertBlock(long userId, long targetId, String reason) {
        return blockMapper.insertIgnore(userId, targetId, reason) > 0;
    }

    @Override
    public boolean deleteBlock(long userId, long targetId) {
        return blockMapper.deletePair(userId, targetId) > 0;
    }

    @Override
    public List<UserBlock> listMyBlocks(long userId, int limit) {
        return blockMapper.listMyBlocks(userId, limit);
    }

    @Override
    public boolean insertTicket(AlertTicket ticket) {
        return ticketMapper.insert(ticket) > 0;
    }

    @Override
    public boolean insertAuditTask(AuditTask task) {
        return auditTaskMapper.insertIgnore(task) > 0;
    }

    @Override
    public AuditTask findPendingAuditTask(String targetType, long targetId) {
        return auditTaskMapper.findPending(targetType, targetId);
    }

    @Override
    public void escalateAuditTask(long taskId, String riskLevel, BigDecimal riskScore,
            LocalDateTime slaAt, LocalDateTime now) {
        auditTaskMapper.escalate(taskId, riskLevel, riskScore, slaAt, now);
    }

    @Override
    public List<Long> listAdminIds() {
        List<Long> ids = userMapper.listAdminIds();
        return ids == null ? new ArrayList<>() : ids;
    }

    /**
     * 「从头开始」的游标在 SQL 里怎么写。
     *
     * <p>写成 {@code id < Long.MAX_VALUE} 而不是 SQL 里的 {@code #{beforeId} IS NULL}：
     * MyBatis 传 null 时不带 jdbcType，会走到 {@code setNull(Types.OTHER)}，
     * 这在 MySQL 驱动的不同版本上表现并不一致（本仓 8 号表踩过一次同类的坑）。
     * id 是无符号自增 BIGINT，永远不可能等于 Java 的 Long.MAX_VALUE，
     * 所以拿它当「没有游标」的哨兵值是一条恒真的比较，而且不需要任何空判分支。</p>
     */
    private static long cursor(Long beforeId) {
        return beforeId == null ? Long.MAX_VALUE : beforeId;
    }
}

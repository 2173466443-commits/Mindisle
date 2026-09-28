package com.mindisle.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.mindisle.entity.NotifyMessage;
import com.mindisle.pm.PmPushGateway;

/**
 * 推送口阶段 5 实现单测（任务 T5.8 · 需求 FR9.2 · 手册 §8.1、§10.4）。
 *
 * <p>本类钉的是四件事，每一件都对应一次真实的翻车方式：
 * ① <b>载荷恰好四键 {@code {type, id, unread, ts}}</b>，且<b>不含</b> title/content——
 * 私信正文与评论原文会出现在锁屏和浏览器弹窗上，需求 BR11 要求最小展示；
 * 多带一个字段前端的 toast 就会依赖推送里的文案，而推送是可丢的，那样红点与文字会对不上；
 * ② <b>{@code unread} 必须在提交之后才算</b>。在事务里算的话读不到自己刚插的那一行，
 * 红点永远少 1；这条判据用「onCreated 之后再把计数改掉」来证，比看代码可靠；
 * ③ <b>事务回滚就不能发帧</b>，否则用户点进一条不存在的通知（幽灵通知）；
 * ④ <b>任何异常都不许冒出到业务线程</b>——通知已经在库里，下次拉列表红点一定会出现，
 * 为一帧推送把点赞请求打成 500 是本末倒置。</p>
 *
 * <p>替身沿用仓库约定：{@link PmPushGateway} 的构造器不校验 null，
 * 于是 {@code super(null, null)} 加覆写 {@code sendToUser} 就够了；
 * {@link NotifyService.NotifyStore} 里有 {@link CountingStore}（只回应 countUnread，
 * 其余方法一旦被调用就抛，用来证明推送链路不碰读路径）。</p>
 */
class StompPushHookTest {

    private static final long USER = 701L;
    private static final long OTHER = 702L;
    private static final long ADMIN = 900L;
    private static final long NOTIFY_ID = 501L;
    private static final String SECRET = "我在想那天在河边说的那些话";

    private CountingStore store;
    private RecordingGateway gateway;
    private StompPushHook hook;

    @BeforeEach
    void setUp() {
        store = new CountingStore();
        gateway = new RecordingGateway();
        hook = new StompPushHook(gateway, store);
    }

    @AfterEach
    void tearDown() {
        // 事务同步是线程绑定的：忘了清会串到下一个测试，表现为「推送莫名其妙晚了一帧」
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ------------------------------------------------------------------ 载荷形状

    @Test
    @DisplayName("一帧落到收件人的 /user/queue/notify")
    void frameGoesToTheRecipientsNotifyQueue() {
        hook.onCreated(row(NOTIFY_ID, USER, NotifyMessage.TYPE_PM));

        assertThat(gateway.frames).hasSize(1);
        assertThat(gateway.frames.get(0).userId()).isEqualTo(USER);
        assertThat(gateway.frames.get(0).destination()).isEqualTo(PmPushGateway.DEST_NOTIFY);
    }

    @Test
    @DisplayName("载荷恰好四键，顺序是 type/id/unread/ts")
    void payloadHasExactlyTheFourKeysInOrder() {
        store.setUnread(USER, 7L);

        hook.onCreated(row(NOTIFY_ID, USER, NotifyMessage.TYPE_PM));

        assertThat(payload(0).keySet()).containsExactly("type", "id", "unread", "ts");
        assertThat(payload(0).get("id")).isEqualTo(NOTIFY_ID);
        assertThat(payload(0).get("unread")).isEqualTo(7L);
        assertThat(payload(0).get("ts")).isInstanceOf(Long.class);
    }

    @Test
    @DisplayName("载荷里没有标题、正文与 ref：toast 文案由前端再拉一次列表")
    void payloadNeverCarriesTitleOrContent() {
        hook.onCreated(row(NOTIFY_ID, USER, NotifyMessage.TYPE_PM));

        assertThat(payload(0).keySet())
                .doesNotContain("title", "content", "refType", "refId", "userId");
        assertThat(payload(0).toString()).doesNotContain(SECRET).doesNotContain("安安");
    }

    @Test
    @DisplayName("unread 是收件人自己的未读数，不是一个常量")
    void unreadIsCountedPerRecipient() {
        store.setUnread(USER, 3L);
        store.setUnread(OTHER, 11L);

        hook.onCreated(row(NOTIFY_ID, USER, NotifyMessage.TYPE_PM));
        hook.onCreated(row(NOTIFY_ID + 1, OTHER, NotifyMessage.TYPE_PM));

        assertThat(payload(0).get("unread")).isEqualTo(3L);
        assertThat(payload(1).get("unread")).isEqualTo(11L);
    }

    // ------------------------------------------------------------------ type 词表

    @Test
    @DisplayName("四种站内类型各有自己的推送名")
    void fourTypesHaveTheirOwnWireNames() {
        assertWireType(NotifyMessage.TYPE_PM, "dm");
        assertWireType(NotifyMessage.TYPE_CRISIS, "risk");
        assertWireType(NotifyMessage.TYPE_COMMENT, "comment");
        assertWireType(NotifyMessage.TYPE_FOLLOW, "follow");
    }

    @Test
    @DisplayName("其余类型一律折进 system：前端只用 type 决定跳转与私信角标")
    void everythingElseCollapsesIntoSystem() {
        assertWireType(NotifyMessage.TYPE_LIKE, "system");
        assertWireType(NotifyMessage.TYPE_REPORT, "system");
        assertWireType(NotifyMessage.TYPE_AUDIT, "system");
        assertWireType(NotifyMessage.TYPE_SYSTEM, "system");
        assertWireType("bogus", "system");
        assertWireType(null, "system");
    }

    // ------------------------------------------------------------------ 不该发帧的三种行

    @Test
    @DisplayName("null 行不推也不抛")
    void nullRowPushesNothing() {
        assertThatCode(() -> hook.onCreated(null)).doesNotThrowAnyException();

        assertThat(gateway.frames).isEmpty();
    }

    @Test
    @DisplayName("主键没回填说明 insert 没成功，推一帧只会把前端领到 404")
    void rowWithoutIdPushesNothing() {
        hook.onCreated(row(null, USER, NotifyMessage.TYPE_PM));

        assertThat(gateway.frames).isEmpty();
        assertThat(store.countUnreadCalls).isZero();
    }

    @Test
    @DisplayName("没有收件人的行不推")
    void rowWithoutRecipientPushesNothing() {
        hook.onCreated(row(NOTIFY_ID, null, NotifyMessage.TYPE_PM));

        assertThat(gateway.frames).isEmpty();
    }

    // ------------------------------------------------------------------ 事务时序

    @Test
    @DisplayName("事务外：立刻发帧，不留到不存在的提交点")
    void sendsImmediatelyOutsideATransaction() {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

        hook.onCreated(row(NOTIFY_ID, USER, NotifyMessage.TYPE_PM));

        assertThat(gateway.frames).hasSize(1);
    }

    @Test
    @DisplayName("事务内：等提交才发，帧里的 unread 是提交后的真实值")
    void insideATransactionTheFrameWaitsForCommit() {
        TransactionSynchronizationManager.initSynchronization();
        store.setUnread(USER, 0L);

        hook.onCreated(row(NOTIFY_ID, USER, NotifyMessage.TYPE_PM));
        // 此刻仍在事务里：库里那行还没提交，读到的未读数当然不含它，所以绝不能现在就发
        assertThat(gateway.frames).isEmpty();
        store.setUnread(USER, 1L);

        fireAfterCommit();

        assertThat(gateway.frames).hasSize(1);
        assertThat(payload(0).get("unread")).isEqualTo(1L);
    }

    @Test
    @DisplayName("事务回滚：一帧都不发，不留幽灵通知")
    void rolledBackTransactionPushesNothing() {
        TransactionSynchronizationManager.initSynchronization();

        hook.onCreated(row(NOTIFY_ID, USER, NotifyMessage.TYPE_CRISIS));
        fireAfterCompletionRolledBack();

        assertThat(gateway.frames).isEmpty();
    }

    @Test
    @DisplayName("同一事务里的多行各自发一帧，顺序与落库顺序一致")
    void eachRowInOneTransactionGetsItsOwnFrame() {
        TransactionSynchronizationManager.initSynchronization();

        hook.onCreated(row(NOTIFY_ID, USER, NotifyMessage.TYPE_PM));
        hook.onCreated(row(NOTIFY_ID + 1, OTHER, NotifyMessage.TYPE_COMMENT));
        assertThat(gateway.frames).isEmpty();

        fireAfterCommit();

        assertThat(gateway.frames).hasSize(2);
        assertThat(gateway.frames.get(0).userId()).isEqualTo(USER);
        assertThat(gateway.frames.get(1).userId()).isEqualTo(OTHER);
        assertThat(payload(1).get("type")).isEqualTo("comment");
    }

    // ------------------------------------------------------------------ 异常口径

    @Test
    @DisplayName("数未读抛异常时吞掉，不发帧也不把业务事务带崩")
    void storeFailureIsSwallowed() {
        store.failure = new IllegalStateException("连接池已耗尽");

        assertThatCode(() -> hook.onCreated(row(NOTIFY_ID, USER, NotifyMessage.TYPE_PM)))
                .doesNotThrowAnyException();

        assertThat(gateway.frames).isEmpty();
    }

    @Test
    @DisplayName("网关抛异常时吞掉：通知本体已经在库里")
    void gatewayFailureIsSwallowed() {
        gateway.failure = new IllegalStateException("broker down");

        assertThatCode(() -> hook.onCreated(row(NOTIFY_ID, USER, NotifyMessage.TYPE_PM)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("收件人不在线（返回 false）不是错误，不重试也不抛")
    void offlineRecipientIsNotAnError() {
        gateway.delivered = false;

        assertThatCode(() -> hook.onCreated(row(NOTIFY_ID, USER, NotifyMessage.TYPE_PM)))
                .doesNotThrowAnyException();

        assertThat(gateway.frames).hasSize(1);
    }

    @Test
    @DisplayName("afterCommit 里抛异常同样吞掉：提交已经完成，没有东西可以回滚")
    void failureAfterCommitIsSwallowed() {
        TransactionSynchronizationManager.initSynchronization();
        hook.onCreated(row(NOTIFY_ID, USER, NotifyMessage.TYPE_PM));
        store.failure = new IllegalStateException("提交后查询失败");

        assertThatCode(() -> fireAfterCommit()).doesNotThrowAnyException();
        assertThat(gateway.frames).isEmpty();
    }

    // ------------------------------------------------------------------ 与 NotifyService 接起来

    @Test
    @DisplayName("私信通知经过本 hook：一条 dm 帧，unread 含刚落的这行")
    void notifyServicePmEndToEnd() {
        FullStore full = new FullStore();
        NotifyService service = new NotifyService(full, new StompPushHook(gateway, full));

        service.notifyPm(USER, "安安", OTHER, SECRET);

        assertThat(gateway.frames).hasSize(1);
        assertThat(payload(0).get("type")).isEqualTo("dm");
        assertThat(payload(0).get("id")).isEqualTo(full.rows.get(0).getId());
        // 未读数走的是同一个 store：刚插入那一行本身就是未读，红点不会少 1
        assertThat(payload(0).get("unread")).isEqualTo(1L);
    }

    @Test
    @DisplayName("危机通知经过本 hook：每个管理员一帧 risk")
    void notifyServiceCrisisEndToEnd() {
        FullStore full = new FullStore();
        NotifyService service = new NotifyService(full, new StompPushHook(gateway, full));

        service.notifyCrisisAdmin(List.of(ADMIN, ADMIN + 1), 59L, "L3", "摘录内容");

        assertThat(gateway.frames).hasSize(2);
        assertThat(payload(0).get("type")).isEqualTo("risk");
        assertThat(payload(1).get("unread")).isEqualTo(1L);
    }

    // ------------------------------------------------------------------ 助手

    private static NotifyMessage row(Long id, Long userId, String type) {
        NotifyMessage row = new NotifyMessage();
        row.setId(id);
        row.setUserId(userId);
        row.setType(type);
        row.setTitle("安安 给你发来一条私信");
        row.setContent(SECRET);
        row.setRefType(NotifyMessage.REF_PM);
        row.setRefId(OTHER);
        row.setIsRead(0);
        row.setDeleted(0);
        return row;
    }

    private void assertWireType(String notifyType, String wireType) {
        gateway.frames.clear();
        hook.onCreated(row(NOTIFY_ID, USER, notifyType));

        assertThat(gateway.frames).hasSize(1);
        assertThat(payload(0).get("type")).isEqualTo(wireType);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(int index) {
        return (Map<String, Object>) gateway.frames.get(index).payload();
    }

    private static void fireAfterCommit() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
    }

    private static void fireAfterCompletionRolledBack() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    /** 只记录下游调用的网关：真实现要 SimpMessagingTemplate，本测试只想知道「谁收到了什么帧」。 */
    private static final class RecordingGateway extends PmPushGateway {

        final List<Frame> frames = new ArrayList<>();
        boolean delivered = true;
        RuntimeException failure;

        RecordingGateway() {
            super(null, null);
        }

        @Override
        public boolean sendToUser(long userId, String destination, Object payload) {
            if (failure != null) {
                throw failure;
            }
            frames.add(new Frame(userId, destination, payload));
            return delivered;
        }
    }

    private record Frame(long userId, String destination, Object payload) {
    }

    /** 只回应 countUnread 的存储：其余方法一旦被调用就抛，用来证明推送链路不碰读路径。 */
    private static final class CountingStore implements NotifyService.NotifyStore {

        private final Map<Long, Long> unread = new LinkedHashMap<>();
        int countUnreadCalls;
        RuntimeException failure;

        void setUnread(long userId, long count) {
            unread.put(userId, count);
        }

        @Override
        public long countUnread(long userId) {
            if (failure != null) {
                throw failure;
            }
            countUnreadCalls++;
            return unread.getOrDefault(userId, 0L);
        }

        @Override
        public boolean existsUnreadDuplicate(long userId, String type, String refType, long refId,
                                             String title, String content) {
            throw new UnsupportedOperationException("推送口不该查幂等");
        }

        @Override
        public void insert(NotifyMessage row) {
            throw new UnsupportedOperationException("推送口不该写库");
        }

        @Override
        public List<NotifyMessage> page(long userId, Long beforeId, int limit) {
            throw new UnsupportedOperationException("推送口不该读列表");
        }

        @Override
        public int markRead(long userId, List<Long> ids, LocalDateTime now) {
            throw new UnsupportedOperationException("推送口不该置已读");
        }

        @Override
        public int markAllRead(long userId, LocalDateTime now) {
            throw new UnsupportedOperationException("推送口不该一键已读");
        }
    }

    /** 会回填自增主键的最小存储：只为把 NotifyService 接进来跑一次端到端。 */
    private static final class FullStore implements NotifyService.NotifyStore {

        final List<NotifyMessage> rows = new ArrayList<>();
        private long seq = 500;

        @Override
        public boolean existsUnreadDuplicate(long userId, String type, String refType, long refId,
                                            String title, String content) {
            return false;
        }

        @Override
        public void insert(NotifyMessage row) {
            row.setId(++seq);
            rows.add(row);
        }

        @Override
        public long countUnread(long userId) {
            return rows.stream()
                    .filter(r -> Long.valueOf(userId).equals(r.getUserId()))
                    .filter(r -> r.getIsRead() == null || r.getIsRead() == 0)
                    .count();
        }

        @Override
        public List<NotifyMessage> page(long userId, Long beforeId, int limit) {
            throw new UnsupportedOperationException("本测试不读列表");
        }

        @Override
        public int markRead(long userId, List<Long> ids, LocalDateTime now) {
            throw new UnsupportedOperationException("本测试不置已读");
        }

        @Override
        public int markAllRead(long userId, LocalDateTime now) {
            throw new UnsupportedOperationException("本测试不一键已读");
        }
    }
}

package com.mindisle.pm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.User;
import com.mindisle.pm.dto.PmPresenceView;

/**
 * 在线状态表单测（任务 T5.4 · 需求 FR6.3、FR6.5）。
 *
 * <p>本类要钉住的不是「能不能查到在线」，而是三条改需求时最容易悄悄丢掉的判据：
 * ① <b>一个人多个标签页只算一次翻转</b>，否则对端会收到三条「他上线了」，
 * 更要紧的是「上线次数」这类派生指标会被重复计数；
 * ② <b>状态变更只推给有过私信往来的人</b>，全站那一条广播只带人数与时间戳、一个 id 都不带
 * （需求 FR6.5 原文那个 {@code /topic/online} 若照字面实现，等于新增一处
 * 「谁在什么时候上线」的公开观测，而没有任何一条需求要它）；
 * ③ <b>{@code presence-enabled=false} 时读写两侧一起哑掉</b>——
 * 「功能关闭」与「查不到」在界面上必须是两回事。</p>
 *
 * <p>替身沿用仓库既有约定：构造器不校验 null，于是 {@code super(null, null)} 加覆写单个方法
 * 就是最便宜的替身（{@link RecordingGateway} 只关心「谁被推了什么」），
 * 比 mock 框架可读，也不需要 Spring 上下文。</p>
 */
class PresenceRegistryTest {

    private static final long ME = 701L;
    private static final long PEER = 702L;
    private static final long THIRD = 703L;

    private MindisleProperties properties;
    private PmFakeStore store;
    private RecordingGateway gateway;
    private PresenceRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new MindisleProperties();
        store = new PmFakeStore();
        store.withUser(activeUser(ME, "小屿"));
        store.withUser(activeUser(PEER, "安安"));
        store.withUser(activeUser(THIRD, "路人"));
        // ME 与 PEER 有过私信往来，与 THIRD 从来没有：隐私判据全靠这两行区分
        store.seedMessage(ME, PEER, "在吗", "text", "delivered", "L0");
        gateway = new RecordingGateway();
        registry = new PresenceRegistry(gateway, store, properties);
    }

    // ------------------------------------------------------------------ 上线与离线的翻转判据

    @Test
    @DisplayName("第一个会话上线：记为在线，并只通知私信伙伴")
    void firstSessionMarksOnlineAndNotifiesPeers() {
        assertThat(registry.enabled()).isTrue();

        registry.onConnected(connected(ME, "s1"));

        assertThat(registry.isOnline(ME)).isTrue();
        assertThat(registry.onlineCount()).isEqualTo(1);
        assertThat(gateway.broadcasts).hasSize(1);
        assertThat(gateway.notifiedUserIds()).containsExactly(PEER);
        assertThat(gateway.pushes.get(0).destination()).isEqualTo(PmPushGateway.DEST_PRESENCE);
    }

    @Test
    @DisplayName("同一人第二个标签页不再算一次上线：只推一条")
    void secondTabOfSameUserDoesNotNotifyAgain() {
        registry.onConnected(connected(ME, "s1"));
        registry.onConnected(connected(ME, "s2"));

        assertThat(registry.isOnline(ME)).isTrue();
        assertThat(gateway.broadcasts).hasSize(1);
        assertThat(gateway.pushes).hasSize(1);
    }

    @Test
    @DisplayName("在线人数按人算不按会话算")
    void onlineCountIsUsersNotSessions() {
        registry.onConnected(connected(ME, "s1"));
        registry.onConnected(connected(ME, "s2"));
        registry.onConnected(connected(PEER, "s3"));

        assertThat(registry.onlineCount()).isEqualTo(2);
        assertThat(broadcastPayload(1).get("onlineCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("关掉其中一个标签页不会让他变成离线")
    void closingOneTabKeepsTheGreenDot() {
        registry.onConnected(connected(ME, "s1"));
        registry.onConnected(connected(ME, "s2"));

        registry.onDisconnect(disconnected(ME, "s1"));

        assertThat(registry.isOnline(ME)).isTrue();
        assertThat(gateway.broadcasts).hasSize(1);
        assertThat(gateway.pushes).hasSize(1);
    }

    @Test
    @DisplayName("最后一个会话断开才算离线，且推的是 false")
    void closingLastSessionFlipsToOffline() {
        registry.onConnected(connected(ME, "s1"));
        registry.onConnected(connected(ME, "s2"));
        registry.onDisconnect(disconnected(ME, "s1"));

        registry.onDisconnect(disconnected(ME, "s2"));

        assertThat(registry.isOnline(ME)).isFalse();
        assertThat(registry.onlineCount()).isZero();
        assertThat(gateway.broadcasts).hasSize(2);
        assertThat(gateway.notifiedUserIds()).containsExactly(PEER, PEER);
        assertThat(view(1).isOnline()).isFalse();
    }

    @Test
    @DisplayName("重复断连（SockJS 降级链路会重复触发）只通知一次离线")
    void duplicateDisconnectNotifiesOnce() {
        registry.onConnected(connected(ME, "s1"));
        registry.onDisconnect(disconnected(ME, "s1"));

        registry.onDisconnect(disconnected(ME, "s1"));

        assertThat(registry.isOnline(ME)).isFalse();
        // 一次上线、一次离线；第二次那条重复断连不再产生第三条
        assertThat(gateway.broadcasts).hasSize(2);
        assertThat(gateway.pushes).hasSize(2);
    }

    @Test
    @DisplayName("会话 id 对不上时不算离线：判据是集合真空了，不是我删掉了一个元素")
    void staleDisconnectDoesNotFlipOffline() {
        registry.onConnected(connected(ME, "s1"));
        registry.onConnected(connected(ME, "s2"));

        registry.onDisconnect(disconnected(ME, "s9"));

        assertThat(registry.isOnline(ME)).isTrue();
        assertThat(gateway.broadcasts).hasSize(1);
        assertThat(gateway.pushes).hasSize(1);
    }

    @Test
    @DisplayName("断连事件不可能缺会话 id：生产里那条兜底判空是防御性的，留着的理由写在这里")
    void disconnectEventCannotBeBuiltWithoutSessionId() {
        registry.onConnected(connected(ME, "s1"));
        registry.onConnected(connected(ME, "s2"));

        // 容器发出来的 SessionDisconnectEvent 在构造器里就拒绝 null 会话 id，于是
        // removeFromSession 中「拿不到 id 就按这个人没有活跃会话了处理」那一支在真实链路上走不到。
        // 不删它：多一行判空，换的是「万一哪天上游放宽约束，不会留下永远灭不掉的假在线」。
        assertThatThrownBy(() -> new SessionDisconnectEvent(new Object(), message("s1"), null,
                CloseStatus.NORMAL, new StompPrincipalHandshakeHandler.UserIdPrincipal(ME)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Session id");

        // 走不到不等于没有后果：两次正常断开之后仍然只有「在线一次、离线一次」两条通知
        registry.onDisconnect(disconnected(ME, "s1"));
        registry.onDisconnect(disconnected(ME, "s2"));
        assertThat(registry.isOnline(ME)).isFalse();
        assertThat(gateway.broadcasts).hasSize(2);
        assertThat(view(1).isOnline()).isFalse();
    }

    @Test
    @DisplayName("离线之后再上线算第二次翻转：不会只在进程生命周期里通知一次")
    void reconnectAfterOfflineFlipsAgain() {
        registry.onConnected(connected(ME, "s1"));
        registry.onDisconnect(disconnected(ME, "s1"));

        registry.onConnected(connected(ME, "s3"));

        assertThat(registry.isOnline(ME)).isTrue();
        // 上线、离线、再上线：三条广播，最后一条说他在
        assertThat(gateway.broadcasts).hasSize(3);
        assertThat(view(2).isOnline()).isTrue();
    }

    // ------------------------------------------------------------------ 身份缺失与不合法

    @Test
    @DisplayName("拿不到会话 id 就不登记：假在线比少一个绿点严重")
    void missingSessionIdSkipsRegistration() {
        registry.onConnected(connected(ME, null));

        assertThat(registry.isOnline(ME)).isFalse();
        assertThat(registry.onlineCount()).isZero();
        assertThat(gateway.broadcasts).isEmpty();
        assertThat(gateway.pushes).isEmpty();
    }

    @Test
    @DisplayName("Principal 不是用户 id 时跳过登记，而不是抛出去把连接线程带崩")
    void nonNumericPrincipalIsIgnored() {
        registry.onConnected(connected((Principal) () -> "anonymous", "s1"));
        registry.onConnected(connected((Principal) () -> "7o1", "s2"));

        assertThat(registry.onlineCount()).isZero();
        assertThat(gateway.broadcasts).isEmpty();
        assertThat(gateway.pushes).isEmpty();
    }

    @Test
    @DisplayName("Principal 为 null 与 getName 为 null 都不登记")
    void absentPrincipalIsIgnored() {
        registry.onConnected(new SessionConnectedEvent(new Object(), message("s1"), null));
        registry.onConnected(connected((Principal) () -> null, "s2"));

        assertThat(registry.onlineCount()).isZero();
        assertThat(gateway.broadcasts).isEmpty();
    }

    @Test
    @DisplayName("断连事件不带 Principal 时什么都不改：不能凭会话 id 猜人")
    void disconnectWithoutPrincipalChangesNothing() {
        registry.onConnected(connected(ME, "s1"));

        registry.onDisconnect(new SessionDisconnectEvent(new Object(), message("s1"), "s1",
                CloseStatus.NORMAL));

        assertThat(registry.isOnline(ME)).isTrue();
        assertThat(gateway.broadcasts).hasSize(1);
    }

    // ------------------------------------------------------------------ 隐私判据

    @Test
    @DisplayName("全站广播只有 onlineCount 与 ts 两个键，一个 id 都不带")
    void siteWideBroadcastCarriesNoIdentity() {
        registry.onConnected(connected(ME, "s1"));

        assertThat(gateway.broadcasts.get(0).destination()).isEqualTo(PmPushGateway.TOPIC_PRESENCE);
        Map<String, Object> payload = broadcastPayload(0);
        assertThat(payload.keySet()).containsExactlyInAnyOrder("onlineCount", "ts");
        assertThat(payload.get("onlineCount")).isEqualTo(1);
        assertThat(payload.get("ts")).isInstanceOf(LocalDateTime.class);
        assertThat(payload.toString()).doesNotContain("701").doesNotContain("702");
    }

    @Test
    @DisplayName("只通知有过私信往来的人：从未聊过的在线者一条都收不到")
    void onlyMessagePartnersAreNotified() {
        registry.onConnected(connected(THIRD, "sA"));

        // THIRD 上线：他没有任何私信伙伴，所以一条点对点推送都没有，只有全站那个数字动了一下
        assertThat(gateway.pushes).isEmpty();
        assertThat(gateway.broadcasts).hasSize(1);
        assertThat(registry.isOnline(THIRD)).isTrue();

        registry.onConnected(connected(ME, "sB"));

        // ME 上线：只有聊过的 PEER 收到，此刻同样在线的 THIRD 收不到
        assertThat(gateway.notifiedUserIds()).containsExactly(PEER);
        assertThat(gateway.broadcasts).hasSize(2);
    }

    @Test
    @DisplayName("伙伴名单里混进自己和 null 时不会推给自己，也不会推给空")
    void selfAndNullEntriesInPeerListAreSkipped() {
        FixedPeerStore peerStore = new FixedPeerStore(Arrays.asList(ME, null, PEER));
        PresenceRegistry custom = new PresenceRegistry(gateway, peerStore, properties);

        custom.onConnected(connected(ME, "s1"));

        assertThat(gateway.notifiedUserIds()).containsExactly(PEER);
    }

    @Test
    @DisplayName("伙伴查询抛错时不再通知任何人，但那条不含身份的广播照发")
    void peerQueryFailureSuppressesPeerPushOnly() {
        PresenceRegistry custom = new PresenceRegistry(gateway, new BrokenPeerStore(), properties);

        custom.onConnected(connected(ME, "s1"));

        assertThat(gateway.broadcasts).hasSize(1);
        assertThat(gateway.pushes).isEmpty();
    }

    @Test
    @DisplayName("推送载荷是被翻转的人与他的新状态，不带在线名单")
    void pushViewCarriesThePersonAndTheFlag() {
        registry.onConnected(connected(ME, "s1"));

        assertThat(view(0).userId()).isEqualTo(ME);
        assertThat(view(0).isOnline()).isTrue();
        assertThat(view(0).online()).isEmpty();
    }

    // ------------------------------------------------------------------ 总开关

    @Test
    @DisplayName("关掉功能之后既不认识任何人，也不发任何通知")
    void disabledPresenceRecordsAndNotifiesNothing() {
        properties.getPm().setPresenceEnabled(false);

        registry.onConnected(connected(ME, "s1"));

        assertThat(registry.enabled()).isFalse();
        assertThat(registry.isOnline(ME)).isFalse();
        assertThat(registry.onlineCount()).isZero();
        assertThat(registry.filterOnline(List.of(ME))).isEmpty();
        assertThat(gateway.broadcasts).isEmpty();
        assertThat(gateway.pushes).isEmpty();
    }

    @Test
    @DisplayName("中途关掉功能时已登记的人一起变不在线；重新打开不需要重新握手")
    void disableGateSilencesAlreadyRegisteredUsers() {
        registry.onConnected(connected(ME, "s1"));
        assertThat(registry.isOnline(ME)).isTrue();

        properties.getPm().setPresenceEnabled(false);
        assertThat(registry.isOnline(ME)).isFalse();
        assertThat(registry.onlineCount()).isZero();
        assertThat(registry.filterOnline(List.of(ME))).isEmpty();
        // 关闸本身不制造一批离线推送：那会让人以为对方断开了，而他只是这个功能被关了
        assertThat(gateway.broadcasts).hasSize(1);

        properties.getPm().setPresenceEnabled(true);
        assertThat(registry.isOnline(ME)).isTrue();
    }

    // ------------------------------------------------------------------ 查询侧

    @Test
    @DisplayName("filterOnline 只留在线的那几个，保持请求顺序，跳过 null")
    void filterOnlineKeepsOrderAndDropsNulls() {
        registry.onConnected(connected(ME, "s1"));
        registry.onConnected(connected(PEER, "s2"));

        assertThat(registry.filterOnline(Arrays.asList(PEER, null, THIRD, ME)))
                .containsExactly(PEER, ME);
        assertThat(registry.filterOnline(List.of(THIRD))).isEmpty();
        assertThat(registry.filterOnline(null)).isEmpty();
    }

    // ------------------------------------------------------------------ 助手

    private static User activeUser(long id, String nickname) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setNickname(nickname);
        user.setStatus("ACTIVE");
        user.setDeleted(0);
        return user;
    }

    private static SessionConnectedEvent connected(long userId, String sessionId) {
        return connected(new StompPrincipalHandshakeHandler.UserIdPrincipal(userId), sessionId);
    }

    private static SessionConnectedEvent connected(Principal principal, String sessionId) {
        return new SessionConnectedEvent(new Object(), message(sessionId), principal);
    }

    private static SessionDisconnectEvent disconnected(long userId, String sessionId) {
        return new SessionDisconnectEvent(new Object(), message(sessionId), sessionId,
                CloseStatus.NORMAL, new StompPrincipalHandshakeHandler.UserIdPrincipal(userId));
    }

    /**
     * 构造一个带会话 id 的裸消息。
     *
     * <p>会话 id 走 {@link SimpMessageHeaderAccessor#setSessionId}，不手工写头部键名：
     * 键名是 STOMP 的内部约定，写错了不报错，只会让「第一个会话」的判据永远成立。</p>
     */
    private static Message<byte[]> message(String sessionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], accessor.toMessageHeaders());
    }

    private Map<String, Object> broadcastPayload(int index) {
        return gateway.broadcasts.get(index).payload();
    }

    private PmPresenceView view(int index) {
        return (PmPresenceView) gateway.pushes.get(index).payload();
    }

    /** 只记录下游调用的网关：真实现要 {@code SimpMessagingTemplate}，本测试只想知道推给了谁。 */
    private static final class RecordingGateway extends PmPushGateway {

        final List<Push> pushes = new ArrayList<>();
        final List<Broadcast> broadcasts = new ArrayList<>();

        RecordingGateway() {
            super(null, null);
        }

        @Override
        public boolean sendToUser(long userId, String destination, Object payload) {
            pushes.add(new Push(userId, destination, payload));
            return true;
        }

        @Override
        public void broadcast(String destination, Map<String, Object> payload) {
            broadcasts.add(new Broadcast(destination, payload));
        }

        List<Long> notifiedUserIds() {
            List<Long> ids = new ArrayList<>();
            for (Push push : pushes) {
                ids.add(push.userId());
            }
            return ids;
        }
    }

    private record Push(long userId, String destination, Object payload) {
    }

    private record Broadcast(String destination, Map<String, Object> payload) {
    }

    /** 伙伴名单由测试指定，用来检查「跳过自己与 null」这条守卫。 */
    private static final class FixedPeerStore extends PmFakeStore {

        private final List<Long> peers;

        FixedPeerStore(List<Long> peers) {
            this.peers = peers;
        }

        @Override
        public List<Long> listPeerIds(long userId) {
            return peers;
        }
    }

    /** 查询伙伴就抛错的存储：断言异常不会顺着连接事件冒出到容器线程。 */
    private static final class BrokenPeerStore extends PmFakeStore {

        @Override
        public List<Long> listPeerIds(long userId) {
            throw new IllegalStateException("私信库不可用");
        }
    }
}

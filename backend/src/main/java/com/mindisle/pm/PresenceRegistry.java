package com.mindisle.pm;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mindisle.config.MindisleProperties;
import com.mindisle.pm.dto.PmPresenceView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * 在线状态表（任务 T5.4 · 需求 FR6.3、FR6.5）。
 *
 * <p><b>为什么用内存表而不是 Redis</b>：需求 §9.3 与本仓 NFR 都写「Redis 在线集合」，
 * 而本机 6379 没有监听（阶段 4 实测），单实例部署下这份状态本来就只有这一个进程需要读。
 * 用 Redis 会引入一个「装了但没人能验」的依赖——而这份表的所有判据（翻转才通知、
 * 只发给有过会话的人、广播不带 id）在内存里同样成立、同样可测。
 * 唯一的真代价是多实例部署时各节点只知道自己连接上的用户，这条已经写进手册 §8 的收工口径。</p>
 *
 * <p><b>三处隐私判据（这是本类存在的另一半理由）</b>：
 * ① {@code /topic/presence} 只发 {@code {onlineCount, ts}} 两个键，<b>不发任何 id</b>——
 * 需求 FR6.5 原文那个 {@code /topic/online} 若照字面把在线名单广播给全站，
 * 等于新增一处「谁在什么时候上线」的公开观测，而没有任何一条需求要它；
 * ② 状态翻转只通知<b>与这个人有过私信往来</b>的人（{@link PmStore#listPeerIds}），
 * 不给关注关系、不给全站；
 * ③ {@code presence-enabled=false} 时本类不记录也不通知，所有查询返回空/false，
 * 前端据此不显示绿点——这是「功能关闭」而不是「查不到」，两者在界面上必须是两回事。</p>
 *
 * <p>连接事件用 {@code SessionConnectedEvent} / {@code SessionDisconnectEvent}，
 * 身份取 {@code event.getUser()}（由 {@link StompPrincipalHandshakeHandler} 定成用户 id）。
 * 一个用户可能有多个标签页，因此表里是 {@code userId -> sessionIds}：
 * <b>只有第一个会话上线和最后一个会话离线才算一次翻转</b>，
 * 否则三个标签页会让对方收到三条「他上线了」。</p>
 *
 * <p><b>两个事件处理都不加 {@code @Async}</b>：登记与注销各是一次 map 操作，异步化省不下
 * 任何用户可见的延迟，却会把「同一秒内 off→on」两次事件的处理顺序交给线程池
 * （Spring 的异步事件监听器不保证投递顺序）。顺序一乱，前端就会收到以
 * 「他离线了」结尾的一条通知，而那个人其实正挂着连接。断连回调里也不做重活，
 * 通知本身走 {@link PmPushGateway}，那里已经把异常吞成 warn。</p>
 */
@Component
public class PresenceRegistry {

  private static final Logger log = LoggerFactory.getLogger(PresenceRegistry.class);

  private final PmPushGateway gateway;
  private final PmStore store;
  private final MindisleProperties properties;

  /** userId -> 该用户当前的会话 id 集合。并发下靠 ConcurrentHashMap 的原子方法维护。 */
  private final Map<Long, Set<String>> sessions = new ConcurrentHashMap<>();

  public PresenceRegistry(PmPushGateway gateway, PmStore store, MindisleProperties properties) {
    this.gateway = gateway;
    this.store = store;
    this.properties = properties;
  }

  /** 本功能是否开启（关着的时候前端连订阅都可以省，Service 侧也据此不再返回 online 字段）。 */
  public boolean enabled() {
    return properties.getPm().isPresenceEnabled();
  }

  public boolean isOnline(long userId) {
    Set<String> current = sessions.get(userId);
    return enabled() && current != null && !current.isEmpty();
  }

  /** 请求名单里此刻在线的那些（不在线的<b>不出现</b>，与 {@code PmPresenceView} 的口径一致）。 */
  public List<Long> filterOnline(Collection<Long> userIds) {
    List<Long> online = new ArrayList<>();
    if (!enabled() || userIds == null) {
      return online;
    }
    for (Long userId : userIds) {
      if (userId != null && isOnline(userId)) {
        online.add(userId);
      }
    }
    return online;
  }

  /**
   * 本进程记着的在线用户数（键的个数，不是会话个数：三个标签页算一个人）。
   *
   * <p>{@code /topic/presence} 广播的数字与本类 {@link #isOnline} 必须同源，
   * 否则会出现「广播说 5 个人在线、逐个问却说这个人不在线」这种界面。
   * 这也是不用 {@code SimpUserRegistry#getUserCount} 的原因：那份表由 Spring 维护，
   * 不含 {@code presence-enabled} 这道闸，关掉功能之后它照样往全站推数字。</p>
   */
  public int onlineCount() {
    return enabled() ? sessions.size() : 0;
  }

  @EventListener
  public void onConnected(SessionConnectedEvent event) {
    Long userId = userIdOf(event.getUser(), event);
    String sessionId = sessionIdOf(event);
    if (userId == null || !enabled()) {
      return;
    }
    if (sessionId == null) {
      // 拿不到会话 id 就不登记：登记了也删不掉（removeFromSession 按 id 删），
      // 结果是这个人被永久记成在线，绿点再也灭不下去——那种假在线比少一个绿点严重得多。
      log.warn("上线事件缺会话 id，跳过在线登记 uid={}", userId);
      return;
    }
    boolean first = addToSession(userId, sessionId);
    if (first) {
      log.debug("用户上线 uid={} session={}", userId, sessionId);
      notifyChange(userId, true);
    }
  }

  @EventListener
  public void onDisconnect(SessionDisconnectEvent event) {
    Long userId = userIdOf(event.getUser(), event);
    String sessionId = event.getSessionId();
    if (userId == null || !enabled()) {
      return;
    }
    boolean last = removeFromSession(userId, sessionId);
    if (last) {
      log.debug("用户离线 uid={} session={}", userId, sessionId);
      notifyChange(userId, false);
    }
  }

  /** 真的翻转了在线状态才通知；同时刷新全站可见的那一个数字（不含身份）。 */
  private void notifyChange(long userId, boolean online) {
    gateway.broadcast(PmPushGateway.TOPIC_PRESENCE,
        Map.of("onlineCount", onlineCount(), "ts", LocalDateTime.now()));
    List<Long> peers;
    try {
      peers = store.listPeerIds(userId);
    } catch (Exception e) {
      log.warn("在线状态变更找不到要通知的人 uid={} err={}", userId, describe(e));
      return;
    }
    PmPresenceView view = PmPresenceView.ofChange(userId, online);
    for (Long peerId : peers) {
      if (peerId != null && peerId != userId) {
        gateway.sendToUser(peerId, PmPushGateway.DEST_PRESENCE, view);
      }
    }
  }

  /**
   * 加入会话，返回这是否是该用户<b>第一个</b>会话（只有第一个才算一次「他上线了」）。
   *
   * <p>判据必须写在 {@code compute} 里面：在函数外面看「集合现在有几个」，
   * 两个标签页同时上线时两次都能看到同一个数字，于是两个人都被判成第一个。
   * {@link ConcurrentHashMap#compute} 对同一键串行，「旧值空不空」这件事 therefore
   * 与写入是同一个原子动作，这才是 2 空格缩进的这一行真正的用意。</p>
   */
  private boolean addToSession(long userId, String sessionId) {
    boolean[] firstSession = { false };
    sessions.compute(userId, (key, current) -> {
      Set<String> set = current == null ? new LinkedHashSet<>() : current;
      firstSession[0] = set.isEmpty();
      set.add(sessionId);
      return set;
    });
    return firstSession[0];
  }

  /**
   * 移除会话，返回这是否是该用户<b>最后一个</b>会话。
   *
   * <p>{@code SessionDisconnectEvent} 在异常断连时可能重复触发（SockJS 降级链路的已知行为），
   * 因此这里以「集合真的空了」为准，而不是「我这次调用删掉了一个元素」。</p>
   */
  private boolean removeFromSession(long userId, String sessionId) {
    boolean[] emptied = { false };
    sessions.computeIfPresent(userId, (key, current) -> {
      if (sessionId != null) {
        current.remove(sessionId);
      }
      if (current.isEmpty()) {
        emptied[0] = true;
        return null;
      }
      return current;
    });
    if (sessionId == null) {
      // 拿不到会话 id 时不敢猜「那一个」是谁：直接按「这个人没有活跃会话了」处理，
      // 宁可多推一次离线（前端只是少一个绿点），也不要留下一个永远绿着的假在线。
      Object removed = sessions.remove(userId);
      return removed != null;
    }
    return emptied[0];
  }

  /** Principal name 就是用户 id 字符串（{@link StompPrincipalHandshakeHandler} 定死的口径）。 */
  private static Long userIdOf(Principal principal, Object event) {
    if (principal == null) {
      return null;
    }
    String name = principal.getName();
    if (name == null) {
      return null;
    }
    try {
      return Long.parseLong(name.trim());
    } catch (NumberFormatException e) {
      log.warn("连接事件的 Principal 不是用户 id，跳过在线登记 name={} event={}", name,
          event == null ? "null" : event.getClass().getSimpleName());
      return null;
    }
  }

  /**
   * 取会话 id。
   *
   * <p><b>不用 {@code headers.get("id")} 手工取</b>：那个键名是 STOMP 协议的内部约定，
   * Spring 用 {@link SimpMessageHeaderAccessor#getSessionId()} 封装它，将来改键名只会改封装。
   * 手工取字符串错了不报错，只会让「第一个会话」的判据永远成立（对端收到三条上线通知）。</p>
   */
  private static String sessionIdOf(SessionConnectedEvent event) {
    if (event.getMessage() == null) {
      return null;
    }
    return SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
  }

  private static String describe(Throwable e) {
    String msg = e.getMessage();
    return e.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
  }
}

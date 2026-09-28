package com.mindisle.pm;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;

/**
 * 唯一一个会往外发 STOMP 帧的地方（任务 T5.2/T5.3 · 手册 §8.1）。
 *
 * <p><b>业务代码不许直接注入 {@code SimpMessagingTemplate}</b>：本类的返回值是
 * 「这条推送有没有真的写进对方一个活跃会话」，而框架自己不会告诉你这件事——
 * {@code convertAndSendToUser} 在目标用户不在线时<b>不抛异常</b>，它只在 debug 日志里
 * 写一句 "user not found" 然后把消息丢掉。如果调用方按「没抛异常＝送达」去写状态机，
 * {@code private_message.status} 就会永远停在 delivered，而那个人其实一条都没收到，
 * 定时重投（T5.3）也就永远不会被触发。所以这里<b>先查会话再发</b>，把那个假成功变成 false。</p>
 *
 * <p>异常一律吞掉只记日志：私信本体已经落库（NFR6「先落库再推送」），
 * 推送失败的最坏结果是「对方晚一点看到」，重投作业会补。让它把事务或 HTTP 请求带崩，
 * 才是真的把一条已经写进库的消息变成一次失败发送。</p>
 */
@Component
public class PmPushGateway {

  private static final Logger log = LoggerFactory.getLogger(PmPushGateway.class);

  /** 目的地常量集中在这里，Service 与监听器都不再自带字符串（手册 §8.4 第 1 条）。 */
  public static final String DEST_PRIVATE = "/queue/private";
  public static final String DEST_ACK = "/queue/ack";
  public static final String DEST_NOTIFY = "/queue/notify";
  public static final String DEST_ALERT = "/queue/alert";
  public static final String DEST_PRESENCE = "/queue/presence";
  public static final String DEST_PONG = "/queue/ping";
  public static final String TOPIC_PRESENCE = "/topic/presence";

  private final SimpMessagingTemplate template;
  private final SimpUserRegistry userRegistry;

  public PmPushGateway(SimpMessagingTemplate template, SimpUserRegistry userRegistry) {
    this.template = template;
    this.userRegistry = userRegistry;
  }

  /** 这个人此刻有没有至少一个活跃会话（在线判据的唯一来源，别在别处再算一遍）。 */
  public boolean isOnline(long userId) {
    SimpUser user = findUser(userId);
    return user != null && user.hasSessions();
  }

  /** 此刻在线的登录用户总数（给 {@code /topic/presence} 那个只带数量的广播用）。 */
  public int onlineUserCount() {
    return userRegistry.getUserCount();
  }

  /**
   * 点对点推送。<b>返回 false 的含义是「没写进活跃会话」，不是「发送失败」</b>：
   * 消息本身已经在库里，调用方据此决定要不要把状态留在 sent 等重投。
   */
  public boolean sendToUser(long userId, String destination, Object payload) {
    if (!isOnline(userId)) {
      return false;
    }
    try {
      template.convertAndSendToUser(String.valueOf(userId), destination, payload);
      return true;
    } catch (Exception e) {
      log.warn("STOMP 点对点推送失败（消息已在库里，交给重投）uid={} dest={} err={}",
          userId, destination, describe(e));
      return false;
    }
  }

  /** 广播。只允许发<b>不含个人身份</b>的载荷，见 {@code PresenceRegistry} 的隐私判据。 */
  public void broadcast(String destination, Map<String, Object> payload) {
    try {
      // 显式转 Object：convertAndSend 同时存在 (D,Object) 与 (Object,Map) 两个重载，
      // 传 String + Map 会让编译器两边都匹配（ambiguous），这里选「目的地 + 载荷」那一个。
      template.convertAndSend(destination, (Object) payload);
    } catch (Exception e) {
      log.warn("STOMP 广播失败 dest={} err={}", destination, describe(e));
    }
  }

  private SimpUser findUser(long userId) {
    try {
      return userRegistry.getUser(String.valueOf(userId));
    } catch (Exception e) {
      // 注册表本身不该抛，但它可能在客户端异常断连的瞬间被并发读到中间态；
      // 一次 warn 换「整个发送链路不崩」，值。
      log.warn("在线注册表查询失败 uid={} err={}", userId, describe(e));
      return null;
    }
  }

  /** 异常一句话说明：类名 + message，两者都为空时只剩类名，日志仍然可读。 */
  private static String describe(Throwable e) {
    String msg = e.getMessage();
    return e.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
  }
}

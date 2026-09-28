package com.mindisle.notify;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.mindisle.entity.NotifyMessage;
import com.mindisle.pm.PmPushGateway;

/**
 * 推送口的阶段 5 实现：通知落库后往 {@code /user/queue/notify} 推一帧（任务 T5.8 · 需求 FR9.2）。
 *
 * <p>标 {@link Primary} 接管 {@link NotifyService.PushHook}，{@link LoggingPushHook} 留在仓库里
 * 作为「WS 不可用时关掉推送」的开关备份。{@code NotifyService} 与三个业务服务一行未改 ——
 * 这正是当初把推送口抽成接口的唯一回报。</p>
 *
 * <p><b>payload 恰好四键</b> {@code {type, id, unread, ts}}（手册 §10.4 与 §8.1 的口径，
 * 前端 T5.8 也只认这四个）。刻意<b>不带</b> title/content：私信正文、评论原文都属于
 * 「会显示在锁屏和浏览器弹窗上的内容」，需求 BR11 要求最小展示；toast 文案由前端
 * 收到帧后再拉一次通知列表首页获得，那条请求本来就要走鉴权。</p>
 *
 * <p><b>为什么发送要挪到 afterCommit</b>：{@code onCreated} 是在业务事务内部被调的
 * （点赞/评论/关注都包在 {@code @Transactional} 里）。不延后有两个后果：
 * ① {@link NotifyService.NotifyStore#countUnread} 读不到自己刚插入但还没提交的那行，
 * 推出去的 unread 永远比真实值小 1，前端红点会「少一个」并要等下一次刷新才对上；
 * ② 事务回滚了而推送已经发出，用户点进去发现什么都没有 —— 这是「幽灵通知」，
 * 比晚一帧严重得多。延后的代价是通知晚几毫秒到达，可接受。</p>
 *
 * <p><b>异常一律吞掉</b>：通知已经在库里，红点在用户下次拉列表时一定会出现；
 * 为一帧推送把点赞请求打成 500 是本末倒置（同 {@link PmPushGateway} 的取舍）。</p>
 */
@Component
@Primary
public class StompPushHook implements NotifyService.PushHook {

  private static final Logger log = LoggerFactory.getLogger(StompPushHook.class);

  /** 站内通知类型 → 推送帧里的 type。手册定的五个取值是 dm/comment/follow/system/risk，
   * 其余（like/report/audit）统一折进 system：前端只用 type 决定「跳哪儿 + 要不要亮私信角标」，
   * 点赞和举报在红点层面没有区别。*/
  private static final Map<String, String> WIRE_TYPE = wireType();

  private final PmPushGateway gateway;
  private final NotifyService.NotifyStore store;

  public StompPushHook(PmPushGateway gateway, NotifyService.NotifyStore store) {
    this.gateway = gateway;
    this.store = store;
  }

  private static Map<String, String> wireType() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put(NotifyMessage.TYPE_PM, "dm");
    map.put(NotifyMessage.TYPE_CRISIS, "risk");
    map.put(NotifyMessage.TYPE_COMMENT, "comment");
    map.put(NotifyMessage.TYPE_FOLLOW, "follow");
    return java.util.Collections.unmodifiableMap(map);
  }

  @Override
  public void onCreated(NotifyMessage row) {
    if (row == null || row.getId() == null || row.getUserId() == null) {
      // 自增主键没回填说明 insert 根本没成功，这时候推一帧只会把前端领到一个 404。
      log.warn("通知推送被跳过：行或主键为空 row={}", row);
      return;
    }
    long userId = row.getUserId();
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          send(row, userId);
        }
      });
      return;
    }
    send(row, userId);
  }

  /** 真正发帧。拆出来是为了让「有事务/没事务」两条路共用同一个异常口径。 */
  private void send(NotifyMessage row, long userId) {
    try {
      Map<String, Object> payload = new LinkedHashMap<>(4);
      payload.put("type", WIRE_TYPE.getOrDefault(row.getType(), "system"));
      payload.put("id", row.getId());
      payload.put("unread", store.countUnread(userId));
      payload.put("ts", System.currentTimeMillis());
      boolean delivered = gateway.sendToUser(userId, PmPushGateway.DEST_NOTIFY, payload);
      if (!delivered) {
        // 不在线是常态（私信对方可能压根没开着页面），info 都不配，debug 留一线排查痕迹。
        log.debug("通知未实时下发（收件人不在线）user={} notifyId={}", userId, row.getId());
      }
    } catch (Exception e) {
      log.warn("通知推送失败（红点将在下次拉取时补上）user={} notifyId={} err={} {}",
          userId, row.getId(), e.getClass().getSimpleName(), e.getMessage());
    }
  }
}

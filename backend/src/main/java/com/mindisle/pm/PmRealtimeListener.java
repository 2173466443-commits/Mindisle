package com.mindisle.pm;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.PrivateMessage;
import com.mindisle.notify.NotifyService;
import com.mindisle.pm.dto.PmAckView;
import com.mindisle.pm.dto.PmMessageView;

/**
 * 事务提交之后的那半条链路（任务 T5.3 / T5.6 · 手册 §8.1）。
 *
 * <p><b>{@code phase = AFTER_COMMIT} 是本类存在的全部理由。</b>如果在 {@code PmService#send}
 * 的事务里就推送，接收方会在提交前收到消息并立刻回一条已读上报，那条 UPDATE 撞上
 * 一个还没提交的行：轻则回执 0 行（前端气泡永远停在「送达中」），重则两个事务互相等。
 * {@code fallbackExecution = true} 是为了让「没有事务的调用」（单测直接 new 一个 store 跑判据）
 * 也照样把事件消费掉，而不是静默丢弃——静默丢弃会让单测绿、线上不推。</p>
 *
 * <p><b>本类一个字都不改 {@code private_message} 的正文</b>：它只做三件事——
 * 推给对方、把 sent 抬成 delivered、告诉通知中心「有人给你发消息了」。
 * 落库在 {@link PmService} 里已经做完了，这里任何一步失败都<b>不影响消息本身的存在</b>，
 * 这是「落库优先」的实际含义（手册 §8.2 第 1 条）。</p>
 *
 * <p><b>不吞异常也不重抛</b>：{@link PmPushGateway} 已经把 IO 失败吞成 warn 并返回 false，
 * 本类因此只需要处理「推没推成功」这个布尔。若在这里抛出，AFTER_COMMIT 阶段已经没有事务可回滚，
 * 异常只会顺着监听器链回传到调用方（也就是已经提交成功的那次 send 请求），
 * 把一次成功的发送变成一个 500——那是最坏的失败形态。</p>
 */
@Component
public class PmRealtimeListener {

  private static final Logger log = LoggerFactory.getLogger(PmRealtimeListener.class);

  private final PmPushGateway gateway;
  private final PmStore store;
  private final PmService pmService;
  private final NotifyService notifyService;
  private final MindisleProperties properties;

  public PmRealtimeListener(PmPushGateway gateway, PmStore store, PmService pmService,
      NotifyService notifyService, MindisleProperties properties) {
    this.gateway = gateway;
    this.store = store;
    this.pmService = pmService;
    this.notifyService = notifyService;
    this.properties = properties;
  }

  /**
   * 新私信到达：推本体 → 回执「已送达」→ 写站内通知 → 危机时两边都弹求助卡。
   *
   * <p><b>顺序是判据的一部分</b>：先推本体再改状态，反过来会让前端在收到气泡的同一条连接上
   * 先收到自己的「已送达」回执——那一刻气泡还没画出来，回执找不到对应的气泡，只能被丢掉。</p>
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onSent(PmSendEvent event) {
    PrivateMessage row = event.row();
    PmMessageView view = pmService.viewForReceiver(row);
    boolean delivered = gateway.sendToUser(event.recipientId(), PmPushGateway.DEST_PRIVATE, view);
    if (delivered) {
      // 只有真的推进去了才抬状态：抬早了，重投作业就再也看不见这条，消息会永远停在「离线用户的收件箱里不提醒」。
      int changed = store.markDelivered(row.getId());
      gateway.sendToUser(event.senderId(), PmPushGateway.DEST_ACK, new PmAckView(
          "delivered", row.getClientMsgId(), row.getId(), event.recipientId(),
          null, null, LocalDateTime.now(), null, null));
      log.info("私信已实时送达 msgId={} from={} to={} 状态翻转={}", row.getId(),
          event.senderId(), event.recipientId(), changed);
    } else {
      log.info("收件人不在线，留待重投 msgId={} to={}", row.getId(), event.recipientId());
    }

    // 通知与实时下发是两条腿：人不在 WS 上时，站内红点是唯一的发现途径（需求 FR9.1）。
    notifyService.notifyPm(event.recipientId(), event.senderName(), event.senderId(),
        PrivateMessage.TYPE_IMAGE.equals(row.getMsgType()) ? "[图片]" : row.getContent());

    if (PmService.TICKET_LEVELS.contains(event.crisisLevel())) {
      pushAlert(event.senderId(), row.getId(), event.crisisLevel(), "sender");
      pushAlert(event.recipientId(), row.getId(), event.crisisLevel(), "receiver");
      List<Long> admins = store.listAdminIds();
      notifyService.notifyPmCrisisAdmin(admins, event.senderId(), row.getId(),
          event.crisisLevel(), event.crisisExcerpt());
      log.warn("私信危机已同步 msgId={} level={} ticketId={} admins={}",
          row.getId(), event.crisisLevel(), event.ticketId(), admins.size());
    }
  }

  /**
   * 已读回执（需求 FR6.4）：告诉「当初发这些话的人」它们被读了几条。
   *
   * <p>推给 {@code event.peerId()}（= 发信方）而不是读者自己：读者屏幕上那些气泡的
   * 「已读」标记由前端本地立刻打勾，不需要等这条回执绕一圈；回执的唯一读者是发信方。</p>
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onRead(PmReadEvent event) {
    gateway.sendToUser(event.peerId(), PmPushGateway.DEST_ACK, new PmAckView(
        "read", null, null, event.readerId(), event.count(), null,
        LocalDateTime.now(), null, null));
  }

  /**
   * 求助卡是<b>点对点</b>的 {@code /user/queue/alert}，不是广播。
   *
   * <p>载荷里只有等级、消息 id 和「给谁看的」这一句视角提示，<b>没有私信正文</b>：
   * 弹窗可能在共享屏幕上被旁人看到（需求 BR11 最小展示原则），
   * 而当事人本来就在这条会话里，正文他自己屏幕上有。</p>
   *
   * <p><b>载荷是 Map 而不是新造一个 DTO</b>：它只有五个键、只喂给前端一张卡片，
   * 而 {@link PmPushGateway#broadcast} 那条路本来就只能发 Map——两边同形状，
   * 前端一个 handler 就够。<b>热线写在这里</b>：这张卡出现的时刻，用户可能正处在
   * 「不想再点进任何页面」的状态，号码必须在屏幕上当场可读。</p>
   */
  private void pushAlert(long userId, long messageId, String level, String side) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("level", level);
    payload.put("messageId", messageId);
    payload.put("side", side);
    payload.put("hotline", properties.getCrisis().getHotline());
    payload.put("at", LocalDateTime.now());
    gateway.sendToUser(userId, PmPushGateway.DEST_ALERT, payload);
  }
}

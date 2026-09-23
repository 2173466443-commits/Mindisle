package com.mindisle.notify;

import com.mindisle.entity.NotifyMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 推送口的当前实现：<b>只记一行日志，不推任何东西</b>（任务 T3.11-b · 手册 §6.1 行 3.11「预留 WebSocket 推送口」）。
 *
 * <p>存在的意义是把「接 WebSocket」这件事的改动面压到零：阶段 5 的 T5.8 新写一个
 * {@code WebSocketPushHook implements NotifyService.PushHook} 并标 {@code @Primary} 就完成切换，
 * 通知写链路、三个业务服务、冒烟脚本都不用动第二遍。payload 口径手册 §10.4 已经定死
 * {@code {type, id, unread, ts}}，这里连日志字段都按这四个名字打，换实现时对得上。</p>
 *
 * <p><b>不吞异常</b>：这一版只有一行 log，没什么可吞的；将来接 WS 时如果抛错，
 * 该由 WS 实现自己决定降级成轮询（FR9.3），而不是在本类里悄悄吃掉。</p>
 */
@Component
public class LoggingPushHook implements NotifyService.PushHook {

  private static final Logger log = LoggerFactory.getLogger(LoggingPushHook.class);

  @Override
  public void onCreated(NotifyMessage row) {
    if (log.isDebugEnabled()) {
      log.debug("notify created: type={} id={} user={} ref={}:{}",
          row.getType(), row.getId(), row.getUserId(), row.getRefType(), row.getRefId());
    }
  }
}

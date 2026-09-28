package com.mindisle.notify;

import com.mindisle.entity.NotifyMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 推送口在<b>阶段 3</b> 的实现：<b>只记一行日志，不推任何东西</b>（任务 T3.11-b · 手册 §6.1 行 3.11「预留 WebSocket 推送口」）。
 *
 * <p>存在的意义是把「接 WebSocket」这件事的改动面压到零：阶段 5 的 T5.8 新写一个
 * {@link StompPushHook}（已落地并标 {@code @Primary}）就完成切换，
 * 通知写链路、三个业务服务、冒烟脚本都不用动第二遍。payload 口径手册 §10.4 已经定死
 * {@code {type, id, unread, ts}}，这里连日志字段都按这四个名字打，换实现时对得上。</p>
 *
 * <p><b>本类现在还在容器里</b>：它只是不再是 {@code NotifyService} 注入到的那一个。
 * 保留它有两个用处：① WS 出问题时把 {@code @Primary} 摘掉就退回「只记日志不推送」，
 * 不用删代码；② 它是这个口在阶段 3 的原始契约证据，单测里按它断言过字段名。</p>
 *
 * <p><b>不吞异常</b>：这一版只有一行 log，没什么可吞的；接上 WS 之后的异常口径由
 * {@link StompPushHook} 自己定（它吞掉并让前端降级成轮询，需求 FR9.3），不在本类里。</p>
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

package com.mindisle.notify;

import com.mindisle.entity.NotifyMessage;
import com.mindisle.mapper.NotifyMessageMapper;
import com.mindisle.notify.NotifyService.NotifyStore;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 通知存储端口的 MyBatis 适配器（任务 T3.11-b · {@link NotifyStore} 的真实现）。
 *
 * <p>分工与 {@code ReportStoreAdapter}、{@code CommentStoreAdapter} 一致：只做端口方法名
 * 到 Mapper 语句的翻译，不掺判断、<b>也不吞数据库异常</b>（连接断了就该让点赞一起回滚，
 * 理由见 {@link NotifyService} 类注释第 3 条）。</p>
 */
@Component
public class NotifyStoreAdapter implements NotifyStore {

  private static final Logger log = LoggerFactory.getLogger(NotifyStoreAdapter.class);

  private final NotifyMessageMapper mapper;

  public NotifyStoreAdapter(NotifyMessageMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public boolean existsUnreadDuplicate(long userId, String type, String refType, long refId,
                                       String title, String content) {
    return mapper.findUnreadDuplicate(userId, type, refType, refId, title, content) != null;
  }

  @Override
  public void insert(NotifyMessage row) {
    mapper.insertOne(row);
  }

  @Override
  public long countUnread(long userId) {
    return mapper.countUnread(userId);
  }

  @Override
  public List<NotifyMessage> page(long userId, Long beforeId, int limit) {
    return mapper.pageBefore(userId, beforeId, limit);
  }

  @Override
  public int markRead(long userId, List<Long> ids, LocalDateTime now) {
    return mapper.markRead(userId, ids, now);
  }

  @Override
  public int markAllRead(long userId, LocalDateTime now) {
    return mapper.markAllRead(userId, now);
  }
}

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

  /**
   * 落一行通知。{@code isRead} 由 {@code NotifyService#write} 按偏好闸门定（0=提醒 / 1=这一类被本人关掉），
   * 本适配器不掺判断 —— 包括「null 当成 0」这种好心的兜底：{@code NotifyMessage#isRead} 为 null 时
   * SQL 会插出 NULL 而列是 NOT NULL DEFAULT 0，MySQL 在严格模式下直接报错，
   * 那比静默把一条该亮的红点写成已读更容易发现（同 {@code PostingQuotaService} 对脏值的口径）。
   */
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

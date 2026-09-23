package com.mindisle.post;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.mindisle.entity.AuditTask;
import com.mindisle.entity.ContentReport;
import com.mindisle.entity.Post;
import com.mindisle.entity.PostStatusLog;
import com.mindisle.entity.User;
import com.mindisle.mapper.AuditTaskMapper;
import com.mindisle.mapper.ContentReportMapper;
import com.mindisle.mapper.PostMapper;
import com.mindisle.mapper.PostStatusLogMapper;
import com.mindisle.mapper.UserMapper;
import com.mindisle.post.ReportService.ReportStore;
import org.springframework.stereotype.Component;

/**
 * 举报存储端口的 MyBatis 适配器（任务 T3.11 · {@link ReportStore} 的真实现）。
 *
 * <p>与 {@link CommentStoreAdapter}、{@link PostInteractionStoreAdapter} 同一分工：
 * 本类只做「翻译」——把端口那十一个方法名换成 Mapper 里已有的语句，不掺任何业务判断，
 * 也<b>不吞任何数据库异常</b>（表不存在、连接断开照常抛出，由 GlobalExceptionHandler 转 90002/503）。
 * 端口里那些「返回 0 行」是业务幂等（重复举报、状态被别人改走了），不是错误兜底，两者不能混。</p>
 */
@Component
public class ReportStoreAdapter implements ReportStore {

  private final PostMapper postMapper;
  private final UserMapper userMapper;
  private final ContentReportMapper contentReportMapper;
  private final AuditTaskMapper auditTaskMapper;
  private final PostStatusLogMapper statusLogMapper;

  public ReportStoreAdapter(PostMapper postMapper, UserMapper userMapper,
                           ContentReportMapper contentReportMapper, AuditTaskMapper auditTaskMapper,
                           PostStatusLogMapper statusLogMapper) {
    this.postMapper = postMapper;
    this.userMapper = userMapper;
    this.contentReportMapper = contentReportMapper;
    this.auditTaskMapper = auditTaskMapper;
    this.statusLogMapper = statusLogMapper;
  }

  @Override
  public Post findPost(long postId) {
    // @TableLogic 过滤 deleted=1：下架并删除的帖子在这里与「从来没有过」同一个 null，
    // 服务层据此报 404/30001，不区分二者（区分就是枚举通道）。
    return postMapper.selectById(postId);
  }

  @Override
  public User findUser(long userId) {
    return userMapper.selectById(userId);
  }

  @Override
  public int insertReport(ContentReport row) {
    return contentReportMapper.insertIgnore(row);
  }

  @Override
  public void refreshPostReportCnt(long postId) {
    postMapper.refreshReportCnt(postId);
  }

  @Override
  public long countPostReporters(long postId) {
    return contentReportMapper.countPostReporters(postId);
  }

  @Override
  public AuditTask findPendingTask(String targetType, long targetId) {
    return auditTaskMapper.findPending(targetType, targetId);
  }

  @Override
  public int insertAuditTask(AuditTask task) {
    int rows = auditTaskMapper.insertIgnore(task);
    // insertIgnore 走的是裸 @Insert + @Options(useGeneratedKeys)：撞唯一键返回 0 时，
    // 自增值已被 MySQL 消耗，回填进实体的 id 不可信。这里刻意不清空它——
    // 服务层在 0 行时按约定改用 findPendingTask 重新定位（见 ReportService#ensureTask）。
    return rows;
  }

  @Override
  public boolean escalateTask(long id, String level, BigDecimal score, LocalDateTime sla,
                              LocalDateTime now) {
    return auditTaskMapper.escalate(id, level, score, sla, now) == 1;
  }

  @Override
  public int markPostHumanReview(long postId) {
    return postMapper.compareAndSetStatus(postId, PostService.STATUS_PUBLISHED,
        PostService.STATUS_HUMAN_REVIEW);
  }

  @Override
  public void logPostStatus(long postId, String fromStatus, String toStatus, String reason) {
    PostStatusLog row = new PostStatusLog();
    row.setPostId(postId);
    row.setFromStatus(fromStatus);
    row.setToStatus(toStatus);
    // operatorId 留 null：这是系统流转，触发者是举报人，而举报人记录在 content_report 里，
    // 这里写他会把「谁干的」和「谁让系统干的」混成一列（DDL 注释要求系统流转在 reason 里标 system）。
    row.setReason(reason);
    statusLogMapper.insert(row);
    // createdAt 不在 Java 侧赋值：与 PostService#logStatus 一致，交给 DDL 的 DEFAULT CURRENT_TIMESTAMP(3)
  }
}

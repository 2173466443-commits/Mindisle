package com.mindisle.post;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindisle.entity.AlertTicket;
import com.mindisle.entity.AnonymousAlias;
import com.mindisle.entity.Comment;
import com.mindisle.entity.Post;
import com.mindisle.entity.User;
import com.mindisle.mapper.AlertTicketMapper;
import com.mindisle.mapper.AnonymousAliasMapper;
import com.mindisle.mapper.CommentMapper;
import com.mindisle.mapper.PostMapper;
import com.mindisle.mapper.UserMapper;
import org.springframework.stereotype.Component;

/**
 * 评论存储端口的 MyBatis 适配器（任务 3.7 · {@link CommentService.CommentStore} 的真实现）。
 *
 * <p>与 {@link PostInteractionStoreAdapter} 同一分工：本类只做「翻译」，不掺业务判断，
 * 也<b>不吞任何数据库异常</b>（表不存在、连接断开照常抛出，由 GlobalExceptionHandler 转
 * 90002/503）。端口里那些「0 行」是业务幂等，不是错误兜底，两者不能混。</p>
 *
 * <p><b>「对某人可见」这条 SQL 判据是 {@link CommentService#isVisibleTo} 的镜像</b>：
 * 已发布人人可见 + 待审仅作者可见。两处必须逐字一致，所以两边都写了同一句枚举条件，
 * 并各配一条测试（服务层用内存替身断言，真库口径由 docs/smoke.mjs 的第 16 步断言）。
 * 为什么不复用同一个函数：SQL 里没有 Java 函数可以调，硬要「共享」只能把判据拼成字符串常量，
 * 反而更容易在 Wrapper 里写错括号优先级。</p>
 */
@Component
public class CommentStoreAdapter implements CommentService.CommentStore {

  private final PostMapper postMapper;
  private final UserMapper userMapper;
  private final CommentMapper commentMapper;
  private final AnonymousAliasMapper anonymousAliasMapper;
  private final AlertTicketMapper alertTicketMapper;

  public CommentStoreAdapter(PostMapper postMapper, UserMapper userMapper, CommentMapper commentMapper,
                             AnonymousAliasMapper anonymousAliasMapper, AlertTicketMapper alertTicketMapper) {
    this.postMapper = postMapper;
    this.userMapper = userMapper;
    this.commentMapper = commentMapper;
    this.anonymousAliasMapper = anonymousAliasMapper;
    this.alertTicketMapper = alertTicketMapper;
  }

  @Override
  public Post findPost(long postId) {
    return postMapper.selectById(postId);
  }

  @Override
  public User findUser(long userId) {
    return userMapper.selectById(userId);
  }

  @Override
  public Comment findComment(long commentId) {
    // @TableLogic 自动过滤 deleted=1，所以「被删的评论」在这里与「不存在的评论」同一个 null，
    // 服务层据此报 400 而不是 404，不泄露两者差别。
    return commentMapper.selectById(commentId);
  }

  @Override
  public void insertComment(Comment comment) {
    commentMapper.insert(comment);
  }

  @Override
  public void refreshPostCommentCnt(long postId) {
    postMapper.refreshCommentCnt(postId);
  }

  @Override
  public long countPublishedComments(long postId) {
    return commentMapper.countPublished(postId);
  }

  @Override
  public List<Comment> pageVisibleRoots(long postId, long viewerId, long offset, int limit) {
    return commentMapper.selectList(visibleTo(viewerId)
        .eq(Comment::getPostId, postId)
        .isNull(Comment::getParentId)
        .orderByAsc(Comment::getId)
        // offset/limit 都是 long/int，且 limit 已被 PageQuery.normalize 夹在 1..50，没有注入面
        .last("limit " + limit + " offset " + offset));
  }

  @Override
  public long countVisibleRoots(long postId, long viewerId) {
    return commentMapper.selectCount(visibleTo(viewerId)
        .eq(Comment::getPostId, postId)
        .isNull(Comment::getParentId));
  }

  @Override
  public List<Comment> listVisibleReplies(long postId, Collection<Long> rootIds, long viewerId) {
    if (rootIds == null || rootIds.isEmpty()) {
      return List.of();
    }
    return commentMapper.selectList(visibleTo(viewerId)
        .eq(Comment::getPostId, postId)
        .in(Comment::getRootId, rootIds)
        .orderByAsc(Comment::getRootId)
        .orderByAsc(Comment::getId));
  }

  @Override
  public Map<Long, User> mapUsers(Collection<Long> userIds) {
    Map<Long, User> map = new LinkedHashMap<>();
    if (userIds == null || userIds.isEmpty()) {
      return map;
    }
    // 注销账号被 @TableLogic 过滤掉，天然不进这张表 -> 展示名回退「已注销的屿民」，与帖子列表同一兜底
    for (User user : userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getId, userIds))) {
      map.put(user.getId(), user);
    }
    return map;
  }

  @Override
  public Map<Long, AnonymousAlias> mapAliases(Collection<Long> aliasIds) {
    Map<Long, AnonymousAlias> map = new LinkedHashMap<>();
    if (aliasIds == null || aliasIds.isEmpty()) {
      return map;
    }
    for (AnonymousAlias alias : anonymousAliasMapper.selectList(
        new LambdaQueryWrapper<AnonymousAlias>().in(AnonymousAlias::getId, aliasIds))) {
      map.put(alias.getId(), alias);
    }
    return map;
  }

  @Override
  public void insertAlertTicket(AlertTicket ticket) {
    alertTicketMapper.insert(ticket);
  }

  /**
   * 「对此人可见」的评论状态条件。
   *
   * <p>括号是这条 SQL 的全部要害：{@code post_id = ? AND (status='PUBLISHED' OR (status='PENDING'
   * AND user_id=?))}。写成 {@code ... AND status='PUBLISHED' OR ...} 会让 OR 把 post_id 条件一起吃掉，
   * 于是 A 帖的一条待审评论会出现在 B 帖的评论区里，而接口只查了 B 帖——这是列表接口最贵的一类 bug。</p>
   */
  private static LambdaQueryWrapper<Comment> visibleTo(long viewerId) {
    return new LambdaQueryWrapper<Comment>()
        .and(w -> w.eq(Comment::getStatus, Comment.STATUS_PUBLISHED)
            .or(o -> o.eq(Comment::getStatus, Comment.STATUS_PENDING).eq(Comment::getUserId, viewerId)));
  }
}

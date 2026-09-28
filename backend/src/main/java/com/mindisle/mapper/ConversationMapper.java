package com.mindisle.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mindisle.entity.Conversation;

/**
 * 会话头 Mapper（任务 T4.2）。
 *
 * <p><b>所有查询都自带 user_id 条件</b>，包括按 id 取单条。理由不是「防 SQL 注入」，
 * 而是这条链路上后面每一步都要用会话：如果 {@code selectById} 能把别人的会话捞出来，
 * 服务层只要有一处忘了比对归属，就会变成跨账号读写。<b>归属写在 SQL 里，就不可能在 Java 里漏掉。</b></p>
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

  /** 会话列表：只取 ACTIVE，按最后一条消息时间倒序（走 idx_user_last 索引，任务 T4.2）。 */
  default List<Conversation> listActive(long userId, int limit) {
    return selectList(new LambdaQueryWrapper<Conversation>()
        .eq(Conversation::getUserId, userId)
        .eq(Conversation::getStatus, "ACTIVE")
        .orderByDesc(Conversation::getLastMsgAt)
        .orderByDesc(Conversation::getId)
        .last("limit " + Math.max(1, Math.min(limit, 200))));
  }

  /** 带归属校验的单条读取；查不到即「不存在或不是你的」，服务层统一回 90006，不区分这两种。 */
  default Conversation findByIdOwned(long userId, long conversationId) {
    return selectOne(new LambdaQueryWrapper<Conversation>()
        .eq(Conversation::getUserId, userId)
        .eq(Conversation::getId, conversationId)
        .last("limit 1"));
  }

  /**
   * 用户主动删除会话（需求 FR2.1 · 任务 T4.2）：status 与 deleted <b>一起改</b>。
   *
   * <p>为什么不直接 {@code deleteById}（{@code @TableLogic} 会自动只置 deleted=1）：
   * 那会让这一行留在库里、status 还是 ACTIVE，而 {@code countActive} 与配额回收那条 SQL
   * 都带 {@code status = 'ACTIVE' AND deleted = 0} 两个条件 —— 今天恰好不出错，
   * 但「删掉的会话 status 仍是 ACTIVE」是一颗迟早会咬人的雷：任何一条只按 status 过滤的
   * 统计或管理端查询都会把已删会话算进去。两处一起写，等于把语义钉在同一行里。
   * 条件里带 user_id 是归属校验，返回 0 即「不存在或不是你的」。</p>
   */
  @Update("UPDATE conversation SET status = 'DELETED', deleted = 1 "
      + "WHERE id = #{id} AND user_id = #{userId} AND deleted = 0")
  int softDeleteOwned(@Param("userId") long userId, @Param("id") long id);

  /** 数一下还有多少个活跃会话，用于「保留最近 50 个」的配额判定。 */
  @Select("SELECT COUNT(*) FROM conversation WHERE user_id = #{userId} AND status = 'ACTIVE' AND deleted = 0")
  int countActive(@Param("userId") long userId);

  /**
   * 把某用户名下除最近 {@code keep} 个之外的活跃会话整体逻辑删除（任务 T4.2「超出逻辑删除最旧」）。
   *
   * <p>用一条 UPDATE 而不是「查出来再循环 deleteById」：后者在并发下会把刚建的新会话也算进
   * 「旧的」里。子查询里的 {@code ORDER BY last_msg_at DESC LIMIT} 就是「保留集合」的定义。
   * MySQL 不允许在 UPDATE 的子查询里直接引用同一张表，所以外面再套一层派生表。</p>
   */
  @Update("UPDATE conversation SET status = 'DELETED', deleted = 1 "
      + "WHERE user_id = #{userId} AND status = 'ACTIVE' AND deleted = 0 "
      + "AND id NOT IN (SELECT id FROM (SELECT id FROM conversation "
      + "  WHERE user_id = #{userId} AND status = 'ACTIVE' AND deleted = 0 "
      + "  ORDER BY last_msg_at DESC, id DESC LIMIT #{keep}) keep_ids)")
  int softDeleteBeyondQuota(@Param("userId") long userId, @Param("keep") int keep);

  /** 分页版会话列表（侧栏以后要「加载更多」，先把分页口径定下来，前端只调 size 不改代码）。 */
  default Page<Conversation> pageActive(long userId, int pageNo, int pageSize) {
    return selectPage(new Page<>(Math.max(1, pageNo), Math.max(1, Math.min(pageSize, 100))),
        new LambdaQueryWrapper<Conversation>()
            .eq(Conversation::getUserId, userId)
            .eq(Conversation::getStatus, "ACTIVE")
            .orderByDesc(Conversation::getLastMsgAt)
            .orderByDesc(Conversation::getId));
  }

  /**
   * 刷新会话的「最后消息时间」。
   *
   * <p>为什么不用 {@code updateById(entity)}：那要求调用方先持有整个实体，
   * 一旦它拿的是旧快照，就会顺手把 {@code title}/{@code summary} 的并发改动覆盖掉。
   * 只写要写的那一列（updated_at 由列上的 ON UPDATE 自己动）。</p>
   */
  @Update("UPDATE conversation SET last_msg_at = NOW(3) WHERE id = #{id} AND user_id = #{userId} AND deleted = 0")
  int touch(@Param("userId") long userId, @Param("id") long id);

  /**
   * 改本会话的人格（任务 T4.4：用户在设置里换了风格，已有会话要跟着变）。
   *
   * <p>条件里带 {@code style <> #{style}}：一样的时候返回 0，调用方据此决定要不要写日志，
   * 而不是每次都发一条 UPDATE 出去。仍然只写这一列，不用 {@code updateById(entity)} ——
   * 那条会把 {@code summary} 的并发改动覆盖掉，理由与 {@link #touch} 相同。</p>
   */
  @Update("UPDATE conversation SET style = #{style} WHERE id = #{id} AND user_id = #{userId} "
      + "AND deleted = 0 AND style <> #{style}")
  int updateStyle(@Param("userId") long userId, @Param("id") long id, @Param("style") String style);

  /**
   * 改标题（任务 T4.2 / 需求 FR2.1「会话可重命名」，v1.2.6 补齐）。
   *
   * <p>与 {@link #touch}、{@link #updateStyle} 同一条纪律：<b>只写 title 这一列</b>，
   * 不用 updateById(entity) —— 那会把并发改掉的 summary / last_msg_at 覆盖回旧快照。
   * 这里刻意<b>不带</b> {@code title <> #{title}} 条件：MySQL 对「值没变」的 UPDATE 本来就返回
   * 0 行受影响，而服务层要靠返回行数区分「不存在」和「改成功」；一旦把同名也算成 0 行，
   * 「改回原标题」就会变成一次假故障。判同名放在服务层做（那里能先读到原值）。</p>
   */
  @Update("UPDATE conversation SET title = #{title} WHERE id = #{id} AND user_id = #{userId} AND deleted = 0")
  int renameOwned(@Param("userId") long userId, @Param("id") long id, @Param("title") String title);
}

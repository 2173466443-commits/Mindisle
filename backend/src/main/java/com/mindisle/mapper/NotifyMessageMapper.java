package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.NotifyMessage;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 站内通知 Mapper（任务 T3.11-b · 需求 FR9.1、FR9.2）。
 *
 * <p>四条裸 SQL 各自解决一件 Wrapper 表达不出来的事：文案级幂等（本表<b>没有唯一键</b>）、
 * 游标分页、批量已读、以及「未读数只走覆盖索引」。其余读写走 BaseMapper。</p>
 */
@Mapper
public interface NotifyMessageMapper extends BaseMapper<NotifyMessage> {

  /**
   * 幂等前置查询：同一个人、同一类、同一个目标、<b>连文案都一样</b>且还没读，就不要再写第二条。
   *
   * <p><b>为什么是「先查后插」而不是唯一索引</b>：本表没有 actor 列（见表注释），
   * 「谁赞了这个帖」这件事只存在于 title 文本里，能构成幂等键的四列
   * (user_id, type, ref_type, ref_id) 在「同一个人先赞后取消再赞」之外还会被
   * 「A 评论、B 回复同一条」共用——那时 title 不同而四列相同，加唯一索引会把 B 的通知挤掉。
   * 所以这里只能按「文案完全相同的未读行」去重：它挡住的是重试与重复触发，
   * 代价是并发下可能出两行（已记进手册 §14，判为可接受：多一行提醒不影响任何数字真相）。</p>
   *
   * <p>只比未读行是有意的：已经读过的那条不该阻止一次<b>新的</b>同类事件再次提醒我。</p>
   */
  @Select("SELECT id FROM notify_message WHERE user_id = #{userId} AND type = #{type} "
      + "AND ref_type = #{refType} AND ref_id = #{refId} AND title = #{title} "
      + "AND content = #{content} AND is_read = 0 AND deleted = 0 LIMIT 1")
  Long findUnreadDuplicate(@Param("userId") long userId, @Param("type") String type,
      @Param("refType") String refType, @Param("refId") long refId,
      @Param("title") String title, @Param("content") String content);

  /**
   * 单列自增主键插入（通知是「一次事件一行」，与 {@code PostMapper#increaseViewCnt} 相反，不做累加）。
   *
   * <p>{@code created_at}/{@code updated_at} 不在列清单里，交给 DDL 的 DEFAULT CURRENT_TIMESTAMP(3)，
   * 与 content_report、audit_task 三条写链路同一口径：通知时间是服务端收到事件的那一刻。</p>
   */
  @Insert("INSERT INTO notify_message (user_id, type, title, content, ref_type, ref_id, is_read) "
      + "VALUES (#{userId}, #{type}, #{title}, #{content}, #{refType}, #{refId}, 0)")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insertOne(NotifyMessage row);

  /**
   * 未读数（红点）。三列全在 {@code idx_user_read(user_id, is_read, id)} 里，
   * 所以 InnoDB 数的是索引而不是回表——这是手册 §6.5 第 4 条「禁止每次开页全表扫」的落地方式。
   *
   * <p><b>与 §6.5 第 4 条的偏离要写白</b>：那一条原本要求「Redis Hash + 落库 unread_count 双写」。
   * 本阶段没做，理由是双写会引入一个需要修复的漂移面（Redis 丢了、MySQL 还在），
   * 而这一版有覆盖索引 COUNT、单用户通知量级又小（几百行），先取「只有一个真相」；
   * 真到红点成为热点路径再按那条约束加缓存，届时替换点就在 {@link #countUnread} 一个方法里。
   * 已记进手册 §14。</p>
   */
  @Select("SELECT COUNT(*) FROM notify_message WHERE user_id = #{userId} AND is_read = 0 AND deleted = 0")
  long countUnread(@Param("userId") long userId);

  /**
   * 游标分页：取比 {@code beforeId} 更旧的 {@code limit} 条（<b>不</b>按 created_at 排序）。
   *
   * <p>排序键用自增 id 而不是时间，与 {@code PostMapper} 的 feed 游标同一个理由：
   * DATETIME(3) 在同一毫秒可以出现多行，用它翻页会重一行或漏一行；id 天然全序。
   * {@code beforeId} 为空时是取最新一页，两条分支合在一条 SQL 里就是
   * {@code AND (#{beforeId} IS NULL OR id < #{beforeId})}——保持一个语句计划，省得优化器两套走。</p>
   */
  @Select("SELECT * FROM notify_message WHERE user_id = #{userId} AND deleted = 0 "
      + "AND (#{beforeId} IS NULL OR id < #{beforeId}) ORDER BY id DESC LIMIT #{limit}")
  List<NotifyMessage> pageBefore(@Param("userId") long userId, @Param("beforeId") Long beforeId,
      @Param("limit") int limit);

  /**
   * 标记已读：一条 UPDATE 改一批，且<b>只改这个人名下还没读的行</b>。
   *
   * <p>{@code user_id} 必须在 WHERE 里：通知 id 是可猜测的自增值，少了这一句，
   * 任何登录用户都能把别人的通知标成已读（越权改别人的数据，比读到的后果更难发现）。
   * {@code AND is_read = 0} 让影响行数等于「这次真的变已读的行数」，前端据此更新红点；
   * 不加就会把重复调用也算成更新，数字对不上还看不出问题。</p>
   */
  @Update("<script>UPDATE notify_message SET is_read = 1, read_at = #{now} "
      + "WHERE user_id = #{userId} AND is_read = 0 AND deleted = 0 AND id IN "
      + "<foreach collection='ids' item='it' open='(' separator=',' close=')'>#{it}</foreach>"
      + "</script>")
  int markRead(@Param("userId") long userId, @Param("ids") List<Long> ids,
      @Param("now") LocalDateTime now);

  /** 一键已读：只动未读，已读行的 read_at 不能被刷新（那是「第一次读」的时间）。 */
  @Update("UPDATE notify_message SET is_read = 1, read_at = #{now} "
      + "WHERE user_id = #{userId} AND is_read = 0 AND deleted = 0")
  int markAllRead(@Param("userId") long userId, @Param("now") LocalDateTime now);
}

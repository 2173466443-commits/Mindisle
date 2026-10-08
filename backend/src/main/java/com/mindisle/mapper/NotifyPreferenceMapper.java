package com.mindisle.mapper;

import com.mindisle.entity.NotifyPreference;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 通知偏好 Mapper（任务 T3.16 后半 · 需求 FR9.4）。
 *
 * <p>三条注解 SQL，没有一条能用 Wrapper 表达得更清楚，所以干脆不用 MyBatis-Plus 的通用 CRUD：
 * 这张表是复合主键，{@code BaseMapper} 的 {@code selectById/updateById} 会拼出
 * {@code WHERE id = ?} 这样一个根本不存在的列（详见 {@link NotifyPreference} 的类注释）。</p>
 */
@Mapper
public interface NotifyPreferenceMapper {

  /**
   * 这个人改过的开关（只返回真有行的那些）。
   *
   * <p>主键前缀 {@code user_id} 就是访问路径，一次范围扫至多八行 —— 这就是不预插默认行、
   * 也不在 {@code user} 表加 JSON 列的回报：读侧永远只扫这个人的几行。</p>
   */
  @Select("SELECT user_id, type, enabled, updated_at FROM notify_preference WHERE user_id = #{userId}")
  List<NotifyPreference> listByUser(@Param("userId") long userId);

  /**
   * 单个开关的点查（写在通知的热路径上）。
   *
   * <p>返回 {@code Integer} 而不是 {@code boolean}：<b>null 表示库里没有这一行</b>，
   * 而「没有这一行」的语义是「没改过，按默认接收」，它和 0 与 1 都不是同一个东西。
   * 把 null 压成 true 的活儿留给服务层，SQL 层不替业务下结论。</p>
   */
  @Select("SELECT enabled FROM notify_preference WHERE user_id = #{userId} AND type = #{type}")
  Integer enabledOf(@Param("userId") long userId, @Param("type") String type);

  /**
   * 保存一个开关：没有就插，有就改。
   *
   * <p>{@code ON DUPLICATE KEY UPDATE} 撞的是复合主键 (user_id, type)，所以「同一个人在同一类上
   * 点两次」在结构上不可能出第二行 —— 这是这张表唯一的幂等来源。</p>
   *
   * <p>不写 {@code updated_at}：DDL 带 {@code ON UPDATE CURRENT_TIMESTAMP(3)}，MySQL 只在行
   * 真的发生值变更时才刷新它。于是这一列说的是「最后一次改动了开关」，
   * 而不是「最后一次点了保存」—— 后者会把两次同样的提交也算成改动。</p>
   */
  @Insert("INSERT INTO notify_preference (user_id, type, enabled) VALUES (#{userId}, #{type}, #{enabled}) "
      + "ON DUPLICATE KEY UPDATE enabled = #{enabled}")
  int upsert(@Param("userId") long userId, @Param("type") String type, @Param("enabled") int enabled);
}

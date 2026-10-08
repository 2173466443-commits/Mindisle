package com.mindisle.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.RecRunLog;

/**
 * 推荐重算台账 Mapper（sql/19 第 36 表）。
 *
 * <p>只有读侧需要裸 SQL：写侧一条 {@code BaseMapper#insert} 就够（这张表没有幂等键——
 * 一次尝试就是一行，重复触发本来就该留下重复的行，那是事实不是脏数据）。</p>
 */
@Mapper
public interface RecRunLogMapper extends BaseMapper<RecRunLog> {

  /**
   * 最近 N 轮（id 倒序 = 时间倒序，主键自增保证两者一致）。
   *
   * <p>{@code limit} 由服务层夹过再进来，SQL 不做二次夹：两处夹会出现「页面看到 50 行、
   * 接口文档写着 200」这种对不上的口径。</p>
   */
  @Select("SELECT * FROM rec_run_log ORDER BY id DESC LIMIT #{limit}")
  List<RecRunLog> recent(@Param("limit") int limit);

  /**
   * 各结局各多少轮（A9 台账卡片的三个数）。
   *
   * <p>返回三行 status/cnt 而不是拼一条八个标量子查询的宽 SQL：这张表行数会随重算轮数线性涨，
   * {@code GROUP BY status} 走 idx_run_status 覆盖索引，一条语句就把四个数各扫一遍是更差的做法。</p>
   */
  @Select("SELECT status, COUNT(*) AS cnt FROM rec_run_log GROUP BY status")
  List<java.util.Map<String, Object>> countByStatus();
}

package com.mindisle.mapper;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.AiCallLog;

/**
 * AI 用量流水 Mapper（任务 T4.13 预算与熔断的数据口）。
 *
 * <p><b>为什么直接写 SQL 而不用 Wrapper</b>：这三条都是 {@code SUM(...)} 聚合，
 * MP 的 LambdaQueryWrapper 表达「按当天求和并且把 NULL 折成 0」要写 selectMaps + 手工取键，
 * 比一条 @Select 更难读，还容易在 NULL 上翻车（{@code COALESCE} 是关键的一个词，
 * 少写它，「今天还没调用过」会返回 null，然后在拆箱成 long 时抛 NPE ——
 * 一个纯查询路径上的 NPE 会把整个对话接口打成 500）。</p>
 */
@Mapper
public interface AiCallLogMapper extends BaseMapper<AiCallLog> {

  /** 某用户从 fromTs 起的 token 总用量（FR2.7 的「每日人均 token 上限」判据）。 */
  @Select("SELECT COALESCE(SUM(tokens_in + tokens_out), 0) FROM ai_call_log "
      + "WHERE user_id = #{userId} AND created_at >= #{fromTs}")
  long sumTokensSince(@Param("userId") long userId, @Param("fromTs") LocalDateTime fromTs);

  /** 全站从 fromTs 起的成本（分），全局预算熔断判据（NFR12）。 */
  @Select("SELECT COALESCE(SUM(cost_cent), 0) FROM ai_call_log WHERE created_at >= #{fromTs}")
  long sumCostCentSince(@Param("fromTs") LocalDateTime fromTs);

  /**
   * 从 fromTs 起连续失败的调用次数（按 id 倒序数到第一条成功为止）。
   *
   * <p>这里问的是「最近有多少次连续失败」，而不是「今天失败了几次」——
   * 后者会把已经恢复的服务继续算成熔断状态。实现思路：取最近 20 次的成败序列，
   * 从头部数连续的 0。用一条 SQL 数「第一条成功之前的失败数」要写窗口函数，
   * 可读性反而更差，所以这里只把最近 20 次取回来，由 Java 数。</p>
   */
  @Select("SELECT success FROM ai_call_log WHERE created_at >= #{fromTs} "
      + "AND scene IN ('chat','emotion','risk') ORDER BY id DESC LIMIT 20")
  java.util.List<Integer> listRecentSuccessFlags(@Param("fromTs") LocalDateTime fromTs);

  /** 今日（本地时区零点起）成功调用的平均首字延迟，给 /api/system/info 与论文 N2 用。 */
  @Select("SELECT COALESCE(ROUND(AVG(first_token_ms)), 0) FROM ai_call_log "
      + "WHERE created_at >= #{fromTs} AND success = 1 AND first_token_ms > 0")
  long avgFirstTokenMsSince(@Param("fromTs") LocalDateTime fromTs);
}

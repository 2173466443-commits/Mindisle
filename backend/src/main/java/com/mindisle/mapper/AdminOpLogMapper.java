package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.admin.dto.StatusCountRow;
import com.mindisle.entity.AdminOpLog;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 管理端操作审计 Mapper（任务 T6.6 · A9 · 需求 A9「所有管理端操作可审计」）。
 *
 * <p>与 {@link AuditRecordMapper} 同理，只增不改，本类没有 {@code @Update}。
 * 查询侧只有 A9 一个入口，因此分页参数走页码（后台要看总数，手册 §5.5 的口径）。</p>
 */
@Mapper
public interface AdminOpLogMapper extends BaseMapper<AdminOpLog> {


  /**
   * 带 action、result 与操作人过滤的那一页（<b>只有这条走 @Select</b>）。
   *
   * <p>{@code result} 是第 65 轮补上的第四个筛选位：A6 用户管理里 ADMIN 试探解匿会留下一行
   * {@code DENIED}，而那一行埋在一百多条 SUCCESS 里翻不到——深链 {@code /logs?result=DENIED}
   * 必须由服务端过滤，否则「越权必留痕」这条演示要在界面上靠肉眼翻页去找，等于没演示。</p>
   *
   * <p>为什么不全部塞进 Wrapper 的 {@code .eq(cond, ...)}：{@code action} 的合法值来自
   * {@link AdminOpLog} 常量，写错值时 Wrapper 会安静地返回空列表，
   * 而 A9 的「今天没有任何解匿操作」和「筛选条件写错了」在界面上是同一张空表 ——
   * 审计页面出现这种歧义是不可接受的，所以这里用显式 SQL，服务层再挡一次白名单。</p>
   */
  @Select("<script>SELECT * FROM admin_op_log WHERE deleted = 0 "
      + "<if test=\"operatorId != null\"> AND operator_id = #{operatorId} </if>"
      + "<if test=\"action != null and action != ''\"> AND action = #{action} </if>"
      + "<if test=\"result != null and result != ''\"> AND result = #{result} </if>"
      + "<if test=\"from != null\"> AND created_at &gt;= #{from} </if>"
      + "<if test=\"to != null\"> AND created_at &lt;= #{to} </if>"
      + "ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<AdminOpLog> pageFiltered(@Param("operatorId") Long operatorId,
      @Param("action") String action, @Param("result") String result,
      @Param("from") LocalDateTime from,
      @Param("to") LocalDateTime to, @Param("offset") long offset, @Param("size") int size);

  /** 与 {@link #pageFiltered} 同一套 WHERE。 */
  @Select("<script>SELECT COUNT(*) FROM admin_op_log WHERE deleted = 0 "
      + "<if test=\"operatorId != null\"> AND operator_id = #{operatorId} </if>"
      + "<if test=\"action != null and action != ''\"> AND action = #{action} </if>"
      + "<if test=\"result != null and result != ''\"> AND result = #{result} </if>"
      + "<if test=\"from != null\"> AND created_at &gt;= #{from} </if>"
      + "<if test=\"to != null\"> AND created_at &lt;= #{to} </if></script>")
  long countFiltered(@Param("operatorId") Long operatorId, @Param("action") String action,
      @Param("result") String result, @Param("from") LocalDateTime from,
      @Param("to") LocalDateTime to);

  /** A9 顶部的「按操作类型分布」，一次 GROUP BY 出全部，前端不再发 N 个请求。 */
  @Select("SELECT action AS status, COUNT(*) AS cnt FROM admin_op_log "
      + "WHERE created_at >= #{from} AND deleted = 0 GROUP BY action ORDER BY cnt DESC")
  List<StatusCountRow> countGroupByAction(@Param("from") LocalDateTime from);

  /**
   * 解匿次数（FR1.4 的红线指标：答辩时要能说「全站一共解匿过 N 次，每一次都有理由」）。
   *
   * <p>单独一条而不是让前端从 {@link #countGroupByAction} 里挑：这条数会被写进
   * §15 的追溯表，SQL 语义要能被逐字引用。</p>
   */
  @Select("SELECT COUNT(*) FROM admin_op_log WHERE action = 'REVEAL_ANONYMOUS' "
      + "AND result = 'SUCCESS' AND deleted = 0")
  long countReveals();
}
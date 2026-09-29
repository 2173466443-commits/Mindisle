package com.mindisle.mapper;

import com.mindisle.admin.dto.DashboardStatsRow;
import com.mindisle.admin.dto.EmotionDailyRow;
import com.mindisle.admin.dto.GradeRow;
import com.mindisle.admin.dto.HourValenceRow;
import com.mindisle.admin.dto.HotTopicRow;
import com.mindisle.admin.dto.LabelCountRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 数据大屏聚合 Mapper（任务 T6.7 · 手册 §9.3 六图 · 需求 FR8.2 NFR11）。
 *
 * <p><b>本类只写聚合，不写业务判断</b>：所有「算不算活跃、算不算闭环」的口径
 * 都留在 SQL 里一次定死，服务层只做装配。理由与 {@code EmotionRecordMapper} 一致——
 * 把 GROUP BY 搬到 Java 里等于把「一次查询」拆成「先取全量再自己数」，
 * 库里现在有两千多条情绪记录，将来是十几万条，那种拆法迟早要回炉。</p>
 *
 * <p><b>别名一律 snake_case</b>（{@code AS active_cnt}），靠 application.yml 里的
 * {@code map-underscore-to-camel-case} 映射进 {@code @Data} 行对象；
 * 写 camelCase 别名在本仓库没有先例，也不被任何一处真库验证过，别在这里发明。</p>
 *
 * <p>大屏的读放大是有意的取舍：五个接口、每个一到两条 SQL，5 秒轮询一次，
 * 单实例本机 MySQL 完全扛得住（Gate6 的 D9 要看的是「数字在动」，不是吞吐）。
 * 真要压到阶段 8 的 50 并发里再考虑缓存那层。</p>
 */
@Mapper
public interface DashboardMapper {

  /**
   * 图表 1「核心指标卡」：十二个数、同一条 SQL、同一刻快照。
   *
   * <p>类注释里那句「必须来自同一条 SQL」是这条语句存在的全部理由。
   * {@code COALESCE} 用在 SUM 上：{@code ai_call_log} 今天一条都没有时 SUM 返回 NULL，
   * 装箱字段会写成 null，前端就显示「-」，而正确的说法是「今日 0 分」。</p>
   *
   * @param from 今日 00:00（由服务层给，方便单测注入固定时刻）
   * @param now  当前时刻，SLA 超时判据用它，与 from 可以不同
   */
  @Select("SELECT "
      + " (SELECT COUNT(DISTINCT user_id) FROM user_action "
      + "   WHERE deleted = 0 AND created_at >= #{from}) AS dau, "
      + " (SELECT COUNT(*) FROM `user` "
      + "   WHERE deleted = 0 AND created_at >= #{from}) AS new_user_cnt, "
      + " (SELECT COUNT(*) FROM chat_message "
      + "   WHERE deleted = 0 AND role = 'assistant' AND created_at >= #{from}) AS chat_round_cnt, "
      + " (SELECT COUNT(*) FROM post "
      + "   WHERE deleted = 0 AND status <> 'DRAFT' AND created_at >= #{from}) AS post_cnt, "
      + " (SELECT COUNT(*) FROM alert_ticket "
      + "   WHERE deleted = 0 AND created_at >= #{from}) AS crisis_cnt, "
      + " (SELECT COALESCE(SUM(cost_cent), 0) FROM ai_call_log "
      + "   WHERE created_at >= #{from}) AS cost_cent, "
      + " (SELECT COUNT(*) FROM audit_task "
      + "   WHERE deleted = 0 AND status = 'PENDING') AS audit_pending_cnt, "
      + " (SELECT COUNT(*) FROM alert_ticket "
      + "   WHERE deleted = 0 AND status = 'pending') AS ticket_pending_cnt, "
      + " (SELECT COUNT(*) FROM content_report "
      + "   WHERE deleted = 0 AND status = 'PENDING') AS report_pending_cnt, "
      + " (SELECT COUNT(*) FROM alert_ticket "
      + "   WHERE deleted = 0 AND sla_at IS NOT NULL AND sla_at < #{now} "
      + "     AND status IN ('pending','claimed','doing')) AS overdue_cnt, "
      + " (SELECT COALESCE(SUM(tokens_in + tokens_out), 0) FROM ai_call_log "
      + "   WHERE created_at >= #{from}) AS token_cnt, "
      + " (SELECT COALESCE(ROUND(AVG(valence), 2), 0) FROM emotion_record "
      + "   WHERE deleted = 0 AND record_date = #{today}) AS avg_valence")
  DashboardStatsRow stats(@Param("from") LocalDateTime from, @Param("today") LocalDate today,
      @Param("now") LocalDateTime now);

  /**
   * 图表 2「近 14 日活跃与情绪指数」。
   *
   * <p>活跃口径是「当天有情绪记录的人」而不是「当天登录的人」：
   * 这张图讲的是情绪，分子分母都得来自同一张表，否则折线会出现
   * 「DAU 涨了、情绪指数那根线却缺一天」。{@code record_date} 是生成列，
   * 已经建好索引（{@code idx_user_date}），按它 GROUP BY 不用回表算日期。</p>
   */
  @Select("SELECT DATE_FORMAT(record_date, '%m-%d') AS day, "
      + "COUNT(DISTINCT user_id) AS active_cnt, "
      + "ROUND(AVG(valence), 2) AS avg_valence, "
      + "COUNT(*) AS record_cnt "
      + "FROM emotion_record WHERE deleted = 0 "
      + "AND record_date BETWEEN #{fromDate} AND #{toDate} "
      + "GROUP BY record_date ORDER BY record_date")
  List<EmotionDailyRow> emotionDaily(@Param("fromDate") LocalDate fromDate,
      @Param("toDate") LocalDate toDate);

  /** 图表 3「情绪分布」（8 类标签，含 neutral）。窗口取近 30 日。 */
  @Select("SELECT label, COUNT(*) AS cnt FROM emotion_record "
      + "WHERE deleted = 0 AND label IS NOT NULL AND record_date >= #{fromDate} "
      + "GROUP BY label ORDER BY cnt DESC")
  List<LabelCountRow> emotionLabels(@Param("fromDate") LocalDate fromDate);

  /**
   * 图表 4「高频话题词云」。
   *
   * <p>只取 {@code audit_status = 'APPROVED'}，理由见 {@code HotTopicRow} 类注释。
   * {@code LIMIT} 由服务层夹在 10–100 之间，不让前端把整张话题表拖上大屏。</p>
   *
   * <p><b>帖数是 join 重算的，不读 {@code topic.post_cnt}</b>：那一列是冗余计数器，
   * 真库现量失眠夜 {@code post_cnt=110} 而按 {@code post_topic} join 出来的已发布帖是 128，
   * 差 18 条。口径与 {@code PostMapper.refreshCommentCnt} 保持一致——从真相表推导，
   * 漂移在结构上不可能发生。</p>
   *
   * <p><b>{@code hot_score} 只进 ORDER BY 的最后一级，绝不当字号</b>：手册 §3.8 ③ 记的
   * 「hot_score 定时重算仍欠」是真的，现查 139 行话题该列<b>全部是 0.0000</b>。
   * 拿它当权重只会得到一张等大的云。字号缩放由前端按 {@code 10 + log(1 + post_cnt)} 算，
   * 那是视觉刻度；后端不许为了「图好看」造 {@code value = max(cnt,1)} 这种假数。</p>
   */
  @Select("SELECT t.id AS id, t.name AS name, COUNT(DISTINCT p.id) AS post_cnt, "
      + "t.hot_score AS hot_score "
      + "FROM topic t "
      + "LEFT JOIN post_topic pt ON pt.topic_id = t.id "
      + "LEFT JOIN post p ON p.id = pt.post_id AND p.deleted = 0 "
      + "  AND p.status = 'PUBLISHED' "
      + "WHERE t.deleted = 0 AND t.audit_status = 'APPROVED' "
      + "GROUP BY t.id, t.name, t.hot_score "
      + "ORDER BY post_cnt DESC, t.hot_score DESC, t.id ASC LIMIT #{limit}")
  List<HotTopicRow> hotTopics(@Param("limit") int limit);

  /**
   * 图表 5「年级聚合柱状」（FR3.6：只聚合、无个体）。
   *
   * <p>必须 JOIN {@code user} 再过滤一次 {@code u.deleted = 0}：
   * {@code user_profile} 是每行注册时建的，注销中的账号（status=DELETED，软删）
   * 资料还在，不 join 就会把已经离开的人算进「本校大三学生数」里。</p>
   */
  @Select("SELECT p.grade AS grade, COUNT(*) AS cnt "
      + "FROM user_profile p JOIN `user` u ON u.id = p.user_id "
      + "WHERE p.deleted = 0 AND u.deleted = 0 AND p.grade IS NOT NULL "
      + "GROUP BY p.grade ORDER BY cnt DESC")
  List<GradeRow> gradeBoard();

  /** 图表 6「24 小时情绪热力」。缺行的小时由前端补 0，见 {@code HourValenceRow}。 */
  @Select("SELECT HOUR(created_at) AS `hour`, COUNT(*) AS cnt, "
      + "ROUND(AVG(valence), 2) AS avg_valence "
      + "FROM emotion_record WHERE deleted = 0 "
      + "AND record_date BETWEEN #{fromDate} AND #{toDate} "
      + "GROUP BY HOUR(created_at) ORDER BY `hour`")
  List<HourValenceRow> hourHeatmap(@Param("fromDate") LocalDate fromDate,
      @Param("toDate") LocalDate toDate);

  /**
   * A9「AI 用量与费用」按日 × 场景 × 模型聚合（需求 NFR11、FR8.2 的对账口径）。
   *
   * <p>返回 {@code List<Map>} 而不是新建 DTO：这张表要给两处用（页面表格 + CSV 导出），
   * 而 CSV 的表头本来就该由列名生成。列名在这里就是接口契约，多一个 DTO 只是多一层
   * 会漂移的翻译。注意 {@code ai_call_log} 没有 deleted 列（日志表只增不删），
   * 所以本条 SQL 里出现 deleted 条件反而是错的。</p>
   */
  @Select("SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS day, scene, model, "
      + "COUNT(*) AS call_cnt, "
      + "COALESCE(SUM(tokens_in + tokens_out), 0) AS tokens, "
      + "COALESCE(SUM(cost_cent), 0) AS cost_cent, "
      + "COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS fail_cnt, "
      + "COALESCE(ROUND(AVG(latency_ms)), 0) AS avg_latency_ms "
      + "FROM ai_call_log WHERE created_at >= #{from} "
      + "GROUP BY day, scene, model ORDER BY day DESC, cost_cent DESC")
  List<Map<String, Object>> aiUsageRows(@Param("from") LocalDateTime from);

  /**
   * A5/D9「工单闭环导出」的原始行（CSV 由 {@code CsvExportService} 拼，判据在页面层）。
   *
   * <p>一次导出上限 5 000 行：Gate6 只要「导出一次 CSV」，但真正跑起来时
   * 无限导出的第一个受害者是管理员的浏览器，第二个是这一条 SQL 的排序缓冲。</p>
   */
  @Select("SELECT id, level, user_id, source_type, source_id, risk_score, trigger_words, "
      + "status, assignee_id, claim_at, sla_at, close_at, handle_note, followup_at, created_at "
      + "FROM alert_ticket WHERE deleted = 0 AND created_at BETWEEN #{from} AND #{to} "
      + "ORDER BY id DESC LIMIT 5000")
  List<Map<String, Object>> exportTickets(@Param("from") LocalDateTime from,
      @Param("to") LocalDateTime to);
}
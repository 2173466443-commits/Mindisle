package com.mindisle.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mindisle.emotion.dto.EmotionGroupRow;
import com.mindisle.entity.EmotionRecord;

/**
 * 情绪记录 Mapper（任务 T4.9 写入 / T4.10 聚合读 / T4.3 情绪上下文）。
 *
 * <p>聚合口径见 {@link EmotionGroupRow} 的类注释：一份分组结果喂三张图。
 * BR12 的可信阈值在这里以 {@code #{confidentMin}} 参数传入，不在 SQL 里写死 ——
 * 它是需求文档里可调整的口径，写死之后改一个数要发一次版。</p>
 */
@Mapper
public interface EmotionRecordMapper extends BaseMapper<EmotionRecord> {

  /**
   * 按天 × 标签聚合（趋势折线、分布饼、日历热力共用）。
   *
   * <p>{@code MAX(text_snippet)} 不是「取最长文本」的意思，只是 GROUP BY 之下
   * 必须给非聚合列一个函数；取样本词面用它是免费的。库里存的是脱敏后的片段，
   * 所以这一列可以直接进接口响应（词云/触发词回看），不必再过一次敏感词。</p>
   */
  @Select("SELECT record_date, label, COUNT(*) AS cnt, "
      + "SUM(CASE WHEN confidence >= #{confidentMin} THEN 1 ELSE 0 END) AS confident_cnt, "
      + "SUM(CASE WHEN confidence <  #{confidentMin} THEN 1 ELSE 0 END) AS uncertain_cnt, "
      + "AVG(CASE WHEN confidence >= #{confidentMin} THEN intensity END) AS avg_intensity, "
      + "SUM(CASE WHEN source = 'checkin' THEN 1 ELSE 0 END) AS checkin_cnt, "
      + "MAX(text_snippet) AS sample_snippet "
      + "FROM emotion_record "
      + "WHERE user_id = #{userId} AND record_date BETWEEN #{fromDate} AND #{toDate} AND deleted = 0 "
      + "GROUP BY record_date, label ORDER BY record_date ASC, cnt DESC")
  List<EmotionGroupRow> groupByDayAndLabel(@Param("userId") long userId,
      @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate,
      @Param("confidentMin") BigDecimal confidentMin);

  /** 打卡幂等查重的键：同一用户同一天、来源 checkin 的那一条（任务 T4.9「每日一次」）。 */
  @Select("SELECT * FROM emotion_record WHERE user_id = #{userId} AND source = 'checkin' "
      + "AND record_date = #{date} AND deleted = 0 ORDER BY id DESC LIMIT 1")
  EmotionRecord findCheckinOn(@Param("userId") long userId, @Param("date") LocalDate date);

  /** 最近 N 条原始记录（U3 的打卡列表），倒序。 */
  default List<EmotionRecord> listRecent(long userId, int limit) {
    return selectList(new LambdaQueryWrapper<EmotionRecord>()
        .eq(EmotionRecord::getUserId, userId)
        .orderByDesc(EmotionRecord::getId)
        .last("limit " + Math.max(1, Math.min(limit, 365))));
  }

  /** 分页导出用（T4.21 隐私导出的情绪域数据源）。 */
  default Page<EmotionRecord> pageOf(long userId, int pageNo, int pageSize) {
    return selectPage(new Page<>(Math.max(1, pageNo), Math.max(1, Math.min(pageSize, 500))),
        new LambdaQueryWrapper<EmotionRecord>()
            .eq(EmotionRecord::getUserId, userId)
            .orderByAsc(EmotionRecord::getId));
  }

  /** 某时间窗内的记录条数，周报「本周 N 条记录」与「数据积累中」（<3 天）判据都读它。 */
  @Select("SELECT COUNT(*) FROM emotion_record WHERE user_id = #{userId} "
      + "AND record_date BETWEEN #{fromDate} AND #{toDate} AND deleted = 0")
  int countBetween(@Param("userId") long userId,
      @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

  /** 有记录的天数（去重），FR3.4「数据点 < 3 天显示数据积累中」的判据。 */
  @Select("SELECT COUNT(DISTINCT record_date) FROM emotion_record WHERE user_id = #{userId} "
      + "AND record_date BETWEEN #{fromDate} AND #{toDate} AND deleted = 0")
  int countDistinctDaysBetween(@Param("userId") long userId,
      @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

  /**
   * 打卡流水（U3 的「近 N 天打卡列表」）。
   *
   * <p>只查 {@code source='checkin'}：被动识别的那些行也是记录，但把它们混进「我的打卡」
   * 列表，用户会以为系统替他打了卡——那是 FR3.1「主动」这两个字的定义被改掉。</p>
   */
  @Select("SELECT * FROM emotion_record WHERE user_id = #{userId} AND source = 'checkin' "
      + "AND record_date BETWEEN #{fromDate} AND #{toDate} AND deleted = 0 "
      + "ORDER BY record_date DESC, id DESC")
  List<EmotionRecord> listCheckinsBetween(@Param("userId") long userId,
      @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

  /**
   * 触发文本片段（任务 T4.10 的词云数据源）。
   *
   * <p>一次取满 500 条在 Java 侧分词计数，而不是在 SQL 里拆词：词面是用「、」拼起来的
   * （{@code DictEmotionEngine#hitWords}），拆它要的是字符串处理，不是数据库的字符串函数。
   * 放 SQL 里会得到一条没人敢改的长语句，而词云口径（最小词长、Top N）
   * 是本项目里最常调的那几个数之一。</p>
   *
   * <p>倒序取最近 500 条：词云要反映「最近被什么戳到」，而不是一整年前的样本。</p>
   */
  @Select("SELECT text_snippet FROM emotion_record WHERE user_id = #{userId} "
      + "AND record_date BETWEEN #{fromDate} AND #{toDate} AND deleted = 0 "
      + "AND text_snippet <> '' ORDER BY id DESC LIMIT 500")
  List<String> listSnippets(@Param("userId") long userId,
      @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

  /** 打卡天数（周报 checkin_days）。 */
  @Select("SELECT COUNT(DISTINCT record_date) FROM emotion_record WHERE user_id = #{userId} "
      + "AND source = 'checkin' AND record_date BETWEEN #{fromDate} AND #{toDate} AND deleted = 0")
  int countCheckinDays(@Param("userId") long userId,
      @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

  /**
   * 该用户<b>某天最后一次</b>主动打卡的心情效价（valence × intensity，−5..+5），没打卡返回 null。
   *
   * <p>消费方是任务 T3.10 的行为埋点（{@code UserActionRecorder.Store#moodValenceOf}）：
   * 需求 §8.2.2 的情绪感知项要「动作发生时的心情」，而 {@code user_action.mood_valence} 的口径
   * ——只认 checkin、乘 intensity 凑出五档量程、NULL 不等于中性——完整写在
   * {@link com.mindisle.entity.UserAction#moodValence} 的注释里，这里只留 SQL 与一句为什么。
   * 这条 SQL 放在本 Mapper 而不是 {@code UserActionMapper}，是因为它读的是 emotion_record：
   * 一个 Mapper 守一张表，否则「谁能动 emotion_record」这件事会多出第二个入口。</p>
   *
   * <p>取最近一条而不是平均：平均会把「早上很难受、下午缓过来」的人算成从未存在过的状态。</p>
   */
  @Select("SELECT valence * intensity FROM emotion_record "
      + "WHERE user_id = #{userId} AND source = 'checkin' AND record_date = #{day} "
      + "AND deleted = 0 ORDER BY id DESC LIMIT 1")
  Integer latestCheckinMood(@Param("userId") long userId, @Param("day") LocalDate day);


  /**
   * 周报定时任务的候选名单：这段窗口里<b>主动打卡过</b>的用户 id（任务 T4.20）。
   *
   * <p><b>只认 {@code source = 'checkin'}</b>：被动识别的那些行也是情绪记录，但把「系统替他识别过」
   * 说成「他有打卡」会让一个从没打开过 App 的用户被 LLM 批次算进名单 —— 白花钱，而且他收到一封
   * 自己没申请过的周报。口径与 {@link #listCheckinsBetween} 一致（FR3.1「主动」这两个字）。
   * 放在本 Mapper 也是因为「一个 Mapper 守一张表」：{@code WeeklyReportJob} 不直接碰 SQL。
   *
   * <p><b>{@code LIMIT #{limit}} 走占位符而不是拼字符串</b>：这个数由上层配置与请求参数决定，
   * 拼串等于把批次上限变成一条可注入的 SQL 片段。MyBatis 对 LIMIT 的占位符在 MySQL 下走预编译，
   * 不存在「只能拼」的限制。
   *
   * <p><b>{@code ORDER BY user_id ASC} 不是为了让界面好看，是批次判据的前提</b>：
   * {@code WeeklyReportJob} 会向这里多要一行（{@code limit + 1}）来判断有没有人被批次上限挤出去。
   * 没有全序的话，MySQL 返回的顺序不保证稳定 —— 「这周被挤掉的人下周还在末尾」这件事就不成立，
   * 谁被挤出去变成不确定的，而 {@code truncated} 这个字段会失去解释。
   */
  @Select("SELECT DISTINCT user_id FROM emotion_record WHERE source = 'checkin' "
      + "AND record_date BETWEEN #{fromDate} AND #{toDate} AND deleted = 0 "
      + "ORDER BY user_id ASC LIMIT #{limit}")
  List<Long> listCheckinUserIds(@Param("fromDate") LocalDate fromDate,
      @Param("toDate") LocalDate toDate, @Param("limit") int limit);

}

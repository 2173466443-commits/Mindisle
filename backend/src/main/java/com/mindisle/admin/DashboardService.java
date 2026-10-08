package com.mindisle.admin;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.mindisle.admin.dto.DashboardStatsRow;
import com.mindisle.admin.dto.EmotionDailyRow;
import com.mindisle.admin.dto.DayHourValenceRow;
import com.mindisle.admin.dto.GradeRow;
import com.mindisle.admin.dto.HotTopicRow;
import com.mindisle.admin.dto.HourValenceRow;
import com.mindisle.admin.dto.LabelCountRow;
import com.mindisle.mapper.DashboardMapper;

/**
 * 运营看板与 A2 数据大屏的装配层（任务 T6.3 · 手册 §9.3 · 需求 FR5 FR8.2）。
 *
 * <p>本类几乎不做计算，它做的事是<b>把时间窗口的口径钉死在一处</b>：DashboardMapper 的每条 SQL
 * 都要调用方把 from/today/fromDate/toDate 传进去，而不是在 SQL 里各写各的 CURDATE()。
 * 两个理由：① 单测能钉住「现在几点」，六张图的数字才可能被断言；② 若 SQL 里各写各的当天，
 * 顶部指标卡写 UTC 的今天、折线图写本地时区的今天，大屏上两张图会对不上同一个日期，
 * 而这种错位在现场演示时看起来像数据 bug，实际是口径漂移。</p>
 *
 * <p>手册 §9.3 的六个接口名是原文硬约束（stats/emotion-board/hot-topics/grade-board/hour-heatmap
 * 再加 ai-usage 当第六图），不改名、不加别名。</p>
 */
@Service
public class DashboardService {

  /** 默认窗口 7 天：情绪折线一周七个点，正好够看出「考试周」这类周期性。 */
  static final int DEFAULT_DAYS = 7;
  static final int MAX_DAYS = 90;

  /** 热门话题夹在 10–100：少于 10 个词云不成形，多于 100 个前端要渲染上百个 label，5 秒轮询会掉帧。 */
  static final int HOT_LIMIT_MIN = 10;
  static final int HOT_LIMIT_MAX = 100;
  static final int HOT_LIMIT_DEFAULT = 40;

  private final DashboardMapper dashboardMapper;

  public DashboardService(DashboardMapper dashboardMapper) {
    this.dashboardMapper = dashboardMapper;
  }

  /**
   * 顶部指标卡（FR8.2）。
   *
   * <p><b>费用为 0 不等于没用过</b>：真库 {@code SUM(cost_cent)} 现量是 0（自部署模型按 0 计价），
   * 而 token 现量是 103917。所以 {@link DashboardStatsRow} 里两个都给，前端优先显示 token、
   * 成本为 0 时明确标注「本部署未计费的模型按 token 计量」，不能画一张空柱状图糊弄过去。</p>
   */
  public DashboardStatsRow stats(LocalDateTime now) {
    LocalDateTime today = now.toLocalDate().atStartOfDay();
    return dashboardMapper.stats(today.minusDays(DEFAULT_DAYS - 1L), now.toLocalDate(), now);
  }

  /** 情绪板块三联：日活/均价度折线 + 标签分布 + 时段热力，前端一次请求画三张图。 */
  public record EmotionBoard(List<EmotionDailyRow> daily, List<LabelCountRow> labels,
                             List<HourValenceRow> heatmap, int days) {
  }

  public EmotionBoard emotionBoard(int days, LocalDateTime now) {
    int span = clampDays(days);
    LocalDate toDate = now.toLocalDate();
    LocalDate fromDate = toDate.minusDays(span - 1L);
    return new EmotionBoard(dashboardMapper.emotionDaily(fromDate, toDate),
        dashboardMapper.emotionLabels(fromDate),
        dashboardMapper.hourHeatmap(fromDate, toDate), span);
  }

  /**
   * 热门话题（词云数据源）。
   *
   * <p>{@code hot_score} 在真库现量全是 0.0000（T7.x 的定时重算还没做），所以
   * <b>字号只能取 post_cnt</b>——DashboardMapper.hotTopics 的 javadoc 已经把这条写成硬约束，
   * 前端不许拿 hotScore 当字号，否则整张词云所有词一样大。</p>
   */
  public List<HotTopicRow> hotTopics(int limit) {
    return dashboardMapper.hotTopics(clampHotLimit(limit));
  }

  /** 年级分布（需求 §18.1 的分层运营依据：大一/研三是两条不同的干预链路）。 */
  public List<GradeRow> gradeBoard() {
    return dashboardMapper.gradeBoard();
  }

  public List<HourValenceRow> hourHeatmap(int days, LocalDateTime now) {
    LocalDate toDate = now.toLocalDate();
    return dashboardMapper.hourHeatmap(toDate.minusDays(clampDays(days) - 1L), toDate);
  }

  /**
   * 图表 6 的二维版：日 × 24 小时热力（任务 U16-④）。
   *
   * <p>返回里带上 fromDate/toDate/days 三份口径，而不是只给一格对的数组：大屏要把缺行的
   * 格子补成 0，补之前必须知道「这个窗口到底是哪几天」，否则前端只能拿今天往前倒推，
   * 一旦后端和前端不在同一天跨午夜（运维在 23:59:58 刷新）就会多画或少画一行。
   * 窗口口径由本类一处定死，和 hourHeatmap 用同一个 clampDays，两张图永远同窗。</p>
   */
  public DayHourBoard dayHourHeatmap(int days, LocalDateTime now) {
    int span = clampDays(days);
    LocalDate toDate = now.toLocalDate();
    LocalDate fromDate = toDate.minusDays(span - 1L);
    return new DayHourBoard(span, fromDate, toDate, dashboardMapper.dayHourHeatmap(fromDate, toDate));
  }

  /**
   * 二维热力的响应体。
   *
   * <p>{@code cells} 是「有记录的格子」稀疏数组，最多 span×24 项；前端按 day×hour 建索引补 0。
   * 后端不铺满空壳行：90 天窗口就是 2160 行，其中大部分本来就没记录，
   * 铺满只会让 5 秒轮询的响应体涨一个数量级。</p>
   */
  public record DayHourBoard(int days, LocalDate fromDate, LocalDate toDate,
                             List<DayHourValenceRow> cells) {
  }

  /** 情绪标签分布单独给一份（A2 的雷达图与看板共用）。 */
  public List<LabelCountRow> emotionLabels(int days, LocalDateTime now) {
    return dashboardMapper.emotionLabels(now.toLocalDate().minusDays(clampDays(days) - 1L));
  }

  /**
   * AI 用量与成本（FR8.2 的「费用＝ai_call_log 汇总」这条判据的唯一取数口）。
   *
   * <p>返回的是 Mapper 里的原生 Map，<b>键名是 snake_case</b>（day/scene/model/call_cnt/tokens/
   * cost_cent/fail_cnt/avg_latency_ms）。这是刻意不做驼峰转换：字段与 SQL 一一对应，
   * 答辩时被追问「这个数哪来的」可以直接把 SQL 摊开。</p>
   */
  public List<Map<String, Object>> aiUsage(int days, LocalDateTime now) {
    return dashboardMapper.aiUsageRows(now.toLocalDate().minusDays(clampDays(days) - 1L)
        .atStartOfDay());
  }

  /** 工单导出取数（T6.8 · CSV 的行来源，见 {@link CsvExportService}）。 */
  public List<Map<String, Object>> exportTickets(int days, LocalDateTime now) {
    LocalDate toDate = now.toLocalDate();
    return dashboardMapper.exportTickets(toDate.minusDays(clampDays(days) - 1L).atStartOfDay(),
        toDate.plusDays(1).atStartOfDay());
  }

  /** 窗口天数：0/负数按默认，上限 90 天（再长 ai_call_log 的分组基数会拖慢大屏 5 秒轮询）。 */
  static int clampDays(int days) {
    if (days <= 0) {
      return DEFAULT_DAYS;
    }
    return Math.min(days, MAX_DAYS);
  }

  static int clampHotLimit(int limit) {
    if (limit <= 0) {
      return HOT_LIMIT_DEFAULT;
    }
    return Math.max(HOT_LIMIT_MIN, Math.min(limit, HOT_LIMIT_MAX));
  }
}
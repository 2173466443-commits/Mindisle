package com.mindisle.web;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.admin.DashboardService;
import com.mindisle.admin.DashboardService.DayHourBoard;
import com.mindisle.admin.DashboardService.EmotionBoard;
import com.mindisle.admin.dto.DashboardStatsRow;
import com.mindisle.admin.dto.GradeRow;
import com.mindisle.admin.dto.HotTopicRow;
import com.mindisle.admin.dto.HourValenceRow;
import com.mindisle.common.Result;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * A3 运营工作台 / A2 数据大屏的数据口（任务 T6.3 · 需求 FR5、FR8.2）。
 *
 * <p>六条查询全部只读，所以本类不写 admin_op_log：需求把留痕义务挂在「会改变事实的操作」上
 * （解匿、裁决、封禁、导出），看一次看板写一行日志，日志里全是噪声，
 * 真正要查的「谁在半夜把某条帖下架了」反而被埋掉。</p>
 *
 * <p>{@code days} 的上限由服务层夹（DashboardService.clampDays），不在这里再判一次：
 * 大屏每 5 秒轮询一次，参数校验放两层会出现「前端看到 400、日志里没有」的空档。</p>
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@Tag(name = "5 管理端-看板", description = "运营指标、情绪看板、热门话题、年级分布、时段热力、AI 用量")
public class AdminDashboardController {

  private final DashboardService dashboardService;

  public AdminDashboardController(DashboardService dashboardService) {
    this.dashboardService = dashboardService;
  }

  @GetMapping("/stats")
  @Operation(summary = "核心指标快照（用户/发帖/审核队列/危机工单/AI 费用八项）")
  public Result<DashboardStatsRow> stats(@AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    AdminSupport.requireAdmin(current);
    return Result.ok(dashboardService.stats(AdminSupport.now()));
  }

  @GetMapping("/emotion-board")
  @Operation(summary = "情绪看板（每日情绪均值 + 标签分布 + 时段热力，一次给全，大屏少发三个请求）")
  public Result<EmotionBoard> emotionBoard(
      @Parameter(description = "统计天数，1-365，默认 30") @RequestParam(name = "days", required = false) Integer days,
      @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(dashboardService.emotionBoard(days == null ? 30 : days, AdminSupport.now()));
  }

  @GetMapping("/hot-topics")
  @Operation(summary = "热门话题榜（按帖数，10-100 条）")
  public Result<List<HotTopicRow>> hotTopics(
      @Parameter(description = "取前 N 条，服务层夹在 10~100") @RequestParam(name = "limit", required = false) Integer limit,
      @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(dashboardService.hotTopics(limit == null ? 10 : limit));
  }

  @GetMapping("/grade-board")
  @Operation(summary = "年级分布（匿名聚合，小于阈值的年级合并为「其他」，防止反推出个体）")
  public Result<List<GradeRow>> gradeBoard(@AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(dashboardService.gradeBoard());
  }

  @GetMapping("/hour-heatmap")
  @Operation(summary = "时段情绪热力（7×24 网格，横轴小时、纵轴星期）")
  public Result<List<HourValenceRow>> hourHeatmap(
      @RequestParam(name = "days", required = false) Integer days, @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(dashboardService.hourHeatmap(days == null ? 30 : days, AdminSupport.now()));
  }

  /**
   * 日 × 24 小时二维情绪热力（U16-④）。
   *
   * <p>上面那条 /hour-heatmap 的 summary 原本写着「7×24 网格」，但它只按小时聚合，
   * 一直是<b>一维 24 格</b>——口径名不副实这条已经在这轮改掉：一维那条留着给旧大屏，
   * 真正要按星期几看节律的用这条。默认 7 天正好是「周一到周日」七行，
   * 参数上限仍由服务层 clampDays 夹到 90，不在这里重复校验。</p>
   */
  @GetMapping("/day-hour-heatmap")
  @Operation(summary = "日 × 24 小时情绪热力（二维稀疏格，前端按天补 0）")
  public Result<DayHourBoard> dayHourHeatmap(
      @Parameter(description = "统计天数，默认 7，服务层上限 90") @RequestParam(name = "days", required = false) Integer days,
      @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(dashboardService.dayHourHeatmap(days == null ? 7 : days, AdminSupport.now()));
  }

  @GetMapping("/emotion-labels")
  @Operation(summary = "情绪标签计数（词云数据源，FR5 六图之一）")
  public Result<List<com.mindisle.admin.dto.LabelCountRow>> emotionLabels(
      @RequestParam(name = "days", required = false) Integer days, @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(dashboardService.emotionLabels(days == null ? 30 : days, AdminSupport.now()));
  }

  @GetMapping("/ai-usage")
  @Operation(summary = "AI 用量与成本（按日 × 场景 × 模型聚合，FR8.2 的费用口径就是这张表）")
  public Result<List<Map<String, Object>>> aiUsage(
      @RequestParam(name = "days", required = false) Integer days, @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(dashboardService.aiUsage(days == null ? 30 : days, AdminSupport.now()));
  }
}
package com.mindisle.web;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.admin.AdminOpLogService;
import com.mindisle.admin.AdminOpLogService.Ctx;
import com.mindisle.admin.CsvExportService;
import com.mindisle.admin.DashboardService;
import com.mindisle.admin.dto.StatusCountRow;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.common.Result;
import com.mindisle.entity.AdminOpLog;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * A9 操作日志与报表导出（任务 T6.6 · 需求 FR8.4 留痕可查、§12 规范 5）。
 *
 * <p>日志查询本身不写日志（否则「查日志」这条动作会把日志表写满，把真正要查的东西埋了），
 * 但<b>导出必须写日志</b>：一次 CSV 导出等于把一批用户数据搬到平台之外，
 * 按 PIPL 的口径这是一次「数据处理活动」，谁导的、导了几天范围、多少行，都得留得下来。</p>
 *
 * <p>三个导出口径的表头都写死在代码里，不跟 {@code Map} 的迭代顺序走：
 * MyBatis 返回的是 HashMap，列序变了就拿不到「两次导出可比」这个性质。</p>
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "5 管理端-日志与导出", description = "操作日志检索、动作统计、解匿计数、CSV 导出")
public class AdminLogController {

  /** 导出行数上限：一次翻页最多取这么多个 50 行页（与 DashboardMapper.exportTickets 的 5000 对齐）。 */
  private static final int EXPORT_MAX_ROWS = 5000;
  private static final int EXPORT_PAGE_SIZE = PageQuery.MAX_SIZE;
  private static final int EXPORT_MAX_PAGES = EXPORT_MAX_ROWS / EXPORT_PAGE_SIZE;

  /** 工单导出的列顺序（与 exportTickets SQL 的别名逐字一致）。 */
  private static final List<String> TICKET_COLUMNS = List.of("id", "level", "user_id", "source_type",
      "source_id", "risk_score", "trigger_words", "status", "assignee_id", "claim_at", "sla_at",
      "close_at", "handle_note", "followup_at", "created_at");

  /** AI 用量导出的列顺序（与 aiUsageRows SQL 的别名逐字一致）。 */
  private static final List<String> AI_USAGE_COLUMNS = List.of("day", "scene", "model", "call_cnt",
      "tokens", "cost_cent", "fail_cnt", "avg_latency_ms");

  /** 操作日志导出的列顺序。 */
  private static final List<String> OP_LOG_COLUMNS = List.of("id", "created_at", "operator_id",
      "operator_role", "action", "result", "target", "target_id", "detail", "ip", "user_agent");

  private final AdminOpLogService opLogService;
  private final CsvExportService csvService;
  private final DashboardService dashboardService;

  public AdminLogController(AdminOpLogService opLogService, CsvExportService csvService,
      DashboardService dashboardService) {
    this.opLogService = opLogService;
    this.csvService = csvService;
    this.dashboardService = dashboardService;
  }

  @GetMapping("/logs")
  @Operation(summary = "操作日志分页（按操作人、动作码、时间区间过滤；A9 的「谁在什么时候干了什么」）")
  public Result<PageResult<AdminOpLog>> page(
      @Parameter(description = "admin_op_log.operator_id") @RequestParam(name = "operatorId", required = false) Long operatorId,
      @Parameter(description = "动作码，必须在白名单内，非法值直接 10001") @RequestParam(name = "action", required = false) String action,
      @RequestParam(name = "from", required = false) LocalDateTime from,
      @Parameter(description = "留痕结果 SUCCESS/FAIL/DENIED，A6 的越权 DENIED 深链用") @RequestParam(name = "result", required = false) String result,
      @RequestParam(name = "to", required = false) LocalDateTime to,
      PageQuery page, @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    return Result.ok(opLogService.page(operatorId, action, result, from, to, page));
  }

  @GetMapping("/logs/actions")
  @Operation(summary = "动作码分布（默认统计最近 30 天，报表里那张柱状图）")
  public Result<List<StatusCountRow>> actionStats(
      @RequestParam(name = "days", required = false) Integer days, @AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    int safeDays = days == null ? 30 : Math.max(1, Math.min(365, days));
    return Result.ok(opLogService.actionStats(LocalDateTime.now().minusDays(safeDays)));
  }

  @GetMapping("/logs/reveals")
  @Operation(summary = "解匿次数与最近十条解匿明细（FR8.4 当场报数：总数 + 可核对的行）")
  public Result<Map<String, Object>> reveals(@AuthenticationPrincipal AuthUser current) {
    AdminSupport.requireAdmin(current);
    PageQuery query = new PageQuery();
    query.setPage(1);
    query.setSize(10);
    PageResult<AdminOpLog> recent = opLogService.page(null, AdminOpLog.ACTION_REVEAL_ANONYMOUS, null,
        null, null, query);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("total", opLogService.revealCount());
    body.put("recent", recent.getList());
    return Result.ok(body);
  }

  // ------------------------------------------------------------ CSV 导出

  @GetMapping("/export/tickets")
  @Operation(summary = "导出危机工单 CSV（UTF-8 BOM + CRLF，防公式注入；本次导出写一条 EXPORT_CSV 日志）")
  public ResponseEntity<byte[]> exportTickets(
      @RequestParam(name = "days", required = false) Integer days,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    Ctx ctx = AdminSupport.ctxOf(current, http);
    int safeDays = days == null ? 30 : Math.max(1, Math.min(365, days));
    LocalDateTime now = AdminSupport.now();
    List<Map<String, Object>> rows = dashboardService.exportTickets(safeDays, now);
    opLogService.success(ctx, AdminOpLog.ACTION_EXPORT_CSV, "csv:tickets", null,
        "导出危机工单 " + rows.size() + " 行，范围=最近" + safeDays + "天");
    return csvResponse("tickets", rows, TICKET_COLUMNS, now);
  }

  @GetMapping("/export/ai-usage")
  @Operation(summary = "导出 AI 用量与费用 CSV（FR8.2：费用口径就是 ai_call_log 的汇总，不另算）")
  public ResponseEntity<byte[]> exportAiUsage(
      @RequestParam(name = "days", required = false) Integer days,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    Ctx ctx = AdminSupport.ctxOf(current, http);
    int safeDays = days == null ? 30 : Math.max(1, Math.min(365, days));
    LocalDateTime now = AdminSupport.now();
    List<Map<String, Object>> rows = dashboardService.aiUsage(safeDays, now);
    opLogService.success(ctx, AdminOpLog.ACTION_EXPORT_CSV, "csv:ai-usage", null,
        "导出 AI 用量 " + rows.size() + " 行，范围=最近" + safeDays + "天");
    return csvResponse("ai-usage", rows, AI_USAGE_COLUMNS, now);
  }

  @GetMapping("/export/op-logs")
  @Operation(summary = "导出操作日志 CSV（最多 5000 行；导出动作自己也要留痕，所以日志里能看到「谁导了日志」）")
  public ResponseEntity<byte[]> exportOpLogs(
      @RequestParam(name = "operatorId", required = false) Long operatorId,
      @RequestParam(name = "action", required = false) String action,
      @RequestParam(name = "result", required = false) String result,
      @RequestParam(name = "days", required = false) Integer days,
      @AuthenticationPrincipal AuthUser current, HttpServletRequest http) {
    Ctx ctx = AdminSupport.ctxOf(current, http);
    int safeDays = days == null ? 30 : Math.max(1, Math.min(365, days));
    LocalDateTime now = AdminSupport.now();
    LocalDateTime from = now.minusDays(safeDays);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int pageNo = 1; pageNo <= EXPORT_MAX_PAGES && rows.size() < EXPORT_MAX_ROWS; pageNo++) {
      PageQuery query = new PageQuery();
      query.setPage(pageNo);
      query.setSize(EXPORT_PAGE_SIZE);
      PageResult<AdminOpLog> page = opLogService.page(operatorId, action, result, from, now, query);
      List<AdminOpLog> list = page.getList();
      if (list == null || list.isEmpty()) {
        break;
      }
      for (AdminOpLog row : list) {
        rows.add(opLogRow(row));
      }
      if (!page.isHasMore()) {
        break;
      }
    }
    opLogService.success(ctx, AdminOpLog.ACTION_EXPORT_CSV, "csv:op-logs", null,
        "导出操作日志 " + rows.size() + " 行，范围=最近" + safeDays + "天"
            + (action == null || action.isBlank() ? "" : "，动作=" + action)
            + (result == null || result.isBlank() ? "" : "，结果=" + result));
    return csvResponse("op-logs", rows, OP_LOG_COLUMNS, now);
  }

  /** 一行日志 -> 一个「列名 -> 值」的 Map，列名与 TICKET/OP_LOG 常量表一致。 */
  private Map<String, Object> opLogRow(AdminOpLog row) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", row.getId());
    map.put("created_at", row.getCreatedAt());
    map.put("operator_id", row.getOperatorId());
    map.put("operator_role", row.getOperatorRole());
    map.put("action", row.getAction());
    map.put("result", row.getResult());
    map.put("target", row.getTarget());
    map.put("target_id", row.getTargetId());
    map.put("detail", row.getDetail());
    map.put("ip", row.getIp());
    map.put("user_agent", row.getUserAgent());
    return map;
  }

  /** 组响应：文件名带时刻，两次导出不互相覆盖。 */
  private ResponseEntity<byte[]> csvResponse(String topic, List<Map<String, Object>> rows,
      List<String> columns, LocalDateTime now) {
    if (topic == null || !topic.matches("[a-z0-9-]{1,32}")) {
      throw new BizException(ErrorCode.PARAM_INVALID, "导出主题名非法：" + topic);
    }
    String fileName = csvService.fileName(topic, now);
    byte[] body = csvService.build(columns, rows).getBytes(StandardCharsets.UTF_8);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .contentLength(body.length)
        .body(body);
  }
}
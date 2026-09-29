package com.mindisle.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * CSV 组装（任务 T6.6 A9 · 手册 §9.2「导出 CSV，不引 POI 大依赖」）。
 *
 * <p><b>本类是纯函数</b>：入参是「表头 + 行」，出参是一个字符串，不碰数据库、不碰 Servlet、
 * 不碰文件系统。数据源由调用方决定（{@code DashboardMapper#exportTickets}、
 * {@code aiUsageRows}、{@code AdminOpLogMapper#pageFiltered}），于是三处导出口径共享同一份
 * 「Excel 能双击打开」的实现，而不是各写一遍各的坑。</p>
 *
 * <p>三件事必须由本类统一保证，缺一个 Gate6 的判据就过不了：</p>
 * <ol>
 *   <li><b>UTF-8 BOM</b>：没有它，中文在 Excel 里是乱码。这不是可选美化——需求要的是
 *       「导出的 CSV 能被运营直接打开看」，乱码等于交付物不可用；</li>
 *   <li><b>CRLF 换行</b>：RFC 4180 的行结束符就是 CRLF，Excel 对 LF 的容忍度随版本波动；</li>
 *   <li><b>公式注入前缀</b>：以 {@code = + - @} 开头的单元格会被 Excel 当公式求值，
 *       而工单的 {@code handle_note}、帖子的 {@code title} 都是用户可写内容。
 *       把 {@code =HYPERLINK(...)} 原样导进 CSV，等于把一次点击式代码执行寄给了管理员。</li>
 * </ol>
 */
@Service
public class CsvExportService {

  /** UTF-8 字节序标记，必须是整个文件的第一段内容。 */
  static final String BOM = "﻿";

  /** RFC 4180 行结束符。 */
  static final String CRLF = "\r\n";

  /** 需要包引号或加转义前缀的危险首字符（Excel 公式注入面）。 */
  private static final String DANGEROUS_PREFIX = "=+-@\t";

  private static final DateTimeFormatter DATETIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  /** 导出文件名：{@code mindisle-<主题>-<yyyyMMdd-HHmm>.csv}，带时刻是为了让两次导出不互相覆盖。 */
  public String fileName(String topic, LocalDateTime now) {
    LocalDate day = now.toLocalDate();
    return "mindisle-" + topic + "-" + day.toString().replace("-", "")
        + "-" + String.format("%02d%02d", now.getHour(), now.getMinute()) + ".csv";
  }

  /**
   * 拼出完整 CSV 文本（含 BOM）。
   *
   * <p>列的顺序<b>只由 {@code headers} 决定</b>：行是 {@code Map}，
   * 如果按 map 的迭代顺序写，同一份数据两次导出的列序可能不同（HashMap 尤其如此），
   * 而「拿导出结果做前后对比」正是 A9 的使用场景。</p>
   */
  public String build(List<String> headers, List<Map<String, Object>> rows) {
    StringBuilder sb = new StringBuilder(4096);
    sb.append(BOM);
    appendRow(sb, headers);
    if (rows != null) {
      for (Map<String, Object> row : rows) {
        appendRow(sb, headers.stream().map(h -> row == null ? null : row.get(h)).toList());
      }
    }
    return sb.toString();
  }

  private void appendRow(StringBuilder sb, List<?> cells) {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(cell(cells.get(i)));
    }
    sb.append(CRLF);
  }

  /** 一个单元格：先转文本，再判要不要转义，最后判要不要挡公式。 */
  String cell(Object value) {
    if (value == null) {
      return "";
    }
    String text = render(value);
    // 防公式注入：危险首字符前置一个单引号，Excel 会当纯文本处理。
    if (!text.isEmpty() && DANGEROUS_PREFIX.indexOf(text.charAt(0)) >= 0) {
      text = "'" + text;
    }
    boolean needQuote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0
        || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
    if (!needQuote) {
      return text;
    }
    return '"' + text.replace("\"", "\"\"") + '"';
  }

  /**
   * 类型到文本。{@code LocalDateTime} 不用 {@code toString()}：那个带 T 与毫秒的 ISO 串
   * 在 Excel 里不会被识别成日期，运营拿它做数据透视只能自己切字符串。
   * {@code BigDecimal} 用 {@code toPlainString()}，避免 1E+2 这种科学计数法出现在费用列里。
   */
  private static String render(Object value) {
    if (value instanceof LocalDateTime dt) {
      return DATETIME.format(dt);
    }
    if (value instanceof LocalDate d) {
      return d.toString();
    }
    if (value instanceof BigDecimal decimal) {
      return decimal.toPlainString();
    }
    if (value instanceof Object[] arr) {
      StringBuilder sb = new StringBuilder();
      for (Object o : arr) {
        if (sb.length() > 0) {
          sb.append(' ');
        }
        sb.append(o);
      }
      return sb.toString();
    }
    return String.valueOf(value);
  }
}
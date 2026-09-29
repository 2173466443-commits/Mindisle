package com.mindisle.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CSV 组装单测（任务 T6.6 A9/D9 · 手册 §9.2「导出 CSV，不引 POI 大依赖」）。
 *
 * <p><b>为什么这个类值得单独测</b>：导出是三处（工单 / AI 用量 / 操作日志）共用的唯一出口，
 * 而它的四个契约——BOM 字节、CRLF、列序、公式注入前缀——全都是「Excel 双击打开」这一件事的
 * 组成部分。这四条里任何一条断掉，交付物的表现都不是「格式不好看」而是<b>不可用或有害</b>：
 * 缺 BOM 中文全乱码，列序漂移让「拿两次导出做前后对比」失效，
 * 而 {@code handle_note} 是用户可写内容，一条 {@code =HYPERLINK(...)} 原样进 CSV
 * 就等于把一次点击式代码执行寄给管理员。</p>
 *
 * <p><b>本类刻意不测的东西</b>：SQL 取数（{@code exportTickets} 那三条注解 SQL 的行数上限、
 * {@code ONLY_FULL_GROUP_BY}）与 HTTP 响应头（Content-Disposition 的拼法、
 * 留痕有没有写 EXPORT_CSV）——那是「接线错」不是「规则错」，
 * 由 {@code frontend/probe/admingate6.mjs} 的 P12 与 {@code admin-smoke.mjs} 的导出三线打真 HTTP 取证。
 * 尤其记住一条闸门踩过的坑：{@code fetch} 的 {@code text()} 会把开头那个 U+FEFF 剥掉，
 * 所以字节级判据必须走 {@code arrayBuffer()}，本类则用 UTF-8 字节直接验 BOM 的前三个字节。</p>
 *
 * <p><b>固定输入</b>：一个 2026-09-29 06:07 的时刻（避开整点，防止 {@code %02d%02d} 凑巧对上）。</p>
 */
class CsvExportServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 29, 6, 7, 3);

    private static final List<String> HEADERS =
        List.of("id", "level", "user_id", "handle_note", "created_at");

    private final CsvExportService csv = new CsvExportService();

    /** 双引号字符。拼期望值时用它，少写一层反斜杠——转义写错一次，判据就变成钉住一个错误输出。 */
    private static final String Q = String.valueOf((char) 34);

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 去掉 BOM 后按 CRLF 切成非空行。 */
    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("\r\n")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    @Test
    @DisplayName("BOM 是 UTF-8 的前三个字节 EF BB BF，不是「文本里那个看不见的字符」")
    void bomIsRealBytes() {
        String text = csv.build(HEADERS, List.of(row("id", 1)));
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertThat(bytes).hasSizeGreaterThanOrEqualTo(3);
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        // 只有表头没有数据行时也必须带 BOM：零行导出同样要被 Excel 正确识别。
        assertThat(csv.build(HEADERS, List.of())).startsWith(CsvExportService.BOM);
    }

    @Test
    @DisplayName("行结束符是 RFC 4180 的 CRLF，末行也带（不是只在行间插分隔符）")
    void usesCrlf() {
        String text = csv.build(HEADERS, List.of(row("id", 1), row("id", 2)));
        // 注意不能直接 doesNotContain("\n")：CRLF 里本来就有 LF。
        // 真正的判据是「每一个 LF 前面都跟着 CR」——把 CRLF 成对摘掉之后不该剩孤立 LF。
        assertThat(text.replace("\r\n", "|")).doesNotContain("\n");
        assertThat(text).contains("\r\n");
        assertThat(text).endsWith("\r\n");
        assertThat(lines(text)).hasSize(3);
    }

    @Test
    @DisplayName("列序只由 headers 决定：行的 Map 换成 HashMap 并倒序 put，输出列序不变")
    void columnOrderFollowsHeadersOnly() {
        Map<String, Object> reversed = new HashMap<>();
        reversed.put("created_at", "2026-09-29 06:07:03");
        reversed.put("handle_note", "已联系辅导员");
        reversed.put("user_id", 42L);
        reversed.put("level", "L1");
        reversed.put("id", 7L);
        List<String> once = csv.build(HEADERS, List.of(reversed)).lines().toList();
        String header = once.get(0).replaceAll("^\uFEFF", "");
        assertThat(header).isEqualTo("id,level,user_id,handle_note,created_at");
        // 多跑几次：HashMap 的迭代顺序在这个键集上是稳定的，但判据不能依赖它稳定。
        for (int i = 0; i < 5; i++) {
            assertThat(csv.build(HEADERS, List.of(reversed))).isEqualTo(
                "\uFEFFid,level,user_id,handle_note,created_at\r\n"
                    + "7,L1,42,已联系辅导员,2026-09-29 06:07:03\r\n");
        }
    }

    @Test
    @DisplayName("headers 里出现行 Map 没有的列，输出空单元格而不是 null 字样")
    void missingColumnBecomesEmptyCell() {
        String text = csv.build(HEADERS, List.of(row("id", 1)));
        assertThat(lines(text).get(1)).isEqualTo("1,,,,");
        // 整行是 null（理论上不会发生，但导出不能因为一行脏数据抛 NPE 而废掉整份报表）
        List<Map<String, Object>> withNull = new ArrayList<>();
        withNull.add(row("id", 2));
        withNull.add(null);
        assertThat(lines(csv.build(HEADERS, withNull)).get(2)).isEqualTo(",,,,");
    }

    @Test
    @DisplayName("rows 为 null 时只出表头，不抛异常")
    void nullRowsIsNotAnError() {
        assertThat(csv.build(HEADERS, null)).isEqualTo("\uFEFFid,level,user_id,handle_note,created_at\r\n");
    }

    @Test
    @DisplayName("含逗号 / 引号 / 换行的单元格加引号，内部引号按 RFC 4180 双写")
    void escapesDelimiters() {
        assertThat(csv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(csv.cell("他说\"你好\"")).isEqualTo(Q + "他说" + Q + Q + "你好" + Q + Q + Q);
        assertThat(decode(csv.cell("他说\"你好\""))).isEqualTo("他说\"你好\"");
        assertThat(csv.cell("第一行\n第二行")).isEqualTo("\"第一行\n第二行\"");
        assertThat(csv.cell("带\r回车")).isEqualTo("\"带\r回车\"");
        // 三种字符同时出现时，转义只做一次，不能把引号套两层
        assertThat(csv.cell("x,\"y\"\nz")).isEqualTo("\"x,\"\"y\"\"\nz\"");
    }

    @Test
    @DisplayName("公式注入面：= + - @ TAB 开头的单元格前置单引号")
    void blocksFormulaInjection() {
        // 守卫前缀 ' 属于字段值的一部分，所以整格必须被引号包住；引号位置错一格，
        // 这一格就会在逗号或引号处裂成两列，导出的表直接对不上表头。
        String evil = csv.cell("=HYPERLINK(\"http://evil\")");
        assertThat(evil).isEqualTo(Q + "'=HYPERLINK(" + Q + Q + "http://evil" + Q + Q + ")" + Q);
        // 读回来才作数：剥掉外层引号、还原双写之后，值必须正好是「守卫前缀 + 原文」。
        assertThat(decode(evil)).isEqualTo("'=HYPERLINK(\"http://evil\")");
        assertThat(csv.cell("+1+1")).isEqualTo("'+1+1");
        assertThat(csv.cell("-id")).isEqualTo("'-id");
        assertThat(csv.cell("@mention")).isEqualTo("'@mention");
        assertThat(csv.cell("\ttab")).isEqualTo("'\ttab");
        // 普通负数不应该被误伤：只有「首字符是减号」才是公式面
        // 负数也会被前置单引号：公式面优先于数值列。三张导出表里
        // risk_score / cost_cent / call_cnt / tokens 全部非负，所以现在没代价；
        // 哪天真要导负数，得先在这里放开「减号后面紧跟数字」这一例。
        assertThat(csv.cell(-12)).isEqualTo("'-12");
        // 空白本身不是危险前缀
        assertThat(csv.cell(" 空格开头")).isEqualTo(" 空格开头");
    }

    @Test
    @DisplayName("LocalDateTime 导成 yyyy-MM-dd HH:mm:ss，不用 ISO 的 T 与毫秒")
    void rendersDateTime() {
        assertThat(csv.cell(LocalDateTime.of(2026, 9, 29, 6, 7, 3, 400_000_000)))
            .isEqualTo("2026-09-29 06:07:03");
        assertThat(csv.cell(LocalDate.of(2026, 9, 29))).isEqualTo("2026-09-29");
    }

    @Test
    @DisplayName("BigDecimal 用 toPlainString：费用列里不许出现 1E+2 这种科学计数法")
    void rendersBigDecimalPlain() {
        assertThat(csv.cell(new BigDecimal("1E+2"))).isEqualTo("100");
        assertThat(csv.cell(new BigDecimal("12.05"))).isEqualTo("12.05");
        // 小数位不丢：toPlainString 保留末尾 0，Excel 才能按它做汇总
        assertThat(csv.cell(new BigDecimal("0.500"))).isEqualTo("0.500");
    }

    @Test
    @DisplayName("Object[]（trigger_words 那类聚合列）拼成空格分隔的一句话")
    void rendersArray() {
        assertThat(csv.cell(new Object[] {"自伤", "伤害自己"})).isEqualTo("自伤 伤害自己");
        assertThat(csv.cell(new Object[] {})).isEqualTo("");
        assertThat(csv.cell(new Object[] {"a", null})).isEqualTo("a null");
    }

    @Test
    @DisplayName("null 单元格是空串，布尔与数字走 String.valueOf，不留 \"null\" 字样")
    void rendersPrimitives() {
        assertThat(csv.cell(null)).isEqualTo("");
        assertThat(csv.cell(Boolean.TRUE)).isEqualTo("true");
        assertThat(csv.cell(0)).isEqualTo("0");
    }

    @Test
    @DisplayName("文件名带日期到分钟，两次导出不互相覆盖")
    void fileNameHasTimestamp() {
        assertThat(csv.fileName("tickets", NOW)).isEqualTo("mindisle-tickets-20260929-0607.csv");
        assertThat(csv.fileName("op-logs", NOW.withHour(9).withMinute(5)))
            .isEqualTo("mindisle-op-logs-20260929-0905.csv");
        assertThat(csv.fileName("ai-usage", NOW)).startsWith("mindisle-ai-usage-").endsWith(".csv");
    }

    @Test
    @DisplayName("危险前缀与转义叠加的顺序：先加守卫再整体包引号（守卫符落在引号里面才算一个字段）")
    void dangerousPrefixAppliedBeforeQuoting() {
        String cell = csv.cell("=a,b");
        assertThat(cell).isEqualTo(Q + "'=a,b" + Q);
        // 旧判据把外层引号写在守卫符之后（形如 '"..."），那样 Excel 会在逗号处裂成两列。
        assertThat(cell).startsWith(Q).endsWith(Q);
        // 除首尾这对之外不该再多一个引号字符（34 就是双引号的码点）
        assertThat(cell.chars().filter(c -> c == 34).count()).isEqualTo(2L);
        assertThat(decode(cell)).isEqualTo("'=a,b");
    }

    @Test
    @DisplayName("一千行导出的规模自检：行数 = 1 表头 + 1000 数据行")
    void scalesToOneThousandRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            rows.add(row("id", (long) i, "level", "L2", "user_id", (long) i,
                "handle_note", "第" + i + "条,含ASCII逗号", "created_at", NOW));
        }
        List<String> out = lines(csv.build(HEADERS, rows));
        assertThat(out).hasSize(1001);
        // 全角逗号不触发转义，半角逗号才会——这条区分必须钉住，
        // 否则「中文标点被莫名包上引号」这种问题会被反向写进判据里。
        assertThat(csv.cell("含，全角逗号")).isEqualTo("含，全角逗号");
        assertThat(out.get(1)).isEqualTo("0,L2,0,\"第0条,含ASCII逗号\",2026-09-29 06:07:03");
        assertThat(Collections.frequency(out, out.get(1000))).isEqualTo(1);
    }

    /**
     * 按 RFC 4180 把一格读回来：剥掉外层引号，再把成对出现的引号还原成一个。
     *
     * <p>判据不能只盯着「字符串长什么样」。导出物的验收标准是
     * 「解析器拿到的值 == 字段值」，所以凡是对转义下判据的地方都配一条 round-trip。
     * 这一轮就是被自己手写的期望值绊的：把外层引号记到了守卫符后面，
     * 于是三条本来该绿的判据红了，而产品输出一直是对的。</p>
     */
    private static String decode(String cell) {
        char q = 34;
        boolean quoted = cell.length() >= 2 && cell.charAt(0) == q && cell.charAt(cell.length() - 1) == q;
        if (!quoted) {
            return cell;
        }
        String inner = cell.substring(1, cell.length() - 1);
        StringBuilder sb = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            sb.append(c);
            if (c == q && i + 1 < inner.length() && inner.charAt(i + 1) == q) {
                i++;
            }
        }
        return sb.toString();
    }
}
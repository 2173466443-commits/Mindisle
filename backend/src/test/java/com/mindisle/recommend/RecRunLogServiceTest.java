package com.mindisle.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mindisle.entity.RecRunLog;
import com.mindisle.mapper.RecRunLogMapper;
import com.mindisle.recommend.OfflineRecommendService.Summary;

/**
 * 推荐重算台账单测（手册 §10.5 · §15 阶段 8 队列① · sql/19 第 36 表 · 答辩 D7）。
 *
 * <p><b>本类钉的四件事</b>：①三种结局（SUCCESS / FAILED / SKIPPED_BUSY）各写出一行长什么样的记录，
 * 尤其「跳过」必须是 started_at = finished_at 且 duration_ms = 0，而不是留成 null 让人猜；
 * ②台账写坏不许把已经算完的重算结果一起带走（{@code write} 只 log.error）；
 * ③{@code channel_share} 那串 JSON 的键序必须确定，否则答辩现场念出来的「六路通道各占多少」
 * 每次刷新都在换顺序，而它明明是一轮历史快照；
 * ④{@code ledger} 的四个键自报截断，前端不再自己数行数。</p>
 *
 * <p><b>为什么还要拿 DDL 对一次账</b>：status 在库里是 ENUM，Java 侧新增一种结局而忘了同步 DDL 时，
 * 插入会在 MySQL 上炸，而炸的位置离「有人改了常量」已经隔了一个作业链路。
 * 这条对账把偏差挪到单测里，口径同 {@code PrivacyDomainsTest} 的双向对账。</p>
 *
 * <p>不用 {@code @SpringBootTest}：本类只验服务层的记账语义，真库里的 ENUM 定义由文件文本对账负责，
 * 一次 {@code INSERT} 的真打由 {@code RecommendJob} 跑通时的台账行负责。</p>
 */
class RecRunLogServiceTest {

  private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 8, 3, 30, 0);

  private final List<RecRunLog> inserts = new ArrayList<>();
  private final List<Integer> recentLimits = new ArrayList<>();
  private boolean breakInserts;
  private List<RecRunLog> recentRows = List.of();
  private List<Map<String, Object>> statusCounts = List.of();
  private RecRunLogService service;

  @BeforeEach
  void setUp() {
    service = new RecRunLogService(stubMapper());
  }

  /** 只打桩 insert / recent / countByStatus 三个口，其余方法被调到即红：防止服务偷偷多读一张表。 */
  private RecRunLogMapper stubMapper() {
    return (RecRunLogMapper) Proxy.newProxyInstance(RecRunLogMapper.class.getClassLoader(),
        new Class<?>[] {RecRunLogMapper.class}, (proxy, method, args) -> switch (method.getName()) {
          case "insert" -> {
            if (breakInserts) {
              throw new IllegalStateException("模拟台账表不可用：磁盘满 1114");
            }
            RecRunLog row = (RecRunLog) args[0];
            row.setId((long) inserts.size() + 1L);
            inserts.add(row);
            yield 1;
          }
          case "recent" -> {
            recentLimits.add((Integer) args[0]);
            yield recentRows;
          }
          case "countByStatus" -> statusCounts;
          default -> throw new UnsupportedOperationException("未打桩的方法：" + method.getName());
        });
  }

  private static Summary summary(String mode, Map<String, Integer> share) {
    return new Summary(4200, 96, 8800, 4200, 311, 3110, mode, 60388L, share);
  }

  // ============================================================= 三种结局行长什么样

  @Test
  @DisplayName("成功一轮：摘要的九个数逐字落进台账，通道 JSON 按 key 排序")
  void successRowCarriesWholeSummary() {
    Map<String, Integer> share = new LinkedHashMap<>();
    share.put("hot", 620);
    share.put("cf", 1980);
    share.put("emotion", 512);
    service.recordSuccess(RecRunLog.TRIGGER_MANUAL, T0, T0.plusSeconds(60), 60388L, summary("emotion-on", share));

    assertThat(inserts).hasSize(1);
    RecRunLog row = inserts.get(0);
    assertThat(row.getStatus()).isEqualTo(RecRunLog.STATUS_SUCCESS);
    assertThat(row.getTriggerType()).isEqualTo("manual");
    assertThat(row.getStartedAt()).isEqualTo(T0);
    assertThat(row.getFinishedAt()).isEqualTo(T0.plusSeconds(60));
    assertThat(row.getDurationMs()).isEqualTo(60388L);
    assertThat(row.getMode()).isEqualTo("emotion-on");
    assertThat(row.getUserCnt()).isEqualTo(311);
    assertThat(row.getQualityRows()).isEqualTo(4200);
    assertThat(row.getTopicRows()).isEqualTo(96);
    assertThat(row.getSimilarityRows()).isEqualTo(8800);
    assertThat(row.getResultRows()).isEqualTo(3110);
    assertThat(row.getChannelShare()).as("键序必须确定：插入顺序是 hot,cf,emotion，存下来必须是 cf,emotion,hot")
        .isEqualTo("{\"cf\":1980,\"emotion\":512,\"hot\":620}");
    assertThat(row.getErrorText()).as("成功行不该带错误文本").isNull();
    assertThat(row.getCreatedAt()).as("createdAt 由 DDL 的 DEFAULT CURRENT_TIMESTAMP(3) 负责，Java 不传")
        .isNull();
  }

  @Test
  @DisplayName("summary 为 null 仍记一行「零写入的成功」，触发口缺省落 schedule")
  void successWithoutSummaryIsStillLogged() {
    service.recordSuccess(null, T0, T0, 12L, null);

    RecRunLog row = inserts.get(0);
    assertThat(inserts).hasSize(1);
    assertThat(row.getTriggerType()).isEqualTo(RecRunLog.TRIGGER_SCHEDULE);
    assertThat(row.getStatus()).isEqualTo(RecRunLog.STATUS_SUCCESS);
    assertThat(row.getQualityRows()).isZero();
    assertThat(row.getResultRows()).isZero();
    assertThat(row.getMode()).isNull();
    assertThat(row.getChannelShare()).isNull();
  }

  @Test
  @DisplayName("失败一轮：error_text 是异常 toString 且截到 1000，堆栈不进库")
  void failureRowTruncatesErrorText() {
    service.recordFailure(RecRunLog.TRIGGER_SCHEDULE, T0, T0.plusSeconds(5), 5_000L,
        new IllegalStateException("x".repeat(2_400)));
    service.recordFailure(RecRunLog.TRIGGER_SCHEDULE, T0, T0, 0L, null);

    assertThat(inserts).hasSize(2);
    RecRunLog row = inserts.get(0);
    assertThat(row.getStatus()).isEqualTo(RecRunLog.STATUS_FAILED);
    assertThat(row.getErrorText()).hasSize(RecRunLogService.ERROR_MAX)
        .startsWith("java.lang.IllegalStateException: xxx");
    assertThat(row.getChannelShare()).as("失败行没有通道占比").isNull();
    assertThat(inserts.get(1).getErrorText()).as("异常对象为 null 也要留下一句能读的话")
        .isEqualTo("unknown");
  }

  @Test
  @DisplayName("跳过一轮：开始即结束、耗时写 0，而不是留 null 让人猜")
  void skippedBusyRowIsHonestAboutDoingNothing() {
    service.recordSkippedBusy(RecRunLog.TRIGGER_MANUAL, T0);

    RecRunLog row = inserts.get(0);
    assertThat(row.getStatus()).isEqualTo(RecRunLog.STATUS_SKIPPED_BUSY);
    assertThat(row.getStartedAt()).isEqualTo(T0);
    assertThat(row.getFinishedAt()).as("本轮什么都没做，结束时刻只能等于开始时刻").isEqualTo(T0);
    assertThat(row.getDurationMs()).as("0 就是 0，不写成 null 也不四舍五入成「小于 1 毫秒」").isEqualTo(0L);
    assertThat(row.getErrorText()).isNull();
  }

  // ============================================================= 写失败不外抛

  @Test
  @DisplayName("台账插不进去也不外抛：记账表不许绑架已经算完的重算结果")
  void brokenLedgerDoesNotBreakTheRun() {
    breakInserts = true;

    assertDoesNotThrow(() -> service.recordSuccess(RecRunLog.TRIGGER_SCHEDULE, T0, T0, 9L,
        summary("emotion-off", Map.of("cf", 1))));
    assertDoesNotThrow(() -> service.recordFailure(RecRunLog.TRIGGER_SCHEDULE, T0, T0, 9L,
        new RuntimeException("boom")));
    assertDoesNotThrow(() -> service.recordSkippedBusy(RecRunLog.TRIGGER_SCHEDULE, T0));

    assertThat(inserts).as("三行都没写进去是事实，但异常必须被吞在 write 里").isEmpty();
  }

  // ============================================================= JSON 与截断的边界

  @Test
  @DisplayName("toJson：空与 null 出 null（不是 {}），键里有引号或反斜杠要转义")
  void jsonEncodingIsDeterministicAndEscaped() {
    assertThat(RecRunLogService.toJson(null)).isNull();
    assertThat(RecRunLogService.toJson(Map.of())).isNull();
    assertThat(RecRunLogService.toJson(Map.of("b", 2, "a", 1))).isEqualTo("{\"a\":1,\"b\":2}");
    assertThat(RecRunLogService.toJson(Map.of("em\"o", 7))).isEqualTo("{\"em\\\"o\":7}");
  }

  @Test
  @DisplayName("cut：等于上限不动，超一个字符才截，列宽 1000/500 的边界不能靠运气")
  void cutKeepsExactLengthAndTrimsOnlyBeyondCap() {
    String s = "y".repeat(RecRunLogService.ERROR_MAX);
    assertThat(RecRunLogService.cut(s, RecRunLogService.ERROR_MAX)).hasSize(RecRunLogService.ERROR_MAX);
    assertThat(RecRunLogService.cut(s + "y", RecRunLogService.ERROR_MAX)).hasSize(RecRunLogService.ERROR_MAX);
    assertThat(RecRunLogService.cut(null, 10)).isNull();
    assertThat(RecRunLogService.cut("abc", RecRunLogService.CHANNEL_MAX)).isEqualTo("abc");
  }

  // ============================================================= 台账读数

  @Test
  @DisplayName("ledger：limit / returned / truncated / totalRuns 四个键自报截断，前端不用自己数")
  void ledgerReportsItsOwnTruncation() {
    recentRows = List.of(row(RecRunLog.STATUS_SUCCESS), row(RecRunLog.STATUS_FAILED));
    statusCounts = List.of(count("SUCCESS", 90), count("FAILED", 5), count("SKIPPED_BUSY", 3));

    Map<String, Object> view = service.ledger(2);

    assertThat(view).containsEntry("limit", 2).containsEntry("returned", 2)
        .containsEntry("truncated", true).containsEntry("totalRuns", 98L);
    assertThat(view.get("runs")).isEqualTo(recentRows);
    @SuppressWarnings("unchecked")
    Map<String, Object> counts = (Map<String, Object>) view.get("counts");
    assertThat(counts).containsEntry("SUCCESS", 90L).containsEntry("FAILED", 5L)
        .containsEntry("SKIPPED_BUSY", 3L);

    // 第二次取到全量行时才该翻成「没截断」：truncated 的分母是库里的总轮数，不是本页行数。
    recentRows = rows(98);
    Map<String, Object> all = service.ledger(500);
    assertThat(all).containsEntry("limit", RecRunLogService.RECENT_MAX).containsEntry("returned", 98)
        .containsEntry("truncated", false);
    assertThat(recentLimits).containsExactly(2, RecRunLogService.RECENT_MAX);
  }

  @Test
  @DisplayName("limit 传 0 或负数按默认 50，上限 200：两处夹的口径必须在服务层说死")
  void clampLimitHasOneOwner() {
    assertThat(RecRunLogService.clampLimit(0)).isEqualTo(RecRunLogService.RECENT_DEFAULT);
    assertThat(RecRunLogService.clampLimit(-7)).isEqualTo(RecRunLogService.RECENT_DEFAULT);
    assertThat(RecRunLogService.clampLimit(20)).isEqualTo(20);
    assertThat(RecRunLogService.clampLimit(201)).isEqualTo(RecRunLogService.RECENT_MAX);
  }

  private static List<RecRunLog> rows(int n) {
    List<RecRunLog> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      out.add(row(RecRunLog.STATUS_SUCCESS));
    }
    return out;
  }

  private static RecRunLog row(String status) {
    RecRunLog r = new RecRunLog();
    r.setStatus(status);
    r.setTriggerType(RecRunLog.TRIGGER_SCHEDULE);
    return r;
  }

  private static Map<String, Object> count(String status, long cnt) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("status", status);
    m.put("cnt", cnt);
    return m;
  }

  // ============================================================= 与 DDL 对账

  @Test
  @DisplayName("三个结局常量与两个触发口常量都在 sql/19 的 ENUM 定义里，加一种结局忘了改 DDL 先红这里")
  void constantsMatchDdlEnums() throws IOException {
    String file = Files.readString(sqlDir().resolve("19_rec_run_log.sql"));
    // 只对账 CREATE TABLE 那一段：文件头的散文里出现过 "`deleted`" 三个字（正是在解释为什么没有这一列），
    // 拿整份文件做 doesNotContain 会把注释当缺陷。
    int from = file.indexOf("CREATE TABLE IF NOT EXISTS `rec_run_log`");
    assertThat(from).as("sql/19 里必须能找到 rec_run_log 的建表语句").isPositive();
    String ddl = file.substring(from);
    for (String status : List.of(RecRunLog.STATUS_SUCCESS, RecRunLog.STATUS_FAILED,
        RecRunLog.STATUS_SKIPPED_BUSY)) {
      assertThat(ddl).as("rec_run_log.status 的 ENUM 里缺 %s", status).contains("'" + status + "'");
    }
    for (String trigger : List.of(RecRunLog.TRIGGER_SCHEDULE, RecRunLog.TRIGGER_MANUAL)) {
      assertThat(ddl).as("rec_run_log.trigger_type 的 ENUM 里缺 %s", trigger).contains("'" + trigger + "'");
    }
    assertThat(ddl).as("台账表只增不删：DDL 里不该出现 deleted 列").doesNotContain("`deleted`");
  }

  /** sql 目录：surefire 的工作目录是 backend，从仓库根与上一级两个候选里找。 */
  private static Path sqlDir() {
    Path base = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    for (String rel : List.of("sql", "../sql", "../../sql")) {
      Path p = base.resolve(rel).normalize();
      if (Files.isDirectory(p)) {
        return p;
      }
    }
    throw new IllegalStateException("找不到 sql 目录，user.dir=" + base);
  }
}
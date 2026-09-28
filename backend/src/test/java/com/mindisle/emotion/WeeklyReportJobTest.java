package com.mindisle.emotion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import com.mindisle.config.MindisleProperties;

/**
 * 周报定时批次的单测（任务 T4.20）。
 *
 * <p><b>不用 {@code @SpringBootTest}，也不用真 Mapper / 真模型</b>：{@link WeeklyReportJob} 的两个端口
 * 在这里由一个内存假实现同时满足。本类需要被钉住的三件事 —— 候选窗口怎么算、批次上限怎么夹、
 * 单个失败会不会拖垮整批 —— 全是纯 Java 逻辑；而生产实现的干活那一侧（情绪聚合 + LLM 调用）
 * 一旦进测试就连真库、真模型、真花钱，那恰恰是 T4.20 最容易失控的地方，
 * 不该由一次 {@code mvn test} 触发。</p>
 *
 * <p>第 2 组是这份测试里最值钱的一条：它不比字符串相等，而是用 {@code CronExpression.parse}
 * <b>真的算了一次下一个触发时刻</b>，于是「语法合法但跑错日子」（把 SUN 改成 SAT）当场跑红。
 * 其余各组分别覆盖 {@link WeeklyReportJob#run} 的每一条分支。</p>
 */
@DisplayName("T4.20 情绪周报定时批次")
class WeeklyReportJobTest {

  // ==================================================== 假实现（同时满足两个端口）

  /**
   * 内存假批次：记下传给候选读数的窗口与 limit，按脚本决定返回几个人、其中谁生成会抛错。
   *
   * <p>断言「传下去的 limit 到底是多少」是必需的：批次上限与截断判据全靠那个 +1，
   * 只看 {@code Summary} 看不出来它向上层要了几行。</p>
   */
  private static final class FakeBatch implements WeeklyReportJob.CandidateSource, WeeklyReportJob.Generator {

    private final List<Long> pool = new ArrayList<>();
    private final List<Long> askedLimits = new ArrayList<>();
    private final List<String> windows = new ArrayList<>();
    private final List<Long> generated = new ArrayList<>();
    private final Set<Long> failing = new HashSet<>();

    FakeBatch returnCount(int n) {
      pool.clear();
      for (int i = 1; i <= n; i++) {
        pool.add((long) i);
      }
      return this;
    }

    FakeBatch returnIds(List<Long> ids) {
      pool.clear();
      pool.addAll(ids);
      return this;
    }

    FakeBatch failUsers(Long... ids) {
      failing.addAll(Arrays.asList(ids));
      return this;
    }

    @Override
    public List<Long> checkinUserIds(LocalDate fromDate, LocalDate toDate, int limit) {
      askedLimits.add((long) limit);
      windows.add(fromDate + ".." + toDate);
      // 只返回真正被要到的行数，模拟 SQL 的 LIMIT 语义（否则「多要一行」判不出截断）
      return new ArrayList<>(pool.subList(0, Math.min(pool.size(), Math.max(0, limit))));
    }

    @Override
    public void generate(long userId) {
      generated.add(userId);
      if (failing.contains(userId)) {
        throw new IllegalStateException("模拟第 " + userId + " 个人的情绪聚合抛错");
      }
    }
  }

  private static MindisleProperties props(boolean enabled, int batchLimit) {
    MindisleProperties p = new MindisleProperties();
    p.getSchedule().setWeeklyReportEnabled(enabled);
    p.getSchedule().setWeeklyReportBatchLimit(batchLimit);
    return p;
  }

  /** 2026-09-21 是周一；2026-09-27 是周日、09-26 是周六，三者同属这一周。 */
  private static final LocalDate MON = LocalDate.of(2026, 9, 21);
  private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

  // ==================================================== 1. ISO 周口径

  @Test
  @DisplayName("weekStartOf：周日归到本周一，不是归到下一周")
  void weekStartIsIsoMonday() {
    assertEquals(MON, WeeklyReportJob.weekStartOf(LocalDate.of(2026, 9, 27)),
        "2026-09-27 是周日，必须回退到本周一 09-21；写成 minusDays(6) 这类固定偏移会在这里跑红");
    assertEquals(MON, WeeklyReportJob.weekStartOf(LocalDate.of(2026, 9, 21)), "周一自己就是本周一");
    assertEquals(MON, WeeklyReportJob.weekStartOf(LocalDate.of(2026, 9, 26)), "周六同样属于 09-21 那一周");
  }

  // ==================================================== 2. cron 口径（最值钱的一条）

  @Test
  @DisplayName("@Scheduled 的 cron 缺省串仍在，且 parse 之后真的是下一个周日 21:00")
  void cronLiteralIsSundayNinePmAndReallyParses() throws Exception {
    Method method = WeeklyReportJob.class.getMethod("scheduledWeekly");
    Scheduled[] anns = method.getAnnotationsByType(Scheduled.class);
    assertEquals(1, anns.length, "scheduledWeekly 必须且只能有一个 @Scheduled");
    String cron = anns[0].cron();
    assertTrue(cron.startsWith("$" + "{mindisle.schedule.weekly-report-cron:"),
        "cron 应当是「占位符 + 内嵌缺省」，否则改配置不发版这件事就没了；实际：" + cron);
    assertTrue(cron.contains("0 0 21 ? * SUN"), "手册 7.5 的口径是周日晚 21:00，实际读到：" + cron);
    CronExpression expr = CronExpression.parse("0 0 21 ? * SUN");
    assertNotNull(expr, "cron 串必须能被解析，否则启动期就抛 IllegalArgumentException");
    LocalDateTime next = expr.next(LocalDateTime.of(2026, 9, 28, 0, 0));
    assertEquals(LocalDateTime.of(2026, 10, 4, 21, 0), next, "下一个触发时刻不是周日 21:00：" + next);
  }

  // ==================================================== 3. 候选窗口

  @Test
  @DisplayName("候选窗口是今天减 30 天到今天（两端都含），逐字传给 CandidateSource")
  void candidateWindowIsThirtyDayLookback() {
    FakeBatch fake = new FakeBatch().returnCount(1);
    WeeklyReportJob.Summary s = new WeeklyReportJob(fake, fake, props(true, 200)).run(TODAY, 0);
    assertEquals(30, WeeklyReportJob.LOOKBACK_DAYS, "手册 7.5 第 2 条写的是近 30 天有打卡");
    assertEquals(List.of(TODAY.minusDays(30) + ".." + TODAY), fake.windows,
        "窗口两端都含（BETWEEN），少一天就会漏掉刚好 30 天前打过卡的人");
    assertEquals(TODAY, s.weekStart(), "2026-09-28 本身就是周一，weekStart 应当是它自己");
  }

  // ==================================================== 4. 批次上限的夹逼

  @Test
  @DisplayName("向候选读数多要一行：limit 夹到不超过 MAX_BATCH，传下去的是 batchLimit + 1")
  void limitIsClampedAndReadAsksForOneMore() {
    FakeBatch a = new FakeBatch().returnCount(1);
    new WeeklyReportJob(a, a, props(true, 200)).run(TODAY, 9999);
    assertEquals(List.of(201L), a.askedLimits, "参数写 9999 也要被 MAX_BATCH=200 夹住，再多要一行");
    FakeBatch b = new FakeBatch().returnCount(3);
    new WeeklyReportJob(b, b, props(true, 200)).run(TODAY, 5);
    assertEquals(List.of(6L), b.askedLimits, "参数写 5 就传 6");
    FakeBatch c = new FakeBatch().returnCount(2);
    new WeeklyReportJob(c, c, props(true, 7)).run(TODAY, 0);
    assertEquals(List.of(8L), c.askedLimits, "limit 为 0 时用配置值（这里配 7，于是传 8）");
    FakeBatch d = new FakeBatch().returnCount(0);
    new WeeklyReportJob(d, d, props(true, 0)).run(TODAY, 0);
    assertEquals(List.of(1L), d.askedLimits, "配置写 0 也不能变成读 0 条：读 0 条时截断判据永远为假");
  }

  // ==================================================== 5. 截断判据

  @Test
  @DisplayName("读到第 201 行才算被截断；刚好 200 行不是截断")
  void truncatedOnlyWhenOneRowTooMany() {
    FakeBatch full = new FakeBatch().returnCount(WeeklyReportJob.MAX_BATCH + 1);
    WeeklyReportJob.Summary s = new WeeklyReportJob(full, full, props(true, 200)).run(TODAY, 0);
    assertTrue(s.truncated(), "多要的那一行真被读到，就说明有人被批次上限挤出去了");
    assertEquals(WeeklyReportJob.MAX_BATCH, s.candidates());
    assertEquals(WeeklyReportJob.MAX_BATCH, s.generated());
    assertEquals(WeeklyReportJob.MAX_BATCH, full.generated.size(), "只处理前 200 个，第 201 个不该被处理");
    FakeBatch exact = new FakeBatch().returnCount(WeeklyReportJob.MAX_BATCH);
    WeeklyReportJob.Summary s2 = new WeeklyReportJob(exact, exact, props(true, 200)).run(TODAY, 0);
    assertFalse(s2.truncated(), "刚好等于上限不算截断，否则满 200 人的批次永远报告有人被挤掉");
    assertEquals(0, s2.failed());
  }

  @Test
  @DisplayName("手动传 limit=3：候选 5 人只处理最前面 3 人并标记截断")
  void explicitLimitTruncatesToo() {
    FakeBatch fake = new FakeBatch().returnCount(5);
    WeeklyReportJob.Summary s = new WeeklyReportJob(fake, fake, props(true, 200)).run(TODAY, 3);
    assertTrue(s.truncated());
    assertEquals(3, s.candidates());
    assertEquals(3, s.generated());
    assertEquals(List.of(1L, 2L, 3L), fake.generated, "处理的是名单最前面三个，全序由 SQL 的 ORDER BY 保证");
  }

  // ==================================================== 6. 逐用户隔离（本任务最核心的风险）

  @Test
  @DisplayName("5 个人里 4 个抛错：成功 1 失败 4，整批不中断也不重抛")
  void oneFailureDoesNotEndTheBatch() {
    FakeBatch fake = new FakeBatch().returnCount(5).failUsers(2L, 3L, 4L, 5L);
    WeeklyReportJob.Summary s = assertDoesNotThrow(
        () -> new WeeklyReportJob(fake, fake, props(true, 200)).run(TODAY, 0),
        "第 2 个人的失败不该把异常抛出批次入口，更不该让 3/4/5 三个人连被尝试的机会都没有");
    assertEquals(1, s.generated());
    assertEquals(4, s.failed());
    assertEquals(5, fake.generated.size(), "每个人都要被调用过：失败是调了但抛错，而不是没调");
  }

  @Test
  @DisplayName("候选里的非法 id 计入失败且不调用生成：脏数据不该变成一次 LLM 调用")
  void illegalCandidateIdsAreSkippedNotGenerated() {
    FakeBatch fake = new FakeBatch().returnIds(Arrays.asList(1L, null, 0L, -5L, 2L));
    WeeklyReportJob.Summary s = new WeeklyReportJob(fake, fake, props(true, 200)).run(TODAY, 0);
    assertEquals(2, s.generated());
    assertEquals(3, s.failed());
    assertEquals(List.of(1L, 2L), fake.generated, "null / 0 / 负数绝不能进 report()");
    assertFalse(s.truncated());
  }

  // ==================================================== 7. 空批次

  @Test
  @DisplayName("没有候选时全 0 且一次生成都不调；读数返回 null 也当成空")
  void emptyBatchCallsGeneratorZeroTimes() {
    FakeBatch none = new FakeBatch().returnCount(0);
    WeeklyReportJob.Summary s = new WeeklyReportJob(none, none, props(true, 200)).run(TODAY, 0);
    assertEquals(0, s.candidates());
    assertEquals(0, s.generated());
    assertEquals(0, s.failed());
    assertFalse(s.truncated());
    assertTrue(none.generated.isEmpty(), "候选为空却调了生成，就是给没打卡的人发周报");
    WeeklyReportJob.CandidateSource nullSource = (from, to, limit) -> null;
    List<Long> calls = new ArrayList<>();
    WeeklyReportJob.Generator gen = id -> calls.add(id);
    WeeklyReportJob.Summary s2 = new WeeklyReportJob(nullSource, gen, props(true, 200)).run(TODAY, 0);
    assertEquals(0, s2.candidates());
    assertTrue(calls.isEmpty(), "读数返回 null 不是异常，不该上抛，也不该生成");
  }

  // ==================================================== 8. 配置开关与定时入口

  @Test
  @DisplayName("weekly-report-enabled=false 时定时入口一条都不生成，连候选读数都不发")
  void disabledSwitchSkipsScheduledEntry() {
    FakeBatch fake = new FakeBatch().returnCount(3);
    new WeeklyReportJob(fake, fake, props(false, 200)).scheduledWeekly();
    assertTrue(fake.generated.isEmpty(), "开关关掉却仍然生成了 " + fake.generated.size() + " 份周报");
    assertTrue(fake.askedLimits.isEmpty(), "开关关掉连候选都不该读，省一次数据库往返");
  }

  @Test
  @DisplayName("定时入口跑的就是 run() 那条路径：与手动触发共用同一段代码")
  void scheduledEntryRunsTheSameBatch() {
    FakeBatch fake = new FakeBatch().returnCount(2);
    new WeeklyReportJob(fake, fake, props(true, 200)).scheduledWeekly();
    assertEquals(2, fake.generated.size(),
        "定时入口与手动触发必须是同一条路径，否则手动验过的行为不是周日夜里真发生的行为");
    assertEquals(1, fake.askedLimits.size(), "一趟批次只读一次候选");
  }

  @Test
  @DisplayName("候选读数整体抛错时定时入口吞掉异常：调度线程不该崩，下一次照排")
  void scheduledEntrySwallowsWholeBatchFailure() {
    WeeklyReportJob.CandidateSource boom = (from, to, limit) -> {
      throw new IllegalStateException("模拟库连接断开");
    };
    List<Long> calls = new ArrayList<>();
    assertDoesNotThrow(() -> new WeeklyReportJob(boom, id -> calls.add(id), props(true, 200)).scheduledWeekly(),
        "整批失败要在入口被吞一次并留下窗口上下文，而不是把异常丢给 Spring 的默认错误处理器");
    assertTrue(calls.isEmpty());
  }

  // ==================================================== 9. Summary 口径

  @Test
  @DisplayName("Summary 带着窗口与耗时：定时任务那行日志与管理端响应体是同一份账")
  void summaryCarriesWindowAndCost() {
    FakeBatch fake = new FakeBatch().returnCount(2);
    LocalDate day = LocalDate.of(2026, 10, 1);
    WeeklyReportJob.Summary s = new WeeklyReportJob(fake, fake, props(true, 200)).run(day, 10);
    assertEquals(LocalDate.of(2026, 9, 28), s.weekStart(), "10-01 是周四，本周一是 09-28");
    assertEquals(day.minusDays(WeeklyReportJob.LOOKBACK_DAYS), s.windowFrom());
    assertEquals(day, s.windowTo());
    assertEquals(2, s.candidates());
    assertEquals(2, s.generated());
    assertEquals(0, s.failed());
    assertFalse(s.truncated());
    assertTrue(s.costMillis() >= 0, "耗时不能是负数：" + s.costMillis());
  }
}

package com.mindisle.privacy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
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
 * 到期清除任务的单测（任务 T4.21 · 需求 FR1.6 注销满 30 天自动删除 · 手册 §7.5 状态机）。
 *
 * <p>骨架照 {@code emotion/WeeklyReportJobTest}，因为两个任务要钉死的四件事是同一类问题：
 * 触发时刻、批次上限怎么夹、单人失败会不会拖垮整批、开关关掉时是否连读数都不发。
 * 区别在于这里删的是数据：上限的意义不是省钱而是<b>锁表的时间</b>，
 * 所以 {@code MAX_BATCH} 是硬上界、配置只能调小 —— 能被配置突破的闸不算闸，这条要单独钉。</p>
 *
 * <p>两个端口都用内存假实现，不连库、不引 mock 框架（与仓库里其余测试同一口味）。
 * {@link FakeSource} 把每一次「问了什么」都记下来，否则「开关关掉时连读数都不发」这种
 * 否定式断言根本没法证伪 —— 它看起来永远是对的。</p>
 */
@DisplayName("T4.21 到期清除任务")
class DataRetentionJobTest {

  /** 候选读数端口替身：应答可配，且逐次记下被问到的时刻与上限。 */
  private static final class FakeSource implements DataRetentionJob.CandidateSource {

    private final List<LocalDateTime> moments = new ArrayList<>();

    private final List<Integer> limits = new ArrayList<>();

    private List<Long> answer = new ArrayList<>();

    void reply(List<Long> ids) {
      this.answer = ids;
    }

    @Override
    public List<Long> duePurgeUserIds(LocalDateTime now, int limit) {
      moments.add(now);
      limits.add(limit);
      return answer;
    }

    /** 最后一次被问到的上限；没问过则 -1 —— 否定式断言要能区分「问了 0」与「根本没问」。 */
    int lastLimit() {
      return limits.isEmpty() ? -1 : limits.get(limits.size() - 1);
    }

    /** 被问的总次数：开关关掉那条断言要看的是这个，而不是某个返回值。 */
    int asked() {
      return limits.size();
    }

    LocalDateTime firstMoment() {
      return moments.get(0);
    }
  }

  /** 清除端口替身：按 id 抛错，用来测「第 2 个人失败不牵连第 1、3 个」。 */
  private static final class FakePurger implements DataRetentionJob.Purger {

    private final List<Long> purged = new ArrayList<>();

    private final Set<Long> boom = new HashSet<>();

    void failFor(long... ids) {
      for (long id : ids) {
        boom.add(id);
      }
    }

    @Override
    public void purgeOne(long userId) {
      purged.add(userId);
      if (boom.contains(userId)) {
        throw new IllegalStateException("产物文件被占用 user=" + userId);
      }
    }
  }

  private static MindisleProperties props(boolean enabled, int batchLimit) {
    MindisleProperties properties = new MindisleProperties();
    properties.getSchedule().setRetentionEnabled(enabled);
    properties.getSchedule().setRetentionBatchLimit(batchLimit);
    return properties;
  }

  private static final LocalDateTime NOON = LocalDateTime.of(2026, 4, 14, 12, 0);

  // ==================================================== 1. 触发时刻

  @Test
  @DisplayName("cron 是「占位符加内嵌缺省」，且 parse 之后真的落在每天 03:30:00")
  void cronLiteralIsDailyHalfPastThreeAndReallyParses() throws Exception {
    Method method = DataRetentionJob.class.getMethod("scheduledRetention");
    Scheduled[] anns = method.getAnnotationsByType(Scheduled.class);
    assertEquals(1, anns.length, "scheduledRetention 必须且只能有一个 @Scheduled");
    String cron = anns[0].cron();
    assertTrue(cron.startsWith("$" + "{mindisle.schedule.retention-cron:"),
        "cron 应当是「占位符加内嵌缺省」，否则改配置不必发版这件事就没了；实际：" + cron);
    assertTrue(cron.contains("0 30 3 * * ?"), "手册 7.5 的口径是每天凌晨 03:30，实际读到：" + cron);
    CronExpression expr = CronExpression.parse("0 30 3 * * ?");
    assertNotNull(expr, "cron 串必须真能被解析，否则启动期就抛 IllegalArgumentException，整个应用起不来");
    LocalDateTime next = expr.next(LocalDateTime.of(2026, 9, 28, 4, 0));
    assertEquals(LocalDateTime.of(2026, 9, 29, 3, 30), next, "下一个触发时刻不是次日 03:30：" + next);
    assertEquals(0, next.getSecond(), "秒位钉死在 0：03:30 与 03:30:59 是两个批次窗口");
  }

  // ==================================================== 2. 批次上限怎么夹

  @Test
  @DisplayName("上限只能被调小：配置写 10000 也夹到 MAX_BATCH，且读数永远多要一行")
  void configuredLimitIsClampedToMaxBatchAndReadAsksForOneMore() {
    FakeSource src = new FakeSource();
    FakePurger purger = new FakePurger();
    new DataRetentionJob(src, purger, props(true, 10000)).run(NOON, 0);
    assertEquals(DataRetentionJob.MAX_BATCH + 1, src.lastLimit(),
        "能被配置突破的上限不算上限：一批 200 个跨二十多张表的 DELETE 已经把演示环境锁不起了");
    assertEquals(200, DataRetentionJob.MAX_BATCH, "硬上界本身也要钉住：它改了就是口径变了，得有人先看见");

    FakeSource over = new FakeSource();
    new DataRetentionJob(over, new FakePurger(), props(true, 50)).run(NOON, 500);
    assertEquals(DataRetentionJob.MAX_BATCH + 1, over.lastLimit(), "显式传参同样夹在硬上界之下，不能开后门");

    FakeSource small = new FakeSource();
    new DataRetentionJob(small, new FakePurger(), props(true, 50)).run(NOON, 3);
    assertEquals(4, small.lastLimit(), "调小是允许的：读数仍是「上限加一」，靠它探测有没有人没排上");
  }

  @Test
  @DisplayName("传 0 或负数回落到配置值，配置也不许把批次夹成 0")
  void nonPositiveLimitFallsBackToConfiguredValue() {
    FakeSource src = new FakeSource();
    new DataRetentionJob(src, new FakePurger(), props(true, 50)).run(NOON, 0);
    assertEquals(51, src.lastLimit(), "0 表示用配置值，不是「一个都不处理」");

    FakeSource neg = new FakeSource();
    new DataRetentionJob(neg, new FakePurger(), props(true, 50)).run(NOON, -7);
    assertEquals(51, neg.lastLimit(), "负数同样回落：否则 min 之后变成负上限，批处理直接空转还报告成功");

    FakeSource zeroCfg = new FakeSource();
    new DataRetentionJob(zeroCfg, new FakePurger(), props(true, 0)).run(NOON, 0);
    assertEquals(2, zeroCfg.lastLimit(), "配置写 0 时批次夹到至少 1：清不到人要叫出来，不能安安静静什么都不做");
  }

  // ==================================================== 3. 截断：读到第 N+1 行才算有人被挤出去

  @Test
  @DisplayName("恰好等于上限不算截断；多出一行才算，而且多出来那个人本轮一次都不碰")
  void exactlyAtLimitIsNotTruncatedButOneRowTooManyIs() {
    FakeSource atLimit = new FakeSource();
    atLimit.reply(Arrays.asList(1L, 2L));
    DataRetentionJob.Summary a = new DataRetentionJob(atLimit, new FakePurger(), props(true, 2)).run(NOON, 2);
    assertFalse(a.truncated(), "读到 2 条、上限 2 条：读数只多要了一行，没多出来就说明没有人在外面等");
    assertEquals(2, a.due());

    FakePurger purger = new FakePurger();
    FakeSource over = new FakeSource();
    over.reply(Arrays.asList(1L, 2L, 3L));
    DataRetentionJob.Summary b = new DataRetentionJob(over, purger, props(true, 2)).run(NOON, 2);
    assertTrue(b.truncated(), "多读到第 3 行就是有人被挤出去了：清除有法定期限，这件事必须是可观测的事实");
    assertEquals(2, b.due(), "due 记的是本批真正处理的个数，不是候选总数");
    assertEquals(Arrays.asList(1L, 2L), purger.purged, "第 3 个人本轮一次都不该被碰：半删比不删更难查");
  }

  @Test
  @DisplayName("到期判定用的是调用方传进来的时刻，逐字发给端口")
  void momentIsPassedThroughVerbatim() {
    FakeSource src = new FakeSource();
    src.reply(List.of());
    new DataRetentionJob(src, new FakePurger(), props(true, 10)).run(NOON, 0);
    assertEquals(1, src.asked());
    assertEquals(NOON, src.firstMoment(), "「到期」唯一的可变输入就是这一刻：靠真时钟构造不了「刚好跨过 purge_at 那一秒」");

    DataRetentionJob.Summary summary = new DataRetentionJob(src, new FakePurger(), props(true, 10)).run(NOON, 0);
    assertEquals(NOON, summary.now(), "账上要能对回那一刻，否则日志与数据库时间对不上时没人知道是哪个批次");
    assertTrue(summary.costMillis() >= 0L, "耗时是这一批唯一的性能读数：" + summary.costMillis());
  }

  // ==================================================== 4. 逐用户隔离：全站唯一吞异常的地方

  @Test
  @DisplayName("第 2 个人清除失败，第 1、3 个照样删：失败计数进账，不重抛")
  void oneFailureDoesNotEndTheBatch() {
    FakePurger purger = new FakePurger();
    purger.failFor(22L);
    FakeSource src = new FakeSource();
    src.reply(Arrays.asList(11L, 22L, 33L));
    DataRetentionJob.Summary summary = new DataRetentionJob(src, purger, props(true, 10)).run(NOON, 0);

    assertEquals(3, summary.due());
    assertEquals(2, summary.purged(), "一个人的产物文件被占用，不该让另外两个人再多留三十天数据");
    assertEquals(1, summary.failed());
    assertFalse(summary.truncated());
    assertEquals(Arrays.asList(11L, 22L, 33L), purger.purged, "顺序就是端口返回的序（purge_at ASC, id ASC）：最先到期的最先删");
  }

  @Test
  @DisplayName("候选里混进 null、0、负 id：记为失败且绝不送给清除端口")
  void illegalCandidateIdsAreCountedAsFailedAndNeverPurged() {
    FakePurger purger = new FakePurger();
    FakeSource src = new FakeSource();
    src.reply(Arrays.asList(5L, null, 0L, -3L, 6L));
    DataRetentionJob.Summary summary = new DataRetentionJob(src, purger, props(true, 10)).run(NOON, 0);

    assertEquals(5, summary.due(), "due 是候选条数，含非法项：账要先能对上，才发现上游 SQL 有问题");
    assertEquals(2, summary.purged());
    assertEquals(3, summary.failed());
    assertEquals(Arrays.asList(5L, 6L), purger.purged,
        "id 为 0 或负数一旦送给 purgeOne，删的是不存在的行还是别人的行，取决于端口怎么写：这里当场拦住");
  }

  @Test
  @DisplayName("候选为空或为 null：一趟都不跑，也不抛")
  void emptyAndNullCandidateListsNeverCallPurger() {
    FakePurger empty = new FakePurger();
    FakeSource src = new FakeSource();
    src.reply(List.of());
    DataRetentionJob.Summary summary = new DataRetentionJob(src, empty, props(true, 10)).run(NOON, 0);
    assertEquals(0, summary.due());
    assertTrue(empty.purged.isEmpty());
    assertFalse(summary.truncated());

    FakeSource nullSrc = new FakeSource();
    nullSrc.reply(null);
    FakePurger boom = new FakePurger();
    DataRetentionJob.Summary second = assertDoesNotThrow(
        () -> new DataRetentionJob(nullSrc, boom, props(true, 10)).run(NOON, 0),
        "端口给 null 是数据问题，不是让定时线程抛出去然后下一次触发悄悄消失");
    assertEquals(0, second.due());
    assertTrue(boom.purged.isEmpty(), "没有候选就不能有删除：这是本类唯一「一条都不许动」的时刻");
  }

  // ==================================================== 5. 定时入口：开关、同一路径、整体失败不抛

  @Test
  @DisplayName("配置关掉时连候选读数都不发：不跑与跑了个空是两件不同的事")
  void disabledSwitchSkipsScheduledEntryWithoutEvenReadingCandidates() {
    FakeSource src = new FakeSource();
    src.reply(Arrays.asList(1L));
    FakePurger purger = new FakePurger();
    new DataRetentionJob(src, purger, props(false, 10)).scheduledRetention();
    assertEquals(0, src.asked(), "开关的意义是「今晚根本不该有 DELETE 在跑」：读一遍候选再丢掉，锁与日志都已经发生了");
    assertTrue(purger.purged.isEmpty());
  }

  @Test
  @DisplayName("定时入口与手动触发走同一条 run；端口整体抛错也只记日志不抛")
  void scheduledEntryRunsTheSameBatchAndSwallowsWholeBatchFailure() {
    FakeSource src = new FakeSource();
    src.reply(Arrays.asList(7L));
    FakePurger purger = new FakePurger();
    new DataRetentionJob(src, purger, props(true, 10)).scheduledRetention();
    assertEquals(Arrays.asList(7L), purger.purged, "定时与手动必须是同一段代码：否则演示时验证过的行为不是夜里真发生的行为");
    assertEquals(1, src.asked());
    assertEquals(11, src.lastLimit(), "定时入口把配置值原样交给 run：夹与多要一行都只有一处实现");

    DataRetentionJob.CandidateSource boom = (now, limit) -> {
      throw new IllegalStateException("库连不上");
    };
    assertDoesNotThrow(() -> new DataRetentionJob(boom, new FakePurger(), props(true, 10)).scheduledRetention(),
        "定时线程抛出未捕获异常会让下一次触发的行为取决于容器，这种事没人能从日志里看出来");
  }
}

package com.mindisle.emotion;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mindisle.config.MindisleProperties;

/**
 * 情绪周报定时任务（任务 T4.20 · 需求 FR3.5 · 手册 §7.5「T4.20」第 1 条）。
 *
 * <p><b>手册给的就是三条硬约束，逐条对应这里的实现</b>：
 * ① 周日晚 21:00 触发（{@code cron = "0 0 21 ? * SUN"}，可经
 * {@code mindisle.schedule.weekly-report-cron} 覆盖）；
 * ② 只处理<b>近 30 天有打卡</b>的用户（{@link CandidateSource} 那条 DISTINCT 读数）；
 * ③ <b>单批 ≤ 200 人</b>（{@link #MAX_BATCH} 是上界，配置值只能把它调小不能调大）。
 * ②③ 两条的存在理由都是钱：一次周报 = 一次 LLM 调用，「全表扫一遍每个人重算」
 * 在没有预算闸的地方会把 FR2.10 的日预算直接吃穿。</p>
 *
 * <p><b>为什么逐用户 try/catch</b>：这个循环里一次失败不该终结整批 —— 第 7 个人的
 * 情绪聚合抛错，剩下 193 个人照样要拿到周报。捕获之后只计数、不重抛，
 * 计数进 {@link Summary} 供管理端与日志核对。这与 {@code UserActionRecorder}
 * 「异常只在一处吞一次」是同一条纪律：吞的位置在批处理入口，
 * 适配器与 {@link WeeklyReportService} 都照常往上抛，否则「为什么这批少了三个人的周报」
 * 会同时有三个可能的沉默地点。</p>
 *
 * <p><b>为什么走两个端口而不是直接依赖 Mapper 和 Service</b>：与 {@code UserActionRecorder.Store}
 * 同一套路。本类真正需要被钉住的三件事 —— 候选窗口怎么算、批次上限怎么夹、单个失败会不会
 * 拖垮整批 —— 全是纯 Java 逻辑，用内存假实现就能逐条断言；
 * 而 {@code WeeklyReportService} 要 8 个协作者（含真库 Mapper 与 LLM 客户端），
 * 把它拉进单测就只能变成一条 {@code @SpringBootTest}，那条又会连真库、
 * 连真模型、真花钱。生产实现见 {@link WeeklyReportJobStore}。</p>
 */
@Component
public class WeeklyReportJob {

  private static final Logger log = LoggerFactory.getLogger(WeeklyReportJob.class);

  /** 手册 §7.5 第 1 条的「单批 ≤ 200 人」，是上界而不是缺省值：配置只能把它调小。 */
  static final int MAX_BATCH = 200;

  /** 手册 §7.5 第 1 条的「近 30 天有打卡」。30 天窗同时覆盖「上周整周 + 本周已过的几天」。 */
  static final int LOOKBACK_DAYS = 30;

  /** 候选读数端口：谁在这段窗口里主动打过卡。 */
  public interface CandidateSource {

    /**
     * @param fromDate 窗口起始日（含）
     * @param toDate   窗口结束日（含）
     * @param limit    最多返回多少个用户 id
     * @return 按 user_id 升序的候选名单
     */
    List<Long> checkinUserIds(LocalDate fromDate, LocalDate toDate, int limit);
  }

  /** 生成端口：给一个人重算当周周报。实现方负责落库（upsert，幂等）。 */
  public interface Generator {

    /** 失败时直接抛，由 {@link WeeklyReportJob} 在批处理入口统一捕获。 */
    void generate(long userId);
  }

  /**
   * 一趟批处理的账。管理端手动触发时这就是响应体，定时任务里它是那行 INFO 日志。
   *
   * <p>{@code truncated} 单独留一个字段：候选数被批次上限夹掉时，
   * 「今天没排上的人」必须是一个可观测的事实，而不是等下周有人问「为什么我没有周报」
   * 时再去猜。被夹掉的这些人下一趟仍会排在窗口里，所以最坏情况是晚一周拿到周报。
   * 判据是「向上层多要一行」：读 {@code batchLimit + 1} 条，真读到第 {@code batchLimit + 1} 条
   * 就说明有人被挤出去了 —— 用 {@code count(*)} 再查一遍也能得到这个数，但那是两次读数，
   * 中间新进一个用户就会让「候选数」与「实际处理数」对不上。</p>
   */
  public record Summary(LocalDate weekStart, LocalDate windowFrom, LocalDate windowTo, int candidates,
      int generated, int failed, boolean truncated, long costMillis) {
  }

  /** ISO 周口径：周日起都归到同一个周一（与 {@code WeeklyReportService#weekStart} 同源）。 */
  static LocalDate weekStartOf(LocalDate day) {
    return day.with(DayOfWeek.MONDAY);
  }

  private final CandidateSource candidates;
  private final Generator generator;
  private final MindisleProperties properties;

  public WeeklyReportJob(CandidateSource candidates, Generator generator,
      MindisleProperties properties) {
    this.candidates = candidates;
    this.generator = generator;
    this.properties = properties;
  }

  /**
   * 定时入口。cron 用占位符 + 内嵌缺省值，两个理由：管理端改配置不必发版；
   * 而 {@code application.yml} 缺这一段时任务照样按手册口径跑，不会因为
   * 占位符解析不到而在启动期抛 IllegalArgumentException（这个坑见 dev-log 阶段 2）。
   *
   * <p>这里 try/catch 是<b>整批</b>级别的最后一道：读数失败（库抖、SQL 报错）不该让
   * 调度线程崩掉，Spring 的默认错误处理器会把异常记进日志但任务照排下一次。
   * 我们仍自己吞一次，是为了留下「哪个窗口、几个人」这条上下文 —— 裸堆栈里没有这个数。</p>
   */
  @Scheduled(cron = "${mindisle.schedule.weekly-report-cron:0 0 21 ? * SUN}")
  public void scheduledWeekly() {
    MindisleProperties.Schedule cfg = properties.getSchedule();
    if (!cfg.isWeeklyReportEnabled()) {
      log.info("周报定时任务已被配置关闭（mindisle.schedule.weekly-report-enabled=false），本次跳过");
      return;
    }
    LocalDate today = LocalDate.now();
    try {
      Summary summary = run(today, cfg.getWeeklyReportBatchLimit());
      log.info("周报定时批次 week={} 窗口={}..{} 候选={} 生成={} 失败={} 被批次上限截断={} 耗时={}ms",
          summary.weekStart(), summary.windowFrom(), summary.windowTo(), summary.candidates(),
          summary.generated(), summary.failed(), summary.truncated(), summary.costMillis());
    } catch (Exception e) {
      log.error("周报定时批次整体失败 窗口={}..{} 原因={}", today.minusDays(LOOKBACK_DAYS), today,
          e.toString(), e);
    }
  }

  /**
   * 跑一批。定时与手动触发（{@code POST /api/emotions/weekly-report/run}）共用这一条路径，
   * 这样「手动验证过的行为」与「周日夜里真正发生的行为」是同一段代码，不是两份。
   *
   * @param limit 本批人数上限；{@code <= 0} 表示用配置值。无论传什么，最终不超 {@link #MAX_BATCH}
   */
  public Summary run(LocalDate today, int limit) {
    long begin = System.currentTimeMillis();
    LocalDate weekStart = weekStartOf(today);
    LocalDate from = today.minusDays(LOOKBACK_DAYS);
    int batchLimit = Math.min(limit > 0 ? limit : properties.getSchedule().getWeeklyReportBatchLimit(),
        MAX_BATCH);
    List<Long> read = candidates.checkinUserIds(from, today, batchLimit + 1);
    boolean truncated = read != null && read.size() > batchLimit;
    List<Long> ids = truncated ? read.subList(0, batchLimit) : read;
    if (ids == null || ids.isEmpty()) {
      log.info("周报批次没有候选 近{}天 窗口={}..{} 上限={}", LOOKBACK_DAYS, from, today, batchLimit);
      return new Summary(weekStart, from, today, 0, 0, 0, truncated, System.currentTimeMillis() - begin);
    }
    int ok = 0;
    int bad = 0;
    for (Long userId : ids) {
      if (userId == null || userId <= 0) {
        bad++;
        log.warn("周报候选里出现非法 id，跳过 raw={}", userId);
        continue;
      }
      try {
        generator.generate(userId);
        ok++;
      } catch (Exception e) {
        bad++;
        log.warn("单人生成失败 user={} 原因={}（继续处理下一位）", userId, e.toString());
      }
    }
    long cost = System.currentTimeMillis() - begin;
    log.info("周报批次完成 week={} 候选={} 成功={} 失败={} 截断={} 耗时={}ms", weekStart, ids.size(), ok,
        bad, truncated, cost);
    return new Summary(weekStart, from, today, ids.size(), ok, bad, truncated, cost);
  }
}

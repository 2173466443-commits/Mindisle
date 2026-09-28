package com.mindisle.privacy;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mindisle.config.MindisleProperties;

/**
 * 冷静期届满账号的到期清除任务（任务 T4.21 · 需求 FR1.6「注销满 30 天后自动删除」· 手册 §7.5 状态机）。
 *
 * <p><b>骨架照 {@code WeeklyReportJob}，但两件事正好相反，值得写清</b>：
 * ① 周报是「算一份新数据」，失败可以重跑且不改变现状；这里是<b>删数据</b>，失败的后果不对称 ——
 *   整批不回滚会留下「帖子没了、人还在」的半删状态，所以清除的单个人事务由
 *   {@link PrivacyPurgeService#purgeOne} 自己管，本类只负责「一个人失败不牵连别人」；
 * ② 周报的批次上限是为了省钱（一次调用 = 一次 LLM 计费），这里的上限是为了<b>锁的时间</b>：
 *   一个账号的清除是跨二十多张表的 DELETE，200 个账号连成一串会把库锁在演示时间之外的一整段里。
 *   所以缺省值配成 50（比周报的 200 小），而 {@link #MAX_BATCH} 仍是硬上界，配置只能调小不能调大 ——
 *   能被配置突破的闸不是闸。</p>
 *
 * <p><b>为什么逐用户 try/catch，且这是全站唯一吞异常的地方</b>：第 7 个人的清除因为一个锁着的产物文件失败，
 * 剩下的人照样要到期删除。捕获之后只计数、不重抛，计数进 {@link Summary} ——
 * 而这些人明晚还会重新排在队列里（他们的 {@code status} 仍是 DELETED、{@code purge_at} 仍已届满），
 * 所以失败是「可重试且必然重现」的，不是一个会被静默吃掉的一夜事故。
 * 端口与 {@link PrivacyPurgeService} 都照常往上抛，否则「今晚为什么少了三个人的清除」
 * 就会同时有三个都会沉默的地点（与周报同一条纪律）。</p>
 *
 * <p><b>为什么走两个端口而不是直接依赖 Mapper 和 Service</b>：与 {@code WeeklyReportJob.CandidateSource} 同一套路。
 * 本类真正需要钉死的四件事 —— 到期判定用哪个时刻、批次上限怎么夹、单人失败会不会拖垮整批、
 * 开关关掉时是否连读数都不发 —— 全是纯 Java 逻辑，用内存假实现就能逐条断言；
 * 而 {@link PrivacyPurgeService} 要 5 个协作者（含真库 Mapper 与 Redis 侧的 {@code JwtService}），
 * 把它拉进单测就只能变成一条会真删开发库数据的 {@code @SpringBootTest}。生产实现见 {@link DataRetentionJobStore}。</p>
 */
@Component
public class DataRetentionJob {

  private static final Logger log = LoggerFactory.getLogger(DataRetentionJob.class);

  /** 单批账号数硬上界（配置值只能把它调小）。见类注释第 ② 条。 */
  static final int MAX_BATCH = 200;

  /**
   * 候选读数端口：谁的冷静期已经届满。
   *
   * <p>{@code now} 由调用方传入而不是端口内部取当前时间：这是「到期」这条判定唯一的可变输入，
   * 测试要能构造「刚好跨过 purge_at 那一秒」的边界，靠真时钟做不到。</p>
   */
  public interface CandidateSource {

    /** @param now   判定到期用的时刻
     * @param limit   最多返回多少个账号 id（调用方传 {@code batchLimit + 1} 以探测截断）
     * @return 按 {@code purge_at ASC, id ASC} 全序排列的到期账号 id */
    List<Long> duePurgeUserIds(LocalDateTime now, int limit);
  }

  /** 清除端口：把一个人删干净。失败直接抛，由 {@link DataRetentionJob} 在批处理入口统一捕获。 */
  public interface Purger {

    void purgeOne(long userId);
  }

  /**
   * 一趟批处理的账。
   *
   * <p>{@code truncated} 单独留一个字段：到期人数被批次上限夹掉时，「今晚没排上的人」必须是一个
   * 可观测的事实，而不是等三个月后有人问「为什么我注销了半年数据还在」时再去猜。
   * 判据同样是「向上层多要一行」：读 {@code batchLimit + 1} 条，真读到第 {@code batchLimit + 1} 条
   * 就说明有人被挤出去了。这里不能像周报那样等下周补上 —— 清除是被法定期限驱动的，
   * 所以被截断时日志级别是 warn 而不是 info。</p>
   */
  public record Summary(LocalDateTime now, int due, int purged, int failed, boolean truncated,
      long costMillis) {
  }

  private final CandidateSource candidates;
  private final Purger purger;
  private final MindisleProperties properties;

  public DataRetentionJob(CandidateSource candidates, Purger purger, MindisleProperties properties) {
    this.candidates = candidates;
    this.purger = purger;
    this.properties = properties;
  }

  /**
   * 定时入口，缺省每天 03:30:00（{@code ${mindisle.schedule.retention-cron} 可覆盖）。
   *
   * <p>cron 用「占位符 + 内嵌缺省值」两个理由与周报一致：改配置不必发版；
   * {@code application.yml} 缺这一段时任务照样按手册口径跑，不会在启动期抛 IllegalArgumentException。
   * 选凌晨三点：与周报的周日 21:00 错开，也避开在线高峰。</p>
   */
  @Scheduled(cron = "${mindisle.schedule.retention-cron:0 30 3 * * ?}")
  public void scheduledRetention() {
    MindisleProperties.Schedule cfg = properties.getSchedule();
    if (!cfg.isRetentionEnabled()) {
      log.info("到期清除任务已被配置关闭（mindisle.schedule.retention-enabled=false），本次跳过");
      return;
    }
    try {
      Summary summary = run(LocalDateTime.now(), cfg.getRetentionBatchLimit());
      log.info("到期清除批次 now={} 到期={} 已清除={} 失败={} 被批次上限截断={} 耗时={}ms",
          summary.now(), summary.due(), summary.purged(), summary.failed(), summary.truncated(),
          summary.costMillis());
    } catch (Exception e) {
      log.error("到期清除批次整体失败 原因={}", e.toString(), e);
    }
  }

  /**
   * 跑一批。定时与手动触发（{@code POST /api/privacy/retention/run}，仅管理员）共用这一条路径，
   * 这样「演示时手动验证过的行为」与「夜里真正发生的行为」是同一段代码，不是两份。
   *
   * @param limit 本批账号上限；{@code <= 0} 表示用配置值。无论传什么，最终不超 {@link #MAX_BATCH}
   */
  public Summary run(LocalDateTime now, int limit) {
    long begin = System.currentTimeMillis();
    LocalDateTime moment = now == null ? LocalDateTime.now() : now;
    int batchLimit = Math.min(limit > 0 ? limit : properties.getSchedule().getRetentionBatchLimit(),
        MAX_BATCH);
    batchLimit = Math.max(1, batchLimit);
    List<Long> read = candidates.duePurgeUserIds(moment, batchLimit + 1);
    boolean truncated = read != null && read.size() > batchLimit;
    List<Long> ids = truncated ? read.subList(0, batchLimit) : read;
    if (ids == null || ids.isEmpty()) {
      log.info("到期清除批次没有候选 now={} 上限={}", moment, batchLimit);
      return new Summary(moment, 0, 0, 0, truncated, System.currentTimeMillis() - begin);
    }
    if (truncated) {
      log.warn("到期账号数超过本批上限，已截断 now={} 本批={} 上限={}（未排上的明晚继续）"
          + " —— 清除有法定期限，长期截断需要人工介入"
          + "（把 mindisle.schedule.retention-batch-limit 调大或提高执行频率）",
          moment, batchLimit, MAX_BATCH);
    }
    int ok = 0;
    int bad = 0;
    for (Long userId : ids) {
      if (userId == null || userId <= 0) {
        bad++;
        log.warn("到期候选里出现非法 id，跳过 raw={}", userId);
        continue;
      }
      try {
        purger.purgeOne(userId);
        ok++;
      } catch (Exception e) {
        bad++;
        log.error("单人清除失败 user={} 原因={}（继续处理下一位，此人明晚重新排队）", userId, e.toString());
      }
    }
    long cost = System.currentTimeMillis() - begin;
    log.info("到期清除批次完成 now={} 候选={} 成功={} 失败={} 截断={} 耗时={}ms",
        moment, ids.size(), ok, bad, truncated, cost);
    return new Summary(moment, ids.size(), ok, bad, truncated, cost);
  }
}

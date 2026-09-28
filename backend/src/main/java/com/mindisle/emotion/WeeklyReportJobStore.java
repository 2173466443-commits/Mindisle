package com.mindisle.emotion;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

import com.mindisle.mapper.EmotionRecordMapper;

/**
 * {@link WeeklyReportJob} 两个端口的生产实现（任务 T4.20）。
 *
 * <p><b>为什么一个类实现两个端口</b>：{@code CandidateSource} 与 {@code Generator} 是同一次批处理的
 * 两侧 —— 一侧读数（谁该收到周报），一侧干活（给这个人重算一份）。把它们拆成两个 bean，
 * 装的是同一批依赖（{@code EmotionRecordMapper} / {@code WeeklyReportService}），
 * 换来的只是「一次任务跑要装两个类」，而端口拆分的收益（各自的内存假实现）
 * 在测试侧照样成立：测试里是一个内部类同时实现两个接口。
 * 拆开的唯一理由会是「两侧要分属不同模块」，这里两侧都在情绪域内。</p>
 *
 * <p><b>本类不吞异常</b>：与 {@code UserActionStoreAdapter} 同一条纪律 ——
 * 「异常只在一处吞一次」，吞的位置是 {@link WeeklyReportJob#run} 的逐用户 try/catch。
 * 这里再包一层 try/catch，「这批为什么少了三个人的周报」就有了两个都会沉默的地点。
 * 同理，这里也不做「查不到就当 0 个人」的兜底：读数为空是真实结果，
 * 而抛错被静默成空列表，会让一个整周失败的批次在日志里看起来像「本周没人需要周报」。</p>
 */
@Component
public class WeeklyReportJobStore implements WeeklyReportJob.CandidateSource, WeeklyReportJob.Generator {

  private final EmotionRecordMapper recordMapper;
  private final WeeklyReportService weeklyReportService;

  public WeeklyReportJobStore(EmotionRecordMapper recordMapper,
      WeeklyReportService weeklyReportService) {
    this.recordMapper = recordMapper;
    this.weeklyReportService = weeklyReportService;
  }

  /**
   * 「近 30 天有打卡」的读数口径见 {@link EmotionRecordMapper#listCheckinUserIds}：
   * 只认 {@code source='checkin'}，被动识别不算「这个人主动打过卡」。
   */
  @Override
  public List<Long> checkinUserIds(LocalDate fromDate, LocalDate toDate, int limit) {
    return recordMapper.listCheckinUserIds(fromDate, toDate, limit);
  }

  /**
   * 复用读侧那一条 {@code report(userId, "current", true)}，不另开一条「批处理专用」的生成路径：
   * 两条路径共用同一个 upsert 口，定时任务夜里写的行和用户在界面上点「再算一次」写的行
   * 才是同一份口径，不会撞出两种 summary_json 结构。
   *
   * <p>{@code refresh=true} 是必须的 —— 定时任务的语义就是「重算」，
   * 若走默认的读时缓存，周日 21:00 这一趟只要当天白天有人点开过周报就会整批命中缓存，
   * 批次跑完而没有任何一行被更新。</p>
   */
  @Override
  public void generate(long userId) {
    weeklyReportService.report(userId, "current", true);
  }
}

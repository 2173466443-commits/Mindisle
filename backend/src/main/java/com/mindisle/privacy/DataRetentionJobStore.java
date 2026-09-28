package com.mindisle.privacy;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

import com.mindisle.mapper.UserMapper;

/**
 * {@link DataRetentionJob} 两个端口的生产实现（任务 T4.21）。
 *
 * <p><b>为什么一个类实现两个端口</b>：与 {@code WeeklyReportJobStore} 同一个理由 ——
 * 「读谁该被清除」和「清除这个人」是同一次批处理的两侧，装的是同一批依赖（{@code UserMapper} /
 * {@link PrivacyPurgeService}），拆成两个 bean 只多出「一个任务两个类」，
 * 而端口拆分在测试侧的收益（两个内存假实现）不受影响。</p>
 *
 * <p><b>本类不吞异常</b>：全站只有 {@link DataRetentionJob#run} 的逐用户 try/catch 一处吞。
 * 这里若包一层「清除失败就当没失败」，日志里的成功数就会比真实删除数大，
 * 而这条数字正是 Gate4「清除后逐表复查为 0」的证据来源。</p>
 *
 * <p><b>{@code @Transactional} 为什么还能生效</b>：{@link PrivacyPurgeService#purgeOne} 是代理 bean 上的方法，
 * 本类跨 bean 调用它，事务切面照常拦截；{@code DataRetentionJob} 自己没有开事务 ——
 * 一个账号一个事务才是这里的正确粒度（整批一个事务的话，第 200 个失败会把前 199 个已成功的清除一起回滚，
 * 而日志里已经打印过它们成功了）。</p>
 */
@Component
public class DataRetentionJobStore implements DataRetentionJob.CandidateSource, DataRetentionJob.Purger {

  private final UserMapper userMapper;
  private final PrivacyPurgeService purgeService;

  public DataRetentionJobStore(UserMapper userMapper, PrivacyPurgeService purgeService) {
    this.userMapper = userMapper;
    this.purgeService = purgeService;
  }

  /**
   * 到期候选读数。状态值从 {@link CoolingState#DELETED} 取，不写进 SQL 文本 ——
   * 与 {@code ExportTaskMapper} 四条状态改写同一条纪律（值只存在于常量里，改一处不会漏另一处）。
   *
   * <p>{@code limit} 在这里再夹一次 {@code Math.max(1, ...)}：任务侧已经夹过，
   * 但 SQL 里那句 {@code LIMIT #{limit}} 拿到 0 或负数不会报错、只会静默返回空集，
   * 而「空集」在批处理日志里长得和「今晚没有到期的人」一模一样。宁可让它显式地读到 1 个人。</p>
   */
  @Override
  public List<Long> duePurgeUserIds(LocalDateTime now, int limit) {
    return userMapper.listDuePurgeUserIds(CoolingState.DELETED, now, Math.max(1, limit));
  }

  /** 清除一个人。异常原样上抛，由任务侧计入失败数并继续下一位。 */
  @Override
  public void purgeOne(long userId) {
    purgeService.purgeOne(userId);
  }
}

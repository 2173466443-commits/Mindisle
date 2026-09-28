package com.mindisle.privacy;

import java.util.List;
import java.util.Map;

import com.mindisle.privacy.PrivacyDomains.Domain;

/**
 * 隐私域读数端口（任务 T4.21）。五个方法，一张注册表驱动全部 34 张表。
 *
 * <p><b>为什么要抽这个端口而不是让服务直接注入 JdbcTemplate</b>：导出、概览、物理清除三件事
 * 共用同一套「按注册表拼归属谓词」的动作，而这套动作里真正需要被钉死的判断是
 * 「哪张表算这个人的」「软删行算不算」「post 集合要先算」—— 全是纯逻辑。
 * 有了这层端口，这些判断能用一个内存假实现逐条断言，不必连真库；
 * 而真库那侧（{@link JdbcPrivacyStore}）只剩「拼 SQL 与执行」这一件可以被肉眼复核的事。
 * 这与 {@code WeeklyReportJob.CandidateSource}、{@code UserActionRecorder.Store} 是同一套路。</p>
 *
 * <p><b>本端口不吞异常</b>：与全站同一条纪律（吞的位置只有一个 ——
 * 定时批处理入口 {@code DataRetentionJob#run} 的逐用户 try/catch）。
 * 这里再包一层，「这个人为什么只删了一半」就会出现两个都会沉默的地点。</p>
 *
 * <p><b>postIds 由调用方传入而不是每个方法现查</b>：物理清除必须按
 * 「先算 post 集合 → 删 Link.POST 的行 → 最后删 post 行」这个顺序走，
 * 顺序一旦由端口内部各自查库决定，删完 post 主行之后附属表就再也算不出集合了。
 * 把集合变成参数，等于把「必须先算」这件事写进方法签名。</p>
 */
public interface PrivacyStore {

  /**
   * 这个人的帖子 id 集合。<b>不带 {@code deleted = 0}</b>：软删帖的行同样是他的个人数据，
   * 同样要进导出包、同样要被物理清除。
   *
   * <p>物理清除时必须在删 post 行之前调用它，{@link PrivacyPurgeService} 的注释里钉了这个顺序。</p>
   */
  List<Long> postIdsOf(long userId);

  /**
   * 读整行，用于导出。列名到值的映射由驱动决定（{@code SELECT *}）。
   *
   * @param limit 单表行数上限（配置项）。读到 limit 行就停：导出包不该无上限地长大，
   *              而截断这件事由调用方按「返回行数 == limit」判定并记进对账，不留成静默行为
   */
  List<Map<String, Object>> selectRows(Domain domain, long userId, List<Long> postIds, int limit);

  /** 数行，用于「我的数据概览」与导出后的条数对账。与 selectRows 用同一条归属谓词，这是 D11 的判据。 */
  long countRows(Domain domain, long userId, List<Long> postIds);

  /** 物理删除该人的行。返回真正被删掉的行数（0 是合法结果：这个人在这张表上本来就没有数据）。 */
  int deleteRows(Domain domain, long userId, List<Long> postIds);

  /**
   * 解绑身份：把 {@code domain.unbindCols()} 的每一列改写成
   * {@link PrivacyDomains#UNBOUND}，行保留。用于审计留痕表。
   *
   * <p>哨兵值取 0 而不是 NULL 的理由写在 {@link PrivacyDomains#UNBOUND} 上，不重复一遍。</p>
   */
  int unbind(Domain domain, long userId);
}

package com.mindisle.emotion;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.entity.WeeklyReport;

/**
 * 情绪周报的「去标识分享」（任务 T4.20 ③ · 手册 §7.5 第 3 条 · 需求 FR3.5、BR13）。
 *
 * <p><b>手册那一条只有两句话，这里逐句对应</b>：「只带昵称或马甲名，不带情绪原始明细」
 * 由 {@link #shareContent} 的正文口径加 {@link Publisher} 侧强制匿名实现；
 * 「shared_flag = 1 时生成一条普通 post 并走完整审核链」由 {@link #share} 走
 * {@link Publisher}（生产实现是 {@code PostService#publish}，那里有配额、字段合规、
 * DFA 机审、危机建单全套，<b>本类一行都不复制</b>）。</p>
 *
 * <p><b>正文里只放三样东西</b>：周区间、{@code summaryText}、生成方式那一句。
 * 刻意不放的东西同样重要，而且必须写在这里而不是「忘了放」：
 * ① {@code insight}（那是 counts / trustedDays / meanValence 的 JSON，属于情绪明细，
 * 是给图表用的原始统计）；② checkin_days / record_cnt / avg_intensity / positive_ratio /
 * trend_delta 五个数值列；③ {@code dominant_label}；④ 任何一条打卡原文
 * （{@code emotion_record.text_snippet}）。
 * 理由不是「数字不好看」，而是<b>这些字段在库里的用途是「被自己统计」，不是「被公开」</b>：
 * NFR8 数据最小化的判断标准是「公开这一条之后，别人能多推出关于这个人的什么」。
 * 周报结论是一段自然语言，读者最多知道「这个人这一周心情如何」——这正是分享者想说的话；
 * 而一串结构化数值是可以被机器再聚合的（连上马甲名与发帖时间，就能对同一个人做时序再识别），
 * 所以数值一律不进正文。</p>
 *
 * <p><b>为什么是端口而不是直接注入 PostService 与 WeeklyReportMapper</b>：
 * 与 {@link WeeklyReportJob} 同一条纪律。本类真正要钉住的五件事 ——
 * 谁能分享、分享过不能再发第二条、正文里不能出现哪些字段、发帖参数、失败时不写
 * shared_flag —— 全是纯 Java 逻辑，内存假实现逐条能断言；而 {@code PostService}
 * 有 8 个协作者（真库 + 配额 + 机审 + 马甲 + 危机建单），把它拉进单测只能变成
 * {@code @SpringBootTest}，那会真发帖、真花钱、真往演示库里插垃圾行。
 * 生产实现见 {@link WeeklyReportShareStore}。</p>
 *
 * <p><b>幂等放在这里而不是放在 SQL 里</b>：shared_flag=1 且 shared_post_id 非空时直接回放
 * 原帖 id。判据来自用户侧的真实动作 —— 双击「分享」按钮、网络抖了再点一次、
 * 换个入口再点一次，这三种都不该产生第二篇帖子（社区里出现同一份周报的两条帖子，
 * 比多发一条更糟：它看起来像「这个人在刷屏」）。数据库层也能靠唯一键做到，
 * 但那是「一个人的一周只能有一篇分享帖」这种只存在于约束里的规则，
 * 出问题时表现为一次 1062 而不是一个可解释的响应。</p>
 */
@Service
public class WeeklyReportShareService {

  /** 分享帖标题的固定前缀，答辩与检索都靠这一句认出「这是周报分享帖」。 */
  static final String TITLE_PREFIX = "情绪周报 · ";

  /** 正文末尾的口径声明。<b>不可省略</b>：「本系统不做诊断」这条红线要落在公开内容上。 */
  static final String DISCLAIMER = "这条周报来自「心屿」的情绪记录，是我自己这一周的心情描述，"
      + "不是医学或心理学诊断，也不构成任何结论。心屿不做诊断。"
      + "\n分享已去标识：不带打卡原文、情绪分布明细，也不带任何可以直接识别到我身份的字段。";

  /** 周报行读写端口（生产实现走 {@code WeeklyReportMapper}）。 */
  public interface ReportStore {

    /**
     * 按主键读一行，<b>只读没被逻辑删除的行</b>：deleted=1 的周报已经不属于用户了，
     * 还让它被分享出去，等于绕过隐私中心的删除动作。
     *
     * @return 读不到返回 {@code null}，不要返回一个空对象
     */
    WeeklyReport findById(long reportId);

    /**
     * 把 shared_flag 置 1 并回填帖子 id。
     *
     * <p>只在 {@link Publisher#publish} 成功返回之后调用。反过来先写标记再发帖，
     * 一旦发帖抛错（配额满、被机审拦下、库抖）就会留下一个「界面上显示已分享、
     * 其实广场上没有」的永久状态 —— 而用户此时已经不能再点一次了。</p>
     */
    void markShared(long reportId, Long postId);
  }

  /** 发帖端口。生产实现 = {@code PostService#publish}，因此分享自动享有完整审核链。 */
  public interface Publisher {

    /**
     * @param userId 发帖人（周报的作者本人，<b>不是</b>马甲的 id：马甲只影响展示名，
     *               帖子的归属、配额、审核工单仍然要记在真人身上，否则举报与申诉没有主体）
     * @return 帖子 id 与发帖结果
     * @throws RuntimeException 任何发帖失败一律直接抛，由调用方看到真实错误；本类不吞
     */
    Posted publish(long userId, String title, String content, LocalDateTime now);

    /**
     * @param postId      新帖 id，非空
     * @param postStatus  发帖状态机终态（DRAFT / MACHINE_REVIEW / HUMAN_REVIEW / PUBLISHED /
     *                    REJECTED），<b>原样回显给用户</b>：机审没放行时谎称「已发布」比失败更糟
     * @param displayName 马甲名（分享恒匿名，所以这里不会是昵称）
     * @param tip         审核链给作者的那句话，可能为 null
     */
    record Posted(Long postId, String postStatus, String displayName, String tip) {
    }
  }

  /**
   * 分享结果。
   *
   * @param reportId      周报行 id
   * @param postId        对应的帖子 id（首次分享=新帖，二次分享=原帖）
   * @param weekStart     周报覆盖的周一
   * @param weekEnd       周报覆盖的周日
   * @param alreadyShared true 表示「本次没有新发帖，回放的是原来那一篇」（幂等）
   * @param postStatus    仅首次分享时有值；回放时为 null，帖子的权威状态请看帖子详情接口
   * @param displayName   同上
   * @param tip           同上
   *
   * <p>回放那一路<b>不重新读帖子</b>去补 postStatus/displayName：那会需要第三个端口，
   * 而 {@code PostQueryService} 本来就有「按 id 取一条帖」的权威口径，
   * 在这里复制第二份必然产生口径分裂（阶段 3 的「评论数分裂」就是这么来的）。</p>
   */
  public record ShareView(Long reportId, Long postId, String weekStart, String weekEnd,
      boolean alreadyShared, String postStatus, String displayName, String tip) {
  }

  private final ReportStore store;
  private final Publisher publisher;

  public WeeklyReportShareService(ReportStore store, Publisher publisher) {
    this.store = store;
    this.publisher = publisher;
  }

  /**
   * 把一份周报分享成一篇去标识的公开帖。
   *
   * @param userId   当前登录用户 id
   * @param reportId 周报行 id
   * @param now      时间基准，由调用方传入（与 {@code PostService#publish} 同一纪律：
   *                 配额窗口、发布时间、落库时间必须落在同一个刻度上）
   */
  public ShareView share(long userId, long reportId, LocalDateTime now) {
    WeeklyReport row = store.findById(reportId);
    if (row == null) {
      // 90006 而不是 30001：这条 id 指的是「周报」这个资源，不是帖子。
      throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    // 越权闸写在服务层，不靠前端把按钮藏起来：改一下 network 面板就能拿别人的 reportId 打这条接口，
    // 而分享出来的内容是他人的心情记录 —— 这是本项目里最贵的一类越权（NFR8 / BR13）。
    if (row.getUserId() == null || row.getUserId().longValue() != userId) {
      throw new BizException(ErrorCode.FORBIDDEN);
    }
    boolean replay = Integer.valueOf(1).equals(row.getSharedFlag())
        && row.getSharedPostId() != null;
    if (replay) {
      return new ShareView(row.getId(), row.getSharedPostId(), str(row.getWeekStart()),
          str(row.getWeekEnd()), true, null, null, null);
    }
    String title = shareTitle(row);
    String content = shareContent(row);
    Publisher.Posted posted = publisher.publish(userId, title, content, now);
    if (posted == null || posted.postId() == null) {
      // 端口契约被破坏时绝不写 shared_flag：宁可让用户再点一次，
      // 也不要留下一个「说已分享但没人能看到」的行。这里抛错是刻意的，不静默成功。
      throw new BizException(ErrorCode.INTERNAL_ERROR,
          "分享没有拿到帖子 id，周报没有被标记为已分享");
    }
    store.markShared(row.getId(), posted.postId());
    return new ShareView(row.getId(), posted.postId(), str(row.getWeekStart()),
        str(row.getWeekEnd()), false, posted.postStatus(), posted.displayName(), posted.tip());
  }

  /** 标题：固定前缀加周区间。<b>不含</b>用户名、主导情绪与任何统计数字。 */
  static String shareTitle(WeeklyReport row) {
    return TITLE_PREFIX + str(row.getWeekStart()) + " ~ " + str(row.getWeekEnd());
  }

  /**
   * 正文：周区间 + {@code summaryText} + 生成方式一句 + 口径声明，见类注释「只放三样」。
   *
   * <p>{@code generator} 必须写出来 —— 模板冒充模型结论是 FR3.5 明令禁止的一件事，
   * 与 {@code WeeklyReportView.generator} 同一口径。公开内容里说清楚「这段是 AI 写的」，
   * 也是生成式 AI 服务标识要求的一部分。</p>
   */
  static String shareContent(WeeklyReport row) {
    String summary = row.getSummaryText() == null ? "" : row.getSummaryText().trim();
    if (summary.isEmpty()) {
      throw new BizException(ErrorCode.PARAM_INVALID, "这份周报还没有结论文案，请先生成再分享");
    }
    String how = "llm".equals(row.getGenerator())
        ? "上面这段文字由 AI 陪伴模型撰写"
        : "上面这段文字由本地模板按我这周的打卡统计生成";
    return "这一周（" + str(row.getWeekStart()) + " ~ " + str(row.getWeekEnd())
        + "）我在心屿记下了一份情绪周报，现在把它去标识之后分享出来：\n\n"
        + summary + "\n\n" + how + "。\n" + DISCLAIMER;
  }

  /** 空值一律渲染成空串而不是字面量 "null"：这是要发给全社区看的文字。 */
  private static String str(Object v) {
    return v == null ? "" : v.toString();
  }
}

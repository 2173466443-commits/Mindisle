package com.mindisle.emotion;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.entity.WeeklyReport;
import com.mindisle.mapper.WeeklyReportMapper;
import com.mindisle.post.PostService;
import com.mindisle.post.dto.CreatePostRequest;
import com.mindisle.post.dto.PostView;

/**
 * {@link WeeklyReportShareService} 两个端口的生产实现（任务 T4.20 ③）。
 *
 * <p>结构与 {@link WeeklyReportJobStore} 一致：一个类同时满足读侧与写侧两个端口，
 * 因为两侧服务的是同一个动作（分享一份周报），拆成两个 bean 只会多出一次装配，
 * 而测试侧的内存假实现照样是一个内部类满足两个接口。</p>
 *
 * <p><b>发帖参数全部锁死在这个类里，不给调用方留口子</b>：{@code type=normal}、
 * {@code anonymous=true}、{@code visibility} 传 null 走配置默认、不挂话题、不带图、
 * {@code autoDestroyHours=null}。这几件事各有理由：</p>
 * <ul>
 * <li>{@code normal} —— 手册写的是「生成一条<b>普通</b> post」。树洞帖（hole）默认 7 天销毁，
 * 分享出去的周报会在两周后凭空消失，而界面上仍然写着「已分享」；求助帖（help）
 * 会进危机通道，一份心情周报不是求助。</li>
 * <li>{@code anonymous=true} —— 手册「只带昵称或马甲名」里本项目选马甲。
 * 理由是「我这周很难过」这类内容与账号真实身份绑在一起时，敏感程度远高于两者单独看；
 * 而 {@code PostService} 在匿名时自动走 {@code AnonymousAliasService} 取场景马甲
 * （normal → scene=ALL），所以这里<b>不需要</b>自己拼马甲名，也就不会造出第二套展示名口径。</li>
 * <li>{@code autoDestroyHours=null} —— 非树洞帖传这个值会被 {@code PostService} 直接判参数错，
 * 分享会在答辩现场变成一个 10001。</li>
 * <li>不挂话题 —— 话题要求「已存在且已过审」，替用户挑一个 #情绪周报 话题等于由系统发起建话题，
 * 那是阶段 3 遗留的「话题预审没有放行通道」会卡住的地方，不该由这条接口去撞。</li>
 * </ul>
 *
 * <p><b>本类不吞异常</b>：{@code PostService#publish} 抛出来的配额超限、内容被拦、参数错，
 * 原样给到 {@link WeeklyReportShareService}，再由它交给控制器与全局异常处理器。
 * 分享这件事的失败必须是可解释的（「今天发帖配额用完了」），
 * 不能是界面上一个没有任何反应的按钮。</p>
 */
@Component
public class WeeklyReportShareStore implements WeeklyReportShareService.ReportStore,
    WeeklyReportShareService.Publisher {

  private final WeeklyReportMapper reportMapper;
  private final PostService postService;

  public WeeklyReportShareStore(WeeklyReportMapper reportMapper, PostService postService) {
    this.reportMapper = reportMapper;
    this.postService = postService;
  }

  /**
   * 走 {@code WeeklyReportMapper#findById} 而不是 {@code BaseMapper#selectById}：
   * 后者不带 {@code deleted = 0}（{@code @TableLogic} 只在 MP 自己生成的语句里生效，
   * 而这里为了「已删除的周报不能被分享出去」专门写的那条 SQL 必须显式判）。
   * 口径与 {@code findByUserWeek} 一致。
   */
  @Override
  public WeeklyReport findById(long reportId) {
    return reportMapper.findById(reportId);
  }

  @Override
  public void markShared(long reportId, Long postId) {
    int rows = reportMapper.markShared(reportId, postId);
    if (rows != 1) {
      // 0 行 = 这一行在「判存在」与「写标记」之间被删掉了（发帖那几百毫秒里用户完全可以去隐私中心删数据）。
      // 这里必须抛而不是静默：帖子已经发出去了，只有把 postId 说给用户，他才能自己去把它删掉；
      // 界面上写「分享成功」而库里两份关联都没有，等于把一个孤儿帖留给了社区。
      throw new BizException(ErrorCode.INTERNAL_ERROR,
          "周报的分享标记没有写成（这一行可能刚刚被删除）。帖子已经发出，id=" + postId
              + "，如需撤回请到「我的帖子」里删除它");
    }
  }

  /**
   * 复用 {@code PostService#publish}：账号状态与当日配额、字段合规、配图与话题校验、
   * DFA 机审、状态机终态、危机建单，一条都不绕过（手册「并走完整审核链（T3.2）」）。
   *
   * <p>{@code now} 一路传下去而不是在这里重新取一次：分享出去这一刻的发布时间、
   * 配额窗口、{@code auto_destroy_at} 必须落在同一个刻度上（同 {@code PostService} 的纪律）。</p>
   */
  @Override
  public Posted publish(long userId, String title, String content, LocalDateTime now) {
    PostView view = postService.publish(userId, shareRequest(title, content), now);
    return new Posted(view.id(), view.status(), view.displayName(), view.tip());
  }

  /**
   * 分享帖的入参，锁成一条包级静态方法（<b>不留在 {@code publish} 的方法体里</b>）：
   * 这七个实参就是「去标识」这件事在协议层的全部内容，它们必须能被一条不连库、
   * 不连模型、不发真帖的断言钉住 —— {@code WeeklyReportShareServiceTest} 第 6 组正是这么做的。
   * 留在方法体里就只有起 {@code @SpringBootTest} 才能验，而那会真往演示库里插一行帖子。
   *
   * @return type=normal、visibility=null（走配置默认）、anonymous=true、
   *         无话题、无配图、autoDestroyHours=null 的发帖入参
   */
  static CreatePostRequest shareRequest(String title, String content) {
    // "normal" 写字面量而不是引 PostService.TYPE_NORMAL：那个常量是包级可见（com.mindisle.post），
    // 跨包引不到。为一条分享把它升成 public 不值当，真正的口径由这条静态方法与它的单测钉住。
    return new CreatePostRequest(title, content, "normal", null, Boolean.TRUE, null, null, null);
  }
}

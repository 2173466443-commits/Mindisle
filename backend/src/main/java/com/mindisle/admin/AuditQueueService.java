package com.mindisle.admin;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mindisle.admin.AdminOpLogService.Ctx;
import com.mindisle.admin.dto.AssigneeStatRow;
import com.mindisle.admin.dto.StatusCountRow;
import com.mindisle.audit.SensitiveWordEngine;
import com.mindisle.audit.SensitiveWordEngine.CheckResult;
import com.mindisle.audit.SensitiveWordEngine.Hit;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.AdminOpLog;
import com.mindisle.entity.AuditRecord;
import com.mindisle.entity.AuditTask;
import com.mindisle.entity.Post;
import com.mindisle.entity.PostImage;
import com.mindisle.entity.PostStatusLog;
import com.mindisle.entity.User;
import com.mindisle.mapper.AuditRecordMapper;
import com.mindisle.mapper.AuditTaskMapper;
import com.mindisle.mapper.PostImageMapper;
import com.mindisle.mapper.PostMapper;
import com.mindisle.mapper.PostStatusLogMapper;
import com.mindisle.mapper.UserMapper;
import com.mindisle.notify.NotifyService;

/**
 * 人工审核队列（手册 §9.1 第 1/2/3/5 条 · 需求 FR4.4 FR7.1 FR7.3 FR7.4 FR7.5 FR7.7）。
 *
 * <p><b>机审留痕为什么「从本阶段起累积」而不是回填历史</b>：audit_record 是只增不改的取证表
 * （DDL 注释原文「论文审计链」）。回填＝拿今天的词库去重算昨天的帖子，得到的 hit_words 与当时发帖所用
 * 的 wordlib_version 不是同一份，那是伪造历史。所以本类口径是：阶段 6 之后每一条进入人审的帖子，
 * 都能在 audit_record 里数到机审（dfa/image）+ 人审（HUMAN_*）两行；之前的帖子没有，这是局限，
 * 手册 §9.1 与答辩口径统一按这句话写。</p>
 *
 * <p><b>认领与裁决的并发语义全部落在 SQL 的 WHERE 上</b>（status='PENDING' /
 * status='PROCESSING' AND assignee_id=?），Java 里不做读改写：两个审核员同时点「认领」，
 * 后到的那次影响 0 行，本类把它翻译成一句人话——这是 FR4.4「不允许两人审同一条」的可执行证明。</p>
 */
@Service
public class AuditQueueService {

  private static final Logger log = LoggerFactory.getLogger(AuditQueueService.class);

  /** audit_task.target_type：本期只有帖子有产生方，其余枚举值暂无入口（评论/私信另有各自的处置口）。 */
  static final String TARGET_POST = "post";

  /** audit_task.status 五态里本期用到的四个（ESCALATED 由危机升级路径写入）。 */
  static final String STATUS_PENDING = "PENDING";
  static final String STATUS_PROCESSING = "PROCESSING";
  static final String STATUS_PASSED = "PASSED";
  static final String STATUS_REJECTED = "REJECTED";
  static final String STATUS_ESCALATED = "ESCALATED";

  /** post.status 的字面量副本：真源是 04_community.sql 的 ENUM 与 PostService 里的包级常量。 */
  static final String POST_MACHINE_REVIEW = "MACHINE_REVIEW";
  static final String POST_HUMAN_REVIEW = "HUMAN_REVIEW";
  static final String POST_PUBLISHED = "PUBLISHED";
  static final String POST_REJECTED = "REJECTED";
  static final String POST_TAKEDOWN = "TAKEDOWN";

  static final String LEVEL_L0 = "L0";
  static final String LEVEL_L2 = "L2";
  static final String LEVEL_L3 = "L3";

  /** 列宽：audit_task.remark 与 audit_record.hit_words/reason 都是 VARCHAR(500)。 */
  static final int REMARK_MAX = 500;
  static final int RECORD_TEXT_MAX = 500;

  /** post_status_log.reason 是 VARCHAR(255)，与 PostService 的 REASON_MAX 同一口径。 */
  static final int STATUS_LOG_REASON_MAX = 255;

  /** 一轮同步最多搬多少条：防「一条语句锁全表」。真库积压现量远小于它。 */
  static final int SYNC_BATCH_LIMIT = 500;

  /** 手册 §9.1 第 1 条：领取后 10 分钟不裁决自动回队，审核员掉线不会把帖子卡死。 */
  static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(10);

  /** audit_record.engine_version：换匹配算法（DFA -> Aho-Corasick）必须同步 bump，复现实验靠它对拍。 */
  static final String ENGINE_VERSION = "dfa-trie@1.0";

  /** 词库作用侧：审核队列审的是用户写的内容，所以恒取 user 侧（与 PostService 发帖口径一致）。 */
  private static final String SIDE_USER = "user";

  private static final Set<String> TASK_STATUSES = Set.of(STATUS_PENDING, STATUS_PROCESSING,
      STATUS_PASSED, STATUS_REJECTED, STATUS_ESCALATED);
  private static final Set<String> RISK_LEVELS = Set.of(LEVEL_L0, "L1", LEVEL_L2, LEVEL_L3);

  private final AuditTaskMapper taskMapper;
  private final AuditRecordMapper recordMapper;
  private final PostMapper postMapper;
  private final PostImageMapper postImageMapper;
  private final PostStatusLogMapper statusLogMapper;
  private final UserMapper userMapper;
  private final SensitiveWordEngine engine;
  private final NotifyService notifyService;
  private final AdminOpLogService opLogService;
  private final MindisleProperties properties;

  public AuditQueueService(AuditTaskMapper taskMapper, AuditRecordMapper recordMapper,
                           PostMapper postMapper, PostImageMapper postImageMapper,
                           PostStatusLogMapper statusLogMapper, UserMapper userMapper,
                           SensitiveWordEngine engine, NotifyService notifyService,
                           AdminOpLogService opLogService, MindisleProperties properties) {
    this.taskMapper = taskMapper;
    this.recordMapper = recordMapper;
    this.postMapper = postMapper;
    this.postImageMapper = postImageMapper;
    this.statusLogMapper = statusLogMapper;
    this.userMapper = userMapper;
    this.engine = engine;
    this.notifyService = notifyService;
    this.opLogService = opLogService;
    this.properties = properties;
  }

  // ================================================================ 同步送审

  /** 一次同步的统计：created 是净新增任务数，duplicated 是「已有待办任务所以没建」的条数。 */
  public record SyncStat(int scanned, int created, int duplicated) {
  }

  /**
   * 把 post.status='HUMAN_REVIEW' 的积压帖搬进审核队列（灰词帖进队列的唯一入口）。
   *
   * <p>为什么这里不是唯一的入口：建任务的动作发生在发帖之后，而发帖事务里插这一刀意味着
   * 审核队列的故障能把「发布」这件事一起失败掉；uk_target_pending 唯一键本来就允许事后补建，
   * 于是同步做成幂等、可反复执行，比在业务主链路上插一刀更稳。<b>v1.3.6 起即时入口已经补上</b>
   * （见 {@link #enqueueQuietly}：由控制器在发布返回后同请求内调用，失败只记日志不打扰用户），
   * 本方法退成兜底与补账：管种子数据、管管理端改状态进来的那些行、管即时入口没接住的那一条。
   * 它也仍由管理端「立即同步」按钮手动触发。</p>
   */
  public SyncStat syncPostsToTasks(LocalDateTime now) {
    List<Post> posts = postMapper.selectList(new LambdaQueryWrapper<Post>()
        .eq(Post::getStatus, POST_HUMAN_REVIEW)
        .orderByAsc(Post::getId)
        .last("limit " + SYNC_BATCH_LIMIT));
    int created = 0;
    int duplicated = 0;
    for (Post post : posts) {
      if (post.getId() == null) {
        continue;
      }
      CheckResult result = engine.check(post.getContent(), SIDE_USER);
      if (insertTask(post, AuditTask.SOURCE_MACHINE, AuditTask.CHANNEL_DFA, result, now)) {
        created++;
      } else {
        duplicated++;
      }
    }
    if (created > 0) {
      log.info("审核队列同步：扫描 {} 条灰词帖，新增任务 {} 条", posts.size(), created);
    }
    return new SyncStat(posts.size(), created, duplicated);
  }

  /**
   * 发帖之后<b>立刻</b>把灰词帖送进审核队列（§15 阶段 8 队列⑤ · 判据 D4）。
   *
   * <p>改造前这条路只有定时同步：cron 一分钟一轮，于是「发帖 → 出现在审核台」实测
   * 60 388ms（手册 §14 记的 D4 那一跳），答辩现场演示要干等一分钟。改法不是往发帖事务里插一刀
   * （{@code syncPostsToTasks} 的原注释解释了为什么），而是由 {@code PostController} 在
   * {@code publish} 返回之后、同一个请求里补这一次建单。同步作业照旧留着当兜底 ——
   * 它还得服务那些不经过 PostService 的行（种子数据、管理端改状态、崩溃时漏掉的那一条）。</p>
   *
   * <p><b>为什么吞异常</b>：走到这一步时帖子已经发出去了。再把审核队列的故障抛回给用户，
   * 等于让他以为「发帖失败」而重复提交一条，而那才是真的事故。所以这里只 {@code log.error}，
   * 队列由下一轮 cron 补上——最差情况就是退回改造前的行为，不会更糟。</p>
   *
   * @return true = 本轮真的建了任务；false 包含「不需要建」「已经有待办任务」「建失败了」，
   *         调用方不需要也无法据此改变给用户的文案（这就是 quietly 的含义）
   */
  public boolean enqueueQuietly(Long postId, String postStatus, LocalDateTime now) {
    if (postId == null || !POST_HUMAN_REVIEW.equals(postStatus)) {
      return false;
    }
    try {
      Post post = postMapper.selectById(postId);
      if (post == null || !POST_HUMAN_REVIEW.equals(post.getStatus())) {
        // 库里的状态与刚返回给控制器的那一份不一致：要么已被并发改掉，要么根本没读到了。
        // 交回兜底的那一轮 cron，不在这里下结论。
        return false;
      }
      CheckResult result = engine.check(post.getContent(), SIDE_USER);
      return insertTask(post, AuditTask.SOURCE_MACHINE, AuditTask.CHANNEL_DFA, result, now);
    } catch (RuntimeException e) {
      log.error("发帖即时送审失败，交下一轮同步作业兜底 postId={}", postId, e);
      return false;
    }
  }

  /**
   * 含图帖进人审抽审（手册 §9.1 第 3 条 · FR7.5）。
   *
   * <p>V1 不接任何图像识别第三方（需求 §12 规范 3 与预算约束），所以「图片审核」这一路的
   * 可执行定义就是：凡带图且已公开的帖子一律进队列由人看一眼。它的判据价值在于
   * 「没有第三方 key 时抽审队列仍有内容」——纯词库通道命不中图片，不做这一步，
   * FR7.5 现场演示会拿到一个空队列。</p>
   */
  public SyncStat syncImagePostsToTasks(LocalDateTime now, int limit) {
    int cap = limit <= 0 ? 200 : Math.min(limit, SYNC_BATCH_LIMIT);
    List<Object> ids = postImageMapper.selectObjs(new QueryWrapper<PostImage>()
        .select("DISTINCT post_id")
        .orderByAsc("post_id")
        .last("limit " + cap));
    int created = 0;
    int duplicated = 0;
    int scanned = 0;
    for (Object raw : ids) {
      if (!(raw instanceof Number number)) {
        continue;
      }
      long postId = number.longValue();
      Post post = postMapper.selectById(postId);
      if (post == null || !POST_PUBLISHED.equals(post.getStatus())) {
        continue;
      }
      scanned++;
      if (insertTask(post, AuditTask.SOURCE_HUMAN, "image", null, now)) {
        created++;
      } else {
        duplicated++;
      }
    }
    return new SyncStat(scanned, created, duplicated);
  }

  /**
   * 建任务 + 同批写机审留痕。返回 false 表示「已经有待办任务」——先靠 findPending 挡一次，
   * 唯一键冲突时 insertIgnore 返回 0，此时 useGeneratedKeys 回填的 id 不可信，绝不能拿它去写留痕。
   */
  private boolean insertTask(Post post, String source, String channel, CheckResult result,
                             LocalDateTime now) {
    if (taskMapper.findPending(TARGET_POST, post.getId()) != null) {
      return false;
    }
    AuditTask task = new AuditTask();
    task.setTargetType(TARGET_POST);
    task.setTargetId(post.getId());
    task.setSource(source);
    task.setChannel(channel);
    task.setResult(AuditRecord.DECISION_REVIEW);
    task.setRiskLevel(post.getRiskLevel() == null ? LEVEL_L0 : post.getRiskLevel());
    task.setSlaAt(slaForTask(post.getRiskLevel(), now));
    task.setStatus(STATUS_PENDING);
    task.setDeleted(0);
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    if (taskMapper.insertIgnore(task) != 1) {
      return false;
    }
    recordMapper.insert(machineRecord(task, post, channel, result, now));
    return true;
  }

  /** 机审留痕一行：dfa 通道记命中词与位置，image 通道记「为什么是人眼看」。 */
  private AuditRecord machineRecord(AuditTask task, Post post, String channel, CheckResult result,
                                    LocalDateTime now) {
    AuditRecord record = new AuditRecord();
    record.setTaskId(task.getId());
    record.setTargetType(TARGET_POST);
    record.setTargetId(post.getId());
    record.setChannel(channel);
    record.setWordlibVersion(engine.version());
    record.setEngineVersion(ENGINE_VERSION);
    record.setDecision(AuditRecord.DECISION_REVIEW);
    record.setDeleted(0);
    record.setCreatedAt(now);
    if (result == null) {
      record.setReason("图片抽审：V1 不接图像模型，含图公开帖一律进人审（论文按局限写）");
      record.setRawOutput("{\"reason\":\"image-sampling\",\"dictVersion\":\""
          + escape(engine.version()) + "\"}");
      return record;
    }
    List<String> words = new ArrayList<>();
    for (Hit hit : result.hits()) {
      words.add(hit.word());
    }
    record.setHitWords(cut(String.join(",", words.stream().distinct().toList()), RECORD_TEXT_MAX));
    record.setReason("词库通道送审：类目=" + result.category() + "，级别=" + result.level()
        + "，处置=" + result.action() + "，命中 " + result.hitCount() + " 处");
    record.setRawOutput(hitsToJson(result));
    return record;
  }

  /** 命中位置也进库：FR7.1「命中日志可回放」——只有词面时无法复原命中的是原文哪一段。 */
  private String hitsToJson(CheckResult result) {
    StringBuilder sb = new StringBuilder(128);
    sb.append("{\"category\":\"").append(escape(result.category()))
        .append("\",\"level\":\"").append(escape(result.level()))
        .append("\",\"action\":\"").append(escape(result.action()))
        .append("\",\"dictVersion\":\"").append(escape(result.dictVersion()))
        .append("\",\"hits\":[");
    boolean first = true;
    for (Hit hit : result.hits()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append("{\"word\":\"").append(escape(hit.word()))
          .append("\",\"start\":").append(hit.start())
          .append(",\"end\":").append(hit.end()).append('}');
    }
    return sb.append("]}").toString();
  }

  /** L2/L3 任务带处置时限，超时由队列排序顶到最前并标红（FR7.4 的可观测部分）。 */
  private LocalDateTime slaForTask(String riskLevel, LocalDateTime now) {
    if (LEVEL_L3.equals(riskLevel)) {
      return now.plusMinutes(properties.getCrisis().getL3SlaMinutes());
    }
    if (LEVEL_L2.equals(riskLevel)) {
      return now.plusHours(properties.getCrisis().getL2SlaHours());
    }
    return null;
  }

  // ================================================================ 队列读取

  /** 审核人在队列里看到的帖子摘要（匿名帖的昵称在这里就替换掉，不给前端二次判断的机会）。 */
  public record PostBrief(long id, Long authorId, String authorNickname, String title,
                          String content, String status, String riskLevel, Integer isAnonymous,
                          int imageCount, LocalDateTime createdAt) {
  }

  public record TaskView(AuditTask task, PostBrief post, List<String> hitWords, boolean overdue) {
  }

  /**
   * 队列分页。筛选项全部过白名单：审核台一次「筛错但看起来正常」的空列表，
   * 代价是一篇帖子在积压里躺三天没人发现，所以宁可当场 400。
   */
  public PageResult<TaskView> list(String status, String riskLevel, String targetType,
                                   Long assigneeId, boolean overdueOnly, PageQuery query,
                                   LocalDateTime now) {
    String statusFilter = requireIn(status, TASK_STATUSES, "任务状态");
    String levelFilter = requireIn(riskLevel, RISK_LEVELS, "风险等级");
    String typeFilter = blankToNull(targetType);
    if (typeFilter != null && !TARGET_POST.equals(typeFilter)) {
      throw new BizException(ErrorCode.PARAM_INVALID, "本期队列只开放 post 送审对象");
    }
    PageQuery q = query.normalize();
    List<AuditTask> tasks = taskMapper.pageQueue(statusFilter, levelFilter, typeFilter,
        assigneeId, overdueOnly, now, q.offset(), q.getSize());
    long total = taskMapper.countQueue(statusFilter, levelFilter, typeFilter,
        assigneeId, overdueOnly, now);
    return PageResult.of(views(tasks, now), total, q);
  }

  /** 批量补帖子摘要与作者昵称：一页最多 50 条，两条 IN 查询比逐条查少 49 次往返。 */
  private List<TaskView> views(List<AuditTask> tasks, LocalDateTime now) {
    Set<Long> postIds = new LinkedHashSet<>();
    for (AuditTask task : tasks) {
      if (TARGET_POST.equals(task.getTargetType()) && task.getTargetId() != null) {
        postIds.add(task.getTargetId());
      }
    }
    List<Post> posts = postIds.isEmpty() ? List.of()
        : postMapper.selectList(new LambdaQueryWrapper<Post>().in(Post::getId, postIds));
    Set<Long> authorIds = new LinkedHashSet<>();
    for (Post post : posts) {
      if (post.getUserId() != null) {
        authorIds.add(post.getUserId());
      }
    }
    List<User> authors = authorIds.isEmpty() ? List.of()
        : userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getId, authorIds));
    List<TaskView> out = new ArrayList<>(tasks.size());
    for (AuditTask task : tasks) {
      Post post = null;
      for (Post candidate : posts) {
        if (candidate.getId() != null && candidate.getId().equals(task.getTargetId())) {
          post = candidate;
          break;
        }
      }
      boolean open = STATUS_PENDING.equals(task.getStatus())
          || STATUS_PROCESSING.equals(task.getStatus());
      boolean overdue = open && task.getSlaAt() != null && task.getSlaAt().isBefore(now);
      out.add(new TaskView(task, brief(post, authors), hitWordsOf(post), overdue));
    }
    return out;
  }

  private PostBrief brief(Post post, List<User> authors) {
    if (post == null || post.getId() == null) {
      return null;
    }
    String nickname = null;
    for (User user : authors) {
      if (user.getId() != null && user.getId().equals(post.getUserId())) {
        nickname = user.getNickname();
        break;
      }
    }
    if (Integer.valueOf(1).equals(post.getIsAnonymous())) {
      nickname = "匿名";
    }
    long images = postImageMapper.selectCount(
        new LambdaQueryWrapper<PostImage>().eq(PostImage::getPostId, post.getId()));
    return new PostBrief(post.getId(), post.getUserId(), nickname, post.getTitle(),
        post.getContent(), post.getStatus(), post.getRiskLevel(), post.getIsAnonymous(),
        (int) images, post.getCreatedAt());
  }

  /** 队列里的命中词按「当前词库」现算；audit_record 里那条记的是当时那版词库，两个用途不同。 */
  private List<String> hitWordsOf(Post post) {
    if (post == null) {
      return List.of();
    }
    CheckResult result = engine.check(post.getContent(), SIDE_USER);
    List<String> words = new ArrayList<>();
    for (Hit hit : result.hits()) {
      words.add(hit.word());
    }
    return words.stream().distinct().toList();
  }

  /** A4 队列顶部计数（FR7.4「10 条队列清空 <60s」的分母从这里来）。 */
  public List<StatusCountRow> statusCounts() {
    return taskMapper.countGroupByStatus();
  }

  /** 人均处理量与平均耗时（手册 §9.2 A4 要求这两个数在界面上看得见）。 */
  public List<AssigneeStatRow> assigneeStats() {
    return taskMapper.assigneeStats();
  }

  /** 右侧「作者历史违规」抽屉：同一作者的历史任务，新到旧，最多 20 条。 */
  public List<AuditTask> authorHistory(long userId) {
    return taskMapper.listPostTasksByAuthor(userId, 20);
  }

  public AuditTask require(long taskId) {
    AuditTask task = taskMapper.selectById(taskId);
    if (task == null) {
      throw new BizException(ErrorCode.AUDIT_TASK_NOT_FOUND);
    }
    return task;
  }

  // ================================================================ 认领 / 裁决 / 超时回队

  /** 认领（FR4.4）：只有 PENDING 能被领走，抢不到就把「已被别人接走」说清楚，不返回空对象。 */
  public AuditTask claim(long taskId, Ctx ctx, LocalDateTime now) {
    long operatorId = requireOperator(ctx);
    AuditTask task = require(taskId);
    if (!STATUS_PENDING.equals(task.getStatus())) {
      opLogService.denied(ctx, AdminOpLog.ACTION_AUDIT_CLAIM, "audit_task:" + taskId, taskId,
          "当前状态 " + task.getStatus() + "，不可领取");
      throw new BizException(ErrorCode.FORBIDDEN,
          "该任务已经不是待领取状态（当前 " + task.getStatus() + "），请刷新队列");
    }
    if (taskMapper.claim(taskId, operatorId, now) == 0) {
      opLogService.denied(ctx, AdminOpLog.ACTION_AUDIT_CLAIM, "audit_task:" + taskId, taskId,
          "并发抢占失败");
      throw new BizException(ErrorCode.FORBIDDEN, "任务已被其他审核员领取，请刷新队列后重选");
    }
    opLogService.success(ctx, AdminOpLog.ACTION_AUDIT_CLAIM, "audit_task:" + taskId, taskId,
        "post=" + task.getTargetId());
    return require(taskId);
  }

  /** 裁决结果：postStatus 为 null 表示原帖已不存在（作者自己删了），任务本身仍然办结。 */
  public record Adjudication(long taskId, String taskStatus, Long postId, String postStatus,
                             boolean postStatusChanged, boolean publishedAtStamped) {
  }

  /**
   * 裁决一条任务（手册 §9.1 第 1 条 · FR7.3 四态流转留痕 · Gate6 D4「通过后 <2 分钟可见」）。
   *
   * <p>一个事务里四件事，缺一条就算没做完：1) 任务办结（WHERE 保证只有受理人能改）；
   * 2) audit_record 人审留痕（decision=HUMAN_PASS/HUMAN_REJECT + operator_id）；
   * 3) 帖子状态流转 + post_status_log（人工操作必须带 operator_id，系统流转留空）；
   *    转入 PUBLISHED 时同刻回填 published_at——灰词帖的「发布时刻」是人审放行那一刻，不是发帖那一刻（见 PostMapper#stampPublishedAt）；
   * 4) 站内通知作者，驳回时告知还有一次申诉机会。</p>
   *
   * <p>抽审进来的帖子状态本来就是 PUBLISHED：通过＝不动状态，驳回＝下架 TAKEDOWN。
   * 这条分支不能省，否则图片抽审的任务永远办不完，队列只进不出，FR7.4 直接不成立。</p>
   */
  @Transactional
  public Adjudication adjudicate(long taskId, Ctx ctx, boolean pass, String reason,
                                 LocalDateTime now) {
    long operatorId = requireOperator(ctx);
    AuditTask task = require(taskId);
    if (!TARGET_POST.equals(task.getTargetType())) {
      throw new BizException(ErrorCode.PARAM_INVALID, "本期只开放帖子人审，其它送审对象另走入口");
    }
    if (reason == null || reason.isBlank()) {
      throw new BizException(ErrorCode.PARAM_INVALID, "裁决必须填写理由，理由会同时给作者与审计");
    }
    String toStatus = pass ? STATUS_PASSED : STATUS_REJECTED;
    if (taskMapper.adjudicate(taskId, operatorId, toStatus, cut(reason, REMARK_MAX), now) == 0) {
      opLogService.denied(ctx, pass ? AdminOpLog.ACTION_AUDIT_PASS : AdminOpLog.ACTION_AUDIT_REJECT,
          "audit_task:" + taskId, taskId, "任务不在处理中，或受理人不是当前账号");
      throw new BizException(ErrorCode.FORBIDDEN,
          "任务已被他人接手或已办结，请刷新队列（审核不允许隔手改单）");
    }
    long postId = task.getTargetId();
    Post post = postMapper.selectById(postId);
    String fromStatus = post == null ? null : post.getStatus();
    String targetStatus = pass ? POST_PUBLISHED : POST_REJECTED;
    if (POST_PUBLISHED.equals(fromStatus)) {
      targetStatus = pass ? POST_PUBLISHED : POST_TAKEDOWN;
    }
    boolean changed = false;
    boolean publishedAtStamped = false;
    if (post != null && !targetStatus.equals(fromStatus)) {
      changed = postMapper.compareAndSetStatus(postId, fromStatus, targetStatus) == 1;
      if (changed) {
        statusLogMapper.insert(statusLog(postId, fromStatus, targetStatus, operatorId,
            "人审|" + (pass ? "通过" : "驳回") + "|" + cut(reason, 100), now));
        // 人审放行才是灰词帖真正的发布时刻（见 PostMapper#stampPublishedAt 注释：compareAndSetStatus
        // 不写 published_at）。不补这一刀，帖子状态是 PUBLISHED 却带着 NULL 发布时间，
        // 广场游标把它排在最后、推荐新帖池把它当没发布跳过，D4「通过后即时可见」只完成一半。
        // SQL 自带 published_at IS NULL 幂等闸门，图片抽审那条分支（本来就是 PUBLISHED）不受影响。
        publishedAtStamped = pass && postMapper.stampPublishedAt(postId, now) == 1;
      }
    }
    recordMapper.insert(humanRecord(task, postId, pass, reason, operatorId, now));
    if (post != null && post.getUserId() != null) {
      notifyService.notifyAuditResult(post.getUserId(), postId, pass, reason);
    }
    opLogService.success(ctx, pass ? AdminOpLog.ACTION_AUDIT_PASS : AdminOpLog.ACTION_AUDIT_REJECT,
        "audit_task:" + taskId, taskId, "post=" + postId + "|改状态=" + changed
            + "|回填发布时间=" + publishedAtStamped + "|理由=" + cut(reason, 120));
    return new Adjudication(taskId, toStatus, postId, post == null ? null : targetStatus, changed,
        publishedAtStamped);
  }

  private PostStatusLog statusLog(long postId, String from, String to, long operatorId,
                                  String reason, LocalDateTime now) {
    PostStatusLog row = new PostStatusLog();
    row.setPostId(postId);
    row.setFromStatus(from);
    row.setToStatus(to);
    row.setOperatorId(operatorId);
    row.setReason(cut(reason, STATUS_LOG_REASON_MAX));
    row.setCreatedAt(now);
    return row;
  }

  private AuditRecord humanRecord(AuditTask task, long postId, boolean pass, String reason,
                                  long operatorId, LocalDateTime now) {
    AuditRecord record = new AuditRecord();
    record.setTaskId(task.getId());
    record.setTargetType(TARGET_POST);
    record.setTargetId(postId);
    record.setChannel(task.getChannel());
    record.setWordlibVersion(engine.version());
    record.setEngineVersion(ENGINE_VERSION);
    record.setDecision(pass ? AuditRecord.DECISION_HUMAN_PASS : AuditRecord.DECISION_HUMAN_REJECT);
    record.setReason(cut((pass ? "人审通过：" : "人审驳回：") + reason, RECORD_TEXT_MAX));
    record.setOperatorId(operatorId);
    record.setDeleted(0);
    record.setCreatedAt(now);
    return record;
  }

  /**
   * 超时未裁决的任务自动回队（手册 §9.1 第 1 条后半）。
   *
   * <p>判据用 updated_at 而不是 claim_at：领取会把 updated_at 推到领取时刻，之后任何写操作同样会推它，
   * 于是「距最后一次动作 10 分钟」比「距领取 10 分钟」更贴近审核台的真实用法。</p>
   */
  public int releaseTimedOut(LocalDateTime now) {
    int released = taskMapper.releaseTimedOut(now.minus(CLAIM_TIMEOUT), now, 200);
    if (released > 0) {
      log.info("审核任务超时回队 {} 条，阈值 {}", released, CLAIM_TIMEOUT);
    }
    return released;
  }

  // ================================================================ 工具

  private static long requireOperator(Ctx ctx) {
    if (ctx == null || ctx.operatorId() == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED, "管理端操作需要登录身份");
    }
    return ctx.operatorId();
  }

  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** 空＝不过滤，非空必须在白名单里；否则 SQL 会安静地返回空列表，界面看起来「今天一条都没有」。 */
  private static String requireIn(String value, Set<String> allowed, String label) {
    String trimmed = blankToNull(value);
    if (trimmed != null && !allowed.contains(trimmed)) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          label + "只能是 " + String.join("/", allowed.stream().sorted().toList()));
    }
    return trimmed;
  }

  static String cut(String value, int maxCodePoints) {
    return AdminOpLogService.cut(value, maxCodePoints);
  }

  static String escape(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }
}
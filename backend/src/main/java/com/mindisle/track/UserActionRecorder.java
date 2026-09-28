package com.mindisle.track;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mindisle.entity.UserAction;

/**
 * 全站唯一的行为埋点写入口（任务 T3.10 · 手册 §6.1 行 3.10 · 需求 FR5.1、BR3）。
 *
 * <p><b>两条硬规矩，都是踩过之后才写的：</b></p>
 * <ol>
 *   <li><b>任何异常都不许外抛。</b>埋点是旁路观测，不是业务事务：用户点赞时行为表写失败，
 *       赞必须照样点上，否则「数据库抖一下」会变成一个 500 打在点赞按钮上。
 *       所以本类每个公开方法整体 try/catch，只留一条 WARN。反过来说，
 *       <b>调用方也不该把 record 当成自己事务里的成功条件</b>——它返回 void 是有意的，
 *       「记上了没有」在业务上不可观测；需要确证的场合（Gate 取证）直接查表。</li>
 *   <li><b>weight、day_bucket、mood_valence 只在这里算。</b>口径集中在
 *       {@link UserActionCatalog}，调用方传的是「谁对什么做了什么」，
 *       这样新增一个入口不可能自带一套权重（原因见 Catalog 类注释第一段）。</li>
 * </ol>
 *
 * <p><b>为什么走 {@link Store} 端口而不是直接依赖 Mapper</b>：与 {@code PostInteractionService}
 * 同一套路——埋点的全部价值在口径，而口径里最难在真机上复现的三件事
 * （同日重复进入要取最长停留、取消后再点要复用同一行、没打卡时 mood 必须是 NULL 而不是 0），
 * 用内存假实现能在单测里逐条钉死。真库适配器见 {@link UserActionStoreAdapter}。</p>
 */
@Component
public class UserActionRecorder {

  private static final Logger log = LoggerFactory.getLogger(UserActionRecorder.class);

  /** 存储端口：三个方法，正好对上 {@code UserActionMapper} 的三条裸 SQL。 */
  public interface Store {

    /** 幂等写入（撞 uk_action 覆盖读数，规则见 Mapper 注释）。返回影响行数，仅用于日志。 */
    int upsert(UserAction action);

    /** 撤销：把活动行置为逻辑删除。返回 0 表示本来就没有活动行。 */
    int cancelActive(long userId, String targetType, long targetId, String actionType);

    /** 该用户这天最后一次打卡的效价 −5..+5；<b>没打卡返回 null</b>，不得折叠成 0。 */
    Integer moodValenceOf(long userId, LocalDate day);
  }

  private final Store store;

  public UserActionRecorder(Store store) {
    this.store = store;
  }

  /**
   * 记一条普通行为。
   *
   * @param now 时间基准，由调用方传入（本项目所有服务内部不读系统时钟，同 {@code act(..., now)}）；
   *            它决定 day_bucket，也顺带决定 {@link #moodFor} 取哪一天的打卡
   */
  public void record(long userId, String actionType, String targetType, long targetId,
      String scene, LocalDateTime now) {
    recordInternal(userId, actionType, targetType, targetId, scene, null, null,
        UserActionCatalog.weightOf(actionType), now);
  }

  /**
   * 记一条带停留时长的行为（view / read_through）。
   *
   * <p>时长为 null 时照常落行，只是 duration_ms 留空——「用户读完了」和
   * 「前端没来得及上报时长」是两件事，后者不该让这条行为整个消失。</p>
   */
  public void recordWithDwell(long userId, String actionType, long postId, String scene,
      Integer durationMs, LocalDateTime now) {
    recordInternal(userId, actionType, UserActionCatalog.TARGET_POST, postId, scene, durationMs,
        null, UserActionCatalog.weightOf(actionType), now);
  }

  /**
   * 记一条对 AI 回复的赞踩（任务 T4.19 · 需求 FR2.7）。
   *
   * <p>target_type = message 且 target_id 与 message_id 同值（后者是为省一次 JOIN 的冗余，
   * 理由见 {@link UserActionCatalog#TARGET_MESSAGE}）。NONE（用户撤回反馈）也落一行 weight = 0：
   * <b>不删行</b>，因为「表过态又撤回」本身是需求 §8.4 对话质量评估集要用的信息。</p>
   */
  public void recordAiFeedback(long userId, long messageId, String feedback, LocalDateTime now) {
    recordInternal(userId, UserActionCatalog.ACTION_AI_FEEDBACK, UserActionCatalog.TARGET_MESSAGE,
        messageId, UserActionCatalog.SCENE_AI, null, messageId,
        UserActionCatalog.aiFeedbackWeight(feedback), now);
  }

  /**
   * 批量记曝光（手册 §6.1 行 3.10「曝光在 feed 返回时写、scene 区分」+ BR3 去重 + FR5.1 采样 30%）。
   *
   * <p>返回<b>实际记下的条数</b>而不是 void：这是本类唯一给出数的方法，因为 Gate 取证要核对
   * 「一屏十条到底记了几条」，而采样率是唯一能离线复算的量。业务调用方（Controller）
   * 忽略这个返回值。</p>
   *
   * <p><b>为什么不做「一小时只记一次」</b>：BR3 的原话是同一用户同一帖一小时内重复曝光
   * 不再计入，而这张表的 uk_action 带 day_bucket，同日的第二次曝光命中同一行、
   * 不会新增行——<b>日级幂等天然严于小时级</b>。代价是一天里第二次刷到同一条不再计曝光，
   * 对推荐而言这正是想要的（不然「刷了两遍」会被当成「更喜欢」）。
   * 这里没有为了精确到小时而多存一个时间桶，因为需求只要一个不重复的分母。</p>
   */
  public int recordExposure(long userId, Collection<Long> postIds, String scene,
      LocalDateTime now) {
    if (postIds == null || postIds.isEmpty()) {
      return 0;
    }
    int written = 0;
    for (Long postId : postIds) {
      if (postId == null || postId <= 0) {
        continue;
      }
      if (!UserActionCatalog.sampleExpose(userId, postId)) {
        continue;
      }
      // 曝光不在 MOOD_TRACKED 里，所以这一条路上一行 mood 查询都不发（见 Catalog#MOOD_TRACKED）
      // 只有真写进去的那一条才计数。返回值的契约是「实际写入条数」，
      // 若照旧写 written++，一个 userId=0 的脏请求或中途炸掉的库都会让条数虚高，
      // 而 Gate 取证正是拿这个数去核对「一屏十条记了几条」——它说谎比没有它更糟
      if (recordInternal(userId, UserActionCatalog.ACTION_EXPOSE, UserActionCatalog.TARGET_POST,
          postId, scene, null, null,
          UserActionCatalog.weightOf(UserActionCatalog.ACTION_EXPOSE), now)) {
        written++;
      }
    }
    return written;
  }

  /** 撤销一次行为（取消点赞 / 取消收藏 / 取关）。软删，行留着作留痕。 */
  public void cancel(long userId, String actionType, String targetType, long targetId) {
    if (!guards(userId, actionType, targetType, targetId)) {
      return;
    }
    try {
      int rows = store.cancelActive(userId, targetType, targetId, actionType);
      if (rows > 0) {
        log.debug("埋点撤销 {} {}#{} 影响 {} 行", actionType, targetType, targetId, rows);
      }
    } catch (Exception e) {
      log.warn("埋点撤销失败（已忽略）action={} target={}#{}", actionType, targetType, targetId, e);
    }
  }

  // ================================================================ 内部

  /**
   * 唯一的落库出口：权重已在调用点解析 → 心情采样 → 组装 → upsert。
   *
   * <p>weight 为 null 说明调用方给了一个不在 {@link UserActionCatalog#ACTION_WEIGHTS}
   * 里的行为名。这时<b>什么都不写</b>并打一条 WARN，而不是兜底成 1.00：
   * 兜底会让一个未经评审的行为悄悄进入协同训练的输入，而 WARN 只在日志里响一下——
   * 少一行数据的代价远小于一个错权重的代价。</p>
   */
  private boolean recordInternal(long userId, String actionType, String targetType, long targetId,
      String scene, Integer durationMs, Long messageId, BigDecimal weight, LocalDateTime now) {
    if (!guards(userId, actionType, targetType, targetId)) {
      return false;
    }
    if (weight == null) {
      log.warn("未知行为名，埋点已跳过 action={}"
          + "（新增行为请同时补 UserActionCatalog.ACTION_WEIGHTS），未写入 {}#{}",
          actionType, targetType, targetId);
      return false;
    }
    LocalDateTime moment = now == null ? LocalDateTime.now() : now;
    LocalDate day = moment.toLocalDate();
    try {
      UserAction row = new UserAction();
      row.setUserId(userId);
      row.setTargetType(targetType);
      row.setTargetId(targetId);
      row.setActionType(actionType);
      row.setWeight(weight);
      row.setMoodValence(moodFor(userId, actionType, day));
      row.setMessageId(messageId);
      row.setDayBucket(day);
      row.setScene(UserActionCatalog.normalizeScene(scene));
      row.setDurationMs(durationMs);
      row.setDeleted(0);
      store.upsert(row);
      return true;
    } catch (Exception e) {
      log.warn("埋点写入失败并已忽略（口径见 UserActionRecorder 类注释第 1 条）user={} action={}"
          + "，目标 {}#{}", userId, actionType, targetType, targetId, e);
      return false;
    }
  }

  /**
   * 参数守卫。四条都是「脏数据」而不是「异常」，所以只打日志、不上抛：
   * userId 非正（未登录却调了埋点）、目标 id 非正、行为名不在十种里、目标类型不在五种里。
   */
  private boolean guards(long userId, String actionType, String targetType, long targetId) {
    if (userId <= 0 || targetId <= 0) {
      log.debug("埋点参数非法已跳过 user={} target={}#{}", userId, targetType, targetId);
      return false;
    }
    // 先判 null 再 contains：Set.of(...) 造的不可变集合对 contains(null) 直接抛 NPE
    // （java.util.ImmutableCollections.SetN#probe 会 o.hashCode()），而这个 NPE 发生在
    // try 之外，会把「不许上抛」这条硬规矩从内部破掉。调用方传个 null 行为名就是 500。
    if (actionType == null || !UserActionCatalog.ACTION_TYPES.contains(actionType)) {
      log.warn("埋点行为名为空或不在 ENUM 里已跳过：{}", actionType);
      return false;
    }
    if (targetType == null || !UserActionCatalog.TARGET_TYPES.contains(targetType)) {
      log.warn("埋点目标类型为空或不在 ENUM 里已跳过：{}", targetType);
      return false;
    }
    return true;
  }

  /**
   * 要不要、能不能取一份「当时的心情」。
   *
   * <p>三态分清：{@link UserActionCatalog#tracksMood} 为 false 直接返回 null 且<b>不发查询</b>
   * （曝光是全站最热的写路径）；为 true 时查询本身失败也返回 null——心情是加分项，
   * 缺了它这行埋点仍然对协同过滤有用，不该因为一次 emotion_record 读失败就丢掉整行。</p>
   */
  private Integer moodFor(long userId, String actionType, LocalDate day) {
    if (!UserActionCatalog.tracksMood(actionType)) {
      return null;
    }
    try {
      return store.moodValenceOf(userId, day);
    } catch (Exception e) {
      log.warn("取当日打卡效价失败，本行 mood_valence 记为空 user={} day={}", userId, day, e);
      return null;
    }
  }
}

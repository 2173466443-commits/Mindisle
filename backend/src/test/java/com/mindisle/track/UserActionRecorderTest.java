package com.mindisle.track;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.mindisle.entity.UserAction;

/**
 * 埋点写入器的单测（任务 T3.10 · 手册 §6.1 行 3.10）。
 *
 * <p><b>为什么用内存假实现而不是真库</b>：埋点最难在真机上复现的三件事——同日重复进入要取
 * 最长停留、取消后再点要复用同一行、没打卡时 mood 必须是 NULL 而不是 0——用真 MySQL 要么
 * 需要固定日期（做不到，测试跑在任意一天），要么需要往真库里塞打卡数据（会把演示库搞脏）。
 * {@link UserActionRecorder.Store} 这个端口就是为了让假实现只用几十行就能把口径钉死。</p>
 *
 * <p><b>假实现复刻了 {@code UserActionMapper.upsert} 的覆盖规则</b>（见该方法注释）。
 * 这份重复是有意的取舍，说清楚免得后来人以为在测 SQL：SQL 本身的正确性靠对真库手工跑过
 * （取证见 docs/gate/阶段4），本测试管的是两件事——「Recorder 有没有把正确的值交给 Store」
 * 与「Store 出错时会不会炸到业务」。换句话说这里测的是 Java 侧口径，
 * 不是 MySQL 的 ON DUPLICATE KEY 语义。</p>
 */
@DisplayName("T3.10 行为埋点写入器")
class UserActionRecorderTest {

  private static final LocalDateTime DAY1 = LocalDateTime.of(2026, 3, 4, 10, 0);
  private static final LocalDateTime DAY2 = LocalDateTime.of(2026, 3, 5, 10, 0);
  private static final long USER = 7L;
  private static final long POST = 21L;
  /** 100..109 这十条对任何用户都恒定命中 3 条（见 CatalogTest 的同名断言）。 */
  private static final List<Long> TEN_ITEMS =
      List.of(100L, 101L, 102L, 103L, 104L, 105L, 106L, 107L, 108L, 109L);

  private FakeStore store;
  private UserActionRecorder recorder;

  @BeforeEach
  void setUp() {
    store = new FakeStore();
    recorder = new UserActionRecorder(store);
  }

  // ==================================================== 落库字段

  @Test
  @DisplayName("点赞落库：权重 3、目标 post、day_bucket 取自传入的 now、deleted=0")
  void recordWritesCatalogWeightAndDayBucket() {
    recorder.record(USER, "like", "post", POST, "plaza", DAY1);
    UserAction row = onlyRow();
    assertEquals(USER, row.getUserId());
    assertEquals("post", row.getTargetType());
    assertEquals(POST, row.getTargetId());
    assertEquals("like", row.getActionType());
    assertEquals(new BigDecimal("3.00"), row.getWeight());
    assertEquals(LocalDate.of(2026, 3, 4), row.getDayBucket());
    assertEquals("plaza", row.getScene());
    assertEquals(0, row.getDeleted());
    assertNull(row.getMessageId());
    assertNull(row.getDurationMs());
    assertNotNull(row);
  }

  @Test
  @DisplayName("scene 传错值时降级成 feed，但这一行照样写：丢标签比丢数据好")
  void unknownSceneDegradesButRowIsStillWritten() {
    recorder.record(USER, "like", "post", POST, "wechat_mini", DAY1);
    assertEquals("feed", onlyRow().getScene());
    assertEquals(1, store.rows.size());
  }

  @Test
  @DisplayName("scene 传 null 时留 null（列可空），不硬塞一个 feed 假装知道来源")
  void nullSceneStaysNull() {
    recorder.record(USER, "like", "post", POST, null, DAY1);
    assertNull(onlyRow().getScene());
  }

  // ==================================================== 幂等（BR3 + uk_action）

  @Test
  @DisplayName("同一用户同一天对同一帖点两次：表里只有一行（BR3 的日级幂等）")
  void sameDaySecondHitReusesTheRow() {
    recorder.record(USER, "like", "post", POST, "plaza", DAY1);
    recorder.record(USER, "like", "post", POST, "detail", DAY1.plusHours(3));
    assertEquals(1, store.rows.size(), "同日重复不应增行");
    // scene 是覆盖而不是保留首个：uk 上只有一行，读侧要的是最近一次的场景
    assertEquals("detail", onlyRow().getScene());
  }

  @Test
  @DisplayName("跨天再点一次：新增一行，阶段 7 的时间衰减 exp(-Δd/7) 要靠它")
  void nextDayGetsItsOwnRow() {
    recorder.record(USER, "like", "post", POST, "plaza", DAY1);
    recorder.record(USER, "like", "post", POST, "plaza", DAY2);
    assertEquals(2, store.rows.size());
  }

  @Test
  @DisplayName("停留时长取历史最长，不被一次快进快出抹掉")
  void dwellKeepsTheMaximum() {
    recorder.recordWithDwell(USER, "view", POST, "detail", 9000, DAY1);
    assertEquals(9000, onlyRow().getDurationMs());
    recorder.recordWithDwell(USER, "view", POST, "detail", 3500, DAY1.plusMinutes(1));
    assertEquals(9000, store.one("view").getDurationMs(), "更短的停留不得覆盖更长的");
    recorder.recordWithDwell(USER, "view", POST, "detail", 30000, DAY1.plusMinutes(2));
    assertEquals(30000, store.one("view").getDurationMs());
    // 没量到时长（null）也不该把已有读数清成空——「用户读完了」和「前端没来得及上报」是两件事
    recorder.recordWithDwell(USER, "view", POST, "detail", null, DAY1.plusMinutes(3));
    assertEquals(30000, store.one("view").getDurationMs());
    assertEquals(1, store.rows.size());
  }

  // ==================================================== 撤销

  @Test
  @DisplayName("取消点赞走软删（参数原样传给 Store），不物理删行")
  void cancelSoftDeletes() {
    recorder.record(USER, "like", "post", POST, "plaza", DAY1);
    recorder.cancel(USER, "like", "post", POST);
    assertEquals(1, store.cancelCalls);
    assertEquals(USER, store.lastCancelUserId);
    assertEquals("post", store.lastCancelTargetType);
    assertEquals(POST, store.lastCancelTargetId);
    assertEquals("like", store.lastCancelAction);
    assertEquals(1, store.one("like").getDeleted(), "行要留着作留痕");
  }

  @Test
  @DisplayName("取消一个非法目标：不碰存储")
  void cancelGuardsSkipStore() {
    recorder.cancel(0, "like", "post", POST);
    recorder.cancel(USER, "like", "post", -1);
    recorder.cancel(USER, "nope", "post", POST);
    assertEquals(0, store.cancelCalls);
  }

  // ==================================================== 脏数据守卫

  @Test
  @DisplayName("六类脏输入一律静默跳过：不写库、不查询、也不抛")
  void dirtyInputIsSkipped() {
    recorder.record(0, "like", "post", POST, "plaza", DAY1);
    recorder.record(-3, "like", "post", POST, "plaza", DAY1);
    recorder.record(USER, "like", "post", 0, "plaza", DAY1);
    recorder.record(USER, "share", "post", POST, "plaza", DAY1);
    recorder.record(USER, "like", "article", POST, "plaza", DAY1);
    recorder.record(USER, null, "post", POST, "plaza", DAY1);
    assertEquals(0, store.rows.size());
    assertEquals(0, store.upsertCalls);
    assertEquals(0, store.moodQueries, "守卫要在查 mood 之前");
  }

  @Test
  @DisplayName("未知行为名不兜底成 1.00：宁可少一行，也不能把未评审的行为喂给 CF")
  void unknownActionNeverGetsDefaultWeight() {
    // 这条是全站最容易被「顺手加个功能」破坏的地方：新增行为只改了 DDL 的 ENUM、
    // 忘了补 Catalog 权重表时，如果这里兜底 1.00，脏数据会一路进到模型输入里，
    // 而 weight 列上的 DEFAULT 1.00 恰好会让「漏配」看起来像「特意配了 1 分」
    recorder.record(USER, "share_to_wechat", "post", POST, "plaza", DAY1);
    assertEquals(0, store.rows.size());
    assertEquals(0, store.upsertCalls);
  }

  // ==================================================== 心情采样

  @Test
  @DisplayName("正向行为顺带采当日打卡效价；曝光是全站最热的写路径，一行 mood 查询都不发")
  void moodIsSampledOnlyForTrackedActions() {
    store.moodValue = 4;
    recorder.record(USER, "like", "post", POST, "plaza", DAY1);
    assertEquals(4, onlyRow().getMoodValence());
    assertEquals(1, store.moodQueries);
    assertEquals(LocalDate.of(2026, 3, 4), store.lastMoodDay, "取的是行为那一天的打卡");

    store.moodQueries = 0;
    recorder.recordExposure(USER, TEN_ITEMS, "plaza", DAY1);
    assertEquals(0, store.moodQueries, "曝光不该发 mood 查询");

    store.moodQueries = 0;
    recorder.record(USER, "report", "post", POST, "detail", DAY1);
    assertEquals(0, store.moodQueries, "负反馈不采心情");
  }

  @Test
  @DisplayName("没打卡就是 NULL，不是 0：NULL 是「未采集」，0 是一个真实的「心情中性」读数")
  void missingMoodStaysNullNotZero() {
    store.moodValue = null;
    recorder.record(USER, "like", "post", POST, "plaza", DAY1);
    assertNull(onlyRow().getMoodValence());
    assertEquals(1, store.moodQueries);
  }

  @Test
  @DisplayName("取 mood 失败只丢这一列，整行还是要写：心情是加分项")
  void moodFailureStillWritesTheRow() {
    store.failMood = true;
    recorder.record(USER, "like", "post", POST, "plaza", DAY1);
    assertEquals(1, store.rows.size());
    assertNull(onlyRow().getMoodValence());
  }

  @Test
  @DisplayName("mood 为 null 的第二次进入不得把已有的效价覆盖掉（COALESCE 口径）")
  void nullMoodDoesNotEraseEarlierMood() {
    store.moodValue = 3;
    recorder.record(USER, "like", "post", POST, "plaza", DAY1);
    store.moodValue = null;
    recorder.record(USER, "like", "post", POST, "detail", DAY1.plusHours(1));
    assertEquals(3, onlyRow().getMoodValence(), "COALESCE(VALUES(mood_valence), mood_valence)");
  }

  // ==================================================== 曝光

  @Test
  @DisplayName("曝光按 30% 采样落库，返回实际写入条数，scene 与日期都带上")
  void exposureWritesSampledSubsetOnly() {
    int written = recorder.recordExposure(USER, TEN_ITEMS, "following", DAY1);
    assertEquals(3, written, "十项里恒定命中三条");
    assertEquals(written, store.rows.size());
    for (UserAction row : store.rows.values()) {
      assertEquals("expose", row.getActionType());
      assertEquals(new BigDecimal("0.10"), row.getWeight());
      assertEquals("following", row.getScene());
      assertEquals(LocalDate.of(2026, 3, 4), row.getDayBucket());
      assertEquals(USER, row.getUserId());
      assertTrue(TEN_ITEMS.contains(row.getTargetId()), "写了个不在列表里的 id");
      assertNull(row.getDurationMs());
    }
  }

  @Test
  @DisplayName("重复刷同一页不会多记曝光：第二次命中的还是同一条 uk")
  void refreshingTheSamePageDoesNotAddExposures() {
    assertEquals(3, recorder.recordExposure(USER, TEN_ITEMS, "plaza", DAY1));
    assertEquals(3, recorder.recordExposure(USER, TEN_ITEMS, "plaza", DAY1));
    assertEquals(3, store.rows.size(), "同日同条目再曝光不该增行");
  }

  @Test
  @DisplayName("空集合、null、未登录与非正 id 都不写")
  void exposureIgnoresGarbage() {
    assertEquals(0, recorder.recordExposure(USER, null, "plaza", DAY1));
    assertEquals(0, recorder.recordExposure(USER, List.of(), "plaza", DAY1));
    assertEquals(0, recorder.recordExposure(0, List.of(POST), "plaza", DAY1));
    assertEquals(0, recorder.recordExposure(USER, List.of(0L, -5L), "plaza", DAY1));
    assertEquals(0, store.rows.size());
  }

  // ==================================================== AI 赞踩（T4.19）

  @Test
  @DisplayName("赞踩落 message：target_type 与 message_id 同值、scene=ai、权重按三态取")
  void aiFeedbackLandsOnMessageTarget() {
    recorder.recordAiFeedback(USER, 55L, "UP", DAY1);
    UserAction up = onlyRow();
    assertEquals("ai_feedback", up.getActionType());
    assertEquals("message", up.getTargetType());
    assertEquals(55L, up.getTargetId());
    assertEquals(55L, up.getMessageId(), "message_id 与 target_id 同值，冗余一列是为省一次 JOIN");
    assertEquals(new BigDecimal("2.00"), up.getWeight());
    assertEquals("ai", up.getScene());

    recorder.recordAiFeedback(USER, 56L, "DOWN", DAY1);
    assertEquals(new BigDecimal("-2.00"), store.row("message", 56L, "ai_feedback").getWeight());

    // NONE 也落一行 weight=0 而不是删行：表过态又撤回，本身是需求 §8.4 对话质量评估集要用的信息
    recorder.recordAiFeedback(USER, 55L, "NONE", DAY1);
    assertEquals(BigDecimal.ZERO, store.row("message", 55L, "ai_feedback").getWeight());
    assertEquals(2, store.rows.size());
  }

  // ==================================================== 异常隔离（最关键的一条）

  @Nested
  @DisplayName("存储出问题时绝不上抛：埋点是旁路，不能让数据库抖一下就把点赞打成 500")
  class ExceptionsAreSwallowed {

    @Test
    @DisplayName("record 写失败不外抛")
    void recordSwallows() {
      store.failUpsert = true;
      assertDoesNotThrow(() -> recorder.record(USER, "like", "post", POST, "plaza", DAY1));
    }

    @Test
    @DisplayName("recordWithDwell 写失败不外抛")
    void dwellSwallows() {
      store.failUpsert = true;
      assertDoesNotThrow(() -> recorder.recordWithDwell(USER, "view", POST, "detail", 5000, DAY1));
    }

    @Test
    @DisplayName("recordExposure 中途失败不外抛：一屏十条不能因为第五条炸掉整页")
    void exposureSwallows() {
      store.failUpsertAfter = 2;
      // 前两条写得进去，第三条起库就抛；返回的数必须是 2 而不是 10——
      // 这个返回值是 Gate 取证要核对的「一屏十条记了几条」，它说谎比没有更糟
      assertDoesNotThrow(() -> assertEquals(2,
          recorder.recordExposure(USER, TEN_ITEMS, "plaza", DAY1)));
      assertEquals(2, store.rows.size());
    }

    @Test
    @DisplayName("cancel 与 recordAiFeedback 失败也不外抛")
    void cancelAndFeedbackSwallow() {
      store.failCancel = true;
      store.failUpsert = true;
      assertDoesNotThrow(() -> recorder.cancel(USER, "like", "post", POST));
      assertDoesNotThrow(() -> recorder.recordAiFeedback(USER, 55L, "UP", DAY1));
    }
  }

  @Test
  @DisplayName("now 传 null 时退化成今天：day_bucket 是 NOT NULL，调用方漏传不该炸整行")
  void nullNowFallsBackToToday() {
    recorder.record(USER, "like", "post", POST, "plaza", null);
    assertEquals(LocalDate.now(), onlyRow().getDayBucket());
  }

  // ==================================================== 工具

  private UserAction onlyRow() {
    assertEquals(1, store.rows.size(), "期望表里只有一行，实际 " + store.rows.size());
    return store.rows.values().iterator().next();
  }

  /**
   * 内存假存储。按 uk_action(user_id, target_type, target_id, action_type, day_bucket) 收敛成一行，
   * 并复刻 {@code UserActionMapper.upsert} 的覆盖规则：weight / scene / message_id / deleted 覆盖，
   * mood_valence 取 COALESCE，duration_ms 取 max，created_at 不动。取舍理由见类注释第二段。
   */
  private static final class FakeStore implements UserActionRecorder.Store {

    final Map<String, UserAction> rows = new LinkedHashMap<>();

    int upsertCalls;
    int cancelCalls;
    int moodQueries;
    Integer moodValue;
    LocalDate lastMoodDay;
    long lastCancelUserId;
    long lastCancelTargetId;
    String lastCancelTargetType;
    String lastCancelAction;
    boolean failUpsert;
    boolean failCancel;
    boolean failMood;
    int failUpsertAfter;

    /** 只有一条动作类型时用它取行（本测试里大多数用例只有一个动作）。 */
    UserAction one(String actionType) {
      return row(null, 0L, actionType);
    }

    UserAction row(String targetType, long targetId, String actionType) {
      for (UserAction a : rows.values()) {
        boolean targetTypeOk = targetType == null || targetType.equals(a.getTargetType());
        boolean targetIdOk = targetId <= 0 || targetId == a.getTargetId();
        if (targetTypeOk && targetIdOk && actionType.equals(a.getActionType())) {
          return a;
        }
      }
      throw new AssertionError("找不到行 " + targetType + "#" + targetId + " " + actionType
          + "，表里现有 " + rows.size() + " 行");
    }

    @Override
    public int upsert(UserAction action) {
      upsertCalls++;
      if (failUpsert) {
        throw new IllegalStateException("模拟埋点库挂了");
      }
      if (failUpsertAfter > 0 && upsertCalls > failUpsertAfter) {
        throw new IllegalStateException("模拟写第 " + upsertCalls + " 行时挂了");
      }
      String uk = action.getUserId() + "|" + action.getTargetType() + "|" + action.getTargetId()
          + "|" + action.getActionType() + "|" + action.getDayBucket();
      UserAction prev = rows.get(uk);
      if (prev == null) {
        rows.put(uk, action);
        return 1;
      }
      prev.setWeight(action.getWeight());
      prev.setScene(action.getScene());
      prev.setMessageId(action.getMessageId());
      prev.setDeleted(action.getDeleted());
      if (action.getMoodValence() != null) {
        prev.setMoodValence(action.getMoodValence());
      }
      if (action.getDurationMs() != null && (prev.getDurationMs() == null
          || action.getDurationMs() > prev.getDurationMs())) {
        prev.setDurationMs(action.getDurationMs());
      }
      return 2;
    }

    @Override
    public int cancelActive(long userId, String targetType, long targetId, String actionType) {
      cancelCalls++;
      if (failCancel) {
        throw new IllegalStateException("模拟埋点库挂了");
      }
      lastCancelUserId = userId;
      lastCancelTargetType = targetType;
      lastCancelTargetId = targetId;
      lastCancelAction = actionType;
      int hit = 0;
      for (UserAction a : rows.values()) {
        if (a.getUserId() == userId && targetType.equals(a.getTargetType())
            && a.getTargetId() == targetId && actionType.equals(a.getActionType())) {
          a.setDeleted(1);
          hit++;
        }
      }
      return hit;
    }

    @Override
    public Integer moodValenceOf(long userId, LocalDate day) {
      moodQueries++;
      lastMoodDay = day;
      if (failMood) {
        throw new IllegalStateException("模拟打卡表读不到");
      }
      return moodValue;
    }
  }

  /** List 版的行快照，留给以后要按顺序比对的用例。 */
  private static List<UserAction> snapshotOf(Map<String, UserAction> rows) {
    return new ArrayList<>(rows.values());
  }
}

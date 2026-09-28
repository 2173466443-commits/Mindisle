package com.mindisle.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 埋点口径表的单测（任务 T3.10）。
 *
 * <p><b>这张表值得单独测，是因为它错一个数字不会被任何功能测试发现。</b>
 * 点赞记成 2 分还是 3 分，接口照样返回 200、前端照样点亮、数据库照样落一行；
 * 要等到阶段 7 跑离线指标才会看到整体变差，而那时候没人能定位到「是 Catalog 抄错了」。
 * 所以这里把每一个数都钉死，并注明它在需求里的出处（FR5.1 / §8.2.1）。</p>
 */
@DisplayName("T3.10 埋点口径表")
class UserActionCatalogTest {

  // ==================================================== 权重（需求 FR5.1 + §8.2.1）

  @Test
  @DisplayName("十种行为的权重逐个对上需求原文，不是大概对")
  void weightsMatchRequirement() {
    eq("expose", "0.10");
    eq("view", "1.00");
    eq("read_through", "2.00");
    eq("like", "3.00");
    eq("comment", "4.00");
    eq("collect", "5.00");
    eq("follow", "5.00");
    eq("dislike", "-3.00");
    eq("report", "-5.00");
  }

  @Test
  @DisplayName("权重表的键集合与 ENUM 行为名严格相等：新增行为忘了配权重会当场红")
  void weightTableCoversExactlyTheEnum() {
    assertEquals(UserActionCatalog.ACTION_TYPES, UserActionCatalog.ACTION_WEIGHTS.keySet(),
        "ACTION_TYPES 与 ACTION_WEIGHTS 不一致，说明有行为名没配权重（或多配了）");
    assertEquals(10, UserActionCatalog.ACTION_TYPES.size());
    assertEquals(10, UserActionCatalog.ACTION_WEIGHTS.size());
  }

  @Test
  @DisplayName("ai_feedback 在权重表里占位为 0，真实分走 aiFeedbackWeight")
  void aiFeedbackIsDelegated() {
    // FR5.1 讲的是内容隐式反馈，ai_feedback 的载体是 AI 消息（手册 T4.19）。
    // 它既然共用同一个 weight 列就必须有个数，这个数是本项目定的（UP +2 / DOWN -2 / NONE 0），
    // 已回写手册 §7.5 的 T4.19 行与 dev-log
    // 这一条不能用 eq(...)：ACTION_WEIGHTS 里 ai_feedback 存的是 BigDecimal.ZERO（标度 0），
    // 而 eq(...) 会连标度一起钉死。DECIMAL(4,2) 落库后都是 0.00，这里只钉「等于零」这件事
    assertEquals(0, BigDecimal.ZERO.compareTo(UserActionCatalog.weightOf("ai_feedback")),
        "ai_feedback 在内容权重表里必须是 0，真实分走 aiFeedbackWeight");
  }

  @Test
  @DisplayName("未知行为名返回 null，不兜底成 1.00")
  void unknownActionHasNoWeight() {
    assertNull(UserActionCatalog.weightOf("share_it_everywhere"));
    assertNull(UserActionCatalog.weightOf(null));
  }

  @Test
  @DisplayName("权重不被埋点层截断：举报与不感兴趣必须是负数，否则负反馈信号就没了")
  void negativeWeightsAreNotClampedHere() {
    // 需求 §8.2.1 那句「下限截 0、上限 10」作用在 R(u,i) 的求和结果上（阶段 7 的 ImplicitScorer），
    // 若在这里就截，一次举报会被写成 0。落库永远存原始值，截断只发生在读侧
    assertTrue(UserActionCatalog.weightOf("report").signum() < 0);
    assertTrue(UserActionCatalog.weightOf("dislike").signum() < 0);
    // 并且 DECIMAL(4,2) 容得下全部取值（最大 5.00、最小 -5.00）
    for (BigDecimal w : UserActionCatalog.ACTION_WEIGHTS.values()) {
      assertTrue(w.abs().compareTo(new BigDecimal("9.99")) <= 0, "超出列宽: " + w);
    }
  }

  private static void eq(String actionType, String expected) {
    BigDecimal actual = UserActionCatalog.weightOf(actionType);
    assertNotNull(actual, actionType + " 没有权重");
    // 用 equals 而不是 compareTo：标度也要钉死（0.10 写成 0.1 会让「读起来一样」掩盖掉一次改动）
    assertEquals(new BigDecimal(expected), actual, actionType + " 权重应为 " + expected);
  }

  // ==================================================== ENUM 一致性

  @Test
  @DisplayName("行为名与目标类型逐字对齐 DDL 的小写 ENUM")
  void enumValuesMatchDdl() {
    assertEquals(Set.of("view", "like", "collect", "comment", "read_through", "dislike",
        "follow", "report", "expose", "ai_feedback"), UserActionCatalog.ACTION_TYPES);
    assertEquals(Set.of("post", "comment", "topic", "user", "message"),
        UserActionCatalog.TARGET_TYPES);
    // message 是 sql/15 补的第 5 个值。理由见 TARGET_MESSAGE 注释：post id 与 message id
    // 都是各自表的自增主键，都从 1 开始，混用会让 (post,17) 与 (message,17) 静默合并成同一个物品
  }

  @Test
  @DisplayName("scene 全部不超过列宽 VARCHAR(16)")
  void scenesFitColumnWidth() {
    for (String scene : UserActionCatalog.SCENES) {
      assertTrue(scene.length() <= 16, "scene 超列宽: " + scene);
    }
  }

  // ==================================================== scene 归一

  @Test
  @DisplayName("scene 归一：null 保持 null，认不出的降级成 feed，大小写与空格不敏感")
  void normalizeSceneBehaviour() {
    assertNull(UserActionCatalog.normalizeScene(null));
    assertEquals("plaza", UserActionCatalog.normalizeScene(" PLAZA "));
    assertEquals("detail", UserActionCatalog.normalizeScene("Detail"));
    assertEquals("feed", UserActionCatalog.normalizeScene("wechat_mini"));
    assertEquals("feed", UserActionCatalog.normalizeScene(""));
    // 认不出降级而不是抛：埋点丢一个标签比丢一行数据好
    assertEquals("feed", UserActionCatalog.normalizeScene("首页"));
  }

  // ==================================================== 心情采集开关

  @Test
  @DisplayName("曝光/举报/不感兴趣不采心情，其余七种正向行为采")
  void moodSamplingSwitch() {
    // 曝光是全站最热的写路径，一次请求二十行；为它多查二十次 emotion_record 不划算，
    // 而需求 §8.2.2 的情绪项只写在「低落时优先推治愈内容」这条正向假设上
    assertFalse(UserActionCatalog.tracksMood("expose"));
    assertFalse(UserActionCatalog.tracksMood("report"));
    assertFalse(UserActionCatalog.tracksMood("dislike"));
    for (String positive : new String[] {"view", "like", "collect", "comment", "read_through",
        "follow", "ai_feedback"}) {
      assertTrue(UserActionCatalog.tracksMood(positive), positive + " 应当采心情");
    }
    assertFalse(UserActionCatalog.tracksMood(null));
    assertFalse(UserActionCatalog.tracksMood("no_such_action"));
  }

  // ==================================================== 曝光采样

  @Test
  @DisplayName("采样判据落在 id 空间上：同一对(用户,条目)永远同结论，可跨重启复现")
  void exposeSamplingIsDeterministic() {
    for (long user = 1; user <= 20; user++) {
      for (long item = 1; item <= 20; item++) {
        boolean first = UserActionCatalog.sampleExpose(user, item);
        for (int repeat = 0; repeat < 5; repeat++) {
          assertEquals(first, UserActionCatalog.sampleExpose(user, item),
              "掷骰子才会每次不同，取模不会");
        }
      }
    }
  }

  @Test
  @DisplayName("任意连续 10 个条目恰好命中 3 条：30% 是能被口算验证的性质")
  void exposeSamplingHitsThreeInTen() {
    for (long user = 1; user <= 30; user++) {
      int hits = 0;
      for (long item = 100; item < 110; item++) {
        if (UserActionCatalog.sampleExpose(user, item)) {
          hits++;
        }
      }
      assertEquals(3, hits, "用户 " + user + " 在 100..109 这十条里命中了 " + hits + " 条");
    }
  }

  @Test
  @DisplayName("相邻用户不能命中完全同一批条目，否则热榜会自我强化")
  void adjacentUsersDoNotShareTheSameHits() {
    Set<Long> user1 = hitsOf(1);
    Set<Long> user2 = hitsOf(2);
    assertEquals(3, user1.size());
    assertEquals(3, user2.size());
    // 若判据写成 (userId + itemId) % 10，这两个集合会逐字相同——整页所有用户看同三条加分
    assertFalse(user1.equals(user2), "相邻用户命中了完全相同的条目: " + user1);
  }

  private static Set<Long> hitsOf(long userId) {
    Set<Long> hits = new HashSet<>();
    for (long item = 1; item <= 10; item++) {
      if (UserActionCatalog.sampleExpose(userId, item)) {
        hits.add(item);
      }
    }
    return hits;
  }

  // ==================================================== 停留阈值

  @Test
  @DisplayName("停留 3 秒的边界：2999 不算、3000 算、null 与负数不算")
  void dwellThresholdBoundary() {
    assertEquals(3000, UserActionCatalog.VIEW_MIN_DURATION_MS);
    assertFalse(UserActionCatalog.isEnoughDwell(null));
    assertFalse(UserActionCatalog.isEnoughDwell(0));
    assertFalse(UserActionCatalog.isEnoughDwell(-1));
    assertFalse(UserActionCatalog.isEnoughDwell(2999));
    assertTrue(UserActionCatalog.isEnoughDwell(3000));
    assertTrue(UserActionCatalog.isEnoughDwell(3001));
    assertTrue(UserActionCatalog.isEnoughDwell(999_999));
  }

  // ==================================================== AI 赞踩

  @Test
  @DisplayName("赞踩三态：UP +2 / DOWN -2 / NONE 0；大小写空格不敏感；认不出按 NONE 计不抛")
  void aiFeedbackWeights() {
    assertEquals(new BigDecimal("2.00"), UserActionCatalog.aiFeedbackWeight("UP"));
    assertEquals(new BigDecimal("-2.00"), UserActionCatalog.aiFeedbackWeight("down"));
    assertEquals(BigDecimal.ZERO, UserActionCatalog.aiFeedbackWeight("  NONE "));
    assertEquals(BigDecimal.ZERO, UserActionCatalog.aiFeedbackWeight(null));
    // 认不出按 0 计而不是抛：埋点不该让接口 500。真正的取值白名单在 ConversationService 已拦过一次
    assertEquals(BigDecimal.ZERO, UserActionCatalog.aiFeedbackWeight("MAYBE"));
    assertEquals(BigDecimal.ZERO, UserActionCatalog.aiFeedbackWeight(""));
    // 一正一负必须对称，否则「大家都点踩」会被算成「大家都很喜欢」
    assertEquals(0, UserActionCatalog.AI_FEEDBACK_UP
        .add(UserActionCatalog.AI_FEEDBACK_DOWN).signum());
  }
}

package com.mindisle.emotion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.entity.WeeklyReport;
import com.mindisle.post.dto.CreatePostRequest;

/**
 * 周报去标识分享的单测（任务 T4.20 ③ · 手册 §7.5 第 3 条 · 需求 FR3.5、BR13）。
 *
 * <p><b>不用 {@code @SpringBootTest}，也不用真 Mapper / 真 {@code PostService}</b>：
 * {@link WeeklyReportShareService} 的两个端口在这里由两个内存假实现满足。这条路径上最贵的四件事
 * —— 别人能不能分享我的周报、分享过能不能再发第二条、公开正文里到底带了什么、
 * 发帖参数是不是「普通帖 + 强制匿名」—— 全是纯 Java 判断；而真 {@code PostService} 一发就是
 * 真库一行帖子 + 真机审 + 真配额，那正是答辩演示库最不该被 {@code mvn test} 污染的地方。</p>
 *
 * <p>第 4 组是这份测试里最值钱的一组：它把 fixture 的每个统计字段都写成一个<b>只在该字段出现的
 * 哨兵值</b>（8888 / 7777 / 3.14 / 0.567 / -0.12 / sadness / counts / trustedDays / meanValence），
 * 然后逐条断言正文里没有它们。之所以要一个个点名而不是「正文里不许有数字」：
 * 周区间 {@code 2026-09-21 ~ 2026-09-27} 本来就带数字，一条大而化之的断言只会把它自己误伤，
 * 然后被人当成噪音删掉 —— 那是断言最常见的死法。
 * {@code summaryText} 里的文案刻意写成不含任何数字的句子，理由同上。</p>
 */
@DisplayName("T4.20 ③ 情绪周报去标识分享")
class WeeklyReportShareServiceTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 10, 0, 0);
  private static final long OWNER = 2L;
  private static final long OTHER = 99L;
  private static final long REPORT_ID = 42L;

  // ==================================================== 假实现

  /** 内存周报行：记下行、记下 markShared 被调用了几次、可以指定「更新影响 0 行」。 */
  private static final class FakeStore implements WeeklyReportShareService.ReportStore {

    private final Map<Long, WeeklyReport> rows = new HashMap<>();
    private final List<String> marked = new ArrayList<>();
    private final List<Long> readIds = new ArrayList<>();

    FakeStore put(WeeklyReport row) {
      rows.put(row.getId(), row);
      return this;
    }

    @Override
    public WeeklyReport findById(long reportId) {
      readIds.add(reportId);
      return rows.get(reportId);
    }

    @Override
    public void markShared(long reportId, Long postId) {
      marked.add(reportId + "#" + postId);
      WeeklyReport row = rows.get(reportId);
      if (row != null) {
        row.setSharedFlag(1);
        row.setSharedPostId(postId);
      }
    }
  }

  /** 内存发帖：记下每次收到的 (userId, title, content)，并按脚本决定返回什么或抛什么。 */
  private static final class FakePublisher implements WeeklyReportShareService.Publisher {

    private final List<String> calls = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private final List<String> contents = new ArrayList<>();
    private int nextPostId = 900;
    private Boolean nullReturn;
    private RuntimeException boom;

    FakePublisher returnNullPostId() {
      nullReturn = Boolean.TRUE;
      return this;
    }

    FakePublisher throwIt(RuntimeException e) {
      boom = e;
      return this;
    }

    @Override
    public Posted publish(long userId, String title, String content, LocalDateTime now) {
      calls.add(userId + "@" + now);
      titles.add(title);
      contents.add(content);
      if (boom != null) {
        throw boom;
      }
      if (nullReturn != null) {
        return new Posted(null, "PUBLISHED", "匿名屿民·甲", null);
      }
      return new Posted((long) nextPostId++, "MACHINE_REVIEW", "匿名屿民·甲", "内容已进入机审");
    }
  }

  /**
   * 一份「什么脏东西都塞进去了」的周报：每个统计字段都是只属于它自己的哨兵值，
   * {@code summaryText} 反过来一句数字都没有 —— 这样「正文里出现了 7777」才是可归因的事实。
   */
  private static WeeklyReport fixture() {
    WeeklyReport row = new WeeklyReport();
    row.setId(REPORT_ID);
    row.setUserId(OWNER);
    row.setWeekStart(LocalDate.of(2026, 9, 21));
    row.setWeekEnd(LocalDate.of(2026, 9, 27));
    row.setCheckinDays(8888);
    row.setRecordCnt(7777);
    row.setDominantLabel("sadness");
    row.setAvgIntensity(new BigDecimal("3.14"));
    row.setPositiveRatio(new BigDecimal("0.567"));
    row.setTrendDelta(new BigDecimal("-0.12"));
    row.setInsight("{\"counts\":{\"joy\":3,\"sadness\":7},\"trustedDays\":999,"
        + "\"meanValence\":-0.33,\"negativeDominant\":true}");
    row.setSummaryText("这一周你把几次低落都说了出来，周末有一次明显轻松一些。");
    row.setGenerator("llm");
    row.setSharedFlag(0);
    row.setSharedPostId(null);
    return row;
  }

  private static WeeklyReportShareService svc(FakeStore store, FakePublisher pub) {
    return new WeeklyReportShareService(store, pub);
  }

  // ==================================================== 1 存在性与作者闸

  @Test
  @DisplayName("1.1 周报不存在（含已逻辑删除，端口按约定返回 null）→ 90006，且不发帖")
  void missingReportIsNotFound() {
    FakeStore store = new FakeStore();
    FakePublisher pub = new FakePublisher();
    BizException ex = assertThrows(BizException.class,
        () -> svc(store, pub).share(OWNER, REPORT_ID, NOW));
    assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    assertEquals(0, pub.calls.size(), "不存在的东西不能被发帖");
  }

  @Test
  @DisplayName("1.2 别人的周报 → 403，且一个字都不发（越权闸不能只写在界面上）")
  void otherUsersReportIsForbidden() {
    FakeStore store = new FakeStore().put(fixture());
    FakePublisher pub = new FakePublisher();
    BizException ex = assertThrows(BizException.class,
        () -> svc(store, pub).share(OTHER, REPORT_ID, NOW));
    assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    assertEquals(0, pub.calls.size(), "非作者不能产生任何公开内容");
    assertEquals(0, store.marked.size(), "非作者不能被写上 shared_flag");
  }

  @Test
  @DisplayName("1.3 行里 userId 为 null 也判 403（不能因为「取不到归属」就放行）")
  void nullOwnerIsForbiddenToo() {
    WeeklyReport row = fixture();
    row.setUserId(null);
    FakeStore store = new FakeStore().put(row);
    FakePublisher pub = new FakePublisher();
    BizException ex = assertThrows(BizException.class,
        () -> svc(store, pub).share(OWNER, REPORT_ID, NOW));
    assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
  }

  // ==================================================== 2 首次分享

  @Test
  @DisplayName("2.1 首次分享成功 → 回 postId 与机审状态，shared_flag 写成 1")
  void firstSharePublishesAndMarks() {
    FakeStore store = new FakeStore().put(fixture());
    FakePublisher pub = new FakePublisher();
    WeeklyReportShareService.ShareView view = svc(store, pub).share(OWNER, REPORT_ID, NOW);
    assertEquals(1, pub.calls.size(), "分享只发一条帖");
    assertEquals(OWNER, Long.parseLong(pub.calls.get(0).split("@")[0]), "帖子归属必须是作者本人，不是马甲");
    assertEquals(REPORT_ID, view.reportId().longValue(), "回的是周报行 id；用户 id 只在发帖那一侧");
    assertEquals(900L, view.postId().longValue());
    assertFalse(view.alreadyShared());
    assertEquals("MACHINE_REVIEW", view.postStatus(), "机审没放行时不能对用户谎称已发布");
    assertEquals("匿名屿民·甲", view.displayName());
    assertNotNull(view.tip(), "审核链给作者的那句话要原样带到界面上");
    assertEquals(List.of(REPORT_ID + "#900"), store.marked);
    assertEquals(Integer.valueOf(1), store.rows.get(REPORT_ID).getSharedFlag(), "分享之后 shared_flag=1");
    assertEquals(900L, store.rows.get(REPORT_ID).getSharedPostId().longValue(), "postId 回填进行");
  }

  @Test
  @DisplayName("2.2 发帖抛错 → 原样上抛，且不写 shared_flag（否则界面会说谎）")
  void publisherFailureDoesNotMark() {
    FakeStore store = new FakeStore().put(fixture());
    FakePublisher pub = new FakePublisher().throwIt(
        new BizException(ErrorCode.RATE_LIMITED, "今日发帖配额已用完"));
    BizException ex = assertThrows(BizException.class,
        () -> svc(store, pub).share(OWNER, REPORT_ID, NOW));
    assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode(), "失败原因要原样给用户，不许被包成 90004");
    assertEquals(0, store.marked.size());
  }

  @Test
  @DisplayName("2.3 端口违约（返回 null 或没有 postId）→ 90004 且不写标记")
  void brokenPortContractDoesNotMark() {
    FakeStore store = new FakeStore().put(fixture());
    FakePublisher pub = new FakePublisher().returnNullPostId();
    BizException ex = assertThrows(BizException.class,
        () -> svc(store, pub).share(OWNER, REPORT_ID, NOW));
    assertEquals(ErrorCode.INTERNAL_ERROR, ex.getErrorCode());
    assertEquals(0, store.marked.size());
  }

  // ==================================================== 3 幂等

  @Test
  @DisplayName("3.1 已分享过 → 回放原帖 id，第二次 publish 与 markShared 都不许发生")
  void secondShareIsReplayOnly() {
    WeeklyReport row = fixture();
    row.setSharedFlag(1);
    row.setSharedPostId(777L);
    FakeStore store = new FakeStore().put(row);
    FakePublisher pub = new FakePublisher();
    WeeklyReportShareService.ShareView view = svc(store, pub).share(OWNER, REPORT_ID, NOW);
    assertTrue(view.alreadyShared());
    assertEquals(777L, view.postId());
    assertEquals(0, pub.calls.size(), "幂等的定义就是不再发第二条");
    assertEquals(0, store.marked.size());
    assertNull(view.postStatus(), "回放不重新读帖子：帖子的权威状态归详情接口，这里不复制第二份口径");
  }

  @Test
  @DisplayName("3.2 幂等判据是「flag=1 且 postId 非空」两个条件同时成立（缺一都会把用户锁死）")
  void halfWrittenStateIsRepaired() {
    // flag=1 / postId=null：老数据或半写状态。若只看 flag，这个人永远点不出分享，
    // 而界面上写着「已分享」，广场上看不到 —— 必须重发一次把 postId 补上。
    WeeklyReport a = fixture();
    a.setSharedFlag(1);
    a.setSharedPostId(null);
    FakeStore storeA = new FakeStore().put(a);
    FakePublisher pubA = new FakePublisher();
    WeeklyReportShareService.ShareView va = svc(storeA, pubA).share(OWNER, REPORT_ID, NOW);
    assertFalse(va.alreadyShared());
    assertEquals(1, pubA.calls.size());

    // flag=0 / postId=555：另一种半写状态，同样重发（这条的存在让「只看 postId」的实现当场跑红）。
    WeeklyReport b = fixture();
    b.setSharedFlag(0);
    b.setSharedPostId(555L);
    FakeStore storeB = new FakeStore().put(b);
    FakePublisher pubB = new FakePublisher();
    assertFalse(svc(storeB, pubB).share(OWNER, REPORT_ID, NOW).alreadyShared());
    assertEquals(1, pubB.calls.size());
  }

  // ==================================================== 4 去标识正文（最值钱的一组）

  @Test
  @DisplayName("4.1 正文含周报结论与周区间，含「AI 撰写」与「不做诊断」两句口径")
  void contentCarriesTheReportAndTheDisclaimer() {
    FakeStore store = new FakeStore().put(fixture());
    FakePublisher pub = new FakePublisher();
    svc(store, pub).share(OWNER, REPORT_ID, NOW);
    String content = pub.contents.get(0);
    assertTrue(content.contains("这一周你把几次低落都说了出来，周末有一次明显轻松一些。"));
    assertTrue(content.contains("2026-09-21 ~ 2026-09-27"));
    assertTrue(content.contains("由 AI 陪伴模型撰写"), "模板冒充模型结论是 FR3.5 明令禁止的");
    assertTrue(content.contains("心屿不做诊断"), "公开内容也要带着不做诊断的口径");
    assertTrue(content.contains("分享已去标识"));
  }

  @Test
  @DisplayName("4.2 正文不含 insight JSON、五个统计列、主导情绪标签")
  void contentOmitsEveryAggregateField() {
    FakeStore store = new FakeStore().put(fixture());
    FakePublisher pub = new FakePublisher();
    svc(store, pub).share(OWNER, REPORT_ID, NOW);
    String content = pub.contents.get(0);
    Map<String, String> forbidden = new LinkedHashMap<>();
    forbidden.put("checkin_days 哨兵", "8888");
    forbidden.put("record_cnt 哨兵", "7777");
    forbidden.put("avg_intensity 哨兵", "3.14");
    forbidden.put("positive_ratio 哨兵", "0.567");
    forbidden.put("trend_delta 哨兵", "-0.12");
    forbidden.put("dominant_label", "sadness");
    forbidden.put("insight: counts", "counts");
    forbidden.put("insight: trustedDays", "trustedDays");
    forbidden.put("insight: meanValence", "meanValence");
    forbidden.put("insight: negativeDominant", "negativeDominant");
    forbidden.put("insight: joy 键", "\"joy\"");
    List<String> leaked = new ArrayList<>();
    for (Map.Entry<String, String> e : forbidden.entrySet()) {
      if (content.contains(e.getValue())) {
        leaked.add(e.getKey() + "=" + e.getValue());
      }
    }
    assertTrue(leaked.isEmpty(), () -> "正文泄露了聚合明细字段：" + leaked);
  }

  @Test
  @DisplayName("4.3 模板周报也要如实写明「本地模板生成」，不含模型字样")
  void templateReportSaysSo() {
    WeeklyReport row = fixture();
    row.setGenerator("template");
    FakeStore store = new FakeStore().put(row);
    FakePublisher pub = new FakePublisher();
    svc(store, pub).share(OWNER, REPORT_ID, NOW);
    assertTrue(pub.contents.get(0).contains("本地模板"));
    assertFalse(pub.contents.get(0).contains("由 AI 陪伴模型撰写"));
  }

  @Test
  @DisplayName("4.4 标题只有前缀与周区间，不含用户名与情绪标签")
  void titleIsRangeOnly() {
    FakeStore store = new FakeStore().put(fixture());
    FakePublisher pub = new FakePublisher();
    svc(store, pub).share(OWNER, REPORT_ID, NOW);
    assertEquals("情绪周报 · 2026-09-21 ~ 2026-09-27", pub.titles.get(0));
    assertFalse(pub.titles.get(0).contains("sadness"));
    assertFalse(pub.titles.get(0).contains("7777"));
  }

  @Test
  @DisplayName("4.5 周区间为 null 时渲染成空串，正文与标题里不许出现字面量 null")
  void nullRangeNeverPrintsNull() {
    WeeklyReport row = fixture();
    row.setWeekStart(null);
    row.setWeekEnd(null);
    FakeStore store = new FakeStore().put(row);
    FakePublisher pub = new FakePublisher();
    svc(store, pub).share(OWNER, REPORT_ID, NOW);
    assertFalse(pub.contents.get(0).contains("null"), () -> "正文里出现了字面量 null：" + pub.contents.get(0));
    assertFalse(pub.titles.get(0).contains("null"));
  }

  @Test
  @DisplayName("4.6 没有结论文案的周报不能分享（10001），而不是发出一条空帖")
  void blankSummaryIsRejected() {
    WeeklyReport blank = fixture();
    blank.setSummaryText("   ");
    FakeStore store = new FakeStore().put(blank);
    FakePublisher pub = new FakePublisher();
    BizException ex = assertThrows(BizException.class,
        () -> svc(store, pub).share(OWNER, REPORT_ID, NOW));
    assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    assertEquals(0, pub.calls.size());

    WeeklyReport nullish = fixture();
    nullish.setSummaryText(null);
    FakeStore store2 = new FakeStore().put(nullish);
    FakePublisher pub2 = new FakePublisher();
    assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
        () -> svc(store2, pub2).share(OWNER, REPORT_ID, NOW)).getErrorCode());
  }

  // ==================================================== 5 端口与调用形状

  @Test
  @DisplayName("5.1 now 由调用方传入并一路透到发帖（时间基准只许有一个）")
  void nowIsThreadedThrough() {
    FakeStore store = new FakeStore().put(fixture());
    FakePublisher pub = new FakePublisher();
    svc(store, pub).share(OWNER, REPORT_ID, NOW);
    assertEquals(List.of(OWNER + "@" + NOW), pub.calls);
  }

  @Test
  @DisplayName("5.2 一次分享只读一行周报（幂等与作者判据都靠这一行，重复读会读到两个版本）")
  void readsExactlyOneRow() {
    FakeStore store = new FakeStore().put(fixture());
    FakePublisher pub = new FakePublisher();
    svc(store, pub).share(OWNER, REPORT_ID, NOW);
    assertEquals(List.of(REPORT_ID), store.readIds);
    FakeStore store2 = new FakeStore().put(secondRow());
    FakePublisher pub2 = new FakePublisher();
    svc(store2, pub2).share(OWNER, REPORT_ID, NOW);
    assertEquals(List.of(REPORT_ID), store2.readIds, "回放路径也只能读一次");
    assertEquals(0, pub2.calls.size());
  }

  /** 一份「已经分享过」的周报，第 3.1 与第 5.2 组共用它的形状。 */
  private static WeeklyReport secondRow() {
    WeeklyReport row = fixture();
    row.setSharedFlag(1);
    row.setSharedPostId(777L);
    return row;
  }

  // ==================================================== 6 发帖入参（协议层的去标识）

  @Test
  @DisplayName("6.1 分享帖入参锁死为「普通帖 + 强制匿名 + 不销毁 + 无话题无图」")
  void shareRequestParametersAreLocked() {
    CreatePostRequest req = WeeklyReportShareStore.shareRequest("T", "C");
    assertEquals("T", req.title());
    assertEquals("C", req.content());
    assertEquals("normal", req.type(), "手册写的是「普通 post」；树洞会 7 天后销毁，求助会进危机通道");
    assertEquals(Boolean.TRUE, req.anonymous(), "去标识 = 马甲名，不是昵称");
    assertNull(req.visibility(), "null 走配置默认（public），不在这里替用户改可见范围");
    assertNull(req.autoDestroyHours(), "非树洞帖传这个值会被 PostService 判 10001");
    assertNull(req.topicIds(), "不替用户挑话题");
    assertNull(req.images());
  }
}

package com.mindisle.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.User;

/**
 * 注销冷静期状态机的单测（任务 T4.21 · 手册 §7.5「active → cooling → purged」）。
 *
 * <p>这个类只有六个静态方法，看起来不值得测，但它管的是<b>整条隐私链里唯一不可逆的那一步的门票</b>：
 * {@code DataRetentionJob} 只清 {@code isCooling} 判假的账号，而 {@code AuthService#login} 只把
 * {@code isCooling} 判真的账号捞回 ACTIVE。两边读的是同一个判断，判断错一天的后果要么是
 * 「用户在 30 天窗口内登录被拒」，要么是「窗口还没走完就被物理清除」——两者都超出「一个 bug」的量级。
 * 所以本类把边界钉到<b>秒</b>：到期那一刻算不算还在期内（答案是不算，{@code isBefore} 而非 {@code <=}），
 * 以及数据不全（时间列为 null）时往哪边偏（答案是偏「不可自动撤回」，因为它只会让用户多找一次客服）。</p>
 *
 * <p>最后一组是<b>跨类</b>对账：兜底天数 {@code DEFAULT_COOLING_DAYS} 与
 * {@code mindisle.privacy.cooling-days} 的默认值必须相等。这两处一个写在 Java 常量里、一个写在
 * {@code application.yml} 的绑定类里，需求原文只有一个「30 天」。哪天有人只改一处，
 * 表现形式是「带配置文件跑是 30 天、某些单测与手滑漏配的路径跑成另一天数」，
 * 而这种偏差不会报错，只会让不同账号的到期时刻差着几天。</p>
 */
@DisplayName("T4.21 注销冷静期状态机")
class CoolingStateTest {

  private static final LocalDateTime T0 = LocalDateTime.of(2026, 3, 15, 10, 30, 0);

  // ============================================================ 助手

  private static User user(String status, LocalDateTime deactivateAt, LocalDateTime purgeAt) {
    User u = new User();
    u.setId(7L);
    u.setUsername("cooling-case");
    u.setStatus(status);
    u.setDeactivateAt(deactivateAt);
    u.setPurgeAt(purgeAt);
    return u;
  }

  /** 造一个「刚提交注销 days 天」的账号，与生产写入路径（deactivate）走同一条。 */
  private static User coolingUser(int days) {
    User u = new User();
    CoolingState.deactivate(u, T0, days);
    return u;
  }

  // ==================================================== 1. 到期时刻的算法

  @Test
  @DisplayName("到期时刻 = 提交时刻 + N 天，逐秒精确，不取整到日")
  void purgeTimeIsExactDayArithmetic() {
    assertEquals(LocalDateTime.of(2026, 4, 14, 10, 30, 0), CoolingState.purgeTime(T0, 30),
        "30 天窗口应该落在同一天的同一时刻；若这里被改成先加天数再截断到日，"
            + "到期判定就会凭空多出或少掉最多一天");
    // 跨月的算术单独钉一条：1 月 31 日 + 30 天，2026 年不是闰年，2 月只有 28 天
    assertEquals(LocalDateTime.of(2026, 3, 2, 10, 30, 0),
        CoolingState.purgeTime(LocalDateTime.of(2026, 1, 31, 10, 30, 0), 30),
        "跨月天数被算错时，用户的到期时刻会整批偏移，而库里看不出来");
  }

  @Test
  @DisplayName("冷静期天数 0 或负数一律夹成 1 天：配置手滑不能把撤回窗口清零")
  void nonPositiveCoolingDaysIsClampedToOneDay() {
    LocalDateTime one = CoolingState.purgeTime(T0, 1);
    assertEquals(one, CoolingState.purgeTime(T0, 0), "写 0 天等于提交注销当刻即可清除，必须夹成 1 天");
    assertEquals(one, CoolingState.purgeTime(T0, -1), "负数与 0 同命");
    assertEquals(one, CoolingState.purgeTime(T0, -9999));
    assertTrue(CoolingState.purgeTime(T0, -1).isAfter(T0), "夹完之后的到期时刻必须严格晚于提交时刻");
  }

  // ==================================================== 2. isCooling 的三态边界

  @Test
  @DisplayName("isCooling 三态：到期前真 / 到期那一刻假 / 到期后假")
  void isCoolingBoundaryIsStrictlyBeforePurgeAt() {
    User u = coolingUser(30);
    LocalDateTime purgeAt = u.getPurgeAt();
    assertTrue(CoolingState.isCooling(u, purgeAt.minusNanos(1000000)),
        "到期前 1 毫秒仍在冷静期内，此刻登录应当被自动撤回注销");
    assertFalse(CoolingState.isCooling(u, purgeAt),
        "到期那一刻判假是刻意的：isBefore 而不是 <=，否则清除任务与用户登录会在同一秒抢同一条记录");
    assertFalse(CoolingState.isCooling(u, purgeAt.plusNanos(1000000)), "过期之后不再算冷静期");
    assertFalse(CoolingState.isCooling(u, purgeAt.plusDays(365)));
  }

  @Test
  @DisplayName("状态不是 DELETED 一律判假：ACTIVE 与 MUTED/BANNED 都不算冷静期")
  void onlyDeletedStatusCanBeCooling() {
    LocalDateTime future = T0.plusDays(30);
    assertFalse(CoolingState.isCooling(user(CoolingState.ACTIVE, T0, future), T0),
        "正常账号即便留着时间列也不能算冷静期，否则撤回注销之后永远撤回不完");
    for (String other : Arrays.asList("MUTED", "BANNED", null, "", "deleted")) {
      assertFalse(CoolingState.isCooling(user(other, T0, future), T0),
          "非 DELETED 状态判假；大小写敏感的 deleted 尤其不能被当成 DELETED：" + other);
    }
  }

  @Test
  @DisplayName("时间列为 null 或 user 为 null 时判假：数据不一致偏「不可自动撤回」那一边")
  void missingTimestampsFailClosed() {
    LocalDateTime future = T0.plusDays(30);
    assertFalse(CoolingState.isCooling(user(CoolingState.DELETED, null, future), T0),
        "缺提交时刻的账号按不可撤回处理");
    assertFalse(CoolingState.isCooling(user(CoolingState.DELETED, T0, null), T0),
        "缺到期时刻的账号按不可撤回处理；判真的话它永远清不掉");
    assertFalse(CoolingState.isCooling(user(CoolingState.DELETED, null, null), T0));
    assertFalse(CoolingState.isCooling(null, T0), "null 账号不能抛 NPE，调用方是从库里捞出来的裸对象");
  }

  // ==================================================== 3. 写入与擦除

  @Test
  @DisplayName("deactivate 一次写满三字段，且写完立刻能被 isCooling 认出")
  void deactivateWritesAllThreeFieldsAndIsSelfConsistent() {
    User u = new User();
    u.setStatus(CoolingState.ACTIVE);
    CoolingState.deactivate(u, T0, 30);
    assertEquals(CoolingState.DELETED, u.getStatus(), "状态值必须与 user.status 的 ENUM 逐字一致");
    assertEquals(T0, u.getDeactivateAt());
    assertEquals(CoolingState.purgeTime(T0, 30), u.getPurgeAt(),
        "purge_at 必须是 deactivate_at + 窗口，两列各算一次就会和清除任务的扫描条件对不上");
    assertTrue(CoolingState.isCooling(u, T0), "刚提交注销的那一刻当然还在窗口内");
    assertEquals(30L, CoolingState.remainDays(u, T0));
  }

  @Test
  @DisplayName("restore 把两列擦成 null：留着过期的旧 purge_at 会把下一次注销算成已到期")
  void restoreClearsTimestampsSoSecondDeactivationStartsFresh() {
    User u = coolingUser(30);
    LocalDateTime firstPurgeAt = u.getPurgeAt();
    CoolingState.restore(u);
    assertEquals(CoolingState.ACTIVE, u.getStatus());
    assertNull(u.getDeactivateAt(), "撤回注销必须显式置 null，且落库要走 UserMapper#restoreActive 的显式 @Update");
    assertNull(u.getPurgeAt());
    assertFalse(CoolingState.isCooling(u, T0), "已撤回的账号不该再被当成冷静期");
    // 再注销一次：到期时刻必须相对新提交时刻重新计算，不能被第一次的残留值污染
    LocalDateTime t1 = T0.plusDays(100);
    CoolingState.deactivate(u, t1, 30);
    assertEquals(CoolingState.purgeTime(t1, 30), u.getPurgeAt(), "第二次注销的到期时刻必须重算");
    assertFalse(firstPurgeAt.equals(u.getPurgeAt()), "到期时刻若等于第一次的残留值，说明没擦干净");
    assertTrue(CoolingState.isCooling(u, t1), "重算之后必须还在窗口内，否则用户刚注销就被列入清除批次");
  }

  // ==================================================== 4. 剩余天数

  @Test
  @DisplayName("remainDays 按整天向下取整、永不为负、不在期内恒 0")
  void remainDaysNeverNegativeAndZeroOutsideWindow() {
    assertEquals(0L, CoolingState.remainDays(null, T0), "null 账号返回 0，调用方按 0 不显示倒计时");
    assertEquals(0L, CoolingState.remainDays(user(CoolingState.ACTIVE, T0, T0.plusDays(9)), T0));
    // 不足一天不计：23 小时之后是 0 而不是 1，界面上「还剩 0 天」与「已到期」由 cooling 布尔位区分
    assertEquals(0L, CoolingState.remainDays(coolingUserAt(T0, T0.plusHours(23)), T0),
        "remainDays 用的是 DAYS.between，语义是向下取整");
    assertEquals(1L, CoolingState.remainDays(coolingUserAt(T0, T0.plusHours(36)), T0));
    assertEquals(29L, CoolingState.remainDays(coolingUserAt(T0, T0.plusDays(29).plusMinutes(1)), T0));
    // 到期之后：isCooling 判假，剩余天数必须停在 0，绝不返回负数给前端显示「还剩 -3 天」
    List<LocalDateTime> probes = new ArrayList<>();
    for (int d = 0; d <= 40; d++) {
      probes.add(T0.plusDays(d));
    }
    User u = coolingUser(30);
    for (LocalDateTime now : probes) {
      assertTrue(CoolingState.remainDays(u, now) >= 0L, "剩余天数出现负数：" + now);
    }
    assertEquals(0L, CoolingState.remainDays(u, u.getPurgeAt().plusDays(10)));
  }

  /** 直接指定到期时刻造账号，便于钉住取整方向。 */
  private static User coolingUserAt(LocalDateTime submittedAt, LocalDateTime purgeAt) {
    return user(CoolingState.DELETED, submittedAt, purgeAt);
  }

  // ==================================================== 5. 跨类对账

  @Test
  @DisplayName("状态常量与兜底天数：常量逐字对齐 DDL 的 ENUM，兜底天数对齐配置默认值")
  void constantsAgreeWithDdlAndConfig() {
    assertEquals("ACTIVE", CoolingState.ACTIVE, "user.status 的 ENUM 写的是 ACTIVE，这里改一个字母就查无此人");
    assertEquals("DELETED", CoolingState.DELETED);
    MindisleProperties props = new MindisleProperties();
    assertEquals(CoolingState.DEFAULT_COOLING_DAYS, props.getPrivacy().getCoolingDays(),
        "配置默认值与代码兜底值必须相等：需求只有一个 30 天，两处各写各的就会让不同账号的到期时刻差几天");
    assertEquals(30, CoolingState.DEFAULT_COOLING_DAYS, "手册 §7.5 与需求 BR11 都是 30 天");
  }
}

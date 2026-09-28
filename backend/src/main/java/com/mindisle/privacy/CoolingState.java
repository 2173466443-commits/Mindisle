package com.mindisle.privacy;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import com.mindisle.entity.User;

/**
 * 注销冷静期的状态判定（任务 T4.21 · 手册 §7.5「active → cooling → purged」状态机）。
 *
 * <p><b>为什么是纯静态类而不是一个 Service</b>：这里三件事 —— 打上冷静期标记、判断现在还在不在
 * 冷静期内、把标记擦掉 —— 全是「读几个字段改几个字段」的纯函数，不碰库、不碰缓存、不碰时间以外的依赖。
 * 而它有两个相距很远的调用方：{@code AuthService#login}（冷静期内登录 = 自动撤回注销）与
 * {@code PrivacyAccountService}（本人提交注销 / 本人撤回注销）。
 * 若把这段逻辑做成 bean 注入进 AuthService，依赖会变成
 * AuthService → PrivacyAccountService → UserService → AuthService 一个<b>环</b>
 * （{@code UserService} 已经依赖 {@code AuthService}，见其构造器第 42 行），
 * Spring Boot 3 之后默认禁止循环引用，应用直接起不来。纯静态让两个调用方共用同一份判断，
 * 又不给容器新增任何一条边 —— 这是「先画依赖图再决定放哪」的结果，不是随手写的方法。</p>
 *
 * <p><b>状态值用字符串而不是 Java 枚举</b>：与 {@link User#getStatus()} 同一个理由 ——
 * DDL 是 {@code ENUM('ACTIVE','MUTED','BANNED','DELETED')}，再造一个枚举等于多一处要和 DDL 对齐的地方，
 * 而对不齐的报错形式是 1265 Data truncated（{@code AuthService.GRADES} 的注释里记过一次）。</p>
 *
 * <p><b>{@code DELETED} 在本系统的语义是「冷静期」而不是「已清除」</b>：需求把注销定义成 30 天可撤回窗口，
 * 真正的「已清除」是行不存在。这个名字是 DDL 里先有的（sql/01 第 20 行的 ENUM 取值），
 * 这里只是沿用，故在注释里把话说死，免得下一个读代码的人以为删行已经完成。</p>
 */
public final class CoolingState {

  /** 与 user.status 的 ENUM 逐字一致。另一份同样的定义在 {@code AuthService}（写入侧）与 {@code PostQueryService.AUTHOR_ACTIVE}（读侧）。 */
  public static final String ACTIVE = "ACTIVE";

  /** 冷静期中的账号态：登录仍可进（并自动撤回），但已不进推荐与搜索。 */
  public static final String DELETED = "DELETED";

  /** 配置缺失时的兜底窗口，与手册 §7.5「冷静期 30 天」一致。 */
  public static final int DEFAULT_COOLING_DAYS = 30;

  /**
   * 到期时刻 = 提交时刻 + 冷静期天数。
   *
   * <p>{@code Math.max(1, ...)} 而不是 {@code <= 0} 直接放行：配置写 0 或负数多半是手滑，
   * 而它的后果是「提交注销的那一刻就到期」—— 冷静期归零等于取消了这个制度。
   * 宁可留出一天的反悔窗口。</p>
   */
  public static LocalDateTime purgeTime(LocalDateTime submittedAt, int coolingDays) {
    return submittedAt.plusDays(Math.max(1, coolingDays));
  }

  /**
   * 打上注销标记（内存对象）。落库由调用方负责，且<b>必须带上两列时间</b>，
   * 否则 {@link #isCooling} 会因为时间为空而判假，用户会「注销成功但再也登不进来」。
   */
  public static void deactivate(User user, LocalDateTime now, int coolingDays) {
    user.setStatus(DELETED);
    user.setDeactivateAt(now);
    user.setPurgeAt(purgeTime(now, coolingDays));
  }

  /**
   * 现在是否还在冷静期内。三个条件缺一不可：
   * 状态是 DELETED、两个时间列都还在、当前时刻早于 purge_at。
   *
   * <p>时间列为空返回 {@code false} 是对的：那是「数据不一致」的账号，
   * 按「不可自动撤回」处理比按「可撤回」处理更安全 —— 前者只让用户多走一次客服，
   * 后者会把一个已经进了清除队列的账号重新点亮。</p>
   */
  public static boolean isCooling(User user, LocalDateTime now) {
    if (user == null || !DELETED.equals(user.getStatus())) {
      return false;
    }
    if (user.getDeactivateAt() == null || user.getPurgeAt() == null) {
      return false;
    }
    return now.isBefore(user.getPurgeAt());
  }

  /**
   * 擦掉注销标记（内存对象）。两列必须显式置 null —— 留着一个已经过去的 purge_at，
   * 下一次再注销时 {@link #isCooling} 会读到老时刻，把新冷静期算成「已经到期」。
   *
   * <p>🔴 置 null 只改内存是不够的：{@code UserService} 之外没人写过这两列，而
   * MyBatis-Plus 的 {@code updateById} 默认字段策略是 NOT_NULL，
   * <b>null 字段根本不会进 UPDATE 语句</b> —— 内存改了、库里没改、接口返回 200。
   * 所以撤回注销走 {@code UserMapper#restoreActive} 那条显式 @Update。
   * 这是本轮踩到的最贵的一颗坑，已记 dev-log。</p>
   */
  public static void restore(User user) {
    user.setStatus(ACTIVE);
    user.setDeactivateAt(null);
    user.setPurgeAt(null);
  }

  /** 还剩几天可撤回（向下取整到整天：ChronoUnit.DAYS 只数完整的 24 小时，不足一天不计）。不在冷静期就返回 0，调用方按 0 不显示倒计时即可。 */
  public static long remainDays(User user, LocalDateTime now) {
    if (!isCooling(user, now)) {
      return 0L;
    }
    long days = ChronoUnit.DAYS.between(now, user.getPurgeAt());
    return days < 0 ? 0L : days;
  }

  private CoolingState() {
  }
}

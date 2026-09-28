package com.mindisle.privacy;

import java.time.LocalDateTime;
import java.util.List;

import com.mindisle.entity.User;

/**
 * 隐私中心的出参记录（任务 T4.21）。放在一个文件里而不是拆五个 dto 文件，
 * 是因为这四个 record 没有任何行为，只是「把这一页要显示的东西一次定死」。
 *
 * <p><b>{@link AccountFacts} 是本文件里唯一有理由存在的一段</b>：账号白名单字段必须
 * 在「概览页」和「导出包」两处完全一致 —— 否则会出现「界面上说导出里有 email、
 * 包里其实没有」。所以映射只写一次（{@link #of(User)}），两处都调它，
 * 而 {@code password} 从一开始就不在候选列里（不是「导出时记得排除」，
 * 是根本没有一条路径能把password 带出去）。</p>
 */
public final class PrivacyViews {

  /**
   * 一个隐私域的读数摘要。{@code truncated} 为 true 表示这一域的行数超过了
   * {@code mindisle.privacy.max-rows-per-table}，导出包里的份数会少于库里真实份数 ——
   * 需求 D11 要求「条数可对账」，对账的前提是截断这件事本身可见。
   */
  public record DomainCount(String table, long rows, boolean truncated) {
  }

  /**
   * 账号本体可导出的字段。<b>类存在本身就是白名单</b>：加字段要显式改这里，
   * 改完就同时改到概览页与导出包，不会只改一处。
   */
  public record AccountFacts(Long id, String username, String nickname, String email, String role,
      String status, String aiStyle, String regSource, LocalDateTime createdAt,
      LocalDateTime lastLoginAt, LocalDateTime agreePrivacyAt, LocalDateTime deactivateAt,
      LocalDateTime purgeAt) {

    /** user 整行 → 白名单字段。这一行是「绝不含 password」唯一的执行点。 */
    public static AccountFacts of(User user) {
      return new AccountFacts(user.getId(), user.getUsername(), user.getNickname(), user.getEmail(),
          user.getRole(), user.getStatus(), user.getAiStyle(), user.getRegSource(), user.getCreatedAt(),
          user.getLastLoginAt(), user.getAgreePrivacyAt(), user.getDeactivateAt(), user.getPurgeAt());
    }
  }

  /** 我的数据概览（GET /api/privacy/summary）。逐域条数 + 冷静期状态 + 账号本体字段。 */
  public record Summary(LocalDateTime generatedAt, AccountFacts account, String accountStatus,
      boolean cooling, LocalDateTime deactivateAt, LocalDateTime purgeAt, long coolingRemainDays,
      int coolingDays, long totalRows, List<DomainCount> domains) {
  }

  /**
   * 注销 / 撤回注销的结果。{@code message} 是给本人看的一句话，
   * 讲清楚「现在处在哪个状态、什么时候会被真的清掉」——
   * 合规动作不能只回一个 200，当事人必须知道冷静期还有几天。
   */
  public record DeactivateView(String status, LocalDateTime deactivateAt, LocalDateTime purgeAt,
      int coolingDays, long coolingRemainDays, boolean cooling, String message) {
  }

  /**
   * 导出任务的外显视图。<b>不含 filePath</b>：那是服务器磁盘上的绝对路径，
   * 出现在任何响应里都是白送的目录结构情报（NFR7）。
   * {@code downloadReady} 把「能不能下载」压成一个布尔，前端不必再去比 status 与 token 谁非空。
   */
  public record ExportTaskView(Long id, String format, String status, long fileBytes,
      String rowCountSummary, boolean downloadReady, String downloadPath, LocalDateTime expireAt,
      LocalDateTime createdAt, String errorText) {
  }

  private PrivacyViews() {
  }
}

package com.mindisle.privacy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mindisle.auth.dto.ConsentRequest;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.User;
import com.mindisle.entity.UserConsent;
import com.mindisle.mapper.UserMapper;
import com.mindisle.privacy.PrivacyDomains.Domain;
import com.mindisle.security.JwtService;
import com.mindisle.user.UserService;

/**
 * 「我的隐私」账号侧服务（任务 T4.21 · 需求 FR1.6、NFR8 · 手册 §7.5）。
 * 三件事：我的数据概览、提交注销、撤回注销；外加一条撤回授权（委托 {@link UserService}）。
 *
 * <p><b>概览的条数为什么走 {@link PrivacyStore#countRows} 而不是 {@code SELECT *}</b>：
 * 概览页要回答的是「系统里存了我多少条什么」，读全部行再数个数是把最贵的一步
 * （几十张表、可能几万行）为一个数字付掉。而它必须与导出用<b>同一条归属谓词</b>，
 * 否则「概览说 128 条、包里有 120 条」这种对不上账的事就会在答辩现场出现 ——
 * D11 的验收判据正是这两处相等，端口把两条路钉在同一个 {@code where()} 上不是巧合。</p>
 *
 * <p><b>注销不删任何一行数据</b>：只把 {@code user.status} 写成 DELETED 并记下两个时间戳。
 * 需求把 30 天定义成冷静期（手册 §7.5），真正的清除在 {@link PrivacyPurgeService}，
 * 由 {@link DataRetentionJob} 在到期后触发。这里如果顺手删了行，冷静期就失去意义，
 * 而「误操作注销」是这类系统最常见的用户事故。</p>
 *
 * <p><b>重复提交注销是幂等的</b>：第二次调用不会把 {@code purge_at} 往后推。
 * 否则「每天点一次注销」就能让冷静期永远不到期，一个想彻底退出的用户反而退不出去 ——
 * 这是把「可撤回窗口」做成了「可无限延期」。到期时刻只由<b>第一次</b>提交决定。</p>
 */
@Service
public class PrivacyAccountService {

  private static final Logger log = LoggerFactory.getLogger(PrivacyAccountService.class);

  private final UserMapper userMapper;
  private final PrivacyStore store;
  private final UserService userService;
  private final JwtService jwtService;
  private final MindisleProperties properties;

  public PrivacyAccountService(UserMapper userMapper, PrivacyStore store, UserService userService,
      JwtService jwtService, MindisleProperties properties) {
    this.userMapper = userMapper;
    this.store = store;
    this.userService = userService;
    this.jwtService = jwtService;
    this.properties = properties;
  }

  /**
   * 我的数据概览。逐可导出域数一遍行，加上冷静期状态与账号本体字段。
   *
   * <p>{@code user} 主行不在 domains 里（注册表把它标成 {@code exportable = false}），
   * 它单独以 {@link PrivacyViews.AccountFacts} 出现 —— 因为「我的账号」和「我的数据」
   * 在界面上是两块，而在库里是同一行，让它出现在两个地方会让用户以为有两套数据。</p>
   */
  public PrivacyViews.Summary summary(long userId) {
    User user = requireUser(userId);
    LocalDateTime now = LocalDateTime.now();
    MindisleProperties.Privacy cfg = properties.getPrivacy();
    int maxRows = Math.max(1, cfg.getMaxRowsPerTable());
    List<Long> postIds = store.postIdsOf(userId);

    List<PrivacyViews.DomainCount> domains = new ArrayList<>();
    long total = 0L;
    for (Domain d : PrivacyDomains.exportable()) {
      long rows = store.countRows(d, userId, postIds);
      total += rows;
      domains.add(new PrivacyViews.DomainCount(d.table(), rows, rows > maxRows));
    }
    boolean cooling = CoolingState.isCooling(user, now);
    return new PrivacyViews.Summary(now, PrivacyViews.AccountFacts.of(user), user.getStatus(), cooling,
        user.getDeactivateAt(), user.getPurgeAt(), CoolingState.remainDays(user, now),
        Math.max(1, cfg.getCoolingDays()), total, domains);
  }

  /**
   * 提交注销：写冷静期标记，并立刻作废当前会话令牌。
   *
   * <p><b>为什么 revoke 掉令牌</b>：注销提交成功之后，界面要把用户送出产品。
   * 留着令牌的话，他会话里剩下的每一次操作都在继续产生新的个人数据
   * （浏览埋点、AI 调用日志），「我已经注销了」与「系统还在记我的动作」同时为真，
   * 这在合规叙述里是站不住的。 revoke 之后他还能重新登录 —— 而且登录即撤回注销，
   * 这正是冷静期的设计。</p>
   */
  public PrivacyViews.DeactivateView deactivate(long userId) {
    User user = requireUser(userId);
    LocalDateTime now = LocalDateTime.now();
    int coolingDays = Math.max(1, properties.getPrivacy().getCoolingDays());
    if (CoolingState.isCooling(user, now)) {
      log.info("重复提交注销，按幂等处理（不延长冷静期）id={} purge_at={}", userId, user.getPurgeAt());
      return view(user, now, coolingDays, "注销申请已在处理中，冷静期内登录即可撤回");
    }
    if (!CoolingState.ACTIVE.equals(user.getStatus())) {
      // MUTED / BANNED 不能靠「注销」绕过封禁，也不能把它们的 status 覆盖成 DELETED
      //（那会让一次封禁看起来像用户主动离开，事后举证方向完全错）
      throw new BizException(ErrorCode.USER_DISABLED, "当前账号状态不支持提交注销，请联系管理员");
    }
    CoolingState.deactivate(user, now, coolingDays);
    userMapper.updateById(user);
    jwtService.revoke(userId);
    log.info("已提交注销，进入冷静期 id={} purge_at={}", userId, user.getPurgeAt());
    return view(user, now, coolingDays,
        "注销申请已提交，" + coolingDays + " 天内重新登录即可撤回；到期后系统将永久删除你的数据");
  }

  /**
   * 主动撤回注销（冷静期内）。与「冷静期内登录自动撤回」共用同一条 SQL 与同一个理由：
   * null 只能用显式 @Update 写回去，{@code updateById} 会把这两列静默跳过。
   */
  public PrivacyViews.DeactivateView restore(long userId) {
    User user = requireUser(userId);
    LocalDateTime now = LocalDateTime.now();
    if (!CoolingState.isCooling(user, now)) {
      throw new BizException(ErrorCode.PARAM_INVALID, "当前账号不处于注销冷静期，无需撤回");
    }
    int n = userMapper.restoreActive(userId, CoolingState.DELETED, CoolingState.ACTIVE);
    if (n == 0) {
      throw new BizException(ErrorCode.USER_DISABLED, "账号状态已变更，请重新登录后再试");
    }
    CoolingState.restore(user);
    log.info("已撤回注销 id={}", userId);
    return view(user, now, Math.max(1, properties.getPrivacy().getCoolingDays()), "已撤回注销，账号恢复正常");
  }

  /**
   * 撤回某项授权（{@code DELETE /api/privacy/consent/{type}}）。
   *
   * <p>委托给 {@link UserService#grantOrWithdraw}，不在这里另写一条插入 {@code user_consent} 的路径：
   * 授权流水的写入口必须全站唯一（追加一行 WITHDRAW、绝不 UPDATE 历史行），
   * 两处写就会有两处「版本号怎么兜底、ip 截多长」的细节，而这类细节的漂移是不可见的。
   * TERMS / PRIVACY 两项由那道闸门拦住，错误文案会指向「请使用注销账号」。</p>
   */
  public UserConsent withdrawConsent(long userId, String consentType, String ip, String userAgent) {
    if (consentType == null || consentType.isBlank()) {
      throw new BizException(ErrorCode.PARAM_INVALID, "请指明要撤回的授权事项");
    }
    ConsentRequest req = new ConsentRequest(consentType.trim().toUpperCase(java.util.Locale.ROOT),
        "WITHDRAW", null, "privacy-center");
    log.info("撤回授权 id={} type={}", userId, req.consentType());
    return userService.grantOrWithdraw(userId, req, ip, userAgent);
  }

  private PrivacyViews.DeactivateView view(User user, LocalDateTime now, int coolingDays, String message) {
    boolean cooling = CoolingState.isCooling(user, now);
    return new PrivacyViews.DeactivateView(user.getStatus(), user.getDeactivateAt(), user.getPurgeAt(),
        coolingDays, CoolingState.remainDays(user, now), cooling, message);
  }

  private User requireUser(long userId) {
    User user = userMapper.selectById(userId);
    if (user == null) {
      throw new BizException(ErrorCode.USER_NOT_FOUND);
    }
    return user;
  }
}

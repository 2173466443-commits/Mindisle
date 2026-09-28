package com.mindisle.user;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mindisle.auth.AuthService;
import com.mindisle.auth.dto.AuthResponse.UserBrief;
import com.mindisle.auth.dto.ConsentRequest;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.entity.User;
import com.mindisle.entity.UserConsent;
import com.mindisle.entity.UserProfile;
import com.mindisle.mapper.UserConsentMapper;
import com.mindisle.mapper.UserProfileMapper;

/**
 * 「我的」域服务（手册 §5.7 任务 T2.10 · 需求 FR1.3、FR1.10、§12 规范 3）。
 *
 * <p>阶段 2 只落地两件现在就能真跑通的事：读自己的账号摘要、管自己的隐私授权。
 * 资料编辑与头像上传牵涉文件落盘和内容安全链，按手册排期留在阶段 3，
 * Controller 的对应接口返回 90001，而不是假装可用。
 *
 * <p>本类不直接注入 UserMapper：账号存在性一律走 AuthService.requireUser，
 * 「令牌里的 id 是否还有效」这件事全站只允许有一个判断口径。
 */
@Service
public class UserService {

  /** 允许的授权事项，与 user_consent.consent_type 的 ENUM 逐字一致。 */
  static final Set<String> CONSENT_TYPES = Set.of(
      "TERMS", "PRIVACY", "SENSITIVE_INFO", "EMOTION_SHARE", "CRISIS_CONTACT");

  /** 前端未回传版本号时的兜底，需与 sql/09_seed.sql 的 prompt.version 保持一致。 */
  static final String DEFAULT_CONTENT_VERSION = "v1";

  private final UserProfileMapper userProfileMapper;
  private final UserConsentMapper userConsentMapper;
  private final AuthService authService;

  public UserService(UserProfileMapper userProfileMapper,
                     UserConsentMapper userConsentMapper,
                     AuthService authService) {
    this.userProfileMapper = userProfileMapper;
    this.userConsentMapper = userConsentMapper;
    this.authService = authService;
  }

  /** 当前登录者的最小画像：前端刷新页面后靠它恢复 Pinia userStore。 */
  public UserBrief me(Long userId) {
    User user = authService.requireUser(userId);
    return new UserBrief(user.getId(), user.getUsername(), user.getNickname(),
        user.getAvatar(), user.getRole(), user.getAiStyle(), user.getStatus());
  }

  /**
   * 扩展资料。
   *
   * <p>读到老数据没有 user_profile 行时顺手补一条空壳再返回，避免前端满屏判 null。
   */
  @Transactional
  public ProfileView profile(Long userId) {
    authService.requireUser(userId);
    UserProfile profile = userProfileMapper.selectById(userId);
    if (profile == null) {
      profile = createBlankProfile(userId);
    }
    return ProfileView.of(profile);
  }

  /** 授权流水（含已撤回的历史），供「我的隐私」页展示与论文举证导出。 */
  public List<UserConsent> listConsents(Long userId) {
    authService.requireUser(userId);
    return userConsentMapper.listByUser(userId);
  }

  /**
   * 授予或撤回某类授权。
   *
   * <p>两张表必须同事务：留痕写成功而冗余开关没改，会出现「用户已撤回但情绪内容仍在分享」
   * 这种最严重的合规事故（需求 §8.2 风险 R7）。撤回同样是新增一行 action=WITHDRAW，
   * 绝不 UPDATE 历史行 —— 举证要的正是完整时间线。
   */
  @Transactional
  public UserConsent grantOrWithdraw(Long userId, ConsentRequest req, String ip, String userAgent) {
    if (!CONSENT_TYPES.contains(req.consentType())) {
      throw new BizException(ErrorCode.PARAM_INVALID, "授权事项不在允许范围内");
    }
    authService.requireUser(userId);
    // 任务 T4.21 · 手册 §7.5「TERMS 与 PRIVACY 不可单独撤回」的实现点。
    //
    // <p>为什么不是「随便一个前端约定」：TERMS 与 PRIVACY 是「能不能使用本产品」的前提，
    // 允许一个开关把它们关掉，会得到一个「已撤回隐私政策但仍继续被收集数据」的账号 ——
    // 那是最严重的合规事故形态（需求 §8.2 风险 R7），而且比「当初就不同意」更难举证。
    // 合法的出口只有一个：注销账号（{@code PrivacyAccountService#deactivate}），
    // 走冷静期 → 物理清除那条完整链路，并把整条授权流水留在导出包里交给本人。
    //
    // <p>现查全站调用：前端只有 ProfileView.vue 用过 WITHDRAW，且只用于 EMOTION_SHARE
    // 与 SENSITIVE_INFO 两项，所以这道闸不会打断任何现有界面。</p>
    if ("WITHDRAW".equals(req.action())
        && ("TERMS".equals(req.consentType()) || "PRIVACY".equals(req.consentType()))) {
      throw new BizException(ErrorCode.PARAM_INVALID,
          "服务条款与隐私政策不支持单独撤回；如需停止我们对你的数据处理，请使用注销账号");
    }

    boolean grant = "GRANT".equals(req.action());

    UserConsent row = new UserConsent();
    row.setUserId(userId);
    row.setConsentType(req.consentType());
    row.setAction(req.action());
    row.setContentVersion(cut(defaultIfBlank(req.contentVersion(), DEFAULT_CONTENT_VERSION), 16));
    row.setSourcePage(cut(defaultIfBlank(req.sourcePage(), "account"), 64));
    row.setIp(cut(ip, 45));
    row.setUserAgent(cut(userAgent, 255));
    userConsentMapper.insert(row);

    if ("EMOTION_SHARE".equals(req.consentType())) {
      syncEmotionShareFlag(userId, grant);
    }
    return row;
  }

  /** 同步 user_profile.emotion_share_consent 冗余位（推荐链路每次都要读它，不能现查流水表）。 */
  private void syncEmotionShareFlag(Long userId, boolean grant) {
    UserProfile profile = userProfileMapper.selectById(userId);
    if (profile == null) {
      profile = createBlankProfile(userId);
    }
    profile.setEmotionShareConsent(grant ? 1 : 0);
    userProfileMapper.updateById(profile);
  }

  /** 只写有默认值的列，其余交给 DDL 的 DEFAULT，避免把「空」写成与枚举不匹配的值。 */
  private UserProfile createBlankProfile(Long userId) {
    UserProfile profile = new UserProfile();
    profile.setUserId(userId);
    profile.setBio("");
    profile.setOnboardingDone(0);
    profile.setEmotionShareConsent(0);
    profile.setRiskFlag(0);
    profile.setFollowingCnt(0);
    profile.setFollowerCnt(0);
    profile.setPostCnt(0);
    profile.setDeleted(0);
    userProfileMapper.insert(profile);
    return profile;
  }

  private static String defaultIfBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String cut(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  /**
   * 扩展资料出参。
   *
   * <p>刻意不含 risk_flag：它是危机干预用的内部标记（手册 §5.1 v1.1.2 补列），
   * 回流到用户界面等于给用户贴标签，违反需求 §12 规范 5「不做诊断与标签化表述」。
   * interestTags 按原始 JSON 字符串透出，前端解析失败时当作空数组处理。
   */
  public record ProfileView(
      Long userId,
      String grade,
      String school,
      String gender,
      String interestTags,
      String bio,
      Integer onboardingDone,
      Integer emotionShareConsent,
      Integer followingCnt,
      Integer followerCnt,
      Integer postCnt) {

    static ProfileView of(UserProfile p) {
      return new ProfileView(p.getUserId(), p.getGrade(), p.getSchool(), p.getGender(),
          p.getInterestTags(), p.getBio(), p.getOnboardingDone(), p.getEmotionShareConsent(),
          p.getFollowingCnt(), p.getFollowerCnt(), p.getPostCnt());
    }
  }
}

package com.mindisle.auth;

import com.mindisle.auth.dto.AuthResponse;
import com.mindisle.auth.dto.LoginRequest;
import com.mindisle.auth.dto.RegisterRequest;
import com.mindisle.cache.CacheService;
import com.mindisle.captcha.CaptchaService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.User;
import com.mindisle.entity.UserConsent;
import com.mindisle.entity.UserProfile;
import com.mindisle.mapper.UserConsentMapper;
import com.mindisle.mapper.UserMapper;
import com.mindisle.mapper.UserProfileMapper;
import com.mindisle.security.JwtService;
import com.mindisle.security.JwtService.TokenPair;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册 / 登录 / 刷新 / 登出（需求 FR1.1-FR1.3 · 手册 §5.4 T2.9-T2.12）。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>口令只存 BCrypt 摘要，任何日志与出参不得出现明文（需求 §12 规范 2）；</li>
 *   <li>协议与隐私同意必须写 user_consent 流水，撤回也是新增一行，不改历史（PIPL 第 29 条）；</li>
 *   <li>登录失败按 IP+用户名计数，达到 loginFailMax 直接 10009，防撞库（FR1.3）。</li>
 * </ol>
 */
@Service
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  /** 用户名规则与前端镜像一致（需求 FR1.1）。 */
  private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{4,32}$");

  /** 协议默认版本；前端未回传 consentVersion 时使用，保证留痕字段永不为空。 */
  static final String DEFAULT_CONSENT_VERSION = "v1.0";

  /** 登录失败计数键前缀。 */
  private static final String FAIL_KEY_PREFIX = "login:fail:";

  private final UserMapper userMapper;
  private final UserProfileMapper userProfileMapper;
  private final UserConsentMapper userConsentMapper;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final CaptchaService captchaService;
  private final CacheService cacheService;
  private final MindisleProperties properties;

  public AuthService(UserMapper userMapper, UserProfileMapper userProfileMapper,
      UserConsentMapper userConsentMapper, PasswordEncoder passwordEncoder, JwtService jwtService,
      CaptchaService captchaService, CacheService cacheService, MindisleProperties properties) {
    this.userMapper = userMapper;
    this.userProfileMapper = userProfileMapper;
    this.userConsentMapper = userConsentMapper;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.captchaService = captchaService;
    this.cacheService = cacheService;
    this.properties = properties;
  }

  /**
   * 注册：验证码 → 用户名/密码规则 → 唯一性 → BCrypt → 三张表落库 → 直接发令牌。
   *
   * <p>注册成功即登录，省掉「注册完再让人输一遍密码」的挫败感。
   * user / user_profile / user_consent 同事务写入：不允许出现「有账号无留痕」的取证断层。
   */
  @Transactional(rollbackFor = Exception.class)
  public AuthResponse register(RegisterRequest req, String ip, String userAgent) {
    captchaService.verifyOrThrow(req.captchaId(), req.captchaCode());

    String username = req.username() == null ? "" : req.username().trim();
    if (!USERNAME.matcher(username).matches()) {
      throw new BizException(ErrorCode.PARAM_INVALID, "用户名只能为 4-32 位字母、数字或下划线");
    }
    if (!passwordStrongEnough(req.password())) {
      throw new BizException(ErrorCode.PARAM_INVALID, "密码至少 8 位，且需同时包含字母和数字");
    }
    // 先查一次给友好提示；真正兜底的是 uk_username（并发注册靠 DuplicateKeyException 转 20002）
    if (userMapper.findByUsername(username) != null) {
      throw new BizException(ErrorCode.USERNAME_TAKEN);
    }
    // service 再判一次同意，防止有人直接注入本 bean 绕过 Controller 校验
    if (!req.termsChecked() || !req.privacyChecked()) {
      throw new BizException(ErrorCode.PRIVACY_CONSENT_REQUIRED);
    }

    LocalDateTime now = LocalDateTime.now();
    User user = new User();
    user.setUsername(username);
    user.setPassword(passwordEncoder.encode(req.password()));
    user.setNickname(cut(defaultIfBlank(req.nickname(), "屿友" + username), 32));
    user.setStatus("ACTIVE");
    user.setRole("USER");
    user.setAiStyle("warm");
    user.setRegSource(cut(defaultIfBlank(req.regSource(), "web"), 32));
    user.setAgreePrivacyAt(now);
    try {
      userMapper.insert(user);
    } catch (DuplicateKeyException e) {
      log.info("注册撞唯一键 username={} ip={}", username, ip);
      throw new BizException(ErrorCode.USERNAME_TAKEN);
    }
    if (user.getId() == null) {
      throw new BizException(ErrorCode.DB_UNAVAILABLE, "注册写入后未取回用户主键");
    }

    UserProfile profile = new UserProfile();
    profile.setUserId(user.getId());
    profile.setGrade(cut(defaultIfBlank(req.grade(), "OTHER"), 16).toUpperCase(Locale.ROOT));
    profile.setSchool(cut(req.school(), 64));
    userProfileMapper.insert(profile);

    String version = cut(defaultIfBlank(req.consentVersion(), DEFAULT_CONSENT_VERSION), 16);
    grantConsent(user.getId(), "TERMS", version, ip, userAgent, now);
    grantConsent(user.getId(), "PRIVACY", version, ip, userAgent, now);
    if (req.sensitiveChecked()) {
      grantConsent(user.getId(), "SENSITIVE_INFO", version, ip, userAgent, now);
    }

    log.info("新用户注册 id={} username={} source={}", user.getId(), username, user.getRegSource());
    return toResponse(user, jwtService.issue(user.getId(), user.getUsername(), user.getRole()));
  }

  /**
   * 登录：先查锁定 → 验证码 → 取账号 → BCrypt 比对 → 账号态 → 记最近登录 → 发令牌 → 清失败计数。
   *
   * <p>「先查锁定再看验证码」的顺序是故意的：账号被锁时不消耗验证码，
   * 既避免攻击者把图形服务打满，也让 10009 的提示稳定可测。
   */
  @Transactional(rollbackFor = Exception.class)
  public AuthResponse login(LoginRequest req, String ip) {
    String username = req.username() == null ? "" : req.username().trim();
    String failKey = FAIL_KEY_PREFIX + ip + ":" + username;
    assertNotLocked(failKey);

    captchaService.verifyOrThrow(req.captchaId(), req.captchaCode());

    User user = userMapper.findByUsername(username);
    // 「用户不存在」与「密码错误」返回同一个码，不给撞库者枚举用户名的信号（NFR7）
    if (user == null || !passwordEncoder.matches(req.password(), user.getPassword())) {
      recordFailure(failKey);
      throw new BizException(ErrorCode.LOGIN_FAILED);
    }
    if (!"ACTIVE".equals(user.getStatus())) {
      // 停用/封禁是「口令对但不给进」，不计入失败锁定，让用户直接看到 20003 的原因
      throw new BizException(ErrorCode.USER_DISABLED);
    }
    cacheService.del(failKey);
    userMapper.updateById(touchUser(user.getId(), ip));
    return toResponse(user, jwtService.issue(user.getId(), user.getUsername(), user.getRole()));
  }

  /** 管理端登录：在用户登录之上加一道角色闸门（需求 FR8.1，权限唯一来源是 user.role）。 */
  public AuthResponse loginAsAdmin(LoginRequest req, String ip) {
    AuthResponse response = login(req, ip);
    String role = response.user().role();
    if (!"ADMIN".equals(role) && !"SUPER".equals(role)) {
      log.info("非管理员账号尝试登录管理端 id={} role={}", response.user().id(), role);
      throw new BizException(ErrorCode.FORBIDDEN);
    }
    return response;
  }

  /** 刷新：校验 refreshToken 后换发新令牌对，白名单随之续期（旧 access 立即失效）。 */
  public AuthResponse refresh(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new BizException(ErrorCode.TOKEN_INVALID);
    }
    TokenPair pair = jwtService.renew(refreshToken, null, null);
    User user = userMapper.selectById(pair.userId());
    if (user == null) {
      throw new BizException(ErrorCode.USER_NOT_FOUND);
    }
    if (!"ACTIVE".equals(user.getStatus())) {
      throw new BizException(ErrorCode.USER_DISABLED);
    }
    return toResponse(user, pair);
  }

  /** 登出：作废服务端白名单里的 access 令牌（当前单活动会话，见 JwtService 注释）。 */
  public void logout(Long userId) {
    jwtService.revoke(userId);
  }

  /** 按主键取账号，查不到即 20001。Controller 与 UserService 共用，避免各处重复判空。 */
  public User requireUser(Long userId) {
    User user = userId == null ? null : userMapper.selectById(userId);
    if (user == null) {
      throw new BizException(ErrorCode.USER_NOT_FOUND);
    }
    return user;
  }

  // ---------------------------------------------------------------- 内部工具

  private void assertNotLocked(String failKey) {
    Long fails = readFailCount(failKey);
    if (fails != null && fails >= properties.getRateLimit().getLoginFailMax()) {
      throw new BizException(ErrorCode.LOGIN_LOCKED);
    }
  }

  private Long readFailCount(String failKey) {
    try {
      return cacheService.get(failKey, Long.class);
    } catch (RuntimeException e) {
      // 计数读失败不拖挂登录：宁可少挡一次，也不让缓存故障变成「全站无法登录」
      log.warn("登录失败计数读取异常 key={}: {}", failKey, e.toString());
      return null;
    }
  }

  private void recordFailure(String failKey) {
    int lockMinutes = properties.getRateLimit().getLoginLockMinutes();
    try {
      cacheService.incr(failKey, Duration.ofMinutes(Math.max(1, lockMinutes)));
    } catch (RuntimeException e) {
      log.warn("登录失败计数写入异常 key={}: {}", failKey, e.toString());
    }
  }

  private void grantConsent(Long userId, String type, String version, String ip, String userAgent,
      LocalDateTime at) {
    UserConsent consent = new UserConsent();
    consent.setUserId(userId);
    consent.setConsentType(type);
    consent.setAction("GRANT");
    consent.setContentVersion(version);
    consent.setSourcePage("register");
    consent.setIp(cut(ip, 45));
    consent.setUserAgent(cut(userAgent, 255));
    consent.setCreatedAt(at);
    userConsentMapper.insert(consent);
  }

  private User touchUser(Long userId, String ip) {
    User update = new User();
    update.setId(userId);
    update.setLastLoginAt(LocalDateTime.now());
    update.setLastLoginIp(cut(ip, 45));
    return update;
  }

  /** 口令强度：长度 ≥ 8 且同时含字母与数字（需求 §12 规范 2 的工程化补充，偏差见 dev-log）。 */
  static boolean passwordStrongEnough(String raw) {
    if (raw == null || raw.length() < 8) {
      return false;
    }
    boolean letter = false;
    boolean digit = false;
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (Character.isLetter(c)) {
        letter = true;
      } else if (Character.isDigit(c)) {
        digit = true;
      }
    }
    return letter && digit;
  }

  private AuthResponse toResponse(User user, TokenPair pair) {
    AuthResponse.UserBrief brief = new AuthResponse.UserBrief(user.getId(), user.getUsername(),
        user.getNickname(), user.getAvatar(), user.getRole(), user.getAiStyle(), user.getStatus());
    return new AuthResponse(pair.accessToken(), pair.refreshToken(), pair.expiresIn(), brief);
  }

  private static String defaultIfBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static String cut(String value, int max) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }
}

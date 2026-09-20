package com.mindisle.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.mindisle.cache.CacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 令牌签发与校验（手册 §5.6 · 任务 T2.5 · 需求 FR1.2）。
 *
 * <p>双令牌：access 2h（短期，前端存 pinia，被 XSS 偷走的窗口小），
 * refresh 7d（只在 /api/auth/refresh 使用，不参与每次请求）。</p>
 *
 * <p>「强制下线」用服务端白名单实现：签发时把 jti 写进 user:token:{uid}，
 * 校验时比对；退出/改密/被管理员停用就删掉它，旧 token 立刻失效。
 * 注意这是<b>单活动会话</b>语义——同一账号后登录的设备会把前一个踢下线，
 * 这是刻意的安全取舍（心理社区存在共用账号的现实风险）；
 * 若要支持多设备，把白名单从 String 改成 Set 并按设备号做成员即可
 * （已列入论文第 7 章可扩展项）。</p>
 *
 * <p>jjwt 0.13：构建用 subject/claim/id/issuedAt/expiration/signWith，
 * 解析用 Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload()。</p>
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** HS256 密钥最低长度要求（RFC 7518：256 bit = 32 字节），低于它 jjwt 自己会拒绝，这里提前拦住并给出可执行的修复指引。 */
    private static final int MIN_SECRET_BYTES = 32;

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_USERNAME = "uname";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    /** 白名单 key 前缀，与 §5.6 一致。 */
    private static final String WHITELIST_PREFIX = "user:token:";

    private final SecretKey key;
    private final CacheService cacheService;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtService(MindisleProperties properties, CacheService cacheService) {
        String secret = properties.getJwt().getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("mindisle.jwt.secret 缺失或长度不足 32 字节："
                    + "请在项目根 .env 里配置 JWT_SECRET（生成方式见 .env.example 与手册 §5.2）");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.cacheService = cacheService;
        this.accessTtl = Duration.ofMinutes(Math.max(1L, properties.getJwt().getAccessMinutes()));
        this.refreshTtl = Duration.ofDays(Math.max(1L, properties.getJwt().getRefreshDays()));
    }

    /** 签发一对令牌，并把 access 的 jti 写入白名单。 */
    public TokenPair issue(long userId, String username, String role) {
        String accessId = newTokenId();
        String refreshId = newTokenId();
        String accessToken = build(userId, username, role, accessId, TYPE_ACCESS, accessTtl);
        String refreshToken = build(userId, username, role, refreshId, TYPE_REFRESH, refreshTtl);
        cacheService.set(WHITELIST_PREFIX + userId, accessId, accessTtl);
        return new TokenPair(accessToken, refreshToken, accessTtl.getSeconds(), userId, role);
    }

    /** 用 refresh 令牌换新的 access 令牌（白名单随之刷新，旧 access 立即失效）。 */
    public TokenPair renew(String refreshToken, String username, String role) {
        Claims claims = parse(refreshToken);
        requireType(claims, TYPE_REFRESH);
        long userId = subjectUserId(claims);
        return issue(userId,
                username != null ? username : claims.get(CLAIM_USERNAME, String.class),
                role != null ? role : claims.get(CLAIM_ROLE, String.class));
    }

    /**
     * 校验 access 令牌：签名 + 有效期 + 类型 + 白名单四道关。
     * 任一不过都归一成 4xxxx 错误码，不回传「为什么不过」的细节
     * （NFR7：不给攻击者探测账号状态的机会）。
     */
    public AuthUser validate(String accessToken) {
        Claims claims = parse(accessToken);
        requireType(claims, TYPE_ACCESS);
        long userId = subjectUserId(claims);
        String tokenId = claims.getId();
        String active = cacheService.get(WHITELIST_PREFIX + userId, String.class);
        if (active == null || !active.equals(tokenId)) {
            log.warn("令牌不在白名单，已失效 userId={}", userId);
            throw new BizException(ErrorCode.TOKEN_INVALID, "登录状态已失效，请重新登录");
        }
        return new AuthUser(userId, claims.get(CLAIM_USERNAME, String.class),
                claims.get(CLAIM_ROLE, String.class), tokenId);
    }

    /** 强制下线：删白名单即可，无需维护令牌黑名单（下次校验自然比对失败）。 */
    public void revoke(long userId) {
        cacheService.del(WHITELIST_PREFIX + userId);
    }

    /** 仅验签与解析，不查白名单；供 refresh 与测试复用。 */
    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            throw new BizException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
    }

    private String build(long userId, String username, String role, String tokenId, String type, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_TYPE, type)
                .id(tokenId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    private static String newTokenId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void requireType(Claims claims, String expected) {
        String type = claims.get(CLAIM_TYPE, String.class);
        if (!expected.equals(type)) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "令牌类型不正确");
        }
    }

    private static long subjectUserId(Claims claims) {
        try {
            return Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
    }

    /**
     * 登录/刷新接口的返回体。
     *
     * @param expiresIn access 令牌剩余秒数；前端据此在到期前主动刷新，避免「正在打字时突然 401」
     */
    public record TokenPair(String accessToken, String refreshToken, long expiresIn, long userId, String role) {
    }
}

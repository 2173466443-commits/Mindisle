package com.mindisle.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.mindisle.cache.CaffeineCacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;

import io.jsonwebtoken.Claims;

/**
 * 令牌链路冒烟测试（手册 §5.10 Gate2 要求 2/3）。
 *
 * <p>不启 Spring 容器，直接 new JwtService + CaffeineCacheService：
 * 令牌规则是纯计算逻辑，把它和数据库、Redis 绑在一起只会让回归变慢、失败原因变糊。
 * 需求 FR1.2 的四道关（签名、有效期、类型、白名单）在这里逐个单独打靶，
 * 因为真实事故往往只坏在其中一道 —— 例如「退出后旧 token 仍可用」，
 * 在心理社区里等于别人能接着看你的对话记录。</p>
 */
class JwtServiceTest {

    /** 测试专用密钥，只满足 HS256 的 32 字节下限，不与任何环境共用。 */
    private static final String TEST_SECRET = "mindisle-unit-test-secret-0123456789abcdef";

    private MindisleProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MindisleProperties();
        properties.getJwt().setSecret(TEST_SECRET);
    }

    private JwtService newService() {
        return new JwtService(properties, new CaffeineCacheService());
    }

    private static BizException expectBiz(Executable body) {
        return assertThrows(BizException.class, body);
    }

    @Test
    @DisplayName("签发的一对令牌可校验，身份字段与签发时一致")
    void issueThenValidate() {
        JwtService service = newService();
        JwtService.TokenPair pair = service.issue(1L, "tester", "USER");

        assertThat(pair.accessToken()).isNotBlank();
        assertThat(pair.refreshToken()).isNotBlank();
        assertThat(pair.accessToken()).isNotEqualTo(pair.refreshToken());
        assertThat(pair.expiresIn()).isEqualTo(120L * 60L);
        assertThat(pair.userId()).isEqualTo(1L);
        assertThat(pair.role()).isEqualTo("USER");

        AuthUser user = service.validate(pair.accessToken());
        assertThat(user.id()).isEqualTo(1L);
        assertThat(user.username()).isEqualTo("tester");
        assertThat(user.role()).isEqualTo("USER");
        assertThat(user.tokenId()).isNotBlank();
        assertThat(user.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("RBAC 角色标记只认 ADMIN 与 SUPER")
    void adminRoleFlag() {
        JwtService service = newService();

        assertThat(service.validate(service.issue(9L, "boss", "ADMIN").accessToken()).isAdmin()).isTrue();
        assertThat(service.validate(service.issue(10L, "root", "SUPER").accessToken()).isAdmin()).isTrue();
        assertThat(service.validate(service.issue(11L, "stu", "USER").accessToken()).isAdmin()).isFalse();
    }

    @Test
    @DisplayName("refresh 令牌不能当 access 用：挡掉拿长期凭证打业务接口")
    void refreshTokenRejectedAsAccess() {
        JwtService service = newService();
        JwtService.TokenPair pair = service.issue(2L, "tester", "USER");

        BizException ex = expectBiz(() -> service.validate(pair.refreshToken()));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("revoke 之后旧令牌立即失效：强制下线的落地路径")
    void revokedTokenRejected() {
        JwtService service = newService();
        JwtService.TokenPair pair = service.issue(3L, "tester", "USER");

        assertThat(service.validate(pair.accessToken())).isNotNull();
        service.revoke(3L);

        BizException ex = expectBiz(() -> service.validate(pair.accessToken()));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("renew 换新令牌后上一枚 access 失效：单活动会话语义")
    void renewInvalidatesPreviousAccess() {
        JwtService service = newService();
        JwtService.TokenPair first = service.issue(4L, "tester", "USER");
        JwtService.TokenPair second = service.renew(first.refreshToken(), "tester", "USER");

        assertThat(second.accessToken()).isNotEqualTo(first.accessToken());
        assertThat(service.validate(second.accessToken()).id()).isEqualTo(4L);
        assertThat(expectBiz(() -> service.validate(first.accessToken())).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("篡改内容与换密钥签发的令牌都归一为 TOKEN_INVALID，不透露失败原因")
    void tamperedAndForeignTokensRejected() {
        JwtService service = newService();
        JwtService.TokenPair pair = service.issue(5L, "tester", "USER");

        assertThat(expectBiz(() -> service.validate(pair.accessToken() + "x")).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
        assertThat(expectBiz(() -> service.validate("not.a.jwt")).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);

        MindisleProperties otherProperties = new MindisleProperties();
        otherProperties.getJwt().setSecret("a-completely-different-secret-value-9876543210");
        JwtService foreign = new JwtService(otherProperties, new CaffeineCacheService());

        assertThat(expectBiz(() -> foreign.validate(pair.accessToken())).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("密钥缺失或过短时启动即失败，而不是运行期随机 401")
    void shortSecretFailsFast() {
        properties.getJwt().setSecret("too-short");
        assertThat(assertThrows(IllegalStateException.class, this::newService).getMessage())
                .contains("32")
                .contains("JWT_SECRET");

        properties.getJwt().setSecret(null);
        assertThrows(IllegalStateException.class, this::newService);
    }

    @Test
    @DisplayName("Claims 暴露 sub/jti/时间戳，供刷新、审计与排障复用")
    void claimsExposeMetadata() {
        JwtService service = newService();
        JwtService.TokenPair pair = service.issue(6L, "tester", "USER");
        Claims claims = service.parse(pair.accessToken());

        assertThat(claims.getSubject()).isEqualTo("6");
        assertThat(claims.getId()).isEqualTo(service.validate(pair.accessToken()).tokenId());
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }
}

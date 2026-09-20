package com.mindisle.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mindisle.cache.CaffeineCacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;

/**
 * 图形码冒烟测试（手册 §5.10 Gate2 要求 3/3 · 任务 T2.16）。
 *
 * <p>重点是「一次性」：先取出再比对，答错也作废。若哪天有人把它改成
 * 校验通过才删，同一个 captchaId 就成了可重放的凭证，登录接口的失败锁定
 * 与限流都会被绕过。这条测试就是为那个改动准备的警报器。</p>
 */
class CaptchaServiceTest {

    /**
     * 与 CaptchaService.KEY_PREFIX 保持一致。
     *
     * <p>测试需要绕过 service 直接看缓存里存了什么，只能按 key 取。
     * 若源码改了前缀，下面用到它的两条测试会因取到 null 而失败 ——
     * 这是有意的耦合：key 命名属于缓存契约的一部分，改动必须被显式察觉。</p>
     */
    private static final String KEY_PREFIX = "cap:";

    private CaffeineCacheService cache;
    private MindisleProperties properties;
    private CaptchaService service;

    @BeforeEach
    void setUp() {
        cache = new CaffeineCacheService();
        // 用真实配置对象而不是 mock：这样「默认值必须是 true」这条安全属性本身也被测试覆盖，
        // 哪天有人把字段默认值改成 false，下面 disabledGate 之外的那几例会直接红。
        properties = new MindisleProperties();
        service = new CaptchaService(cache, properties);
    }

    @Test
    @DisplayName("配置闸默认是开启的（fail-closed）：漏配键也不会把注册/登录敞开")
    void captchaIsEnabledByDefault() {
        assertThat(new MindisleProperties().getCaptcha().isEnabled()).isTrue();
        properties.getCaptcha().setEnabled(false);
        assertDoesNotThrow(() -> service.verifyOrThrow(null, null));
        properties.getCaptcha().setEnabled(true);
        assertThat(assertThrows(BizException.class,
                () -> service.verifyOrThrow(null, null)).getErrorCode())
                .isEqualTo(ErrorCode.CAPTCHA_INVALID);
    }

    @Test
    @DisplayName("显式关闭后完全放行：不查缓存、不消耗，本地冒烟才能拿到 200")
    void disabledGateSkipsVerificationEntirely() {
        properties.getCaptcha().setEnabled(false);
        assertDoesNotThrow(() -> service.verifyOrThrow("whatever", "wrong-code"));
        // 关闭态下连「已存在的正确答案」也不需要填：这里证明它确实没走缓存分支
        CaptchaService.Captcha captcha = service.generate();
        assertDoesNotThrow(() -> service.verifyOrThrow(captcha.captchaId(), "0000"));
        assertDoesNotThrow(() -> service.verifyOrThrow(captcha.captchaId(), "0000"));
        // 答案仍在缓存里，说明只是没校验，没有把数据弄丢；重新开启后照常生效
        properties.getCaptcha().setEnabled(true);
        String answer = cache.get(KEY_PREFIX + captcha.captchaId(), String.class);
        assertThat(answer).isNotNull();
        assertDoesNotThrow(() -> service.verifyOrThrow(captcha.captchaId(), answer));
    }

    @Test
    @DisplayName("出参：id 唯一、图片是纯 Base64（不含 data: 前缀）、有效期为正")
    void generateReturnsUsablePayload() {
        CaptchaService.Captcha first = service.generate();
        CaptchaService.Captcha second = service.generate();

        assertThat(first.captchaId()).isNotBlank().hasSize(32).doesNotContain("-");
        assertThat(first.captchaId()).isNotEqualTo(second.captchaId());
        assertThat(first.imageBase64()).isNotBlank().hasSizeGreaterThan(100);
        assertThat(first.imageBase64()).doesNotStartWith("data:");
        assertThat(first.imageBase64()).matches("[A-Za-z0-9+/=]+");
        assertThat(first.ttlSeconds()).isPositive();
    }

    @Test
    @DisplayName("答案按大写存进缓存：比对大小写不敏感，用户不必纠结 Shift")
    void answerIsStoredUppercase() {
        CaptchaService.Captcha captcha = service.generate();
        String answer = cache.get(KEY_PREFIX + captcha.captchaId(), String.class);

        assertThat(answer).isNotNull().hasSize(4);
        assertThat(answer).isEqualTo(answer.toUpperCase());
    }

    @Test
    @DisplayName("正确答案通过校验，且同一 captchaId 用第二次即失效（防重放）")
    void correctCodeIsSingleUse() {
        CaptchaService.Captcha captcha = service.generate();
        String answer = cache.get(KEY_PREFIX + captcha.captchaId(), String.class);
        assertThat(answer).isNotNull();

        assertDoesNotThrow(() -> service.verifyOrThrow(captcha.captchaId(), answer.toLowerCase()));

        BizException replay = assertThrows(BizException.class,
                () -> service.verifyOrThrow(captcha.captchaId(), answer));
        assertThat(replay.getErrorCode()).isEqualTo(ErrorCode.CAPTCHA_EXPIRED);
    }

    @Test
    @DisplayName("答错同样消耗这张图：脚本每猜一次都要重新申请")
    void wrongCodeAlsoConsumes() {
        CaptchaService.Captcha captcha = service.generate();

        // 字符集刻意排除易混的 0/1/O/I，"000000" 不可能等于正确答案。
        BizException wrong = assertThrows(BizException.class,
                () -> service.verifyOrThrow(captcha.captchaId(), "000000"));
        assertThat(wrong.getErrorCode()).isEqualTo(ErrorCode.CAPTCHA_INVALID);

        BizException reused = assertThrows(BizException.class,
                () -> service.verifyOrThrow(captcha.captchaId(), "000000"));
        assertThat(reused.getErrorCode()).isEqualTo(ErrorCode.CAPTCHA_EXPIRED);
    }

    @Test
    @DisplayName("未申请过的 id 视为已失效，不给「该 id 是否存在」留探测口子")
    void unknownIdIsExpired() {
        BizException ex = assertThrows(BizException.class,
                () -> service.verifyOrThrow("ffffffffffffffffffffffffffffffff", "ABCD"));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CAPTCHA_EXPIRED);
    }

    @Test
    @DisplayName("id 或答案为空时直接判参数错误，不查缓存")
    void blankInputIsInvalid() {
        for (String[] pair : new String[][] { { null, "ABCD" }, { "", "ABCD" }, { "  ", "ABCD" } }) {
            assertThat(assertThrows(BizException.class,
                    () -> service.verifyOrThrow(pair[0], pair[1])).getErrorCode())
                    .isEqualTo(ErrorCode.CAPTCHA_INVALID);
        }
        CaptchaService.Captcha captcha = service.generate();
        for (String blank : new String[] { null, "", "   " }) {
            assertThat(assertThrows(BizException.class,
                    () -> service.verifyOrThrow(captcha.captchaId(), blank)).getErrorCode())
                    .isEqualTo(ErrorCode.CAPTCHA_INVALID);
        }
    }
}

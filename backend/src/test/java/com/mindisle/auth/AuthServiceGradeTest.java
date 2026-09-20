package com.mindisle.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.mindisle.auth.dto.RegisterRequest;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * 注册年级字段的取值闸（任务 T2.9 补漏 · 手册 §5.11「脏值不许落到 DB」）。
 *
 * <p>来历：2026-09-20 建库后第一次真实注册，冒烟脚本把中文标签「大二」当 grade 发过来，
 * MySQL 的 ENUM 列抛 1265 Data truncated，全局兜底把它变成 90002/503「数据暂时读取不到」，
 * 而真实原因只是入参。这类「参数问题伪装成基础设施故障」在现场最难查，
 * 所以两头都堵：DTO 的 @Pattern 负责给人话提示，service 的 normalizeGrade 负责
 * 挡住任何绕过 Controller 直接注入本 bean 的调用。</p>
 */
class AuthServiceGradeTest {

    @ParameterizedTest
    @ValueSource(strings = { "FRESH", "SOPH", "JUNIOR", "SENIOR", "OTHER", "soph", " Junior ", "" })
    @DisplayName("白名单内的值（含大小写与首尾空格；空串按 OTHER）都能归一通过")
    void acceptsWhitelistedGrades(String raw) {
        String normalized = AuthService.normalizeGrade(raw);
        assertThat(AuthService.GRADES).contains(normalized);
        if (raw.isBlank()) {
            assertEquals("OTHER", normalized);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "大二", "大一新生", "GRAD", "unknown", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" })
    @DisplayName("白名单外的值直接 10001，绝不把脏值交给 ENUM 列")
    void rejectsUnknownGrade(String raw) {
        BizException ex = assertThrows(BizException.class, () -> AuthService.normalizeGrade(raw));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(ex.getMessage()).contains("FRESH");
    }

    @Test
    @DisplayName("DTO 边界：grade 非法时 @Pattern 就拦下，提示里不含任何数据库术语")
    void dtoRejectsChineseGrade() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        Set<String> messages = validator.validate(register("大二")).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
        assertThat(messages).anyMatch(m -> m.contains("年级"));

        assertThat(validator.validate(register("SOPH"))).isEmpty();
        // 不填年级是合法路径：注册不该强制收集这个字段（需求 FR1.1 明确写「可选」）
        assertThat(validator.validate(register(null))).isEmpty();
    }

    private static RegisterRequest register(String grade) {
        return new RegisterRequest("smokeuser", "Smoke#2026x", "冒烟用户",
                "ffffffffffffffffffffffffffffffff", "ABCD", true, true, false, "v1.0", grade,
                "示例大学", "smoke-script");
    }
}

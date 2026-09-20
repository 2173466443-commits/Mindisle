package com.mindisle.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 统一响应体与错误码冒烟测试（手册 §5.10 Gate2 要求 1/3）。
 *
 * <p>这是全项目最小也最不能坏的一块：前后端契约完全建立在 code 之上，
 * 一旦某个错误码被误改或被复制成两个，前端镜像表就会静默错位，
 * 用户看到的提示会答非所问。所以除了 happy path，
 * 这里额外加了「错误码全局唯一」的结构断言，把这类改动挡在提交前。</p>
 */
class ResultTest {

    @AfterEach
    void clearTrace() {
        MDC.remove(Result.TRACE_ID_KEY);
    }

    @Test
    @DisplayName("成功响应固定 code=0，前端只判这一个值")
    void okUsesZeroCode() {
        Result<Void> result = Result.ok();

        assertThat(result.getCode()).isZero();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMsg()).isEqualTo(ErrorCode.SUCCESS.getMsg());
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("成功响应携带业务数据")
    void okCarriesData() {
        Result<String> result = Result.ok("hello");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("hello");
    }

    @Test
    @DisplayName("失败响应带出错误码与默认文案，data 恒为空")
    void failUsesErrorCode() {
        Result<Void> result = Result.fail(ErrorCode.CAPTCHA_EXPIRED);

        assertThat(result.getCode()).isEqualTo(10007);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMsg()).isEqualTo(ErrorCode.CAPTCHA_EXPIRED.getMsg()).isNotBlank();
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("fail(code, msg) 允许保留原码但覆写文案")
    void failCanOverrideMessageOnly() {
        Result<Void> result = Result.fail(ErrorCode.PARAM_INVALID.getCode(), "参数不合法：username 不能为空");

        assertThat(result.getCode()).isEqualTo(10001);
        assertThat(result.getMsg()).endsWith("username 不能为空");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("traceId 取自 MDC：有则回传，无则为空且不报错")
    void traceIdComesFromMdc() {
        assertThat(Result.ok().getTraceId()).isNull();

        MDC.put(Result.TRACE_ID_KEY, "trace-abc-123");
        assertThat(Result.fail(ErrorCode.INTERNAL_ERROR).getTraceId()).isEqualTo("trace-abc-123");
    }

    @Test
    @DisplayName("每个 ErrorCode 都能按 code 反查，且业务码不重复")
    void errorCodesAreUniqueAndResolvable() {
        List<Integer> seen = new ArrayList<>();
        List<Integer> duplicated = new ArrayList<>();

        for (ErrorCode item : ErrorCode.values()) {
            if (!seen.add(item.getCode())) {
                duplicated.add(item.getCode());
            }
            assertThat(ErrorCode.fromCode(item.getCode())).isSameAs(item);
        }

        assertThat(duplicated).as("错误码重复会让前端镜像表错位").isEmpty();
        assertThat(ErrorCode.fromCode(88888)).isNull();
    }

    @Test
    @DisplayName("未实现接口返回 90001 且提示写清是哪个阶段，避免前端静默兜底")
    void notImplementedMessageNamesTheStage() {
        BizException ex = BizException.notImplemented("阶段3 帖子列表与分页");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_IMPLEMENTED);
        assertThat(ex.getErrorCode().getHttpStatus()).isEqualTo(501);
        assertThat(ex.getMessage()).contains("阶段3").contains("帖子列表与分页");
    }
}

package com.mindisle;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.mindisle.common.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文装配冒烟测试（手册 §5.10 Gate2 的可选第 4 项）。
 *
 * <p>默认 <b>@Disabled</b>，原因如实记录：它需要本机 MySQL 已按 sql/00_create_db_and_user.sql
 * 建好 31 张表、并且 mindisle.llm.provider 对应的密钥可用，属于「集成环境」而不是「单元测试」。
 * Gate2 的通过判据因此是三个纯单测（ResultTest / JwtServiceTest / CaptchaServiceTest）全绿；
 * 这条测试留作环境就绪后的装配检查，一旦解除注释失败，说明有 Bean 的依赖关系被改坏。</p>
 *
 * <p>properties 全部用字面量覆盖，避免测试依赖 .env 是否存在。
 * mindisle.llm.provider=mock 让阶段 2 的 AI 客户端走占位实现，
 * mindisle.cache.mode=local 让 Redis 不在场也能起容器（需求 §11.2 的降级口）。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:mysql:///mindisle_test",
        "spring.datasource.username=mindisle",
        "spring.datasource.password=test",
        "mindisle.cache.mode=local",
        "mindisle.llm.provider=mock",
        "mindisle.jwt.secret=mindisle-integration-test-secret-0123456789abcdef",
        "spring.ai.deepseek.api-key=sk-integration-test-placeholder"
})
@Disabled("需要本机 MySQL 与可用配置；Gate2 以三个纯单测为准，环境就绪后解除注释")
class MindisleApplicationTests {

    @Autowired
    private org.springframework.context.ApplicationContext context;

    @Test
    @DisplayName("Spring 容器能起来，核心 Bean 与错误码枚举同时在位")
    void contextLoads() {
        assertThat(context.getBean(com.mindisle.auth.AuthService.class)).isNotNull();
        assertThat(context.getBean(com.mindisle.security.JwtService.class)).isNotNull();
        assertThat(context.getBean(com.mindisle.cache.CacheService.class).mode()).isEqualTo("local");
        assertThat(ErrorCode.fromCode(0)).isEqualTo(ErrorCode.SUCCESS);
    }
}

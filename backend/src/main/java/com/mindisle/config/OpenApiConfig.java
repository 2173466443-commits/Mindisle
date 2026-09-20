package com.mindisle.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 分组（springdoc-openapi 3.1.1，见手册 §5.3）。
 *
 * <p>分组口径与 Gate 2 验收一致：在 /doc.html 的下拉里能看到分组列表（阶段 2 为 5 个，阶段 3 起增加「06-audit 内容安全」与「07-file 文件与上传」共 7 个）、接口总数不少于 20，
 * 论文第 6 章接口清单按同一口径统计，避免答辩时数字对不上。</p>
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI mindisleOpenApi() {
        return new OpenAPI()
                .info(new Info().title("心屿 MindIsle 服务端接口").version("0.0.1-SNAPSHOT")
                        .contact(new Contact().name("MindIsle 本科毕设 009"))
                        .description("AI 心理陪伴与互助社区平台。统一响应体 Result 含 code、msg、data、traceId；鉴权方式为 Authorization: Bearer 加访问令牌。本平台不提供诊断或治疗建议。"))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("登录接口签发的访问令牌")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    @Bean
    public GroupedOpenApi authApi() {
        return GroupedOpenApi.builder().group("01-auth 认证与安全").pathsToMatch("/api/auth/**").build();
    }

    @Bean
    public GroupedOpenApi userApi() {
        return GroupedOpenApi.builder().group("02-user 用户与隐私").pathsToMatch("/api/users/**").build();
    }

    @Bean
    public GroupedOpenApi systemApi() {
        return GroupedOpenApi.builder().group("03-system 系统与话题").pathsToMatch("/api/system/**", "/api/topics/**").build();
    }

    @Bean
    public GroupedOpenApi feedApi() {
        return GroupedOpenApi.builder().group("04-feed 社区与推荐").pathsToMatch("/api/posts/**", "/api/feed/**").build();
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder().group("05-admin 管理端").pathsToMatch("/api/admin/**").build();
    }

    @Bean
    public GroupedOpenApi auditApi() {
        return GroupedOpenApi.builder().group("06-audit 内容安全").pathsToMatch("/api/audit/**").build();
    }

    @Bean
    public GroupedOpenApi fileApi() {
        return GroupedOpenApi.builder().group("07-file 文件与上传").pathsToMatch("/api/files/**").build();
    }
}

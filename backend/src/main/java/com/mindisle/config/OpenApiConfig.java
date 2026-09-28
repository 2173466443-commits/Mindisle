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
 * <p>分组口径与 Gate 2 验收一致：在 /doc.html 的下拉里能看到分组列表——阶段 2 为 5 个，阶段 3 先加「06-audit 内容安全」
 * 与「07-file 文件与上传」到 7 个，任务 T3.11-b 再加「08-notify 站内通知」到 8 个，任务 T3.9 加「09-search 站内搜索」到 9 个，任务 T4.2/T4.5 加「10-ai AI 对话」到 10 个（阶段 4 的全部 6 条路径都在这一组）；
 * 任务 T4.20 补「12-privacy 隐私中心」、任务 T5.4 加「13-pm 站内私信」到 13 个（11-emotion 在 T4.9 单独成组，所以 10/11 是错开的两段业务而不是编号用错）；
 * 接口总数不少于 20，论文第 6 章接口清单按同一口径统计，避免答辩时数字对不上。</p>
 *
 * <p><b>分组数没有被任何脚本写死</b>：手册 v1.2.0 之前有一句「Swagger 的 @Tag 分组数被 docs/openapi-check
 * 断言写死」，实测 docs/ 下不存在该脚本、smoke.mjs 也不校验 /v3/api-docs，已按 bug 订正（手册 §19 v1.2.1）。
 * 真正的约束是反过来的：一批新路径如果忘了出现在这里的某个 pathsToMatch 里，它在 /doc.html 上会一条都看不见，
 * 所以<b>新增一批对外路径就必须同时在这里加一个分组</b>，这件事由冒烟后的手工核对负责。</p>
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

    @Bean
    public GroupedOpenApi notifyApi() {
        return GroupedOpenApi.builder().group("08-notify 站内通知")
                .pathsToMatch("/api/notifications/**").build();
    }

    /**
     * 搜索分组（任务 T3.9）。
     *
     * <p>三条子路径的出参形状各不相同（帖是分页流、话题与人是定长数组），所以没有把它们
     * 挤进一条 {@code /api/search} 路径——理由与口径偏离的记录写在 {@code SearchController} 的类注释里。</p>
     */
    @Bean
    public GroupedOpenApi searchApi() {
        return GroupedOpenApi.builder().group("09-search 站内搜索")
                .pathsToMatch("/api/search/**").build();
    }

    /**
     * 情绪域分组（任务 T4.9 / T4.10 / T4.20）。
     *
     * <p>单独成组而不并进「10-ai」：这四条路径一个模型调用都不发（周报是唯一例外，
     * 它发一次且不阻塞），闸门也不同——AI 对话要过同意闸 + 预算闸 + 限流 6 次/分，
     * 情绪打卡只要「登录 + 同意」。混在一组，文档上就看不出这两套闸门差在哪。</p>
     */
    @Bean
    public GroupedOpenApi emotionApi() {
        return GroupedOpenApi.builder().group("11-emotion 情绪与档案")
                .pathsToMatch("/api/emotions/**").build();
    }

    /**
     * AI 对话分组（任务 T4.2 / T4.5 / T4.19）。
     *
     * <p>它必须单独存在：本域的 {@code POST /api/ai/chat/stream} 出参是 text/event-stream，
     * 整批新路径如果漏了这里的 pathsToMatch，在 /doc.html 上会一条都看不见，
     * 而答辩要演示的恰好是这一域（类注释里的「新增路径必须同步加分组」就是这个意思）。</p>
     */
    @Bean
    public GroupedOpenApi aiApi() {
        return GroupedOpenApi.builder().group("10-ai AI 对话")
                .pathsToMatch("/api/ai/**").build();
    }

    /**
     * 隐私中心（任务 T4.20 · 需求 FR11）。阶段 4 收工时漏配的一个分组：
     * {@code PrivacyController} 的 @Tag 当时就叫「12 隐私中心」，但这里没有对应的
     * pathsToMatch，于是 /doc.html 下拉里看不到它 —— 分组编号 12 因此空了一段。
     * 补在这一行而不是插到 11 前面：编号一旦对外（前端文档、论文接口清单）引用过就不再改。
     */
    @Bean
    public GroupedOpenApi privacyApi() {
        return GroupedOpenApi.builder().group("12-privacy 隐私中心")
                .pathsToMatch("/api/privacy/**").build();
    }

    /**
     * 私信（任务 T5.4/T5.6/T5.9 · 需求 FR6）。
     *
     * <p>只收 REST 侧 {@code /api/pm/**}；{@code /app/private}、{@code /app/read}、{@code /app/ping}
     * 是 STOMP 目的地，不经过 HTTP 控制器映射，springdoc 抓不到，也不该抓 ——
     * 手册 §8.4 的「REST 与 STOMP 同一套判据」靠单测保证，不靠 OpenAPI 清单。</p>
     */
    @Bean
    public GroupedOpenApi pmApi() {
        return GroupedOpenApi.builder().group("13-pm 站内私信")
                .pathsToMatch("/api/pm/**").build();
    }
}

package com.mindisle.ai.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.config.MindisleProperties;

/**
 * LlmClient 的装配（任务 T4.1 · 「三实现 + 一个开关」里的那个开关）。
 *
 * <p><b>为什么用 ObjectProvider&lt;ChatModel&gt; 而不是直接注入 ChatModel</b>：
 * provider=mock 或 raw-http 时，Spring AI 的自动配置可能因为没配 api-key 而根本不产生
 * ChatModel bean。直接注入会让<b>应用启动失败</b> —— 而这个应用在没有 Key 的开发机上
 * 必须还能起来（阶段 1–3 的全部功能与 AI 无关）。ObjectProvider 把「有没有」推迟到取的那一刻，
 * 取不到就在下面明确报错，而不是抛一个 NoSuchBeanDefinitionException 让人猜。</p>
 *
 * <p><b>provider 值做了归一化</b>：{@code spring-ai} 与 {@code springai} 与 {@code SPRING_AI}
 * 都指向同一个实现。理由不是贴心，是 yml 里写错一个连字符就静默回落到别的实现，
 * 排查时看不出任何差别 —— 这种失败模式必须在装配阶段就变成「要么明确匹配，要么明确报错」。</p>
 */
@Configuration
public class LlmClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmClientConfiguration.class);

    @Bean
    public LlmClient llmClient(MindisleProperties properties, ObjectMapper mapper,
                              ObjectProvider<ChatModel> chatModels, Environment environment) {
        MindisleProperties.Llm cfg = properties.getLlm();
        String provider = normalize(cfg.getProvider());
        boolean hasKey = cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
        ChatModel chatModel = chatModels.getIfAvailable();

        LlmClient client = switch (provider) {
            case "mock" -> new MockLlmClient();
            case "raw" -> {
                if (!hasKey) {
                    throw new IllegalStateException("mindisle.llm.provider=raw-http 但 api-key 为空："
                            + "请在环境变量 DEEPSEEK_API_KEY 中提供，或把 provider 改成 mock（仅开发）");
                }
                yield new RawHttpLlmClient(cfg, mapper);
            }
            default -> {
                if (!hasKey) {
                    throw new IllegalStateException("mindisle.llm.provider=spring-ai 但 api-key 为空："
                            + "请设置 DEEPSEEK_API_KEY，或把 provider 改成 mock（仅开发）");
                }
                if (chatModel == null) {
                    throw new IllegalStateException("mindisle.llm.provider=spring-ai 但容器里没有 ChatModel bean："
                            + "检查 spring-ai-starter-model-deepseek 是否生效、spring.ai.deepseek.api-key 是否配到");
                }
                yield new SpringAiLlmClient(chatModel, cfg);
            }
        };

        // 这一段日志是「降级到底会不会生效」的现场证据，也是演示前唯一必看的一行
        log.info("AI 装配完成：provider={} → 实现={} model={} thinking={} json=按需 key={}",
                cfg.getProvider(), client.name(), cfg.getModel(),
                cfg.isThinkingEnabled() ? "ON(注意事实B会白屏)" : "OFF",
                hasKey ? "已配置" : "缺失");
        if (!hasKey && "mock".equals(provider)) {
            log.warn("当前使用 mock 模型实现，AI 对话返回的是固定话术，不是模型输出，不可用于演示与答辩");
        }
        return client;
    }

    /** 把 yml 里的写法归一到 spring-ai | raw | mock 三个值。 */
    static String normalize(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase().replace('_', '-');
        return switch (v) {
            case "spring-ai", "springai", "spring", "ai", "" -> "spring";
            case "raw-http", "raw", "http", "jdk" -> "raw";
            case "mock", "fake", "test" -> "mock";
            default -> throw new IllegalStateException("未知的 mindisle.llm.provider：" + raw
                    + "，允许值 spring-ai | raw-http | mock");
        };
    }
}

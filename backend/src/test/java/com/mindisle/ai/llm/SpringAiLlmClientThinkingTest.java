package com.mindisle.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.ResponseFormat;

import com.mindisle.config.MindisleProperties;

/**
 * thinking 开关的单测（任务 T4.1 · dev-log「事实 B」）。
 *
 * <p><b>为什么单独钉这一件事</b>：deepseek-flash 是思考型模型，Spring AI 的默认值跟随上游
 * （= 默认开启思考）。开启时模型把话说在 reasoning_content 里，content 恒为空 ——
 * 实测 contentChars=0 / reasoningChars=484，界面表现是「转圈直到 30 秒超时」，
 * 而且<b>不报错、不打日志</b>。这种「改回默认值就静默失效」的配置，只有单测拦得住。
 * {@code application.yml}、{@link SpringAiLlmClient} 与 {@link MindisleProperties.Llm}
 * 三处注释都指名本类是那道守卫，所以本类必须存在且必须真的能红。</p>
 *
 * <p>不启 Spring、不接库、不发网络请求：只检查 {@link SpringAiLlmClient#buildOptions(ChatRequest)}
 * 拼出来的 options 对象，因此这里给 ChatModel 传 null（buildOptions 不使用它）。</p>
 */
@DisplayName("T4.1 Spring AI 通道：thinking 开关与参数回退")
class SpringAiLlmClientThinkingTest {

    private static ChatRequest req(String scene, boolean jsonMode) {
        return new ChatRequest(scene, List.of(LlmMessage.user("今晚又睡不着")), null, null, null,
                jsonMode, "chat_default_v1", 1L);
    }

    private static SpringAiLlmClient client(boolean thinkingEnabled) {
        MindisleProperties.Llm cfg = new MindisleProperties().getLlm();
        cfg.setThinkingEnabled(thinkingEnabled);
        return new SpringAiLlmClient(null, cfg);
    }

    @Test
    @DisplayName("配置默认值本身就是关：有人把 Llm.thinkingEnabled 改回 true，这条先红")
    void defaultIsThinkingOff() {
        assertThat(new MindisleProperties().getLlm().isThinkingEnabled())
                .as("事实 B：默认开思考会让 content 恒空，默认值必须是 false").isFalse();
    }

    @Test
    @DisplayName("thinkingEnabled=false 时 options 产出 Thinking.DISABLED（这一行没了，SSE 就一个字都不上屏）")
    void disabledWhenConfiguredOff() {
        DeepSeekChatOptions options = client(false).buildOptions(req("chat", false));

        assertThat(options.getThinking())
                .as("必须显式带 thinking:{type:disable}，不能靠上游默认").isNotNull();
        assertThat(options.getThinking().type()).isEqualTo(DeepSeekApi.ChatCompletionRequest.Thinking.Type.DISABLED);
        assertThat(options.getThinking()).isEqualTo(DeepSeekApi.ChatCompletionRequest.Thinking.DISABLED);
    }

    @Test
    @DisplayName("thinkingEnabled=true 时才给 ENABLED：两个分支各有一个产出，不是无条件的 DISABLED")
    void enabledWhenConfiguredOn() {
        DeepSeekChatOptions options = client(true).buildOptions(req("chat", false));

        assertThat(options.getThinking().type()).isEqualTo(DeepSeekApi.ChatCompletionRequest.Thinking.Type.ENABLED);
        assertThat(options.getThinking()).isNotEqualTo(DeepSeekApi.ChatCompletionRequest.Thinking.DISABLED);
    }

    @Test
    @DisplayName("model/temperature/maxTokens 缺省回落配置、请求侧显式给值时覆盖（T4.1 参数装配）")
    void fallsBackToConfigAndHonoursRequestOverrides() {
        MindisleProperties.Llm cfg = new MindisleProperties().getLlm();
        SpringAiLlmClient client = new SpringAiLlmClient(null, cfg);
        DeepSeekChatOptions byConfig = client.buildOptions(req("chat", false));
        assertThat(byConfig.getModel()).isEqualTo(cfg.getModel());
        assertThat(byConfig.getTemperature()).isEqualTo(cfg.getTemperature());
        assertThat(byConfig.getMaxTokens()).isEqualTo(cfg.getMaxTokens());

        ChatRequest override = new ChatRequest("chat", List.of(LlmMessage.user("hi")),
                "deepseek-r1", 0.1d, 64, false, "chat_default_v1", 1L);
        DeepSeekChatOptions byRequest = client.buildOptions(override);
        assertThat(byRequest.getModel()).isEqualTo("deepseek-r1");
        assertThat(byRequest.getTemperature()).isEqualTo(0.1d);
        assertThat(byRequest.getMaxTokens()).isEqualTo(64);
    }

    @Test
    @DisplayName("jsonMode 只在请求要求时才带 JSON_OBJECT：普通对话开了它会破坏陪伴语气")
    void responseFormatOnlyForJsonMode() {
        MindisleProperties.Llm cfg = new MindisleProperties().getLlm();
        SpringAiLlmClient client = new SpringAiLlmClient(null, cfg);
        assertThat(client.buildOptions(req("chat", false)).getResponseFormat()).isNull();
        assertThat(client.buildOptions(req("emotion", true)).getResponseFormat()).isNotNull();
        assertThat(client.buildOptions(req("emotion", true)).getResponseFormat().getType())
                .isEqualTo(ResponseFormat.Type.JSON_OBJECT);
    }
}

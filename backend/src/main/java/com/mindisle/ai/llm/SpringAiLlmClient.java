package com.mindisle.ai.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.ResponseFormat;

import com.mindisle.config.MindisleProperties;

/**
 * Spring AI 实现（任务 T4.1 · 默认实现）。
 *
 * <p>这是 provider 的默认值，因为它把鉴权、重试、序列化都交给框架，本项目的代码量最小。
 * 但它<b>不能</b>是无条件可信的默认：下面 disableThinking() 那一行就是踩过坑之后
 * 从框架默认值里夺回来的控制权。</p>
 *
 * <p><b>关于 disableThinking()（dev-log 事实 B，本项目最贵的一个坑）</b>：
 * deepseek-flash 是思考型模型，Spring AI 的默认值跟随上游（即默认开启思考）。
 * 开启时模型把话说在 reasoning_content 里，content 恒为空 —— 实测
 * contentChars=0 / reasoningChars=484，界面表现是「转圈直到 30 秒超时」，
 * 而且<b>不报错、不打日志</b>。关掉之后 firstContentMs=601ms。
 * 这个字段是那种「改回默认值就静默失效」的配置，所以它必须被单测钉住：
 * {@code SpringAiLlmClientThinkingTest} 直接断言 buildOptions() 在
 * {@code thinkingEnabled=false} 时产出 Thinking.DISABLED。</p>
 *
 * <p><b>为什么 {@link #buildOptions(ChatRequest)} 是包级可见而不是 private</b>：
 * 唯一理由是让上面那个单测能直接检查构造出来的 options 对象。
 * 为了可测性放大可见域到 package 是可接受的，放大到 public 不是。</p>
 *
 * <p>流式这里用 {@code toIterable()} 在调用线程上串行消费，而不是把 Reactor 的
 * Subscription 一路传到 Controller：那样 SSE 的写入线程会变成 Netty 的事件循环线程，
 * 在 Servlet 栈上反而更别扭。取消判定仍然逐分片问一次 {@link StreamHandler#isCancelled()}。</p>
 */
public class SpringAiLlmClient implements LlmClient {

  private static final Logger log = LoggerFactory.getLogger(SpringAiLlmClient.class);

  private final ChatModel chatModel;
  private final MindisleProperties.Llm cfg;

  public SpringAiLlmClient(ChatModel chatModel, MindisleProperties.Llm cfg) {
    this.chatModel = chatModel;
    this.cfg = cfg;
  }

  @Override
  public ChatResult chat(ChatRequest request) {
    long started = System.nanoTime();
    try {
      ChatResponse response = chatModel.call(new Prompt(toMessages(request), buildOptions(request)));
      long total = elapsed(started);
      String text = "";
      String thinking = "";
      String finish = "stop";
      if (response.getResult() != null && response.getResult().getOutput() != null) {
        AssistantMessage out = response.getResult().getOutput();
        text = out.getText() == null ? "" : out.getText();
        thinking = reasoningOf(out);
        String fr = response.getResult().getMetadata().getFinishReason();
        if (fr != null && !fr.isBlank()) {
          finish = fr;
        }
      }
      Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
      return new ChatResult(text, thinking, finish, intOf(usage == null ? null : usage.getPromptTokens()),
          intOf(usage == null ? null : usage.getCompletionTokens()), modelOf(response, request),
          request.promptVersion(), total, total, false);
    } catch (LlmException e) {
      throw e;
    } catch (Exception e) {
      throw translate(e, "chat");
    }
  }

  @Override
  public void stream(ChatRequest request, StreamHandler handler) {
    long started = System.nanoTime();
    StringBuilder full = new StringBuilder();
    StringBuilder thinking = new StringBuilder();
    AtomicLong firstTokenMs = new AtomicLong(-1L);
    AtomicReference<String> finish = new AtomicReference<>("stop");
    AtomicReference<String> model = new AtomicReference<>();
    AtomicReference<Usage> usage = new AtomicReference<>();
    AtomicBoolean failed = new AtomicBoolean(false);
    AtomicReference<Exception> error = new AtomicReference<>();
    try {
      for (ChatResponse response : chatModel.stream(new Prompt(toMessages(request), buildOptions(request))).toIterable()) {
        if (handler.isCancelled()) {
          log.info("[spring-ai] 调用方取消流式，已累积 {} 字，scene={}", full.length(), request.scene());
          return;
        }
        if (failed.get()) {
          return;
        }
        if (response.getMetadata() != null) {
          if (response.getMetadata().getModel() != null) {
            model.set(response.getMetadata().getModel());
          }
          if (response.getMetadata().getUsage() != null) {
            usage.set(response.getMetadata().getUsage());
          }
        }
        Generation gen = response.getResult();
        if (gen == null || gen.getOutput() == null) {
          continue;
        }
        String fr = gen.getMetadata().getFinishReason();
        if (fr != null && !fr.isBlank()) {
          finish.set(fr);
        }
        String reason = reasoningOf(gen.getOutput());
        if (!reason.isEmpty()) {
          thinking.append(reason);
        }
        String visible = gen.getOutput().getText();
        if (visible != null && !visible.isEmpty()) {
          if (firstTokenMs.get() < 0) {
            firstTokenMs.set(elapsed(started));
            handler.onFirstToken(firstTokenMs.get());
          }
          full.append(visible);
          handler.onDelta(visible);
        }
      }
      long total = elapsed(started);
      Usage u = usage.get();
      handler.onComplete(new ChatResult(full.toString(), thinking.toString(), finish.get(),
          intOf(u == null ? null : u.getPromptTokens()), intOf(u == null ? null : u.getCompletionTokens()),
          modelOf(model.get(), request), request.promptVersion(), total,
          Math.max(0, firstTokenMs.get()), false));
    } catch (Exception e) {
      error.set(e);
    }
    if (error.get() != null && !failed.get()) {
      handler.onError(translate(error.get(), "stream"));
    }
  }

  @Override
  public String name() {
    return "spring-ai";
  }

  @Override
  public boolean available() {
    return cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
  }

  /**
   * 按请求组装 DeepSeek 专属 options。
   *
   * <p>包级可见只为单测能断言 thinking 开关，见类注释。</p>
   */
  DeepSeekChatOptions buildOptions(ChatRequest request) {
    DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder()
        .model(request.model() == null ? cfg.getModel() : request.model())
        .temperature(request.temperature() == null ? cfg.getTemperature() : request.temperature())
        .maxTokens(request.maxTokens() == null ? cfg.getMaxTokens() : request.maxTokens());
    if (request.jsonMode()) {
      builder.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
    }
    // 事实 B：思考型模型不关思考就没有 content。这一行的存在由 ThinkingTest 钉住。
    if (!cfg.isThinkingEnabled()) {
      builder.disableThinking();
    } else {
      builder.thinking(DeepSeekApi.ChatCompletionRequest.Thinking.ENABLED);
    }
    return builder.build();
  }

  private List<Message> toMessages(ChatRequest request) {
    List<Message> messages = new ArrayList<>();
    for (LlmMessage m : request.messages()) {
      switch (m.role()) {
        case LlmMessage.SYSTEM -> messages.add(new SystemMessage(m.content() == null ? "" : m.content()));
        case LlmMessage.ASSISTANT -> messages.add(new AssistantMessage(m.content() == null ? "" : m.content()));
        default -> messages.add(new UserMessage(m.content() == null ? "" : m.content()));
      }
    }
    return messages;
  }

  /** 思考内容在 Spring AI 2.0 里落在 AssistantMessage 的 metadata 上，键名上游没有公开契约，所以多试几个。 */
  private static String reasoningOf(AssistantMessage message) {
    try {
      Object v = message.getMetadata().get("reasoning_content");
      if (v == null) {
        v = message.getMetadata().get("reasoningContent");
      }
      return v == null ? "" : String.valueOf(v);
    } catch (Exception e) {
      return "";
    }
  }

  private String modelOf(ChatResponse response, ChatRequest request) {
    String echoed = response.getMetadata() == null ? null : response.getMetadata().getModel();
    return modelOf(echoed, request);
  }

  private String modelOf(String echoed, ChatRequest request) {
    if (echoed != null && !echoed.isBlank()) {
      return echoed;
    }
    return request.model() == null ? cfg.getModel() : request.model();
  }

  private static int intOf(Integer value) {
    return value == null ? 0 : value;
  }

  /**
   * 框架异常 → {@link LlmException}。
   *
   * <p>只能靠异常类名与文案匹配（Spring AI 把上游状态码包在 message 里），
   * 所以这里刻意保守：认不出的统统记 NETWORK 而<b>不是</b> BAD_RESPONSE，
   * 免得一个偶发的客户端异常被误判成「上游返回了看不懂的包」而污染熔断统计。</p>
   */
  private LlmException translate(Exception e, String phase) {
    String msg = e.getMessage() == null ? "" : e.getMessage();
    String lower = (e.getClass().getName() + " " + msg).toLowerCase();
    if (lower.contains("timeout") || lower.contains("timed out")) {
      return LlmException.of(LlmException.Kind.TIMEOUT, "读超时于" + phase + "：" + brief(msg), e);
    }
    if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("authentication")) {
      return LlmException.of(LlmException.Kind.AUTH, "鉴权失败于" + phase + "：" + brief(msg), e);
    }
    if (lower.contains("429") || lower.contains("rate") || lower.contains("concurrency")) {
      return LlmException.of(LlmException.Kind.RATE_LIMITED, "上游限流于" + phase + "：" + brief(msg), e);
    }
    if (lower.contains("500") || lower.contains("502") || lower.contains("503") || lower.contains("bad gateway")) {
      return LlmException.of(LlmException.Kind.UPSTREAM, "上游故障于" + phase + "：" + brief(msg), e);
    }
    return LlmException.of(LlmException.Kind.NETWORK, phase + " 失败：" + e.getClass().getSimpleName()
        + " " + brief(msg), e);
  }

  private static String brief(String raw) {
    String oneLine = raw.replace('\r', ' ').replace('\n', ' ').trim();
    return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
  }

  private static long elapsed(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }
}

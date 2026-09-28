package com.mindisle.ai.llm;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.config.MindisleProperties;

/**
 * 裸 HTTP 实现（任务 T4.1 · 手册 §5.4 的第二条路）。
 *
 * <p><b>它存在的理由</b>：Spring AI 的流式返回绑在 Reactor 的 Flux 上，而本项目要的三个行为
 * ——「每读一分片就问一次能不能取消」「首字延迟单独计时」「上游 4xx 要能翻成可分类的
 * {@link LlmException.Kind}」—— 在 Flux 里都要绕。用 JDK 自带的 HttpClient 手写 SSE 解析，
 * 依赖树不增一列，行为完全在我手里，也是框架升级时的逃生通道。</p>
 *
 * <p><b>思考链这个坑必须留在这里说明</b>（dev-log 事实 B）：deepseek-flash 是思考型模型，
 * 不传 {@code thinking={"type":"disabled"}} 时，实测首包 contentChars=0、reasoningChars=484，
 * 也就是模型在说话但说的全在 reasoning_content 里，SSE 一个字都上不了屏。
 * 所以下面 buildBody 里那段 thinking 开关<b>不是可选优化</b>，配 {@code thinkingEnabled=true}
 * 就会白屏，这条由 {@code LlmClientStreamContractTest} 与真实链路探针共同盯住。</p>
 *
 * <p>非流式的 {@code chat()} 里 firstTokenMs 等于总耗时 —— 因为没有「第一个字」这回事，
 * 这个约定写在 {@link ChatResult} 的 javadoc 里，两个实现口径一致。</p>
 */
public class RawHttpLlmClient implements LlmClient {

  private static final Logger log = LoggerFactory.getLogger(RawHttpLlmClient.class);
  private static final String DONE_MARKER = "[DONE]";

  private final MindisleProperties.Llm cfg;
  private final ObjectMapper mapper;
  private final HttpClient http;

  public RawHttpLlmClient(MindisleProperties.Llm cfg, ObjectMapper mapper) {
    this.cfg = cfg;
    this.mapper = mapper;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(cfg.getConnectTimeoutMs()))
        .version(HttpClient.Version.HTTP_1_1)
        .build();
  }

  @Override
  public ChatResult chat(ChatRequest request) {
    long started = System.nanoTime();
    try {
      HttpRequest req = newRequest(buildBody(request, false));
      HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream in = resp.body()) {
        if (resp.statusCode() != 200) {
          throw LlmException.fromStatus(resp.statusCode(), readAll(in, 2048));
        }
        JsonNode root = mapper.readTree(in);
        JsonNode choice = firstChoice(root);
        String text = textOf(choice.path("message").path("content"));
        String thinking = textOf(choice.path("message").path("reasoning_content"));
        JsonNode usage = root.path("usage");
        long total = elapsed(started);
        return new ChatResult(text, thinking, finishOf(choice), usage.path("prompt_tokens").asInt(0),
            usage.path("completion_tokens").asInt(0), modelOf(root, request), request.promptVersion(),
            total, total, false);
      }
    } catch (LlmException e) {
      throw e;
    } catch (Exception e) {
      throw classify(e, "chat");
    }
  }

  @Override
  public void stream(ChatRequest request, StreamHandler handler) {
    long started = System.nanoTime();
    StringBuilder full = new StringBuilder();
    StringBuilder thinking = new StringBuilder();
    long firstTokenMs = -1L;
    String finishReason = null;
    int tokensIn = 0;
    int tokensOut = 0;
    String echoedModel = null;
    JsonNode chunkRoot = mapper.createObjectNode();
    HttpResponse<InputStream> resp;
    try {
      resp = http.send(newRequest(buildBody(request, true)), HttpResponse.BodyHandlers.ofInputStream());
    } catch (Exception e) {
      handler.onError(classify(e, "stream-connect"));
      return;
    }
    if (resp.statusCode() != 200) {
      try (InputStream errIn = resp.body()) {
        handler.onError(LlmException.fromStatus(resp.statusCode(), readAll(errIn, 2048)));
      } catch (Exception e) {
        handler.onError(new LlmException(LlmException.Kind.UPSTREAM, resp.statusCode(), "读取错误响应失败", e));
      }
      return;
    }
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (handler.isCancelled()) {
          log.info("[raw-http] 调用方取消流式，已累积 {} 字，scene={}", full.length(), request.scene());
          return;
        }
        if (line.isEmpty() || !line.startsWith("data:")) {
          continue;
        }
        String payload = line.substring(5).trim();
        if (DONE_MARKER.equals(payload)) {
          break;
        }
        JsonNode chunk = mapper.readTree(payload);
        chunkRoot = chunk;
        if (chunk.hasNonNull("model")) {
          echoedModel = chunk.path("model").asText();
        }
        if (chunk.has("usage") && !chunk.path("usage").isNull()) {
          tokensIn = chunk.path("usage").path("prompt_tokens").asInt(tokensIn);
          tokensOut = chunk.path("usage").path("completion_tokens").asInt(tokensOut);
        }
        JsonNode choice = firstChoice(chunk);
        if (choice.isMissingNode()) {
          continue;
        }
        String fr = choice.path("finish_reason").asText(null);
        if (fr != null && !"null".equals(fr)) {
          finishReason = fr;
        }
        JsonNode delta = choice.path("delta");
        String visible = textOf(delta.path("content"));
        String reason = textOf(delta.path("reasoning_content"));
        if (!reason.isEmpty()) {
          thinking.append(reason);
        }
        if (!visible.isEmpty()) {
          if (firstTokenMs < 0) {
            firstTokenMs = elapsed(started);
            handler.onFirstToken(firstTokenMs);
          }
          full.append(visible);
          handler.onDelta(visible);
        }
      }
      long total = elapsed(started);
      handler.onComplete(new ChatResult(full.toString(), thinking.toString(),
          finishReason == null ? "stop" : finishReason, tokensIn, tokensOut,
          echoedModel == null ? modelOf(chunkRoot, request) : echoedModel, request.promptVersion(),
          total, Math.max(0, firstTokenMs), false));
    } catch (LlmException e) {
      handler.onError(e);
    } catch (Exception e) {
      handler.onError(classify(e, "stream-body"));
    }
  }

  @Override
  public String name() {
    return "raw-http";
  }

  @Override
  public boolean available() {
    return cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
  }

  /**
   * 组装 OpenAI 兼容的请求体。
   *
   * <p>{@code stream_options.include_usage} 只有流式下才合法，非流式带上会被上游 400，
   * 所以分开加。温度/max_tokens 为 null 时回落配置默认。</p>
   */
  private Map<String, Object> buildBody(ChatRequest request, boolean streaming) {
    List<Map<String, String>> msgs = new ArrayList<>();
    for (LlmMessage m : request.messages()) {
      Map<String, String> one = new LinkedHashMap<>();
      one.put("role", m.role());
      one.put("content", m.content() == null ? "" : m.content());
      msgs.add(one);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", request.model() == null ? cfg.getModel() : request.model());
    body.put("messages", msgs);
    body.put("stream", streaming);
    body.put("temperature", request.temperature() == null ? cfg.getTemperature() : request.temperature());
    body.put("max_tokens", request.maxTokens() == null ? cfg.getMaxTokens() : request.maxTokens());
    if (!cfg.isThinkingEnabled()) {
      body.put("thinking", Map.of("type", "disabled"));
    }
    if (request.jsonMode()) {
      body.put("response_format", Map.of("type", "json_object"));
    }
    if (streaming) {
      body.put("stream_options", Map.of("include_usage", true));
    }
    return body;
  }

  private HttpRequest newRequest(Map<String, Object> body) throws Exception {
    byte[] payload = mapper.writeValueAsBytes(body);
    return HttpRequest.newBuilder()
        .uri(URI.create(trimSlash(cfg.getBaseUrl()) + "/chat/completions"))
        .timeout(Duration.ofMillis(cfg.getReadTimeoutMs()))
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")
        .header("Authorization", "Bearer " + cfg.getApiKey())
        .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
        .build();
  }

  /**
   * 把底层异常翻译成带 Kind 的 LlmException。
   *
   * <p>断网与超时必须区分：断网（IOException 家族里的 ConnectException/UnknownHostException）
   * 说明配置或环境坏了，重试没意义；读超时说明上游慢，值得熔断器记账但不代表永久不可用。</p>
   */
  private LlmException classify(Exception e, String phase) {
    if (e instanceof java.net.http.HttpTimeoutException) {
      return LlmException.of(LlmException.Kind.TIMEOUT, "读超时于" + phase, e);
    }
    if (e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException) {
      return LlmException.of(LlmException.Kind.NETWORK, "网络不可达于" + phase + "：" + e.getMessage(), e);
    }
    if (e instanceof java.io.InterruptedIOException) {
      return LlmException.of(LlmException.Kind.TIMEOUT, "中断于" + phase, e);
    }
    if (e instanceof com.fasterxml.jackson.core.JsonProcessingException) {
      return LlmException.of(LlmException.Kind.BAD_RESPONSE, "响应解析失败于" + phase, e);
    }
    return LlmException.of(LlmException.Kind.NETWORK, phase + " 失败：" + e.getClass().getSimpleName(), e);
  }

  private static JsonNode firstChoice(JsonNode root) {
    JsonNode choices = root.path("choices");
    if (choices.isArray() && !choices.isEmpty()) {
      return choices.get(0);
    }
    return MissingNode.getInstance();
  }

  private static String finishOf(JsonNode choice) {
    String fr = choice.path("finish_reason").asText(null);
    return fr == null || "null".equals(fr) ? "stop" : fr;
  }

  /** content 为 null 与字段缺失都归一成空串，调用方无需再判 null。 */
  private static String textOf(JsonNode node) {
    if (node == null || node.isNull() || !node.isTextual()) {
      return "";
    }
    return node.asText();
  }

  /** 上游回显的模型名优先；它没回显时回落到请求里发出去的那个名字。 */
  private String modelOf(JsonNode root, ChatRequest request) {
    String echoed = textOf(root.path("model"));
    if (!echoed.isEmpty()) {
      return echoed;
    }
    return request.model() == null ? cfg.getModel() : request.model();
  }

  private static String trimSlash(String url) {
    if (url == null) {
      return "";
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private static String readAll(InputStream in, int cap) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try {
      byte[] buf = new byte[1024];
      int n;
      int got = 0;
      while ((n = in.read(buf)) > 0 && got < cap) {
        bos.write(buf, 0, n);
        got += n;
      }
    } catch (Exception ignored) {
      return "";
    }
    return bos.toString(StandardCharsets.UTF_8);
  }

  private static long elapsed(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }
}

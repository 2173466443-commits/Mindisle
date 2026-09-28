package com.mindisle.ai.llm;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mock 实现（任务 T4.1）—— <b>只服务于单元测试与断网演示，绝不允许成为线上默认</b>。
 *
 * <p>本项目的规矩是「不用 mock 糊弄演示」。为了让这条规矩可执行，
 * mock 的痕迹必须做到「想藏都藏不住」：
 * ① 每次调用打一条 WARN；② 回复正文自带 {@code 【MOCK】} 前缀；
 * ③ {@code ChatResult.model} 写成 {@code mock-llm} 而不是真模型名，
 * 于是它会原样落进 {@code ai_call_log.model} 与 {@code chat_message.model}，
 * 事后查账一眼能看出这段时间的对话不是模型给的。</p>
 *
 * <p>分片是固定 6 字一切，不模拟网络抖动 —— 单测要的是「delta 会被调用多次」这件事可断言，
 * 抖动交给 {@code RawHttpLlmClient} 的真实调用去验。</p>
 */
public class MockLlmClient implements LlmClient {

  private static final Logger log = LoggerFactory.getLogger(MockLlmClient.class);

  /** 落进 model 列的名字，带 mock 字样是刻意的。 */
  public static final String MODEL_NAME = "mock-llm";

  private static final int CHUNK_CHARS = 6;

  private final AtomicLong calls = new AtomicLong();

  @Override
  public ChatResult chat(ChatRequest request) {
    long started = System.nanoTime();
    String text = replyFor(request);
    log.warn("[MOCK] LLM chat scene={} promptVersion={} —— 这是 mock 回复，不是模型输出，别拿它当演示结果",
        request.scene(), request.promptVersion());
    calls.incrementAndGet();
    return new ChatResult(text, "", "stop", estimateTokens(request.messages()),
        text.length(), MODEL_NAME, request.promptVersion(), elapsedMs(started), elapsedMs(started), false);
  }

  @Override
  public void stream(ChatRequest request, StreamHandler handler) {
    long started = System.nanoTime();
    String text = replyFor(request);
    log.warn("[MOCK] LLM stream scene={} promptVersion={} —— mock 流式，仅用于单测/断网演示",
        request.scene(), request.promptVersion());
    calls.incrementAndGet();
    long first = -1;
    for (int i = 0; i < text.length(); i += CHUNK_CHARS) {
      if (handler.isCancelled()) {
        log.warn("[MOCK] 流式被调用方取消，已发 {}/{} 字", i, text.length());
        return;
      }
      String piece = text.substring(i, Math.min(text.length(), i + CHUNK_CHARS));
      if (first < 0) {
        first = elapsedMs(started);
        handler.onFirstToken(first);
      }
      handler.onDelta(piece);
    }
    handler.onComplete(new ChatResult(text, "", "stop", estimateTokens(request.messages()),
        text.length(), MODEL_NAME, request.promptVersion(), elapsedMs(started), Math.max(0, first), false));
  }

  @Override
  public String name() {
    return "mock";
  }

  /** 单测里想知道「这一路有没有真的走到 mock」。 */
  public long callCount() {
    return calls.get();
  }

  /**
   * 按场景给固定话术。
   *
   * <p>JSON 类场景（emotion/risk）返回<b>可解析的</b> JSON，否则 T4.8/T4.11 的解析分支
   * 在单测里永远走不到。对话场景返回带 {@code 【MOCK】} 前缀的中文，长度故意超过一个分片。</p>
   */
  private String replyFor(ChatRequest request) {
    return switch (request.scene()) {
      case "emotion" -> "{\"label\":\"sadness\",\"intensity\":3,\"valence\":-1,\"risk\":0.1,\"confidence\":0.72}";
      case "risk" -> "{\"risk\":0.1,\"level\":\"L0\",\"intent\":0,\"evidence\":\"\"}";
      case "summary" -> "对方提到期末周 sleep 不足，反复担心导师评价，已确认无自伤念头。";
      case "report" -> "本周你的情绪整体偏平静，低谷集中在周中。";
      default -> "【MOCK】我在，你慢慢说。这段话是 mock 实现给的固定话术，不是模型输出。";
    };
  }

  /** 粗略 token 估算：中文按 1 字 1 token，其余按 4 字符 1 token（与 ContextAssembler 同一口径）。 */
  static int estimateTokens(List<LlmMessage> messages) {
    int total = 0;
    for (LlmMessage m : messages) {
      total += ContextEstimator.estimate(m.content());
    }
    return total;
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }
}

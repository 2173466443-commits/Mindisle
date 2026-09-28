package com.mindisle.ai.llm;

/**
 * 一次模型调用的完整结果（任务 T4.1）。
 *
 * <p>{@code firstTokenMs} 是 NFR2 的 TTFT，也是「逐字流式到底有没有成立」的唯一证据：
 * 一次 3 秒返回整段的调用和一次首字 300ms、总耗时同样 3 秒的流式调用，在
 * {@code latencyMs} 上看不出任何区别。非流式的 {@code chat()} 里它等于总耗时，
 * 这一点在 {@code SpringAiLlmClient} 与 {@code RawHttpLlmClient} 里都写明了，
 * 不要让它被误读成「流式很慢」。</p>
 *
 * <p>{@code thinking} 只在思考型模型且未显式关闭思考时非空。<b>它是排查现场最有用的一个字段</b>：
 * dev-log 记的「SSE 一个字都不上屏」那次，唯一能证明「模型其实在说话、只是说的不是 content」
 * 的证据就是 reasoningChars=484 / contentChars=0 这一对数。</p>
 *
 * @param text          正文（已拼接完所有增量）
 * @param thinking      思考内容，可为空
 * @param finishReason  stop | length | content_filter | error，原样透传
 * @param tokensIn      输入 token，取不到时 0
 * @param tokensOut     输出 token，取不到时 0
 * @param model         服务端回显的模型名；为空时回落到请求里的名字
 * @param promptVersion 回显 {@code ChatRequest.promptVersion}，方便直接落库
 * @param latencyMs     总耗时
 * @param firstTokenMs  首字延迟（TTFT）
 * @param degraded      true = 本次不是模型给的，而是离线共情话术库（T4.13）
 */
public record ChatResult(String text, String thinking, String finishReason, int tokensIn, int tokensOut,
                         String model, String promptVersion, long latencyMs, long firstTokenMs, boolean degraded) {

  /** 离线降级结果的构造入口：界面必须显示「离线模式」，所以 degraded 恒 true。 */
  public static ChatResult offline(String text, String promptVersion, long latencyMs) {
    return new ChatResult(text, "", "offline", 0, 0, "offline-empathy-bank", promptVersion, latencyMs, latencyMs, true);
  }

  /** token 总数，预算判定读它。 */
  public int tokensTotal() {
    return Math.max(0, tokensIn) + Math.max(0, tokensOut);
  }
}

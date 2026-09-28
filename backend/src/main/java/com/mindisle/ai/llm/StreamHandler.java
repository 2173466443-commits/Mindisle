package com.mindisle.ai.llm;

/**
 * 流式回调（任务 T4.5 / T4.6）。
 *
 * <p><b>为什么是回调而不是返回 {@code Flux}</b>：手册 T4.1 明写 RawHttpLlmClient
 * 「JDK HttpClient + 手写 SSE 行解析，无 Reactor 依赖」，接口一旦把 Flux 写进签名，
 * 那个实现就得引入 Reactor 才能满足类型。回调节线让三个实现（Spring AI / 裸 HTTP / Mock）
 * 都能只依赖这一个接口。</p>
 *
 * <p>{@link #isCancelled()} 是「停止生成」能真正省钱的关键：前端 abort 之后，
 * 服务端如果还在读上游流，DeepSeek 那边的 token 照样在烧。
 * 三个实现在<b>每读一个分片后</b>都要问一次这个方法，为 true 就立刻停手。
 * 这也是为什么它是 default 而不是必须实现——纯单测里的哑实现不需要管取消。</p>
 */
public interface StreamHandler {

  /** 第一个 content 分片到达时回调一次，参数是首字延迟毫秒（NFR2 的 TTFT）。 */
  default void onFirstToken(long firstTokenMs) {
  }

  /** 每个可见文本增量。实现方要按到达顺序串行调用，不做合并。 */
  void onDelta(String text);

  /** 正常结束。{@code result.text()} 与所有 onDelta 拼起来的内容一致。 */
  void onComplete(ChatResult result);

  /** 异常结束（网络、鉴权、超时、解析失败都走这里，参数保留原始异常供上层分类）。 */
  void onError(Throwable error);

  /** 调用方是否已经不要这一路流了（用户点了停止，或 SSE 连接已断）。 */
  default boolean isCancelled() {
    return false;
  }
}

package com.mindisle.ai.llm;

import java.util.List;

/**
 * 模型接入抽象（任务 T4.1 · 手册 §5.4「三实现 + 一个开关」）。
 *
 * <p>三个实现的选择只看 {@code mindisle.llm.provider}：
 * {@code spring-ai}（默认，走 Spring AI 的 ChatModel）、{@code raw-http}
 * （JDK HttpClient 手写 SSE，不引 Reactor）、{@code mock}（<b>只给单测和断网演示</b>）。</p>
 *
 * <p><b>为什么值得自己维护三个实现</b>：本项目的对话链路要同时满足
 * 「首字延迟可测量」（NFR2）、「中途能取消」（T4.6）、「Key 失效时能降级」（T4.13），
 * 这三件事在框架里的支持程度各不相同。留一条裸 HTTP 后路，
 * 意味着框架升级把某个行为改掉时，我有第二条能立刻跑通的路，而不是当场失去产品能力。
 * 反过来，如果没有 {@code mock}，任何一次断网都意味着整个阶段 4 的测试跑不动 ——
 * 但 mock <b>绝不能出现在演示与生产</b>：它在日志里必须自带 mock 字样，
 * 且 {@code ChatResult.model} 也标成 mock，让「这是假回复」在三处都能被看见。</p>
 */
public interface LlmClient {

  /** 一次性请求-响应（情绪兜底 T4.8、风险通道 T4.11、摘要压缩 T4.3 都用它：不需要边出边看）。 */
  ChatResult chat(ChatRequest request);

  /**
   * 流式请求（对话主链路 T4.5）。
   *
   * <p>实现方<b>必须</b>保证：要么 {@code onComplete}，要么 {@code onError}，二者恰好其一；
   * 否则上层的 SseEmitter 会一直挂着等不到收尾，最终吃掉整个 30s 超时。
   * 这条契约由 {@code LlmClientStreamContractTest} 对三个实现统一钉住。</p>
   */
  void stream(ChatRequest request, StreamHandler handler);

  /** 实现名，进 ai_call_log.model 前的前缀与日志标识：spring-ai / raw-http / mock。 */
  String name();

  /** 当前配置下这个实现是否可用（缺 Key 的 spring-ai/raw-http 就是不可用，用于降级判定）。 */
  default boolean available() {
    return true;
  }

  /** 允许的 scene 列表，仅用于配置自检日志。 */
  static List<String> scenes() {
    return ChatRequest.SCENES;
  }
}

package com.mindisle.ai.llm;

import java.util.List;

/**
 * 一次模型调用的完整入参（任务 T4.1 · 手册 §7.1 行 4.1 的字段清单逐字对应）。
 *
 * <p>{@code scene} 的取值域就是 {@code ai_call_log.scene} 的 ENUM：
 * chat / emotion / risk / audit / summary / report / embed。它是预算与熔断的统计维度
 * （需求 FR2.7），所以<b>不允许出现枚举之外的字符串</b>——构造器里直接夹住，
 * 不靠调用点自觉。</p>
 *
 * <p>{@code jsonMode} 对应 DeepSeek 的 {@code response_format={"type":"json_object"}}
 * （实测可用，见 dev-log 阶段 4「事实 C」）。情绪兜底与风险双通道都靠它拿到可解析输出；
 * 普通对话<b>不要</b>开它，开了模型会倾向于输出带引号的结构化文本，破坏陪伴语气。</p>
 *
 * <p>{@code promptVersion} 只为记日志而存在（写进 ai_call_log.prompt_version 与
 * chat_message.prompt_version），论文做 Prompt 消融时按它分组。</p>
 *
 * @param scene         调用场景，见上
 * @param messages      完整的消息序列，调用方负责顺序（system 在最前）
 * @param model         模型名；null 表示用配置里的默认模型
 * @param temperature   采样温度；null 表示用配置默认
 * @param maxTokens     生成长度上限；null 表示用配置默认
 * @param jsonMode      是否要求 JSON 输出
 * @param promptVersion 提示词模板名+版本，如 {@code chat_default_v1}
 * @param userId        归属用户，可空（定时任务无归属）；只进 ai_call_log，不进请求体
 */
public record ChatRequest(String scene, List<LlmMessage> messages, String model, Double temperature,
                          Integer maxTokens, boolean jsonMode, String promptVersion, Long userId) {

  /** 场景白名单，逐字对齐 sql/02_ai.sql 的 ai_call_log.scene ENUM。 */
  public static final List<String> SCENES = List.of("chat", "emotion", "risk", "audit", "summary", "report", "embed");

  public ChatRequest {
    if (scene == null || !SCENES.contains(scene)) {
      throw new IllegalArgumentException("未知的 AI 调用场景：" + scene + "，允许值 " + SCENES);
    }
    if (messages == null || messages.isEmpty()) {
      throw new IllegalArgumentException("ChatRequest.messages 不能为空");
    }
    messages = List.copyOf(messages);
  }

  /** 便捷构造：只给场景与消息，其余走配置默认。 */
  public static ChatRequest of(String scene, List<LlmMessage> messages, String promptVersion, Long userId) {
    return new ChatRequest(scene, messages, null, null, null, false, promptVersion, userId);
  }

  /** JSON 模式变体（情绪兜底 T4.8、风险通道 T4.11 用）。 */
  public ChatRequest json(boolean json) {
    return new ChatRequest(scene, messages, model, temperature, maxTokens, json, promptVersion, userId);
  }

  public ChatRequest temperature(Double t) {
    return new ChatRequest(scene, messages, model, t, maxTokens, jsonMode, promptVersion, userId);
  }

  public ChatRequest maxTokens(Integer n) {
    return new ChatRequest(scene, messages, model, temperature, n, jsonMode, promptVersion, userId);
  }
}

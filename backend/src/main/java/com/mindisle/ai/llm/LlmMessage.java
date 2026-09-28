package com.mindisle.ai.llm;

/**
 * 送进模型的一条消息（任务 T4.1 的请求侧最小单元）。
 *
 * <p>刻意与落库实体 {@code ChatMessage} 分开：一个 {@code List<LlmMessage>} 里可能同时有
 * 「system 提示词 + 摘要 + 近 8 轮 + 本轮用户输入」，其中摘要是拼出来的、不落库；
 * 而 {@code ChatMessage} 一条对应一行数据库记录、带情绪与风险列。
 * 两者混用的第一天，就会出现「把系统提示词当用户消息存进会话」这种事故。</p>
 *
 * @param role    system | user | assistant，取值域与 chat_message.role 的 ENUM 一致
 * @param content 消息正文
 */
public record LlmMessage(String role, String content) {

  public static final String SYSTEM = "system";
  public static final String USER = "user";
  public static final String ASSISTANT = "assistant";

  public static LlmMessage system(String content) {
    return new LlmMessage(SYSTEM, content);
  }

  public static LlmMessage user(String content) {
    return new LlmMessage(USER, content);
  }

  public static LlmMessage assistant(String content) {
    return new LlmMessage(ASSISTANT, content);
  }
}

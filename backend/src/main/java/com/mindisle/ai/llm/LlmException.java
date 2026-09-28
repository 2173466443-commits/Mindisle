package com.mindisle.ai.llm;

/**
 * 模型调用异常（任务 T4.1 / T4.13）。
 *
 * <p>带 {@code kind} 而不是只带 message，是因为熔断器（T4.13）要按类型决定
 * 「这次算不算连续失败」：超时、401/403 鉴权失败、429 限流、余额不足这几类<b>必须</b>计入，
 * 而「内容被上游拒答 finish_reason=content_filter」不算服务不可用 ——
 * 把它算进去会让一个正常工作的服务被自己的熔断器打死。</p>
 *
 * <p>{@code summary()} 是给 {@code ai_call_log.error} 用的短摘要，
 * <b>永远不会包含 API Key</b>：构造时只接受 HTTP 状态码与异常类名，
 * 不把可能含请求头的原始异常文本抄进去。日志里出现密钥是本项目的红线。</p>
 */
public class LlmException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 失败类型，取值域集中在这里，熔断判定只认它。 */
  public enum Kind {
    /** 连接/读取超时。计入熔断。 */
    TIMEOUT,
    /** 401/403：Key 失效、余额为 0 被拒。计入熔断，且优先级最高（再重试也是白花钱）。 */
    AUTH,
    /** 429 或「ReachedConcurrencyLimit」：上游限流。计入熔断。 */
    RATE_LIMITED,
    /** 5xx：上游故障。计入熔断。 */
    UPSTREAM,
    /** 网络不可达（DNS/断网）。计入熔断。 */
    NETWORK,
    /** 返回能拿到但看不懂（非 SSE、JSON 解析失败、缺 choices）。计入熔断。 */
    BAD_RESPONSE,
    /** 模型正常返回但拒答/被内容策略截断。<b>不</b>计入熔断。 */
    REFUSED;

    /** 这一类失败要不要记进「连续失败」计数。 */
    public boolean countsAsOutage() {
      return this != REFUSED;
    }
  }

  private final Kind kind;
  private final int status;

  public LlmException(Kind kind, int status, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
    this.status = status;
  }

  public static LlmException of(Kind kind, String message) {
    return new LlmException(kind, 0, message, null);
  }

  public static LlmException of(Kind kind, String message, Throwable cause) {
    return new LlmException(kind, 0, message, cause);
  }

  /** 按 HTTP 状态码归类，唯一一处把状态码翻译成 Kind 的地方。 */
  public static LlmException fromStatus(int status, String bodySnippet) {
    Kind kind = switch (status) {
      case 401, 403 -> Kind.AUTH;
      case 429 -> Kind.RATE_LIMITED;
      case 408, 504 -> Kind.TIMEOUT;
      default -> status >= 500 ? Kind.UPSTREAM : Kind.BAD_RESPONSE;
    };
    return new LlmException(kind, status, "HTTP " + status + " " + truncate(bodySnippet), null);
  }

  public Kind kind() {
    return kind;
  }

  public int status() {
    return status;
  }

  public boolean countsAsOutage() {
    return kind.countsAsOutage();
  }

  /** 落 ai_call_log.error（列宽 255）与日志用的摘要，只含类型、状态码与极短正文。 */
  public String summary() {
    return kind.name() + (status > 0 ? "(" + status + ")" : "") + ": " + truncate(getMessage());
  }

  private static String truncate(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    String oneLine = raw.replace('\r', ' ').replace('\n', ' ').trim();
    return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
  }
}

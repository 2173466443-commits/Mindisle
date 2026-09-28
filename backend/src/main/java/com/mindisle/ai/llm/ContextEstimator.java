package com.mindisle.ai.llm;

/**
 * 上下文 token 估算（任务 T4.3 的截断判据）。
 *
 * <p><b>为什么不用真分词器</b>：DeepSeek 用的是自己的 BPE 词表，仓库里没有它的
 * tokenizer 文件，引一个第三方 tokenizer 只会得到一个「看起来很精确其实是错的」数。
 * 这里的口径是<b>刻意做粗</b>：CJK 字符按 1 token、其余按 4 字符 1 token ——
 * 中文实际约 0.6~1 token/字，所以这个估算对中文是<b>偏高</b>的，
 * 偏高的后果是「更早触发截断」，方向上保守（宁可少带一轮历史，也不要超额烧钱）。</p>
 *
 * <p>它同时被 MockLlmClient 用来伪造 tokens_in，这样 ai_call_log 里的数字
 * 在 mock 与真实调用之间是同一个量纲，预算逻辑不会因为切换实现而失真。</p>
 */
public final class ContextEstimator {

  private ContextEstimator() {
  }

  public static int estimate(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    int cjk = 0;
    int other = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (isCjk(c)) {
        cjk++;
      } else if (!Character.isWhitespace(c)) {
        other++;
      }
    }
    return cjk + (other + 3) / 4;
  }

  /** 中日韩统一表意文字 + 扩展 A + 兼容表意 + 全角标点，够覆盖本项目会出现的中文语料。 */
  private static boolean isCjk(char c) {
    return (c >= 0x4E00 && c <= 0x9FFF)
        || (c >= 0x3400 && c <= 0x4DBF)
        || (c >= 0xF900 && c <= 0xFAFF)
        || (c >= 0x3000 && c <= 0x303F)
        || (c >= 0xFF00 && c <= 0xFF65);
  }
}

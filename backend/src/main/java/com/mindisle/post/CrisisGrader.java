package com.mindisle.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.mindisle.audit.SensitiveWordEngine.CheckResult;
import com.mindisle.audit.SensitiveWordEngine.Hit;

/**
 * 危机分级（词面规则通道）——创新点 3「L0–L3 危机—12356 转介闭环」在本阶段的可跑实现。
 *
 * <p><b>为什么现在就有一版规则分级，而不是等任务 4.11 的双通道 RiskScorer</b>：
 * 发帖状态机（任务 3.3）必须当场决定「放行 + 建不建工单 + SLA 多久」，
 * 没有这一层，T3.3 就只能把 risk 命中一律压成 L2，L3 的 30 分钟时限形同虚设。
 * 本类是 4.11 的<b>规则通道前身</b>：接口形状（输入 CheckResult、输出 L0/L2/L3 + 分数）保持不变，
 * 等模型通道接上后，两路取高分即可，不需要改调用方。</p>
 *
 * <p><b>刻意不产出 L1</b>：需求 §5.2 的 L1 判据是「情绪 5 且连续 3 条」，
 * 需要同一用户的时间序列，发帖只有当前这一条文本，判 L1 属于凭空造数据。
 * 词面通道的输出域因此是 {L0, L2, L3}，这一点在论文第 6 章要写明白。</p>
 *
 * <p><b>阈值取向</b>：需求 §5.2 答辩话术已定调「宁可误报，不可漏报——漏报的代价是一条生命，
 * 误报的代价是一位管理员多一次点击」。所以拿不准时向上取 L3，
 * 且本类<b>绝不因为「像玩笑」而向下取</b>——文本里没有语气，规则更没有。</p>
 */
public final class CrisisGrader {

    /** 无风险。与 post.risk_level 的 ENUM 逐字一致。 */
    public static final String L0 = "L0";
    /** 显著风险：置顶求助卡片 + 生成工单（待认领），4 小时内认领。 */
    public static final String L2 = "L2";
    /** 紧急风险：含具体计划/方式/时间/告别语义，30 分钟内认领。 */
    public static final String L3 = "L3";

    /**
     * 词库 v0.1 快照里「自伤自杀」组中属于<b>方式 / 计划 / 告别</b>语义的词条（需求 §5.2 的 L3 判据）。
     *
     * <p>写死成常量而不是塞回词库：sensitive_word.level 只有 black/grey/risk 三档，
     * 没有「risk 里再分 L2/L3」这一列，改 DDL 为一个 Java 侧判定动整个 schema 不值得。
     * <b>词库升版本时必须复核这个清单</b>，这条依赖已写进任务 6.2（词库管理）的验收项。</p>
     */
    static final Set<String> L3_WORDS = Set.of(
            "写遗书", "留遗书", "把东西分给室友", "最后一次跟这里说说话",
            "结束生命", "自我了断", "割腕", "自残", "吞药", "烧炭", "跳下去");

    private CrisisGrader() {
    }

    /**
     * 词面通道定级。
     *
     * @return {@link #L0}/{@link #L2}/{@link #L3}
     */
    public static String levelOf(CheckResult result) {
        if (result == null || !result.riskTouched()) {
            return L0;
        }
        for (Hit hit : result.hits()) {
            if ("risk".equals(hit.level()) && L3_WORDS.contains(hit.word())) {
                return L3;
            }
        }
        return L2;
    }

    /** 是否到了「必须建工单」的线（需求 §5.2：L0/L1 只软引导不建单）。 */
    public static boolean needsTicket(String level) {
        return L2.equals(level) || L3.equals(level);
    }

    /** 命中的风险词，逗号分隔，供 alert_ticket.trigger_words（列宽 200，超出截断）。 */
    public static String triggerWords(CheckResult result, int maxChars) {
        List<String> words = new ArrayList<>();
        for (Hit hit : result.hits()) {
            if ("risk".equals(hit.level()) && !words.contains(hit.word())) {
                words.add(hit.word());
            }
        }
        return cut(String.join("，", words), maxChars);
    }

    /**
     * 工单证据片段（FR10.5「脱敏后 200 字上下文」）。
     *
     * <p>三件事一次做完，缺一不可：
     * ① <b>以第一条危机命中为中心</b>取窗口，而不是从头截——求助语往往在正文中后段，
     *    从头截 200 字经常是一段无关的自我介绍，辅导员看了等于没看；
     * ② <b>遮掉非危机命中</b>（手机号、微信号、QQ 号这类隐私命中）：工单是管理端常驻数据，
     *    把用户的联系方式抄进工单，等于在救助流程里制造一次新的隐私扩散（NFR8 数据最小化）；
     * ③ 换行压成空格，保证管理端表格与站内信一行放得下。</p>
     */
    public static String evidence(String text, CheckResult result, int maxChars) {
        if (text == null || text.isBlank() || maxChars <= 0) {
            return "";
        }
        int[] window = windowOf(text, result, maxChars);
        char[] buf = text.substring(window[0], window[1]).toCharArray();
        for (Hit hit : result.hits()) {
            if ("risk".equals(hit.level())) {
                continue;
            }
            for (int i = Math.max(hit.start(), window[0]); i < Math.min(hit.end(), window[1]); i++) {
                buf[i - window[0]] = '*';
            }
        }
        String masked = new String(buf).replaceAll("\\s+", " ").trim();
        return cut(masked, maxChars);
    }

    /** 计算证据窗口 [start,end)：以首条危机命中为中心，两端不够时用正文其余部分补齐。 */
    private static int[] windowOf(String text, CheckResult result, int maxChars) {
        int length = text.length();
        int anchor = -1;
        for (Hit hit : result.hits()) {
            if ("risk".equals(hit.level())) {
                anchor = hit.start();
                break;
            }
        }
        if (anchor < 0) {
            return new int[] {0, Math.min(length, maxChars)};
        }
        int left = Math.max(0, anchor - maxChars / 2);
        int right = Math.min(length, left + maxChars);
        // 尾部不够长时把窗口整体往前挪，保证拿满 maxChars 个字符。
        left = Math.max(0, right - maxChars);
        return new int[] {left, right};
    }

    private static String cut(String value, int maxChars) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}

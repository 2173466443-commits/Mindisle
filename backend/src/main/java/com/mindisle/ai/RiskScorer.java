package com.mindisle.ai;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.ai.llm.ChatRequest;
import com.mindisle.ai.llm.ChatResult;
import com.mindisle.ai.llm.LlmClient;
import com.mindisle.ai.llm.LlmMessage;
import com.mindisle.audit.SensitiveWordEngine;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.post.CrisisGrader;

/**
 * 危机风险双通道判定（任务 T4.11 · 需求 5.2 节、创新点 2）。
 *
 * <p><b>两路取高分，不是投票</b>：词面规则通道（{@link CrisisGrader}，确定性、零延迟、
 * 不依赖外部服务）与模型语义通道各出一个等级，最终取<b>更严重</b>的那个。
 * 这是「宁可误报、不可漏报」的代价敏感策略的直接翻译 —— 漏报的代价可能是一条生命，
 * 误报的代价只是辅导员多点一次鼠标。投票（两路一致才报警）在本场景是<b>错误</b>的设计：
 * 它把两种代价对称化了。</p>
 *
 * <p><b>模型通道坏了不许把风险判成 L0</b>：兜底方向永远朝「更严重」偏，
 * 所以任何异常都只意味着「模型通道弃权」，词面通道的结论原样生效。
 * 这条由 RiskScorerTest 钉住 —— 它测的不是「正常时能加分」，
 * 而是「上游返回垃圾、超时、抛异常时，词面命中 L3 的那条依然是 L3」。</p>
 *
 * <p>模型通道的分数换算：risk 大于等于 l3Score 记 L3，大于等于 l2Score 记 L2，否则 L0。
 * 词面通道<b>永不产出 L1</b>（CrisisGrader 的既有设计，L1 只可能来自模型的中间分数），
 * 这个不对称要记住，否则写断言时会想当然。</p>
 */
@Component
public class RiskScorer {

    private static final Logger log = LoggerFactory.getLogger(RiskScorer.class);
    public static final String PROMPT = "risk_llm_v1";

    /** 工单 trigger_words 与 evidence_text 的列宽（现查 information_schema：varchar(200) / varchar(500)）。 */
    static final int TRIGGER_WORDS_MAX = 200;
    static final int EVIDENCE_MAX = 500;

    /**
     * 工单证据片段：与帖子侧 CrisisGrader.evidence 同一口径
     * （以风险命中词为锚点开窗、非风险命中一律打星号），L2/L3 都拿它落 alert_ticket.evidence_text。
     */
    private String excerpt(String text, SensitiveWordEngine.CheckResult check) {
        int chars = properties.getCrisis().getEvidenceChars();
        int width = Math.min(EVIDENCE_MAX, Math.max(60, chars));
        return CrisisGrader.evidence(text == null ? "" : text, check, width);
    }

    private final SensitiveWordEngine wordEngine;
    private final LlmClient llm;
    private final PromptTemplate prompts;
    private final MindisleProperties properties;
    private final ObjectMapper mapper;

    public RiskScorer(SensitiveWordEngine wordEngine, LlmClient llm, PromptTemplate prompts,
                      MindisleProperties properties, ObjectMapper mapper) {
        this.wordEngine = wordEngine;
        this.llm = llm;
        this.prompts = prompts;
        this.properties = properties;
        this.mapper = mapper;
    }

    /**
     * 判定结果。
     *
     * @param level        最终等级 L0/L1/L2/L3（两路取高）
     * @param wordLevel    词面通道单独等级（审计与论文对照用）
     * @param llmLevel     模型通道单独等级，null 表示本次弃权
     * @param riskScore    模型给的连续分，null 表示弃权
     * @param evidence     模型照抄的原文片段
     * @param triggerWords 词面命中的危机词
     * @param needsTicket  是否要建危机工单
     */
    public record Risk(String level, String wordLevel, String llmLevel, Double riskScore, String evidence,
                       String triggerWords, boolean needsTicket, String excerpt) {

        /** 词面通道的快捷构造：没有模型结论，excerpt 先留空由调用方或 score() 填。 */
        static Risk wordOnly(String level, String words, String excerpt) {
            return new Risk(level, level, null, null, "", words, CrisisGrader.needsTicket(level), excerpt);
        }

        /** 等级翻译成前端 SSE 用的数字（0/1/2/3）：ChatView 用 Number(riskLevel) 判定，不能传字符串。 */
        public int levelValue() {
            return severity(level);
        }
    }

    /** 只走词面通道（模型总开关关掉，或调用方明确不想花钱时用）。 */
    public Risk scoreWordChannelOnly(String text) {
        SensitiveWordEngine.CheckResult check = wordEngine.check(text == null ? "" : text, "user");
        String level = CrisisGrader.levelOf(check);
        return Risk.wordOnly(level, CrisisGrader.triggerWords(check, TRIGGER_WORDS_MAX),
                excerpt(text, check));
    }

    /**
     * 完整双通道判定。
     *
     * @param traceId 透传给日志，用来把「一条消息」与「一次模型调用」串起来
     */
    public Risk score(String text, Long userId, String traceId) {
        SensitiveWordEngine.CheckResult check = wordEngine.check(text == null ? "" : text, "user");
        String wordLevel = CrisisGrader.levelOf(check);
        String triggerWords = CrisisGrader.triggerWords(check, TRIGGER_WORDS_MAX);
        String excerpt = excerpt(text, check);

        if (!properties.getLlm().isRiskLlmEnabled()) {
            return Risk.wordOnly(wordLevel, triggerWords, excerpt);
        }
        // 词面已经到 L3 时不再花这一次调用：L3 是最严重的等级，模型只可能给出「一样」或「更轻」，
        // 而更轻会被取高分规则丢弃 —— 也就是说这次调用的结论对结果毫无影响，纯属烧钱。
        if (CrisisGrader.L3.equals(wordLevel)) {
            log.info("词面通道已判 L3，跳过模型通道以省钱 traceId={}", traceId);
            return new Risk(CrisisGrader.L3, CrisisGrader.L3, null, null, "", triggerWords, true, excerpt);
        }
        LlmRisk judged = askModel(text, userId, traceId);
        String finalLevel = worse(wordLevel, judged == null ? null : judged.level());
        return new Risk(finalLevel, wordLevel, judged == null ? null : judged.level(),
                judged == null ? null : judged.risk(), judged == null ? "" : judged.evidence(),
                triggerWords, CrisisGrader.needsTicket(finalLevel), excerpt);
    }

    private LlmRisk askModel(String text, Long userId, String traceId) {
        try {
            String prompt = prompts.render(PROMPT, Map.of("text", text == null ? "" : text));
            ChatRequest request = ChatRequest.of("risk", List.of(LlmMessage.user(prompt)), PROMPT, userId);
            ChatResult result = llm.chat(request.json(true).temperature(0.0d));
            return parse(result.text());
        } catch (Exception e) {
            // 兜底方向朝「弃权」而不是朝「无风险」：返回 null 让调用方保留词面结论
            log.warn("风险模型通道弃权（词面结论仍然生效）traceId={} err={}", traceId, summarize(e));
            return null;
        }
    }

    /**
     * 解析模型的 JSON。字段缺失或类型不对一律当弃权，<b>不做</b>「解析不出来就当作没风险」。
     *
     * <p>包级可见是为了让 RiskScorerTest 能直接喂畸形 JSON 断言弃权，
     * 而不必 mock 整个 LlmClient。</p>
     */
    LlmRisk parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(extractJson(raw));
            double risk = root.path("risk").asDouble(-1d);
            if (risk < 0d) {
                log.warn("风险通道返回里没有可用的 risk 字段，按弃权处理：{}", brief(raw));
                return null;
            }
            MindisleProperties.Crisis crisis = properties.getCrisis();
            String level = risk >= crisis.getL3Score() ? CrisisGrader.L3
                    : risk >= crisis.getL2Score() ? CrisisGrader.L2 : CrisisGrader.L0;
            String declared = root.path("level").asText("");
            // 模型自报的 level 只可能把结果推得更严重，不允许推得更轻（同一个保守方向）
            if (severity(declared) > severity(level)) {
                level = declared;
            }
            return new LlmRisk(level, risk, cut(root.path("evidence").asText(""), 200));
        } catch (Exception e) {
            log.warn("风险通道输出不是合法 JSON，按弃权处理：{}", brief(raw));
            return null;
        }
    }

    /** 模型偶尔会把 JSON 包在 markdown 代码围栏里，取第一个花括号到最后一个花括号之间。 */
    private static String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private static int severity(String level) {
        if (CrisisGrader.L3.equals(level)) {
            return 3;
        }
        if (CrisisGrader.L2.equals(level)) {
            return 2;
        }
        if ("L1".equals(level)) {
            return 1;
        }
        return 0;
    }

    /** 取更严重的等级；null 表示该路弃权，不参与比较。 */
    static String worse(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return severity(a) >= severity(b) ? a : b;
    }

    private static String cut(String raw, int max) {
        if (raw == null) {
            return "";
        }
        return raw.length() <= max ? raw : raw.substring(0, max);
    }

    private static String brief(String raw) {
        String oneLine = raw.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    private static String summarize(Exception e) {
        return e.getClass().getSimpleName() + " " + (e.getMessage() == null ? "" : brief(String.valueOf(e.getMessage())));
    }

    /** 模型通道的中间结果。 */
    record LlmRisk(String level, double risk, String evidence) {
    }
}

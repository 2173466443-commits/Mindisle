package com.mindisle.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.mindisle.audit.SensitiveWordEngine;
import com.mindisle.config.MindisleProperties;

/**
 * 对话链路的安全闸（任务 T4.18 · 需求 FR7.1「AI 输出也要过审」、FR10.4、BR8）。
 *
 * <p><b>它卡在两个位置，而且两个位置的原则不一样</b>：</p>
 * <ul>
 *   <li><b>进模型之前（{@link #sanitizeUser}）只做「脏字符清理 + 长度截断 + 注入标记」，
 *       绝不做静默改写。</b>用户的原话是他此刻处境的第一手证据：把「我不想活了」里的字删掉或替换，
 *       风险通道（T4.11）就读不到真话了 —— 那是拿创新点 2 换一种虚假的安全感。
 *       所以这里连「敏感词消音」都不做，敏感词的用途是<b>判级</b>，不是<b>改字</b>。</li>
 *   <li><b>出模型之后（{@link #guardAiOutput}）才允许改写整条回复。</b>拦的是模型说的话，
 *       不是用户说的话，改写的代价只是「这一条回复变成一句更稳妥的求助引导」，
 *       需求 §5.2 明确要「对话继续但不留白」，所以是替换文案而非丢弃消息。</li>
 * </ul>
 *
 * <p><b>结构性防注入优于任何正则</b>：用户输入永远以 {@code role=user} 送进模型，
 * 从不拼进 system 段（见 {@link ContextAssembler}）。做到这一点之后，
 * 「忽略以上指令」在协议层就只是一句普通的话。本类的注入检测因此<b>只产出标记</b>，
 * 用途是让 ChatService 在 system 末尾追加一句硬提醒并留一行日志，
 * 而不是自认为能靠关键词把攻击挡住 —— 那种检测必然漏，也必然误伤
 * 「我刚在看一篇讲提示词注入的论文」这类正常输入。</p>
 *
 * <p><b>为什么要第二份词表（{@code dict/br8_medical.txt}）而不是只靠阶段 3 的敏感词库</b>：
 * 阶段 3 快照的 139 条口径已被 Gate3 证据与单测钉住，且其 ai 侧的「医疗越界词」
 * 只有「抑郁症确诊」「开药」这类组合词，漏判裸病名与裸术语（「你这是抑郁症」
 * 「建议药物治疗」），而 BR8 点名的正是这三项。另开一份输出侧专用表，
 * 不参与发帖机审，因此不会改动阶段 3 的任何一条判定（手册 §14 第 27 条：
 * 同一条判据不许两处实现 —— 这里是<b>两条不同的判据</b>，一条管社区内容，一条管模型输出）。</p>
 *
 * <p><b>失败姿态</b>：词表缺失、为空或列数不符时让应用启动失败。
 * 一个静默失灵的安全闸比一个起不来的服务危险得多（同 {@link SensitiveWordEngine}）。</p>
 */
@Component
public class SafetyGuard {

    private static final Logger log = LoggerFactory.getLogger(SafetyGuard.class);

    /** 危机应答模板名（需求 FR10.4）：L2/L3 时整段替换对话人格模板。 */
    public static final String CRISIS_PROMPT = "crisis_safety_v1";

    /** 单条用户输入送进模型前的码点上限。需求没给数字，取 1000 与评论同宽（FR4.4），再长属粘贴全文。 */
    static final int MAX_INPUT_CHARS = 1000;

    /** BR8 词表位置与列数（类别 / 词条 / 处置）。 */
    static final String BR8_RESOURCE = "dict/br8_medical.txt";
    static final int BR8_COLUMNS = 3;
    static final String BR8_ACTION = "REWRITE";

    /** 清理后仍然为空时给用户的提示语（ChatService 据此返回参数错误，不静默吞掉一条消息）。 */
    static final String EMPTY_MARK = "";

    /** 命中后的替换文案：不删消息、不说「系统故障」，仍然把话接住并指向专业渠道（BR8 原文要求）。 */
    static final String REWRITE_MEDICAL =
            "抱歉，这句我不能替医生下结论。你的感受是真实的，但它值得由专业的人来判断 —— "
            + "学校心理健康中心和正规医疗机构都能给你这个判断。如果你愿意，我们可以接着说说"
            + "现在最难受的是哪一部分，我在这儿听着。";

    static final String REWRITE_CRISIS_ECHO =
            "我听到了你现在有多难受。先别一个人扛着：现在拨打 {{hotline}}，"
            + "或者走到身边任何一个能陪你的人那里；如果已经受伤、或者觉得自己马上会动手，"
            + "直接拨打 120 或去最近的医院急诊。我已经把求助入口放在你手边，"
            + "也会有人跟进，你不需要在这一条消息里解释更多。";

    static final String REWRITE_BLOCKED =
            "抱歉，刚才那句话不太合适，我换一个说法：你现在的处境值得被认真对待，"
            + "如果它已经影响到吃饭睡觉或上课，学校心理健康中心是可以直接去问一问的地方。"
            + "我们继续说，你从哪儿开始觉得撑不住的？";

    /** 注入检测的样本模式（只置标记，见类注释）。label 进日志，不给用户看。 */
    private static final String[][] INJECTION_PATTERNS = {
            // 「忽略以上的所有指令」这类中间夹修饰语的说法，旧写法（只容一个「的」）会漏检 —— 见 v1.2.7 注入台账。
            // 放宽成「忽略/无视 + 指代词 + ≤6 字修饰 + 目标名词」：9 条攻击语料全命中，12 条陪伴语料零误伤。
            {"覆盖指令", "忽略(以上|上面|上述|所有|之前|先前)[^。！？\\n]{0,6}?(指令|提示|规则|设定|要求)"},
            {"覆盖指令", "无视(以上|上面|上述|所有|之前|先前)[^。！？\\n]{0,6}?(指令|提示|规则|设定|要求)"},
            {"覆盖指令", "不(要|能)(遵从|遵守|执行)(以上|上述|之前|你的)(指令|规则|设定)"},
            {"角色改写", "你现在是(?!不)"},   // 「你现在是不是…」是陪伴句式，不是改写指令（v1.2.7 台账里的假阳性回归）
            {"角色改写", "(进入|开启|切换到)(开发者|管理员|越狱|无限制)(模式)?"},
            {"套取提示词", "(系统|初始)(提示词?|指令)(是什么|内容|原文)?"},
            {"套取提示词", "(输出|复述|重复|打印|泄露|给我看)(你的|上面的)?(系统)?(提示词|指令|设定)"},
            {"套取密钥", "(密钥|秘钥|api ?key|secret ?key|token)"},
            {"英文注入", "(?i)ignore\\s+(all\\s+|the\\s+)?(previous|above|prior)\\s+(instructions?|prompts?|rules?)"},
            {"英文注入", "(?i)you\\s+are\\s+now\\s+(a|an|not)\\b"},
            {"英文注入", "(?i)(reveal|show|print)\\s+(your\\s+)?(system\\s+)?prompt"},
    };

    /**
     * 阶段 3 词库里 AI 输出侧唯一的医疗分组名（快照第一列逐字）。转介豁免只认这一组，不许扩大。
     */
    static final String MEDICAL_CATEGORY = "医疗越界词";

    /**
     * 专业转介语境（T4.18 的豁免判据 A）：模型把「开药 / 调药 / 诊断」的决定权交回专业人士。
     *
     * <p><b>为什么需要它</b>：需求 BR8 要求 AI「不诊断、不给剂量、引导去专业渠道」，
     * 而第三条恰好要求回复里出现医生 / 医院 / 复诊。可阶段 3 分组里的 {@code 开药}
     * 是裸词 {@code contains}，于是「减量这件事得由<b>开药</b>的医生来定」
     * 「打电话问一下给你<b>开药</b>的<b>医院</b>」这种<b>最合规的回答</b>也会命中 BLOCK，
     * 输出闸把 BR8 最想要的那句话整条换成套话（2026-09-24 真链路 F 轮实锤，
     * 日志 reason=sensitive-BLOCK:医疗越界词 命中 2 条 = 同一个「开药」出现两次）。</p>
     *
     * <p><b>影响面</b>：只作用于 AI 输出侧，社区发帖 / 评论 / 私信的判定一字未改；
     * 词库快照 139 条口径也不动（改词面会让 DB 与 classpath 快照两处漂移，
     * 而豁免是「怎么用一个词」的判据，本就该长在消费方）。</p>
     */
    private static final Pattern REFERRAL_CONTEXT = Pattern.compile(
            "(由|让|交给|得问|去问|请问|咨询|问一下|联系|预约|挂|遵循|按)[^。！？\\n]{0,10}"
                    + "(医生|医师|大夫|药师|精神科|心理科|医院|门诊|复诊|就诊|心理健康中心)"
                    + "|(医生|医师|大夫|药师|精神科|心理科|医院|门诊)[^。！？\\n]{0,8}"
                    + "(定|决定|判断|评估|调|说|开)"
                    + "|(复诊|就诊|随访)[^。！？\\n]{0,6}(问|说|讲|告诉|提)");

    /**
     * 真越界的形态（豁免判据 B 的反面）：命中任何一条就<b>不</b>豁免，宁可换成求助引导。
     * 之所以要它，是因为「转介措辞」和「处方措辞」经常同框出现，只看前者会放水。
     */
    private static final Pattern PRESCRIPTION_ASSERTION = Pattern.compile(
            "(我|这边|屿屿)[^。！？\\n]{0,6}开(点|些|几)?药"
                    + "|建议[^。！？\\n]{0,6}(吃|服|加量|减量|停药)"
                    + "|(每(天|次)|一次)[^。！？\\n]{0,4}[0-9零一两二三四五六七八九十半]{1,3}\\s*"
                    + "(片|粒|颗|毫克|mg)"
                    + "|剂量(改成|调成|加到|减到|减半)"
                    + "|你(这|可能|大概|应该)?(是|得了|患有|属于)[^。！？\\n]{0,10}(症|障碍|病)"
                    + "|(不用|不需要|不必)(吃药|服药|复诊|就医)");

    private final SensitiveWordEngine wordEngine;
    private final MindisleProperties properties;
    private final CrisisVocabulary crisisVocabulary;
    private final List<Pattern> injectionPatterns;
    private final List<String> br8Terms;
    private final String br8Version;

    public SafetyGuard(SensitiveWordEngine wordEngine, MindisleProperties properties,
                       CrisisVocabulary crisisVocabulary) {
        this.wordEngine = wordEngine;
        this.properties = properties;
        this.crisisVocabulary = crisisVocabulary;
        List<Pattern> compiled = new ArrayList<>(INJECTION_PATTERNS.length);
        for (String[] row : INJECTION_PATTERNS) {
            compiled.add(Pattern.compile(row[1]));
        }
        this.injectionPatterns = Collections.unmodifiableList(compiled);
        List<String> terms = new ArrayList<>(32);
        String version = "br8-unknown";
        try (InputStream in = new ClassPathResource(BR8_RESOURCE).getInputStream()) {
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            int lineNo = 0;
            for (String line : raw.split("\n")) {
                lineNo++;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("#")) {
                    if (trimmed.startsWith("#version=")) {
                        version = trimmed.substring("#version=".length()).trim();
                    }
                    continue;
                }
                String[] cols = trimmed.split("\t");
                if (cols.length != BR8_COLUMNS) {
                    throw new IllegalStateException(BR8_RESOURCE + " 第 " + lineNo
                            + " 行应为 " + BR8_COLUMNS + " 列（TAB 分隔），实际 " + cols.length + " 列");
                }
                if (!BR8_ACTION.equals(cols[2].trim())) {
                    throw new IllegalStateException(BR8_RESOURCE + " 第 " + lineNo
                            + " 行处置只支持 " + BR8_ACTION + "，实际 " + cols[2]);
                }
                String term = cols[1].trim();
                if (!term.isEmpty()) {
                    terms.add(term.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("BR8 词表读取失败：" + BR8_RESOURCE, e);
        }
        if (terms.isEmpty()) {
            throw new IllegalStateException("BR8 词表为空：" + BR8_RESOURCE);
        }
        this.br8Terms = Collections.unmodifiableList(terms);
        this.br8Version = version;
        log.info("已装载 BR8 医疗越界词表 version={} 共 {} 条；注入检测模式 {} 条", version, terms.size(),
                injectionPatterns.size());
    }

    /**
     * 输入侧清洗结果。
     *
     * @param text              清洗并截断后的文本（可能为空串，调用方必须判空并回参数错误）
     * @param injectionSuspected 疑似提示词注入，只用于加提醒与日志
     * @param reason            命中的模式 label，无命中为 null
     */
    public record Input(String text, boolean injectionSuspected, String reason) {
    }

    /**
     * 输出侧闸结果。
     *
     * @param text    要么原样，要么整条替换文案
     * @param rewrote 是否发生过替换（进 SSE done 事件与 chat_message 的判定日志）
     * @param reason  处置标签：{@code rewrote=true} 时是替换原因；{@code rewrote=false} 时
     *                可能是「命中但被豁免」的标注（如 {@code medical-referral:开药}）；
     *                完全没触发任何判据时为 null
     */
    public record Output(String text, boolean rewrote, String reason) {
    }

    /**
     * 进模型前的清洗：控制字符与零宽字符、换行统一、码点截断、注入标记。
     *
     * <p>零宽字符必须先删再判敏感词与注入：「不&#8203;想&#8203;活」用零宽断开就能绕过所有
     * 词面匹配，而它对模型同样只是一句正常的话 —— 于是攻击面与危机信号会同时消失。
     * 同理，emoji 变体选择符 U+FE0F 保留（它参与情绪词典里的 emoji 匹配）。</p>
     */
    public Input sanitizeUser(String raw) {
        if (raw == null) {
            return new Input(EMPTY_MARK, false, null);
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\t') {
                sb.append(c);
                continue;
            }
            if (c == '\r') {
                continue;
            }
            if (c < 0x20 || c == 0x7F) {
                continue;
            }
            // 零宽与双向控制字符：BOM、ZWSP/ZWNJ/ZWJ、 word joiner、RLO/LRO 等
            if (c == 0xFEFF || (c >= 0x200B && c <= 0x200F) || (c >= 0x202A && c <= 0x202E)
                    || c == 0x2060 || c == 0x180E) {
                continue;
            }
            sb.append(c);
        }
        String cleaned = trimCollapse(sb.toString());
        cleaned = cut(cleaned, MAX_INPUT_CHARS);
        String reason = injectionReason(cleaned);
        if (reason != null) {
            log.info("检测到疑似提示词注入（模式 {}），已标记；用户原文仍按原样送模型", reason);
        }
        return new Input(cleaned, reason != null, reason);
    }

    /**
     * 出模型后的闸。三条判据按「最该拦的排前面」，命中即整条替换。
     *
     * @param rawAiText 模型原始输出（已流给用户的那段文字，替换只影响<b>落库与后续展示</b>，
     *                  已经推出去的 SSE 分片无法收回 —— 所以 ChatService 必须在
     *                  onDelta 之前先做「攒够一句再过闸」或接受「事后修正」，本类不管这个取舍）
     * @param riskLevel 本轮判定等级 L0/L1/L2/L3，只在 L2/L3 时启用「复述方法」检查
     *
     * <p><b>「医疗越界词 BLOCK」之外还有专业转介语境豁免，见 {@link #isMedicalReferral}：
     * 模型把判断权交回医生的那句话，正是需求 BR8 想要的回答，不能当成越界话术换掉。</b></p>
     */
    public Output guardAiOutput(String rawAiText, String riskLevel) {
        String text = rawAiText == null ? "" : trimCollapse(rawAiText);
        if (text.isEmpty()) {
            return new Output("", false, null);
        }
        SensitiveWordEngine.CheckResult check = wordEngine.check(text, "ai");
        if (check.hit() && check.hasAction("BLOCK")) {
            String blockedWords = blockHitsLabel(check);
            if (isMedicalReferral(text, check, riskLevel)) {
                // 豁免不等于放行不管：词面与 reason 仍然进日志，管理端要能回答「为什么这条没被换」
                log.info("AI 输出命中「{}」但属专业转介语境，按 BR8 保留原文：命中词={} 原文长={}",
                        MEDICAL_CATEGORY, blockedWords, text.length());
                return new Output(text, false, "medical-referral:" + blockedWords);
            }
            log.warn("AI 输出命中敏感词 BLOCK，已替换回复：主因 {} 命中 {} 条 词面={}",
                    check.category(), check.hitCount(), blockedWords);
            return new Output(REWRITE_BLOCKED, true, "sensitive-BLOCK:" + check.category() + "/" + blockedWords);
        }
        String medical = firstBr8Term(text);
        if (medical != null) {
            log.warn("AI 输出命中 BR8 医疗越界词「{}」，已替换为求助引导", medical);
            return new Output(REWRITE_MEDICAL, true, "br8-medical:" + medical);
        }
        // 只有危机轮次才查「有没有复述方法」：平时模型提到「自伤」这类词是正当的接话，
        // 一律改写会让危机对话变成一句套话，正是需求 §5.2 要避免的「留白」。
        String methodWord = crisisVocabulary.firstMethodWord(text);
        if (isCrisis(riskLevel) && methodWord != null) {
            log.warn("危机轮次（{}）的 AI 输出复述了方法词「{}」，已替换为安全应答", riskLevel, methodWord);
            return new Output(crisisEchoText(), true, "crisis-method:" + methodWord);
        }
        return new Output(text, false, null);
    }

    /** 命中的 BLOCK 词面（去重、最多列 3 个），只进日志与 reason，不给用户看。 */
    private static String blockHitsLabel(SensitiveWordEngine.CheckResult check) {
        List<String> words = new ArrayList<>();
        for (SensitiveWordEngine.Hit h : check.hits()) {
            if ("BLOCK".equals(h.action()) && !words.contains(h.word())) {
                words.add(h.word());
            }
        }
        if (words.isEmpty()) {
            return check.category();
        }
        return words.size() > 3 ? String.join("、", words.subList(0, 3)) + "…" : String.join("、", words);
    }

    /**
     * 医疗越界分组的「专业转介豁免」判据（三条同时成立才豁免，缺一即维持整条替换）：
     * <ol>
     *   <li>本轮 BLOCK 命中<b>全部</b>属于 {@link #MEDICAL_CATEGORY}——混进任何别的分组（辱骂、
     *       违法、风险词…）说明问题不在「措辞提到医疗」，不谈豁免；</li>
     *   <li>句中确有 {@link #REFERRAL_CONTEXT} 的转介措辞，且不含 {@link #PRESCRIPTION_ASSERTION}
     *       的处方/诊断断言，也没有命中 BR8 输出侧词表（那张表本身就是精确判据）；</li>
     *   <li>非危机轮次；L2/L3 轮只要复述了方法类词面就一律不豁免——危机回复的容错空间最小。</li>
     * </ol>
     */
    private boolean isMedicalReferral(String text, SensitiveWordEngine.CheckResult check, String riskLevel) {
        int medicalBlockHits = 0;
        for (SensitiveWordEngine.Hit h : check.hits()) {
            if (!"BLOCK".equals(h.action())) {
                continue;
            }
            if (!MEDICAL_CATEGORY.equals(h.category())) {
                return false;
            }
            medicalBlockHits++;
        }
        if (medicalBlockHits == 0 || firstBr8Term(text) != null) {
            return false;
        }
        if (isCrisis(riskLevel) && crisisVocabulary.firstMethodWord(text) != null) {
            return false;
        }
        return REFERRAL_CONTEXT.matcher(text).find() && !PRESCRIPTION_ASSERTION.matcher(text).find();
    }

    /**
     * 危机应答文案（带热线号码，热线来自配置不写死，需求 BR8 与 FR10.4）。
     *
     * <p>公开它只为一件事：{@code ChatService} 在模型不可用时也要接住危机轮次，
     * 而那一刻能说的、被审过的话就是这一句。抄第二份同义文案进 ChatService，
     * 等于让「危机应答」出现两个版本 —— 改热线、改措辞时必有一个被漏掉。</p>
     */
    public String crisisSafetyText() {
        return crisisEchoText();
    }

    /** 危机应答替换文案（带热线号码，热线来自配置不写死，需求 BR8 与 FR10.4）。 */
    private String crisisEchoText() {
        return REWRITE_CRISIS_ECHO.replace("{{hotline}}", properties.getCrisis().getHotline());
    }

    /** L2/L3 才算危机轮次；L1 是「模型觉得不太对」的观察档，不动输出。 */
    private static boolean isCrisis(String level) {
        return "L2".equals(level) || "L3".equals(level);
    }

    /** 命中的第一个 BR8 词条（小写比较，PTSD 与 ptsd 同判）；没命中返回 null。 */
    public String firstBr8Term(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String term : br8Terms) {
            if (lower.contains(term)) {
                return term;
            }
        }
        return null;
    }

    /** 命中的第一个注入模式 label；没命中返回 null。 */
    public String injectionReason(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (int i = 0; i < injectionPatterns.size(); i++) {
            Matcher m = injectionPatterns.get(i).matcher(text);
            if (m.find()) {
                return INJECTION_PATTERNS[i][0];
            }
        }
        return null;
    }

    /** BR8 词表版本，进 ai_call_log 与日志，论文里「输出侧闸用的是什么表」要能回答。 */
    public String br8Version() {
        return br8Version;
    }

    /** BR8 词条数，管理端与自检用。 */
    public int br8TermCount() {
        return br8Terms.size();
    }

    /** 连续空白压成一个空格，首尾去掉；换行保留（模型爱用换行分段，压平会让语气变差）。 */
    private static String trimCollapse(String text) {
        String collapsed = text.replaceAll("[ \\t\\u3000]+", " ").replaceAll(" +\n", "\n");
        return collapsed.trim();
    }

    /** 按<b>码点</b>截断（与 NotifyService.cut 同口径：一个 emoji 是一个码点两个 char）。 */
    static String cut(String text, int maxCodePoints) {
        if (text == null) {
            return "";
        }
        int chars = text.codePointCount(0, text.length());
        if (chars <= maxCodePoints) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxCodePoints);
        return text.substring(0, end);
    }
}

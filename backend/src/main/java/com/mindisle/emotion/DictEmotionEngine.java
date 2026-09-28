package com.mindisle.emotion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mindisle.audit.TextNormalizer;
import com.mindisle.emotion.EmotionLexicon.Entry;
import com.mindisle.emotion.EmotionLexicon.Kind;
import com.mindisle.emotion.EmotionLexicon.Match;

/**
 * 词典情绪识别引擎（任务 4.7 · 手册 §7.2 五级流水 · 需求 §8.1.2 · 创新点 1 的 A 级实现）。
 *
 * <p><b>流水线与手册 §7.2 的对应关系</b>：
 * ① 预处理 → {@link #prepare}（复用审核侧的 {@link TextNormalizer} 做全半角/繁简/同形字折叠，
 * emoji 因为会被折叠器删掉，改在原文分句上单独扫）；
 * ② 词典匹配 → {@link #score}（FMM 切出 DUT 词条，主情感全权重、辅助情感半权重、非核心义项 0.6）；
 * ③ 修正 → 程度副词乘子、否定翻转、转折取后句、感叹/问号升强度，全在 {@link #score} 一遍完成；
 * ④ 置信度 → 见下；⑤ 输出 → {@link Analysis}，调用方负责写 {@code emotion_record}。</p>
 *
 * <p><b>置信度公式（对 §7.2 ④「覆盖度 × 一致率」的落地细化，论文 4.5 要照这段写）</b>：
 * <pre>
 * coverage  = min(1, 命中词面总字数 / clamp(内容字数, denominatorMinChars, saturatedCoverageChars))
 * agreement = 胜出类别权重 / 全部类别权重之和
 * conf      = min(confCeiling, agreement * (0.35 + 0.65 * coverage))
 * </pre>
 * 三个细节都有理由：其一，coverage 的分子<b>计入程度副词与否定词</b>，因为它们是同一个词典里的词，
 * 「我很开心」四个字被解释了三个，本来就该算高覆盖；其二，0.35 是下限项，
 * 保证「长句里只切到一个情绪词」时 conf 不会被覆盖度直接压到 0 后还要送去兜底——
 * 那个场景真正缺的是一致率信息，不是覆盖度；其三，{@code confCeiling}（默认 0.95）
 * 刻意让词典通道永远不给满分：多义词消歧只用了义项序号，没有句法语义，
 * 声称 1.0 置信度的系统不配让下游跳过 LLM 复核。</p>
 *
 * <p><b>已知失手（不是 bug，是论文要写清的边界）</b>：
 * 反讽（「可真是个好结果呢」）、网络缩写（「xswl」「yyds」不在 DUT 4.0 里）、
 * 以及 DUT 里 2045 个跨类多义词中义项序号相同的那一部分，词典通道判不对。
 * 这些正是 T4.8 把 conf&lt;0.55 交给 LLM 的理由，也是实验组「纯词典 vs 级联」的差值来源。</p>
 *
 * <p><b>线程安全</b>：加载后只读，词典与先验都是不可变的，实例可以并发共享。</p>
 */
public final class DictEmotionEngine {

    /** 词典通道的通道名，与 emotion_record.channel / chat_message.emotion_channel 的 ENUM 逐字一致。 */
    public static final String CHANNEL = "dict";

    /**
     * 否定之后标签怎么走。
     *
     * <p>「不开心」不是「喜悦的反面类别」里某个词，它落在难过；
     * 负面情绪被否定（「不难过」）更接近平静而不是另一种负面情绪。
     * 这套重映射是<b>本项目自定</b>的取舍（DUT 只给词义，不给否定后的语义），
     * 论文 4.4 与 {@link #analyze} 的 javadoc 各写一次，不许只留代码。</p>
     */
    static final Map<String, String> NEGATION_REMAP = Map.of(
            "joy", "sadness",
            "trust", "neutral",
            "neutral", "neutral",
            "sadness", "neutral",
            "fear", "neutral",
            "anger", "neutral",
            "disgust", "neutral");

    /** 否定后的强度衰减：情绪没有完全抵消，只是弱一档。 */
    static final double NEGATED_INTENSITY = 0.8;

    /** 单个词典命中的可追溯记录，前端角标 tooltip 与周报词云都用它。 */
    public record Hit(String word, Kind kind, String label, int intensity, double weight, boolean negated,
                      boolean aux, String dutCode) {
    }

    /**
     * 分析结论。
     *
     * @param label      七类之一（emotion_record.label 的取值域）
     * @param intensity  1-5
     * @param valence    -1/0/1
     * @param confidence 0-1
     * @param needsLlm   是否该送 LLM 兜底（conf 低于阈值，或文本命中风险词由调用方另行并入）
     * @param modelVersion 词典版本 + 先验版本，写进 emotion_record.model_version
     * @param codeVotes  DUT 21 小类 → 累计权重（论文做细粒度分析用，不落库）
     */
    public record Analysis(String label, int intensity, int valence, double confidence, boolean needsLlm,
                           String modelVersion, List<Hit> hits, Map<String, Double> codeVotes, int contentChars) {

        /** 词典完全没切中：既没类别也没强度，前端角标不该显示「平静」。 */
        public boolean empty() {
            return hits.isEmpty();
        }

        /** 逗号分隔的命中词面，给 emotion_record.text_snippet 与周报词云。 */
        public String hitWords(int maxChars) {
            StringBuilder sb = new StringBuilder();
            for (Hit hit : hits) {
                if (hit.kind() != Kind.EMOTION && hit.kind() != Kind.EMOJI) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('，');
                }
                sb.append(hit.word());
            }
            if (sb.length() > maxChars) {
                return sb.substring(0, maxChars);
            }
            return sb.toString();
        }
    }

    private final EmotionLexicon lexicon;
    private final EmotionPrior prior;

    public DictEmotionEngine(EmotionLexicon lexicon, EmotionPrior prior) {
        this.lexicon = lexicon;
        this.prior = prior;
    }

    /** 词典只读访问，供 T4.8 LLM 兜底拼提示词时引用词面，以及单测断言版本串。 */
    public EmotionLexicon lexicon() {
        return lexicon;
    }

    public EmotionPrior prior() {
        return prior;
    }

    /** 版本串，落 emotion_record.model_version：形如 {@code dut-4.0-mindisle-v1+prior-v1.0}。 */
    public String modelVersion() {
        return lexicon.dutVersion() + "+" + prior.version();
    }

    // ================================================================== ① 预处理

    /** 一个句子片段：折叠串 + 该片段在原文里的边界 + 句末标点 + 片段内 emoji 命中。 */
    private record Clause(String folded, int rawStart, int rawEnd, char tailPunct, List<Entry> emojis) {
    }

    /**
     * 分句 + 折叠 + 摘出 emoji。
     *
     * <p>分句在<b>原文</b>上做（标点会被折叠器删掉，删完就没有边界了）；
     * 词匹配在<b>折叠串</b>上做（繁体、全角、插分隔符的写法要能吃到同一个词典）；
     * emoji 在<b>原文片段</b>上按码位扫（它是唯一「删除即丢信号」的一类）。</p>
     */
    private List<Clause> prepare(String text) {
        List<Clause> clauses = new ArrayList<>(4);
        int length = text.length();
        int start = 0;
        for (int i = 0; i <= length; i++) {
            boolean atEnd = i == length;
            int cp = atEnd ? -1 : text.codePointAt(i);
            if (!atEnd) {
                i += Character.charCount(cp) - 1;
            }
            boolean breakHere = atEnd || isClauseBreak(cp);
            if (!breakHere) {
                continue;
            }
            String segment = text.substring(start, i == length ? length : i - (Character.charCount(cp) - 1));
            addClause(clauses, text, segment, start, atEnd, cp);
            start = i == length ? length : i + Character.charCount(cp);
        }
        return clauses;
    }

    private void addClause(List<Clause> clauses, String text, String segment, int segmentStart, boolean atEnd, int breakCp) {
        if (segment.isEmpty()) {
            return;
        }
        StringBuilder folded = new StringBuilder(segment.length());
        int cursor = 0;
        while (cursor < segment.length()) {
            int cp = segment.codePointAt(cursor);
            cursor += Character.charCount(cp);
            int fold = TextNormalizer.fold(cp);
            if (TextNormalizer.isIgnored(fold)) {
                continue;   // 空白、零宽、装饰性标点、emoji 全部由折叠器负责判定
            }
            folded.appendCodePoint(fold);
        }
        // 句末标点用折叠后的 ASCII 形态判（全角 ！ 折成 !）
        char tail = breakCp < 0 ? ' ' : (char) TextNormalizer.fold(breakCp);
        List<Entry> emojis = scanEmoji(segment);
        if (folded.length() == 0 && emojis.isEmpty()) {
            return;
        }
        clauses.add(new Clause(folded.toString(), segmentStart, segmentStart + segment.length(), tail, emojis));
    }

    /** 常见中英文句子分隔符。刻意不含顿号以外的符号，宁少勿多：切碎了会丢程度副词与情绪词的邻接关系。 */
    static boolean isClauseBreak(int cp) {
        return cp == 0xFF0C || cp == ',' || cp == 0x3002 || cp == 0x3001 || cp == 0xFF01 || cp == '!'
                || cp == 0xFF1F || cp == '?' || cp == 0xFF1B || cp == ';' || cp == 0x300A || cp == 0x300B
                || cp == '\n' || cp == '\r' || cp == 0xFF1A || cp == ':' || cp == 0x2014 || cp == 0x2026
                || cp == ')' || cp == '(' || cp == 0x3010 || cp == 0x3011;
    }

    /** 在原始片段上按码位扫 emoji（词典里的 emoji 可能是 1~2 个码位，逐个尝试匹配）。 */
    private List<Entry> scanEmoji(String segment) {
        List<Entry> out = new ArrayList<>(2);
        int cursor = 0;
        while (cursor < segment.length()) {
            int cp = segment.codePointAt(cursor);
            int width = Character.charCount(cp);
            if (isEmojiLike(cp)) {
                Entry best = longestEmojiEntry(segment, cursor);
                if (best != null) {
                    out.add(best);
                    cursor += Math.max(width, best.word().length());
                    continue;
                }
            }
            cursor += width;
        }
        return out;
    }

    private Entry longestEmojiEntry(String segment, int from) {
        int max = Math.min(segment.length() - from, lexicon.maxWordLength());
        List<Entry> found = null;
        for (int len = max; len >= 1; len--) {
            List<Entry> candidates = emojiLookup(segment.substring(from, from + len));
            if (candidates != null) {
                found = candidates;
                break;
            }
        }
        if (found == null) {
            return null;
        }
        for (Entry entry : found) {
            if (entry.kind() == Kind.EMOJI) {
                return entry;
            }
        }
        return null;
    }

    private List<Entry> emojiLookup(String word) {
        Match probe = null;
        for (Match match : lexicon.match(word)) {
            probe = match;
        }
        return probe != null && probe.end() == word.length() && probe.start() == 0 ? probe.entries() : null;
    }

    /** emoji 码位粗判：只认这些区段，别的符号不当情绪信号（一个「♥」够了，不必把「⚡」算成情绪）。 */
    static boolean isEmojiLike(int cp) {
        return (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x1F000 && cp <= 0x1F0FF)
                || cp == 0x2764 || cp == 0xFE0F;
    }

    // ================================================================== ②③④ 匹配、修正、置信度

    /**
     * 完整分析。
     *
     * @param text 原文，null/空白按「无信号」返回（needsLlm=true，由调用方决定要不要送兜底）
     */
    public Analysis analyze(String text) {
        return analyze(text, false);
    }

    /**
     * @param riskTouched 调用方（T4.11 危机双通道）已经查过敏感词引擎，命中风险组时传 true：
     *                    词典情绪结论本身没资格决定要不要复核，但风险信号必须把 needsLlm 顶成 true。
     */
    public Analysis analyze(String text, boolean riskTouched) {
        double ceiling = prior.coverageParam("confCeiling", 0.95);
        if (text == null || text.isBlank()) {
            return new Analysis("neutral", 1, 0, 0.0, true, modelVersion(), List.of(), Map.of(), 0);
        }
        List<Clause> clauses = prepare(text);
        Map<String, Double> votes = new LinkedHashMap<>();
        Map<String, Double> codeVotes = new LinkedHashMap<>();
        Map<String, Integer> intensityByLabel = new LinkedHashMap<>();
        List<Hit> hits = new ArrayList<>();
        int matchedChars = 0;   // 由各子句的 scoreClause 累加，不能在子句循环里重扫 hits
        int contentChars = 0;
        for (Clause clause : clauses) {
            contentChars += clause.folded().length();
        }
        // 转折重心：全篇最后一个转折词之后才是重心（「虽然很累，但是很开心」→ 开心）
        int pivot = contrastPivot(clauses);
        for (int ci = 0; ci < clauses.size(); ci++) {
            Clause clause = clauses.get(ci);
            matchedChars += scoreClause(clause, ci, pivot, votes, codeVotes, intensityByLabel, hits);
        }
        if (votes.isEmpty() && codeVotes.isEmpty()) {
            double conf = 0.0;
            return new Analysis("neutral", prior.anchorIntensity("neutral"), 0, conf,
                    true, modelVersion(), hits, codeVotes, contentChars);
        }
        // ② 类别投票 + 平票取负面优先
        String winner = pickWinner(votes);
        double total = votes.values().stream().mapToDouble(Double::doubleValue).sum();
        double agreement = total <= 0 ? 0 : votes.getOrDefault(winner, 0.0) / total;
        int saturated = (int) prior.coverageParam("saturatedCoverageChars", 24);
        int floorChars = (int) prior.coverageParam("denominatorMinChars", 8);
        // 分母取「内容字数」但两头都夹住：下限防止 3 个字的文本因为全命中而拿满分，
        // 上限防止长文本靠字数摊薄（超过 saturated 之后覆盖度不再增长，长句只看一致率）。
        int denominator = Math.max(1, Math.min(Math.max(contentChars, floorChars), saturated));
        double coverage = Math.min(1.0, (double) matchedChars / denominator);
        double confidence = Math.min(ceiling, agreement * (0.35 + 0.65 * coverage));
        int intensity = intensityByLabel.getOrDefault(winner, prior.anchorIntensity(winner));
        // 「问号 + 负面」升强度（§7.2 ③）：疑问句里的负面情绪更值得被当回事
        if (prior.valenceTier(winner) < 0 && hasQuestion(clauses)) {
            intensity = clampIntensity(intensity + 1);
        }
        double fallback = prior.threshold("llmFallbackConf", 0.55);
        boolean needsLlm = confidence < fallback || riskTouched || hits.isEmpty();
        Map<String, Double> frozenCodes = new LinkedHashMap<>();
        codeVotes.forEach((code, value) -> frozenCodes.put(code, round(value)));
        return new Analysis(winner, intensity, prior.valenceTier(winner), round(confidence), needsLlm,
                modelVersion(), List.copyOf(hits), Map.copyOf(frozenCodes), contentChars);
    }

    private static boolean hasQuestion(List<Clause> clauses) {
        for (Clause clause : clauses) {
            if (clause.tailPunct() == '?') {
                return true;
            }
        }
        return false;
    }

    /** 返回全篇最后一个转折词所在的子句下标；-1 表示没有转折。该下标之前的子句降权。 */
    private int contrastPivot(List<Clause> clauses) {
        int pivot = -1;
        for (int i = 0; i < clauses.size(); i++) {
            List<Match> matches = lexicon.match(clauses.get(i).folded());
            for (Match match : matches) {
                for (Entry entry : match.entries()) {
                    if (entry.kind() == Kind.CONTRAST) {
                        pivot = i;
                    }
                }
            }
        }
        return pivot;
    }

    /**
     * 给一个子句打分，返回该子句里「被词典解释掉的字数」。
     *
     * <p>覆盖度分子<b>同时计入情绪词面与程度副词、否定词面</b>：它们来自同一批词典资源，
     * 「我很开心」四个字被解释了三个，本来就比「我心情不错」这种没有词典词的写法更可信。</p>
     */
    private int scoreClause(Clause clause, int clauseIndex, int pivot, Map<String, Double> votes,
                            Map<String, Double> codeVotes, Map<String, Integer> intensityByLabel,
                            List<Hit> hits) {
        int covered = 0;
        String folded = clause.folded();
        List<Match> matches = lexicon.match(folded);
        double contrastWeight = pivot >= 0 && clauseIndex < pivot
                ? EmotionLexicon.BEFORE_CONTRAST_WEIGHT : 1.0;
        // 程度副词与否定词要看着情绪词的位置生效，所以先把非情绪命中按位置排好
        List<int[]> modifiers = new ArrayList<>(matches.size());
        List<Entry> modifierEntries = new ArrayList<>(matches.size());
        for (Match match : matches) {
            for (Entry entry : match.entries()) {
                if (entry.kind() == Kind.DEGREE || entry.kind() == Kind.NEGATION) {
                    modifiers.add(new int[] {match.start(), match.end()});
                    modifierEntries.add(entry);
                    covered += match.end() - match.start();
                }
            }
        }
        for (Match match : matches) {
            List<Entry> emotions = EmotionLexicon.emotionsOf(match.entries());
            if (emotions.isEmpty()) {
                continue;
            }
            // 覆盖度按「匹配到的词面区间」计一次，多义词的多个义项共用同一段字数，不能重复计
            covered += match.end() - match.start();
            // ③ 修正之一：否定。只往前看邻接链，因为中文否定词一定在情绪词前面
            boolean negated = negatedBefore(match.start(), modifiers, modifierEntries);
            // ③ 修正之二：程度副词。前置（非常难过）与后置（难过死了）都要求紧邻
            double multiplier = degreeMultiplier(match, modifiers, modifierEntries);
            // 感叹号连发：「难过死了！！」的 !! 已经在分句时被当边界，这里用尾部标点判
            // 感叹号：1.15 倍在 1-5 的整数刻度上几乎从不跨档（3*1.15=3.45 还是 3），等于没生效；
            // 取 1.3 才真的能把「难过！」推到下一档，且与前置程度副词互斥（有程度词时听程度词的）。
            if (clause.tailPunct() == '!' && multiplier == 1.0) {
                multiplier = prior.threshold("exclamationMultiplier", 1.3);
            }
            String effectiveLabel = negated ? NEGATION_REMAP.getOrDefault(emotions.get(0).label(), "neutral")
                    : emotions.get(0).label();
            for (Entry entry : emotions) {
                double weight = (entry.aux() ? prior.weight("auxRole", 0.5) : prior.weight("sensePrimary", 1.0))
                        * contrastWeight;
                if (weight <= 0) {
                    continue;
                }
                String label = negated ? NEGATION_REMAP.getOrDefault(entry.label(), "neutral") : entry.label();
                votes.merge(label, weight, Double::sum);
                codeVotes.merge(entry.dutCode(), weight, Double::sum);
                int intensity = clampIntensity((int) Math.round(entry.intensity() * multiplier *
                        (negated ? NEGATED_INTENSITY : 1.0)));
                // 同一类别被多个词命中时取更强的那个：情绪强度不该被「还有一个更轻的说法」拉低
                intensityByLabel.merge(label, intensity, Math::max);
                hits.add(new Hit(entry.word(), Kind.EMOTION, label, intensity, weight, negated, entry.aux(),
                        entry.dutCode()));
            }
        }
        // emoji：没有分词也能吃到信号，但权重按 emojiHit 打折（它比词面更容易被随手发）
        for (Entry emoji : clause.emojis()) {
            double weight = prior.weight("emojiHit", 0.7) * contrastWeight;
            String label = emoji.label();
            votes.merge(label, weight, Double::sum);
            intensityByLabel.merge(label, clampIntensity(emoji.intensity()), Math::max);
            hits.add(new Hit(emoji.word(), Kind.EMOJI, label, clampIntensity(emoji.intensity()), weight,
                    false, false, "EMO"));
            covered += emoji.word().length();
        }
        return covered;
    }

    /**
     * 只按情绪/emoji 命中算词面字数，供单测交叉验证 {@link Analysis#hits()}。
     *
     * <p>注意它<b>不是</b>引擎内部覆盖度用的那个数：引擎还会把程度副词与否定词的字数计进分子
     * （见 {@link #scoreClause}），所以这个方法的返回值只会小于等于内部值。</p>
     */
    static int matchedCharsOf(List<Hit> hits) {
        int sum = 0;
        for (Hit hit : hits) {
            if (hit.kind() == Kind.EMOTION || hit.kind() == Kind.EMOJI) {
                sum += hit.word().length();
            }
        }
        return sum;
    }

    /**
     * 这个情绪词是否被否定：<b>沿修饰链往回走</b>，链上出现否定词即判否定。
     *
     * <p>旧口径是「4 字窗口内出现否定词就算」，实测会在「看到别人被表扬」上误判——
     * 分词把 <b>别</b> 单独切出来当否定词，离 <b>表扬</b> 只隔 2 个字，于是表扬被读成负向。
     * 现在的口径改成邻接链：情绪词前面必须紧贴一个否定词或程度词，
     * 程度词前面再紧贴否定词，链可以穿过程度副词但每步最多容许 1 个未匹配字
     * （「并不是很开心」里 很 与 开心 之间没有空隙，「不开心」也没有；
     * 「别人被表扬」里 别 和 表扬 之间隔着 人被，链断了）。
     * 这一步是把否定通道从「窗口召回」换成「结构精度」，是本轮实测数据逼出来的改动，
     * 论文 4.4 与 dev-log 各写一次。</p>
     */
    private boolean negatedBefore(int emotionStart, List<int[]> spans, List<Entry> entries) {
        int gapTolerance = (int) prior.threshold("negationGapChars", 1);
        int chainLimit = (int) prior.threshold("negationChainSteps", 4);
        int position = emotionStart;
        for (int step = 0; step < chainLimit; step++) {
            int found = -1;
            for (int i = 0; i < spans.size(); i++) {
                int[] span = spans.get(i);
                Entry entry = entries.get(i);
                if (entry.kind() != Kind.NEGATION && entry.kind() != Kind.DEGREE) {
                    continue;
                }
                if (span[1] <= position && position - span[1] <= gapTolerance) {
                    if (found < 0 || spans.get(found)[1] > span[1]) {
                        found = i;
                    }
                }
            }
            if (found < 0) {
                return false;
            }
            if (entries.get(found).kind() == Kind.NEGATION) {
                return true;
            }
            position = spans.get(found)[0];
        }
        return false;
    }

    /**
     * 程度副词乘子：只认<b>紧邻</b>的情绪词修饰语（前置 1 字容错、后置 0 字容错）。
     *
     * <p>多个程度词同时命中时取「偏离 1 最远」的那个而不是连乘：
     * 「非常极其难过」不等于 1.5×1.8 倍，那是把两个同义强化当成两级强化。</p>
     */
    private double degreeMultiplier(Match emotion, List<int[]> spans, List<Entry> entries) {
        int gapBefore = (int) prior.threshold("degreeGapBeforeChars", 1);
        double best = 1.0;
        for (int i = 0; i < spans.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.kind() != Kind.DEGREE || entry.multiplier() == 1.0) {
                continue;
            }
            int[] span = spans.get(i);
            boolean before = span[1] <= emotion.start() && emotion.start() - span[1] <= gapBefore;
            boolean after = span[0] >= emotion.end() && span[0] - emotion.end() == 0;
            if (before || after) {
                // 多个程度词叠加时取最强的那个，而不是连乘：「非常特别极其难过」不该等于九倍
                best = entry.multiplier() > 1.0 ? Math.max(best, entry.multiplier())
                        : Math.min(best == 1.0 ? entry.multiplier() : best, entry.multiplier());
            }
        }
        return best;
    }

    private String pickWinner(Map<String, Double> votes) {
        double max = 0;
        List<String> tied = new ArrayList<>(3);
        for (Map.Entry<String, Double> entry : votes.entrySet()) {
            double value = entry.getValue();
            if (value > max + 1e-9) {
                max = value;
                tied.clear();
                tied.add(entry.getKey());
            } else if (Math.abs(value - max) <= 1e-9) {
                tied.add(entry.getKey());
            }
        }
        if (tied.isEmpty()) {
            return "neutral";
        }
        return tied.size() == 1 ? tied.get(0) : prior.pickOnTie(tied);
    }

    private static int clampIntensity(int value) {
        return Math.max(1, Math.min(5, value));
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    /** 归一化后的小写标签校验，供 LLM 兜底回来的 JSON 用（模型很爱多打空格与大小写）。 */
    public static String normalizeLabel(String raw, String dflt) {
        if (raw == null) {
            return dflt;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return EmotionPrior.LABELS.contains(value) ? value : dflt;
    }
}

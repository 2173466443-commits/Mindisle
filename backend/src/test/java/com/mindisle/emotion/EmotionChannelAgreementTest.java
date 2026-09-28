package com.mindisle.emotion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.emotion.DictEmotionEngine.Analysis;

/**
 * T4.15（后半）· 情绪三通道在<b>线上真实数据</b>上的占比与一致率（手册 §7.2 / §7.3、需求 FR3.2、AR-Emotion）。
 *
 * <p><b>这条测试存在的理由，是为了替代一个更诱人的假做法</b>：§7.3 原本要「自建 300 条标注集 + 第二人
 * 独立标注 + Cohen&#39;s Kappa」，那是论文阶段的工作量。如果现在为了填那一格而造一批「词典说了算」的样本、
 * 再让词典去测自己，数字会漂亮到 100%，而它一分钱证据都不值。所以这里<b>不新造任何标注集</b>：
 * 数据来自真库 emotion_record 的一份导出快照
 * （{@code src/test/resources/emotion/emotion_record_snapshot.tsv}，文件头写了导出 SQL、时间与清洗口径），
 * 测的是「线上三条通道各占多少」与「把词典通道重新跑在同一段文本上，结果和当时落库的那个标签差多远」。</p>
 *
 * <p><b>两个必须分开的口径（这才是本文件的价值所在）</b>：
 * ① {@code channel=dict} 的行，标签本来就是词典写的 —— 拿它算「一致率」是自比，天然偏高，所以单列一档并写明；
 * 真正有外部参照的是 {@code channel=llm}（模型说的）与 {@code channel=manual}（用户自己选的，最接近自评标注）。
 * ② 词典<b>零命中</b>时按设计返回 {@code neutral}（{@code conf=0}、{@code needsLlm=true}）。如果库里那条恰好
 * 也是 neutral，字符串比对会算成「一致」，那是<b>两个空值撞在一起</b>，不是识别对了。所以本文件同时给
 * 「原始一致率」和「剔除词典零命中之后的有效一致率」，并把撞车条数原样打出来。</p>
 *
 * <p><b>产物</b>：{@code 论文材料/experiments/output/emotion_channel_agreement.txt}，与
 * {@code emotion_latency.txt}（万条压测）配套：那份给的是<b>合成语料</b>上的兜底比例，
 * 这份给的是<b>线上真实文本</b>上的兜底比例，两份必须放在一起读，单独引用任何一份都会读歪。</p>
 */
@DisplayName("T4.15 情绪通道占比与一致率（真库快照，不新造标注集）")
class EmotionChannelAgreementTest {

    /** 快照至少要 100 行才配叫「线上现状」：几十行的时候占比会被单个用户带跑。 */
    private static final int MIN_ROWS = 100;

    /** 一致率的分母下限：可比样本太少的话，报出来的百分比只是噪声，直接判红而不是硬凑。 */
    private static final int MIN_COMPARABLE = 60;

    /**
     * <b>总体有效一致率的下限（含 dict 自比）</b>。首次运行的真实读数：33/73 = 45.21%。
     * 这个分母里有 20 条 dict 通道属于自比（词典重跑自己当初写下的标签，天然 100%），所以它只能当
     * 「快照与词典版本没串位、统计口径没崩」的守恒线看，<b>不能当准确率被引用</b>；
     * 要引用就用下面的外部一致率。跌破 40% 说明连自比档都在掉，先核对快照来源。
     */
    private static final double EFFECTIVE_AGREEMENT_FLOOR_PCT = 40.00D;

    /**
     * <b>外部一致率的下限 —— 这份报告真正该被引用的那一个</b>：只算 llm 与 manual 两个通道，
     * 再扣掉「词典零命中而库里恰好也是 neutral」的空值撞车。首跑真实读数 = 13/53 = 24.53%
     * （llm 11/40 = 27.50%，manual 2/13 = 15.38%，撞车 0 条）。
     *
     * <p>为什么红线定在 20% 而不是 25% 或更高：7 类标签、单条短句、参照物本身是另一个模型，
     * 这个指标的天花板本来就不高（它不是准确率）。20% 要守的是「词典对线上真实文本还剩多少
     * 可对照的信号」这条底线，跌破它就没有资格把词典通道写进论文。<b>动这个数必须同时给出理由</b>，
     * 并把新读数记进 docs/gate/阶段4/ 与 docs/dev-log.md。</p>
     */
    private static final double EXTERNAL_AGREEMENT_FLOOR_PCT = 20.00D;

    /** 外部档的分母下限：现在实量 53 条撑得住；掉到 30 条以下就别报百分比了，那是噪声不是结论。 */
    private static final int MIN_EXTERNAL_COMPARABLE = 30;

    private static final String DICT_CHANNEL = "dict";
    private static final String NEUTRAL = "neutral";

    private static DictEmotionEngine engine;
    private static List<Row> rows;

    @BeforeAll
    static void setUp() {
        engine = new DictEmotionEngine(EmotionLexicon.load(), EmotionPrior.load(new ObjectMapper()));
        rows = loadSnapshot();
    }

    /** 一条 emotion_record。{@code snip} 是当时的文本片段（导出时已把 CR/LF/TAB 换成空格）。 */
    private record Row(long id, String channel, String label, double confidence,
                       String source, String snip, String modelVersion, String recordDate) {

        boolean comparable() {
            return snip != null && !snip.isBlank();
        }
    }
    /**
     * 读快照。<b>列数不齐就当场判失败</b>，而不是让下一行错位之后还若无其事地算占比：
     * 快照是 mysql -B 导出的制表符文件，列序在文件头注释里写着，这里逐字对齐。
     * 两种脏数据要区分开：真列数不足（split 会丢尾部空列）与文本里夹了换行（残片没有制表符）。
     */
    private static List<Row> loadSnapshot() {
        List<Row> out = new ArrayList<>();
        int bad = 0;
        String cols = null;
        try (InputStream in = EmotionChannelAgreementTest.class.getClassLoader()
                .getResourceAsStream("emotion/emotion_record_snapshot.tsv")) {
            assertTrue(in != null, "快照不在 classpath 上：emotion/emotion_record_snapshot.tsv");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.charAt(0) == 35) {
                    int mark = cols == null ? line.indexOf("列序 = ") : -1;
                    if (mark >= 0) {
                        cols = line.substring(mark + 5).replace("\\t", String.valueOf((char) 9));
                    }
                    continue;
                }
                String[] p = line.split("\t", -1);
                if (p.length != 9) {
                    bad++;
                    continue;
                }
                out.add(new Row(Long.parseLong(p[0]), p[1], p[2], Double.parseDouble(p[3]),
                        p[4], p[5], p[6], p[7]));
            }
        } catch (Exception e) {
            throw new IllegalStateException("读情绪快照失败：" + e, e);
        }
        if (cols != null) {
            assertEquals("id channel label confidence source snip model_version record_date deleted".replace(" ", String.valueOf((char) 9)),
                    cols, "文件头写的列序与解析器假定的列序必须一致，否则整份占比都是错位读出来的");
        }
        assertEquals(0, bad, "有 " + bad + " 行列数不等于 9：多半是文本里夹了制表符或换行，快照该重新导出");
        return out;
    }
    /**
     * 把词典重新跑一遍每条可比文本，然后按<b>口径分档</b>统计。分档比总数字重要：
     * 一个「总体一致率」会把「词典自比」和「空值撞车」都算进分子，那是这份报告最容易被读歪的地方。
     */
    @Test
    @DisplayName("线上快照：通道占比 + 词典原始/有效一致率 + 真实兜底比例")
    void channelShareAndAgreement() throws Exception {
        assertTrue(rows.size() >= MIN_ROWS,
                "快照只有 " + rows.size() + " 行，占比会被单个探针账号带跑，这份报告没有代表性");

        Map<String, Integer> byChannel = new LinkedHashMap<>();
        Map<String, Integer> blankByChannel = new LinkedHashMap<>();
        Map<String, Integer> storedLabels = new LinkedHashMap<>();
        Map<String, Integer> dictLabels = new LinkedHashMap<>();
        for (Row row : rows) {
            bump(byChannel, row.channel());
            bump(storedLabels, row.label());
            if (!row.comparable()) {
                bump(blankByChannel, row.channel());
            }
        }

        int comparable = 0;
        int agreeAll = 0;
        int zeroHitNeutral = 0;
        int zeroHit = 0;
        double confSum = 0D;
        int dictChannel = 0;
        int dictChannelAgree = 0;
        int llmChannel = 0;
        int llmChannelAgree = 0;
        int manualChannel = 0;
        int manualChannelAgree = 0;
        int needsLlm = 0;
        List<String> llmMismatchSamples = new ArrayList<>();

        for (Row row : rows) {
            if (!row.comparable()) {
                continue;
            }
            comparable++;
            Analysis a = engine.analyze(row.snip());
            String pred = a == null ? null : a.label();
            double conf = a == null ? 0D : a.confidence();
            boolean same = pred != null && pred.equals(row.label());
            bump(dictLabels, pred == null ? "(null)" : pred);
            if (same) {
                agreeAll++;
            }
            if (conf <= 0D) {
                zeroHit++;
                if (NEUTRAL.equals(pred) && NEUTRAL.equals(row.label())) {
                    zeroHitNeutral++;
                }
            }
            if (a != null && a.needsLlm()) {
                needsLlm++;
            }
            confSum += conf;
            switch (row.channel()) {
                case DICT_CHANNEL -> {
                    dictChannel++;
                    if (same) {
                        dictChannelAgree++;
                    }
                }
                case "llm" -> {
                    llmChannel++;
                    if (same) {
                        llmChannelAgree++;
                    } else if (llmMismatchSamples.size() < 5) {
                        llmMismatchSamples.add(row.id() + " 库=" + row.label() + " 词典=" + pred
                                + " conf=" + conf + " 文本=「" + row.snip() + "」");
                    }
                }
                case "manual" -> {
                    manualChannel++;
                    if (same) {
                        manualChannelAgree++;
                    }
                }
                default -> {
                }
            }
        }
        assertTrue(comparable >= MIN_COMPARABLE,
                "可比样本只有 " + comparable + " 条（快照里 " + (rows.size() - comparable)
                        + " 条没有文本），一致率的分母太小，报出来的是噪声不是结论");

        int external = llmChannel + manualChannel;
        int externalAgree = llmChannelAgree + manualChannelAgree;
        int zeroHitExternal = 0;
        for (Row row : rows) {
            if (!row.comparable() || DICT_CHANNEL.equals(row.channel())) {
                continue;
            }
            Analysis a = engine.analyze(row.snip());
            if (a != null && a.confidence() <= 0D && NEUTRAL.equals(row.label())) {
                zeroHitExternal++;
            }
        }
        List<String> report = new ArrayList<>();
        report.add("情绪通道占比与一致率 · 线上真实快照（任务 T4.15 后半 / 手册 §7.2 §7.3 / 需求 FR3.2、AR-Emotion）");
        report.add("生成时间 = " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        report.add("来源测试 = backend/src/test/java/com/mindisle/emotion/EmotionChannelAgreementTest.java");
        report.add("数据来源 = backend/src/test/resources/emotion/emotion_record_snapshot.tsv（真库 emotion_record 的导出快照，文件头有导出 SQL 与时间）");
        report.add("JDK = " + System.getProperty("java.version") + " ; 词典 = " + engine.modelVersion());
        report.add("");
        report.add("== 一、通道占比（分母 = 快照全部 " + rows.size() + " 行，含无文本的打卡行）==");
        for (Map.Entry<String, Integer> e : byChannel.entrySet()) {
            int blank = blankByChannel.getOrDefault(e.getKey(), 0);
            report.add("  channel=" + pad(e.getKey(), 7) + " " + right(e.getValue(), 4) + " 行  占比 "
                    + pct(e.getValue(), rows.size()) + "   其中无文本 " + blank + " 行（"
                    + pct(blank, e.getValue()) + "）");
        }
        report.add("  校验：三个通道相加 = " + sum(byChannel) + " 行，与总行数 " + rows.size()
                + (sum(byChannel) == rows.size() ? " 一致" : " 不一致（说明有未知 channel 值）"));
        report.add("");
        report.add("== 二、落库标签的分布（这是「线上看起来是什么样」，不是词典的成绩）==");
        for (Map.Entry<String, Integer> e : storedLabels.entrySet()) {
            report.add("  " + pad(e.getKey(), 9) + right(e.getValue(), 4) + " 行  " + pct(e.getValue(), rows.size()));
        }
        report.add("");
        report.add("== 三、把词典重新跑在同一段文本上（可比样本 " + comparable + " 条 = 有 text_snippet 的行）==");
        report.add("  原始一致率（逐字比标签）   = " + agreeAll + "/" + comparable + " = " + pct(agreeAll, comparable));
        report.add("    其中词典零命中(conf=0) 判 neutral = " + zeroHit + " 条，与库里的 neutral 撞车 "
                + zeroHitNeutral + " 条 —— 撞上的这两条都是「没有信号」，不算识别正确");
        int effectiveDenom = comparable - zeroHitNeutral;
        int effectiveAgree = agreeAll - zeroHitNeutral;
        report.add("  总体有效一致率（剔除空值撞车，含 dict 自比 " + dictChannel + " 条） = " + effectiveAgree + "/"
                + effectiveDenom + " = " + pct(effectiveAgree, effectiveDenom)
                + "   ← 这是守恒线；要引用请改用第四节的「外部一致率」");
        report.add("  词典平均置信度（可比样本） = " + round2(confSum / comparable));
        report.add("");
        report.add("== 四、分通道看：谁跟谁一致，才是有意义的问法 ==");
        report.add("  dict 通道（词典自己写的标签，重跑 = " + dictChannelAgree + "/" + dictChannel + " = "
                + pct(dictChannelAgree, dictChannel) + "）→ 这是自比，只用来证明「快照与词典版本没串位」，不能当准确率");
        report.add("  llm 通道（模型写的标签，词典同意 = " + llmChannelAgree + "/" + llmChannel + " = "
                + pct(llmChannelAgree, llmChannel) + "）");
        report.add("  manual 通道（用户自己选的，词典同意 = " + manualChannelAgree + "/" + manualChannel + " = "
                + pct(manualChannelAgree, manualChannel) + "）→ 最接近自评标注的一档");
        int extDenom = external - zeroHitExternal;
        int extAgree = externalAgree - zeroHitExternal;
        report.add("  外部一致率（llm + manual，剔除空值撞车 " + zeroHitExternal + " 条）= " + extAgree + "/" + extDenom
                + " = " + pct(extAgree, extDenom) + "   ← 答辩与论文请引用这一行，不要引用第三节那个数");
        report.add("    口径说明：llm 档的参照物是另一个模型（它自己也会错），manual 档的参照物是用户本人；"
                + "两档合起来当「外部参照」用，分开的数字在上面三行，别只报合计。");
        if (!llmMismatchSamples.isEmpty()) {
            report.add("  llm 不一致的样本（原样抄 " + llmMismatchSamples.size() + " 条，供人工复核而不是只看一个百分比）：");
            for (String sample : llmMismatchSamples) {
                report.add("    - " + sample);
            }
        }
        report.add("");
        report.add("== 五、真实兜底比例（与 emotion_latency.txt 对着读）==");
        report.add("  线上可比文本里 needsLlm=true 的 = " + needsLlm + "/" + comparable + " = "
                + pct(needsLlm, comparable));
        report.add("  合成压测语料里 needsLlm=true 的 = 8846/10000 = 88.46%（那份是把互不相干的词面粘在一起，天然偏低）");
        report.add("  两个数一起才说明白：词典对「有词面但类别不一致」的真实短句确实吃力，但对完全没词面的句子是「明确说不知道」并交棒 LLM，这是设计意图。");
        report.add("");
        report.add("== 六、这份文件不能用来主张什么 ==");
        report.add("  1) 不是 §7.3 的 300 条人工标注集：没有第二人独立标注 ⇒ 没有 Cohen's Kappa ⇒ 没有 macro-F1。");
        report.add("  2) 一致率不等于准确率：llm 那档的参照物是另一个模型，它自己也会错；只有 manual 那档的参照物是用户本人。");
        report.add("  3) 快照是某一刻的线上现状，探针账号在里面占比很高（本次 " + rows.size()
                + " 行几乎都来自冒烟与 DOM 探针）⇒ 这一格在演示数据重播之后必须重导一次，否则数字会跟着夹具漂。");
        report.add("  判据：可比样本 ≥ " + MIN_COMPARABLE + " 行；外部可比样本 ≥ " + MIN_EXTERNAL_COMPARABLE
                + " 行；总体有效一致率 ≥ " + round2(EFFECTIVE_AGREEMENT_FLOOR_PCT) + "%；外部一致率 ≥ "
                + round2(EXTERNAL_AGREEMENT_FLOOR_PCT) + "%；dict 自比一致率必须 100%。");
        for (String line : report) {
            System.out.println("[T4.15b] " + line);
        }

        // ---- 判据：三条都是结构守恒，不是「数字好看」，改错任何一档统计都会立刻红 ----
        assertEquals(rows.size(), sum(byChannel),
                "通道占比的分母不等于总行数：有未知 channel 值，占比表整体不可信");
        assertEquals(comparable, dictChannel + llmChannel + manualChannel,
                "可比样本没有逐条落进三个通道之一（" + comparable + " vs "
                        + (dictChannel + llmChannel + manualChannel) + "）：分通道统计与总一致率对不上同一批行");
        assertEquals(dictChannel, dictChannelAgree,
                "dict 通道的标签本来就是词典写的，重跑却对不上 " + (dictChannel - dictChannelAgree)
                        + " 条 ⇒ 快照版本与词典版本串了位，这份报告的整体口径作废");
        assertTrue(external > 0, "快照里没有 llm/manual 通道的可比样本，外部一致率无从谈起");
        double effectivePct = effectiveDenom == 0 ? 0D : effectiveAgree * 100D / effectiveDenom;
        assertTrue(effectivePct >= EFFECTIVE_AGREEMENT_FLOOR_PCT,
                "总体有效一致率 " + round2(effectivePct) + "% 低于下限 " + round2(EFFECTIVE_AGREEMENT_FLOOR_PCT)
                        + "%：连含 dict 自比的守恒档都掉下来了，先核对快照与词典版本是否串位");
        assertTrue(extDenom >= MIN_EXTERNAL_COMPARABLE,
                "外部可比样本只有 " + extDenom + " 条，低于下限 " + MIN_EXTERNAL_COMPARABLE
                        + "：这个分母报出来的百分比是噪声，先把 llm/manual 通道的线上数据跑出来再说");
        double externalPct = extDenom == 0 ? 0D : extAgree * 100D / extDenom;
        assertTrue(externalPct >= EXTERNAL_AGREEMENT_FLOOR_PCT,
                "外部一致率 " + round2(externalPct) + "% 低于下限 " + round2(EXTERNAL_AGREEMENT_FLOOR_PCT)
                        + "%：词典通道对线上真实文本已退化到没有可对照的信号，应当先修词典而不是先写论文");

        if (!Boolean.getBoolean("mindisle.bench.skipWrite")) {
            Path out = ExperimentPaths.experimentOutputDir().resolve("emotion_channel_agreement.txt");
            Files.createDirectories(out.getParent());
            Files.write(out, report, StandardCharsets.UTF_8);
            System.out.println("[T4.15b] 产物已写入 " + out.toAbsolutePath());
        }
    }

    private static void bump(Map<String, Integer> map, String key) {
        map.merge(key == null ? "(null)" : key, 1, Integer::sum);
    }

    private static int sum(Map<String, Integer> map) {
        int s = 0;
        for (Integer v : map.values()) {
            s += v;
        }
        return s;
    }

    private static String pct(int part, int whole) {
        return whole == 0 ? "—" : round2(part * 100D / whole) + "%";
    }

    private static String round2(double v) {
        return String.format("%.2f", v);
    }

    /** 报表要对得齐就得有对齐：中文按字面宽度排（不按显示宽度），这里只求列位稳定。 */
    private static String pad(String s, int width) {
        String v = s == null ? "" : s;
        StringBuilder sb = new StringBuilder(v);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String right(int n, int width) {
        String v = Integer.toString(n);
        StringBuilder sb = new StringBuilder();
        for (int i = v.length(); i < width; i++) {
            sb.append(' ');
        }
        return sb.append(v).toString();
    }
}
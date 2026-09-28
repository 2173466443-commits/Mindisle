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
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.emotion.DictEmotionEngine.Analysis;

/**
 * T4.15 · 词典情绪通道的单条时延压测（手册 §7.2 末条：词典通道单条 P95 ≤ 50ms，NFR3）。
 *
 * <p><b>为什么这条能在答辩现场被追问</b>：需求 §8.1.2 说情绪识别要在「进模型之前」出结果，
 * 那一步是同步卡在用户第一句话上的 —— 词典通道一旦慢，慢的是整条对话的首字延迟，
 * 而不是某个后台报表。所以判据取 P95 不取平均：平均数会被前半段的热身读数糊过去。</p>
 *
 * <p><b>语料怎么造才有意义</b>：不是随手拼一万条乱码，那只会测出一条「什么都没命中」的廉价快路径。
 * 这里的每一条都由三部分组成：① 从真词典 {@code dict/dut_emotion.tsv} 按固定种子抽出的词面
 * （保证前向最大匹配真的命中、修正规则真的跑到）；② 四类真实句式骨架（打卡 / 对话 / 树洞 / 社区评论）；
 * ③ 按线上比例掺入的修饰信号（程度副词 30%、否定 20%、转折 15%、emoji 10%、疑问句 20%）。
 * 长度按三档分布（短 30% / 中 50% / 长 20%），对齐 chat_message 正文的实际体量。
 * 固定种子 + 固定词典快照 ⇒ 这条压测在任何机器上跑出的都是<b>同一万条文本</b>，
 * 论文里的数字因此可复现；换词典版本会让数字变，但那正是应当重测的时刻。</p>
 *
 * <p><b>热身为什么要单独丢掉</b>：JIT 没编译到的前若干条会慢一个数量级，
 * 把它们算进 P95 是给自己制造假阳性，算进平均值是给自己制造假阴性（本轮先取 1000 条热身，
 * 判据只看其后 10000 条）。产物文件里两段的分界写得清清楚楚。</p>
 *
 * <p><b>产物</b>：{@code 论文材料/experiments/output/emotion_latency.txt}（手册 §7.2 指定的路径）。
 * 用 {@code -Dmindisle.bench.skipWrite=true} 可只跑数不落盘。</p>
 */
@DisplayName("T4.15 词典情绪通道：万条时延压测 P95 ≤ 50ms")
class EmotionLatencyBenchmarkTest {

    /** 手册 §7.2 与需求 NFR3 的硬指标：单条 P95 不超过 50ms。 */
    private static final long P95_BUDGET_NANOS = 50L * 1_000_000L;

    /** 手册 §7.2 的「1 万条批量测试」。 */
    private static final int SAMPLES = 10_000;

    /** 热身条数：不计入判据，也不写进分位数，只在产物里报一条读数供核对。 */
    private static final int WARMUP = 1_000;

    /** 固定种子 ⇒ 语料逐条可复现。这个数字同时出现在产物文件头。 */
    private static final long SEED = 20260928L;

    private static final char TAB = (char) 9;

    private static DictEmotionEngine engine;
    private static List<String> wordFaces;

    @BeforeAll
    static void setUp() {
        engine = new DictEmotionEngine(EmotionLexicon.load(), EmotionPrior.load(new ObjectMapper()));
        wordFaces = loadWordFaces();
        assertTrue(wordFaces.size() > 20_000, "词面抽出来太少，说明解析口径错了，压测语料会失真：" + wordFaces.size());
    }

    /** 只取第一列的词面，注释行与空行跳过；不做去重 —— 重复出现的词面正是线上文本的真实形态。 */
    private static List<String> loadWordFaces() {
        List<String> out = new ArrayList<>();
        try (InputStream in = EmotionLatencyBenchmarkTest.class.getClassLoader()
                .getResourceAsStream("dict/dut_emotion.tsv")) {
            assertTrue(in != null, "词典资源 dict/dut_emotion.tsv 不在 classpath 上");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == 35) {
                    continue;
                }
                int tab = line.indexOf(TAB);
                String face = tab < 0 ? line : line.substring(0, tab);
                if (!face.isBlank()) {
                    out.add(face);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读词典失败：" + e, e);
        }
        return out;
    }

    // ============================================================ 语料构造

    /** 四类真实句式骨架。{} 处填词面，其余部分是线上用户的说话方式。 */
    private static final String[] SKELETONS = {
            "今天感觉比较{}，晚上{}得有点晚，早上不想起床上课。",
            "老师让我把话说清楚，可我一说就{}，也不知道自己在{}什么。",
            "一个人待在宿舍里，越想越{}，真想找个人说说话。",
            "看到帖子说{}，我第一反应是{}，然后才开始担心明天的答辩。",
            "这周比上周好一些，没有以前那么{}了，虽然还是有点{}。",
            "妈妈打电话问我怎么样，我不想让她{}，就说挺好的，挂断以后反而更{}。",
            "社团的活动临时取消，我其实挺{}的，可又觉得自己这样有点{}。",
            "晚上十一点的操场很安静，跑完两圈之后没有这么{}了。",
            "论文改了第四遍还是被退回，我有点{}，也{}是不是自己不适合读研。",
            "朋友送我一杯奶茶，说是看我最近{}，那一瞬间其实挺{}的。",
    };

    private static final String[] DEGREES = { "很", "特别", "非常", "有点", "稍微", "太", "超级" };
    private static final String[] NEGATIONS = { "不", "没有", "不太", "不算" };
    private static final String[] CONTRASTS = { "但是", "可是", "不过", "然而" };
    private static final String[] EMOJIS = { "😀", "😢", "😡", "😨", "🙁" };

    /** 造一条语料：至少两个词面（让 FMM 与修正规则都跑到），按概率掺修饰信号，末尾按长度档补齐。 */
    private static String buildCorpusLine(Random rnd, int targetLen) {
        StringBuilder sb = new StringBuilder();
        String skeleton = SKELETONS[rnd.nextInt(SKELETONS.length)];
        String body = skeleton;
        int guard = 0;
        while (body.indexOf(123) >= 0 && guard++ < 8) {
            String face = wordFaces.get(rnd.nextInt(wordFaces.size()));
            if (rnd.nextInt(100) < 30) {
                face = DEGREES[rnd.nextInt(DEGREES.length)] + face;
            }
            if (rnd.nextInt(100) < 20) {
                face = NEGATIONS[rnd.nextInt(NEGATIONS.length)] + face;
            }
            int at = body.indexOf(123);
            body = body.substring(0, at) + face + body.substring(at + 1);
        }
        sb.append(body);
        if (rnd.nextInt(100) < 15) {
            sb.append(CONTRASTS[rnd.nextInt(CONTRASTS.length)]);
            sb.append(wordFaces.get(rnd.nextInt(wordFaces.size())));
            sb.append("，还是想先把这周过完。");
        }
        while (sb.length() < targetLen) {
            sb.append("后来想了想，").append(wordFaces.get(rnd.nextInt(wordFaces.size())));
            sb.append("也就算了，日子还得继续过下去。");
        }
        if (rnd.nextInt(100) < 10) {
            sb.append(EMOJIS[rnd.nextInt(EMOJIS.length)]);
        }
        if (rnd.nextInt(100) < 20) {
            sb.append("这样是不是不太好？");
        }
        return sb.toString();
    }

    private static List<String> buildCorpus() {
        Random rnd = new Random(SEED);
        List<String> corpus = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            int bucket = rnd.nextInt(100);
            int targetLen = bucket < 30 ? 12 + rnd.nextInt(9) : (bucket < 80 ? 22 + rnd.nextInt(39) : 70 + rnd.nextInt(90));
            corpus.add(buildCorpusLine(rnd, targetLen));
        }
        return corpus;
    }

    // ============================================================ 压测

    @Test
    @DisplayName("一万条真语料：P50/P90/P95/P99/max 逐条计时，P95 必须 ≤ 50ms，产物落 experiments/output")
    void tenThousandAnalysesKeepP95UnderFiftyMillis() throws Exception {
        List<String> corpus = buildCorpus();
        assertEquals(SAMPLES, corpus.size(), "语料条数就是手册要的 1 万条，不许悄悄减量");

        // ① 热身：只跑不记。同时把「语料确实有命中」这件事量出来，否则这条压测是在测空转。
        int hitWarm = 0;
        for (int i = 0; i < WARMUP; i++) {
            Analysis a = engine.analyze(corpus.get(i));
            if (a != null && a.confidence() > 0) {
                hitWarm++;
            }
        }

        // ② 正式计数：逐条 nanoTime，包住 analyze 本身，不含任何 IO。
        long[] nanos = new long[SAMPLES];
        long totalChars = 0L;
        int maxChars = 0;
        int hits = 0;
        int needsLlm = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < SAMPLES; i++) {
            String text = corpus.get(i);
            long s = System.nanoTime();
            Analysis a = engine.analyze(text);
            nanos[i] = System.nanoTime() - s;
            totalChars += text.length();
            maxChars = Math.max(maxChars, text.length());
            if (a != null && a.confidence() > 0) {
                hits++;
            }
            if (a != null && a.needsLlm()) {
                needsLlm++;
            }
        }
        long wallNanos = System.nanoTime() - t0;

        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        long p50 = percentile(sorted, 50);
        long p90 = percentile(sorted, 90);
        long p95 = percentile(sorted, 95);
        long p99 = percentile(sorted, 99);
        long max = sorted[sorted.length - 1];
        long sum = 0L;
        for (long v : nanos) {
            sum += v;
        }
        double meanMs = sum / (double) SAMPLES / 1_000_000D;
        double throughput = SAMPLES * 1_000_000_000D / wallNanos;

        List<String> report = new ArrayList<>();
        report.add("词典情绪通道 · 单条时延压测（任务 T4.15 / 手册 §7.2 / 需求 NFR3）");
        report.add("生成时间 = " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        report.add("来源测试 = backend/src/test/java/com/mindisle/emotion/EmotionLatencyBenchmarkTest.java");
        report.add("JDK = " + System.getProperty("java.version") + " ; VM = " + System.getProperty("java.vm.name"));
        report.add("词典模型版本 = " + engine.modelVersion() + " ; 词面样本池 = " + wordFaces.size() + " 条");
        report.add("语料随机种子 = " + SEED + "（固定 ⇒ 同一万条文本可逐条复现）");
        report.add("语料构造 = 真词典词面 × 10 类真实句式骨架 + 程度副词30%/否定20%/转折15%/emoji10%/疑问20%，长度三档 30%/50%/20%");
        report.add("热身（不计入判据） = " + WARMUP + " 条，其中命中 " + hitWarm + " 条");
        report.add("正式样本 = " + SAMPLES + " 条，命中（conf>0） = " + hits + " 条（" + pct(hits, SAMPLES) + "），送 LLM 兜底 = " + needsLlm + " 条（" + pct(needsLlm, SAMPLES) + "）");
        report.add("文本长度 = 平均 " + (totalChars / SAMPLES) + " 字，最长 " + maxChars + " 字");
        report.add("兜底比例解读 = needsLlm 是 conf < 0.55 送 LLM 的比例；合成语料是把互不相干的词面粘在一起，类别一致率天然偏低，所以这条比例高不等于线上比例高。它对压测反而是好事：每条都走满词典匹配 + 四类修正 + 置信度计算，是词典通道的最坏路径。线上真实口径见 emotion_channel_agreement.txt。");
        report.add("单条耗时 = P50 " + ms(p50) + " / P90 " + ms(p90) + " / P95 " + ms(p95) + " / P99 " + ms(p99) + " / max " + ms(max));
        report.add("平均耗时 = " + round2(meanMs) + " ms ；整批墙钟 = " + round2(wallNanos / 1_000_000D) + " ms ；吞吐 = " + Math.round(throughput) + " 条/秒");
        report.add("判据 = P95 ≤ 50 ms ⇒ " + (p95 <= P95_BUDGET_NANOS ? "达标" : "不达标（实测 " + ms(p95) + "）"));
        report.add("口径说明 = 计时只包住 DictEmotionEngine.analyze，不含 DB 与网络；分位数按最近邻法（升序第 ceil(p*n) 个）取。");

        for (String row : report) {
            System.out.println("[T4.15] " + row);
        }

        // ③ 语料有效性兜底：命中率过低说明压测在测空转，那这条绿就没有意义，直接判红。
        assertTrue(hits * 100L >= SAMPLES * 60L,
                "压测语料命中率低于 60%，等于没走词典通道，数字不可信：hits=" + hits);
        assertTrue(p95 <= P95_BUDGET_NANOS,
                "词典通道 P95 超过 50ms（手册 §7.2 / NFR3）：" + ms(p95));

        if (!Boolean.getBoolean("mindisle.bench.skipWrite")) {
            Path out = experimentDir().resolve("emotion_latency.txt");
            Files.createDirectories(out.getParent());
            Files.write(out, report, StandardCharsets.UTF_8);
            System.out.println("[T4.15] 产物已写入 " + out.toAbsolutePath());
        }
    }

    /** 最近邻分位：升序第 ceil(p/100*n) 个（1-based），下标夹在 [0, n-1]。 */
    private static long percentile(long[] ascending, int p) {
        int n = ascending.length;
        int idx = (int) Math.ceil(p / 100D * n) - 1;
        return ascending[Math.min(n - 1, Math.max(0, idx))];
    }

    private static String ms(long nanos) {
        return round2(nanos / 1_000_000D) + "ms";
    }

    private static String round2(double v) {
        return String.format("%.2f", v);
    }

    private static String pct(int part, int whole) {
        return round2(part * 100D / whole) + "%";
    }

    /**
     * 产物目录 = 项目根下的 {@code 论文材料/experiments/output}（手册 §7.2 指定）。
     * 解析口径统一挪到 {@link ExperimentPaths}，因为 T4.15 现在有两份产物共用同一个目录；
     * 这里只留一层薄委托，保留原名是为了不动上面那段落盘代码。
     */
    private static Path experimentDir() {
        return ExperimentPaths.experimentOutputDir();
    }

}

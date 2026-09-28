package com.mindisle.emotion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 情绪词典的加载与前向最大匹配（任务 4.7 第 ②③ 级的数据结构底座）。
 *
 * <p><b>装了什么</b>：DUT 派生情感词（{@code dict/dut_emotion.tsv}）、程度副词
 * （{@code dict/degree_adv.txt}）、否定词（{@code dict/negation.txt}）、
 * emoji（{@code dict/emoji_emotion.tsv}）、转折连接词（内置常量，见 {@link #CONTRAST}）。
 * 五类共用一张「词 -> 词条列表」表，所以一遍最大匹配就能同时拿到情绪词和它的修饰语，
 * 不需要先做分词——这是本阶段不引 HanLP/ansj 的根本原因：
 * 手册 §7.2 让分词，但词典通道真正要的只是「在正确的位置切出正确的词」，
 * 而按词典本身做 FMM 恰好就是这个语义，且比通用分词少一个 10MB 级依赖（需求 §12 反过度设计）。</p>
 *
 * <p><b>为什么 DUT 的坑要写在这儿的注释里</b>：本体是多义词表，同一个词面可以有多行
 * （不同词性、不同义项、主情感 + 辅助情感）。实测 27315 个词面里有 2045 个跨 7 类，
 * 典型如「开心」既是 adj/PA(快乐,义项 1) 又是 verb/NN(贬责,义项 2)。
 * 消歧口径：<b>优先取义项序号最小的一批</b>（DUT 的义项 1 即核心义），
 * 主情感权重 1.0、辅助情感权重 0.5、非核心义项权重 0.6，权重全部来自 prior.json。
 * 这套口径是本项目自定的，论文 4.x 与 {@code dict/dut_code_map.tsv} 要一并交代。</p>
 */
public final class EmotionLexicon {

    private static final Logger log = LoggerFactory.getLogger(EmotionLexicon.class);

    /** 词条类型。一条词面可以同时是多种（例如「好」既是程度副词也可能出现在情感词里）。 */
    public enum Kind {
        /** DUT 情感词。 */
        EMOTION,
        /** 程度副词，带乘子。 */
        DEGREE,
        /** 否定词。 */
        NEGATION,
        /** emoji，直接给类别与强度。 */
        EMOJI,
        /** 转折连接词，只影响加权，不产出情绪。 */
        CONTRAST
    }

    /**
     * 转折连接词（手册 §7.2 ③「转折取后句」）。
     *
     * <p>写死成常量：这类功能词只有十几个、几乎不随语料变，
     * 单独建一个 dict 文件反而让人以为它可以被运维改。中文里真正承担
     * 「情绪重心在后面」的也就是这几个，宁可少不可错——
     * 漏掉的「不过话说回来」这类会退化成「不」+「过」，不产生错误重心，只是不做加权。</p>
     */
    static final List<String> CONTRAST = List.of(
            "但是", "但", "可是", "不过", "然而", "倒是", "其实", "反而", "只是", "偏偏");

    /** 转折前句子的降权系数。不是 0：有人先说结果再说原因，整句丢掉会误伤（论文 4.5 消融项）。 */
    static final double BEFORE_CONTRAST_WEIGHT = 0.4;

    /** 一个词条。DUT 侧字段尽量原样保留，实验复现时不用回头翻原始 CSV。 */
    public record Entry(Kind kind, String word, String pos, String dutCode, String dutCodeName,
                        String family, String label, int intensity9, int intensity, int polarity,
                        int senseIndex, boolean aux, double multiplier) {

        static Entry emotion(EmotionLexicon.LexRow row) {
            return new Entry(Kind.EMOTION, row.word, row.pos, row.dutCode, row.dutCodeName, row.family,
                    row.label, row.intensity9, row.intensity, row.polarity, row.senseIndex, row.aux, 1.0);
        }
    }

    /** TSV 解析中间态（避免 11 参数的构造器在两个工厂方法里各抄一遍）。 */
    private static final class LexRow {
        String word;
        String pos;
        String dutCode;
        String dutCodeName;
        String family;
        String label;
        int intensity9;
        int intensity;
        int polarity;
        int senseIndex;
        boolean aux;
    }

    private final Map<String, List<Entry>> byWord;
    private final Map<Integer, BitSet> firstCharByLength;
    private final int maxWordLength;
    private final String dutVersion;
    /** DUT 去重后的<b>词面</b>数（论文里「27315 个词面」的唯一出处）。 */
    private final int dutFaceCount;
    /** DUT 非辅助角色的词条行数（27417），注意它不等于词面数：一个词面可以有多行 primary。 */
    private final int dutPrimaryCount;
    private final int dutEntryCount;

    private EmotionLexicon(Map<String, List<Entry>> byWord, Map<Integer, BitSet> firstCharByLength,
                           int maxWordLength, String dutVersion, int dutFaceCount, int dutPrimaryCount,
                           int dutEntryCount) {
        this.byWord = byWord;
        this.firstCharByLength = firstCharByLength;
        this.maxWordLength = maxWordLength;
        this.dutVersion = dutVersion;
        this.dutFaceCount = dutFaceCount;
        this.dutPrimaryCount = dutPrimaryCount;
        this.dutEntryCount = dutEntryCount;
    }

    /**
     * 全量加载。任一情感词典缺失都会抛 {@link IllegalStateException}：
     * 没有词典的「词典通道」不是降级，是假数据，必须响亮地失败。
     * 程度副词/否定词/emoji 属于增益项，缺失只告警不抛。
     */
    public static EmotionLexicon load() {
        Map<String, List<Entry>> table = new HashMap<>(40000);
        String version;
        int faces;
        int primaries;
        int entries;
        try {
            long[] stats = loadDut(table);
            version = readVersionLine(DUT_RESOURCE);
            faces = (int) stats[0];
            primaries = (int) stats[1];
            entries = (int) stats[2];
        } catch (IOException e) {
            throw new IllegalStateException("情绪词典缺失或不可读：" + DUT_RESOURCE
                    + "（重跑 tools/derive_dut.mjs 生成派生表）", e);
        }
        try {
            loadMultiplier(table, DEGREE_RESOURCE);
        } catch (IOException e) {
            log.warn("程度副词表缺失，情绪强度修正将退化为不修正：{}", e.toString());
        }
        try {
            loadPlain(table, NEGATION_RESOURCE, Kind.NEGATION);
        } catch (IOException e) {
            log.warn("否定词表缺失，否定翻转将失效：{}", e.toString());
        }
        try {
            loadEmoji(table, EMOJI_RESOURCE);
        } catch (IOException e) {
            log.warn("emoji 情绪表缺失，表情信号将被忽略：{}", e.toString());
        }
        for (String contrast : CONTRAST) {
            table.computeIfAbsent(contrast, k -> new ArrayList<>())
                    .add(new Entry(Kind.CONTRAST, contrast, "conj", "CT", "转折", "", "",
                            0, 0, 0, 1, false, 1.0));
        }

        Map<Integer, BitSet> bits = new HashMap<>();
        int max = 1;
        for (Map.Entry<String, List<Entry>> entry : table.entrySet()) {
            int len = entry.getKey().length();
            if (len > max) {
                max = len;
            }
            // 词面按 UTF-16 单元存，首字符取码位低 16 位；代理对开头的 emoji 落在同一个桶，
            // 只影响「这个长度有没有词以它开头」这一个布尔判断，不会漏匹配（长度桶只是预筛）。
            int head = entry.getKey().charAt(0);
            bits.computeIfAbsent(len, k -> new BitSet(0xFFFF)).set(head & 0xFFFF);
        }
        log.info("情绪词典加载完成 dut={} DUT词面={} primary词条={} 全量词条={} 含辅助词的总词面槽={} 最长={}",
                version, faces, primaries, entries, table.size(), max);
        return new EmotionLexicon(Collections.unmodifiableMap(table), bits, max, version, faces, primaries,
                entries);
    }

    private static final String DUT_RESOURCE = "dict/dut_emotion.tsv";
    private static final String DEGREE_RESOURCE = "dict/degree_adv.txt";
    private static final String NEGATION_RESOURCE = "dict/negation.txt";
    private static final String EMOJI_RESOURCE = "dict/emoji_emotion.tsv";

    /** 派生表第一行的「# 版本标识：xxx」，回落到文件名，保证 model_version 永远非空。 */
    private static String readVersionLine(String resource) {
        try (InputStream in = EmotionLexicon.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return resource;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int at = line.indexOf("版本标识：");
                    if (at >= 0) {
                        return line.substring(at + "版本标识：".length()).trim();
                    }
                    if (!line.startsWith("#")) {
                        break;
                    }
                }
            }
        } catch (IOException ignored) {
            // 版本只是留痕字段，读不到不该影响匹配能力
        }
        return resource;
    }

    private static long[] loadDut(Map<String, List<Entry>> table) throws IOException {
        List<String> lines = readLines(DUT_RESOURCE);
        int primaryRows = 0;
        int entryRows = 0;
        for (String line : lines) {
            String[] f = line.split("\t");
            // 列序与 tools/derive_dut.mjs 的 banner 一致：
            // word pos dut_code dut_code_name dut_family plutchik intensity_1_9 intensity_1_5 polarity sense_index role
            if (f.length < 11) {
                continue;
            }
            LexRow row = new LexRow();
            row.word = f[0];
            row.pos = f[1];
            row.dutCode = f[2];
            row.dutCodeName = f[3];
            row.family = f[4];
            row.label = f[5];
            row.intensity9 = parseInt(f[6], 5);
            row.intensity = clampIntensity(parseInt(f[7], 3));
            row.polarity = parseInt(f[8], 0);
            row.senseIndex = Math.max(1, parseInt(f[9], 1));
            row.aux = "aux".equals(f[10]);
            table.computeIfAbsent(row.word, k -> new ArrayList<>()).add(Entry.emotion(row));
            entryRows++;
            if (!row.aux) {
                primaryRows++;
            }
        }
        if (entryRows == 0) {
            throw new IOException("派生表为空，先跑 tools/derive_dut.mjs");
        }
        // 此刻 table 里只有 DUT 的词面，size() 就是去重后的 DUT 词面数（论文口径用它，不是 primary 行数）
        return new long[] {table.size(), primaryRows, entryRows};
    }

    private static void loadMultiplier(Map<String, List<Entry>> table, String resource) throws IOException {
        for (String line : readLines(resource)) {
            String[] f = line.split("\t");
            if (f.length < 2 || f[0].isEmpty()) {
                continue;
            }
            double mult;
            try {
                mult = Double.parseDouble(f[1].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            table.computeIfAbsent(f[0].trim(), k -> new ArrayList<>())
                    .add(new Entry(Kind.DEGREE, f[0].trim(), "adv", "DEG", "程度", "", "",
                            0, 0, 0, 1, false, mult));
        }
    }

    private static void loadPlain(Map<String, List<Entry>> table, String resource, Kind kind) throws IOException {
        for (String line : readLines(resource)) {
            String word = line.trim();
            if (word.isEmpty()) {
                continue;
            }
            table.computeIfAbsent(word, k -> new ArrayList<>())
                    .add(new Entry(kind, word, "", "NEG", "否定", "", "", 0, 0, 0, 1, false, 1.0));
        }
    }

    private static void loadEmoji(Map<String, List<Entry>> table, String resource) throws IOException {
        for (String line : readLines(resource)) {
            String[] f = line.split("\t");
            if (f.length < 3 || f[0].isEmpty()) {
                continue;
            }
            table.computeIfAbsent(f[0], k -> new ArrayList<>())
                    .add(new Entry(Kind.EMOJI, f[0], "emoji", "EMO", "表情", "", f[1].trim(),
                            0, clampIntensity(parseInt(f[2], 3)), 0, 1, false, 1.0));
        }
    }

    /** 读掉 '#' 注释与空行；文件不存在直接抛 IOException 交给调用方决定是否降级。 */
    private static List<String> readLines(String resource) throws IOException {
        try (InputStream in = EmotionLexicon.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("classpath 资源不存在：" + resource);
            }
            List<String> out = new ArrayList<>(32000);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') {
                        continue;
                    }
                    out.add(line);
                }
            }
            return out;
        }
    }

    private static int parseInt(String raw, int dflt) {
        try {
            return (int) Math.round(Double.parseDouble(raw.trim()));
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    private static int clampIntensity(int value) {
        return Math.max(1, Math.min(5, value));
    }

    /**
     * 一次匹配结果：词面在扫描串中的半开区间 + 该词面的<b>全部</b>词条
     * （DUT 多义词在这里仍然是一对多，消歧交给 {@link #emotionsOf}）。
     */
    public record Match(List<Entry> entries, int start, int end) {

        String word() {
            return entries.get(0).word();
        }
    }

    /**
     * 双向最大匹配取优（FMM + BMM，谁覆盖的字数多就用谁）。
     *
     * <p><b>为什么要做双向</b>：纯正向前匹配会因为「已经心灰意冷」先把 <b>经心</b>（DUT 里真实存在的
     * NE 词）切走，从而把成语 <b>心灰意冷</b> 整个吞掉——切错一个词，后面全部错位。
     * 反向匹配（BMM）在同一句上切出的是 心灰意冷，覆盖 4 个字 &gt; 经心 的 2 个字。
     * 取舍规则用最经典的那条：<b>覆盖字数多的胜出；字数相同取词数少的</b>。
     * 代价是每个子句多扫一遍，实测 P95 仍在 50ms 预算内（见 DictEmotionEngineTest 的性能钉子）。</p>
     *
     * <p>只在 {@link #firstCharByLength} 预筛通过的长度上取子串，
     * 避免「每个位置都切 15 个子串去查哈希」——实测 1000 字的长文靠这道预筛
     * 把子串数量压掉一个数量级（P95 预算的余量就在这儿）。</p>
     */
    public List<Match> match(String text) {
        List<Match> forward = matchForward(text);
        List<Match> backward = matchBackward(text);
        int coveredForward = coveredChars(forward);
        int coveredBackward = coveredChars(backward);
        if (coveredBackward > coveredForward) {
            return backward;
        }
        if (coveredBackward == coveredForward && backward.size() < forward.size()) {
            return backward;
        }
        return forward;
    }

    /** 前向最大匹配：从左侧逐格推进，每格取能切到的最长词面。 */
    private List<Match> matchForward(String text) {
        List<Match> out = new ArrayList<>(8);
        int length = text.length();
        int index = 0;
        while (index < length) {
            int matched = 0;
            List<Entry> hit = null;
            for (int len = Math.min(maxWordLength, length - index); len >= 1; len--) {
                if (!firstCharHas(len, text.charAt(index))) {
                    continue;
                }
                List<Entry> found = byWord.get(text.substring(index, index + len));
                if (found != null) {
                    matched = len;
                    hit = found;
                    break;
                }
            }
            if (matched > 0) {
                out.add(new Match(hit, index, index + matched));
                index += matched;
            } else {
                index++;
            }
            // 没匹配到就前进一格；刻意不做「按首字符跳格」的优化，
            // 否则「开开心心」这类叠词会因为跳格漏掉第二个词，宁慢勿错。
        }
        return out;
    }

    /** 后向最大匹配：从右侧逐格回退，每格取能切到的最长词面（词面区间仍是 [start,end)）。 */
    private List<Match> matchBackward(String text) {
        List<Match> reversed = new ArrayList<>(8);
        int length = text.length();
        int end = length;
        while (end > 0) {
            int matched = 0;
            List<Entry> hit = null;
            for (int len = Math.min(maxWordLength, end); len >= 1; len--) {
                int start = end - len;
                if (!firstCharHas(len, text.charAt(start))) {
                    continue;
                }
                List<Entry> found = byWord.get(text.substring(start, end));
                if (found != null) {
                    matched = len;
                    hit = found;
                    break;
                }
            }
            if (matched > 0) {
                reversed.add(new Match(hit, end - matched, end));
                end -= matched;
            } else {
                end--;
            }
        }
        List<Match> out = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            out.add(reversed.get(i));
        }
        return out;
    }

    /** 该长度的词面里，有没有以这个字符开头的（位图预筛，避免无谓的 substring+哈希）。 */
    private boolean firstCharHas(int length, char head) {
        BitSet bits = firstCharByLength.get(length);
        return bits != null && bits.get(head & 0xFFFF);
    }

    private static int coveredChars(List<Match> matches) {
        int sum = 0;
        for (Match match : matches) {
            sum += match.end() - match.start();
        }
        return sum;
    }

    /** 词条列表按「类型优先 + DUT 消歧口径」压成唯一最佳情绪解释（详见类注释）。 */
    public static List<Entry> emotionsOf(List<Entry> candidates) {
        List<Entry> emotions = new ArrayList<>(candidates.size());
        for (Entry entry : candidates) {
            if (entry.kind() == Kind.EMOTION) {
                emotions.add(entry);
            }
        }
        if (emotions.size() <= 1) {
            return emotions;
        }
        int minSense = Integer.MAX_VALUE;
        for (Entry entry : emotions) {
            minSense = Math.min(minSense, entry.senseIndex());
        }
        List<Entry> core = new ArrayList<>(emotions.size());
        for (Entry entry : emotions) {
            // 只保留核心义项；辅助情感一起留下（它的权重在打分时按 auxRole 折半）
            if (entry.senseIndex() == minSense) {
                core.add(entry);
            }
        }
        return core;
    }

    public static List<Entry> nonEmotionsOf(List<Entry> candidates) {
        List<Entry> others = new ArrayList<>(candidates.size());
        for (Entry entry : candidates) {
            if (entry.kind() != Kind.EMOTION) {
                others.add(entry);
            }
        }
        return others;
    }

    public String dutVersion() {
        return dutVersion;
    }

    /** DUT 去重词面数（27315）。 */
    public int dutFaceCount() {
        return dutFaceCount;
    }

    /** DUT primary 词条行数（27417）。 */
    public int dutPrimaryCount() {
        return dutPrimaryCount;
    }

    public int dutEntryCount() {
        return dutEntryCount;
    }

    public int wordSlots() {
        return byWord.size();
    }

    public int maxWordLength() {
        return maxWordLength;
    }
}

package com.mindisle.emotion;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 情绪先验与词典通道全部可调参数（{@code resources/dict/prior.json}，任务 4.7 第 ④⑤ 级）。
 *
 * <p><b>为什么单独一个类而不是塞进 MindisleProperties</b>：这些数字属于
 * 「算法口径」而不是「部署配置」——改 valence 就等于改创新点 1 的推荐输入，
 * 必须和词典版本一起进实验记录，不能让运维在 application.yml 里顺手调。
 * 因此它只从 classpath 的带版本文件读，不读环境变量。</p>
 *
 * <p>读不到文件时<b>回落内置默认值</b>并打 error 日志，而不是让应用起不来：
 * 需求 NFR5 的口径是「AI 依赖故障时系统降级不整站不可用」，
 * 一个词典参数缺失就 503 属于把可降级的事做成了致命故障。
 * 回落值与 prior.json 逐字一致，两处不同步由 {@code EmotionPriorTest} 钉住。</p>
 */
public final class EmotionPrior {

    private static final Logger log = LoggerFactory.getLogger(EmotionPrior.class);
    private static final String RESOURCE = "dict/prior.json";

    /** 七类标签，顺序即 prior.json 里的展示顺序，也是 emotion_record.label 的取值域（需求 §1.5）。 */
    public static final List<String> LABELS = List.of(
            "joy", "trust", "anger", "sadness", "fear", "disgust", "neutral");

    /** 内置默认表：valence 与前端 EmotionPill / EmotionView 的取值逐字一致。 */
    private static final Map<String, Double> DEFAULT_VALENCE = Map.of(
            "joy", 0.8, "trust", 0.5, "neutral", 0.0,
            "sadness", -0.6, "fear", -0.6, "anger", -0.6, "disgust", -0.4);

    private static final Map<String, Integer> DEFAULT_ANCHOR = Map.of(
            "joy", 4, "trust", 3, "neutral", 2,
            "sadness", 4, "fear", 4, "anger", 4, "disgust", 3);

    /** 平票时的优先顺序（手册 §7.2 ②「平票取负面优先」的唯一出处）。 */
    public static final List<String> NEGATIVE_FIRST = List.of(
            "anger", "disgust", "fear", "sadness", "neutral", "trust", "joy");

    private final String version;
    private final Map<String, Double> valence;
    private final Map<String, Integer> anchorIntensity;
    private final Map<String, String> zh;
    private final Map<String, Double> weights;
    private final Map<String, Double> thresholds;
    private final Map<String, Double> coverage;
    private final boolean fromFile;

    private EmotionPrior(String version, Map<String, Double> valence, Map<String, Integer> anchor,
                         Map<String, String> zh, Map<String, Double> weights, Map<String, Double> thresholds,
                         Map<String, Double> coverage, boolean fromFile) {
        this.version = version;
        this.valence = Map.copyOf(valence);
        this.anchorIntensity = Map.copyOf(anchor);
        this.zh = Map.copyOf(zh);
        this.weights = Map.copyOf(weights);
        this.thresholds = Map.copyOf(thresholds);
        this.coverage = Map.copyOf(coverage);
        this.fromFile = fromFile;
    }

    /** 从 classpath 读取；任何异常都回落默认值（见类注释）。 */
    public static EmotionPrior load(ObjectMapper mapper) {
        Map<String, Double> valence = new LinkedHashMap<>(DEFAULT_VALENCE);
        Map<String, Integer> anchor = new LinkedHashMap<>(DEFAULT_ANCHOR);
        Map<String, String> zh = new LinkedHashMap<>();
        Map<String, Double> weights = new LinkedHashMap<>();
        Map<String, Double> thresholds = new LinkedHashMap<>();
        Map<String, Double> coverage = new LinkedHashMap<>();
        String version = "prior-default";
        boolean ok = false;
        try (InputStream in = EmotionPrior.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in != null) {
                JsonNode root = mapper.readTree(in);
                version = root.path("version").asText(version);
                JsonNode labels = root.path("labels");
                for (String label : LABELS) {
                    JsonNode node = labels.path(label);
                    if (node.isObject()) {
                        valence.put(label, node.path("valence").asDouble(valence.get(label)));
                        anchor.put(label, node.path("anchorIntensity").asInt(anchor.get(label)));
                        zh.put(label, node.path("zh").asText(label));
                    }
                }
                readFlat(root.path("weights"), weights);
                readFlat(root.path("thresholds"), thresholds);
                readFlat(root.path("coverage"), coverage);
                ok = true;
            }
        } catch (Exception e) {
            log.error("情绪先验读取失败，回落内置默认值 resource={} err={}", RESOURCE, e.toString());
        }
        if (!ok) {
            // 默认值也要能算 conf，所以补三份兜底参数（与 prior.json 同名同值）。
            weights.putIfAbsent("sensePrimary", 1.0);
            weights.putIfAbsent("auxRole", 0.5);
            weights.putIfAbsent("emojiHit", 0.7);
            thresholds.putIfAbsent("llmFallbackConf", 0.55);
            thresholds.putIfAbsent("uncertainConf", 0.6);
            thresholds.putIfAbsent("negationGapChars", 1.0);
            thresholds.putIfAbsent("negationChainSteps", 4.0);
            thresholds.putIfAbsent("degreeGapBeforeChars", 1.0);
            thresholds.putIfAbsent("exclamationMultiplier", 1.3);
            thresholds.putIfAbsent("intensityMin", 1.0);
            thresholds.putIfAbsent("intensityMax", 5.0);
            thresholds.putIfAbsent("minCharsForAnalysis", 2.0);
            coverage.putIfAbsent("denominatorMinChars", 8.0);
            coverage.putIfAbsent("saturatedCoverageChars", 24.0);
            coverage.putIfAbsent("confCeiling", 0.95);
        }
        for (String label : LABELS) {
            zh.putIfAbsent(label, label);
        }
        return new EmotionPrior(version, valence, anchor, zh, weights, thresholds, coverage, ok);
    }

    private static void readFlat(JsonNode node, Map<String, Double> sink) {
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNumber()) {
                sink.put(entry.getKey(), entry.getValue().asDouble());
            }
        });
    }

    public String version() {
        return version;
    }

    /** true 表示参数来自 prior.json；false 表示走了内置兜底，日志与体检接口要能看见这个差别。 */
    public boolean loaded() {
        return fromFile;
    }

    public Map<String, Double> valenceByLabel() {
        return Collections.unmodifiableMap(valence);
    }

    /** 效价档位（-1/0/1）：需求 §7.2 #14 的 valence 列是 TINYINT，只存分档不存小数。 */
    public int valenceTier(String label) {
        double v = valence.getOrDefault(label, 0.0);
        return v > 0.05 ? 1 : (v < -0.05 ? -1 : 0);
    }

    public double valenceOf(String label) {
        return valence.getOrDefault(label, 0.0);
    }

    public int anchorIntensity(String label) {
        return anchorIntensity.getOrDefault(label, 2);
    }

    public String zh(String label) {
        return zh.getOrDefault(label, label);
    }

    public boolean isKnown(String label) {
        return label != null && valence.containsKey(label);
    }

    public double weight(String key, double dflt) {
        return weights.getOrDefault(key, dflt);
    }

    public double threshold(String key, double dflt) {
        return thresholds.getOrDefault(key, dflt);
    }

    public double coverageParam(String key, double dflt) {
        return coverage.getOrDefault(key, dflt);
    }

    /** 平票裁决：按「负面优先」表取先出现的标签；表里没有的排到最后。 */
    public String pickOnTie(List<String> tiedLabels) {
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        for (String label : tiedLabels) {
            int rank = NEGATIVE_FIRST.indexOf(label);
            rank = rank < 0 ? NEGATIVE_FIRST.size() + 1 : rank;
            if (rank < bestRank) {
                bestRank = rank;
                best = label;
            }
        }
        return best == null ? "neutral" : best;
    }
}

package com.mindisle.emotion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.emotion.dto.EmotionGroupRow;
import com.mindisle.emotion.dto.ProfileView;
import com.mindisle.mapper.EmotionRecordMapper;

/**
 * 情绪档案页的四图聚合（任务 T4.10 · 需求 FR3.4 · BR12）。
 *
 * <p><b>一条分组 SQL 喂四张图</b>：{@code GROUP BY record_date, label} 的结果
 * （{@link EmotionGroupRow}）在 Java 侧做四次投影——趋势按天取主导标签、分布按标签求和、
 * 日历按天填格、连续天数按天序列递推。手册 §7.1 行 4.10 写的是「聚合 SQL 三写」，
 * 实现时收成一写：三写会得到三个「今天平均强度是多少」的答案，
 * 而它们并排显示在同一个屏幕上。这是阶段 3 那个「两处评论数不一致」bug 的通用形状，
 * 手册 §6.4 第 4 条已经为它写过一次教训，这里不再犯第二次。</p>
 *
 * <p><b>可信/不可信的分工</b>（FR3.2）：{@code confidence < }阈值的行
 * <b>计入总数与日历格，但不进趋势线、不进分布饼</b>。阈值来自
 * {@code mindisle.llm.emotion-confident-min}（默认 0.6），以参数传给 SQL，
 * 不在 SQL 里写字面量——它是需求里可调的口径。</p>
 *
 * <p><b>BR12 在这里落地</b>：{@code accumulating = 有可信记录的天数 < 3}。
 * 前端拿到 true 就画「数据积累中」。判据用「天数」而不是「条数」：
 * 一个人一天里发了 30 条消息会得到 30 行被动识别记录，
 * 但它们在趋势图上只该算一个点——否则一个人聊得久，折线就「自动可信」了。</p>
 */
@Service
public class EmotionProfileService {

    private static final Logger log = LoggerFactory.getLogger(EmotionProfileService.class);

    /** 需求 FR3.4 明写的三个窗口，不给任意值：任意窗口会让「近 N 天」这个词在论文里无法复现。 */
    static final Set<Integer> ALLOWED_RANGES = Set.of(7, 30, 90);

    /** BR12 的天数下限。 */
    static final int MIN_TRUSTED_DAYS = 3;

    /** 词云最多取多少项：ECharts 词云超过这个数就糊成一片，也没有信息量。 */
    static final int WORDCLOUD_MAX = 30;

    /** 短于 2 个字的词面不进词云（单字几乎全是连接词与语气词）。 */
    static final int WORD_MIN_CHARS = 2;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final EmotionRecordMapper recordMapper;
    private final DictEmotionEngine emotionEngine;
    private final MindisleProperties properties;

    public EmotionProfileService(EmotionRecordMapper recordMapper, DictEmotionEngine emotionEngine,
            MindisleProperties properties) {
        this.recordMapper = recordMapper;
        this.emotionEngine = emotionEngine;
        this.properties = properties;
    }

    /** 档案页响应（一次给全四图 + 汇总）。 */
    public ProfileView profile(long userId, Integer rawRange) {
        int range = rawRange == null ? 7 : rawRange;
        if (!ALLOWED_RANGES.contains(range)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "回看窗口只能是 7、30 或 90 天。");
        }
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(range - 1L);
        BigDecimal confidentMin = BigDecimal
                .valueOf(properties.getLlm().getEmotionConfidentMin())
                .setScale(3, RoundingMode.HALF_UP);
        List<EmotionGroupRow> rows = recordMapper.groupByDayAndLabel(userId, from, to, confidentMin);
        EmotionPrior prior = emotionEngine.prior();

        // 1) 先按天合并：一天可能有多条（不同标签、不同来源）
        Map<LocalDate, Day> days = new LinkedHashMap<>();
        for (int i = 0; i < range; i++) {
            days.put(from.plusDays(i), new Day());
        }
        int total = 0, confidentTotal = 0, uncertainTotal = 0;
        Map<String, Integer> byLabel = new LinkedHashMap<>();
        Map<String, Integer> bySource = new LinkedHashMap<>();
        for (EmotionGroupRow row : rows) {
            Day day = days.get(row.getRecordDate());
            if (day == null) {
                // 理论上不会发生（SQL 已按窗口过滤），真发生了说明时区口径在两边不一致，要能看见
                log.warn("聚合行落在窗口之外，已忽略 user={} date={} 窗口={}~{}", userId,
                        row.getRecordDate(), from, to);
                continue;
            }
            int cnt = nvl(row.getCnt());
            int confident = nvl(row.getConfidentCnt());
            int uncertain = nvl(row.getUncertainCnt());
            total += cnt;
            confidentTotal += confident;
            uncertainTotal += uncertain;
            day.count += cnt;
            day.confident += confident;
            day.uncertain += uncertain;
            day.checkin += nvl(row.getCheckinCnt());
            if (confident <= 0) {
                continue;
            }
            byLabel.merge(row.getLabel(), confident, Integer::sum);
            // 加权平均强度：分子是「该标签当天的平均强度 × 可信条数」，分母在投影时再除
            double avg = row.getAvgIntensity() == null ? 0d : row.getAvgIntensity().doubleValue();
            day.intensityWeight += avg * confident;
            day.labels.add(new LabelWeight(row.getLabel(), confident, avg));
        }
        bySource.put("checkin", days.values().stream().mapToInt(d -> d.checkin).sum());
        bySource.put("passive", Math.max(0, total - bySource.get("checkin")));

        // 2) 趋势折线：一天一个点，主导标签在可信条数里取，平票按「负面优先」（与词典通道同一规则）
        List<ProfileView.TrendPoint> trend = new ArrayList<>(days.size());
        List<ProfileView.CalendarCell> calendar = new ArrayList<>(days.size());
        List<LocalDate> trustedDates = new ArrayList<>();
        List<LocalDate> checkinDates = new ArrayList<>();
        for (Map.Entry<LocalDate, Day> e : days.entrySet()) {
            Day day = e.getValue();
            if (day.checkin > 0) {
                checkinDates.add(e.getKey());
            }
            if (day.count > 0) {
                trustedDates.add(e.getKey());
            }
            if (day.confident <= 0) {
                // 这天全是不可信行：日历格要有（它表示「这一天有记录」），折线不能有点
                calendar.add(new ProfileView.CalendarCell(DATE.format(e.getKey()), day.count > 0,
                        day.checkin > 0, day.count, null, null, 0, null));
                continue;
            }
            LabelWeight winner = pickDominant(day.labels, prior);
            double avg = round2(day.intensityWeight / day.confident);
            calendar.add(new ProfileView.CalendarCell(DATE.format(e.getKey()), day.count > 0,
                    day.checkin > 0, day.count, winner.label(), prior.zh(winner.label()),
                    prior.valenceTier(winner.label()), (int) Math.round(Math.max(avg, 1d))));
            trend.add(new ProfileView.TrendPoint(DATE.format(e.getKey()), winner.label(),
                    prior.zh(winner.label()), avg, prior.valenceTier(winner.label()),
                    day.count, day.confident, day.uncertain));
        }

        List<ProfileView.LabelCount> distribution = distribute(byLabel, confidentTotal, prior);
        List<ProfileView.LabelCount> sources = sourceSplit(bySource, total);
        List<ProfileView.WordItem> cloud = wordCloud(userId, from, to);
        ProfileView.Streak streak = new ProfileView.Streak(currentStreak(trustedDates, to),
                longestStreak(trustedDates), checkinDates.size(), bySource.get("checkin"));

        // BR12 的判据：有【可信】记录的天数，不是日历格数、也不是总条数
        long trustedDays = days.values().stream().filter(d -> d.confident > 0).count();
        boolean accumulating = trustedDays < MIN_TRUSTED_DAYS;
        log.info("情绪档案 user={} 窗口={}天 记录={} 可信={} 不可信={} 可信天数={} 积累中={} 词云={}",
                userId, range, total, confidentTotal, uncertainTotal, trustedDays, accumulating,
                cloud.size());
        return new ProfileView(range, DATE.format(from), DATE.format(to), accumulating,
                trustedDates.size(), total, confidentTotal, uncertainTotal, trend, distribution,
                cloud, calendar, streak, sources);
    }

    /**
     * 词云：把 {@code text_snippet} 按标点切开再计频。
     *
     * <p>片段有两种形状：被动识别存的是词典命中词面（「压力，难受」），
     * 打卡存的是用户那一句话（清洗截断过）。两者都是「这个人自己看见的词」，
     * 混在一个云里在语义上成立——都在回答「最近什么戳到我」；
     * 但它<b>只出现在本人的档案页</b>（FR3.6：管理端只有群体聚合）。</p>
     */
    private List<ProfileView.WordItem> wordCloud(long userId, LocalDate from, LocalDate to) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (String snippet : recordMapper.listSnippets(userId, from, to)) {
            for (String token : snippet.split("[\\s，,、。;；:：!！?？~~()（）\\[\\]「」\"'·/\\\\-]+")) {
                String word = token.strip();
                if (word.length() < WORD_MIN_CHARS || word.length() > 12) {
                    continue;
                }
                freq.merge(word, 1, Integer::sum);
            }
        }
        return freq.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(WORDCLOUD_MAX)
                .map(e -> new ProfileView.WordItem(e.getKey(), e.getValue()))
                .toList();
    }

    /** 分布饼：按可信条数占比，标签顺序固定用 {@code EmotionPrior.LABELS}，缺的补 0。 */
    private List<ProfileView.LabelCount> distribute(Map<String, Integer> byLabel, int confidentTotal,
            EmotionPrior prior) {
        List<ProfileView.LabelCount> out = new ArrayList<>();
        for (String label : EmotionPrior.LABELS) {
            int count = byLabel.getOrDefault(label, 0);
            if (count <= 0) {
                continue;
            }
            out.add(new ProfileView.LabelCount(label, prior.zh(label), count,
                    ratio(count, confidentTotal)));
        }
        return out;
    }

    /** 来源拆分：主动打卡 vs 被动识别（FR3.1/FR3.2 是两个指标，不能合成一个）。 */
    private List<ProfileView.LabelCount> sourceSplit(Map<String, Integer> bySource, int total) {
        List<ProfileView.LabelCount> out = new ArrayList<>(2);
        for (Map.Entry<String, Integer> e : bySource.entrySet()) {
            out.add(new ProfileView.LabelCount(e.getKey(),
                    "checkin".equals(e.getKey()) ? "主动打卡" : "被动识别", e.getValue(),
                    ratio(e.getValue(), total)));
        }
        return out;
    }

    /** 平票取负面优先——与 {@link EmotionPrior#pickOnTie} 同源，两处「哪个是今天的主导情绪」必须同一个答案。 */
    private static LabelWeight pickDominant(List<LabelWeight> labels, EmotionPrior prior) {
        int max = labels.stream().mapToInt(LabelWeight::count).max().orElse(0);
        List<String> tied = labels.stream().filter(l -> l.count() == max).map(LabelWeight::label).toList();
        String winner = prior.pickOnTie(new ArrayList<>(tied));
        return labels.stream().filter(l -> l.label().equals(winner)).findFirst().orElse(labels.get(0));
    }

    /** 截至今天的连续记录天数（今天没记录就从昨天往回数，与「连续打卡」的常识一致）。 */
    static int currentStreak(List<LocalDate> dates, LocalDate today) {
        Set<LocalDate> set = new LinkedHashSet<>(dates);
        LocalDate cursor = set.contains(today) ? today : today.minusDays(1);
        int streak = 0;
        while (set.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    /** 窗口内最长连续（升序扫一遍）。 */
    static int longestStreak(List<LocalDate> sortedDates) {
        int best = 0, run = 0;
        LocalDate prev = null;
        for (LocalDate d : sortedDates) {
            run = (prev != null && prev.plusDays(1).equals(d)) ? run + 1 : 1;
            best = Math.max(best, run);
            prev = d;
        }
        return best;
    }

    private static double ratio(int part, int whole) {
        return whole <= 0 ? 0d : round2((double) part / whole);
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    /** 聚合中间态：一天的计数。放在服务内部，不进 DTO——它是实现细节，不是接口契约。 */
    private static final class Day {
        int count;
        int confident;
        int uncertain;
        int checkin;
        double intensityWeight;
        final List<LabelWeight> labels = new ArrayList<>();
    }

    /** 「某天某标签」的可信计数与平均强度，用于挑主导标签。 */
    private record LabelWeight(String label, int count, double avgIntensity) {
    }

    /** 供周报复用：把「窗口内一条分组 SQL」的口径固定在这里，两处不会写歪。 */
    List<EmotionGroupRow> grouped(long userId, LocalDate from, LocalDate to, BigDecimal confidentMin) {
        return recordMapper.groupByDayAndLabel(userId, from, to, confidentMin);
    }

    /** 供周报复用：可信阈值（BR12 的那个 0.6），只从配置读一次。 */
    BigDecimal confidentMin() {
        return new BigDecimal(Double.toString(properties.getLlm().getEmotionConfidentMin()))
                .setScale(3, RoundingMode.HALF_UP);
    }

    /** 空值安全的两位小数，周报算平均强度用。 */
    static BigDecimal scale2(BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }
}

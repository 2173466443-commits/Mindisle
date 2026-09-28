package com.mindisle.emotion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.ai.AiUsageService;
import com.mindisle.ai.PromptTemplate;
import com.mindisle.ai.llm.ChatRequest;
import com.mindisle.ai.llm.ChatResult;
import com.mindisle.ai.llm.LlmClient;
import com.mindisle.ai.llm.LlmMessage;
import com.mindisle.common.BizException;
import com.mindisle.emotion.dto.EmotionGroupRow;
import com.mindisle.emotion.dto.WeeklyReportView;
import com.mindisle.entity.WeeklyReport;
import com.mindisle.mapper.EmotionRecordMapper;
import com.mindisle.mapper.WeeklyReportMapper;

/**
 * 情绪周报（任务 T4.20 · 需求 FR3.5 · BR12）。
 *
 * <p><b>「本地统计 + LLM 文案」严格分开</b>：数字全部由 SQL 算出来，模型只负责把数字写成
 * 一段话。这样模型不可用时周报照样出（{@code generator=template}），而模型说的每一句都能
 * 追溯到表格里的某个数。需求 §12 的「不许让模型自己数数」在这里是硬约束：
 * 让它算「打卡天数」，它会给出一个看起来合理但错的整数，而那种错在论文里叫数据造假。</p>
 *
 * <p><b>为什么 {@code GET} 也会写库</b>：FR3.5 是「每周生成」，而周报的数字是「截至今天」的，
 * 两者天然打架 —— 只在周日夜里算一次的话，周一打开看到的是六天前的数。
 * 所以这里保留<b>读时生成 + 当周当日命中缓存</b>：判据是「本周那一行的 {@code updated_at} 是不是今天」，
 * 是就直接返回不再花一次钱；不是就重算 —— 本周还在继续，昨天的周报今天必然少一天。
 * 上周及更早的周报一旦生成不再重算，那是历史。</p>
 *
 * <p><b>与 T4.20 的定时批次是什么关系（这条注释在 v40 之前写的是「本项目还没有定时任务基础设施」，
 * 现在那份基础设施已经有了，所以必须改写）</b>：{@code WeeklyReportJob} 每周日 21:00 主动重算一批，
 * 它调的正是这里的 {@code report(userId, "current", true)}。两条路径<b>互补而不是互相替代</b>：
 * 读时生成服务的是「用户点开就当场给他一份最新的」，批处理服务的是「没人点也有一份存着、
 * 且批次结果可被管理端一次性核对」。它们共用同一个方法，也就共用同一个
 * {@code INSERT ... ON DUPLICATE KEY UPDATE}（见 {@code WeeklyReportMapper}）——
 * 同一周不会因为「白天用户点过一次、夜里任务又跑一次」而撞出 1062 变成 90004。</p>
 *
 * <p><b>四条回落路径</b>：模型不可用 / 预算或熔断拒绝（BizException） / 调用抛错 / 返回空白，
 * 一律回落模板文案，并<strong>照样 upsert 落库</strong>。周报可用性不依赖模型可用性，
 * 这是 FR3.5 的验收口径，也是 Gate4「断网演示」必须成立的事。</p>
 */
@Service
public class WeeklyReportService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportService.class);

    /** 提示词名，同时写进 ai_call_log.prompt_version（论文按它做 Prompt 消融）。 */
    static final String REPORT_PROMPT = "weekly_report_v1";

    /** 数据点不足时的结论，与 BR12、与提示词第 4 条硬要求三处口径一致。 */
    static final String ACCUMULATING_TEXT =
            "这一周的数据还在积累，先不急着看趋势。等记录满三天，这里的曲线才有意义。";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EmotionRecordMapper recordMapper;
    private final WeeklyReportMapper reportMapper;
    private final EmotionProfileService profileService;
    private final DictEmotionEngine emotionEngine;
    private final LlmClient llm;
    private final PromptTemplate prompts;
    private final AiUsageService aiUsageService;
    private final ObjectMapper mapper;

    public WeeklyReportService(EmotionRecordMapper recordMapper, WeeklyReportMapper reportMapper,
            EmotionProfileService profileService, DictEmotionEngine emotionEngine, LlmClient llm,
            PromptTemplate prompts, AiUsageService aiUsageService, ObjectMapper mapper) {
        this.recordMapper = recordMapper;
        this.reportMapper = reportMapper;
        this.profileService = profileService;
        this.emotionEngine = emotionEngine;
        this.llm = llm;
        this.prompts = prompts;
        this.aiUsageService = aiUsageService;
        this.mapper = mapper;
    }

    /**
     * 取一份周报。
     *
     * @param week    {@code current}（默认）或 {@code last}
     * @param refresh true = 强制重算（界面上的「再算一次」）
     */
    public WeeklyReportView report(long userId, String week, boolean refresh) {
        LocalDate monday = weekStart(week);
        LocalDate sunday = monday.plusDays(6);
        WeeklyReport stored = reportMapper.findByUserWeek(userId, monday);
        boolean historical = monday.isBefore(weekStart("current"));
        if (stored != null && !refresh && (historical || isToday(stored.getUpdatedAt()))) {
            log.info("周报命中已存行 user={} week={} 生成于{} 历史周={}", userId, monday,
                    stored.getUpdatedAt(), historical);
            return toView(stored);
        }
        return generate(userId, monday, sunday);
    }

    /** 算统计 → 生成文案 → upsert → 回读。 */
    private WeeklyReportView generate(long userId, LocalDate monday, LocalDate sunday) {
        BigDecimal confidentMin = profileService.confidentMin();
        Stats thisWeek = stats(userId, monday, sunday, confidentMin);
        Stats lastWeek = stats(userId, monday.minusDays(7), sunday.minusDays(7), confidentMin);
        Double trendDelta = lastWeek.totalConfident > 0 && thisWeek.totalConfident > 0
                ? round2(thisWeek.meanValence - lastWeek.meanValence)
                : null;
        String summary;
        String generator;
        if (thisWeek.accumulating()) {
            // 数据不足时不调模型：提示词第 4 条本来就要求它「不做趋势判断」，
            // 花一次钱买一句模板也会说的话，不划算（需求 §12 省钱三招）
            summary = ACCUMULATING_TEXT;
            generator = "template";
        } else {
            String out = llmSummary(userId, monday, sunday, thisWeek, trendDelta);
            generator = out.startsWith(LLM_MARK) ? "llm" : "template";
            summary = out.startsWith(LLM_MARK) ? out.substring(LLM_MARK.length()) : out;
        }
        reportMapper.upsert(userId, monday, sunday, thisWeek.checkinDays, thisWeek.totalCount,
                thisWeek.dominant, decimal2(thisWeek.totalConfident > 0 ? thisWeek.avgIntensity : 0d),
                decimal3(thisWeek.totalConfident > 0 ? thisWeek.positiveRatio : 0d),
                decimal2(trendDelta == null ? 0d : trendDelta), toJson(thisWeek), summary, generator);
        WeeklyReport row = reportMapper.findByUserWeek(userId, monday);
        log.info("周报生成 user={} week={} 打卡{}天 记录{}条 可信{}条 主导={} 均强={} 正向占比={} 方式={}",
                userId, monday, thisWeek.checkinDays, thisWeek.totalCount, thisWeek.totalConfident,
                thisWeek.dominant, thisWeek.avgIntensity, thisWeek.positiveRatio, generator);
        return toView(row == null ? fallbackRow(userId, monday, sunday, thisWeek, summary, generator) : row);
    }

    /** {@code llm.chat} 成功时的返回值前缀；调用方据此决定 generator，失败一律不带它。 */
    private static final String LLM_MARK = "[llm]";

    /** 极端情况（写完仍读不到，例如并发下被逻辑删）下的兜底视图：让界面有东西可画，而不是 90004。 */
    private WeeklyReport fallbackRow(long userId, LocalDate monday, LocalDate sunday, Stats s,
            String summary, String generator) {
        WeeklyReport row = new WeeklyReport();
        row.setUserId(userId);
        row.setWeekStart(monday);
        row.setWeekEnd(sunday);
        row.setCheckinDays(s.checkinDays);
        row.setRecordCnt(s.totalCount);
        row.setDominantLabel(s.dominant);
        row.setAvgIntensity(decimal2(s.totalConfident > 0 ? s.avgIntensity : 0d));
        row.setPositiveRatio(decimal3(s.totalConfident > 0 ? s.positiveRatio : 0d));
        row.setSummaryText(summary);
        row.setGenerator(generator);
        row.setInsight(toJson(s));
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    /** LLM 文案。成功返回带 {@link #LLM_MARK} 前缀的正文，四条失败路径返回不带前缀的模板文案。 */
    private String llmSummary(long userId, LocalDate monday, LocalDate sunday, Stats s, Double delta) {
        if (!llm.available()) {
            log.info("模型不可用，周报走统计模板 user={}", userId);
            return templateSummary(s, delta);
        }
        String prompt = prompts.render(REPORT_PROMPT, Map.of(
                "weekStart", DATE.format(monday),
                "weekEnd", DATE.format(sunday),
                "checkinDays", String.valueOf(s.checkinDays),
                "recordCnt", String.valueOf(s.totalCount),
                "dominantLabel", s.dominant == null ? "无" : emotionEngine.prior().zh(s.dominant),
                "avgIntensity", fmt(s.avgIntensity),
                "positiveRatio", percent(s.positiveRatio),
                "trendDelta", delta == null ? "无上周数据" : fmt(delta),
                "counts", countsText(s)));
        ChatRequest request = ChatRequest.of("report", List.of(LlmMessage.user(prompt)), REPORT_PROMPT,
                userId).temperature(0.6d).maxTokens(260);
        long start = System.currentTimeMillis();
        try {
            aiUsageService.guardBeforeCall(userId);
            ChatResult result = llm.chat(request);
            String text = result.text() == null ? "" : result.text().strip();
            aiUsageService.recordSuccess(userId, request, result, null);
            if (text.isEmpty()) {
                log.warn("周报文案为空，回落模板 user={}", userId);
                return templateSummary(s, delta);
            }
            return LLM_MARK + cut(text, 1000);
        } catch (BizException e) {
            // 预算/熔断是被设计出来的拒绝，不是故障：记一条降级日志，仍照常出周报
            aiUsageService.recordDegraded(userId, "report", REPORT_PROMPT, AiUsageService.MODEL_STAT_TEMPLATE,
                    "code=" + e.getErrorCode().getCode(), null);
            log.warn("周报文案降级 user={} 原因={}（{}）", userId, e.getMessage(),
                    e.getErrorCode().getCode());
            return templateSummary(s, delta);
        } catch (Exception e) {
            aiUsageService.recordFailure(userId, request, e, System.currentTimeMillis() - start, null);
            log.warn("周报文案生成失败，回落统计模板 user={} err={}", userId, e.toString());
            return templateSummary(s, delta);
        }
    }

    /**
     * 纯统计模板。断网演示、预算耗尽、模型返空三种情况都靠它保证「周报一定在」。
     *
     * <p>句子结构刻意固定：论文要用它做「LLM 文案 vs 模板文案」的主观评价对照，
     * 而对照实验要求除文案来源外什么都不变。负向为主时末尾那句转介提示是提示词第 3 条的
     * 同款要求——模板侧也必须写，否则「降级的那批用户」拿到的安全口径比正常用户还低。</p>
     */
    static String templateSummary(Stats s, Double delta) {
        StringBuilder sb = new StringBuilder();
        sb.append("这一周你有 ").append(s.checkinDays).append(" 天留下了心情记录，一共 ")
                .append(s.totalCount).append(" 条。");
        if (s.dominant != null) {
            sb.append("出现最多的是").append(s.dominantZh).append("。");
        }
        if (s.totalConfident > 0) {
            sb.append("平均强度 ").append(fmt(s.avgIntensity))
                    .append("，正向情绪约占 ").append(percent(s.positiveRatio)).append("。");
        }
        if (delta != null) {
            sb.append(delta >= 0 ? "整体比上周轻松一些。" : "整体比上周吃力一些，这值得被看见。");
        } else {
            sb.append("上周还没有可比的数据。");
        }
        if (s.negativeDominant) {
            sb.append("如果这种感觉持续两周以上并且影响到吃饭睡觉上课，")
                    .append("找学校心理健康中心聊聊会更有把握一些。");
        }
        return sb.toString();
    }

    /**
     * 一个时间窗的统计。
     *
     * <p>包级可见 + 字段可读：单测直接 {@code new} 一个塞进 {@link #templateSummary}，
     * 不必为了测四句话去连数据库。{@code positiveRatio} 用「可信条数」当分母而不是总条数：
     * FR3.2 说不可信的不进趋势统计，占比也是统计。</p>
     */
    static final class Stats {
        int checkinDays;
        int totalCount;
        int totalConfident;
        int positiveCount;
        int trustedDays;
        String dominant;
        String dominantZh;
        double avgIntensity;
        double positiveRatio;
        double meanValence;
        boolean negativeDominant;
        final Map<String, Integer> counts = new LinkedHashMap<>();

        /** BR12：有可信记录的天数不足 3 天。 */
        boolean accumulating() {
            return trustedDays < EmotionProfileService.MIN_TRUSTED_DAYS;
        }
    }

    /** 一个窗口的统计口径（打卡天数、条数、可信条数、主导情绪、平均强度、正向占比、平均效价）。 */
    Stats stats(long userId, LocalDate from, LocalDate to, BigDecimal confidentMin) {
        Stats s = new Stats();
        s.checkinDays = recordMapper.countCheckinDays(userId, from, to);
        s.totalCount = recordMapper.countBetween(userId, from, to);
        double intensityWeighted = 0d;
        double valenceWeighted = 0d;
        int confidentDays = 0;
        Map<LocalDate, Integer> confidentByDay = new LinkedHashMap<>();
        EmotionPrior prior = emotionEngine.prior();
        for (EmotionGroupRow row : profileService.grouped(userId, from, to, confidentMin)) {
            int confident = row.getConfidentCnt() == null ? 0 : row.getConfidentCnt();
            s.totalConfident += confident;
            if (confident > 0) {
                confidentByDay.merge(row.getRecordDate(), confident, Integer::sum);
            }
            if (confident <= 0) {
                continue;
            }
            s.counts.merge(row.getLabel(), confident, Integer::sum);
            intensityWeighted += (row.getAvgIntensity() == null ? 0d
                    : row.getAvgIntensity().doubleValue()) * confident;
            valenceWeighted += prior.valenceTier(row.getLabel()) * confident;
        }
        for (Integer v : confidentByDay.values()) {
            if (v != null && v > 0) {
                confidentDays++;
            }
        }
        s.trustedDays = confidentDays;
        if (s.totalConfident > 0) {
            s.avgIntensity = round2(intensityWeighted / s.totalConfident);
            s.meanValence = round2(valenceWeighted / s.totalConfident);
            s.positiveCount = s.counts.entrySet().stream()
                    .filter(e -> prior.valenceTier(e.getKey()) > 0)
                    .mapToInt(Map.Entry::getValue).sum();
            s.positiveRatio = round2((double) s.positiveCount / s.totalConfident);
            int negative = s.counts.entrySet().stream()
                    .filter(e -> prior.valenceTier(e.getKey()) < 0)
                    .mapToInt(Map.Entry::getValue).sum();
            s.negativeDominant = negative * 2 > s.totalConfident;
            s.dominant = topLabel(s.counts, prior);
            s.dominantZh = s.dominant == null ? null : prior.zh(s.dominant);
        }
        return s;
    }

    /** 主导标签：条数最多者；平票走 {@code pickOnTie}（负面优先），与词典通道同一规则。 */
    private static String topLabel(Map<String, Integer> counts, EmotionPrior prior) {
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (max <= 0) {
            return null;
        }
        List<String> tied = counts.entrySet().stream().filter(e -> e.getValue() == max)
                .map(Map.Entry::getKey).toList();
        return prior.pickOnTie(new java.util.ArrayList<>(tied));
    }

    /** 视图映射。{@code counts}/{@code trustedDays} 从 insight JSON 还原；解析失败不报错，数字丢了还有正文。 */
    WeeklyReportView toView(WeeklyReport row) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int trustedDays = 0;
        if (row.getInsight() != null && !row.getInsight().isBlank()) {
            try {
                JsonNode root = mapper.readTree(row.getInsight());
                JsonNode node = root.path("counts");
                node.fields().forEachRemaining(e -> counts.put(e.getKey(), e.getValue().asInt()));
                trustedDays = root.path("trustedDays").asInt(0);
            } catch (Exception e) {
                log.warn("周报 insight 解析失败，退回只展示正文 report={} err={}", row.getId(), e.toString());
            }
        }
        EmotionPrior prior = emotionEngine.prior();
        return new WeeklyReportView(row.getId(), DATE.format(row.getWeekStart()),
                DATE.format(row.getWeekEnd()), nvl(row.getCheckinDays()), nvl(row.getRecordCnt()),
                row.getDominantLabel(),
                row.getDominantLabel() == null ? null : prior.zh(row.getDominantLabel()),
                row.getAvgIntensity() == null ? null : row.getAvgIntensity().doubleValue(),
                row.getPositiveRatio() == null ? 0d : row.getPositiveRatio().doubleValue(),
                row.getTrendDelta() == null ? null : row.getTrendDelta().doubleValue(),
                counts, row.getSummaryText(), row.getGenerator(),
                trustedDays < EmotionProfileService.MIN_TRUSTED_DAYS,
                row.getCreatedAt() == null ? null : TS.format(row.getCreatedAt()),
                // 分享状态一起给出去（T4.20 ③）：按钮文案要能反映「已经分享过」，而不是让人点一次
                // 才知道。这里的 row 是 upsert 之后回读的那一行，所以这两个值与表里必然同源。
                Integer.valueOf(1).equals(row.getSharedFlag()), row.getSharedPostId());
    }

    /**
     * ISO 周起点（周一）。
     *
     * <p>{@code last} 在演示时最有用：当周才打了两天卡，看上周才知道周报界面不是只有空态。
     * 未知值回当周而不是报错——这是一个只读参数，为一个拼写错误弹红框不值。</p>
     */
    static LocalDate weekStart(String week) {
        LocalDate current = LocalDate.now().with(DayOfWeek.MONDAY);
        if (week != null && "last".equalsIgnoreCase(week.strip().toLowerCase(Locale.ROOT))) {
            return current.minusWeeks(1);
        }
        return current;
    }

    private static boolean isToday(LocalDateTime time) {
        return time != null && time.toLocalDate().isEqual(LocalDate.now());
    }

    private String toJson(Stats s) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("counts", s.counts);
            root.put("trustedDays", s.trustedDays);
            root.put("meanValence", s.meanValence);
            root.put("negativeDominant", s.negativeDominant);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("周报 insight 序列化失败，落一个最小 JSON：{}", e.toString());
            return "{}";
        }
    }

    private String countsText(Stats s) {
        if (s.counts.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        s.counts.forEach((k, v) -> sb.append(emotionEngine.prior().zh(k)).append(v).append("条、"));
        return sb.substring(0, sb.length() - 1);
    }

    private static BigDecimal decimal2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal3(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP);
    }

    private static String fmt(double v) {
        return decimal2(v).toPlainString();
    }

    private static String percent(double ratio) {
        return Math.round(ratio * 100d) + "%";
    }

    private static double round2(double v) {
        return decimal2(v).doubleValue();
    }

    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    /** 按码点截断，避免劈开 emoji（模型爱在结尾加表情）。 */
    static String cut(String text, int maxCodePoints) {
        String t = text.strip();
        return t.codePointCount(0, t.length()) <= maxCodePoints ? t
                : t.substring(0, t.offsetByCodePoints(0, maxCodePoints));
    }
}

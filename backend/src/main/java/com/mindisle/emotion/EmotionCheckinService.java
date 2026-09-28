package com.mindisle.emotion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mindisle.ai.SafetyGuard;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.emotion.dto.CheckinRequest;
import com.mindisle.emotion.dto.CheckinView;
import com.mindisle.entity.EmotionRecord;
import com.mindisle.mapper.EmotionRecordMapper;
import com.mindisle.mapper.UserConsentMapper;

/**
 * 情绪打卡（任务 T4.9 · 需求 FR3.1 · 界面 U3「今天感觉怎么样」）。
 *
 * <p><b>打卡是「主动那一侧」，与被动识别共用同一张表</b>：{@code ChatService} 每轮对话
 * 已经往 {@code emotion_record} 写一行 {@code source='chat'}，这里写的是 {@code source='checkin'}。
 * 一张表两个来源的好处是趋势/周报天然合并；代价是「打卡率」与「识别覆盖率」必须靠
 * {@code source} 分开算，否则两个完全不同的指标会被混成一个数——所以本类写进去的
 * {@code source}/{@code channel} 两个字段是要害，不是装饰。</p>
 *
 * <p><b>「每日一次」与「不可覆盖历史」是一对矛盾</b>（FR3.1 原文：可补卡，不可重复覆盖历史，
 * 新版本追加）。这里的解法是：<b>同一天再打卡就追加新行</b>，{@code id} 递增就是版本序列，
 * 而「打卡天数」一律用 {@code COUNT(DISTINCT record_date)} 统计，
 * 于是重复打卡既不会丢历史，也不会把「连续 7 天」刷成「连续 7 次」。
 * 响应里的 {@code appended=true} 让界面能如实说一句「今天已经打过卡了，这条是新增的」。</p>
 *
 * <p><b>{@code confidence=1.000}、{@code channel='manual'}</b>：自评不存在识别误差，
 * BR12 的「&lt;0.6 不计入趋势」对它天然不成立。若给打卡塞一个 0.8，
 * 用户改一次标签就改变一条记录的统计归属，那是把「我说我难过」降级成「系统猜我难过」。</p>
 *
 * <p><b>同意闸在最前</b>：手册 §7.1 行 4.14 要求「撤回后禁写新情绪数据」。
 * 情绪记录是敏感个人信息（NFR8），未授予 SENSITIVE_INFO 时这一句都不该落库。</p>
 */
@Service
public class EmotionCheckinService {

    private static final Logger log = LoggerFactory.getLogger(EmotionCheckinService.class);

    /** 与 sql/03_emotion.sql 的 emotion_record.source ENUM 对齐。 */
    static final String SOURCE_CHECKIN = "checkin";

    /** 打卡是用户自己填的，识别通道记 manual（消融实验里它是「上界」，不参与词典/LLM 对比）。 */
    static final String CHANNEL_MANUAL = "manual";

    /** 写进 model_version：自评没有模型，但这一列 NOT NULL，且论文要能按「数据是问出来的」分组。 */
    static final String MODEL_CHECKIN_V1 = "checkin-v1";

    /** text_snippet 列宽 200；与危机证据、词云共用同一列，所以截断口径也要一致。 */
    static final int NOTE_MAX_CHARS = 200;

    /** 补卡最多往前 30 天。需求只说「可补卡」没说多久，30 天取自周报口径（一个月≈四周）。 */
    static final int BACKFILL_MAX_DAYS = 30;

    /** 睡眠档位只有 0-3 四档（DDL 里没有这一列的枚举，用取值域夹住，避免脏值进周报）。 */
    static final int SLEEP_MIN = 0, SLEEP_MAX = 3;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EmotionRecordMapper recordMapper;
    private final UserConsentMapper consentMapper;
    private final DictEmotionEngine emotionEngine;
    private final SafetyGuard safetyGuard;

    public EmotionCheckinService(EmotionRecordMapper recordMapper, UserConsentMapper consentMapper,
            DictEmotionEngine emotionEngine, SafetyGuard safetyGuard) {
        this.recordMapper = recordMapper;
        this.consentMapper = consentMapper;
        this.emotionEngine = emotionEngine;
        this.safetyGuard = safetyGuard;
    }

    /**
     * 写一次打卡。
     *
     * @throws BizException 20005 未授予敏感信息处理同意；10001 参数不合法
     */
    public CheckinView checkin(long userId, CheckinRequest req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "打卡表单是空的。");
        }
        if (!consentMapper.isGranted(userId, CONSENT_SENSITIVE_INFO)) {
            throw new BizException(ErrorCode.SENSITIVE_CONSENT_REQUIRED,
                    "情绪记录属于敏感个人信息，需要你先在「隐私与同意」里单独授权。");
        }
        EmotionPrior prior = emotionEngine.prior();
        String label = req.emotion() == null ? "" : req.emotion().trim().toLowerCase(java.util.Locale.ROOT);
        if (!prior.isKnown(label)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "情绪标签只能是 " + String.join("/", EmotionPrior.LABELS) + " 之一。");
        }
        Integer intensity = req.intensity();
        if (intensity == null || intensity < 1 || intensity > 5) {
            throw new BizException(ErrorCode.PARAM_INVALID, "强度只能是 1 到 5。");
        }
        Integer sleep = req.sleepBucket();
        if (sleep != null && (sleep < SLEEP_MIN || sleep > SLEEP_MAX)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "睡眠只有四档可选。");
        }
        LocalDate date = parseDate(req.recordDate());

        // 一句话先过输入侧清洗：零宽字符不影响阅读，但会让「后面按文本做的一切」口径失真
        String note = "";
        if (req.note() != null && !req.note().isBlank()) {
            note = cut(safetyGuard.sanitizeUser(req.note()).text(), NOTE_MAX_CHARS);
        }

        EmotionRecord previous = recordMapper.findCheckinOn(userId, date);
        EmotionRecord row = new EmotionRecord();
        row.setUserId(userId);
        row.setSource(SOURCE_CHECKIN);
        row.setRefId(null);
        row.setTextSnippet(note);
        row.setLabel(label);
        row.setIntensity(intensity);
        row.setValence(prior.valenceTier(label));
        row.setConfidence(new BigDecimal("1.000"));
        row.setChannel(CHANNEL_MANUAL);
        row.setModelVersion(MODEL_CHECKIN_V1);
        row.setRecordDate(date);
        row.setSleepBucket(sleep);
        row.setCreatedAt(LocalDateTime.now());
        recordMapper.insert(row);
        log.info("用户 {} 打卡 {} {} 强度{} 睡眠档={} noteLen={} 追加={} 记录#{}", userId, date, label,
                intensity, sleep, note.length(), previous != null, row.getId());
        return toView(row, previous != null);
    }

    /** 近 N 天的打卡流水（U3 的列表；不含被动识别行，理由见类注释）。 */
    public List<CheckinView> recent(long userId, int days) {
        int span = Math.max(1, Math.min(days, 365));
        LocalDate to = LocalDate.now();
        List<EmotionRecord> rows = recordMapper.listCheckinsBetween(userId, to.minusDays(span - 1L), to);
        List<CheckinView> views = new ArrayList<>(rows.size());
        for (EmotionRecord row : rows) {
            views.add(toView(row, false));
        }
        return views;
    }

    /** 打卡行 → 视图。{@code appended} 只在写入响应里有意义，列表里恒为 false。 */
    CheckinView toView(EmotionRecord row, boolean appended) {
        return new CheckinView(row.getId(), DATE.format(row.getRecordDate()), row.getLabel(),
                emotionEngine.prior().zh(row.getLabel()), row.getIntensity() == null ? 0 : row.getIntensity(),
                row.getValence() == null ? 0 : row.getValence(), row.getSleepBucket(), row.getTextSnippet(),
                row.getSource(), row.getChannel(), appended,
                row.getCreatedAt() == null ? null : TS.format(row.getCreatedAt()));
    }

    /**
     * 解析补卡日期：空 = 今天；不接受未来；不接受 30 天以前。
     *
     * <p>「未来」被拒是必须的：{@code record_date} 是趋势与周报的分组键，
     * 一条未来的打卡会让折线右端凭空多出一个还没有的格子。</p>
     */
    static LocalDate parseDate(String raw) {
        LocalDate today = LocalDate.now();
        if (raw == null || raw.isBlank()) {
            return today;
        }
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(raw.trim(), DATE);
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "日期格式要是 yyyy-MM-dd。");
        }
        if (parsed.isAfter(today)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "还不能给未来的自己打卡。");
        }
        if (parsed.isBefore(today.minusDays(BACKFILL_MAX_DAYS))) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "补卡最多往前 " + BACKFILL_MAX_DAYS + " 天。");
        }
        return parsed;
    }

    /** 按码点截断：代理对（emoji）劈开会变成非法字符，而打卡那一句话里出现 emoji 是常态。 */
    static String cut(String text, int maxCodePoints) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.codePointCount(0, trimmed.length()) <= maxCodePoints
                ? trimmed
                : trimmed.substring(0, trimmed.offsetByCodePoints(0, maxCodePoints));
    }

    /** 与 {@code ChatService} 同一常量值：同意事项类型必须逐字一致，否则两处判定会分叉。 */
    private static final String CONSENT_SENSITIVE_INFO = "SENSITIVE_INFO";
}

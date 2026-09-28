package com.mindisle.ai;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mindisle.ai.llm.ChatRequest;
import com.mindisle.ai.llm.ChatResult;
import com.mindisle.ai.llm.LlmException;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.AiCallLog;
import com.mindisle.mapper.AiCallLogMapper;

/**
 * AI 用量记账、预算闸与熔断器（任务 T4.12 预算 + T4.13 熔断，需求 FR2.7）。
 *
 * <p><b>每一次调用都要落一行 ai_call_log，失败也要落</b>。只记成功会让两件事失真：
 * 熔断器的「连续失败次数」数不出来；成本账看着很漂亮但实际钱已经花出去了
 * （上游 429 之前那几次尝试的 token 是真实计费的）。</p>
 *
 * <p><b>熔断为什么放在进程内而不上 Redis/Resilience4j</b>：单实例部署（手册 §5.1 的
 * 部署形态），跨实例共享状态目前没有消费方。用一个 {@code volatile} 的打开时刻
 * 加一条「最近 N 次成败序列」的数据库查询，就能同时满足「立刻止血」和「重启后不装死」。
 * Redis 起不来时这套逻辑照样工作，这是刻意的。</p>
 *
 * <p><b>半开态</b>：熔断打开 {@code circuitHalfOpenMs} 之后放行一次真实调用去试探，
 * 成功就闭合、失败就重新打开。不试探的话，一次 5 分钟的上游抖动会让 AI 永久关闭到重启。</p>
 */
@Service
public class AiUsageService {

    private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);
    /** ② 只有「回复真的来自离线共情话术库」才准用这个值。 */
    public static final String MODEL_OFFLINE_BANK = "offline-empathy-bank";

    /** ③ 周报降级到纯统计模板：它没调模型，但也跟共情话术库无关。 */
    public static final String MODEL_STAT_TEMPLATE = "stat-template";

    /** model 传空时的占位，用来让调用方的疏漏在报表里看得见。 */
    public static final String MODEL_UNKNOWN = "degraded-unknown";


    private final AiCallLogMapper callLogMapper;
    private final MindisleProperties properties;

    /** 熔断打开的时刻（毫秒），0 表示闭合。volatile 足够：这里的竞态只影响「多放行一两个请求」。 */
    private volatile long openedAtMs;
    /** 半开态下只允许一个探针在飞，免得试探本身把上游打爆。 */
    private final AtomicLong probing = new AtomicLong();

    private final AtomicLong localSuccesses = new AtomicLong();
    private final AtomicLong localFailures = new AtomicLong();

    public AiUsageService(AiCallLogMapper callLogMapper, MindisleProperties properties) {
        this.callLogMapper = callLogMapper;
        this.properties = properties;
    }

    /**
     * 调用前的闸门：预算 + 熔断。
     *
     * <p>顺序是刻意的 —— 先看熔断再看预算。上游已经坏了的时候，
     * 报「AI 暂时不可用」比报「你额度用完了」诚实，也不会误导用户去做无意义的等待。</p>
     */
    public void guardBeforeCall(Long userId) {
        MindisleProperties.Llm llm = properties.getLlm();
        MindisleProperties.AiBudget budget = properties.getAiBudget();
        if (isCircuitOpen(llm)) {
            throw new BizException(ErrorCode.AI_UNAVAILABLE, "AI 服务暂时不可用，已自动降级，请稍后再试");
        }
        LocalDateTime midnight = LocalDate.now().atStartOfDay();
        if (userId != null) {
            long used = callLogMapper.sumTokensSince(userId, midnight);
            if (used >= budget.getUserDailyTokens()) {
                throw new BizException(ErrorCode.AI_BUDGET_EXCEEDED,
                        "今日 AI 用量已达上限（" + used + "/" + budget.getUserDailyTokens() + " tokens），明天会自动恢复");
            }
            long warnAt = (long) (budget.getUserDailyTokens() * budget.getAlertRatio());
            if (used >= warnAt) {
                int percent = (int) Math.round(used * 100.0d / budget.getUserDailyTokens());
                log.warn("用户 {} 的 AI 用量已达日预算告警线：{}%（{}/{} tokens）", userId, percent,
                        used, budget.getUserDailyTokens());
            }
        }
        long globalCost = callLogMapper.sumCostCentSince(midnight);
        if (globalCost >= budget.getGlobalDailyCostCent()) {
            throw new BizException(ErrorCode.AI_BUDGET_EXCEEDED,
                    "平台今日 AI 预算已用尽（" + globalCost + "/" + budget.getGlobalDailyCostCent() + " 分），已自动降级");
        }
    }

    /** 熔断是否处于打开态（含半开放行的判定与抢占）。 */
    private boolean isCircuitOpen(MindisleProperties.Llm llm) {
        long opened = openedAtMs;
        if (opened == 0L) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - opened;
        if (elapsed < llm.getCircuitHalfOpenMs()) {
            return true;
        }
        // 到了半开窗口：抢探针名额，抢到的这次放行去试探，其余继续拒绝
        if (probing.compareAndSet(0L, System.currentTimeMillis())) {
            log.info("熔断进入半开，放行一个探针请求（此前打开了 {} ms）", elapsed);
            return false;
        }
        return true;
    }

    /**
     * 成功收尾：落账 + 闭合熔断。
     *
     * <p>{@code degraded=true} 的离线兜底结果<b>不该</b>走到这里 —— 那是没调模型的情况，
     * 由调用方用 {@link #recordDegraded} 单独记，避免把「没花钱」记成「花了 0 token 的成功调用」
     * 从而稀释平均 TTFT。</p>
     */
    public void recordSuccess(Long userId, ChatRequest request, ChatResult result, String traceId) {
        int cost = costCent(result);
        AiCallLog row = base(userId, request, traceId);
        row.setTokensIn(result.tokensIn());
        row.setTokensOut(result.tokensOut());
        row.setCostCent(cost);
        row.setFirstTokenMs((int) Math.min(Integer.MAX_VALUE, Math.max(0, result.firstTokenMs())));
        row.setLatencyMs((int) Math.min(Integer.MAX_VALUE, Math.max(0, result.latencyMs())));
        row.setSuccess(1);
        row.setModel(result.model() == null ? request.model() : result.model());
        callLogMapper.insert(row);
        localSuccesses.incrementAndGet();
        if (openedAtMs != 0L) {
            log.info("一次成功调用使熔断闭合（此前打开了 {} ms）", System.currentTimeMillis() - openedAtMs);
            openedAtMs = 0L;
            probing.set(0L);
        }
    }

    /** 失败收尾：落账（success=0，tokens 未知记 0）并按类型决定是否推进熔断。 */
    public void recordFailure(Long userId, ChatRequest request, Throwable error, long latencyMs, String traceId) {
        AiCallLog row = base(userId, request, traceId);
        row.setTokensIn(0);
        row.setTokensOut(0);
        row.setCostCent(0);
        row.setFirstTokenMs(0);
        row.setLatencyMs((int) Math.min(Integer.MAX_VALUE, Math.max(0, latencyMs)));
        row.setSuccess(0);
        row.setError(shortError(error));
        callLogMapper.insert(row);
        localFailures.incrementAndGet();
        boolean outage = !(error instanceof LlmException le) || le.countsAsOutage();
        if (!outage) {
            // 拒答/内容过滤不是服务不可用，计入会让正常服务被自己的熔断器打死
            return;
        }
        int threshold = properties.getLlm().getCircuitFailThreshold();
        int consecutive = consecutiveFailures();
        if (consecutive >= threshold && openedAtMs == 0L) {
            openedAtMs = System.currentTimeMillis();
            log.error("连续 {} 次 AI 调用失败，熔断打开 {} ms；期间对话走离线话术库",
                    consecutive, properties.getLlm().getCircuitHalfOpenMs());
        }
    }

    /**
     * 降级 / 中止也要留一行痕迹：success=0、error 前缀 {@code DEGRADED:}，<b>但 model 由调用方传</b>。
     *
     * <p><b>为什么 model 是形参而不是常量（v1.2.6 修的真 bug）</b>：本方法有三个语义完全不同的调用方 ——
     * ① 用户按「停止生成」（{@code ChatService} 的 cancel 分支，上游其实连通了、token 也花了）；
     * ② 上游不可用，回复改由离线共情话术库给出（{@code deliverOffline}）；
     * ③ 周报被预算闸 / 熔断挡下，改用纯统计模板（{@code WeeklyReportService}）。
     * 早先这里无条件写 {@link #MODEL_OFFLINE_BANK}，于是 {@code ai_call_log} 里 13 行「看着像降级」
     * 的记录其实全是 ①，而 ② 一次都没真跑过（直到去业务表数 {@code chat_message.degraded} 全为 0
     * 才戳穿）。<b>日志表的字段必须能排他</b>：一个值对应两种语义，它在报表侧就是谎言发生器。</p>
     *
     * @param model 真正产出这次回复的模型 / 话术库 / 模板标识，必须按调用方传；空则记
     *              {@link #MODEL_UNKNOWN}，让「忘了传」在报表里显形而不是冒充 ②
     */
    public void recordDegraded(Long userId, String scene, String promptVersion, String model,
            String reason, String traceId) {
        AiCallLog row = new AiCallLog();
        row.setUserId(userId);
        row.setScene(scene == null ? "chat" : scene);
        row.setModel(model == null || model.isBlank() ? MODEL_UNKNOWN : model.trim());
        row.setPromptVersion(promptVersion);
        row.setTokensIn(0);
        row.setTokensOut(0);
        row.setCostCent(0);
        row.setFirstTokenMs(0);
        row.setLatencyMs(0);
        row.setSuccess(0);
        row.setError("DEGRADED: " + shortString(reason));
        row.setTraceId(traceId);
        callLogMapper.insert(row);
    }

    /**
     * 最近一次真实调用的平均首字延迟，给「性能达标了吗」提供可查询的现量（NFR2）。
     *
     * <p>读的是数据库而不是内存计数器：内存值一重启就归零，答辩现场「你实测 TTFT 多少」
     * 这个问题需要一个能查出来的数。</p>
     */
    public long averageFirstTokenMsSince(LocalDateTime from) {
        return callLogMapper.avgFirstTokenMsSince(from);
    }

    /** 熔断状态快照，给管理端/健康检查用。 */
    public Status status() {
        return new Status(openedAtMs != 0L, consecutiveFailures(),
                localSuccesses.get(), localFailures.get(),
                LocalDate.now().atStartOfDay());
    }

    public void resetCircuit() {
        openedAtMs = 0L;
        probing.set(0L);
    }

    /** 单价换算成「分」，向上取整到分以内用四舍六入，成本极小，宁可高估。 */
    private int costCent(ChatResult result) {
        MindisleProperties.Llm llm = properties.getLlm();
        double raw = result.tokensIn() / 1000.0d * llm.getPriceInCentPer1k()
                + result.tokensOut() / 1000.0d * llm.getPriceOutCentPer1k();
        return (int) Math.round(raw);
    }

    /**
     * 连续失败次数：优先数内存里最近的序列，内存空了（刚重启）回落到数据库。
     *
     * <p>回落这一步是「重启后不装死」的另一半 —— 但更重要的是它让熔断判定
     * 不依赖于「同一个进程从头活到尾」这个假设。</p>
     */
    private int consecutiveFailures() {
        List<Integer> recent = callLogMapper.listRecentSuccessFlags(
                LocalDateTime.now().minusHours(6));
        if (recent.isEmpty()) {
            return 0;
        }
        int streak = 0;
        for (Integer flag : recent) {
            if (flag != null && flag == 1) {
                break;
            }
            streak++;
        }
        return streak;
    }

    private AiCallLog base(Long userId, ChatRequest request, String traceId) {
        AiCallLog row = new AiCallLog();
        row.setScene(request.scene());
        row.setPromptVersion(request.promptVersion());
        row.setUserId(userId);
        row.setModel(request.model() == null ? properties.getLlm().getModel() : request.model());
        row.setTraceId(traceId);
        return row;
    }

    private static String shortError(Throwable error) {
        if (error instanceof LlmException le) {
            return shortString(le.summary());
        }
        return shortString(error == null ? "unknown" : error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    private static String shortString(String raw) {
        if (raw == null) {
            return "";
        }
        String oneLine = raw.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() <= 240 ? oneLine : oneLine.substring(0, 240) + "…";
    }

    /** 熔断与账目的只读快照。 */
    public record Status(boolean circuitOpen, int consecutiveFailures, long successes, long failures,
                         LocalDateTime since) {
    }
}

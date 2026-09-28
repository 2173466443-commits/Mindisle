package com.mindisle.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mindisle.ai.llm.ContextEstimator;
import com.mindisle.ai.llm.LlmMessage;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.emotion.DictEmotionEngine;
import com.mindisle.emotion.EmotionPrior;
import com.mindisle.emotion.dto.EmotionGroupRow;
import com.mindisle.entity.ChatMessage;
import com.mindisle.entity.Conversation;
import com.mindisle.mapper.ChatMessageMapper;
import com.mindisle.mapper.ConversationMapper;
import com.mindisle.mapper.EmotionRecordMapper;

/**
 * 上下文组装（任务 T4.3 · 手册 §5.4「摘要 + 近 N 轮 + 情绪趋势」）。
 *
 * <p>送给模型的 messages 顺序固定为：
 * system 人格 → system 会话摘要（有的话）→ 近 N 轮历史 → 本轮用户输入。
 * 情绪趋势是<b>拼进 system 人格段的占位符</b>而不是单独一条 message，
 * 因为它是「关于用户的事实」而不是「谁说的话」。</p>
 *
 * <p><b>三条不容易想到的取舍</b>：</p>
 * <ul>
 *   <li><b>趋势是聚合的，不是逐条原文。</b>需求 §11 的隐私红线是「AI 上下文只带
 *       标签与强度，不带发帖原文」。这里给的是「近 3 日：难过×4（均值强度 3.2）、平静×2」，
 *       模型据此调整语气，但拿不到用户到底写了什么。</li>
 *   <li><b>低置信度的记录不进趋势</b>（BR12，阈值 {@code emotionConfidentMin}）。
 *       把「词典没吃准」的判定当成事实告诉模型，会让语气跑偏，而且这个错误会被复利放大。</li>
 *   <li><b>截断时丢最老的轮，不丢最新的</b>：配对顺序（user/assistant 交替）保持不变，
 *       否则模型会看到半句话。摘要触发时把被丢出窗口的轮次压进 conversation.summary。</li>
 * </ul>
 */
@Component
public class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final EmotionRecordMapper emotionMapper;
    /** 对话人格模板名，与 resources/prompts/ 下的文件名一致。 */
    public static final String CHAT_PROMPT = "chat_default_v1";

    private final MindisleProperties properties;
    private final EmotionPrior prior;
    private final DictEmotionEngine engine;
    private final PromptTemplate prompts;

    public ContextAssembler(ConversationMapper conversationMapper, ChatMessageMapper messageMapper,
                            EmotionRecordMapper emotionMapper, MindisleProperties properties,
                            DictEmotionEngine engine, PromptTemplate prompts) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.emotionMapper = emotionMapper;
        this.properties = properties;
        this.engine = engine;
        this.prompts = prompts;
        this.prior = engine.prior();
    }

    /**
     * 组装结果。
     *
     * @param messages   送模型的完整序列
     * @param rounds     实际带入的历史轮数
     * @param truncated  是否因超出 token 预算而丢过一个完整轮次
     * @param estTokens  组装后的 token 估算（偏高口径，见 {@link ContextEstimator}）
     * @param trendText  给日志与论文用的趋势描述串
     */
    public record Assembled(List<LlmMessage> messages, int rounds, boolean truncated, int estTokens,
                            String trendText) {
    }

    /**
     * 组装一次对话请求的上下文。
     *
     * @param conversation   已确认归属当前用户的会话
     * @param userText       本轮用户输入
     * @param styleText      已映射成提示词口径的人格描述（ChatService 里做映射，见其 javadoc）
     * @param currentEmotion 本轮用户消息的情绪判定结果，可为 null
     */
    public Assembled assemble(Conversation conversation, String userText, String styleText,
                              DictEmotionEngine.Analysis currentEmotion) {
        MindisleProperties.Llm llm = properties.getLlm();
        String trend = trendFor(conversation.getUserId());
        String emotionLabel = currentEmotion == null ? null : currentEmotion.label();
        int emotionIntensity = currentEmotion == null ? 2 : currentEmotion.intensity();

        String system = prompts.render(CHAT_PROMPT, java.util.Map.of(
                        "emotion", emotionLabel == null ? "未识别" : prior.zh(emotionLabel),
                        "intensity", String.valueOf(emotionIntensity),
                        "trend", trend.isEmpty() ? "暂无近期记录" : trend,
                        "style", styleText == null ? "" : styleText));

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(system));
        if (conversation.getSummary() != null && !conversation.getSummary().isBlank()) {
            messages.add(LlmMessage.system("以下是本次会话更早内容的摘要（不是原话）：" + conversation.getSummary()));
        }

        // 多取一倍再按需回丢，避免「先算预算再查库」的两趟查询
        List<ChatMessage> history = messageMapper.listLatest(conversation.getId(),
                Math.max(llm.getContextRounds() * 2, llm.getContextRounds()));
        List<ChatMessage> chosen = new ArrayList<>();
        int used = ContextEstimator.estimate(system)
                + (conversation.getSummary() == null ? 0 : ContextEstimator.estimate(conversation.getSummary()))
                + ContextEstimator.estimate(userText);
        boolean truncated = false;
        // history 是时间升序，从最新的往回收集
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            int cost = ContextEstimator.estimate(m.getContent());
            if (used + cost > llm.getContextMaxTokens()) {
                truncated = true;
                break;
            }
            used += cost;
            chosen.add(0, m);
            if (chosen.size() >= llm.getContextRounds() * 2) {
                break;
            }
        }
        if (truncated) {
            log.info("会话 {} 上下文超出 {} token 预算，丢出最老轮次，实际带入 {} 条",
                    conversation.getId(), llm.getContextMaxTokens(), chosen.size());
        }
        for (ChatMessage m : chosen) {
            if (LlmMessage.USER.equals(m.getRole())) {
                messages.add(LlmMessage.user(m.getContent()));
            } else if (LlmMessage.ASSISTANT.equals(m.getRole())) {
                messages.add(LlmMessage.assistant(m.getContent()));
            }
            // role=system 的历史行是系统注入的提示（如危机提醒），不进模型上下文
        }
        messages.add(LlmMessage.user(userText));
        int est = messages.stream().mapToInt(m -> ContextEstimator.estimate(m.content())).sum();
        return new Assembled(messages, chosen.size() / 2, truncated, est, trend);
    }

    /** 会话轮数超过这个值就该触发摘要压缩（T4.3）。 */
    public boolean shouldSummarize(int messageCount) {
        return messageCount >= properties.getLlm().getSummaryTriggerRounds() * 2;
    }

    /**
     * 近 3 日情绪趋势（聚合，不含原文）。
     *
     * <p>返回空串表示「没有可用数据」，调用方负责填「暂无近期记录」——
     * 让「没数据」和「数据是中性」在提示词里长得一样，模型就会凭空生成一个不存在的情绪基调。</p>
     */
    String trendFor(long userId) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(2);
        List<EmotionGroupRow> rows = emotionMapper.groupByDayAndLabel(userId, from, to,
                BigDecimal.valueOf(properties.getLlm().getEmotionConfidentMin()));
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (EmotionGroupRow r : rows) {
            if (r.getCnt() == null || r.getCnt() <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('、');
            }
            BigDecimal avg = r.getAvgIntensity() == null ? BigDecimal.ZERO : r.getAvgIntensity();
            sb.append(prior.zh(r.getLabel())).append('×').append(r.getCnt())
                    .append("(强度").append(avg.setScale(1, RoundingMode.HALF_UP)).append(')');
        }
        return sb.toString();
    }

    /** 会话归属校验由 Mapper 的 SQL 负责，这里只取；取不到直接抛，不返回 null 让上层猜。 */
    public Conversation requireOwned(long userId, long conversationId) {
        Conversation conversation = conversationMapper.findByIdOwned(userId, conversationId);
        if (conversation == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "会话不存在或无权访问");
        }
        return conversation;
    }
}

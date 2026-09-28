package com.mindisle.ai;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mindisle.ai.dto.ConversationView;
import com.mindisle.ai.dto.MessageView;
import com.mindisle.ai.llm.LlmMessage;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.ChatMessage;
import com.mindisle.entity.Conversation;
import com.mindisle.mapper.ChatMessageMapper;
import com.mindisle.mapper.ConversationMapper;

/**
 * 会话与消息的读侧 + 轻写侧（任务 T4.2「conversation / chat_message CRUD」· 需求 FR2.1 · 界面 U7 侧栏）。
 *
 * <p><b>本类一行都不碰模型</b>：所有会花钱、会处理敏感内容的动作都在 {@link ChatService} 里，
 * 这里只有「列出来、起个名、删掉、回看、点个赞踩」。分开不是因为洁癖，而是这两件事的
 * <b>闸门不一样</b>：发一句话要过同意闸、预算闸、清洗闸；而「看我自己的历史」「删掉我的会话」
 * 是主体权利（需求 FR1.5/FR1.6），把它们也绑在同意闸后面，用户撤回同意之后就会发现自己
 * <b>连删都删不掉</b> —— 那是把「撤回」实现成了「扣留数据」，方向正好相反。</p>
 *
 * <p><b>归属校验只有一条路</b>：所有查询都带 {@code user_id}，单条读取一律走
 * {@code findByIdOwned} / {@code softDeleteOwned} 这类条件里含 user_id 的语句，
 * 查不到统一回 90006（不区分「不存在」与「不是你的」，否则这里变成一个别人 id 空间探测器）。
 * 前端传什么 id 都不能跳过这一步。</p>
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** 侧栏一次能取的会话数：与 {@code llm.keepConversations} 同源，不给前端一个绕过配额要更多会话的口子。 */
    static final int SIDEBAR_MAX = 50;

    /**
     * 手动命名的长度上限。
     *
     * <p>和自动标题（FR2.1「首句前 20 字」，走配置 {@code llm.title-max-chars}）故意用两个数：
     * 自动标题是被截断的第一句话，20 字够辨认即可；手动命名是人有意取的名字，
     * 压到 20 字会把「和上次那件关于实习的事」这种正常名字切掉半截。列宽 64，30 个码点留足余量。</p>
     */
    static final int TITLE_MAX = 30;

    /** 回看一页的消息数上限（超过这个数的长会话，本来就该靠摘要而不是逐条翻）。 */
    static final int MESSAGE_PAGE_MAX = 200;

    /** 与 DDL 的 {@code conversation.title DEFAULT '新的对话'} 逐字一致。 */
    static final String DEFAULT_TITLE = "新的对话";

    /** {@code chat_message.feedback} 的取值域（需求 §7.2 表 #2）。
     * 不接受 FIRST/GOOD 这类近义词：那是把数据清洗推给论文阶段的脚本。 */
    static final Set<String> FEEDBACK_VALUES = Set.of("NONE", "UP", "DOWN");

    /** 接口里的时间一律这个格式：前端拿到就显示，不做二次时区换算（全站同一时区口径）。 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final SafetyGuard safetyGuard;
    private final MindisleProperties properties;

    public ConversationService(ConversationMapper conversationMapper, ChatMessageMapper messageMapper,
            SafetyGuard safetyGuard, MindisleProperties properties) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.safetyGuard = safetyGuard;
        this.properties = properties;
    }

    /**
     * 侧栏会话列表（FR2.1）。
     *
     * <p>空列表是正常结果不是错误：新用户的侧栏就该是空的，
     * 这里回 60001「暂无内容」那种语义会让前端多写一条分支去猜「是不是我没权限」。</p>
     */
    public List<ConversationView> list(long userId, Integer limit) {
        int cap = Math.max(1, Math.min(limit == null ? SIDEBAR_MAX : limit, SIDEBAR_MAX));
        List<Conversation> rows = conversationMapper.listActive(userId, cap);
        List<ConversationView> views = new ArrayList<>(rows.size());
        for (Conversation row : rows) {
            views.add(toView(row));
        }
        return views;
    }

    /**
     * 新建会话（POST /api/ai/conversations）。
     *
     * <p><b>可以先建一条空会话</b>：用户点「新建对话」时不该立刻拿到一个 id 却什么都不显示 ——
     * 那会让侧栏出现一条永远空着的行。所以标题留默认值，等第一条消息由
     * {@link ChatService} 把它换成「首句前 20 字」。
     * {@code create} 与「发消息时顺手开会话」是同一条幂等路径的两个入口，
     * 前者给人一个明确的 id，后者兜住前端拿着本机 local-xxx 号来发消息的情况。</p>
     */
    public ConversationView create(long userId, String rawTitle, String rawStyle) {
        String title = rawTitle == null ? "" : safetyGuard.sanitizeUser(rawTitle).text().trim();
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setTitle(title.isEmpty() ? DEFAULT_TITLE : SafetyGuard.cut(title, TITLE_MAX));
        conv.setStyle(ChatService.mapStyle(rawStyle));
        conv.setLastMsgAt(LocalDateTime.now());
        conv.setStatus("ACTIVE");
        conversationMapper.insert(conv);
        int recycled = conversationMapper.softDeleteBeyondQuota(userId,
                properties.getLlm().getKeepConversations());
        if (recycled > 0) {
            log.info("用户 {} 新建会话后超出配额，已逻辑删除最旧 {} 条", userId, recycled);
        }
        return toView(conv);
    }

    /**
     * 删除会话（FR2.1）。
     *
     * <p><b>只删会话头，不删消息</b>：{@code deleted=1} 之后列表和上下文装配都读不到它，
     * 效果上已经「消失」；消息行的物理清除属于注销链路（T4.21「到期物理清除」）。
     * 在这里顺手删消息的代价是：将来一旦要做「误删找回」或者数据导出，
     * 内容已经没了，只剩一行标题。留 7 天由 {@code DataRetentionJob} 收，是需求 §7.5 的口径。</p>
     */
    public void delete(long userId, long conversationId) {
        int changed = conversationMapper.softDeleteOwned(userId, conversationId);
        if (changed == 0) {
            // 要么不存在、要么不是你的、要么已经删过：三种情况给同一个回，别做探测器
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "这条会话已经不在这儿了。");
        }
        log.info("用户 {} 删除会话 #{}", userId, conversationId);
    }
    /**
     * 重命名会话（需求 FR2.1「会话可重命名」· 任务 T4.2 的最后一格，v1.2.6 补齐）。
     *
     * <p><b>三步顺序是刻意的：先判空 → 再查归属 → 再判同名。</b>
     * 判空放最前面既省一次数据库往返，也不让「空标题」和「别人的会话」混成同一个回；
     * <b>空标题回 10001 而不是悄悄改回「新的对话」</b> —— 那等于把用户刚打进去的输入吞掉，
     * 界面还会显示一个他从来没写过的标题。「不存在或不是你的」统一回 90006，
     * 与 {@link #delete} 同一口径，不做跨账号探测器。同名直接返回不发 UPDATE：一是省一次写，
     * 二是 {@code renameOwned} 对同值返回 0 行，拿那个 0 去判「不存在」就是假故障
     * （这条理由写在 Mapper 的注释里，两处要一起看）。</p>
     *
     * @throws BizException 10001 标题为空或清洗后为空；90006 会话不存在或不属于你
     */
    public ConversationView rename(long userId, long conversationId, String rawTitle) {
        String title = rawTitle == null ? "" : safetyGuard.sanitizeUser(rawTitle).text().trim();
        if (title.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "会话标题不能是空的。");
        }
        Conversation conv = conversationMapper.findByIdOwned(userId, conversationId);
        if (conv == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "这条会话已经不在这儿了。");
        }
        String next = SafetyGuard.cut(title, TITLE_MAX);
        if (next.equals(conv.getTitle())) {
            return toView(conv);
        }
        conversationMapper.renameOwned(userId, conversationId, next);
        log.info("用户 {} 把会话 #{} 的标题从「{}」改成「{}」", userId, conversationId, conv.getTitle(), next);
        conv.setTitle(next);
        return toView(conv);
    }


    /**
     * 回看一个会话的消息（点侧栏里的旧会话）。
     *
     * <p>先按 user_id 校验会话归属，再取消息：消息行自己也有 user_id，
     * 单看消息也能校验，但那样每次回看都是「先猜一个 conversation_id 是否属于我」。
     * 两步的顺序是<b>会话在前</b> —— 它同时给出 404 的正确语义（会话不存在 ≠ 消息不存在）。</p>
     *
     * <p><b>路径口径要写白</b>：需求 §9.1 的 AI 行只列了 5 个端点，没有这一条；
     * 但同节任务 T4.2 写的是「conversation/chat_message CRUD」，U7 又要求「左会话列表 + 右聊天区」，
     * 点开旧会话却没有读消息的通道就做不到。所以这一条属 T4.2 的「R」，不是自创端点，
     * 差异已按 SOP 记进 docs/dev-log.md 的契约漂移清单。</p>
     */
    public List<MessageView> messages(long userId, long conversationId, Integer limit) {
        Conversation conv = conversationMapper.findByIdOwned(userId, conversationId);
        if (conv == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "这条会话已经不在这儿了。");
        }
        int cap = Math.max(1, Math.min(limit == null ? MESSAGE_PAGE_MAX : limit, MESSAGE_PAGE_MAX));
        List<ChatMessage> rows = messageMapper.listByConversation(conversationId, cap);
        List<MessageView> views = new ArrayList<>(rows.size());
        for (ChatMessage row : rows) {
            views.add(toView(row));
        }
        return views;
    }

    /**
     * 赞踩（FR2.7「有用 / 没被理解」· 任务 T4.19）。
     *
     * <p>只写 {@code chat_message.feedback} 这一列，且只允许打在助手消息上：
     * 这份数据要进需求 §8.4 的对话质量评估集，混进「用户给自己那句话点赞」
     * 会直接污染标注口径，而事后从 feedback 里分不清打的是哪一方（两行都是同一个 user_id）。</p>
     *
     * <p><b>T4.19 要求同时写一条 {@code user_action(action_type='ai_feedback')}</b>——欠账已还，
     * 但<b>不在本方法里</b>：埋点通道（任务 T3.10）落地后，这一条挂在 {@code AiController#feedback}
     * 上调 {@link com.mindisle.track.UserActionRecorder#recordAiFeedback}。放在控制器是因为本方法
     * 有一句「反馈值没变就 return」的早退（那是给 UPDATE 省一次写），而埋点的口径是
     * 「每一次点击都要进 recorder」；在这里自己 INSERT 等于造第二套埋点口径——
     * 权重、日级去重、mood 采样三件事都会分叉（理由见 {@code UserActionCatalog} 类注释）。</p>
     */
    public void feedback(long userId, long messageId, String rawFeedback) {
        String value = rawFeedback == null ? "" : rawFeedback.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            value = "NONE";
        }
        if (!FEEDBACK_VALUES.contains(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "反馈只能是 UP、DOWN 或 NONE。");
        }
        ChatMessage row = messageMapper.findByIdOwned(userId, messageId);
        if (row == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "这条消息已经不在这儿了。");
        }
        if (!LlmMessage.ASSISTANT.equals(row.getRole())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "只能给屿屿的回复打分。");
        }
        if (value.equals(row.getFeedback())) {
            return;
        }
        // 只 set 这一列：MyBatis-Plus 默认 NOT_NULL 策略，其余列不会出现在 UPDATE 语句里，
        // 于是「改一个赞踩」不会把 content / emotion_label 这些旧值原样回写一遍
        ChatMessage upd = new ChatMessage();
        upd.setId(row.getId());
        upd.setFeedback(value);
        messageMapper.updateById(upd);
        log.info("用户 {} 给消息 #{} 打了 {}（原值 {}）", userId, messageId, value, row.getFeedback());
    }

    /** 会话实体 → 列表项（包级可见，给单测直接比对映射）。 */
    static ConversationView toView(Conversation conv) {
        LocalDateTime sortKey = conv.getLastMsgAt() != null ? conv.getLastMsgAt() : conv.getCreatedAt();
        return new ConversationView(conv.getId(), conv.getTitle(), conv.getStyle(), conv.getSummary(),
                fmt(sortKey), fmt(conv.getCreatedAt()));
    }

    /**
     * 消息实体 → 回看项。
     *
     * <p>{@code degraded}/{@code interrupted} 在库里是 tinyint，出去是 boolean：
     * 前端要用它决定气泡上挂不挂「离线模式」标记，发 0/1 过去会多一层 {@code Number(x)===1} 的判断，
     * 而那层判断在 null 上会静默变成 false —— 恰好是「明明降级了却显示成正常回复」的那种错法。</p>
     */
    static MessageView toView(ChatMessage row) {
        return new MessageView(row.getId(), row.getRole(), row.getContent(), row.getEmotionLabel(),
                row.getEmotionChannel(), row.getRiskLevel(), row.getFeedback(),
                isOne(row.getDegraded()), isOne(row.getInterrupted()), row.getModel(),
                row.getPromptVersion(), fmt(row.getCreatedAt()));
    }

    /** null 一律按 false：这两列有默认值 0，读到 null 只可能是查出来的行不完整。 */
    private static boolean isOne(Integer flag) {
        return flag != null && flag == 1;
    }

    private static String fmt(LocalDateTime time) {
        return time == null ? null : TS.format(time);
    }
}

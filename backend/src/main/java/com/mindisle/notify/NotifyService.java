package com.mindisle.notify;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.entity.NotifyMessage;
import com.mindisle.notify.dto.MarkReadRequest;
import com.mindisle.notify.dto.MarkReadView;
import com.mindisle.notify.dto.NotifyItem;
import com.mindisle.notify.dto.NotifyPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 站内通知（任务 T3.11-b · 需求 FR9.1、FR9.2 · 手册 §6.1 行 3.11）。
 *
 * <p><b>本类管两件事：把互动事件写成一行通知，以及把这行通知读回来并标已读。</b>
 * 写的一侧没有事务边界可以自己决定——它<b>必须</b>和触发它的业务写落在同一个
 * {@code @Transactional} 里（点赞、评论、关注的服务方法内），理由与取舍见下面第 3 条。</p>
 *
 * <p><b>1. 为什么没有 WebSocket</b>：手册那行还要求「预留推送口」，本类的 {@link PushHook}
 * 就是那个口——写完一行通知之后调一次，现在唯一的实现只打一行 debug 日志。
 * 阶段 5 的 T5.8 换成本类之外的 STOMP 实现即可，<b>业务服务与写链路一行都不用改</b>。
 * 为什么不现在就接：STOMP 要先有 /ws 端点与订阅会话（T5.1、T5.2 都在阶段 5），
 * 现在接等于让阶段 3 的冒烟依赖一个还没有的协议。</p>
 *
 * <p><b>2. 为什么幂等靠「先查后插 + 文案相同」而不是唯一索引</b>：notify_message 的 DDL 没有
 * actor 列，能当幂等键的四列 (user_id, type, ref_type, ref_id) 会把
 * 「A 赞了这帖」和「B 赞了这帖」判成同一件事——那是两个人、两条应该都留的通知。
 * 所以这里比到 title 与 content 全等才跳过（{@link #shouldSkip}）。
 * <b>并发下它挡不住两个人同时赞同一帖</b>（两条通知都写进去，这是对的），
 * 也挡不住同一个人的两次重试刚好交错（会出两行，这是可接受的脏，已记进手册 §14）。</p>
 *
 * <p><b>3. 为什么异常不吞</b>：写通知与业务写在同一个事务里，插入失败会让点赞一起回滚。
 * 反过来「点赞成功、通知丢了」是<b>静默</b>故障——用户永远不知道少了一条提醒，也没有任何地方能补。
 * 一起失败至少会暴露成一次可重试的 500。真正的解耦做法是 outbox（写事件表 + 后台投递），
 * 那属阶段 5 与 WebSocket 一起做，本阶段不假装做过。</p>
 *
 * <p><b>4. 为什么「取消赞」不撤回通知</b>：没有 actor 列，就不知道哪一行是这个人那次点的赞，
 * 只能按文案找——那会把别人的同名通知一起标掉。产品上「A 赞了你」之后 A 又取消，
 * 收件人看到那条已读通知也不是错误（这件事<b>发生过</b>）。已记进手册 §14。</p>
 *
 * <p><b>5. 举报本轮不写通知</b>：举报的回执是即时的（{@code ReportView.tip} 已经把话说完），
 * 而「你的举报被采纳了」要等 T6.1 的处置结论，那时写 {@code type=report}。
 * 现在写一条 {@code status=PENDING} 的通知等于把「已提交」这件事说两遍。</p>
 */
@Service
public class NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);

    /** title / content 的列宽上限（DDL VARCHAR(100) 与 VARCHAR(500)），码点计数。 */
    static final int TITLE_MAX = 100;
    static final int CONTENT_MAX = 500;

    /** 列表默认与最大页长，与 {@code PageQuery} 同口径，避免同一个站两套「一页多少条」。 */
    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    /** 一次批量已读的上限：超过就是前端在拿 id 做遍历攻击，让它报错而不是慢慢吞吞地扫全表。 */
    static final int MARK_BATCH_MAX = 100;

    /** 评论摘录的展示上限（码点），超出补省略号——通知不是阅读器，够认出来是哪条就行。 */
    static final int EXCERPT_MAX = 60;

    /** 八类通知的中文标签，与 {@link NotifyMessage} 的常量一一对应（前端不再自己维护映射）。 */
    private static final Map<String, String> TYPE_LABELS = labels();

    /**
     * 存储端口（套路同 {@code ReportService.ReportStore}）：规则留在本类，SQL 推到外面，
     * 于是「文案相同才跳过」「只数未读」「一键已读不动已读行」这三条能在单测里逐条钉住。
     * 真环境适配器见 {@link NotifyStoreAdapter}。
     */
    public interface NotifyStore {

        /** 库里已有<b>未读</b>的同文案行吗；true 即本次不该再写第二条。 */
        boolean existsUnreadDuplicate(long userId, String type, String refType, long refId,
                                      String title, String content);

        /** 落一行通知，实现方负责回填自增 id。 */
        void insert(NotifyMessage row);

        /** 未读总数（红点），走 idx_user_read 覆盖索引。 */
        long countUnread(long userId);

        /** 取比 beforeId 更旧的 limit 条，id 倒序；beforeId 为 null 即最新一页。 */
        List<NotifyMessage> page(long userId, Long beforeId, int limit);

        /** 批量置已读；返回影响行数（只算真的从未读变已读的）。 */
        int markRead(long userId, List<Long> ids, LocalDateTime now);

        /** 一键已读；返回影响行数。 */
        int markAllRead(long userId, LocalDateTime now);
    }

    /**
     * 推送口（手册 §6.1 行 3.11「预留 WebSocket 推送口」的落点）。
     *
     * <p>阶段 5 的实现按手册 §10.4 的 payload 口径 {@code {type, id, unread, ts}}
     * 往 {@code /user/queue/notify} 发；现在注册的是 {@link LoggingPushHook}。</p>
     */
    public interface PushHook {

        /** 一行通知刚落库之后调用。<b>实现不得抛异常打断业务事务</b>，所以这里刻意没有返回值。 */
        void onCreated(NotifyMessage row);
    }

    private final NotifyStore store;
    private final PushHook pushHook;

    public NotifyService(NotifyStore store, PushHook pushHook) {
        this.store = store;
        this.pushHook = pushHook;
    }

    private static Map<String, String> labels() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(NotifyMessage.TYPE_LIKE, "赞");
        map.put(NotifyMessage.TYPE_COMMENT, "评论");
        map.put(NotifyMessage.TYPE_FOLLOW, "关注");
        map.put(NotifyMessage.TYPE_PM, "私信");
        map.put(NotifyMessage.TYPE_SYSTEM, "系统");
        map.put(NotifyMessage.TYPE_AUDIT, "审核结果");
        map.put(NotifyMessage.TYPE_CRISIS, "危机关怀");
        map.put(NotifyMessage.TYPE_REPORT, "举报回执");
        return Collections.unmodifiableMap(map);
    }

    /** 标签查不到时回原码：ENUM 将来加值不至于让列表接口 500。 */
    static String typeLabel(String type) {
        return TYPE_LABELS.getOrDefault(type, type);
    }

    // ================================================================ 写

    /**
     * 「有人赞了我的帖子」。
     *
     * <p>调用方负责三件判据：这次点赞<b>真的成立了</b>（{@code changed}）、
     * <b>不是自赞</b>（自己赞自己不该给自己发提醒）、以及传进来的 {@code actorName}
     * 已经是<b>对外展示名</b>。第三条是本项目最在意的一条：文案一旦落库就成了对外内容，
     * 到这一层再想脱敏就晚了。</p>
     */
    public void notifyLike(long recipientId, String actorName, long postId, String postTitle) {
        write(recipientId, NotifyMessage.TYPE_LIKE,
                actorName + " 赞了你的帖子", quote(postTitle), NotifyMessage.REF_POST, postId);
    }

    /**
     * 「有人评论了我的帖子」。
     *
     * <p>只有<b>已发布</b>的评论才发通知：待审评论仅作者自己可见，
     * 给帖子作者推一条「有人评论了你」却点开什么都没有，等于把一个还在审核里的内容广播出去。</p>
     *
     * <p><b>为什么不带帖标题</b>：辅助行给的是评论摘录（那才是楼主不知道的内容），帖子本身由
     * {@code ref_type/ref_id} 指过去；当年留一个 postTitle 参数却一个字都没用进文案，
     * 那是「契约说谎」而不是「以后可能用得上」，已经删掉（手册 §14）。</p>
     */
    public void notifyComment(long recipientId, String actorName, long postId,
                              String commentContent) {
        write(recipientId, NotifyMessage.TYPE_COMMENT,
                actorName + " 评论了你的帖子", excerpt(commentContent),
                NotifyMessage.REF_POST, postId);
    }

    /** 「有人回复了我的评论」，跳转目标仍然是帖子——回复藏在楼中楼里，点进评论 id 反而看不到上下文。 */
    public void notifyReply(long recipientId, String actorName, long postId, String commentContent) {
        write(recipientId, NotifyMessage.TYPE_COMMENT,
                actorName + " 回复了你的评论", excerpt(commentContent),
                NotifyMessage.REF_POST, postId);
    }

    /** 「有人关注了我」，ref 指向关注者的公开主页（关注关系本身不匿名，FR4.6）。 */
    public void notifyFollow(long recipientId, String actorName, long actorId) {
        write(recipientId, NotifyMessage.TYPE_FOLLOW, actorName + " 关注了你",
                "去他的主页看看", NotifyMessage.REF_USER, actorId);
    }

    /**
     * 「有人给我发了一条私信」（任务 T5.1 · 需求 FR6.1、FR9.1）。
     *
     * <p><b>它和消息送达是两回事</b>：私信本体经
     * {@link com.mindisle.pm.PmRealtimeListener} 走 STOMP 直接落到对方的
     * {@code /user/queue/private}，那条路径不经过本类；本方法只负责通知中心里那条
     * 「X 给你发来一条私信」的红点。所以 {@link #shouldSkip} 的同文案幂等命中时，
     * 少掉的只是重复的红点，不会少一条消息——这句话要写在这里，
     * 否则下一个读代码的人会以为把私信接进 NotifyService 就实现了实时下发。</p>
     *
     * <p>{@code refId} 是<b>对方用户 id</b>（见 {@link NotifyMessage#REF_PM}），
     * 摘录用 {@link #excerpt} 而不是原文：通知中心是列表页，一条 800 字的私信
     * 会把整页撑开；点开进会话才看到全文。</p>
     */
    public void notifyPm(long recipientId, String actorName, long peerId, String contentText) {
        write(recipientId, NotifyMessage.TYPE_PM,
                actorName + " 给你发来一条私信", excerpt(contentText),
                NotifyMessage.REF_PM, peerId);
    }

    /**
     * 唯一的写入口：判空 → 截断 → 幂等 → 落库 → 触发推送口。
     *
     * <p><b>不吞异常</b>（理由见类注释第 3 条）；{@code recipientId <= 0} 直接跳过——
     * 匿名帖的作者 id 一定存在，但「系统帖」这类将来可能出现 0 值，为它写一行永远读不到的通知没有意义。</p>
     */
    private void write(long recipientId, String type, String rawTitle, String rawContent,
                       String refType, long refId) {
        if (recipientId <= 0L) {
            return;
        }
        String title = cut(rawTitle, TITLE_MAX);
        String content = rawContent == null || rawContent.isEmpty() ? null : cut(rawContent, CONTENT_MAX);
        if (content != null && shouldSkip(recipientId, type, refType, refId, title, content)) {
            return;
        }
        NotifyMessage row = new NotifyMessage();
        row.setUserId(recipientId);
        row.setType(type);
        row.setTitle(title);
        row.setContent(content == null ? title : content);
        row.setRefType(refType);
        row.setRefId(refId);
        row.setIsRead(0);
        row.setDeleted(0);
        store.insert(row);
        pushHook.onCreated(row);
    }

    /**
     * 人审裁决结果通知作者（任务 T6.1 · 需求 FR7.3「处置结果回写」· 手册 §9.1 第 ③ 步）。
     *
     * <p><b>为什么驳回必须带理由</b>：需求 §12 规范 5 写的是「可追溯」，而对作者真正可追溯的
     * 唯一形式是「一句他能读懂的话」。这里传的 {@code reason} 由调用方从审核备注里取，
     * 服务层不掺词库原词（那是内部规则，写进用户通知等于把绕过方法公开）。</p>
     *
     * <p>{@code refId} 是帖子 id：{@link NotifyMessage#REF_POST} 在整个仓库里的语义都是「点进去看那条帖」，
     * 待审/已驳回的帖只有作者自己能看见（{@code PostQueryService} 的可见性判据），
     * 所以这条跳转对作者是有效的，对别人无效——正好。</p>
     */
    public void notifyAuditResult(long authorId, long postId, boolean passed, String reason) {
        String title = passed ? "你的帖子已通过审核" : "你的帖子未通过审核";
        String body = passed
                ? "内容已对外可见。如果之前提示过风险，是因为词库的保守判定，欢迎继续记录心情。"
                : "原因：" + (reason == null || reason.isBlank() ? "内容不符合社区规范" : reason.trim())
                        + "。你有一次申诉机会，路径在帖子详情页。";
        write(authorId, NotifyMessage.TYPE_AUDIT, title, body, NotifyMessage.REF_POST, postId);
    }

    /**
     * 举报处置结论回执（任务 T3.11 埋的口子 + T6.6 补账 · 需求 FR4.7）。
     *
     * <p>类注释第 5 条说过「举报本轮不写通知」，那是在处置方还不存在的时候。
     * 现在 A7 能处置了，这条就欠着必须还：举报人最需要知道的不是「我报成功了」，
     * 而是「后来怎么样了」——不然举报按钮等于一个没有回音的电报机。</p>
     *
     * <p>{@code refId} 用<b>举报行 id</b>而不是帖子 id（{@link NotifyMessage#REF_REPORT}）：
     * 同一帖可能被同一个人分两次举报不同内容，按帖去重会把第二条回执吞掉。</p>
     */
    public void notifyReportResult(long reporterId, long reportId, long postId,
                                   boolean accepted, String note) {
        String title = accepted ? "你的举报已处理：内容已下架" : "你的举报已处理：经复核不违规";
        String body = (note == null || note.isBlank() ? "" : note.trim() + " ")
                + "感谢你把边界问题说出来（帖子 #" + postId + "）。";
        write(reporterId, NotifyMessage.TYPE_REPORT, title, body, NotifyMessage.REF_REPORT, reportId);
    }
    /**
     * 账号处置通知（任务 T6.4 · 需求 FR8.3）：禁言/封禁/恢复都告诉当事人「你现在能做什么、不能做什么」。
     *
     * <p>文案里刻意不写判定规则（禁的是发内容还是发评论、多少天）之外的任何内部口径；
     * 也不写「疑似心理危机」这类定性——管理员不是医生（BR3）。</p>
     */
    public void notifyAccountAction(long userId, String title, String body) {
        write(userId, NotifyMessage.TYPE_SYSTEM, title, body, NotifyMessage.REF_USER, userId);
    }

    /**
     * 内容处置通知（任务 T6.5 · 需求 FR4.7）：下架与恢复走同一条，区别只在文案。
     *
     * <p>下架必须把「你还能申诉一次」写进去：申诉入口在帖子详情页，用户不会因为收到通知就自己去翻规则。</p>
     */
    public void notifyContentAction(long authorId, long postId, boolean takenDown, String reason) {
        String title = takenDown ? "你的帖子已被管理下架" : "你的帖子已恢复可见";
        String body = takenDown
                ? "原因：" + (reason == null || reason.isBlank() ? "违反社区规范" : reason.trim())
                        + "。帖子在你自己的主页仍可见，你可以发起一次申诉。"
                : "内容重新对外可见。感谢你对社区规范的理解。";
        write(authorId, NotifyMessage.TYPE_AUDIT, title, body, NotifyMessage.REF_POST, postId);
    }

    /** 申诉结果通知（任务 T6.7 · FR7.6「结果回写通知」）：一次申诉只发一条，终态不再重复打扰。 */
    public void notifyAppealResult(long userId, long postId, boolean accepted, String note) {
        String title = accepted ? "你的申诉已通过" : "你的申诉未通过";
        String body = (note == null || note.isBlank() ? "" : note.trim() + " ")
                + (accepted ? "内容已恢复可见，社区谢谢你把话说清楚。" : "这是本次申诉的最终结论，如需进一步沟通可以在个人中心联系心理委员。");
        write(userId, NotifyMessage.TYPE_AUDIT, title, body, NotifyMessage.REF_POST, postId);
    }    /** 幂等判据：同一个人、同一类、同一目标、<b>连文案都一样</b>且那条还没读，才算重复。 */
    private boolean shouldSkip(long userId, String type, String refType, long refId,
                               String title, String content) {
        return store.existsUnreadDuplicate(userId, type, refType, refId, title, content);
    }

    // ================================================================ 读

    /**
     * 通知列表（游标倒序）。
     *
     * <p>多取一条判 {@code hasMore}，而不是数总数：本表按用户增长，COUNT 一次全量的收益
     * 只有「页码能显示共几页」，代价是每次开页扫一遍。红点已经有未读数了，够用了。</p>
     */
    /**
     * 危机工单弹给管理员（任务 T4.13 · 需求 FR10.6 / §5.2）。
     *
     * <p><b>为什么新开方法而不是让 ChatService 自己新建 NotifyMessage</b>：{@link #write} 里面藏着三件
     * 事——码点截断、{@code existsUnreadDuplicate} 同文案幂等、{@link PushHook} 推送口。
     * 绕过它就等于写出一条「不会推送、也不去重」的通知：同一个危机用户连续发十条消息
     * 就会给每个管理员刷十条红点，第十一条开始这个通知就垃圾了。写在本类里才算正硬。</p>
     *
     * <p><b>幂等口径</b>：{@code ref_type=conversation} + {@code ref_id=会话 id} + 固定文案，
     * 所以「同一会话内的连续 L3」只会留一条未读。但 title 里带了等级，
     * L2 升到 L3 会文案不同——这是故意的：升级必须再次打烊。</p>
     *
     * <p><b>不抄贝原文</b>：内容只放 {@code excerpt}（调用方已用 {@code CrisisGrader.evidence} 截过），
     * 因为通知会推到管理员的浏览器弹窗上，屏幕可能被别人看见（需求 BR11 最小展示原则）。</p>
     *
     * @param adminIds       接收人（{@code UserMapper#listAdminIds()}）；空集合直接返回，不报错
     * @param conversationId 工单指向的会话，作为通知的 ref（点击跳审核队列）
     * @param level          L2 / L3
     * @param excerpt        证据摘录，可为 null
     */
    public void notifyCrisisAdmin(List<Long> adminIds, long conversationId, String level, String excerpt) {
        if (adminIds == null || adminIds.isEmpty()) {
            log.warn("危机通知没有接收人（管理员列表为空）conversationId={} level={}", conversationId, level);
            return;
        }
        String safeLevel = level == null || level.isBlank() ? "L2" : level;
        String title = "【" + safeLevel + " 危机】AI 对话命中风险词，会话 #" + conversationId;
        String body = "热线 " + CRISIS_HOTLINE_HINT + "。证据摘录："
                + (excerpt == null || excerpt.isBlank() ? "（无）" : excerpt);
        for (Long adminId : adminIds) {
            if (adminId == null) {
                continue;
            }
            write(adminId, NotifyMessage.TYPE_CRISIS, title, body, NotifyMessage.REF_CONVERSATION, conversationId);
        }
    }

    /**
     * 私信命中危机词时弹给管理员（任务 T5.5 / T5.7 · 需求 FR6.6、FR10.6）。
     *
     * <p>与 {@link #notifyCrisisAdmin} 是<b>两条独立的线</b>，不能复用：那条的 ref 是会话 id、
     * 点击跳 AI 会话审核；这里的 ref 必须是<b>发信方用户 id</b>，管理员点开后落在
     * 与该用户的私信线程里（{@code NotifyMessage#REF_PM} 的语义在整个仓库里都是
     * 「对方用户 id」，改成消息 id 会让前端路由跳错，所以这里宁可多带一个 messageId 参数只进文案）。</p>
     *
     * <p><b>幂等口径</b>：同 ref_id + 同 title（含等级）才去重，因此同一个发信人
     * 对<b>不同收件人</b>连发十条危机私信只会留一条未读红点 —— 管理员要处理的是「这个人」，
     * 不是十条消息。等级从 L2 升到 L3 时 title 变了，会再打烊一次。</p>
     *
     * @param adminIds     接收人；空集合只记 warn，不抛异常（私信已经发出去了，不能因为通知失败回滚）
     * @param senderUserId 发信方用户 id，作为通知的 ref（点击跳私信线程）
     * @param messageId    命中的私信 id，只进文案，便于在审核台里定位那一条
     * @param level        L2 / L3
     * @param excerpt      证据摘录（调用方已按码点截过），可为 null
     */
    public void notifyPmCrisisAdmin(List<Long> adminIds, long senderUserId, long messageId,
                                    String level, String excerpt) {
        if (adminIds == null || adminIds.isEmpty()) {
            log.warn("私信危机通知没有接收人（管理员列表为空）messageId={} sender={} level={}",
                    messageId, senderUserId, level);
            return;
        }
        String safeLevel = level == null || level.isBlank() ? "L2" : level;
        String title = "【" + safeLevel + " 危机】私信命中风险词，消息 #" + messageId;
        String body = "热线 " + CRISIS_HOTLINE_HINT + "。证据摘录："
                + (excerpt == null || excerpt.isBlank() ? "（无）" : excerpt);
        for (Long adminId : adminIds) {
            if (adminId == null) {
                continue;
            }
            write(adminId, NotifyMessage.TYPE_CRISIS, title, body, NotifyMessage.REF_PM, senderUserId);
        }
    }

    /** 通知文案里的热线提示。写死而不取配置：管理员看到的是「该打哪个电话」，它属于公共危机资源。 */
    private static final String CRISIS_HOTLINE_HINT = "12356";

    public NotifyPage list(long userId, Long beforeId, Integer size) {
        int limit = normalizeSize(size);
        List<NotifyMessage> rows = store.page(userId, beforeId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<NotifyItem> items = new ArrayList<>(Math.min(rows.size(), limit));
        for (int i = 0; i < Math.min(rows.size(), limit); i++) {
            items.add(toItem(rows.get(i)));
        }
        Long cursor = items.isEmpty() ? null : items.get(items.size() - 1).id();
        return new NotifyPage(items, cursor, hasMore, store.countUnread(userId));
    }

    /**
     * 标记已读：{@code all=true} 走一键已读，否则按 ids 批量。
     *
     * <p>两个字段都不给时报 400/10001：静默当成「全部已读」是最坏的一种宽容——
     * 前端漏传一个字段就能把用户所有通知点成已读。</p>
     */
    @Transactional
    public MarkReadView markRead(long userId, MarkReadRequest request, LocalDateTime now) {
        boolean all = request != null && Boolean.TRUE.equals(request.all());
        int updated;
        if (all) {
            updated = store.markAllRead(userId, now);
        } else {
            List<Long> ids = cleanIds(request == null ? null : request.ids());
            updated = store.markRead(userId, ids, now);
        }
        return new MarkReadView(updated, store.countUnread(userId), all);
    }

    /** 去 null、去非正数、去重（保持顺序，方便测试断言）、超上限报错。 */
    private static List<Long> cleanIds(List<Long> raw) {
        List<Long> ids = new ArrayList<>();
        if (raw != null) {
            for (Long id : raw) {
                if (id != null && id > 0L && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "要标记已读的通知 id 不能为空");
        }
        if (ids.size() > MARK_BATCH_MAX) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "一次最多标记 " + MARK_BATCH_MAX + " 条，现在是 " + ids.size() + " 条");
        }
        return ids;
    }

    private static int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    /** 实体 → 出参。readAt/deleted/触发者身份都不在这里出现（判据见 {@link NotifyItem} 类注释）。 */
    static NotifyItem toItem(NotifyMessage row) {
        return new NotifyItem(row.getId(), row.getType(), typeLabel(row.getType()),
                row.getTitle(), row.getContent(), row.getRefType(), row.getRefId(),
                row.getIsRead() != null && row.getIsRead() == 1, row.getCreatedAt());
    }

    /** 包级可见：单测要钉的是「这条写路径有没有落库」，而不是只有 public 入口。 */
    static String quote(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return "「" + text.strip() + "」";
    }

    /**
     * 评论/回复的辅助行：截到 {@value #EXCERPT_MAX} 字并在<b>真的截过</b>时补省略号。
     *
     * <p>省略号只在真截断时出现，是为了让「这句话就是全文」和「后面还有」在界面上可区分——
     * 通知里出现一个恒有的尾巴，等于永远在骗人。</p>
     */
    static String excerpt(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String body = text.strip();
        if (body.codePointCount(0, body.length()) > EXCERPT_MAX) {
            body = cut(body, EXCERPT_MAX) + "…";
        }
        return "「" + body + "」";
    }

    /**
     * 按<b>码点</b>截断（与举报描述同一口径）：一个 emoji 是一个码点两个 char，
     * 按 char 截会把 emoji 切成半个。列宽是字符数，MySQL 数的也是字符。
     */
    static String cut(String text, int maxCodePoints) {
        if (text == null) {
            return null;
        }
        int chars = text.codePointCount(0, text.length());
        if (chars <= maxCodePoints) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxCodePoints);
        return text.substring(0, end);
    }
}

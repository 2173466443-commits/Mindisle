package com.mindisle.pm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.DefaultResourceLoader;

import com.mindisle.ai.RiskScorer;
import com.mindisle.audit.SensitiveWordEngine;
import com.mindisle.cache.CaffeineCacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.AlertTicket;
import com.mindisle.entity.AuditTask;
import com.mindisle.entity.PrivateMessage;
import com.mindisle.entity.User;
import com.mindisle.pm.dto.PmAckView;
import com.mindisle.pm.dto.PmBlockItem;
import com.mindisle.pm.dto.PmBlockView;
import com.mindisle.pm.dto.PmConversationPage;
import com.mindisle.pm.dto.PmMessageView;
import com.mindisle.pm.dto.PmReadRequest;
import com.mindisle.pm.dto.PmReportRequest;
import com.mindisle.pm.dto.PmReportView;
import com.mindisle.pm.dto.PmSendRequest;
import com.mindisle.pm.dto.PmThreadPage;
import com.mindisle.pm.dto.PmUnreadView;
import com.mindisle.post.CrisisGrader;
import com.mindisle.post.PostingQuotaService;

/**
 * 私信域判据单测（任务 T5.2–T5.7 · 需求 FR6 · 手册 §8 的九条 DoD）。
 *
 * <p><b>本类钉的是「顺序」和「不做什么」，不是「能不能跑通」</b>。{@code PmService#send} 十三个
 * 步骤的先后本身就是判据：词库 BLOCK 排在拉黑之前，所以「拉黑 + 违禁词」报的是内容被拒而不是
 * 403；发信方资格排在收信方存在性之前，所以禁言号给注销号发信不会被误报成「查无此人」。
 * 每条顺序断言都对应注释里的一句「为什么在这一步」，改顺序必须先改这里。</p>
 *
 * <p><b>替身选型</b>：{@link PmFakeStore} 逐字复刻 {@code PrivateMessageMapper} 的谓词；
 * 在线状态与模型通道用<b>子类覆写</b>而不是 mock 框架（{@code PresenceRegistry#isOnline}
 * 与 {@code RiskScorer#score} 都是 public 非 final，且两者的构造器都不校验 null，
 * 于是 {@code super(null, null, properties)} 就是最便宜的替身）。仓库里既有约定也是如此：
 * {@code ReportServiceTest} / {@code NotifyServiceTest} 全部手写替身，测试树里 0 处 Mockito。</p>
 *
 * <p><b>刻意不测</b>：{@code PmStoreAdapter} 拼出的 SQL、{@code @TableLogic} 有没有真的补上
 * {@code deleted = 0}、STOMP 目的地能不能送达——那是接线，由 Gate5 的真 HTTP + 真 WebSocket
 * 探针（{@code frontend/probe/pmgate.mjs}）负责，不在这里假装测过。</p>
 */
class PmServiceTest {

    /** 固定时间基准：SLA 的 30 分钟 / 4 小时 / 24 小时全靠它算，服务内部不读墙上时钟。 */
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 9, 28, 10, 0);

    private static final long ME = 701L;
    private static final long PEER = 702L;
    private static final long THIRD = 703L;
    private static final long GONE = 704L;
    private static final long STRANGER = 705L;

    /**
     * <b>词面逐字抄自磁盘上的 {@code CrisisGrader.L3_WORDS}，不是凭记忆写的</b>：
     * 「割腕 / 结束生命 / 写遗书」在名单里所以命中即 L3，「伤害自己」不在名单里所以只到 L2。
     * 两处踩坑记录：① 近似词面（把「最后一次跟这里说说话」写成「最后一次跟这里说话」）不会命中；
     * ② 反过来，测试里想让它命中 L3，这个词必须先出现在词库里——{@code levelOf} 只看命中项。
     */
    private static final String FIXTURE = String.join("\n",
            "#version=test-pm-1",
            row("政治违法", "black", "BLOCK", "both", "contains", "枪支弹药"),
            row("辱骂攻击", "grey", "REVIEW", "user", "contains", "傻逼"),
            row("自伤自杀", "risk", "TAG", "both", "contains", "伤害自己"),
            row("自伤自杀", "risk", "TAG", "both", "contains", "割腕"),
            row("自伤自杀", "risk", "TAG", "both", "contains", "结束生命"),
            row("自伤自杀", "risk", "TAG", "both", "contains", "写遗书"));

    /** 同一套词库但<b>不含 L3 词</b>，用来演「管理员先上线了 L2 词、后来才加 L3 词」的抬级路径。 */
    private static final String FIXTURE_WITHOUT_L3 = String.join("\n",
            "#version=test-pm-l2-only",
            row("辱骂攻击", "grey", "REVIEW", "user", "contains", "傻逼"),
            row("自伤自杀", "risk", "TAG", "both", "contains", "伤害自己"));

    private RecordingStore store;
    private MindisleProperties properties;
    private SensitiveWordEngine engine;
    private RecordingPublisher events;
    private FakePresence presence;
    private FakeRiskScorer riskScorer;
    private PmService service;

    private static String row(String group, String level, String action, String scope,
                             String matchType, String word) {
        return String.join("\t", group, level, action, scope, matchType, word);
    }

    @BeforeEach
    void setUp() {
        properties = new MindisleProperties();
        engine = new SensitiveWordEngine(new MindisleProperties(), new DefaultResourceLoader());
        engine.reload(FIXTURE);
        store = new RecordingStore();
        store.withUser(activeUser(ME, "小屿"));
        store.withUser(activeUser(PEER, "安安"));
        store.withUser(activeUser(THIRD, "路人"));
        store.withUser(activeUser(GONE, "已注销"));
        store.withUser(activeUser(STRANGER, "无关的人"));
        store.users.get(GONE).setDeleted(1);
        events = new RecordingPublisher();
        presence = new FakePresence(properties);
        riskScorer = new FakeRiskScorer();
        service = newService(riskScorer);
    }

    /** 每次新建都换一枚新的限流缓存，测试之间不会互相吃掉对方的额度。 */
    private PmService newService(RiskScorer scorer) {
        return new PmService(store, properties, engine, new CaffeineCacheService(),
                new PostingQuotaService(new CaffeineCacheService(), properties), scorer, presence, events);
    }

    // ------------------------------------------------------------------ 造数据与断言助手

    private static User activeUser(long id, String nickname) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setNickname(nickname);
        user.setStatus("ACTIVE");
        user.setDeleted(0);
        user.setCreatedAt(DAY.minusDays(30));
        return user;
    }

    private PmMessageView send(long sender, Long to, String content, String clientMsgId) {
        return service.send(sender, new PmSendRequest(to, content, clientMsgId, null), DAY);
    }

    private PmMessageView sendToPeer(String content) {
        return send(ME, PEER, content, null);
    }

    private PrivateMessage lastRow() {
        return store.lastMessage();
    }

    private static ErrorCode codeOf(Runnable call) {
        return assertThrows(BizException.class, call::run).getErrorCode();
    }

    private static String messageOf(Runnable call) {
        return assertThrows(BizException.class, call::run).getMessage();
    }

    /** 落库行的风险等级（第一条消息），断言时别再重复一遍 Service 的字段名。 */
    private String lastRiskLevel() {
        return lastRow().getRiskLevel();
    }

    // ================================================================== A 参数与资格（send 的第 1–6 步）

    @Test
    @DisplayName("整个请求体缺失（null）走 10001，不是 500：Controller 允许 @RequestBody 为空，判据必须在 Service 兜住")
    void nullRequestIsParamInvalid() {
        assertThat(codeOf(() -> service.send(ME, null, DAY))).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(messageOf(() -> service.send(ME, null, DAY))).isEqualTo("私信内容不能是空的");
        assertThat(store.messages).isEmpty();
        assertThat(events.published).isEmpty();
    }

    @Test
    @DisplayName("没有收件人先报「收件人不能为空」，而不是内容不能为空：主语比宾语先到")
    void missingRecipientIsNamedSpecifically() {
        assertThat(codeOf(() -> service.send(ME, new PmSendRequest(null, "在吗", "k1", null), DAY)))
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(messageOf(() -> service.send(ME, new PmSendRequest(null, "在吗", "k1", null), DAY)))
                .isEqualTo("收件人不能为空");
    }

    @Test
    @DisplayName("自发私信挡在最前，并给出可执行的替代（草稿箱）：后面每一句文案里的「对方」都靠它才成立")
    void selfSendIsRejectedWithAnAlternative() {
        assertThat(messageOf(() -> send(ME, ME, "写给自己", "k1")))
                .isEqualTo("不能给自己发私信，写给自己可以用草稿箱");
        assertThat(store.messages).isEmpty();
    }

    @Test
    @DisplayName("全空白内容按码点算就是 0 个字：10001，且不占用任何后续判定")
    void blankContentIsRejected() {
        assertThat(codeOf(() -> sendToPeer("   \t\n  "))).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(messageOf(() -> sendToPeer("   "))).isEqualTo("私信内容不能是空的");
        assertThat(store.messages).isEmpty();
    }

    @Test
    @DisplayName("长度闸门按码点而不是 char：1000 个 emoji 能发，1001 个才被拒，报错里念出真实条数")
    void limitIsCountedInCodePointsNotChars() {
        String ok = "😀".repeat(properties.getPm().getMaxContentChars());
        String oneTooMany = ok + "😀";
        assertThat(ok.length()).as("char 数是 2000，用 char 判会误拒").isEqualTo(2000);

        assertThat(sendToPeer(ok).content()).isEqualTo(ok);
        assertThat(messageOf(() -> sendToPeer(oneTooMany)))
                .isEqualTo("私信最多 1000 个字，这条有 1001 个");
        assertThat(store.messages).hasSize(1);
    }

    @Test
    @DisplayName("幂等键只限长度、不做格式校验：64 字过、65 字拒，且这条判定排在内容判定之前（先认重试再挑刺）")
    void idempotencyKeyIsCappedAtSixtyFourChars() {
        assertThat(send(ME, PEER, "在吗", "k".repeat(64)).id()).isNotNull();
        assertThat(messageOf(() -> send(ME, PEER, "   ", "k".repeat(65))))
                .isEqualTo("幂等键最多 64 个字符");
        assertThat(store.messages).hasSize(1);
    }

    @Test
    @DisplayName("幂等键去首尾空白；空白等于「放弃去重」，服务端补 srv- 前缀的随机键，两次各落一行")
    void blankIdempotencyKeyBecomesServerUuid() {
        assertThat(send(ME, PEER, "第一句", "  abc  ").id()).isNotNull();
        assertThat(lastRow().getClientMsgId()).isEqualTo("abc");

        send(ME, PEER, "第二句", "   ");
        send(ME, PEER, "第三句", null);
        assertThat(store.messages).hasSize(3);
        assertThat(lastRow().getClientMsgId()).startsWith("srv-");
        assertThat(store.messages.get(1).getClientMsgId()).startsWith("srv-");
        assertThat(store.messages.get(1).getClientMsgId())
                .isNotEqualTo(store.messages.get(2).getClientMsgId());
    }

    @Test
    @DisplayName("类型白名单只有 text / image：自称 system 的消息会让「平台通知」变成用户可伪造的东西")
    void onlyTextAndImageAreUserTypes() {
        assertThat(service.send(ME, new PmSendRequest(PEER, "第一句", "k1", "  text  "), DAY)
                .msgType()).isEqualTo("text");
        assertThat(service.send(ME, new PmSendRequest(PEER, "第二句", "k2", null), DAY)
                .msgType()).as("类型缺失默认 text").isEqualTo("text");
        for (String illegal : List.of("system", "video", "TEXT")) {
            assertThat(codeOf(() -> service.send(ME, new PmSendRequest(PEER, "第三句", "k-" + illegal, illegal), DAY)))
                    .isEqualTo(ErrorCode.PARAM_INVALID);
            assertThat(messageOf(() -> service.send(ME, new PmSendRequest(PEER, "第三句", "x" + illegal, illegal), DAY)))
                    .isEqualTo("消息类型只能是文字或图片");
        }
        assertThat(store.messages).hasSize(2);
    }

    @Test
    @DisplayName("图片私信只认站内上传路径：外链图片会被第三方域名统计谁看过它，还常被拿来钓鱼")
    void imageMessagesMustPointAtUploadPath() {
        assertThat(service.send(ME, new PmSendRequest(PEER, "/uploads/a.png", "k1", "image"), DAY)
                .msgType()).isEqualTo("image");
        for (String external : List.of("https://evil.example/a.png", "/upload/a.png", "uploads/a.png")) {
            assertThat(messageOf(() -> service.send(ME, new PmSendRequest(PEER, external, "k" + external, "image"), DAY)))
                    .isEqualTo("图片私信只能指向站内上传的图片");
        }
        assertThat(store.messages).hasSize(1);
    }

    @Test
    @DisplayName("收件人不存在的三种形态合并成一个 404：查无此人 / deleted=1 / BANNED，接口不能用来探测账号状态")
    void everyGoneRecipientIsIndistinguishable() {
        User banned = activeUser(706L, "封禁号");
        banned.setStatus("BANNED");
        store.withUser(banned);
        User deletedRow = activeUser(707L, "已清除");
        deletedRow.setDeleted(1);
        store.withUser(deletedRow);

        assertThat(codeOf(() -> send(ME, 999999L, "在吗", null))).isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThat(codeOf(() -> send(ME, GONE, "在吗", null))).isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThat(codeOf(() -> send(ME, banned.getId(), "在吗", null))).isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThat(codeOf(() -> send(ME, deletedRow.getId(), "在吗", null))).isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThat(messageOf(() -> send(ME, GONE, "在吗", null))).isEqualTo("对方不存在或已注销，这条私信没有收件人");
        assertThat(store.messages).isEmpty();
    }

    @Test
    @DisplayName("发信方资格（第 5 步）排在收信方存在性（第 6 步）之前：禁言号给注销号发信报 403 而不是 404")
    void senderQualificationIsCheckedBeforeRecipientExistence() {
        store.users.get(ME).setStatus("MUTED");
        assertThat(codeOf(() -> send(ME, GONE, "在吗", null))).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(messageOf(() -> send(ME, GONE, "在吗", null)))
                .contains("禁言期");

        store.users.get(ME).setStatus("BANNED");
        assertThat(codeOf(() -> send(ME, GONE, "在吗", null))).isEqualTo(ErrorCode.USER_DISABLED);
    }

    @Test
    @DisplayName("发信人自己不在库里（令牌里的 id 已被物理清除）：USER_NOT_FOUND，绝不能变成 500")
    void missingSenderRowIsNotFoundNotInternalError() {
        store.users.remove(ME);
        assertThat(codeOf(() -> sendToPeer("在吗"))).isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThat(store.messages).isEmpty();
    }

    @Test
    @DisplayName("禁言的人收得到私信：收信不是「说」，所以 MUTED 只是发信方的闸门")
    void mutedRecipientStillReceives() {
        store.users.get(PEER).setStatus("MUTED");
        assertThat(sendToPeer("给你留句话").id()).isNotNull();
        assertThat(store.messages).hasSize(1);
    }

    @Test
    @DisplayName("词库 BLOCK 排在拉黑之前：拉黑状态下发违禁内容，报的是内容被拒（不给「绕过拦截」的错误印象）")
    void blockWordIsJudgedBeforePrivacyBlock() {
        store.insertBlock(PEER, ME, "别烦我");
        assertThat(codeOf(() -> sendToPeer("枪支弹药"))).isEqualTo(ErrorCode.CONTENT_REJECTED);
        assertThat(messageOf(() -> sendToPeer("枪支弹药"))).isEqualTo("私信内容包含违规信息，已拦截");
        assertThat(store.messages).isEmpty();
        assertThat(store.tickets).isEmpty();
    }

    // ================================================================== B 词库与危机建单（第 7–11 步）

    @Test
    @DisplayName("黑词拦下后一个字都不落：没有消息、没有工单、没有审核任务、没有事件")
    void blackWordLeavesNoTraceAtAll() {
        assertThat(codeOf(() -> sendToPeer("这里有人卖枪支弹药"))).isEqualTo(ErrorCode.CONTENT_REJECTED);
        assertThat(store.messages).isEmpty();
        assertThat(store.tickets).isEmpty();
        assertThat(store.auditTasks).isEmpty();
        assertThat(events.published).isEmpty();
    }

    @Test
    @DisplayName("L2 词面：落库带等级、建一张挂在发信方名下的工单（SLA +4 小时）、一张机审任务、一个事件")
    void l2WordCreatesTicketAndMachineTask() {
        PmMessageView view = sendToPeer("我想伤害自己");

        assertThat(view.riskLevel()).isEqualTo(CrisisGrader.L2);
        assertThat(lastRiskLevel()).isEqualTo(CrisisGrader.L2);

        assertThat(store.tickets).hasSize(1);
        AlertTicket ticket = store.tickets.get(0);
        assertThat(ticket.getUserId()).isEqualTo(ME);
        assertThat(ticket.getSourceType()).isEqualTo("pm");
        assertThat(ticket.getSourceId()).isEqualTo(lastRow().getId());
        assertThat(ticket.getLevel()).isEqualTo(CrisisGrader.L2);
        assertThat(ticket.getStatus()).as("alert_ticket.status 的ENUM是小写档").isEqualTo("pending");
        assertThat(ticket.getSlaAt()).isEqualTo(DAY.plusHours(properties.getCrisis().getL2SlaHours()));
        assertThat(ticket.getRiskScore()).isEqualByComparingTo("0.600");
        assertThat(ticket.getRiskScore().scale()).as("与帖子/评论工单同一把尺子：三位小数").isEqualTo(3);
        assertThat(ticket.getTriggerWords()).contains("伤害自己");
        assertThat(ticket.getEvidenceText()).contains("伤害自己");

        assertThat(store.auditTasks).hasSize(1);
        AuditTask task = store.auditTasks.get(0);
        assertThat(task.getTargetType()).isEqualTo("pm");
        assertThat(task.getTargetId()).isEqualTo(lastRow().getId());
        assertThat(task.getSource()).isEqualTo(AuditTask.SOURCE_MACHINE);
        assertThat(task.getChannel()).isEqualTo(AuditTask.CHANNEL_DFA);
        assertThat(task.getResult()).isEqualTo("RISK");
        assertThat(task.getStatus()).isEqualTo(AuditTask.STATUS_PENDING);
        assertThat(task.getRiskLevel()).isEqualTo(CrisisGrader.L2);
        assertThat(task.getSlaAt()).isEqualTo(DAY.plusHours(properties.getCrisis().getL2SlaHours()));
        assertThat(task.getRemark()).contains("私信机审").contains("等级=L2");

        assertThat(events.published).singleElement().isInstanceOf(PmSendEvent.class);
    }

    @Test
    @DisplayName("L3 词面走 30 分钟 SLA，且工单与审核任务的时限各自取自 crisis 配置")
    void l3WordUsesThirtyMinuteSla() {
        sendToPeer("我打算割腕");

        assertThat(lastRiskLevel()).isEqualTo(CrisisGrader.L3);
        assertThat(store.tickets).hasSize(1);
        assertThat(store.tickets.get(0).getSlaAt())
                .isEqualTo(DAY.plusMinutes(properties.getCrisis().getL3SlaMinutes()));
        assertThat(store.tickets.get(0).getRiskScore()).isEqualByComparingTo("0.900");
        assertThat(store.auditTasks).hasSize(1);
        assertThat(store.auditTasks.get(0).getSlaAt())
                .isEqualTo(DAY.plusMinutes(properties.getCrisis().getL3SlaMinutes()));
        assertThat(store.auditTasks.get(0).getRiskLevel()).isEqualTo(CrisisGrader.L3);
        assertThat(store.auditTasks.get(0).getResult()).as("L3 也是机审结论，不是等级字段").isEqualTo("RISK");
    }

    @Test
    @DisplayName("机审结论看「有没有」不看「排第几」：辱骂(REVIEW)+自伤(TAG) 的结论是 REVIEW，等级仍是 L3")
    void verdictLooksAtPresenceAndLevelAtSeverityIndependently() {
        sendToPeer("你这个傻逼，我要割腕");

        assertThat(lastRiskLevel()).as("等级取最严重的风险命中").isEqualTo(CrisisGrader.L3);
        assertThat(store.auditTasks).hasSize(1);
        assertThat(store.auditTasks.get(0).getResult())
                .as("action 字段是主因（grey/REVIEW 被压在 risk 之后），照 PostService 口径复刻")
                .isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("普通内容：L0、零工单、零审核任务，但事件照发（私信不该因为「没风险」就不推送）")
    void cleanContentStillEmitsExactlyOneEvent() {
        PmMessageView view = sendToPeer("今天好一些了，谢谢你听我说");

        assertThat(view.riskLevel()).isEqualTo(CrisisGrader.L0);
        assertThat(view.alert()).as("非危机不落求助卡").isNull();
        assertThat(store.tickets).isEmpty();
        assertThat(store.auditTasks).isEmpty();
        assertThat(events.published).hasSize(1);
    }

    @Test
    @DisplayName("求助卡分视角：发信方看到「已经帮你留在待审队列」，收信方看到「对方此刻可能很需要支持」")
    void crisisAlertTextDependsOnWhoIsLooking() {
        PmMessageView view = sendToPeer("我想伤害自己");
        assertThat(view.mine()).isTrue();
        assertThat(view.alert()).contains("待审队列").contains(properties.getCrisis().getHotline());

        PmMessageView forPeer = service.viewForReceiver(lastRow());
        assertThat(forPeer.mine()).isFalse();
        assertThat(forPeer.alert()).contains("对方此刻").contains(properties.getCrisis().getHotline());
        assertThat(forPeer.alert()).isNotEqualTo(view.alert());
    }

    @Test
    @DisplayName("事件带的是落库后的真实 id 与工单 id：监听器不该为了拿这两个值再查一次库")
    void sendEventCarriesPersistedRowAndTicketId() {
        PmMessageView view = sendToPeer("我说过想结束生命");

        assertThat(events.published).hasSize(1);
        PmSendEvent event = (PmSendEvent) events.published.get(0);
        assertThat(event.senderId()).isEqualTo(ME);
        assertThat(event.recipientId()).isEqualTo(PEER);
        assertThat(event.row().getId()).isEqualTo(view.id());
        assertThat(event.senderName()).isEqualTo("小屿");
        assertThat(event.crisisLevel()).isEqualTo(CrisisGrader.L3);
        assertThat(event.ticketId()).isEqualTo(store.tickets.get(0).getId());
        assertThat(event.crisisExcerpt()).contains("结束生命");
        assertThat(event.at()).isEqualTo(DAY);
    }

    @Test
    @DisplayName("普通私信只把等级带进事件（L0），工单号与证据留空：监听器按 TICKET_LEVELS 判要不要弹管理员，不按 null 判")
    void ordinaryMessageEventCarriesNoCrisisFields() {
        sendToPeer("明天见");
        PmSendEvent event = (PmSendEvent) events.published.get(0);
        assertThat(event.crisisLevel()).as("等级恒有值，缺它才是异常").isEqualTo(CrisisGrader.L0);
        assertThat(event.ticketId()).isNull();
        assertThat(event.crisisExcerpt()).isNull();
    }

    // ================================================================== C 幂等与隐私拦截（第 2、8、10 步）

    @Test
    @DisplayName("同一个 clientMsgId 发两次：库里一行、事件一个、返回同一个 id（重试风暴的反义词）")
    void duplicateClientMsgIdIsAbsorbed() {
        PmMessageView first = send(ME, PEER, "刚才那句可能没送达", "dup-1");
        PmMessageView again = send(ME, PEER, "刚才那句可能没送达", "dup-1");

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(store.messages).hasSize(1);
        assertThat(events.published).hasSize(1);
        assertThat(again.status()).isEqualTo("sent");
    }

    @Test
    @DisplayName("幂等重发不建第二张危机工单：一次求助只该有一个人跟进，而不是每重试一次多一张单")
    void idempotentReplayDoesNotDoubleFileTheCrisis() {
        send(ME, PEER, "我想伤害自己", "dup-crisis");
        send(ME, PEER, "我想伤害自己", "dup-crisis");

        assertThat(store.messages).hasSize(1);
        assertThat(store.tickets).as("工单只有一张").hasSize(1);
        assertThat(store.auditTasks).as("待审任务也只有一张").hasSize(1);
        assertThat(events.published).hasSize(1);
    }

    @Test
    @DisplayName("同 key 换个收件人不冲突：uk_pair_msg 是 (client_msg_id, to_user_id) 联合键")
    void sameKeyDifferentRecipientIsNotACollision() {
        send(ME, PEER, "同一句话", "shared-key");
        send(ME, THIRD, "同一句话", "shared-key");

        assertThat(store.messages).hasSize(2);
        assertThat(events.published).hasSize(2);
    }

    @Test
    @DisplayName("唯一键看不见 deleted：软删行照样占键，回查又为空时只能是 90004，不能把读不到的行当成功")
    void softDeletedRowStillOccupiesTheUniqueKey() {
        send(ME, PEER, "这句话被清掉了", "gone-key");
        store.messages.get(0).setDeleted(1);

        assertThat(codeOf(() -> send(ME, PEER, "这句话被清掉了", "gone-key")))
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(store.messages).as("没有新增行").hasSize(1);
        assertThat(events.published).as("第一次成功发送的事件还在，第二次不许再补一个").hasSize(1);
    }

    @Test
    @DisplayName("双向拉黑都是 403/30005，且一个字都不入库：我拉黑他、他拉黑我，判据完全对称")
    void eitherDirectionOfBlockRejectsTheSend() {
        store.insertBlock(ME, PEER, "先冷静一下");
        assertThat(codeOf(() -> sendToPeer("你好"))).isEqualTo(ErrorCode.PM_BLOCKED);
        store.deleteBlock(ME, PEER);

        store.insertBlock(PEER, ME, "别烦我");
        assertThat(codeOf(() -> sendToPeer("你好"))).isEqualTo(ErrorCode.PM_BLOCKED);
        assertThat(store.messages).isEmpty();
        assertThat(events.published).isEmpty();
        assertThat(store.tickets).isEmpty();
    }

    @Test
    @DisplayName("被拉黑 + 危机词：私信照拦，但工单照建，source_id 落 0（拦下私信和救下人是两条独立的线）")
    void blockedCrisisStillFilesATicketAgainstTheSender() {
        store.insertBlock(PEER, ME, "别烦我");

        assertThat(codeOf(() -> sendToPeer("我准备写遗书了"))).isEqualTo(ErrorCode.PM_BLOCKED);
        assertThat(store.messages).as("没送达的话不进库，避免管理员以为对方看到了").isEmpty();
        assertThat(store.auditTasks).as("没有可审的 target，所以不建审核任务").isEmpty();
        assertThat(events.published).isEmpty();

        assertThat(store.tickets).hasSize(1);
        AlertTicket ticket = store.tickets.get(0);
        assertThat(ticket.getUserId()).as("工单挂发信方").isEqualTo(ME);
        assertThat(ticket.getSourceId()).as("无消息可指，落 0 而不是 null").isEqualTo(0L);
        assertThat(ticket.getLevel()).isEqualTo(CrisisGrader.L3);
        assertThat(ticket.getEvidenceText()).as("证据全文就在工单里，管理员不需要那条消息").contains("写遗书");
    }

    @Test
    @DisplayName("解除拉黑后同一句话能正常送达：拦的是「以后的这条」，不是把这个人永久拉进黑洞")
    void unblockRestoresDeliverability() {
        store.insertBlock(ME, PEER, "先冷静一下");
        assertThat(codeOf(() -> send(ME, PEER, "我还是想聊聊", "after-block"))).isEqualTo(ErrorCode.PM_BLOCKED);

        assertThat(service.unblock(ME, PEER).changed()).isTrue();
        assertThat(send(ME, PEER, "我还是想聊聊", "after-block").id()).isNotNull();
        assertThat(store.messages).hasSize(1);
    }

    // ================================================================== D 读路径（T5.3 / T5.5）

    @Test
    @DisplayName("历史翻成正序、被拉黑也照样能读完整历史：拉黑拦的是「以后」，不是让聊天记录失忆")
    void threadIsChronologicalAndStillReadableWhenBlocked() {
        store.seedMessage(ME, PEER, "第一句", "text", "delivered", "L0");
        store.seedMessage(PEER, ME, "第二句", "text", "sent", "L0");
        store.seedMessage(ME, PEER, "第三句", "text", "sent", "L0");
        store.insertBlock(PEER, ME, "别烦我");

        PmThreadPage page = service.thread(ME, PEER, null, null);
        assertThat(page.list()).extracting(PmMessageView::content)
                .containsExactly("第一句", "第二句", "第三句");
        assertThat(page.blocked()).isTrue();
        assertThat(page.list().get(0).mine()).isTrue();
        assertThat(page.list().get(1).mine()).isFalse();
        assertThat(page.nextCursor()).isEqualTo(1L);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.peerName()).isEqualTo("安安");
    }

    @Test
    @DisplayName("游标是「本页最旧那条」：下一页拿它当 beforeId 正好接上，不会重也不会漏")
    void cursorIsTheOldestRowOnThePage() {
        for (int i = 1; i <= 5; i++) {
            store.seedMessage(ME, PEER, "第" + i + "句", "text", "sent", "L0");
        }

        PmThreadPage first = service.thread(ME, PEER, null, 2);
        assertThat(first.list()).extracting(PmMessageView::content).containsExactly("第4句", "第5句");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isEqualTo(4L);

        PmThreadPage second = service.thread(ME, PEER, first.nextCursor(), 2);
        assertThat(second.list()).extracting(PmMessageView::content).containsExactly("第2句", "第3句");
        assertThat(second.nextCursor()).isEqualTo(2L);
    }

    @Test
    @DisplayName("分页 size 的上限是 fetchMax 而不是 maxSize：size=1000 时端口只收到 101（+1 用来判 hasMore）")
    void sizeIsNormalizedToFetchMax() {
        store.seedMessage(ME, PEER, "hi", "text", "sent", "L0");
        service.thread(ME, PEER, null, 1000);
        assertThat(store.lastPageLimit).isEqualTo(properties.getPm().getFetchMax() + 1);
        service.thread(ME, PEER, null, null);
        assertThat(store.lastPageLimit).isEqualTo(properties.getPm().getDefaultSize() + 1);
        service.thread(ME, PEER, null, 0);
        assertThat(store.lastPageLimit).isEqualTo(properties.getPm().getDefaultSize() + 1);
        service.thread(ME, PEER, null, 5);
        assertThat(store.lastPageLimit).isEqualTo(6);
    }

    @Test
    @DisplayName("会话对象是自己=10001、对方已注销=404：和 send 同一套口径，读接口不能比写接口宽松")
    void threadRejectsSelfAndGonePeer() {
        assertThat(codeOf(() -> service.thread(ME, ME, null, null))).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(codeOf(() -> service.thread(ME, GONE, null, null))).isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThat(codeOf(() -> service.thread(ME, 999999L, null, null))).isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("补拉与翻页是同一份判据（refill 直接委托 thread）：服务端不记「你拉到哪了」")
    void refillIsTheSameJudgementAsThread() {
        store.seedMessage(ME, PEER, "一", "text", "sent", "L0");
        store.seedMessage(ME, PEER, "二", "text", "sent", "L0");

        PmThreadPage byCursor = service.thread(ME, PEER, 2L, 10);
        PmThreadPage byRefill = service.refill(ME, PEER, 2L, 10);
        assertThat(byRefill).isEqualTo(byCursor);
        assertThat(byRefill.list()).extracting(PmMessageView::content).containsExactly("一");
    }

    @Test
    @DisplayName("upToId 为空=整段已读：端口收到 Long.MAX_VALUE，而不是在接口上开第二条方法")
    void markReadWithoutUpToIdReadsEverything() {
        store.seedMessage(PEER, ME, "在吗", "text", "sent", "L0");
        store.seedMessage(PEER, ME, "早点睡", "text", "delivered", "L0");

        PmAckView ack = service.markRead(ME, new PmReadRequest(PEER, null), DAY);
        assertThat(ack.kind()).isEqualTo("read");
        assertThat(ack.count()).isEqualTo(2);
        assertThat(ack.unread()).isZero();
        assertThat(ack.peerId()).isEqualTo(PEER);
        assertThat(ack.at()).isEqualTo(DAY);
        assertThat(store.lastMarkReadUpToId).isEqualTo(Long.MAX_VALUE);
        assertThat(store.messages).allMatch(row -> "read".equals(row.getStatus()));
        assertThat(events.published).hasSize(1);
    }

    @Test
    @DisplayName("已读不回刷 read_at：第二次上报是 0 行、不发事件，两个标签页不会把已读动画播两遍")
    void markReadOnlyFlipsUnreadRowsAndStaysSilentAtZero() {
        store.seedMessage(PEER, ME, "在吗", "text", "sent", "L0");
        store.seedMessage(PEER, ME, "早点睡", "text", "sent", "L0");

        assertThat(service.markRead(ME, new PmReadRequest(PEER, 1L), DAY).count())
                .as("只读到第一条").isEqualTo(1);
        assertThat(store.messages.get(0).getReadAt()).isEqualTo(DAY);
        assertThat(store.messages.get(1).getStatus()).isEqualTo("sent");
        assertThat(store.messages.get(1).getReadAt()).isNull();

        PmAckView repeat = service.markRead(ME, new PmReadRequest(PEER, 1L), DAY);
        assertThat(repeat.count()).isZero();
        assertThat(events.published).as("0 行不发已读事件").hasSize(1);
        assertThat(store.messages.get(0).getReadAt()).as("已读时间不会被第二次上报刷掉").isEqualTo(DAY);
    }

    @Test
    @DisplayName("已读只清「我收到的」那一半：我把会话标记已读不会动对方屏幕上的行，也不会吞别人的未读")
    void markReadOnlyTouchesMyOwnInbox() {
        store.seedMessage(ME, PEER, "我发出去的", "text", "sent", "L0");
        store.seedMessage(THIRD, ME, "别人的未读", "text", "sent", "L0");

        assertThat(service.markRead(ME, new PmReadRequest(PEER, null), DAY).count()).isZero();
        assertThat(store.messages.get(0).getStatus()).isEqualTo("sent");
        assertThat(store.messages.get(1).getStatus()).isEqualTo("sent");
        assertThat(events.published).isEmpty();
    }

    @Test
    @DisplayName("已读上报缺 peerId=10001、peer 是自己=10001：STOMP /app/read 与 REST 共用这一条判据")
    void markReadRejectsMissingOrSelfPeer() {
        assertThat(codeOf(() -> service.markRead(ME, new PmReadRequest(null, null), DAY)))
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(messageOf(() -> service.markRead(ME, new PmReadRequest(null, null), DAY)))
                .isEqualTo("缺少会话对象");
        assertThat(codeOf(() -> service.markRead(ME, new PmReadRequest(ME, null), DAY)))
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(codeOf(() -> service.markRead(ME, null, DAY))).isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("会话列表取 limit+1 判 hasMore、游标是本页最旧那条尾巴、未读总数走单独的 countUnreadTotal")
    void conversationsPageByLimitPlusOneAndCarryTheUnreadBadge() {
        store.seedMessage(ME, PEER, "早", "text", "read", "L0");
        store.seedMessage(PEER, ME, "早啊", "text", "sent", "L0");
        store.seedMessage(ME, THIRD, "/uploads/a.png", "image", "sent", "L0");

        PmConversationPage page = service.conversations(ME, null, 1);
        assertThat(store.lastTailsLimit).isEqualTo(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.list()).hasSize(1);
        assertThat(page.list().get(0).peerId()).isEqualTo(THIRD);
        assertThat(page.list().get(0).lastContent()).as("图片摘要不落外链").isEqualTo("[图片]");
        assertThat(page.list().get(0).lastMine()).isTrue();
        assertThat(page.list().get(0).unreadCnt()).isZero();
        assertThat(page.nextCursor()).isEqualTo(3L);
        assertThat(page.unreadTotal()).isEqualTo(1);

        PmConversationPage all = service.conversations(ME, null, 10);
        assertThat(all.hasMore()).isFalse();
        assertThat(all.list()).hasSize(2);
        assertThat(all.list().get(0).peerId()).isEqualTo(THIRD);
        assertThat(all.list().get(1).peerId()).isEqualTo(PEER);
        assertThat(all.list().get(1).lastContent()).isEqualTo("早啊");
        assertThat(all.list().get(1).unreadCnt()).isEqualTo(1);
        assertThat(all.nextCursor()).as("游标是本页最小的那条尾句 id（2），不是我发出的第一条消息（1）")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("摘要 60 个码点封顶：列表页一行放不下 100 字，长正文不能被拖成整页")
    void conversationPreviewIsCappedAtSixtyCodePoints() {
        store.seedMessage(ME, PEER, "话".repeat(200), "text", "read", "L0");
        PmConversationPage page = service.conversations(ME, null, 10);
        assertThat(page.list().get(0).lastContent()).hasSize(60);
    }

    @Test
    @DisplayName("未读视图只带未读 > 0 的人：200 个老同事的账号不该每次轮询拖 200 个键")
    void unreadViewOmitsZeroUnreadPeers() {
        store.seedMessage(PEER, ME, "在吗", "text", "sent", "L0");
        store.seedMessage(PEER, ME, "再一句", "text", "delivered", "L0");
        store.seedMessage(THIRD, ME, "已经读过了", "text", "read", "L0");

        PmUnreadView view = service.unreadView(ME);
        assertThat(view.total()).isEqualTo(2);
        assertThat(view.byPeer()).containsOnlyKeys(PEER);
        assertThat(view.byPeer().get(PEER)).isEqualTo(2);
        assertThat(view.byPeer()).doesNotContainKey(THIRD);
    }

    @Test
    @DisplayName("重投候选只看「还是 sent 且创建早于 now - 宽限期」：没有宽限期时每封信都会被推两遍")
    void undeliveredCandidatesRespectTheGraceWindow() {
        store.seedMessage(ME, PEER, "老的没送达", "text", "sent", "L0");
        store.clock = DAY;
        store.seedMessage(ME, PEER, "刚发的", "text", "sent", "L0");
        store.seedMessage(ME, THIRD, "已送达的", "text", "delivered", "L0");

        List<PrivateMessage> rows = service.undeliveredCandidates(DAY);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getContent()).isEqualTo("老的没送达");
        assertThat(store.lastUndeliveredBefore).isEqualTo(DAY.minusSeconds(3));
        assertThat(store.lastUndeliveredLimit).isEqualTo(50);
    }

    @Test
    @DisplayName("重投是替收件人渲染的：视图里 mine=false，不能把「我发的」气泡样式推给对方")
    void retryViewIsRenderedForTheReceiver() {
        PrivateMessage row = store.seedMessage(ME, PEER, "重投我", "text", "sent", "L2");
        PmMessageView view = service.viewForReceiver(row);
        assertThat(view.mine()).isFalse();
        assertThat(view.alert()).contains("对方此刻");
    }

    @Test
    @DisplayName("在线状态只回答「你问的这些人里谁在线」，不广播名单（FR6.5 的最小暴露面）")
    void presenceOnlyAnswersTheAskedPeers() {
        presence.onlineIds.add(PEER);
        presence.onlineIds.add(STRANGER);
        assertThat(service.presenceOf(ME, List.of(PEER, THIRD)).online()).containsExactly(PEER);
        assertThat(service.presenceOf(ME, List.of(THIRD)).online()).isEmpty();
        properties.getPm().setPresenceEnabled(false);
        assertThat(service.presenceOf(ME, List.of(PEER)).online()).isEmpty();
    }

    // ================================================================== E 拉黑名单与举报（T5.7）

    @Test
    @DisplayName("拉黑原因是可选的：整段空白落 null 而不是空串，纯空格也当没填")
    void blankBlockReasonIsStoredAsNull() {
        assertThat(service.block(ME, PEER, "   ").changed()).isTrue();
        assertThat(store.blocks).hasSize(1);
        assertThat(store.blocks.get(0).getReason()).isNull();

        service.block(ME, THIRD, "  就是不想聊  ");
        assertThat(store.blocks.get(1).getReason()).isEqualTo("就是不想聊");
    }

    @Test
    @DisplayName("拉黑原因 200 字封顶：超长是 10001 并念出上限，而不是让 MySQL 静默截断")
    void overlongBlockReasonIsRejected() {
        assertThat(service.block(ME, PEER, "原".repeat(200)).changed()).isTrue();
        assertThat(messageOf(() -> service.block(ME, THIRD, "原".repeat(201))))
                .isEqualTo("拉黑原因最多 200 个字");
        assertThat(store.blocks).hasSize(1);
    }

    @Test
    @DisplayName("重复拉黑不是错误：changed=false / blocked=true，库里还是那一行（前端不该把成功翻译成 409）")
    void blockIsIdempotent() {
        assertThat(service.block(ME, PEER, "先冷静").changed()).isTrue();
        PmBlockView second = service.block(ME, PEER, "又点了一次");
        assertThat(second.changed()).isFalse();
        assertThat(second.blocked()).isTrue();
        assertThat(second.action()).isEqualTo("block");
        assertThat(second.targetId()).isEqualTo(PEER);
        assertThat(store.blocks).hasSize(1);
        assertThat(store.blocks.get(0).getReason()).as("第二次不许覆盖第一次的原因").isEqualTo("先冷静");
    }

    @Test
    @DisplayName("拉黑自己、拉黑查无此人、缺 targetId 各归各的错：id 非法不能伪装成「用户不存在」")
    void blockRejectsSelfAndUnknownTarget() {
        assertThat(messageOf(() -> service.block(ME, ME, "我"))).isEqualTo("不能拉黑自己");
        assertThat(messageOf(() -> service.block(ME, null, "我"))).isEqualTo("缺少目标用户 id");
        assertThat(messageOf(() -> service.block(ME, 0L, "我"))).isEqualTo("缺少目标用户 id");
        assertThat(codeOf(() -> service.block(ME, 999999L, "我"))).isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThat(store.blocks).isEmpty();
    }

    @Test
    @DisplayName("没拉黑过的人解除拉黑：changed=false，不是错误也不是「恢复成功」")
    void unblockIsHonestAboutDoingNothing() {
        PmBlockView view = service.unblock(ME, PEER);
        assertThat(view.changed()).isFalse();
        assertThat(view.action()).isEqualTo("unblock");
        assertThat(view.blocked()).isFalse();

        service.block(ME, PEER, null);
        assertThat(service.unblock(ME, PEER).changed()).isTrue();
        assertThat(service.unblock(ME, PEER).changed()).isFalse();
        assertThat(store.blocks).isEmpty();
    }

    @Test
    @DisplayName("名单里保留已被清除的对方，用「已注销用户」占位：否则那一行永远查不到是谁")
    void blocksListKeepsPurgedPeersVisible() {
        service.block(ME, PEER, "还想留着这条");
        service.block(ME, THIRD, "删了档案");
        store.users.remove(THIRD);

        List<PmBlockItem> items = service.blocks(ME);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).peerId()).as("倒序：后拉黑的排前面").isEqualTo(THIRD);
        assertThat(items.get(0).peerName()).isEqualTo("已注销用户");
        assertThat(items.get(0).peerAvatar()).isEmpty();
        assertThat(items.get(1).peerName()).isEqualTo("安安");
        assertThat(items.get(1).reason()).isEqualTo("还想留着这条");
    }

    @Test
    @DisplayName("举报私信不写 content_report，直接建 audit_task：ENUM 里没有 pm，塞进去只会被 MySQL 拒")
    void reportFilesAnAuditTaskDirectly() {
        PrivateMessage row = store.seedMessage(PEER, ME, "骂人的话", "text", "read", "L0");

        PmReportView view = service.report(ME, new PmReportRequest(row.getId(), "abuse", "太凶了"), DAY);
        assertThat(view.status()).isEqualTo(AuditTask.STATUS_PENDING);
        assertThat(view.tip()).contains("24 小时").contains("看完这条私信");

        assertThat(store.auditTasks).hasSize(1);
        AuditTask task = store.auditTasks.get(0);
        assertThat(task.getTargetType()).isEqualTo("pm");
        assertThat(task.getTargetId()).isEqualTo(row.getId());
        assertThat(task.getSource()).isEqualTo(AuditTask.SOURCE_REPORT);
        assertThat(task.getChannel()).isEqualTo(AuditTask.CHANNEL_DFA);
        assertThat(task.getStatus()).isEqualTo(AuditTask.STATUS_PENDING);
        assertThat(task.getRiskLevel()).isEqualTo(CrisisGrader.L0);
        assertThat(task.getRiskScore()).isEqualByComparingTo("0.000");
        assertThat(task.getRemark()).contains("私信举报").contains("攻击辱骂(abuse)")
                .contains("举报人=" + ME).contains("会话对方=" + PEER).contains("补充=太凶了");
        assertThat(view.taskId()).isEqualTo(task.getId());
    }

    @Test
    @DisplayName("理由必须在那六类里；整段空白当「其他」，报错文案直接念出下拉顺序（LinkedHashMap 的键序）")
    void reportReasonIsWhitelistedAndBlankBecomesOther() {
        PrivateMessage row = store.seedMessage(PEER, ME, "随便什么", "text", "read", "L0");
        assertThat(messageOf(() -> service.report(ME, new PmReportRequest(row.getId(), "harassment", null), DAY)))
                .isEqualTo("举报理由只能是：spam / abuse / sexual / privacy / self-harm / other");

        service.report(ME, new PmReportRequest(row.getId(), "   ", null), DAY);
        assertThat(store.auditTasks.get(0).getRemark()).contains("其他(other)");
        service.report(PEER, new PmReportRequest(row.getId(), "self-harm", null), DAY);
        assertThat(store.auditTasks).as("同一目标只有一张待审").hasSize(1);
    }

    @Test
    @DisplayName("举报的六类顺序是前端下拉的顺序本身：写成 HashMap 会让这个顺序每天漂一次")
    void reportLabelOrderIsTheDropdownOrder() {
        assertThat(PmService.REPORT_LABELS.keySet())
                .containsExactly("spam", "abuse", "sexual", "privacy", "self-harm", "other");
        assertThat(PmService.REPORT_LABELS.values())
                .containsExactly("广告骚扰", "攻击辱骂", "色情低俗", "泄露隐私", "自伤风险", "其他");
    }

    // ================================================================== E2 举报的边界与等级升级

    @Test
    @DisplayName("缺 id 与查无此条分开：10001 让前端清红点，30006 让它把这条从列表里摘掉")
    void reportValidatesTheTargetPointer() {
        assertThat(codeOf(() -> service.report(ME, null, DAY))).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(messageOf(() -> service.report(ME, new PmReportRequest(null, "abuse", null), DAY)))
                .isEqualTo("缺少被举报的消息 id");
        assertThat(messageOf(() -> service.report(ME, new PmReportRequest(0L, "abuse", null), DAY)))
                .isEqualTo("缺少被举报的消息 id");
        assertThat(codeOf(() -> service.report(ME, new PmReportRequest(-7L, "abuse", null), DAY)))
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(codeOf(() -> service.report(ME, new PmReportRequest(424242L, "abuse", null), DAY)))
                .isEqualTo(ErrorCode.PM_NOT_FOUND);
        assertThat(store.auditTasks).isEmpty();
    }

    @Test
    @DisplayName("第三人拿自增 id 举报别人的私信只能得到 30006：otherSide 分不出「我是发信方」和「我不是当事人」，越权靠显式核对挡")
    void strangerCannotFeedAuditQueueWithOtherPeersMessages() {
        PrivateMessage row = store.seedMessage(PEER, ME, "骂人的话", "text", "read", "L0");
        assertThat(codeOf(() -> service.report(STRANGER, new PmReportRequest(row.getId(), "abuse", null), DAY)))
                .isEqualTo(ErrorCode.PM_NOT_FOUND);
        assertThat(store.auditTasks).as("越权举报连一条待审都不该留下").isEmpty();

        PrivateMessage selfDirected = store.seedMessage(ME, ME, "写给自己的那句", "text", "read", "L0");
        assertThat(codeOf(() -> service.report(ME, new PmReportRequest(selfDirected.getId(), "abuse", null), DAY)))
                .as("两端都是我自己，不构成会话").isEqualTo(ErrorCode.PM_NOT_FOUND);
        assertThat(store.auditTasks).isEmpty();
    }

    @Test
    @DisplayName("先按 L2 建单、词库补上 L3 词后再举报：同一张待审被抬级，不新建第二张；词库撤回也不降级")
    void reportEscalatesThePendingTaskAndNeverLowersIt() {
        engine.reload(FIXTURE_WITHOUT_L3);
        service = newService(riskScorer);
        PrivateMessage row = store.seedMessage(PEER, ME, "我想伤害自己，也想结束生命", "text", "read", "L0");

        PmReportView first = service.report(ME, new PmReportRequest(row.getId(), "self-harm", null), DAY);
        assertThat(first.tip()).as("这份库里它只到 L2，就说 4 小时").contains("4 小时");
        assertThat(store.auditTasks.get(0).getRiskLevel()).isEqualTo(CrisisGrader.L2);
        assertThat(store.auditTasks.get(0).getSlaAt()).isEqualTo(DAY.plusHours(4));

        engine.reload(FIXTURE);
        service = newService(riskScorer);
        PmReportView second = service.report(PEER, new PmReportRequest(row.getId(), "self-harm", "更严重了"), DAY);
        assertThat(second.tip()).contains("1 小时");
        assertThat(store.auditTasks).as("uk_target_pending 兜住了，不该有第二张").hasSize(1);
        AuditTask task = store.auditTasks.get(0);
        assertThat(second.taskId()).isEqualTo(task.getId());
        assertThat(task.getRiskLevel()).isEqualTo(CrisisGrader.L3);
        assertThat(task.getRiskScore()).isEqualByComparingTo("0.900");
        assertThat(task.getSlaAt()).as("时限跟着改到 30 分钟").isEqualTo(DAY.plusMinutes(30));
        assertThat(task.getUpdatedAt()).isEqualTo(DAY);
        assertThat(task.getRemark()).as("抬级不重写备注：留的是第一次受理时的举报人")
                .contains("举报人=" + ME).doesNotContain("举报人=" + PEER);

        engine.reload(FIXTURE_WITHOUT_L3);
        service = newService(riskScorer);
        service.report(ME, new PmReportRequest(row.getId(), "self-harm", null), DAY);
        assertThat(store.auditTasks.get(0).getRiskLevel()).as("管理员撤词不能让已经升上去的工单落回 L2")
                .isEqualTo(CrisisGrader.L3);
        assertThat(store.auditTasks).hasSize(1);
    }

    @Test
    @DisplayName("回执里念的小时数跟着等级走：L3 的 30 分钟说成「1 小时」，而不是「0.5 小时」或一律 24 小时")
    void reportTipUsesTheLevelSpecificSla() {
        PrivateMessage urgent = store.seedMessage(PEER, ME, "我要结束生命", "text", "read", "L0");
        assertThat(service.report(ME, new PmReportRequest(urgent.getId(), "self-harm", null), DAY).tip())
                .isEqualTo("已收到，我们会在 1 小时内看完这条私信。");

        engine.reload(FIXTURE_WITHOUT_L3);
        service = newService(riskScorer);
        PrivateMessage risky = store.seedMessage(PEER, ME, "他想伤害自己", "text", "read", "L0");
        assertThat(service.report(ME, new PmReportRequest(risky.getId(), "self-harm", null), DAY).tip())
                .contains(" 4 小时");

        PrivateMessage plain = store.seedMessage(PEER, ME, "今天天气不错", "text", "read", "L0");
        PmReportView view = service.report(ME, new PmReportRequest(plain.getId(), "spam", null), DAY);
        assertThat(view.tip()).contains(" 24 小时");
        assertThat(store.auditTasks.get(store.auditTasks.size() - 1).getSlaAt())
                .as("内容类举报用举报默认时限，不是危机时限").isEqualTo(DAY.plusHours(24));
    }

    @Test
    @DisplayName("补充说明必须在 Service 里按备注预算剪断：audit_task.remark 是 VARCHAR(500)，超长交给 MySQL 就是 1406")
    void reportRemarkFitsTheColumn() {
        PrivateMessage row = store.seedMessage(PEER, ME, "随便什么", "text", "read", "L0");
        service.report(ME, new PmReportRequest(row.getId(), "other", "说".repeat(600)), DAY);
        AuditTask task = store.auditTasks.get(0);
        assertThat(task.getRemark()).hasSize(properties.getPm().getReportRemarkMax());
        assertThat(task.getRemark()).startsWith("私信举报").contains("其他(other)").contains("补充=说说说");
    }

    // ================================================================== F 纯逻辑工具与双通道开关

    @Test
    @DisplayName("cut 按码点剪且不切半代理对：半个代理项写进 utf8mb4 会变成问号，管理员看到的是乱码证据")
    void cutNeverSplitsASurrogatePair() {
        assertThat(PmService.cut("😀😀😀", 2)).isEqualTo("😀😀");
        assertThat(PmService.cut("a😀b", 2)).isEqualTo("a😀");
        assertThat(PmService.cut("abc", 10)).isEqualTo("abc");
        assertThat(PmService.cut("abc", 3)).isEqualTo("abc");
        assertThat(PmService.cut("abc", 0)).isEmpty();
        assertThat(PmService.cut("abc", -1)).isEmpty();
        assertThat(PmService.cut(null, 5)).isEmpty();
    }

    @Test
    @DisplayName("otherSide 两个方向都答得对，缺收件人的脏行按 0 处理：它只回答「另一端是谁」，不回答「我是不是当事人」")
    void otherSideHandlesBothDirectionsAndMissingIds() {
        PrivateMessage row = new PrivateMessage();
        row.setFromUserId(ME);
        row.setToUserId(PEER);
        assertThat(PmService.otherSide(row, ME)).isEqualTo(PEER);
        assertThat(PmService.otherSide(row, PEER)).isEqualTo(ME);
        assertThat(PmService.otherSide(row, STRANGER)).as("对第三人它照样返回发信方").isEqualTo(ME);

        PrivateMessage broken = new PrivateMessage();
        assertThat(PmService.otherSide(broken, ME)).as("两端都是 null 也不能 NPE").isZero();
    }

    @Test
    @DisplayName("rankOf 只认 L1/L2/L3：词库里写出 L9 也不能把待审任务抬到危机顶上")
    void rankOfIgnoresLevelsItDoesNotKnow() {
        assertThat(PmService.rankOf(null)).isZero();
        assertThat(PmService.rankOf("L0")).isZero();
        assertThat(PmService.rankOf("L1")).isEqualTo(1);
        assertThat(PmService.rankOf("L2")).isEqualTo(2);
        assertThat(PmService.rankOf("L3")).isEqualTo(3);
        assertThat(PmService.rankOf("L9")).isZero();
        assertThat(PmService.rankOf("HIGH")).isZero();
    }

    @Test
    @DisplayName("verdictOf 看「有没有」不看「排第几」：BLOCK > REVIEW > RISK > TAG > HIT > PASS 的优先序一次钉全")
    void verdictUsesPresenceNotPosition() {
        assertThat(PmService.verdictOf(null)).isEqualTo("PASS");
        assertThat(PmService.verdictOf(engine.check("今天天气不错", "user"))).isEqualTo("PASS");
        assertThat(PmService.verdictOf(engine.check("伤害自己", "user"))).isEqualTo("RISK");
        assertThat(PmService.verdictOf(engine.check("傻逼 伤害自己", "user")))
                .as("risk 级命中被 grey 压在后面，结论仍是 REVIEW，但危机判定照旧成立").isEqualTo("REVIEW");
        assertThat(PmService.verdictOf(engine.check("枪支弹药 傻逼 伤害自己", "user"))).isEqualTo("BLOCK");

        engine.reload(VERDICT_FIXTURE);
        assertThat(PmService.verdictOf(engine.check("加微信", "user"))).isEqualTo("TAG");
        assertThat(PmService.verdictOf(engine.check("转账", "user"))).isEqualTo("HIT");
        assertThat(engine.check("转账", "user").riskTouched()).as("HIT 档不该被误读成危机").isFalse();
    }

    @Test
    @DisplayName("模型通道默认关闭：riskScorer 传 null 也照样发得出私信，一分钱 token 都不花")
    void modelChannelIsOffByDefault() {
        service = newService(null);
        assertThat(sendToPeer("在吗").content()).isEqualTo("在吗");
        assertThat(riskScorer.calls).isZero();
        assertThat(store.tickets).isEmpty();
    }

    @Test
    @DisplayName("打开模型通道后它只能往上抬：词面 L0 由模型判到 L3 也建单；词面已 L3 时模型说 L2 不改变结论")
    void modelChannelCanOnlyRaiseTheLevel() {
        properties.getPm().setRiskModelEnabled(true);
        service = newService(riskScorer);

        sendToPeer("今天感觉还不错");
        assertThat(riskScorer.calls).as("开着的时候每条私信都过一次").isEqualTo(1);
        assertThat(lastRiskLevel()).as("词面 L0、模型 L3，取高").isEqualTo(CrisisGrader.L3);
        assertThat(store.tickets).hasSize(1);
        assertThat(store.tickets.get(0).getUserId()).as("工单挂的是发信方").isEqualTo(ME);
        assertThat(store.tickets.get(0).getSourceType()).isEqualTo("pm");
        assertThat(((PmSendEvent) events.published.get(0)).crisisLevel()).isEqualTo(CrisisGrader.L3);

        riskScorer.level = CrisisGrader.L2;
        sendToPeer("我想结束生命");
        assertThat(riskScorer.calls).isEqualTo(2);
        assertThat(lastRiskLevel()).as("模型降级不会被当成「这人安全了」").isEqualTo(CrisisGrader.L3);
        assertThat(store.tickets).as("两条都够建单").hasSize(2);
    }
    // ================================================================== 替身：每个只覆写需要的那一个方法

    /**
     * 只为 {@code verdictOf} 的 TAG / HIT 两档准备：主词库里的 TAG 全是 risk 级，测不出
     * 「命中 TAG 但没碰危机」，而这两档正是写进 audit_task.result 时最容易混淆的地方。
     */
    private static final String VERDICT_FIXTURE = String.join("\n",
            "#version=test-pm-verdict",
            row("辱骂攻击", "grey", "REVIEW", "user", "contains", "傻逼"),
            row("自伤自杀", "risk", "TAG", "both", "contains", "伤害自己"),
            row("广告引流", "white", "TAG", "user", "contains", "加微信"),
            row("其他线索", "white", "HIT", "user", "contains", "转账"));

    /** 在线状态：真注册表要 STOMP 会话和心跳，单测里只需要「我说谁在线，谁就在线」。 */
    private static final class FakePresence extends PresenceRegistry {

        final Set<Long> onlineIds = new LinkedHashSet<>();

        FakePresence(MindisleProperties properties) {
            super(null, null, properties);
        }

        @Override
        public boolean isOnline(long userId) {
            return onlineIds.contains(userId);
        }
    }


    /** 模型通道替身：真实现要发 HTTP，这里只记录被调了几次、回一个测试指定的等级。 */
    private static final class FakeRiskScorer extends RiskScorer {

        int calls;

        String level = CrisisGrader.L3;

        FakeRiskScorer() {
            super(null, null, null, null, null);
        }

        @Override
        public Risk score(String text, Long userId, String traceId) {
            calls++;
            return new Risk(level, level, level, null, "", "",
                    CrisisGrader.needsTicket(level), "");
        }
    }


    /** 事件收集器：只测「发没发、发的是哪一个」，AFTER_COMMIT 的时序交给 Gate5 真跑。 */
    private static final class RecordingPublisher implements ApplicationEventPublisher {

        final List<Object> published = new ArrayList<>();

        @Override
        public void publishEvent(org.springframework.context.ApplicationEvent event) {
            published.add(event);
        }

        @Override
        public void publishEvent(Object event) {
            published.add(event);
        }
    }


    /**
     * 记下调用参数的 PmStore 替身：分页到底取了几条、已读报到哪个 id、重投的宽限线画在几点
     * ——这三样都是判据（多取一条就少一次翻页、宽限期内的消息不该被重投），不是实现细节。
     */
    private static final class RecordingStore extends PmFakeStore {

        int lastPageLimit;

        int lastTailsLimit;

        int lastUndeliveredLimit;

        long lastMarkReadUpToId;

        LocalDateTime lastUndeliveredBefore;

        @Override
        public List<PrivateMessage> pageThread(long userId, long peerId, Long beforeId, int limit) {
            lastPageLimit = limit;
            return super.pageThread(userId, peerId, beforeId, limit);
        }

        @Override
        public List<PrivateMessage> listConversationTails(long userId, Long beforeId, int limit) {
            lastTailsLimit = limit;
            return super.listConversationTails(userId, beforeId, limit);
        }

        @Override
        public List<PrivateMessage> listUndelivered(LocalDateTime beforeCreated, int limit) {
            lastUndeliveredBefore = beforeCreated;
            lastUndeliveredLimit = limit;
            return super.listUndelivered(beforeCreated, limit);
        }

        @Override
        public int markThreadRead(long userId, long peerId, long upToId, LocalDateTime now) {
            lastMarkReadUpToId = upToId;
            return super.markThreadRead(userId, peerId, upToId, now);
        }
    }
}
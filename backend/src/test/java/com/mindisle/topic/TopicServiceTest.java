package com.mindisle.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.DuplicateKeyException;

import com.mindisle.audit.SensitiveWordEngine;
import com.mindisle.audit.SensitiveWordEngine.CheckResult;
import com.mindisle.auth.AuthService;
import com.mindisle.cache.CaffeineCacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.Topic;
import com.mindisle.entity.User;
import com.mindisle.mapper.TopicFollowMapper;
import com.mindisle.mapper.TopicMapper;
import com.mindisle.post.PostingQuotaService;
import com.mindisle.topic.dto.TopicCard;
import com.mindisle.topic.dto.TopicCreateRequest;
import com.mindisle.topic.dto.TopicCreateView;
import com.mindisle.topic.dto.TopicFollowRequest;
import com.mindisle.topic.dto.TopicFollowView;

/**
 * 话题创建与关注的规则单测（任务 3.8 · 需求 FR1.7、FR4.5、FR8.6、BR6）。
 *
 * <p><b>为什么能不起 Spring、不连库就把规则钉死</b>：{@link TopicService} 的每一个判断都只读
 * 三样东西 —— 账号真相（{@code user.status} 与 {@code created_at}）、{@code topic} 行本身、
 * 词库结论。前两者用一个 {@link Store} 内存在替身里给全，第三者用<b>真的</b>
 * {@link SensitiveWordEngine}（词库格式与线上同一份，只换成四行测试词条），
 * 配额用<b>真的</b> {@link PostingQuotaService} + 真的 Caffeine。
 * 于是「BLOCK 不落库所以不烧配额」「预审开关只改状态、不改拦截」这类跨类规则是真跑通了一遍，
 * 而不是打桩打出来的自证。</p>
 *
 * <p><b>替身里刻意复刻的三件事</b>：① {@code selectById} 遇到 {@code deleted = 1} 返回 null，
 * 替的是 MyBatis-Plus 的 {@code @TableLogic}；② {@code findByName} 用 {@code equalsIgnoreCase}
 * 且<b>看不见</b>逻辑删除，替的是 {@code utf8mb4_0900_ai_ci} + 那条绕过 {@code @TableLogic} 的裸 SQL；
 * ③ {@code refreshFollowCnt} 从真相集合<b>重算</b>而不是自增。少了 ① 就测不出
 * 「软删话题仍占住名字」，少了 ② 就测不出「重名判据有两层」，少了 ③ 计数不变式就只是句口号。</p>
 *
 * <p><b>本类刻意不测的东西</b>：{@code TopicFollowMapper} 那五条注解 SQL 的形状
 * （{@code INSERT IGNORE} 在真 MySQL 上回填什么、{@code refreshFollowCnt} 的相关子查询过不过得了
 * {@code ONLY_FULL_GROUP_BY}）与 {@code TopicController} 的参数绑定 ——
 * 那是「接线错」而不是「规则错」，由 {@code docs/smoke.mjs} 第 21 步打真 HTTP + 真库取证负责。
 * 在这里假装测过 SQL，就等于把两类缺陷混进同一份绿（沿用 {@code ReportServiceTest} 那段口径）。</p>
 */
class TopicServiceTest {

    /** 固定时钟：配额的自然日全靠它算，服务内部不读系统时钟。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 23, 12, 0);

    private static final long CREATOR = 801L;
    private static final long VIEWER = 802L;
    private static final long TOPIC_ID = 900L;
    private static final long GHOST_TOPIC = 999L;

    /** 与 SensitiveWordEngineTest / ReportServiceTest 同一套词库格式，只留本类要用的四组。 */
    private static final String FIXTURE = String.join("\n",
            "#version=test-topic-1",
            row("政治违法", "black", "BLOCK", "both", "contains", "枪支弹药"),
            row("广告导流", "black", "BLOCK", "both", "contains", "加微信领"),
            row("辱骂攻击", "grey", "REVIEW", "user", "contains", "傻逼"),
            row("自伤自杀", "risk", "TAG", "both", "contains", "伤害自己"));

    private Store store;
    private MindisleProperties properties;
    /** 一个测试方法内共享同一份配额计数：重建服务不该把当天的用量清零。 */
    private CaffeineCacheService cache;

    private static String row(String group, String level, String action, String scope,
                              String matchType, String word) {
        return String.join("\t", group, level, action, scope, matchType, word);
    }

    @BeforeEach
    void setUp() {
        store = new Store();
        store.users.put(CREATOR, user(CREATOR, "创建者", "ACTIVE"));
        store.users.put(VIEWER, user(VIEWER, "路人", "ACTIVE"));
        properties = new MindisleProperties();
        cache = new CaffeineCacheService();
    }

    // ------------------------------------------------------------------ 装配与造数据

    /** 按当前 {@link #properties} 装配一个服务：改完配置重建即可，不必碰每个用例。 */
    private TopicService service() {
        SensitiveWordEngine engine = new SensitiveWordEngine(properties, new DefaultResourceLoader());
        engine.reload(FIXTURE);
        return new TopicService(store.topicMapper(), store.followMapper(), new StubAuth(store),
                new PostingQuotaService(cache, properties), engine, properties);
    }

    /** 改 FR8.6 那个「话题预审开关」。 */
    private void preReview(boolean enabled) {
        properties.getTopic().setRequirePreReview(enabled);
    }

    private static User user(long id, String nickname, String status) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setNickname(nickname);
        user.setStatus(status);
        user.setCreatedAt(NOW.minusDays(30));
        return user;
    }

    /**
     * 造一个话题行。计数列全部按 DDL 的 {@code NOT NULL DEFAULT 0} 初始化 ——
     * 替身太宽松会让断言假绿（T3.6 踩过：{@code follow_cnt} 留 null 时
     * 「计数等于真相表行数」这条断言永远为真）。
     */
    private static Topic topic(long id, String name, String auditStatus) {
        Topic topic = new Topic();
        topic.setId(id);
        topic.setName(name);
        topic.setDescTxt("这是一个用来测试的话题");
        topic.setAuditStatus(auditStatus);
        topic.setPostCnt(3);
        topic.setFollowCnt(0);
        topic.setHotScore(new BigDecimal("1.5000"));
        topic.setIsOfficial(1);
        topic.setDeleted(0);
        return topic;
    }

    /** 往内存表里放一个已过审话题并返回它。 */
    private Topic approved() {
        Topic topic = topic(TOPIC_ID, "失眠夜", "APPROVED");
        store.topics.put(TOPIC_ID, topic);
        return topic;
    }

    private static TopicCreateRequest req(String name, String desc) {
        return new TopicCreateRequest(name, desc);
    }

    /** 断言这段调用抛 {@link BizException}，并把异常交回调用方继续读错误码与提示文案。 */
    private static BizException expectBiz(Executable call) {
        return assertThrows(BizException.class, call);
    }

    private static List<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).collect(Collectors.toList());
    }

    // -------------------------------------------------------------- 判据函数（无状态，可直调）

    @Test
    @DisplayName("requireReadable 真值表：已过审放行、待审 409/30004、驳回与不存在 404/90006")
    void requireReadableTruthTable() {
        Topic approvedTopic = topic(TOPIC_ID, "失眠夜", "APPROVED");

        assertThat(TopicService.requireReadable(approvedTopic)).isSameAs(approvedTopic);

        BizException pending = expectBiz(() ->
                TopicService.requireReadable(topic(TOPIC_ID, "失眠夜", "PENDING")));
        assertThat(pending.getErrorCode()).isEqualTo(ErrorCode.TOPIC_PENDING);
        assertThat(pending.getErrorCode().getHttpStatus()).isEqualTo(409);
        assertThat(pending.getErrorCode().getCode()).isEqualTo(30004);
        // 手册 L803：待审要「说清楚用户能做什么」，所以提示里必须带话题名和「审核」这件事
        assertThat(pending.getMessage()).contains("失眠夜").contains("审核");

        assertThat(expectBiz(() -> TopicService.requireReadable(null)).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(expectBiz(() ->
                TopicService.requireReadable(topic(TOPIC_ID, "失眠夜", "REJECTED"))).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(ErrorCode.RESOURCE_NOT_FOUND.getHttpStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("看不懂的审核状态按「还没过审」处理：失败关闭，绝不因为 ENUM 加了新值就默认放行")
    void unknownAuditStatusFailsClosed() {
        assertThat(expectBiz(() -> TopicService.requireReadable(topic(TOPIC_ID, "失眠夜", "APPROVIED")))
                .getErrorCode()).isEqualTo(ErrorCode.TOPIC_PENDING);
        assertThat(expectBiz(() -> TopicService.requireReadable(topic(TOPIC_ID, "失眠夜", "approved")))
                .getErrorCode()).isEqualTo(ErrorCode.TOPIC_PENDING);
        // 名字带尾空格的 APPROVED 也不算过审：判据用 equals，不做 trim（trim 属于归一化，归一化在建的时候做）
        assertThat(expectBiz(() -> TopicService.requireReadable(topic(TOPIC_ID, "失眠夜", "APPROVED ")))
                .getErrorCode()).isEqualTo(ErrorCode.TOPIC_PENDING);
    }

    @Test
    @DisplayName("空白折叠带全角空格：Java 的 \\s 不含 U+3000，而中文输入法打出来的恰恰是它")
    void collapseWhitespaceHandlesFullWidthSpace() {
        assertThat(TopicService.collapseWhitespace("考研\u3000\u3000加油站")).isEqualTo("考研 加油站");
        assertThat(TopicService.collapseWhitespace("  期末\n\t破防  瞬间 ")).isEqualTo("期末 破防 瞬间");
        assertThat(TopicService.collapseWhitespace(null)).isEmpty();
        assertThat(TopicService.collapseWhitespace("   ")).isEmpty();
    }

    @Test
    @DisplayName("话题名长度按码点算：两个 emoji 加二十九个汉字 = 33 个 char / 31 个码点，不该被判超长")
    void nameLengthCountsCodePointsNotChars() {
        TopicService service = service();

        // 33 个码点：越界，且提示里的两个数都要是「码点口径」（32 是上限、33 是当前值）
        BizException tooLong = expectBiz(() -> service.normalizeName("一".repeat(33)));
        assertThat(tooLong.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(tooLong.getMessage()).contains("32").contains("33");

        String emojiHeavy = emoji() + emoji() + "一".repeat(29);
        assertThat(emojiHeavy.length()).isEqualTo(33);          // char 数已经超 32
        assertThat(emojiHeavy.codePointCount(0, emojiHeavy.length())).isEqualTo(31);
        assertThat(service.normalizeName(emojiHeavy)).isEqualTo(emojiHeavy);

        String tooManyEmoji = emoji().repeat(33);
        assertThat(tooManyEmoji.length()).isEqualTo(66);        // 若按 char 判会报 66
        assertThat(expectBiz(() -> service.normalizeName(tooManyEmoji)).getMessage())
                .contains("33").doesNotContain("66");
    }

    @Test
    @DisplayName("话题名上限读配置而不是写死：改 mindisle.topic.max-name-chars 立刻生效")
    void nameLimitComesFromConfig() {
        properties.getTopic().setMaxNameChars(4);

        assertThat(service().normalizeName("失眠夜")).isEqualTo("失眠夜");
        assertThat(service().normalizeName("失眠的夜")).isEqualTo("失眠的夜");   // 正好 4 字，不越界
        BizException ex = expectBiz(() -> service().normalizeName("失眠的夜里"));   // 5 字越界
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(ex.getMessage()).contains("4").contains("5");
    }

    @Test
    @DisplayName("空话题名给的是「话题名不能为空」，而不是长度校验那句：提示要指向能改的地方")
    void blankNameHasItsOwnMessage() {
        BizException ex = expectBiz(() -> service().normalizeName("　 \n "));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(ex.getMessage()).isEqualTo("话题名不能为空");
    }

    @Test
    @DisplayName("简介可空、同样折叠空白、超长另有提示：它落的是 NOT NULL DEFAULT ''，所以空值是空串不是 null")
    void descIsNormalisedAndLimited() {
        TopicService service = service();

        assertThat(service.normalizeDesc(null)).isEmpty();
        assertThat(service.normalizeDesc("  考研\u3000\u3000失败也能睡个好觉\n ")).isEqualTo("考研 失败也能睡个好觉");
        assertThat(service.normalizeDesc("一".repeat(200))).hasSize(200);
        assertThat(expectBiz(() -> service.normalizeDesc("一".repeat(201))).getMessage())
                .contains("200").contains("201");
    }

    @Test
    @DisplayName("关注动作白名单：大小写与首尾空白都收，白名单外一律 10001（与关注某人同一套词）")
    void actionWhitelist() {
        assertThat(TopicService.normalizeAction(" Follow ")).isEqualTo("follow");
        assertThat(TopicService.normalizeAction("UNFOLLOW")).isEqualTo("unfollow");

        BizException ex = expectBiz(() -> TopicService.normalizeAction("like"));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(ex.getMessage()).isEqualTo("action 只能是 follow 或 unfollow");
        assertThat(expectBiz(() -> TopicService.normalizeAction(null)).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("FR8.6 开关 x 机审结论 -> 审核状态：开着时结果与机审无关，关掉才走机审直通")
    void resolveAuditStatusTable() {
        CheckResult clean = scan("今晚睡不着");
        CheckResult review = scan("傻逼才睡得着");
        CheckResult blocked = scan("枪支弹药");

        // 开关开（默认）：FR4.5 字面「创建需管理员审核」，三种机审结论都落 PENDING
        assertThat(TopicService.resolveAuditStatus(true, clean)).isEqualTo("PENDING");
        assertThat(TopicService.resolveAuditStatus(true, review)).isEqualTo("PENDING");
        assertThat(TopicService.resolveAuditStatus(true, blocked)).isEqualTo("PENDING");
        assertThat(TopicService.resolveAuditStatus(true, null)).isEqualTo("PENDING");

        // 开关关：只有 REVIEW 转待审，干净直接过审
        assertThat(TopicService.resolveAuditStatus(false, clean)).isEqualTo("APPROVED");
        assertThat(TopicService.resolveAuditStatus(false, review)).isEqualTo("PENDING");
        assertThat(TopicService.resolveAuditStatus(false, null)).isEqualTo("APPROVED");
        // BLOCK 在这一层「看不见」：它由 create 在调用本方法之前就拒掉。
        // 这条断言钉的是职责边界 —— 状态判据不负责拦违规，违规判据只有一处。
        assertThat(TopicService.resolveAuditStatus(false, blocked)).isEqualTo("APPROVED");
    }

    /** 用同一份测试词库真跑一次机审，供 {@link #resolveAuditStatusTable()} 取三种结论。 */
    private CheckResult scan(String text) {
        SensitiveWordEngine engine = new SensitiveWordEngine(properties, new DefaultResourceLoader());
        engine.reload(FIXTURE);
        return engine.check(text, "user");
    }

    /** 一个占 2 个 char、只算 1 个码点的 emoji（U+1F389）。不写字面量是为了避开源文件编码这一层变量。 */
    private static String emoji() {
        return new String(Character.toChars(0x1F389));
    }

    // ------------------------------------------------------------------ create（FR4.5 + FR8.6）

    @Test
    @DisplayName("预审开着（默认）：建话题一律 PENDING、回执 usable=false，且不自动关注创建者")
    void createDefaultsToPendingAndDoesNotAutoFollow() {
        TopicCreateView view = service().create(CREATOR, req("  考研\u3000\u3000加油站  ", "一战失败也能睡个好觉"), NOW);

        assertThat(view.id()).isNotNull().isPositive();
        assertThat(view.name()).isEqualTo("考研 加油站");
        assertThat(view.desc()).isEqualTo("一战失败也能睡个好觉");
        assertThat(view.auditStatus()).isEqualTo("PENDING");
        assertThat(view.usable()).isFalse();
        assertThat(view.tip()).contains("审核").doesNotContain("现在就能");
        assertThat(view.postCnt()).isZero();
        assertThat(view.followCnt()).isZero();

        Topic saved = store.topics.get(view.id());
        assertThat(saved).isNotNull();
        assertThat(saved.getAuditStatus()).isEqualTo("PENDING");
        assertThat(saved.getName()).isEqualTo("考研 加油站");
        assertThat(saved.getDescTxt()).isEqualTo("一战失败也能睡个好觉");
        assertThat(saved.getIsOfficial()).isZero();   // 官方角标是运营盖的，用户建不出来
        assertThat(saved.getPostCnt()).isZero();
        assertThat(saved.getFollowCnt()).isZero();
        assertThat(saved.getHotScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saved.getDeleted()).isZero();
        assertThat(saved.getCover()).isNull();        // U6 头图今天没有上传通道

        // 🔴 不自动关注：follow_cnt 要恒等于 topic_follow 的行数，创建者那一票不是「参与」
        assertThat(store.follows).isEmpty();
        assertThat(store.insertIgnoreCalls).isZero();
        // 也不碰 post_cnt：那是 refreshPostCnt 的活（定时任务 / 发帖时刷），建话题没有帖可挂
        assertThat(store.refreshPostCntCalls).isZero();
    }

    @Test
    @DisplayName("预审关掉 + 内容干净：直接 APPROVED，回执 usable=true 并明说「现在就能进去发帖」")
    void createIsInstantlyUsableWhenPreReviewOff() {
        preReview(false);
        TopicService service = service();

        TopicCreateView view = service.create(CREATOR, req("失眠夜", "凌晨三点的人在这儿"), NOW);

        assertThat(view.auditStatus()).isEqualTo("APPROVED");
        assertThat(view.usable()).isTrue();
        assertThat(view.tip()).contains("现在就能");
        // 关掉预审之后，详情页与挂帖这条链路必须立刻通：这是「阶段 3 唯一能自动放行」的那一档
        assertThat(service.detail(VIEWER, view.id()).name()).isEqualTo("失眠夜");
        assertThat(store.topics.get(view.id()).getAuditStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("risk 档（TAG）不拦：话题名里出现「伤害自己」照样建得出来")
    void riskLevelWordDoesNotBlockCreation() {
        preReview(false);

        TopicCreateView view = service().create(CREATOR, req("伤害自己之后", "聊聊冲动之后的那晚"), NOW);

        // 危机识别的对象是「某个具体的人在某一时刻的求助表达」，一个圈子名字不是（也不建 alert_ticket）
        assertThat(view.auditStatus()).isEqualTo("APPROVED");
        assertThat(view.usable()).isTrue();
        assertThat(store.inserted).hasSize(1);
    }

    @Test
    @DisplayName("grey 档（REVIEW）：预审开着看不出差别，关掉才转待审 —— 开关的语义差在这里")
    void reviewLevelWordOnlyDiffersWhenPreReviewOff() {
        // 开关开着：REVIEW 档命中与「完全干净」是同一个结局，这就是 FR4.5 的字面
        assertThat(service().create(CREATOR, req("傻逼才睡得着", "换个说法"), NOW).auditStatus())
                .isEqualTo("PENDING");
        assertThat(service().create(CREATOR, req("失眠夜一号", "今晚也要熬"), NOW).auditStatus())
                .isEqualTo("PENDING");

        preReview(false);
        // 开关关掉才看得见差别：命中 REVIEW 转待审，干净直接过审
        assertThat(service().create(CREATOR, req("傻逼的夜里", "换个说法"), NOW).auditStatus())
                .isEqualTo("PENDING");
        assertThat(service().create(VIEWER, req("失眠夜二号", "今晚也要熬"), NOW).auditStatus())
                .isEqualTo("APPROVED");
        // 脏词只在简介里也要算：名与简介是合成一份文本送检的，不是只查名字
        assertThat(service().create(VIEWER, req("失眠夜三号", "傻逼才睡得着"), NOW).auditStatus())
                .isEqualTo("PENDING");
        assertThat(store.inserted).hasSize(5);
    }

    @Test
    @DisplayName("BLOCK 档直接拒且不落库：既不占 uk_name、也不烧当天的配额")
    void blockWordRejectsBeforePersistAndSpendsNoQuota() {
        TopicService service = service();

        BizException ex = expectBiz(() -> service.create(CREATOR, req("枪支弹药", "求购"), NOW));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONTENT_REJECTED);
        assertThat(ex.getErrorCode().getCode()).isEqualTo(50001);
        // 回执绝不回显词面：那是把词库内容念给用户，等于告诉绕过者规则长什么样
        assertThat(ex.getMessage()).doesNotContain("枪支弹药").doesNotContain("傻逼");
        assertThat(store.inserted).isEmpty();
        assertThat(store.insertCalls).isZero();

        // 配额没被烧掉：后面五个干净话题照样建得出来（上限 5）
        for (int i = 0; i < properties.getTopic().getMaxCreatePerDay(); i++) {
            assertThat(service.create(CREATOR, req("失眠夜" + i, "聊聊"), NOW).usable()).isFalse();
        }
        assertThat(store.insertCalls).isEqualTo(5);
    }

    @Test
    @DisplayName("BLOCK 只看机审结论，与预审开关无关：关掉开关也拦得住（违规内容不给落库不是审核结论）")
    void blockWordRejectsEvenWithPreReviewOff() {
        preReview(false);
        TopicService service = service();

        assertThat(expectBiz(() -> service.create(CREATOR, req("正常名字", "加微信领小礼品"), NOW))
                .getErrorCode()).isEqualTo(ErrorCode.CONTENT_REJECTED);
        assertThat(store.inserted).isEmpty();
    }

    @Test
    @DisplayName("重名的两条路给同一句话：先查（findByName 看得见软删行）和撞唯一键（并发）")
    void duplicateNameHasTheSameMessageOnBothPaths() {
        approved();
        TopicService service = service();

        BizException preCheck = expectBiz(() -> service.create(CREATOR, req("失眠夜", "再建一个"), NOW));
        assertThat(preCheck.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(preCheck.getMessage()).isEqualTo("这个话题已经有人建过了，直接去参与它吧");
        assertThat(store.insertCalls).isZero();

        // 大小写不同也算重名：判据在库的 collation（utf8mb4_0900_ai_ci），Java 不做 toLowerCase
        assertThat(expectBiz(() -> service.create(CREATOR, req("失眠夜 ", "带个尾空格"), NOW))
                .getMessage()).isEqualTo(preCheck.getMessage());

        store.duplicateOnInsert = true;
        BizException race = expectBiz(() -> service.create(CREATOR, req("失眠的夜", "并发撞键"), NOW));
        assertThat(race.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(race.getMessage()).isEqualTo(preCheck.getMessage());
        assertThat(store.insertCalls).isEqualTo(1);   // 撞键那次确实发了 INSERT，只是没落成功
    }

    @Test
    @DisplayName("软删话题仍然占住名字：selectById 查不到它（详情页 404），但 findByName 查得到")
    void softDeletedTopicStillHoldsItsName() {
        Topic gone = topic(777L, "曾经的话题", "APPROVED");
        gone.setDeleted(1);
        store.topics.put(777L, gone);
        TopicService service = service();

        assertThat(expectBiz(() -> service.create(CREATOR, req("曾经的话题", "重建一次"), NOW))
                .getMessage()).isEqualTo("这个话题已经有人建过了，直接去参与它吧");
        assertThat(store.topics.get(777L)).isNotNull();
        assertThat(store.insertCalls).isZero();
        assertThat(expectBiz(() -> service.detail(VIEWER, 777L)).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("每日建话题上限读 mindisle.topic.max-create-per-day：撞顶回 429/10010，调高上限立刻能建")
    void dailyQuotaCapsCreation() {
        TopicService service = service();
        int limit = properties.getTopic().getMaxCreatePerDay();
        assertThat(limit).isEqualTo(5);

        for (int i = 0; i < limit; i++) {
            service.create(CREATOR, req("话题" + i, "同一天里连着建"), NOW);
        }
        BizException ex = expectBiz(() -> service.create(CREATOR, req("话题过载", "第六个"), NOW));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
        assertThat(ex.getErrorCode().getHttpStatus()).isEqualTo(429);
        assertThat(ex.getMessage()).contains(String.valueOf(limit));
        assertThat(store.insertCalls).isEqualTo(limit);

        // 上限是配置：调到 8 之后同一个账号当天还能再建三个（已用的 5 个仍然记着）
        properties.getTopic().setMaxCreatePerDay(8);
        for (int i = 0; i < 3; i++) {
            service.create(CREATOR, req("补建话题" + i, "调高上限之后"), NOW);
        }
        assertThat(store.insertCalls).isEqualTo(8);
        assertThat(expectBiz(() -> service.create(CREATOR, req("第九个话题", "还是超限"), NOW))
                .getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
        // 另一个账号不受影响：配额按人算，不是全站共用一个数
        assertThat(service.create(VIEWER, req("路人的话题", "换个账号"), NOW).name()).isEqualTo("路人的话题");
    }

    @Test
    @DisplayName("BR6 分工：禁言账号可以看和点（能关注话题），不能说（不能建话题）")
    void mutedAccountCanFollowButNotCreate() {
        store.users.put(CREATOR, user(CREATOR, "被禁言的人", "MUTED"));
        approved();
        TopicService service = service();

        BizException ex = expectBiz(() -> service.create(CREATOR, req("失眠夜别的路", "试试"), NOW));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(ex.getErrorCode().getCode()).isEqualTo(10003);
        assertThat(store.insertCalls).isZero();

        assertThat(service.follow(CREATOR, TOPIC_ID, "follow").following()).isTrue();
    }

    @Test
    @DisplayName("封禁账号两样都不行：建话题与关注话题都回 20003")
    void bannedAccountCanDoNeither() {
        store.users.put(CREATOR, user(CREATOR, "被封的人", "BANNED"));
        approved();
        TopicService service = service();

        assertThat(expectBiz(() -> service.create(CREATOR, req("失眠夜别的路", "试试"), NOW))
                .getErrorCode()).isEqualTo(ErrorCode.USER_DISABLED);
        assertThat(expectBiz(() -> service.follow(CREATOR, TOPIC_ID, "follow"))
                .getErrorCode()).isEqualTo(ErrorCode.USER_DISABLED);
        assertThat(store.follows).isEmpty();
    }

    @Test
    @DisplayName("查无此人回 20001 而不是 NPE：状态判据第一行就要挡住空账号")
    void unknownAccountIsNotFound() {
        approved();
        TopicService service = service();

        assertThat(expectBiz(() -> service.create(404404L, req("失眠夜别的路", "试试"), NOW))
                .getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThat(expectBiz(() -> service.follow(404404L, TOPIC_ID, "follow"))
                .getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("请求体整个为 null 也不是 500：走的是「话题名不能为空」这条普通提示")
    void nullRequestIsParamInvalid() {
        TopicService service = service();

        // 请求体整个缺失：这是「参数不合法 + 一句能照着改的提示」，不是空指针
        assertThat(expectBiz(() -> service.create(CREATOR, null, NOW)).getMessage())
                .isEqualTo("话题名不能为空");
        assertThat(store.insertCalls).isZero();

        // 简介可以不给：DDL 是 NOT NULL DEFAULT ''，所以落库的是空串而不是 null，
        // 前端 TopicCard.desc 因此永远拿得到字符串，详情页不用写可选链
        TopicCreateView view = service.create(CREATOR, req("失眠夜没人陪", null), NOW);
        assertThat(view.desc()).isEmpty();
        assertThat(store.topics.get(view.id()).getDescTxt()).isEmpty();
        // 名字只给空白也算没给：折叠后是空串
        assertThat(expectBiz(() -> service.create(CREATOR, req("　　", "只有全角空格的名字"), NOW))
                .getMessage()).isEqualTo("话题名不能为空");
    }

    // ------------------------------------------------------------------ follow / detail

    @Test
    @DisplayName("关注写真相表并重算计数：follow_cnt 恒等于 topic_follow 的行数，回执带的就是这个数")
    void followUpdatesCounterFromTruthTable() {
        approved();
        TopicService service = service();

        TopicFollowView first = service.follow(CREATOR, TOPIC_ID, "follow");
        assertThat(first.topicId()).isEqualTo(TOPIC_ID);
        assertThat(first.action()).isEqualTo("follow");
        assertThat(first.changed()).isTrue();
        assertThat(first.following()).isTrue();
        assertThat(first.followCnt()).isEqualTo(1);
        assertThat(store.follows).containsExactly(CREATOR + "#" + TOPIC_ID);
        assertThat(store.insertIgnoreCalls).isEqualTo(1);
        assertThat(store.refreshFollowCalls).isEqualTo(1);
        // 计数列与真相表同值：这是手册 §18 Gate3 那条「计数 = 关联表 count」的实现处口径
        assertThat(store.topics.get(TOPIC_ID).getFollowCnt()).isEqualTo(store.follows.size());

        TopicFollowView second = service.follow(VIEWER, TOPIC_ID, "follow");
        assertThat(second.followCnt()).isEqualTo(2);
        assertThat(second.following()).isTrue();
        assertThat(store.topics.get(TOPIC_ID).getFollowCnt()).isEqualTo(store.follows.size());
    }

    @Test
    @DisplayName("重复关注：第二次什么都不写、changed=false，但仍然 200 回当前状态（前端乐观更新要靠它收敛）")
    void repeatFollowIsANoOpThatStillReportsState() {
        approved();
        TopicService service = service();

        service.follow(CREATOR, TOPIC_ID, "follow");
        TopicFollowView again = service.follow(CREATOR, TOPIC_ID, "follow");

        assertThat(again.changed()).isFalse();
        assertThat(again.following()).isTrue();
        assertThat(again.followCnt()).isEqualTo(1);
        // 已关注就不再发第二条 INSERT：IGNORE 本来也不写，但省一次往返，
        // 更要紧的是 changed 的语义是「这次真的改了状态」而不是「SQL 执行成功」
        assertThat(store.insertIgnoreCalls).isEqualTo(1);
        assertThat(store.refreshFollowCalls).isEqualTo(1);
        assertThat(store.follows).hasSize(1);
    }

    @Test
    @DisplayName("取关幂等：没关注过时 deletePair 删 0 行，changed=false，计数一次都不刷")
    void unfollowIsIdempotent() {
        approved();
        TopicService service = service();

        TopicFollowView nothing = service.follow(CREATOR, TOPIC_ID, "unfollow");
        assertThat(nothing.changed()).isFalse();
        assertThat(nothing.following()).isFalse();
        assertThat(nothing.followCnt()).isZero();
        assertThat(store.refreshFollowCalls).isZero();
        assertThat(store.deletePairCalls).isEqualTo(1);   // 取关方向不预判、直接删：删 0 行本身就是答案

        service.follow(CREATOR, TOPIC_ID, "follow");
        TopicFollowView dropped = service.follow(CREATOR, TOPIC_ID, "unfollow");
        assertThat(dropped.changed()).isTrue();
        assertThat(dropped.following()).isFalse();
        assertThat(dropped.followCnt()).isZero();
        assertThat(store.topics.get(TOPIC_ID).getFollowCnt()).isEqualTo(store.follows.size());
    }

    @Test
    @DisplayName("待审与不存在的话题都进不了关注：409/30004 与 404/90006，攒关注数要等过审之后")
    void pendingAndMissingTopicsRejectFollow() {
        store.topics.put(GHOST_TOPIC, topic(GHOST_TOPIC, "还没审的话题", "PENDING"));
        TopicService service = service();

        assertThat(expectBiz(() -> service.follow(CREATOR, GHOST_TOPIC, "follow")).getErrorCode())
                .isEqualTo(ErrorCode.TOPIC_PENDING);
        assertThat(expectBiz(() -> service.detail(VIEWER, GHOST_TOPIC)).getErrorCode())
                .isEqualTo(ErrorCode.TOPIC_PENDING);
        assertThat(expectBiz(() -> service.follow(CREATOR, 404404L, "follow")).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(store.follows).isEmpty();
        assertThat(store.refreshFollowCalls).isZero();
    }

    @Test
    @DisplayName("动作非法在「查账号」之前就拒：省一次无谓的 SELECT，也给的是能改的提示")
    void badActionIsCheckedFirst() {
        approved();
        TopicService service = service();

        // 这个账号在内存表里根本不存在，若判据顺序反了就会回 20001，用户对着「用户不存在」一头雾水
        assertThat(expectBiz(() -> service.follow(404404L, TOPIC_ID, "subscribe")).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("详情页资料头带当前访问者的关注态；desc 为空回空串，cover 为 null（前端按占位图渲染）")
    void detailCarriesViewerState() {
        Topic saved = approved();
        saved.setDescTxt(null);
        saved.setCover(null);
        store.follows.add(VIEWER + "#" + TOPIC_ID);
        TopicService service = service();

        TopicCard mine = service.detail(VIEWER, TOPIC_ID);
        assertThat(mine.id()).isEqualTo(TOPIC_ID);
        assertThat(mine.name()).isEqualTo("失眠夜");
        assertThat(mine.desc()).isEmpty();          // 不是 null：前端直接渲染，不用到处 ?.
        assertThat(mine.cover()).isNull();
        assertThat(mine.following()).isTrue();
        assertThat(mine.postCnt()).isEqualTo(3);
        assertThat(mine.isOfficial()).isEqualTo(1);
        assertThat(mine.hotScore()).isEqualByComparingTo("1.5000");

        assertThat(service.detail(CREATOR, TOPIC_ID).following()).isFalse();
        assertThat(service.detail(VIEWER, TOPIC_ID).desc()).isEmpty();
    }

    @Test
    @DisplayName("话题不存在时详情回 404/90006，不给「存在但被拒」留枚举通道（与帖子同一口径）")
    void missingTopicDetailIsNotFound() {
        assertThat(expectBiz(() -> service().detail(VIEWER, 404404L)).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ------------------------------------------------------------------ 响应体形状

    @Test
    @DisplayName("三个响应 record 的字段名是前端契约：改名要连着改 api/topic.js 与 TopicDetailView")
    void responseRecordsKeepTheirShape() {
        assertThat(componentNames(TopicCard.class)).containsExactly(
                "id", "name", "desc", "postCnt", "followCnt", "hotScore", "isOfficial", "cover", "following");
        assertThat(componentNames(TopicCreateView.class)).containsExactly(
                "id", "name", "desc", "auditStatus", "usable", "tip", "postCnt", "followCnt");
        assertThat(componentNames(TopicFollowView.class)).containsExactly(
                "topicId", "action", "changed", "following", "followCnt");
        assertThat(componentNames(TopicCreateRequest.class)).containsExactly("name", "desc");
        assertThat(componentNames(TopicFollowRequest.class)).containsExactly("action");
        // 请求体只有 action 一个字段：前端按钮传 follow/unfollow，不传布尔，是为了让「再点一次」
        // 有确定的语义（幂等的布尔翻转做不到这一点），也与关注某人的请求体同形。
    }

    // ------------------------------------------------------------------ 替身

    /**
     * {@code topic} / {@code topic_follow} / {@code user} 三张表的内存替身。
     *
     * <p>只实现本类真正会走到的那几刀，其余方法一律 {@link UnsupportedOperationException} ——
     * 「测了个没人调的分支」是替身最容易骗人的方式：真服务哪天不这么查了，替身还替它答得出来。</p>
     */
    private static final class Store {

        final Map<Long, Topic> topics = new LinkedHashMap<>();
        final Map<Long, User> users = new LinkedHashMap<>();
        /** 关注关系，键为 {@code userId + "#" + topicId}；用 # 分隔是防 8 与 801 前缀撞车。 */
        final Set<String> follows = new HashSet<>();
        final List<Topic> inserted = new ArrayList<>();

        int insertCalls;
        int insertIgnoreCalls;
        int deletePairCalls;
        int refreshFollowCalls;
        int refreshPostCntCalls;
        /** 打开后 {@code insert} 抛 {@link DuplicateKeyException}，替并发撞 {@code uk_name}。 */
        boolean duplicateOnInsert;
        private long seq = TOPIC_ID;

        TopicMapper topicMapper() {
            return (TopicMapper) Proxy.newProxyInstance(Store.class.getClassLoader(),
                    new Class<?>[] { TopicMapper.class }, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "insert":
                        insertCalls++;
                        if (duplicateOnInsert) {
                            throw new DuplicateKeyException(
                                    "Duplicate entry for key 'topic.uk_name'");
                        }
                        Topic entity = (Topic) args[0];
                        entity.setId(++seq);
                        topics.put(entity.getId(), entity);
                        inserted.add(entity);
                        return 1;
                    case "selectById": {
                        Topic found = topics.get(args[0]);
                        // 替 @TableLogic：逻辑删除的行在 selectById 里查不到
                        if (found != null && Integer.valueOf(1).equals(found.getDeleted())) {
                            return null;
                        }
                        return found;
                    }
                    case "findByName": {
                        String wanted = (String) args[0];
                        // 替 utf8mb4_0900_ai_ci（大小写不敏感）+ 那条绕过 @TableLogic 的裸 SQL
                        for (Topic item : topics.values()) {
                            if (item.getName() != null && item.getName().equalsIgnoreCase(wanted)) {
                                return item;
                            }
                        }
                        return null;
                    }
                    case "refreshPostCnt":
                        refreshPostCntCalls++;
                        return 1;
                    case "toString":
                        return "TopicMapper$Fake";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        throw new UnsupportedOperationException(
                                "本用例不预期的调用：TopicMapper." + method.getName());
                }
            });
        }

        TopicFollowMapper followMapper() {
            return (TopicFollowMapper) Proxy.newProxyInstance(Store.class.getClassLoader(),
                    new Class<?>[] { TopicFollowMapper.class }, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "insertIgnore": {
                        insertIgnoreCalls++;
                        String pair = args[0] + "#" + args[1];
                        return follows.add(pair) ? 1 : 0;
                    }
                    case "deletePair": {
                        deletePairCalls++;
                        String pair = args[0] + "#" + args[1];
                        return follows.remove(pair) ? 1 : 0;
                    }
                    case "isFollowing":
                        return follows.contains(args[0] + "#" + args[1]) ? 1L : 0L;
                    case "countByUser": {
                        String prefix = args[0] + "#";
                        return follows.stream().filter(item -> item.startsWith(prefix)).count();
                    }
                    case "refreshFollowCnt": {
                        // 🔴 重算而不是自增：与 TopicFollowMapper 那条 UPDATE 的语义一致，
                        // 也让「follow_cnt == 真相表行数」这条不变式在测试里真的可验证
                        refreshFollowCalls++;
                        long topicId = ((Number) args[0]).longValue();
                        String suffix = "#" + topicId;
                        int total = (int) follows.stream().filter(item -> item.endsWith(suffix)).count();
                        Topic target = topics.get(topicId);
                        if (target != null) {
                            target.setFollowCnt(total);
                        }
                        return 1;
                    }
                    case "toString":
                        return "TopicFollowMapper$Fake";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        throw new UnsupportedOperationException(
                                "本用例不预期的调用：TopicFollowMapper." + method.getName());
                }
            });
        }
    }

    /**
     * 只覆盖 {@code requireUser} 的 AuthService 替身。
     *
     * <p><b>为什么不能用 JDK 动态代理</b>：{@link AuthService} 是类不是接口，
     * 而本仓库的测试不引 mock 框架（少一层能改行为、又能吞掉「方法名拼错」的东西）。
     * 继承一次、其余八个参数全传 null 就够 —— 本类只走 {@code requireUser} 这一条路，
     * 它只读 {@code userMapper}，一旦哪天有人在这里顺手调了别的方法，
     * 空指针会立刻指出「这条测试没打算测那个」。</p>
     */
    private static final class StubAuth extends AuthService {

        private final Store store;

        StubAuth(Store store) {
            super(null, null, null, null, null, null, null, null);
            this.store = store;
        }

        @Override
        public User requireUser(Long userId) {
            User user = userId == null ? null : store.users.get(userId);
            if (user == null) {
                throw new BizException(ErrorCode.USER_NOT_FOUND);
            }
            return user;
        }
    }
}

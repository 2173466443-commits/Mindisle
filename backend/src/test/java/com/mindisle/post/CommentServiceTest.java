package com.mindisle.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.mindisle.audit.SensitiveWordEngine;
import com.mindisle.cache.CaffeineCacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.AlertTicket;
import com.mindisle.entity.AnonymousAlias;
import com.mindisle.entity.Comment;
import com.mindisle.entity.NotifyMessage;
import com.mindisle.entity.Post;
import com.mindisle.entity.User;
import com.mindisle.notify.RecordingNotifyService;
import com.mindisle.post.dto.CommentCreateRequest;
import com.mindisle.post.dto.CommentCreateView;
import com.mindisle.post.dto.CommentItem;
import com.mindisle.post.dto.CommentThread;

/**
 * 评论与楼中楼单测（任务 T3.7 · 需求 FR4.3、FR4.4、FR7.3、BR1、BR4、BR6）。
 *
 * <p><b>为什么能这么测</b>：{@link CommentService} 把落库全部推到 {@code CommentStore} 端口后面，
 * 于是「两级压平」「父级越帖」「匿名不解匿」「待审只对作者可见」「危机建单」这些
 * 只能靠构造边界场景才验得到的规则，这里全部不连库、不起 Spring 就能钉死。
 * 机审与配额用<b>真的</b> {@link SensitiveWordEngine} 与 {@link PostingQuotaService}
 * （后者只依赖 CacheService，new 一个 {@link CaffeineCacheService} 就是最真实的实现），
 * 所以「评论也要过 T3.2」「禁言不能说话但能点赞」这两条跨类规则是真跑通了一遍，
 * 不是两边各自 mock 得像。</p>
 *
 * <p><b>本类刻意不测的东西</b>：{@code CommentStoreAdapter} 那 12 个方法的 QueryWrapper
 * 到底拼出了什么 SQL、{@code refreshPostCommentCnt} 那条相关子查询过不过滤软删行——
 * 那是「接线错」而不是「规则错」，由 docs/smoke.mjs 第 16 步打真 HTTP + 真库全表谓词取证负责。
 * 在这里假装测过 SQL，就等于把两类缺陷混进同一份绿。</p>
 */
class CommentServiceTest {

    /** 固定时间基准：既当树洞到期判据，也决定评论配额的日窗；服务内部不读系统时钟。 */
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 9, 21, 10, 0);

    private static final long POST_ID = 601L;
    private static final long POST2_ID = 602L;
    private static final long AUTHOR_ID = 900L;
    private static final long ME = 901L;
    private static final long OTHER = 902L;

    /** 与 SensitiveWordEngineTest 同一套词库格式，只留本类要用的五组。 */
    private static final String FIXTURE = String.join("\n",
            "#version=test-comment-1",
            row("政治违法", "black", "BLOCK", "both", "contains", "枪支弹药"),
            row("辱骂攻击", "grey", "REVIEW", "user", "contains", "傻逼"),
            row("隐私泄露", "grey", "REVIEW", "both", "regex", "1[3-9][0-9]{9}"),
            row("自伤自杀", "risk", "TAG", "both", "contains", "伤害自己"),
            row("自伤自杀", "risk", "TAG", "both", "contains", "割腕"));

    private FakeStore store;
    private MindisleProperties properties;
    private PostingQuotaService quota;
    private AnonymousAliasService aliasService;
    private CommentService service;
    private RecordingNotifyService notify;

    private static String row(String group, String level, String action, String scope,
                             String matchType, String word) {
        return String.join("\t", group, level, action, scope, matchType, word);
    }

    @BeforeEach
    void setUp() {
        SensitiveWordEngine engine = new SensitiveWordEngine(new MindisleProperties(),
                new DefaultResourceLoader());
        engine.reload(FIXTURE);
        properties = new MindisleProperties();
        quota = new PostingQuotaService(new CaffeineCacheService(), properties);
        InMemoryAliasRepository aliasRepository = new InMemoryAliasRepository();
        aliasService = new AnonymousAliasService(aliasRepository);
        store = new FakeStore(aliasRepository);
        store.posts.put(POST_ID, publicPost(POST_ID, AUTHOR_ID, "normal"));
        store.posts.put(POST2_ID, publicPost(POST2_ID, OTHER, "normal"));
        store.users.put(AUTHOR_ID, activeUser(AUTHOR_ID, "楼主"));
        store.users.put(ME, activeUser(ME, "我"));
        store.users.put(OTHER, activeUser(OTHER, "路人"));
        notify = new RecordingNotifyService();
        service = new CommentService(store, quota, engine, new CaffeineCacheService(),
                aliasService, properties, notify.service());
    }

    // ------------------------------------------------------------------ 造数据

    private static Post publicPost(long id, long userId, String type) {
        Post post = new Post();
        post.setId(id);
        post.setUserId(userId);
        post.setType(type);
        post.setStatus(PostService.STATUS_PUBLISHED);
        post.setVisibility(PostQueryService.VISIBILITY_PUBLIC);
        post.setIsAnonymous(0);
        // DDL 里几个计数列都是 NOT NULL DEFAULT 0：fake 必须一样，否则「零写入」路径拿到 null，
        // 断言会因为替身太宽松而假绿（这条是 T3.6 实测踩过的）。
        post.setViewCnt(0);
        post.setLikeCnt(0);
        post.setCommentCnt(0);
        post.setCollectCnt(0);
        return post;
    }

    private static User activeUser(long id, String nickname) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setNickname(nickname);
        user.setStatus("ACTIVE");
        user.setCreatedAt(DAY.minusDays(60));
        return user;
    }

    /** 改账号状态（BR6 的禁言 / 封禁分支）。 */
    private void withStatus(long userId, String status) {
        User user = store.users.get(userId);
        user.setStatus(status);
    }

    /** 手工塞一条已存在的评论，用来构造「回复一个别人看不见的父级」这类真机上要排队才能撞到的状态。 */
    private Comment seedComment(long id, long postId, long userId, Long parentId, Long rootId,
                                String status) {
        Comment row = new Comment();
        row.setId(id);
        row.setPostId(postId);
        row.setUserId(userId);
        row.setParentId(parentId);
        row.setRootId(rootId);
        row.setReplyToUserId(parentId == null ? null : userId);
        row.setContent("种子内容" + id);
        row.setIsAnonymous(0);
        row.setStatus(status);
        row.setLikeCnt(0);
        row.setDeleted(0);
        row.setCreatedAt(DAY.minusHours(1));
        row.setUpdatedAt(DAY.minusHours(1));
        store.comments.put(id, row);
        return row;
    }

    private CommentCreateView comment(long userId, long postId, String content) {
        return service.comment(userId, postId, new CommentCreateRequest(content, null, null), DAY);
    }

    private CommentCreateView reply(long userId, long postId, String content, Long parentId) {
        return service.comment(userId, postId, new CommentCreateRequest(content, parentId, null), DAY);
    }

    /** 最近一次由服务写进 fake 的那一行（含自增回填的 id）。 */
    private Comment lastRow() {
        return store.comments.get(store.nextId);
    }

    private static ErrorCode codeOf(Runnable call) {
        return assertThrows(BizException.class, call::run).getErrorCode();
    }

    private static String messageOf(Runnable call) {
        BizException error = assertThrows(BizException.class, call::run);
        return error.getMessage();
    }

    private PageResult<CommentThread> list(long postId, long viewerId, Long rootId, int page, int size) {
        PageQuery query = new PageQuery();
        query.setPage(page);
        query.setSize(size);
        return service.list(postId, viewerId, rootId, query, DAY);
    }

    // ------------------------------------------------------------------ 入参归一

    @Test
    @DisplayName("纯空白评论 10001，且一行都不写：先拒再落库，不能靠后置的 NOT NULL 约束")
    void blankContentIsRejectedBeforeAnyWrite() {
        int inserts = store.insertCalls;
        assertThat(codeOf(() -> comment(ME, POST_ID, "  \n\t "))).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(messageOf(() -> comment(ME, POST_ID, "   "))).contains("不能为空");
        assertThat(store.insertCalls).as("被拒的评论不许留下半行数据").isEqualTo(inserts);
        assertThat(store.refreshCalls).isEmpty();
    }

    @Test
    @DisplayName("整个请求体缺失（null）也走同一句 10001，不是 500")
    void nullRequestIsBadRequestNotInternalError() {
        assertThat(codeOf(() -> service.comment(ME, POST_ID, null, DAY)))
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("FR4.4 上限 1000 字按码点算：1000 个 emoji 通过、1001 个才拒（按 UTF-16 长度会把 emoji 算成两个字）")
    void maxLengthCountsCodePointsNotUtf16Units() {
        String emoji = new String(Character.toChars(0x1F6AA));
        assertThat(emoji.length()).as("一个增补平面 emoji 在 UTF-16 里占两个 char").isEqualTo(2);

        CommentCreateView ok = comment(ME, POST_ID, emoji.repeat(1000));
        assertThat(ok.comment().content()).hasSize(2000);
        assertThat(store.comments.get(ok.comment().id()).getContent()).hasSize(2000);

        assertThat(codeOf(() -> comment(ME, POST_ID, emoji.repeat(1001)))).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(messageOf(() -> comment(ME, POST_ID, "啊".repeat(1001)))).contains("1000");
    }

    @Test
    @DisplayName("首尾空白被去掉后才判空与判长，落库的是 trim 过的内容")
    void contentIsTrimmedBeforeStoring() {
        CommentCreateView view = comment(ME, POST_ID, "\n  抱抱你  \t");
        assertThat(view.comment().content()).isEqualTo("抱抱你");
        assertThat(lastRow().getContent()).isEqualTo("抱抱你");
    }

    // ------------------------------------------------------------------ 帖子可见性（FR7.3）

    @Test
    @DisplayName("帖子不可见一律 404/30001，与「帖子不存在」同一句话：不能借评论接口枚举私密帖")
    void invisiblePostIsIndistinguishableFromMissingPost() {
        Post mine = publicPost(700L, ME, "normal");
        mine.setVisibility("private");
        store.posts.put(700L, mine);
        assertThat(codeOf(() -> comment(OTHER, 700L, "路过的话"))).isEqualTo(ErrorCode.POST_NOT_FOUND);
        assertThat(messageOf(() -> comment(OTHER, 700L, "在吗"))).isEqualTo(ErrorCode.POST_NOT_FOUND.getMsg());
        assertThat(store.insertCalls).as("判 404 之前不许写任何东西").isZero();

        // 同一帖，作者自己可以评论：差别只在 visibleTo 里的 owner 分支
        assertThat(comment(ME, 700L, "自己的楼").comment().content()).isEqualTo("自己的楼");
    }

    @Test
    @DisplayName("查无此人（已注销）与帖不存在是两条不同的 404，但都不写库")
    void deletedAuthorCannotComment() {
        store.users.remove(ME);
        assertThat(codeOf(() -> comment(ME, POST_ID, "还有人吗"))).isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThat(store.insertCalls).isZero();
    }

    @Test
    @DisplayName("树洞到期销毁后不能评论：可见性判据只有 PostQueryService.visibleTo 那一份")
    void expiredTreeHoleRejectsComments() {
        Post expired = publicPost(701L, AUTHOR_ID, "hole");
        expired.setAutoDestroyAt(DAY.minusMinutes(1));
        store.posts.put(701L, expired);
        assertThat(codeOf(() -> comment(ME, 701L, "有人吗"))).isEqualTo(ErrorCode.POST_NOT_FOUND);
    }

    // ------------------------------------------------------------------ BR6 账号资格

    @Test
    @DisplayName("BR6：禁言能点赞但不能评论（10003），封禁连账号都不算能用（20003）")
    void mutedCanNotSpeakButBannedIsDisabled() {
        withStatus(ME, "MUTED");
        assertThat(codeOf(() -> comment(ME, POST_ID, "说点什么"))).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(messageOf(() -> comment(ME, POST_ID, "说点什么"))).contains("禁言");

        withStatus(OTHER, "BANNED");
        assertThat(codeOf(() -> comment(OTHER, POST_ID, "说点什么"))).isEqualTo(ErrorCode.USER_DISABLED);
        assertThat(store.insertCalls).as("两档拦截都发生在写入之前").isZero();
    }

    // ------------------------------------------------------------------ BR4 配额

    @Test
    @DisplayName("BR4：同一人同一帖当日第 21 条 30003；换一帖重新计数（配额键含 postId）")
    void dailyCommentQuotaIsPerPost() {
        int limit = properties.getQuota().getDailyCommentsPerPost();
        for (int i = 1; i <= limit; i++) {
            comment(ME, POST_ID, "第 " + i + " 条");
        }
        assertThat(codeOf(() -> comment(ME, POST_ID, "超了"))).isEqualTo(ErrorCode.COMMENT_TOO_MANY);
        assertThat(comment(ME, POST2_ID, "换一帖还能说").comment().content()).isEqualTo("换一帖还能说");
    }

    @Test
    @DisplayName("被机审拦下的评论照样消耗配额：拦内容不拦人，也不开放「免费无限试探拦截边界」")
    void rejectedCommentStillConsumesQuota() {
        int limit = properties.getQuota().getDailyCommentsPerPost();
        for (int i = 1; i <= limit; i++) {
            CommentCreateView view = comment(ME, POST_ID, "枪支弹药 " + i);
            assertThat(view.comment().status()).isEqualTo(Comment.STATUS_REJECTED);
        }
        assertThat(store.comments.values().stream()
                .filter(row -> Comment.STATUS_REJECTED.equals(row.getStatus())).count())
                .as("REJECTED 也要留下一行，供人工复核与风控回溯").isEqualTo(limit);
        assertThat(codeOf(() -> comment(ME, POST_ID, "正常的一句话")))
                .as("前 20 条全被拦下，第 21 条照样算超额").isEqualTo(ErrorCode.COMMENT_TOO_MANY);
    }

    // ------------------------------------------------------------------ 两级展示、无限层级存储

    @Test
    @DisplayName("不带 parentId 即一级评论：parent_id 与 root_id 都是 NULL")
    void topLevelCommentKeepsBothAnchorsNull() {
        CommentCreateView view = comment(ME, POST_ID, "一楼");
        Comment row = lastRow();
        assertThat(row.getParentId()).isNull();
        assertThat(row.getRootId()).isNull();
        assertThat(row.getReplyToUserId()).isNull();
        assertThat(view.comment().parentId()).isNull();
        assertThat(view.comment().rootId()).isNull();
        assertThat(view.comment().replyToName()).as("一级评论没有「回复谁」这句话").isNull();
    }

    @Test
    @DisplayName("回复一级评论：root_id 就等于父 id，一次 root_id 查询能捞全整棵子树")
    void replyToRootAnchorsItselfToThatRoot() {
        long rootId = seedComment(5001L, POST_ID, OTHER, null, null, Comment.STATUS_PUBLISHED).getId();
        reply(ME, POST_ID, "回复一楼", rootId);
        Comment row = lastRow();
        assertThat(row.getParentId()).isEqualTo(rootId);
        assertThat(row.getRootId()).as("子树锚点必须是那条一级评论").isEqualTo(rootId);
        assertThat(row.getReplyToUserId()).isEqualTo(OTHER);
        assertThat(view(row).replyToName()).isEqualTo("路人");
    }

    @Test
    @DisplayName("回复楼中楼：root_id 沿用祖先、parent_id 指向真身——层级无限但页面只有两层")
    void deepReplyKeepsAncestorRootAndRealParent() {
        long root = seedComment(5010L, POST_ID, OTHER, null, null, Comment.STATUS_PUBLISHED).getId();
        long second = seedComment(5011L, POST_ID, AUTHOR_ID, root, root, Comment.STATUS_PUBLISHED).getId();
        long third = seedComment(5012L, POST_ID, ME, second, root, Comment.STATUS_PUBLISHED).getId();

        reply(AUTHOR_ID, POST_ID, "第四层", third);
        Comment row = lastRow();
        assertThat(row.getParentId()).as("被回复的那一条").isEqualTo(third);
        assertThat(row.getRootId()).as("但展示时仍然挂在一楼下面").isEqualTo(root);
        assertThat(view(row).replyToName()).as("@ 的是第三层的作者").isEqualTo("我");
    }

    @Test
    @DisplayName("parentId 传 0 或负数按一级评论处理：不拿它去查库，也不报「父级不存在」")
    void nonPositiveParentIsTopLevel() {
        int lookups = store.findCommentCalls;
        assertThat(commentWithParent(0L).comment().rootId()).isNull();
        assertThat(commentWithParent(-7L).comment().rootId()).isNull();
        assertThat(store.findCommentCalls)
                .as("0 与负数在服务层就短路掉了，不该变成两次数据库往返").isEqualTo(lookups);
    }

    private CommentCreateView commentWithParent(Long parentId) {
        return service.comment(ME, POST_ID, new CommentCreateRequest("一句话", parentId, null), DAY);
    }

    @Test
    @DisplayName("父级越帖与父级不存在报同一句 400：不借这条通道承认「那个 id 在别的帖子里存在」")
    void parentFromAnotherPostIsRejectedSilently() {
        long elsewhere = seedComment(5020L, POST2_ID, OTHER, null, null, Comment.STATUS_PUBLISHED).getId();
        String cross = messageOf(() -> commentWithParent(elsewhere));
        String missing = messageOf(() -> commentWithParent(999999L));
        assertThat(cross).isEqualTo(missing).contains("不存在或已被删除");
        assertThat(store.insertCalls).isZero();
    }

    @Test
    @DisplayName("只有已发布的评论能被回复：待审评论虽对作者可见也不能当父级（否则回复变孤儿子树）")
    void onlyPublishedCommentsAcceptReplies() {
        long pendingByOther = seedComment(5030L, POST_ID, OTHER, null, null, Comment.STATUS_PENDING).getId();
        assertThat(CommentService.isVisibleTo(store.comments.get(pendingByOther), ME))
                .as("前置事实：这条待审评论对 ME 不可见").isFalse();
        assertThat(messageOf(() -> commentWithParent(pendingByOther))).contains("不能被回复");
        assertThat(store.insertCalls).isZero();

        // 关键对照：自己的待审评论「可见」却依然「不可回复」——可见性判据不能拿来当父级判据。
        long mine = seedComment(5031L, POST_ID, ME, null, null, Comment.STATUS_PENDING).getId();
        assertThat(CommentService.isVisibleTo(store.comments.get(mine), ME)).isTrue();
        assertThat(messageOf(() -> commentWithParent(mine)))
                .as("放行就会造出「回复公开、父级看不见」的孤儿子树，列表页整棵丢掉")
                .contains("不能被回复");
        assertThat(store.insertCalls).as("两次被拒都不许留下半行数据").isZero();

        // 正向：已发布的父级放行，且压平规则、回执展示名一起验掉。
        long published = seedComment(5032L, POST_ID, OTHER, null, null, Comment.STATUS_PUBLISHED).getId();
        CommentCreateView replied = reply(ME, POST_ID, "这才叫回复", published);
        assertThat(replied.comment().replyToName())
                .as("父级实名时 @ 的就是它的作者昵称").isEqualTo("路人");
        assertThat(lastRow().getParentId()).isEqualTo(published);
        assertThat(lastRow().getRootId()).as("回复一级评论时 root_id 就是那条一级评论").isEqualTo(published);
    }

    @Test
    @DisplayName("回复被驳回的评论也拒：被驳回的行留在库里给风控回溯，但不是可回复的对象")
    void replyToRejectedCommentIsRejected() {
        long rejected = seedComment(5040L, POST_ID, OTHER, null, null, Comment.STATUS_REJECTED).getId();
        assertThat(messageOf(() -> commentWithParent(rejected))).contains("不能被回复");
    }

    // ------------------------------------------------------------------ 状态机

    @Test
    @DisplayName("机审终态映射：只有 PUBLISHED/REJECTED 原样，其余（含认不出来的）一律待审——失败关闭")
    void statusForFailsClosed() {
        assertThat(CommentService.statusFor(PostService.STATUS_PUBLISHED)).isEqualTo(Comment.STATUS_PUBLISHED);
        assertThat(CommentService.statusFor(PostService.STATUS_REJECTED)).isEqualTo(Comment.STATUS_REJECTED);
        assertThat(CommentService.statusFor("HUMAN_REVIEW")).isEqualTo(Comment.STATUS_PENDING);
        assertThat(CommentService.statusFor("MACHINE_REVIEW")).isEqualTo(Comment.STATUS_PENDING);
        assertThat(CommentService.statusFor("DRAFT")).isEqualTo(Comment.STATUS_PENDING);
        assertThat(CommentService.statusFor(null)).isEqualTo(Comment.STATUS_PENDING);
        assertThat(CommentService.statusFor("随便一个没见过的值")).isEqualTo(Comment.STATUS_PENDING);
    }

    @Test
    @DisplayName("灰词评论转待审：状态 PENDING、回执带审核提示，post.comment_cnt 不 +1")
    void greyWordCommentGoesToPendingAndDoesNotInflateCounter() {
        CommentCreateView view = comment(ME, POST_ID, "你就是个傻逼");
        assertThat(view.comment().status()).isEqualTo(Comment.STATUS_PENDING);
        assertThat(view.tip()).isNotBlank().doesNotContain("傻逼");
        assertThat(view.hotline()).as("灰词不是危机，不该弹求助卡片").isNull();
        assertThat(store.tickets).isEmpty();
        assertThat(store.posts.get(POST_ID).getCommentCnt())
                .as("待审不进计数，否则列表数字点进去对不上").isZero();
        assertThat(store.refreshCalls).as("仍然要重算一次真相，让冗余列自洽").containsExactly(POST_ID);
    }

    @Test
    @DisplayName("黑词评论 REJECTED 且不留工单（无危机词时）：拦内容，但不打扰人工队列")
    void blackWordCommentIsRejectedWithoutTicket() {
        CommentCreateView view = comment(ME, POST_ID, "卖枪支弹药的");
        assertThat(view.comment().status()).isEqualTo(Comment.STATUS_REJECTED);
        assertThat(view.tip()).isNotBlank().doesNotContain("枪支弹药");
        assertThat(store.tickets).isEmpty();
        assertThat(lastRow().getContent()).as("原文留下供复核，遮罩只用于匿名联系方式").isEqualTo("卖枪支弹药的");
    }

    // ------------------------------------------------------------------ 危机分流（创新点 3）

    @Test
    @DisplayName("L2 评论：放行 + 求助卡片 + 一张 4 小时 SLA 的工单，证据里带评论 id")
    void l2CommentRaisesTicketWithHotline() {
        CommentCreateView view = comment(ME, POST_ID, "这两天一直都在想伤害自己这件事");
        assertThat(view.comment().status()).as("需求 §18.3 放行不删除").isEqualTo(Comment.STATUS_PUBLISHED);
        assertThat(view.hotline()).isEqualTo("12356");
        assertThat(view.tip()).isNotBlank();
        assertThat(store.posts.get(POST_ID).getCommentCnt()).isEqualTo(1);

        assertThat(store.tickets).hasSize(1);
        AlertTicket ticket = store.tickets.get(0);
        assertThat(ticket.getLevel()).isEqualTo(CrisisGrader.L2);
        assertThat(ticket.getUserId()).isEqualTo(ME);
        assertThat(ticket.getSourceType()).isEqualTo("post");
        assertThat(ticket.getSourceId()).isEqualTo(POST_ID);
        assertThat(ticket.getSlaAt()).isEqualTo(DAY.plusHours(4));
        assertThat(ticket.getEvidenceText()).contains("伤害自己").contains("（评论 id=" + lastRow().getId() + "）");
    }

    @Test
    @DisplayName("L3 树洞评论：SLA 收紧到 30 分钟、source_type 沿用 hole（ENUM 里没有 comment，已写进手册 §14）")
    void l3CommentOnHoleUsesTightSlaAndPostSourceType() {
        Post hole = publicPost(780L, AUTHOR_ID, "hole");
        store.posts.put(780L, hole);
        CommentCreateView view = comment(ME, 780L, "我想过用刀片割腕");
        assertThat(view.comment().status()).isEqualTo(Comment.STATUS_PUBLISHED);
        assertThat(store.tickets).hasSize(1);
        AlertTicket ticket = store.tickets.get(0);
        assertThat(ticket.getLevel()).isEqualTo(CrisisGrader.L3);
        assertThat(ticket.getSourceType()).as("评论挂在哪张帖子上就记哪个来源").isEqualTo("hole");
        assertThat(ticket.getSourceId()).isEqualTo(780L);
        assertThat(ticket.getSlaAt()).as("L3 的 30 分钟时限是真落库的，不是页面上算的").isEqualTo(DAY.plusMinutes(30));
        assertThat(ticket.getTriggerWords()).contains("割腕");
    }

    @Test
    @DisplayName("黑词 + 危机词同时命中：内容照拦、工单照建、求助卡片照给（拦内容不拦救助）")
    void blackPlusCrisisStillRaisesTicket() {
        CommentCreateView view = comment(ME, POST_ID, "卖枪支弹药，还有伤害自己");
        assertThat(view.comment().status()).isEqualTo(Comment.STATUS_REJECTED);
        assertThat(view.hotline()).isEqualTo("12356");
        assertThat(store.tickets).hasSize(1);
        assertThat(store.tickets.get(0).getLevel()).isEqualTo(CrisisGrader.L2);
        assertThat(store.posts.get(POST_ID).getCommentCnt()).as("被拦内容不进计数").isZero();
    }

    @Test
    @DisplayName("无危机时回执里没有 hotline 字段值：null 而不是空串，前端 v-if 才不会被「有值」骗到")
    void hotlineIsNullForOrdinaryComment() {
        CommentCreateView view = comment(ME, POST_ID, "今天天气不错");
        assertThat(view.hotline()).isNull();
        assertThat(view.tip()).as("无命中连提示也不给，别把用户当需要教育的新手").isNull();
    }

    // ------------------------------------------------------------------ 匿名（FR1.4 / BR1）

    @Test
    @DisplayName("匿名评论：出参不回 authorId、展示马甲名；同一人第二张马甲是不允许的（BR1 复用同一 id）")
    void anonymousCommentHidesAuthorIdAndReusesOneFace() {
        CommentCreateView first = service.comment(ME, POST_ID,
                new CommentCreateRequest("抱抱楼主", null, true), DAY);
        assertThat(first.comment().authorId()).as("留在响应体里就等于能被反查，前端不显示不算防护").isNull();
        assertThat(first.comment().authorName()).startsWith("匿名屿民·").isNotEqualTo("我");
        assertThat(first.comment().anonymous()).isTrue();
        Comment row = lastRow();
        assertThat(row.getUserId()).as("库里必须有真实作者，否则无法处置违规").isEqualTo(ME);
        assertThat(row.getIsAnonymous()).isEqualTo(1);
        assertThat(row.getAliasId()).isNotNull();

        CommentCreateView second = service.comment(ME, POST_ID,
                new CommentCreateRequest("再抱一次", null, true), DAY);
        assertThat(lastRow().getAliasId())
                .as("同一场景同一人只有一匹马甲：换了帖也不能换脸").isEqualTo(row.getAliasId());
        assertThat(second.comment().authorName()).isEqualTo(first.comment().authorName());
    }

    @Test
    @DisplayName("回复一条匿名评论时 @ 的是马甲名：「回复」这个动作不能变成解匿通道")
    void replyToAnonymousCommentNeverLeaksRealNickname() {
        AnonymousAlias face = aliasService.resolve(OTHER, CommentService.ANON_SCENE);
        store.users.get(OTHER).setNickname("路人真名");
        Comment anon = seedComment(5050L, POST_ID, OTHER, null, null, Comment.STATUS_PUBLISHED);
        anon.setIsAnonymous(1);
        anon.setAliasId(face.getId());

        CommentCreateView view = reply(ME, POST_ID, "顶一下", anon.getId());
        assertThat(view.comment().replyToName())
                .as("取马甲名而不是作者昵称：这是本类最容易被写错的一行").isEqualTo(face.getAliasName());
        assertThat(view.comment().replyToName()).isNotEqualTo("路人真名");
        // 实名回复：@ 的才是真昵称
        long named = seedComment(5051L, POST_ID, OTHER, null, null, Comment.STATUS_PUBLISHED).getId();
        assertThat(reply(ME, POST_ID, "再顶", named).comment().replyToName()).isEqualTo("路人真名");
    }

    @Test
    @DisplayName("父评论整行取不到时兜底「已隐藏的评论」，而不是把 null 拼成一句「回复 null」")
    void missingParentRowFallsBackToHiddenLabel() {
        long ghost = seedComment(5060L, POST_ID, OTHER, null, null, Comment.STATUS_PUBLISHED).getId();
        CommentCreateView view = reply(ME, POST_ID, "先留着", ghost);
        Comment row = lastRow();
        store.comments.remove(ghost);
        assertThat(view(row).replyToName()).isEqualTo(CommentService.HIDDEN_PARENT);
    }

    @Test
    @DisplayName("匿名评论里的手机号落库前就被遮掉且提示里有这句话；实名评论不遮（口径与匿名帖一致）")
    void anonymousCommentMasksContactInfo() {
        CommentCreateView anon = service.comment(ME, POST_ID,
                new CommentCreateRequest("有事找我13800138000", null, true), DAY);
        String stored = lastRow().getContent();
        assertThat(stored).doesNotContain("13800138000").hasSize("有事找我13800138000".length());
        assertThat(anon.tip()).contains("联系方式");

        CommentCreateView named = comment(ME, POST_ID, "有事找我13900139000");
        assertThat(lastRow().getContent())
                .as("实名评论的作者本来就露着身份，遮联系方式属于越权修改用户内容").isEqualTo("有事找我13900139000");
        assertThat(named.tip())
                .as("手机号规则是 grey/REVIEW：实名评论同样会转人工审核并给出审核提示，"
                        + "但因为作者身份本来就露着，这里绝不许说「联系方式已遮罩」那句假话")
                .doesNotContain("遮")
                .contains("人工审核");
    }

    @Test
    @DisplayName("危机词原文不被匿名遮罩吃掉：工单证据要能看清原句，否则复核变成猜谜")
    void crisisTextSurvivesAnonymityMasking() {
        service.comment(ME, POST_ID, new CommentCreateRequest("想伤害自己，电话13800138000", null, true), DAY);
        assertThat(lastRow().getContent()).contains("伤害自己").doesNotContain("13800138000");
        assertThat(store.tickets.get(0).getEvidenceText()).contains("伤害自己");
    }

    @Test
    @DisplayName("马甲行被人删掉时展示兜底「匿名屿民」，而不是抛 NPE 把整个列表接口打挂")
    void missingAliasRowFallsBackToGenericAnonymousName() {
        Comment row = new Comment();
        row.setId(5070L);
        row.setPostId(POST_ID);
        row.setUserId(ME);
        row.setStatus(Comment.STATUS_PUBLISHED);
        row.setContent("占位");
        row.setIsAnonymous(1);
        row.setAliasId(424242L);
        assertThat(CommentService.displayNameOf(row, contextFor(row)))
                .isEqualTo(PostQueryService.ANONYMOUS_FALLBACK);
    }

    // ------------------------------------------------------------------ 站内通知（T3.11-b · FR9.1）

    @Test
    @DisplayName("评论别人的帖子：楼主收到一条 comment 通知，正文是评论摘录，状态未读")
    void commentOnOthersPostNotifiesPostOwner() {
        comment(ME, POST_ID, "楼主抱抱");
        assertThat(notify.size()).as(notify.dump()).isEqualTo(1);
        NotifyMessage sent = notify.rows().get(0);
        assertThat(sent.getUserId()).isEqualTo(AUTHOR_ID);
        assertThat(sent.getType()).isEqualTo(NotifyMessage.TYPE_COMMENT);
        assertThat(sent.getTitle()).isEqualTo("我 评论了你的帖子");
        assertThat(sent.getContent()).as("摘录加书名号引号，前端不必再拼").isEqualTo("「楼主抱抱」");
        assertThat(sent.getRefType()).isEqualTo(NotifyMessage.REF_POST);
        assertThat(sent.getRefId()).isEqualTo(POST_ID);
        assertThat(sent.getIsRead()).as("新通知必须未读，红点只数这个").isEqualTo(0);
        assertThat(notify.pushCount()).as("推送口每落一行调一次（阶段 5 换成 WebSocket 时靠这条回归）").isEqualTo(1);
    }

    @Test
    @DisplayName("回复楼主自己的评论：只留「回复了你的评论」一条，同一件事不占两个红点")
    void replyToOwnerCollapsesIntoSingleNotification() {
        long root = seedComment(5070L, POST_ID, AUTHOR_ID, null, null, Comment.STATUS_PUBLISHED).getId();
        reply(ME, POST_ID, "同意楼主", root);
        assertThat(notify.dump()).as("楼主既是被回复者又是帖主，只发更具体的那条").isEqualTo("comment|" + AUTHOR_ID + "|我 回复了你的评论");
        assertThat(notify.rows().get(0).getContent()).isEqualTo("「同意楼主」");
    }

    @Test
    @DisplayName("回复别人的评论：被回复者与楼主各一条，两件事不能合成一条")
    void replyNotifiesRepliedUserAndPostOwnerSeparately() {
        long root = seedComment(5071L, POST_ID, OTHER, null, null, Comment.STATUS_PUBLISHED).getId();
        reply(ME, POST_ID, "我补充一句", root);
        assertThat(notify.size()).as(notify.dump()).isEqualTo(2);
        assertThat(notify.ofType(NotifyMessage.TYPE_COMMENT)).hasSize(2);
        assertThat(notify.rows()).anySatisfy(row -> {
            assertThat(row.getUserId()).isEqualTo(OTHER);
            assertThat(row.getTitle()).isEqualTo("我 回复了你的评论");
        });
        assertThat(notify.rows()).anySatisfy(row -> {
            assertThat(row.getUserId()).as("楼主看到的仍是「评论了你的帖子」").isEqualTo(AUTHOR_ID);
            assertThat(row.getTitle()).isEqualTo("我 评论了你的帖子");
        });
    }

    @Test
    @DisplayName("匿名评论的通知只写马甲名：真昵称一旦进正文就永久留痕（FR1.4）")
    void anonymousCommentNotificationUsesAliasNameOnly() {
        store.users.get(ME).setNickname("我的真名");
        service.comment(ME, POST_ID, new CommentCreateRequest("抱抱楼主", null, true), DAY);
        assertThat(notify.size()).as(notify.dump()).isEqualTo(1);
        NotifyMessage sent = notify.rows().get(0);
        assertThat(sent.getTitle()).startsWith("匿名屿民·").doesNotContain("我的真名");
    }

    @Test
    @DisplayName("待审与被驳回的评论不发通知；楼主自评也不发")
    void pendingRejectedAndSelfCommentsSendNothing() {
        comment(ME, POST_ID, "你就是个傻逼");
        assertThat(notify.size()).as("转人审的评论只有作者自己看得见，不该广播").isZero();
        comment(ME, POST_ID, "卖枪支弹药的");
        assertThat(notify.size()).isZero();
        comment(AUTHOR_ID, POST_ID, "楼主自己补充一句");
        assertThat(notify.size()).as("自己评论自己的帖子，不需要提醒自已").isZero();
    }

    @Test
    @DisplayName("超过 60 字的评论只落摘录并补省略号：通知是提醒不是阅读器")
    void longCommentIsExcerptedIntoNotification() {
        String long500 = "话".repeat(70);
        comment(ME, POST_ID, long500);
        NotifyMessage sent = notify.rows().get(0);
        assertThat(sent.getContent()).startsWith("「" + "话".repeat(60)).endsWith("」").contains("…");
        assertThat(sent.getContent().codePointCount(0, sent.getContent().length())).isEqualTo(63);
    }


    @Test
    @DisplayName("isVisibleTo 三条腿：公开人人可见、待审只给作者、驳回谁都不给")
    void visibilityRulesPerStatus() {
        Comment published = commentRow(1L, OTHER, Comment.STATUS_PUBLISHED);
        Comment pending = commentRow(2L, OTHER, Comment.STATUS_PENDING);
        Comment rejected = commentRow(3L, OTHER, Comment.STATUS_REJECTED);
        assertThat(CommentService.isVisibleTo(published, ME)).isTrue();
        assertThat(CommentService.isVisibleTo(published, OTHER)).isTrue();
        assertThat(CommentService.isVisibleTo(pending, OTHER)).as("作者本人看得见自己的").isTrue();
        assertThat(CommentService.isVisibleTo(pending, ME)).as("别人看不见，也就不进 comment_cnt").isFalse();
        assertThat(CommentService.isVisibleTo(rejected, OTHER)).isFalse();
        assertThat(CommentService.isVisibleTo(rejected, ME)).isFalse();
        assertThat(CommentService.isVisibleTo(null, ME)).isFalse();
    }

    @Test
    @DisplayName("审核提示只发给这条评论的作者：viewerId 与 postOwnerId 混用会让别人看到一句与己无关的提示")
    void auditTipGoesOnlyToTheCommentAuthor() {
        Comment pending = commentRow(4L, OTHER, Comment.STATUS_PENDING);
        assertThat(CommentService.auditTipOf(pending, OTHER)).isEqualTo(PostQueryService.AUDIT_TIP);
        assertThat(CommentService.auditTipOf(pending, ME)).isNull();
        assertThat(CommentService.auditTipOf(commentRow(5L, OTHER, Comment.STATUS_PUBLISHED), OTHER)).isNull();
    }

    @Test
    @DisplayName("toItem 的 authorIsPostOwner 认的是「帖子的作者」：它和 viewerId 是两个不同的问题")
    void authorIsPostOwnerIsAboutThePostAuthorNotTheViewer() {
        Comment byOwner = commentRow(6L, AUTHOR_ID, Comment.STATUS_PUBLISHED);
        Comment byStranger = commentRow(7L, OTHER, Comment.STATUS_PUBLISHED);
        byOwner.setPostId(POST_ID);
        byStranger.setPostId(POST_ID);
        CommentItem ownerItem = CommentService.toItem(byOwner, contextFor(byOwner));
        CommentItem strangerItem = CommentService.toItem(byStranger, contextFor(byStranger));
        assertThat(ownerItem.authorIsPostOwner()).as("楼主自己回复等于「置顶自己那条」的前端判据").isTrue();
        assertThat(strangerItem.authorIsPostOwner()).isFalse();
        assertThat(strangerItem.authorId()).isEqualTo(OTHER);
    }

    @Test
    @DisplayName("assemble 每棵子树只预览 3 条回复，但 replyTotal 是整棵子树的真相")
    void assemblePreviewsThreeAndReportsTrueTotal() {
        long root = seedComment(5080L, POST_ID, OTHER, null, null, Comment.STATUS_PUBLISHED).getId();
        List<Comment> replies = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            replies.add(seedComment(5080L + i, POST_ID, ME, root, root, Comment.STATUS_PUBLISHED));
        }
        Comment rootRow = store.comments.get(root);
        List<Comment> all = new ArrayList<>(List.of(rootRow));
        all.addAll(replies);
        List<CommentThread> threads = CommentService.assemble(List.of(rootRow), replies,
                contextForAll(all), CommentService.REPLY_PREVIEW);

        assertThat(threads).hasSize(1);
        assertThat(threads.get(0).replies()).as("预览条数由服务侧常量决定").hasSize(CommentService.REPLY_PREVIEW);
        assertThat(threads.get(0).replyTotal()).as("「查看 N 条回复」的 N 是全量，不是预览数").isEqualTo(5);
        assertThat(threads.get(0).root().id()).isEqualTo(root);
    }

    @Test
    @DisplayName("assemble 不认识的 rootId 直接丢：孤儿回复不许挂到别的子树下面冒充楼层")
    void assembleDropsRepliesWhoseRootIsNotOnThisPage() {
        long rootA = seedComment(5090L, POST_ID, OTHER, null, null, Comment.STATUS_PUBLISHED).getId();
        long rootB = seedComment(5091L, POST_ID, OTHER, null, null, Comment.STATUS_PUBLISHED).getId();
        Comment orphan = seedComment(5092L, POST_ID, ME, rootB, rootB, Comment.STATUS_PUBLISHED);
        List<CommentThread> threads = CommentService.assemble(
                List.of(store.comments.get(rootA)), List.of(orphan),
                contextForAll(List.of(store.comments.get(rootA), orphan)), 3);
        assertThat(threads).hasSize(1);
        assertThat(threads.get(0).replies()).isEmpty();
        assertThat(threads.get(0).replyTotal()).isZero();
    }

    // ------------------------------------------------------------------ 读接口

    @Test
    @DisplayName("列表按 id 正序翻页，total 只数「对此人可见」的一级评论：待审对作者自己可见且占总数")
    void listPagesRootsAndTotalsVisibleOnly() {
        seedComment(5100L, POST_ID, OTHER, null, null, Comment.STATUS_PUBLISHED);
        seedComment(5101L, POST_ID, OTHER, null, null, Comment.STATUS_PENDING);
        long third = seedComment(5102L, POST_ID, ME, null, null, Comment.STATUS_PENDING).getId();
        seedComment(5103L, POST_ID, OTHER, null, null, Comment.STATUS_REJECTED);

        // OTHER 视角：5100 已发布人人可见，5101 是他自己那条待审（对他可见、也占总数），
        // 5102 是 ME 的待审（对 OTHER 不可见），5103 被驳回（谁都不见）。
        PageResult<CommentThread> asOther = list(POST_ID, OTHER, null, 1, 2);
        assertThat(asOther.getTotal()).as("总数用的是查看者视角的可见数，不是全表数").isEqualTo(2);
        assertThat(asOther.getList()).extracting(thread -> thread.root().id())
                .containsExactly(5100L, 5101L);
        assertThat(asOther.getList().get(0).root().auditTip())
                .as("已发布的那条不许带待审提示").isNull();
        assertThat(asOther.getList().get(1).root().auditTip())
                .as("只有作者本人能看到这句提示").isEqualTo(PostQueryService.AUDIT_TIP);

        PageResult<CommentThread> asMe = list(POST_ID, ME, null, 1, 2);
        assertThat(asMe.getTotal()).as("我能看到 5100 与我自己那条待审 = 2 条").isEqualTo(2);
        assertThat(asMe.getList()).extracting(thread -> thread.root().id())
                .containsExactly(5100L, third);

        PageResult<CommentThread> secondPage = list(POST_ID, ME, null, 2, 1);
        assertThat(secondPage.getTotal()).as("第 2 页的 total 与第 1 页同一口径").isEqualTo(2);
        assertThat(secondPage.getList()).extracting(thread -> thread.root().id())
                .containsExactly(third);
    }

    @Test
    @DisplayName("展开子树：rootId 不存在 / 是条回复 / 属于别的帖子，三种都报同一句 400/10001")
    void expandRejectsEveryKindOfBadRootId() {
        long root = seedComment(5110L, POST_ID, OTHER, null, null, Comment.STATUS_PUBLISHED).getId();
        long nested = seedComment(5111L, POST_ID, ME, root, root, Comment.STATUS_PUBLISHED).getId();
        long elsewhere = seedComment(5112L, POST2_ID, OTHER, null, null, Comment.STATUS_PUBLISHED).getId();
        assertThat(messageOf(() -> list(POST_ID, ME, 999999L, 1, 20))).contains("不存在或已被删除");
        assertThat(messageOf(() -> list(POST_ID, ME, nested, 1, 20))).contains("不存在或已被删除");
        assertThat(messageOf(() -> list(POST_ID, ME, elsewhere, 1, 20))).contains("不存在或已被删除");
    }

    @Test
    @DisplayName("展开时帖子不可见仍是 404/30001：判帖在前，评论 id 合法与否轮不到说话")
    void expandChecksPostVisibilityFirst() {
        Post hidden = publicPost(790L, ME, "normal");
        hidden.setVisibility("private");
        store.posts.put(790L, hidden);
        long root = seedComment(5120L, 790L, ME, null, null, Comment.STATUS_PUBLISHED).getId();
        assertThat(codeOf(() -> list(790L, OTHER, root, 1, 20))).isEqualTo(ErrorCode.POST_NOT_FOUND);
    }

    @Test
    @DisplayName("带 rootId 时一次给完整棵子树（不截断到预览数），且按 (root_id, id) 正序")
    void expandReturnsWholeSubtreeInOrder() {
        long root = seedComment(5130L, POST_ID, OTHER, null, null, Comment.STATUS_PUBLISHED).getId();
        for (int i = 1; i <= 5; i++) {
            seedComment(5130L + i, POST_ID, ME, root, root, Comment.STATUS_PUBLISHED);
        }
        PageResult<CommentThread> page = list(POST_ID, ME, root, 1, 20);
        assertThat(page.getList()).hasSize(1);
        CommentThread thread = page.getList().get(0);
        assertThat(thread.replies()).hasSize(5);
        assertThat(thread.replyTotal()).isEqualTo(5);
        assertThat(thread.replies()).extracting(CommentItem::id)
                .containsExactly(5131L, 5132L, 5133L, 5134L, 5135L);
    }

    @Test
    @DisplayName("万楼层保护：单次展开封顶 500 条回复，超出的部分既不返回也不报错")
    void expandCapsSubtreeSize() {
        long root = seedComment(5200L, POST_ID, OTHER, null, null, Comment.STATUS_PUBLISHED).getId();
        for (int i = 1; i <= CommentService.MAX_SUBTREE_REPLIES + 3; i++) {
            seedComment(5200L + i, POST_ID, ME, root, root, Comment.STATUS_PUBLISHED);
        }
        CommentThread thread = list(POST_ID, ME, root, 1, 20).getList().get(0);
        assertThat(thread.replies()).hasSize(CommentService.MAX_SUBTREE_REPLIES);
    }

    @Test
    @DisplayName("展开时别人的待审回复不会漏进来，自己的待审与已发布的会（同一次查询里两套可见性）")
    void expandAppliesSameVisibilityFilter() {
        long root = seedComment(5250L, POST_ID, OTHER, null, null, Comment.STATUS_PUBLISHED).getId();
        seedComment(5251L, POST_ID, AUTHOR_ID, root, root, Comment.STATUS_PENDING);
        long mine = seedComment(5252L, POST_ID, ME, root, root, Comment.STATUS_PENDING).getId();
        seedComment(5253L, POST_ID, OTHER, root, root, Comment.STATUS_PUBLISHED);
        CommentThread thread = list(POST_ID, ME, root, 1, 20).getList().get(0);
        assertThat(thread.replies()).extracting(CommentItem::id).containsExactly(mine, 5253L);
    }

    // ------------------------------------------------------------------ 冗余列不变式

    @Test
    @DisplayName("不变式：每次写入后 post.comment_cnt 恒等于「已发布且未删除」的评论行数")
    void commentCounterAlwaysEqualsPublishedTruth() {
        comment(ME, POST_ID, "正常的");
        comment(ME, POST_ID, "正常的二");
        comment(ME, POST_ID, "卖枪支弹药的");
        comment(ME, POST_ID, "你就是个傻逼");
        Comment pending = lastRow();
        assertThat(store.posts.get(POST_ID).getCommentCnt())
                .as("两条公开 + 一条驳回 + 一条待审 = 2").isEqualTo(2);
        assertThat(store.countPublishedComments(POST_ID)).isEqualTo(2);

        // 人工放行那条待审后重算，数字必须跟着真相走（这里直接改 fake 里的行，模拟人审接口）
        pending.setStatus(Comment.STATUS_PUBLISHED);
        store.refreshPostCommentCnt(POST_ID);
        assertThat(store.posts.get(POST_ID).getCommentCnt()).isEqualTo(3);
    }

    @Test
    @DisplayName("软删的评论不进真相计数：refreshPostCommentCnt 重算时必须把 deleted=1 滤掉")
    void softDeletedRowsAreExcludedFromCounter() {
        comment(ME, POST_ID, "一楼");
        comment(ME, POST_ID, "二楼");
        lastRow().setDeleted(1); // fake 与 comments 映射里是同一个对象，改它就等于改那一行
        store.refreshPostCommentCnt(POST_ID);
        assertThat(store.posts.get(POST_ID).getCommentCnt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 测试专用的小工具

    private static Comment commentRow(long id, long userId, String status) {
        Comment row = new Comment();
        row.setId(id);
        row.setPostId(POST_ID);
        row.setUserId(userId);
        row.setContent("内容" + id);
        row.setStatus(status);
        row.setIsAnonymous(0);
        row.setLikeCnt(0);
        row.setDeleted(0);
        row.setCreatedAt(DAY);
        return row;
    }

    private CommentService.CommentContext contextFor(Comment row) {
        return contextForAll(List.of(row));
    }

    /** 用 fake 里现成的作者行与马甲行搭一个上下文：让组装规则能在不碰任何 mock 的情况下被断言。 */
    private CommentService.CommentContext contextForAll(List<Comment> rows) {
        Map<Long, User> users = new LinkedHashMap<>(store.users);
        Map<Long, AnonymousAlias> aliases = new LinkedHashMap<>(store.aliases());
        Map<Long, Comment> index = CommentService.indexById(rows);
        Post post = store.posts.get(POST_ID);
        long ownerId = post == null || post.getUserId() == null ? 0L : post.getUserId();
        return new CommentService.CommentContext(users, aliases, index, ME, ownerId);
    }

    private CommentItem view(Comment row) {
        return CommentService.toItem(row, contextForAll(new ArrayList<>(store.comments.values())));
    }

    // ------------------------------------------------------------------ 内存 fake

    /**
     * 存储端口的内存实现。
     *
     * <p><b>它唯一的价值是「像不像本体」</b>：可见性判据直接复用
     * {@link CommentService#isVisibleTo}（与适配器里那个 OR 条件同一份逻辑），
     * 计数按 DDL 的 {@code NOT NULL DEFAULT 0} 初始化，自增 id 由 insert 回填。
     * 至于 {@code post_id} 外键、{@code status} 的 ENUM、{@code uk} 索引，这里只实现
     * 「服务层真的依赖的那几条」——替身多实现一条，测试就多一份假绿的风险。</p>
     */
    private final class FakeStore implements CommentService.CommentStore {

        private final Map<Long, Post> posts = new LinkedHashMap<>();
        private final Map<Long, User> users = new LinkedHashMap<>();
        private final Map<Long, Comment> comments = new LinkedHashMap<>();
        private final List<AlertTicket> tickets = new ArrayList<>();
        private final List<Long> refreshCalls = new ArrayList<>();
        private final InMemoryAliasRepository aliasRepository;

        /** 模拟自增主键：从 9000 起，避开所有手塞的种子 id。 */
        private long nextId = 9000L;
        private int insertCalls;
        private int findCommentCalls;

        private FakeStore(InMemoryAliasRepository aliasRepository) {
            this.aliasRepository = aliasRepository;
        }

        private Map<Long, AnonymousAlias> aliases() {
            return aliasRepository.byId();
        }

        private boolean samePost(Comment row, long postId) {
            return row.getPostId() != null && row.getPostId().longValue() == postId;
        }

        private boolean live(Comment row) {
            return row.getDeleted() == null || row.getDeleted() == 0;
        }

        /** 此人对这帖「能看见」的行：软删与不可见两层都在这里过，服务侧才不会出现两套口径。 */
        private List<Comment> visible(long postId, long viewerId) {
            return comments.values().stream()
                    .filter(row -> live(row) && samePost(row, postId))
                    .filter(row -> CommentService.isVisibleTo(row, viewerId))
                    .sorted(Comparator.comparingLong(Comment::getId))
                    .collect(Collectors.toList());
        }

        @Override
        public Post findPost(long postId) {
            return posts.get(postId);
        }

        @Override
        public User findUser(long userId) {
            return users.get(userId);
        }

        @Override
        public Comment findComment(long commentId) {
            findCommentCalls++;
            Comment row = comments.get(commentId);
            return row == null || !live(row) ? null : row;
        }

        @Override
        public void insertComment(Comment comment) {
            insertCalls++;
            comment.setId(++nextId);
            if (comment.getLikeCnt() == null) {
                comment.setLikeCnt(0);
            }
            if (comment.getDeleted() == null) {
                comment.setDeleted(0);
            }
            comments.put(comment.getId(), comment);
        }

        @Override
        public void refreshPostCommentCnt(long postId) {
            refreshCalls.add(postId);
            Post post = posts.get(postId);
            if (post != null) {
                post.setCommentCnt((int) countPublishedComments(postId));
            }
        }

        @Override
        public long countPublishedComments(long postId) {
            return comments.values().stream()
                    .filter(row -> live(row) && samePost(row, postId))
                    .filter(row -> Comment.STATUS_PUBLISHED.equals(row.getStatus()))
                    .count();
        }

        @Override
        public List<Comment> pageVisibleRoots(long postId, long viewerId, long offset, int limit) {
            return visible(postId, viewerId).stream()
                    .filter(row -> row.getParentId() == null)
                    .skip(offset)
                    .limit(limit)
                    .collect(Collectors.toList());
        }

        @Override
        public long countVisibleRoots(long postId, long viewerId) {
            return visible(postId, viewerId).stream().filter(row -> row.getParentId() == null).count();
        }

        @Override
        public List<Comment> listVisibleReplies(long postId, Collection<Long> rootIds, long viewerId) {
            return visible(postId, viewerId).stream()
                    .filter(row -> row.getParentId() != null && rootIds.contains(row.getRootId()))
                    .sorted(Comparator.comparingLong(Comment::getRootId).thenComparingLong(Comment::getId))
                    .collect(Collectors.toList());
        }

        @Override
        public Map<Long, User> mapUsers(Collection<Long> userIds) {
            Map<Long, User> found = new LinkedHashMap<>();
            for (Long id : userIds) {
                // @TableLogic 的真语义：注销账号在这里自然查不到，服务侧兜底「已注销的屿民」
                User user = users.get(id);
                if (user != null && !"DELETED".equals(user.getStatus())) {
                    found.put(id, user);
                }
            }
            return found;
        }

        @Override
        public Map<Long, AnonymousAlias> mapAliases(Collection<Long> aliasIds) {
            Map<Long, AnonymousAlias> all = aliases();
            Map<Long, AnonymousAlias> found = new LinkedHashMap<>();
            for (Long id : aliasIds) {
                AnonymousAlias alias = all.get(id);
                if (alias != null) {
                    found.put(id, alias);
                }
            }
            return found;
        }

        @Override
        public void insertAlertTicket(AlertTicket ticket) {
            tickets.add(ticket);
        }
    }

    /** 马甲存储端口的内存实现：与 T3.4 那份同一语义（查不到返回 null、列表按 id 升序、撞键返回对手行）。 */
    private static final class InMemoryAliasRepository implements AnonymousAliasRepository {

        private final List<AnonymousAlias> rows = new ArrayList<>();
        private long nextId = 300L;

        private Map<Long, AnonymousAlias> byId() {
            Map<Long, AnonymousAlias> map = new LinkedHashMap<>();
            for (AnonymousAlias row : rows) {
                map.put(row.getId(), row);
            }
            return map;
        }

        @Override
        public AnonymousAlias findByUserAndScene(long userId, String scene) {
            for (AnonymousAlias row : rows) {
                if (row.getUserId() != null && row.getUserId().longValue() == userId
                        && Objects.equals(row.getScene(), scene)) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public List<AnonymousAlias> listByUser(long userId) {
            return rows.stream()
                    .filter(row -> row.getUserId() != null && row.getUserId().longValue() == userId)
                    .sorted(Comparator.comparingLong(AnonymousAlias::getId))
                    .collect(Collectors.toList());
        }

        @Override
        public AnonymousAlias insertIfAbsent(long userId, String scene, String aliasName) {
            AnonymousAlias existing = findByUserAndScene(userId, scene);
            if (existing != null) {
                return existing;
            }
            AnonymousAlias row = new AnonymousAlias();
            row.setId(++nextId);
            row.setUserId(userId);
            row.setScene(scene);
            row.setAliasName(aliasName);
            rows.add(row);
            return row;
        }
    }
}

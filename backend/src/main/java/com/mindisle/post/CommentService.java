package com.mindisle.post;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.mindisle.audit.SensitiveWordEngine;
import com.mindisle.audit.SensitiveWordEngine.CheckResult;
import com.mindisle.cache.CacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.AlertTicket;
import com.mindisle.entity.AnonymousAlias;
import com.mindisle.entity.Comment;
import com.mindisle.entity.Post;
import com.mindisle.entity.User;
import com.mindisle.notify.NotifyService;
import com.mindisle.post.dto.CommentCreateRequest;
import com.mindisle.post.dto.CommentCreateView;
import com.mindisle.post.dto.CommentItem;
import com.mindisle.post.dto.CommentThread;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评论与楼中楼（任务 3.7 · 需求 FR4.4、FR7.3、BR4、BR6 · 手册 §6.1 行 3.7）。
 *
 * <p><b>三件事照抄发帖的既有口径，一处都不自创</b>：
 * ① <b>可见性</b>——帖子不可见时评论接口一律 404/30001（判据仍是
 * {@link PostQueryService#visibleTo} 那唯一的一份），报 403 等于替别人承认「这条存在」；
 * ② <b>机审</b>——手册 §6.1 行 3.7 明写「评论也过 3.2」，所以 {@link #comment} 走的是与发帖
 * 同一条 DFA + {@link CrisisGrader} + {@link PostService#decide} 分支优先级
 * （拦截 &gt; 危机放行 &gt; 转人审 &gt; 直发）；那两条判据（{@code decide}、{@code maskNonRisk}）
 * 是包级可见，就是为了在这里复用而不是抄第二份；
 * ③ <b>资格</b>——BR6 的「禁言不能说话、封禁全不可」走
 * {@link PostingQuotaService#assertCanComment}（它内部先过 {@code assertStatusAllowsWrite}），
 * 全站只有这一处 switch。</p>
 *
 * <p><b>两级展示、无限层级存储</b>：{@code parent_id} 存「被回复的那一条」，
 * {@code root_id} 存「所属一级评论」，所以一次 {@code root_id} 查询就能捞全整棵子树，
 * 而页面上永远只有两层（回复之间靠 replyToName 说「回复了谁」）。
 * 为什么不在写入时就压平 parent_id = root_id：压平会丢掉「谁回复了谁」这条边，
 * 而它既是楼中楼 @ 文案的唯一来源，也是 FR9.1「有人回复了你」通知的收件人依据
 * ——存的时候省一列，将来就得回刷全部历史评论才能补回来。</p>
 *
 * <p><b>评论的匿名与帖子的匿名互相独立</b>：树洞帖下面实名评论是允许的（产品要的正是
 * 「别人敢站出来抱抱他」），反过来普通帖也能匿名评论。判据只看这条评论自己的
 * {@code is_anonymous/alias_id}，绝不继承 {@code post.is_anonymous}。
 * 唯一必须继承的是<b>不可解匿</b>：回复一条匿名评论时，@ 的名字取<b>那条马甲的名字</b>，
 * 而不是它的真实作者昵称，否则「回复」这个动作本身就成了 FR1.4 要挡住的解匿通道。</p>
 *
 * <p><b>待审评论只有作者本人看得到</b>，且<b>不进 {@code post.comment_cnt}</b>：
 * 与帖子的「作者额外可见自己的待审帖 + 待审提示」同一口径，卡片数字与点进去的条数必须自洽。</p>
 *
 * <p><b>REJECTED 与转人审都照样消耗配额、也照样建危机工单</b>：理由与发帖一致
 * （见 {@link PostService#publish} 第 9 步）——拦的是内容不是人，
 * 也不能替攻击者免费开放「无限试探拦截边界」的通道。</p>
 *
 * <p><b>评论与回复会写站内通知</b>（任务 T3.11-b · 需求 FR9.1）：判据全部集中在
 * {@link #notifyParties} 一处——只有已发布的评论才发、展示名只用 {@code CommentItem.authorName}
 * （匿名评论必须显示马甲名）、被回复者是楼主时只发更具体的那一条。写通知与评论落库同一个事务，
 * 理由与不吞异常的取舍见 {@link NotifyService} 类注释。</p>
 */
@Service
public class CommentService {

    /** 匿名评论统一用全域马甲 ALL：一个人不该因为换了个场景就有第二张脸（BR1）。 */
    static final String ANON_SCENE = "ALL";

    /** 列表里每棵子树预览的回复条数；剩下的靠 rootId 单独展开。 */
    static final int REPLY_PREVIEW = 3;

    /** 单次展开一棵子树最多带回的回复条数（防「万楼层」把一次请求变成一次全表搬运）。 */
    static final int MAX_SUBTREE_REPLIES = 500;

    /** 父评论已被删除/驳回时的 @ 兜底文案。不能用「已注销的屿民」：那是账号状态，不是内容状态。 */
    static final String HIDDEN_PARENT = "已隐藏的评论";

    private static final String PUBLISHED = Comment.STATUS_PUBLISHED;
    private static final String PENDING = Comment.STATUS_PENDING;
    private static final String REJECTED = Comment.STATUS_REJECTED;

    /**
     * 存储端口（与 {@link PostInteractionService.InteractionStore} 同一套路）：
     * 本类只管规则，落库细节全推到接口外面，换来「父级越帖」「匿名解匿」「两级压平」
     * 这些只能靠构造边界场景才能验证的规则，能在单测里逐条钉住。
     * 真环境适配器见 {@link CommentStoreAdapter}。
     */
    public interface CommentStore {

        /** 帖子原始行（含 status/visibility/auto_destroy_at），可见性判断留在服务层。 */
        Post findPost(long postId);

        /** 评论者账号行；查不到即「不存在或已注销」。 */
        User findUser(long userId);

        /** 评论原始行（<b>不</b>过可见性），父级校验与子树展开用它。 */
        Comment findComment(long commentId);

        /** 落一条评论；实现方负责回填自增 id。 */
        void insertComment(Comment comment);

        /** 把 post.comment_cnt 刷成真相（重算 UPDATE，不做 INCR）。 */
        void refreshPostCommentCnt(long postId);

        /** 真相条数：已发布且未删除的评论（含楼中楼）。 */
        long countPublishedComments(long postId);

        /** 该帖对<b>此人</b>可见的一级评论，按 id 正序翻页。 */
        List<Comment> pageVisibleRoots(long postId, long viewerId, long offset, int limit);

        /** 该帖对此人可见的一级评论总数（给 PageResult.total）。 */
        long countVisibleRoots(long postId, long viewerId);

        /** 若干棵子树里对该人可见的<b>全部</b>回复，按 (root_id, id) 正序。 */
        List<Comment> listVisibleReplies(long postId, Collection<Long> rootIds, long viewerId);

        /** 批量取作者行，返回 id → User；注销账号自然不在里面。 */
        Map<Long, User> mapUsers(Collection<Long> userIds);

        /** 批量取马甲行，返回 id → AnonymousAlias。 */
        Map<Long, AnonymousAlias> mapAliases(Collection<Long> aliasIds);

        /** L2/L3 危机工单落库（FR10.5）。 */
        void insertAlertTicket(AlertTicket ticket);
    }

    private final CommentStore store;
    private final PostingQuotaService quotaService;
    private final SensitiveWordEngine engine;
    private final CacheService cacheService;
    private final AnonymousAliasService aliasService;
    private final MindisleProperties properties;
    private final NotifyService notifyService;

    public CommentService(CommentStore store, PostingQuotaService quotaService,
                          SensitiveWordEngine engine, CacheService cacheService,
                          AnonymousAliasService aliasService, MindisleProperties properties,
                          NotifyService notifyService) {
        this.store = store;
        this.quotaService = quotaService;
        this.engine = engine;
        this.cacheService = cacheService;
        this.aliasService = aliasService;
        this.properties = properties;
        this.notifyService = notifyService;
    }

    // ================================================================ 写

    /**
     * 发一条评论或回复。
     *
     * <p><b>校验顺序是设计，不是随手写的</b>：帖可见 → 人有账号 → 有没有说话资格与额度
     * → 内容合不合法 → 父级对不对 → 机审 → 落库。额度检查排在内容校验之前，
     * 是让「超字数」这种纯客户端错误不必先读一次缓存；机审放在最后，
     * 因为它最贵（DFA 全文扫描 + 可能的词库热重载），前面任何一道闸拦住一条就省一次扫描。</p>
     *
     * @param now 时间基准，由调用方传入；本类内部绝不读系统时钟
     */
    @Transactional
    public CommentCreateView comment(long userId, long postId, CommentCreateRequest req, LocalDateTime now) {
        Post post = store.findPost(postId);
        if (post == null || !PostQueryService.visibleTo(post, userId, now)) {
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }
        User author = store.findUser(userId);
        if (author == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        // BR6（禁言不许说话、封禁全不可）+ BR4（单用户单帖 ≤20 条/天）一次过完
        quotaService.assertCanComment(author, postId, now);

        String content = normalizeContent(req == null ? null : req.content());
        Long parentId = req == null ? null : req.parentId();
        Comment parent = findParent(postId, parentId);
        boolean anonymous = req != null && Boolean.TRUE.equals(req.anonymous());
        AnonymousAlias alias = anonymous ? aliasService.resolve(userId, ANON_SCENE) : null;

        engine.refreshIfStale(cacheService, properties.getAudit().getDictVersionKey());
        CheckResult result = engine.check(content, "user");
        String level = CrisisGrader.levelOf(result);
        PostService.MachineDecision decision = PostService.decide(result, level, CrisisGrader.needsTicket(level));

        String storedContent = content;
        boolean contactMasked = false;
        if (anonymous) {
            // 与匿名帖同一口径：非危机命中（手机号、微信号）落库前就遮掉，危机原文保留给管理员
            String masked = PostService.maskNonRisk(content, result);
            contactMasked = !masked.equals(content);
            storedContent = masked;
        }

        Placement placement = placementOf(parent);
        Comment row = new Comment();
        row.setPostId(postId);
        row.setUserId(userId);
        row.setParentId(placement.parentId());
        row.setRootId(placement.rootId());
        row.setReplyToUserId(placement.replyToUserId());
        row.setContent(storedContent);
        row.setIsAnonymous(anonymous ? 1 : 0);
        row.setAliasId(alias == null ? null : alias.getId());
        row.setStatus(statusFor(decision.status()));
        row.setLikeCnt(0);
        row.setDeleted(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        store.insertComment(row);

        // 配额：REJECTED 也消耗（口径见类注释）
        quotaService.recordCommentCreated(author, postId, now);

        if (decision.care()) {
            store.insertAlertTicket(newTicket(userId, post, row.getId(), content, result, decision, now));
        }
        // 冗余列一律「按真相表重算后整体赋值」，与 T3.6 的 like_cnt 同一口径：漂移在结构上不可能发生
        store.refreshPostCommentCnt(postId);

        String tip = decision.tip();
        if (contactMasked) {
            tip = tip == null || tip.isEmpty() ? PostService.tipContactMasked() : tip + PostService.tipContactMasked();
        }
        // 求助卡片只在「这次真的命中危机」时随评论回执给出；帖子自己是不是求助帖由详情接口负责
        String hotline = decision.care() ? properties.getCrisis().getHotline() : null;
        CommentItem item = toItem(row, contextOfReply(row, author, alias, parent, userId, post.getUserId()));
        // 通知放在回执组装之后、返回之前：同一个事务，且 authorName 已经在 item 里算好了，
        // 复用它而不是再算一次，是「匿名评论不许漏真名」这条规则的结构性保证。
        notifyParties(post, row, item, placement, userId, postId, storedContent);
        return new CommentCreateView(item, hotline, tip);
    }

    /**
     * 评论落库之后的两条通知（任务 T3.11-b · 需求 FR9.1 · 手册 §6.1 行 3.11）。
     *
     * <p><b>只有已发布的评论才发通知</b>：待审评论仅作者自己可见（见类注释第 5 段），
     * 这时候给楼主推一条「有人评论了你」，点进去却什么都没有——那等于把没过审的内容广播出去。
     * REJECTED 更不发。</p>
     *
     * <p><b>展示名取 {@code item.authorName()}，绝不取 {@code PostService.displayNameOf(author)}</b>：
     * 匿名评论要显示马甲名（FR1.4），拿账号行算名字就等于把真名写进通知正文并永久留在库里。
     * {@code item} 在调用点已经算好了，复用它既省一次计算，也让这条红线变成结构上走不通的路。</p>
     *
     * <p><b>被回复者恰好是楼主时只发「回复了你的评论」</b>：那条更具体，而「评论了你的帖子」
     * 是它的子集，同时发两条等于让同一件事占两个红点。</p>
     *
     * <p>与点赞一样：同一个事务、异常不吞，取舍见 {@link NotifyService} 类注释第 3 条。</p>
     */
    private void notifyParties(Post post, Comment row, CommentItem item, Placement placement,
                               long commenterId, long postId, String content) {
        if (!PUBLISHED.equals(row.getStatus())) {
            return;
        }
        long ownerId = post.getUserId() == null ? 0L : post.getUserId();
        Long replyToId = placement == null ? null : placement.replyToUserId();
        // 回复自己那条评论（replyToId == commenterId）不算「有人回复了你」，同自赞不发通知一个道理
        boolean replied = replyToId != null && replyToId > 0L && replyToId != commenterId;
        if (replied) {
            notifyService.notifyReply(replyToId, item.authorName(), postId, content);
        }
        if (ownerId > 0L && ownerId != commenterId && !(replied && replyToId == ownerId)) {
            notifyService.notifyComment(ownerId, item.authorName(), postId, content);
        }
    }

    // ================================================================ 读

    /**
     * 评论列表。
     *
     * <p>不带 {@code rootId}：按一级评论正序翻页，每棵子树预览前 {@value #REPLY_PREVIEW} 条回复，
     * 并带回该子树的回复总数（前端据此显示「查看 N 条回复」）。
     * 带 {@code rootId}：整棵子树一次给完（上限 {@value #MAX_SUBTREE_REPLIES} 条）。</p>
     *
     * <p><b>为什么回复不分页</b>：楼中楼的分页要跨页保持顺序稳定，而一条回复被删会让后面所有页
     * 整体错位；本阶段的代价是「万楼层」的单次传输，已经用 {@value #MAX_SUBTREE_REPLIES} 条封顶。
     * 这条边界同步写进手册 §14。</p>
     */
    public PageResult<CommentThread> list(long postId, long viewerId, Long rootId, PageQuery query,
                                          LocalDateTime now) {
        Post post = store.findPost(postId);
        if (post == null || !PostQueryService.visibleTo(post, viewerId, now)) {
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }
        PageQuery page = (query == null ? new PageQuery() : query).normalize();
        if (rootId != null) {
            Comment root = store.findComment(rootId);
            if (root == null || root.getParentId() != null || !Objects.equals(root.getPostId(), postId)
                    || !isVisibleTo(root, viewerId)) {
                // 与父级校验同一口径：400 而不是 404。帖子的存在性在这次请求里已经通过了，
                // 评论 id 不该再对外区分「不存在」和「被删了」——那与用 403 枚举私密帖是同一条通道。
                throw new BizException(ErrorCode.PARAM_INVALID, "要展开的评论不存在或已被删除");
            }
            List<Comment> replies = cap(store.listVisibleReplies(postId, List.of(rootId), viewerId));
            CommentContext ctx = buildContext(concat(List.of(root), replies), viewerId, post.getUserId());
            List<CommentItem> items = new ArrayList<>();
            for (Comment reply : replies) {
                items.add(toItem(reply, ctx));
            }
            return PageResult.of(List.of(new CommentThread(toItem(root, ctx), items, items.size())), 1L, page);
        }
        List<Comment> roots = store.pageVisibleRoots(postId, viewerId, page.offset(), page.getSize());
        long total = store.countVisibleRoots(postId, viewerId);
        List<Comment> replies = roots.isEmpty() ? List.of()
                : store.listVisibleReplies(postId, idsOf(roots), viewerId);
        return PageResult.of(assemble(roots, replies,
                buildContext(concat(roots, replies), viewerId, post.getUserId()), REPLY_PREVIEW), total, page);
    }

    /**
     * 把「平铺的一级评论 + 平铺的回复」组装成树。
     *
     * <p>包级可见 + 纯函数：整棵树的形状（谁挂在谁下面、回复总数、预览截断、匿名不回 id）
     * 都能在不连库、不注入任何替身的情况下逐条断言（上下文由调用方建好传进来）。
     * {@code preview} 大于子树实际条数即等于不截断。</p>
     */
    static List<CommentThread> assemble(List<Comment> roots, List<Comment> replies,
                                          CommentContext ctx, int preview) {
        Map<Long, List<Comment>> byRoot = new LinkedHashMap<>();
        for (Comment root : roots) {
            byRoot.put(root.getId(), new ArrayList<>());
        }
        for (Comment reply : replies) {
            List<Comment> bucket = byRoot.get(reply.getRootId());
            if (bucket != null) {
                bucket.add(reply);
            }
        }
        List<CommentThread> threads = new ArrayList<>();
        for (Comment root : roots) {
            List<Comment> bucket = byRoot.getOrDefault(root.getId(), List.of());
            int shownCount = Math.min(bucket.size(), preview);
            List<CommentItem> shown = new ArrayList<>(shownCount);
            for (int i = 0; i < shownCount; i++) {
                shown.add(toItem(bucket.get(i), ctx));
            }
            threads.add(new CommentThread(toItem(root, ctx), shown, bucket.size()));
        }
        return threads;
    }

    // ================================================================ 出参组装

    /**
     * 一次渲染所需的全部关联数据。
     *
     * <p><b>只有批量查询的结果，没有任何单条查询</b>：一屏 20 条一级评论 + 60 条回复，
     * 逐条查作者与马甲就是 80 次往返，那是列表接口最常见的 N+1 事故形状
     * （与 {@link PostQueryService.Relations} 同一教训）。所以本记录一旦建好，
     * 下面的 {@link #assemble} 与 {@link #toItem} 就是<b>纯函数</b>——
     * 树的形状、匿名不回 id、@ 名字取马甲这些规则，全部能在不连库、不注入任何 mock 的
     * 单测里逐条断言。</p>
     *
     * <p><b>{@code viewerId} 与 {@code postOwnerId} 必须分开</b>：前者决定「待审提示给不给」，
     * 后者决定「这条是不是作者自评」（BR4 埋点要跳过）。写成同一个字段就会退化成
     * 「谁刷到别人的待审评论，谁看到一句与己无关的审核提示」——帖子列表当年就是这么错的。</p>
     */
    record CommentContext(Map<Long, User> users, Map<Long, AnonymousAlias> aliases,
                          Map<Long, Comment> rows, long viewerId, long postOwnerId) {
    }

    /** 批量取回这一屏要用的作者与马甲，建成上下文。 */
    private CommentContext buildContext(List<Comment> rows, long viewerId, Long postOwnerId) {
        Set<Long> userIds = new LinkedHashSet<>();
        Set<Long> aliasIds = new LinkedHashSet<>();
        for (Comment row : rows) {
            if (!isAnonymous(row) && row.getUserId() != null) {
                userIds.add(row.getUserId());
            }
            if (row.getAliasId() != null) {
                aliasIds.add(row.getAliasId());
            }
        }
        return new CommentContext(userIds.isEmpty() ? Map.of() : store.mapUsers(userIds),
                aliasIds.isEmpty() ? Map.of() : store.mapAliases(aliasIds),
                indexById(rows), viewerId, postOwnerId == null ? 0L : postOwnerId);
    }

    /**
     * 单条评论回执用的上下文：作者行与马甲行都还在手上，不必回头再查一次库；
     * 但<b>父级那一行必须一起给</b>——{@link #toItem} 的 replyToName 是拿父级算的，
     * 上下文里少了父级，前端刚发出去的那条回复就会显示成「回复 已隐藏的评论」，
     * 等下一次刷新才变对。多出来的只有父级作者/马甲这一批查询，代价是一次回复一次往返。
     */
    private CommentContext contextOfReply(Comment row, User author, AnonymousAlias alias,
                                          Comment parent, long viewerId, Long postOwnerId) {
        Map<Long, User> users = new LinkedHashMap<>();
        Map<Long, AnonymousAlias> aliases = new LinkedHashMap<>();
        if (author != null && author.getId() != null) {
            users.put(author.getId(), author);
        }
        if (alias != null && alias.getId() != null) {
            aliases.put(alias.getId(), alias);
        }
        List<Comment> rows = new ArrayList<>();
        rows.add(row);
        if (parent != null) {
            rows.add(parent);
            if (isAnonymous(parent)) {
                if (parent.getAliasId() != null) {
                    aliases.putAll(store.mapAliases(List.of(parent.getAliasId())));
                }
            } else if (parent.getUserId() != null && !users.containsKey(parent.getUserId())) {
                User parentAuthor = store.findUser(parent.getUserId());
                if (parentAuthor != null && parentAuthor.getId() != null) {
                    users.put(parentAuthor.getId(), parentAuthor);
                }
            }
        }
        return new CommentContext(users, aliases, indexById(rows), viewerId,
                postOwnerId == null ? 0L : postOwnerId);
    }

    static Map<Long, Comment> indexById(List<Comment> rows) {
        Map<Long, Comment> byId = new LinkedHashMap<>();
        for (Comment row : rows) {
            if (row.getId() != null) {
                byId.put(row.getId(), row);
            }
        }
        return byId;
    }

    /** 一条评论是不是匿名（两个条件都认，口径与 {@link PostQueryService#isAnonymous} 一致）。 */
    static boolean isAnonymous(Comment row) {
        return (row.getIsAnonymous() != null && row.getIsAnonymous() == 1) || row.getAliasId() != null;
    }

    /** 展示名：匿名走马甲名（马甲行被删则兜底），实名走昵称，作者已注销走「已注销的屿民」。 */
    static String displayNameOf(Comment row, CommentContext ctx) {
        if (isAnonymous(row)) {
            AnonymousAlias alias = row.getAliasId() == null ? null : ctx.aliases().get(row.getAliasId());
            return alias == null || alias.getAliasName() == null
                    ? PostQueryService.ANONYMOUS_FALLBACK : alias.getAliasName();
        }
        User user = row.getUserId() == null ? null : ctx.users().get(row.getUserId());
        return user == null ? PostQueryService.DELETED_AUTHOR : PostService.displayNameOf(user);
    }

    /**
     * 组装单条评论。
     *
     * <p>匿名时 {@code authorId} 恒为 null（FR1.4：留在响应体里就等于能被反查，
     * 前端「不显示」不算防护）；{@code replyToName} 取<b>父评论的展示名</b>，
     * 父评论匿名时就是它的马甲名——这是本方法里最容易被写错、也最不能写错的一行。</p>
     */
    static CommentItem toItem(Comment row, CommentContext ctx) {
        String replyToName = null;
        if (row.getParentId() != null) {
            Comment parent = ctx.rows().get(row.getParentId());
            replyToName = parent == null ? HIDDEN_PARENT : displayNameOf(parent, ctx);
        }
        return new CommentItem(row.getId(), row.getPostId(), row.getParentId(), row.getRootId(),
                row.getContent(), isAnonymous(row) ? null : row.getUserId(), displayNameOf(row, ctx),
                isAnonymous(row), replyToName, intValue(row.getLikeCnt()), row.getStatus(),
                auditTipOf(row, ctx.viewerId()), row.getCreatedAt(),
                row.getUserId() != null && row.getUserId().longValue() == ctx.postOwnerId());
    }

    /** 待审提示：只有作者本人 + 仍在待审才给（与帖子的 auditTipOf 同一语义）。 */
    static String auditTipOf(Comment row, long viewerId) {
        return isPending(row) && row.getUserId() != null && row.getUserId().longValue() == viewerId
                ? PostQueryService.AUDIT_TIP : null;
    }

    private static boolean isPending(Comment row) {
        return PENDING.equals(row.getStatus());
    }

    /** 这条评论对该查看者可见吗：已发布人人可见，待审只有作者可见，驳回与删除谁都不见。 */
    static boolean isVisibleTo(Comment row, long viewerId) {
        if (row == null) {
            return false;
        }
        if (PUBLISHED.equals(row.getStatus())) {
            return true;
        }
        return isPending(row) && row.getUserId() != null && row.getUserId().longValue() == viewerId;
    }



    /** 换行归一 + 去首尾空白 + 空内容拒绝 + 码点长度上限（FR4.4 ≤1000 字）。 */
    String normalizeContent(String raw) {
        String value = PostService.normalizeNewlines(raw).trim();
        if (value.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "评论内容不能为空");
        }
        int max = properties.getPost().getMaxCommentChars();
        int length = value.codePointCount(0, value.length());
        if (length > max) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "评论最多 " + max + " 字（标点和 emoji 都算一个字），现在有 " + length + " 字");
        }
        return value;
    }

    /** 父级定位结果：三个 id 一次算清，避免调用点各拼一遍拼出三种组合。 */
    record Placement(Long parentId, Long rootId, Long replyToUserId) {

        static Placement topLevel() {
            return new Placement(null, null, null);
        }
    }

    /**
     * 取父评论并校验它属于本帖（规则 ①）。
     *
     * <p>父级越帖会让整棵子树在 {@code WHERE post_id = ?} 的查询里凭空蒸发，
     * 是最难查的一类脏数据，所以「越帖」与「不存在」报同一句 400——不借这条通道
     * 泄露「那个 id 其实存在于另一帖」。</p>
     *
     * <p><b>父级必须已发布，而不是「对当前操作者可见」</b>。这两条判据在这里不等价：
     * 待审评论对作者本人是可见的，如果照 {@link #isVisibleTo} 放行，作者就能回复自己那条还没公开的评论，
     * 于是公开出去的回复挂在一棵别人看不见的父级下面——列表页会把它整棵丢掉（{@link #assemble} 只认
     * 本页出现过的 root_id），变成谁也捞不出来的孤儿子树。所以这里要的是更严的那一条。</p>
     *
     * <p>顺手记一笔踩坑：这条判据最初写成 {@code isVisibleTo(parent, parentId)}——参数个数一样、
     * 类型一样、编译一样过，比的却是「评论作者的 user_id 等不等于评论 id」，只在两个 id 恰好相等时
     * 才误放行，属于真机上最难复现的那类错。可见性判据一旦要「谁在看」这个维度，就必须显式命名入参，
     * 不能让两个 long 并排站在签名里。</p>
     */
    Comment findParent(long postId, Long parentId) {
        if (parentId == null || parentId <= 0L) {
            return null;
        }
        Comment parent = store.findComment(parentId);
        if (parent == null || parent.getId() == null || !Objects.equals(parent.getPostId(), postId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "要回复的评论不存在或已被删除");
        }
        if (!PUBLISHED.equals(parent.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "这条评论现在不能被回复");
        }
        return parent;
    }

    /**
     * 两级压平（规则 ③）：回复一级评论时 {@code root_id = 父 id}，
     * 回复楼中楼时 {@code root_id} 沿用父的 {@code root_id}，而 {@code parent_id} 永远指向真正被回复的那一条。
     *
     * <p>与 {@link #findParent} 分家的唯一理由：让「越帖」「不可见」「压平」三条规则能被分别断言。
     * 合并成一条长 if 的话，测试永远只能命中第一个分支，后两条等于没测。</p>
     */
    static Placement placementOf(Comment parent) {
        if (parent == null) {
            return Placement.topLevel();
        }
        Long rootId = parent.getParentId() == null ? parent.getId() : parent.getRootId();
        return new Placement(parent.getId(), rootId, parent.getUserId());
    }

    /**
     * 机审终态 → 评论状态。评论只有四态，没有「机审中/人审中」的分别：
     * {@code HUMAN_REVIEW} 与 {@code MACHINE_REVIEW} 都落 {@code PENDING}（未公开），
     * {@code PUBLISHED}/{@code REJECTED} 原样；<b>认不出来的状态一律按 PENDING</b>——
     * 多压一条待审远比误公开一条违规内容便宜，这一行是失败关闭。
     */
    static String statusFor(String postStatus) {
        if (PostService.STATUS_PUBLISHED.equals(postStatus)) {
            return PUBLISHED;
        }
        if (PostService.STATUS_REJECTED.equals(postStatus)) {
            return REJECTED;
        }
        return PENDING;
    }

    private static List<Comment> concat(List<Comment> a, List<Comment> b) {
        List<Comment> all = new ArrayList<>(a.size() + b.size());
        all.addAll(a);
        all.addAll(b);
        return all;
    }

    private static List<Long> idsOf(List<Comment> rows) {
        List<Long> ids = new ArrayList<>(rows.size());
        for (Comment row : rows) {
            if (row.getId() != null) {
                ids.add(row.getId());
            }
        }
        return ids;
    }

    private static List<Comment> cap(List<Comment> replies) {
        return replies.size() <= MAX_SUBTREE_REPLIES
                ? replies : new ArrayList<>(replies.subList(0, MAX_SUBTREE_REPLIES));
    }

    private static int intValue(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 评论触发的危机工单（FR10.5）。
     *
     * <p><b>已知妥协，写白不藏</b>：{@code alert_ticket.source_type} 的 ENUM 只有
     * chat/post/hole/pm 四个值（DDL 定稿在阶段 2），<b>没有 comment</b>。
     * 这里选择记成「来源帖子」并把评论 id 追加进证据文本，而不是干脆不建单——
     * 需求 §18.3 对自伤类内容要的是「人不能被推回沉默」：来源指针不精确是可以解释的瑕疵，
     * 漏掉一条 L3 预警是事故。真正的修法是给 ENUM 加值（阶段 6 的 T3.15 一并做），
     * 已同步写进手册 §14 与 dev-log。</p>
     *
     * <p>组单本身复用 {@link PostService#newTicket}，<b>不抄第二份</b>：SLA 算法、
     * 风险分基准值、触发词截断长度三处只要有一份漂了，工单定级就会和帖子通道不一致。
     * 评论 id 只能<b>追加</b>不能前缀：{@link CrisisGrader#evidence} 按命中偏移量切片，
     * 而偏移量是在原文上算出来的，改动原文会让证据切错位置。</p>
     */
    private AlertTicket newTicket(long userId, Post post, Long commentId, String checkText,
                                  CheckResult result, PostService.MachineDecision decision,
                                  LocalDateTime now) {
        AlertTicket ticket = PostService.newTicket(userId,
                PostService.TYPE_HOLE.equals(post.getType()) ? "hole" : "post", post.getId(),
                checkText, result, decision, properties.getCrisis(), now);
        if (commentId != null) {
            String evidence = ticket.getEvidenceText() == null ? "" : ticket.getEvidenceText();
            ticket.setEvidenceText(evidence + "（评论 id=" + commentId + "）");
        }
        return ticket;
    }

}

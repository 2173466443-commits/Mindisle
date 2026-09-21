package com.mindisle.post;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.AnonymousAlias;
import com.mindisle.entity.Post;
import com.mindisle.entity.PostImage;
import com.mindisle.entity.PostLike;
import com.mindisle.entity.PostTopic;
import com.mindisle.entity.Topic;
import com.mindisle.entity.User;
import com.mindisle.mapper.AnonymousAliasMapper;
import com.mindisle.mapper.PostImageMapper;
import com.mindisle.mapper.PostLikeMapper;
import com.mindisle.mapper.PostMapper;
import com.mindisle.mapper.PostTopicMapper;
import com.mindisle.mapper.TopicMapper;
import com.mindisle.mapper.UserMapper;
import com.mindisle.post.dto.PostDetailView;
import com.mindisle.post.dto.PostListItem;
import com.mindisle.post.dto.PostView;
import org.springframework.stereotype.Service;

/**
 * 帖子列表与详情（任务 3.5 · 手册 §6.1 行 3.5 · 需求 FR4.3、FR7.3、BR9）。
 *
 * <p><b>分页为什么用游标而不是页码</b>：{@code PageQuery} 自己的注释就写了这条分工 ——
 * 信息流用游标，后台才用页码。广场是「按发布时间倒序 + 一直在插新帖」的组合，
 * 页码分页在这种情况下必然跳页重复（第 1 页看过的内容下一页又出现一遍）。
 * 排序键取 {@code (published_at DESC, id DESC)}，与 {@code idx_status_pub} 完全同序，
 * 游标条件是「比锚点更靠后」的严格字典序比较，所以翻页既不重叠也不漏。</p>
 *
 * <b>可见性三条口径，都落在 {@link #visibleTo} 这一个函数里</b>：
 * <ol>
 *   <li>别人能看到的只有「已发布 + 公开」（FR7.3：{@code MACHINE_REVIEW}/{@code HUMAN_REVIEW}
 *       期间仅作者可见）；</li>
 *   <li>作者额外能看到<b>自己</b>的待审帖，出参带 {@code auditTip}，让「先发后审」在产品上是
 *       可感知的，而不是「我发了条帖，它凭空消失了」；</li>
 *   <li>{@code REJECTED / TAKEDOWN / DELETED / DRAFT} 谁都看不到（{@code REJECTED} 的
 *       申诉入口属任务 3.15，届时用 30002/403 而不是这里）。</li>
 * </ol>
 *
 * <p><b>不可见统一报 404，不报 403</b>：{@code POST_NOT_FOUND(30001)} 与
 * {@code POST_FORBIDDEN(30002)} 的差别在于「是否承认这条内容存在」。
 * 对私密帖和待审帖而言，承认存在本身就是泄露（有人可以通过 403 枚举出谁的 private 帖 id）。
 * 30002 留给真正「知道有、但没权限做」的写操作。</p>
 *
 * <p><b>浏览量：列表给库值，详情走缓存</b>。理由见 {@link ViewCountService}：
 * 一屏 20 条逐个合并未回写增量要发 20 次缓存读，换来的只是几次的读数差，
 * 所以列表最多滞后一个回写窗口（5 分钟），详情给准数。</p>
 *
 * <p><b>树洞到期销毁在这里先做「读侧不可见」</b>：{@code auto_destroy_at} 是发帖时算好的绝对时间
 * （BR9 明确禁止查询时用 create_time + N 现算），扫表把状态置成 destroyed 属任务 3.15，
 * 但读侧不先把过期帖滤掉，就等于向用户承诺了 7 天销毁却仍然天天给他看。
 * 两处过滤（列表 SQL 与详情 {@code visibleTo}）共用同一个 {@link #isExpired} 判据。</p>
 *
 * <p><b>列表与详情对「作者自己的私密已发布帖」口径不同，这是分工不是 bug</b>：
 * 广场列表 SQL 只放行「public 且 PUBLISHED」与「自己的待审帖」两类，所以作者<b>不会</b>
 * 在广场刷到自己勾了「仅自己可见」的那条；但详情接口走 {@link #visibleTo}，owner 恒可见。
 * 也就是说私密帖要靠自己记住的链接进去，或等任务 3.14「我的帖子」按 user_id 直查——
 * 广场是公共流，把私密帖混进自己的时间线反而会让「刷到已读三遍的旧帖」变成常态。</p>
 */
@Service
public class PostQueryService {

    /** 列表摘要长度：80 字够看清「说了什么」，又不至于把列表变成正文。 */
    static final int EXCERPT_CHARS = 80;

    /** 与 post.visibility 的 ENUM 逐字一致；friends（FR4.6 好友可见）本期不开放。 */
    static final String VISIBILITY_PUBLIC = "public";

    /** 马甲行被人为删掉时的兜底展示名——宁可显示成通用匿名，也不能回退成真实昵称。 */
    static final String ANONYMOUS_FALLBACK = "匿名屿民";

    /** 作者已注销（user 行 deleted=1，批量查自然查不到）时的展示名。 */
    static final String DELETED_AUTHOR = "已注销的屿民";

    /** 待审帖占位提示。与 PostService 给发帖人的一长句不同，列表里只放一行短标签。 */
    static final String AUDIT_TIP = "审核中，仅自己可见";

    private final PostMapper postMapper;
    private final PostImageMapper postImageMapper;
    private final PostTopicMapper postTopicMapper;
    private final TopicMapper topicMapper;
    private final UserMapper userMapper;
    private final AnonymousAliasMapper anonymousAliasMapper;
    private final PostLikeMapper postLikeMapper;
    private final ViewCountService viewCountService;
    private final MindisleProperties properties;

    public PostQueryService(PostMapper postMapper,
                            PostImageMapper postImageMapper,
                            PostTopicMapper postTopicMapper,
                            TopicMapper topicMapper,
                            UserMapper userMapper,
                            AnonymousAliasMapper anonymousAliasMapper,
                            PostLikeMapper postLikeMapper,
                            ViewCountService viewCountService,
                            MindisleProperties properties) {
        this.postMapper = postMapper;
        this.postImageMapper = postImageMapper;
        this.postTopicMapper = postTopicMapper;
        this.topicMapper = topicMapper;
        this.userMapper = userMapper;
        this.anonymousAliasMapper = anonymousAliasMapper;
        this.postLikeMapper = postLikeMapper;
        this.viewCountService = viewCountService;
        this.properties = properties;
    }

    // ================================================================ 列表

    /**
     * 广场列表。带 beforeId 走游标，否则走页码（两套都支持是 PageQuery 的既定契约）。
     *
     * @param type 帖子形式过滤，null/空串=全部；白名单外直接 10001，不做「猜一个」的兜底
     */
    public PageResult<PostListItem> list(long viewerId, String type, PageQuery query, LocalDateTime now) {
        PageQuery page = (query == null ? new PageQuery() : query).normalize();
        String typeFilter = normalizeTypeFilter(type);
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        applyVisible(wrapper, viewerId, typeFilter, now);

        return pageResult(wrapper, viewerId, page, now);
    }

    /**
     * 「我的帖子」列表（任务 3.13 第二批 · 手册 §6.2 U12 · 需求 FR4.1、FR4.3、BR9）。
     *
     * <p><b>为什么不复用广场那条路</b>：广场的判据是「别人能不能看」（{@link #visibleTo}），
     * 所以作者自己勾了「仅自己可见」的已发布帖<b>不进</b>广场列表 —— 那是分工不是漏洞。
     * 但用户发完总得找得回来，于是这里按 {@code user_id} 直查，绕开公共可见性判据。
     * 两条路的差别只有「查谁的帖」与「要不要过滤 public」，其余（游标键、排序、摘要、马甲、
     * 求助卡片、待审提示）全部共用同一个 {@link #pageResult}，绝不复制一份逻辑以免两边判歪。</p>
     *
     * <p><b>这里能看到哪些状态</b>：自己的非删除行全在，包含 {@code private}、待审，
     * 也包含机审未过（REJECTED）。未通过的帖对用户本人是「这条我没发出去」的事实信息，
     * 藏起来只会让人怀疑系统吞帖；别人则走广场与主页接口，永远拿不到这些行。</p>
     *
     * <p><b>到期销毁的树洞仍然不出现</b>：与广场共用同一个 {@code auto_destroy_at} 判据
     * （读侧先隐藏），把状态真扫成 destroyed 属任务 3.15。</p>
     *
     * @param status 可选状态过滤，白名单外直接 10001（与 type 同一口径：不做「猜一个」的兜底）
     */
    public PageResult<PostListItem> mine(long userId, String status, PageQuery query, LocalDateTime now) {
        PageQuery page = (query == null ? new PageQuery() : query).normalize();
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        applyOwned(wrapper, userId, normalizeStatusFilter(status), now);
        return pageResult(wrapper, userId, page, now);
    }

    /**
     * 某人的公开主页帖子列表（任务 3.13 第二批 · 手册 §6.2 U11 · 需求 FR1.4、FR4.1）。
     *
     * <p><b>这是一个刻意收窄的视图：匿名帖恒不出现</b>。接口按 {@code user_id} 查，
     * 如果不排除「is_anonymous=1 或挂了马甲 id」的行，就等于给任何人一条把匿名帖与真实账号
     * 逐条对上的通道 —— 那正是需求 FR1.4「匿名不可被普通用户解匿」要挡住的事。
     * 所以判据是三重收窄：{@code status=PUBLISHED} 且 {@code visibility=public}
     * 且既非匿名也无马甲 id。管理员的解匿审计走管理端，不经过这里。</p>
     *
     * <p><b>本人访问自己的主页与外人看到的内容完全一致</b>：这是「主页」的语义。
     * 想连私密与待审一起看，用 {@link #mine}；不因为你是本人就把私密内容混进公开视图。</p>
     */
    public PageResult<PostListItem> profile(long viewerId, long targetUserId, PageQuery query,
                                             LocalDateTime now) {
        if (userMapper.selectById(targetUserId) == null) {
            // 与详情的「不可见即 404」同口径：不存在与已注销不区分，区分本身就是一条枚举通道
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        PageQuery page = (query == null ? new PageQuery() : query).normalize();
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        applyPublicProfile(wrapper, targetUserId, now);
        return pageResult(wrapper, viewerId, page, now);
    }

    /**
     * 三种列表共用的翻页与组装：带 beforeId 走游标，否则走页码（两套都支持是 PageQuery 的既定契约）。
     *
     * <p>抽成一个函数的唯一理由是「翻页口径只允许有一份」。广场、我的、主页任何一处自己写一遍
     * {@code order by published_at desc, id desc}，就总有一天会出现「A 页翻页丢条目、B 页不丢」这种
     * 只有真机才能发现的偏差。</p>
     */
    private PageResult<PostListItem> pageResult(LambdaQueryWrapper<Post> wrapper, long viewerId,
                                                 PageQuery page, LocalDateTime now) {
        if (page.useCursor()) {
            // 锚点行只取排序键（published_at + id），不回任何内容，所以客户端拿别人的帖子 id 当游标也不泄露东西
            applyCursor(wrapper, postMapper.selectById(page.getBeforeId()), page.getBeforeId());
            wrapper.orderByDesc(Post::getPublishedAt).orderByDesc(Post::getId)
                    // size 已经被 normalize 夹在 1..50，拼进 limit 没有注入面；多取一条判 hasMore
                    .last("limit " + (page.getSize() + 1));
            List<Post> rows = postMapper.selectList(wrapper);
            return PageResult.ofCursor(toListItems(viewerId, rows, now), page.getSize(), PostListItem::id);
        }

        wrapper.orderByDesc(Post::getPublishedAt).orderByDesc(Post::getId);
        Page<Post> result = postMapper.selectPage(new Page<>(page.getPage(), page.getSize()), wrapper);
        List<PostListItem> items = toListItems(viewerId, result.getRecords(), now);
        PageResult<PostListItem> view = PageResult.of(items, result.getTotal(), page);
        // 页码模式也回 nextCursor，让前端只需要一种翻页方式：信息流的首屏必然是一次不带 beforeId
        // 的请求（走页码、给 total 做「共 N 条」），而它的下一页只能用游标。若首屏不回 nextCursor，
        // 前端就得自己知道「拿最后一条的 id 当游标」——那等于把服务端分页键的选型泄露给调用方。
        if (!items.isEmpty()) {
            view.setNextCursor(items.get(items.size() - 1).id());
        }
        return view;
    }


    private List<PostListItem> toListItems(long viewerId, List<Post> rows, LocalDateTime now) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Relations relations = loadRelations(viewerId, rows);
        List<PostListItem> items = new ArrayList<>(rows.size());
        for (Post row : rows) {
            items.add(toListItem(row, viewerId, relations, crisisHotline(row), now));
        }
        return items;
    }

    /**
     * 组装一条列表项。
     *
     * <p>{@code viewerId} 必须传进来：{@code auditTip} 的语义是「<b>你</b>发的这条还在审核」，
     * 拿 post.userId 自己和自己比会恒真，于是待审帖会在别人的信息流里顶着一句与己无关的提示，
     * 顺带把「谁在被审核」这件事泄露给全广场。</p>
     */
    /** 包级可见：与 visibleTo/excerpt 同一约定，让「出参字段有没有接错」能被单测钉住。 */
    static PostListItem toListItem(Post post, long viewerId, Relations relations, String hotline,
                                            LocalDateTime now) {
        return new PostListItem(post.getId(), post.getType(), post.getTitle(), excerpt(post.getContent()),
                displayNameOf(post, relations), isAnonymous(post), authorIdOf(post),
                relations.topicNames().getOrDefault(post.getId(), List.of()),
                relations.images().getOrDefault(post.getId(), List.of()), post.getStatus(), post.getVisibility(),
                longValue(post.getViewCnt()), intValue(post.getLikeCnt()), intValue(post.getCommentCnt()),
                post.getPublishedAt(), post.getAutoDestroyAt(), auditTipOf(post, viewerId, now), hotline,
                hasAction(relations, post, PostInteractionService.ACTION_LIKE),
                hasAction(relations, post, PostInteractionService.ACTION_COLLECT),
                intValue(post.getCollectCnt()));
    }

    // ================================================================ 详情

    /**
     * 帖子详情。读得到即有权看，权限判断全在 {@link #visibleTo}。
     *
     * <p>作者自己打开自己的帖<b>不</b>计浏览：BR4 已经定了「自赞自评不计入质量分」，
     * 而浏览量同样是质量分的输入，一个用户反复刷自己的帖就能把自己推上广场，
     * 这是推荐链路最先会被玩坏的地方。</p>
     */
    public PostDetailView detail(long viewerId, long postId, LocalDateTime now) {
        Post post = postMapper.selectById(postId);
        if (post == null || !visibleTo(post, viewerId, now)) {
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }
        Relations relations = loadRelations(viewerId, List.of(post));
        long viewCnt = longValue(post.getViewCnt());
        if (isOwner(post, viewerId)) {
            // 作者自看不计数（见上面那段注释），但展示值仍要把未回写的增量加上：
            // 否则同一条帖子，作者看到的会比别人看到的<b>小</b>，「我发了条帖，别人那里 12、我这里 9」
            // 会被当成计数丢了。计数口径可以是「你不算」，展示口径必须全平台一致。
            viewCnt += viewCountService.pending(post.getId());
        } else {
            viewCnt = viewCountService.recordView(post.getId(), viewCnt, now);
        }
        return new PostDetailView(post.getId(), post.getType(), post.getTitle(), post.getContent(),
                post.getVisibility(), displayNameOf(post, relations), isAnonymous(post), authorIdOf(post),
                relations.topicNames().getOrDefault(post.getId(), List.of()),
                relations.images().getOrDefault(post.getId(), List.of()),
                viewCnt, intValue(post.getLikeCnt()), intValue(post.getCommentCnt()),
                post.getPublishedAt(), post.getAutoDestroyAt(), post.getCreatedAt(),
                auditTipOf(post, viewerId, now), crisisHotline(post),
                hasAction(relations, post, PostInteractionService.ACTION_LIKE),
                hasAction(relations, post, PostInteractionService.ACTION_COLLECT),
                intValue(post.getCollectCnt()));
    }

    // ================================================================ 纯逻辑（单测直调，不碰数据库）

    /**
     * 一次批量读出来的「周边」：配图、话题名、作者、马甲、当前用户的点赞收藏态。
     *
     * <p>拆出来只为了让组装能被单测覆盖；第 5 个字段是任务 3.6 加的，
     * key 为 postId、value 为 {LIKE / COLLECT} 的活动动作集合——缺席即「没点过」，
     * 所以未登录时整个 Map 是空的而不是 null（判 null 收在 {@link #hasAction} 一处）。</p>
     */
    record Relations(Map<Long, List<PostView.ImageBrief>> images,
                     Map<Long, List<String>> topicNames,
                     Map<Long, User> users,
                     Map<Long, AnonymousAlias> aliases,
                     Map<Long, Set<String>> actions) {
    }

    /**
     * 「你点过没有」——列表与详情共用这一条判据（{@code relations.actions()} 里是 LIKE / COLLECT 字符串，
     * 取值与 {@code post_like.action_type} 的 ENUM 逐字一致）。
     *
     * <p>判 null 收在这一个函数里，而不是让每个调用点自己 {@code getOrDefault(...).contains(...)}：
     * 一屏二十条绝大多数没人赞过，给它们各塞一个空 Set 是白花的内存；
     * 而漏判一次 null 就是列表接口整页 500。这类「只有两处用到的判据」宁肯多一个静态函数。</p>
     */
    static boolean hasAction(Relations relations, Post post, String actionType) {
        Set<String> actions = relations.actions().get(post.getId());
        return actions != null && actions.contains(actionType);
    }

    /** 匿名帖（is_anonymous=1 或马甲 id 存在）。两个条件都认，因为历史数据里可能只有其一。 */
    static boolean isAnonymous(Post post) {
        return (post.getIsAnonymous() != null && post.getIsAnonymous() == 1) || post.getAliasId() != null;
    }

    /** 匿名帖一律不回 authorId：留在响应体里，前端不显示也照样能被抓包反查（FR1.4）。 */
    static Long authorIdOf(Post post) {
        return isAnonymous(post) ? null : post.getUserId();
    }

    static boolean isOwner(Post post, long viewerId) {
        return post.getUserId() != null && post.getUserId().longValue() == viewerId;
    }

    /** 树洞到期：auto_destroy_at 非空且不晚于当前时间。 */
    static boolean isExpired(Post post, LocalDateTime now) {
        return post.getAutoDestroyAt() != null && !post.getAutoDestroyAt().isAfter(now);
    }

    /** 唯一的一条可见性判据，列表 SQL 与详情都走它（两边判歪任何一边都是事故）。 */
    static boolean visibleTo(Post post, long viewerId, LocalDateTime now) {
        if (post == null || isExpired(post, now)) {
            return false;
        }
        boolean owner = isOwner(post, viewerId);
        String status = post.getStatus();
        if (PostService.STATUS_PUBLISHED.equals(status)) {
            return owner || VISIBILITY_PUBLIC.equals(post.getVisibility());
        }
        return owner && (PostService.STATUS_HUMAN_REVIEW.equals(status)
                || PostService.STATUS_MACHINE_REVIEW.equals(status));
    }

    /**
     * 待审占位提示：只有「作者本人 + 还在机审/人审 + 未到期销毁」三者同时成立才给。
     *
     * <p>别人根本拿不到这些行（列表 SQL 与详情 {@link #visibleTo} 都挡在前面），
     * 但这句提示本身是一句「我在替你审核」的承诺，所以判据里必须写清比较对象是查看者，
     * 而不是让「owner」在参数位置上悄悄变成 post.userId。</p>
     */
    static String auditTipOf(Post post, long viewerId, LocalDateTime now) {
        boolean pending = PostService.STATUS_HUMAN_REVIEW.equals(post.getStatus())
                || PostService.STATUS_MACHINE_REVIEW.equals(post.getStatus());
        return pending && isOwner(post, viewerId) && !isExpired(post, now) ? AUDIT_TIP : null;
    }

    /**
     * 正文摘要：换行与制表符压成空格（列表是单行展示），再按<b>码点</b>裁到 80。
     *
     * <p>用 codePoint 而不是 length 裁，是为了不把 emoji 截成半个代理对——
     * 任务 3.3 的遮罩也踩过同一类坑（孤立代理项会让响应体序列化直接抛 500）。</p>
     */
    static String excerpt(String content) {
        if (content == null) {
            return "";
        }
        StringBuilder flat = new StringBuilder(Math.min(content.length(), EXCERPT_CHARS * 2));
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            flat.append(c == '\n' || c == '\r' || c == '\t' ? ' ' : c);
        }
        String oneLine = flat.toString().strip();
        int chars = oneLine.codePointCount(0, oneLine.length());
        if (chars <= EXCERPT_CHARS) {
            return oneLine;
        }
        int end = oneLine.offsetByCodePoints(0, EXCERPT_CHARS);
        return oneLine.substring(0, end).stripTrailing() + "…";
    }

    /** 展示名：匿名走马甲名，实名走昵称（无昵称回退登录名）。口径与 PostService 完全一致。 */
    static String displayNameOf(Post post, Relations relations) {
        if (isAnonymous(post)) {
            AnonymousAlias alias = post.getAliasId() == null ? null : relations.aliases().get(post.getAliasId());
            return alias == null || alias.getAliasName() == null ? ANONYMOUS_FALLBACK : alias.getAliasName();
        }
        User author = post.getUserId() == null ? null : relations.users().get(post.getUserId());
        return author == null ? DELETED_AUTHOR : PostService.displayNameOf(author);
    }

    /** 配图按帖分组，保持 SQL 已经排好的 sort 顺序。 */
    static Map<Long, List<PostView.ImageBrief>> groupImages(List<PostImage> rows) {
        Map<Long, List<PostView.ImageBrief>> grouped = new LinkedHashMap<>();
        for (PostImage row : rows) {
            if (row.getPostId() == null) {
                continue;
            }
            grouped.computeIfAbsent(row.getPostId(), k -> new ArrayList<>())
                    .add(new PostView.ImageBrief(row.getUrl(), intValue(row.getWidth()), intValue(row.getHeight())));
        }
        return grouped;
    }

    /** 帖-话题关联按帖分组（保留发帖时的顺序，话题先后对用户是有含义的）。 */
    static Map<Long, List<Long>> groupTopicIds(List<PostTopic> rows) {
        Map<Long, List<Long>> grouped = new LinkedHashMap<>();
        for (PostTopic row : rows) {
            if (row.getPostId() == null || row.getTopicId() == null) {
                continue;
            }
            grouped.computeIfAbsent(row.getPostId(), k -> new ArrayList<>()).add(row.getTopicId());
        }
        return grouped;
    }

    /**
     * 话题 id 换成名字，<b>只留已过审的</b>（FR4.5）：话题是先审后挂的，
     * 但过审之后仍可能被管理员下架，那时帖子不该还替它引流，所以这里再判一次状态。
     * 话题被逻辑删除时批量查自然查不到，等价于「不显示」，不用额外处理。
     */
    static Map<Long, List<String>> nameTopics(Map<Long, List<Long>> linkIds, Map<Long, Topic> topicById) {
        Map<Long, List<String>> names = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Long>> entry : linkIds.entrySet()) {
            List<String> list = new ArrayList<>(entry.getValue().size());
            for (Long topicId : entry.getValue()) {
                Topic topic = topicById.get(topicId);
                if (topic != null && "APPROVED".equals(topic.getAuditStatus())) {
                    list.add(topic.getName());
                }
            }
            names.put(entry.getKey(), List.copyOf(list));
        }
        return names;
    }

    static long longValue(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    static int intValue(Integer value) {
        return value == null ? 0 : value;
    }

    /** 列表的 type 过滤：null 与空串表示「全部」，白名单外报参数错。 */
    static String normalizeTypeFilter(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String value = type.trim();
        if (!PostService.TYPES.contains(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "type 只能是 normal / hole / help，或不传表示全部");
        }
        return value;
    }

    /**
     * 「我的帖子」状态过滤白名单，取值与 {@code sql/04_community.sql} 的 post.status ENUM 逐字一致。
     *
     * <p>为什么不复用 {@code PostService} 的 STATUS_* 常量：那边只定义到这版代码会<b>写入</b>的 5 个态，
     * 而 DDL 是 8 态（还含 APPEALING / TAKEDOWN / DELETED）。这里是「读侧允许按哪些态筛」，
     * 必须跟 DDL 对齐，否则管理员下架后的帖就再也筛不出来。</p>
     */
    static final Set<String> STATUS_FILTERS = Set.of("DRAFT", "MACHINE_REVIEW", "HUMAN_REVIEW",
            "PUBLISHED", "REJECTED", "APPEALING", "TAKEDOWN", "DELETED");

    /** 状态过滤：null 与空串表示「全部」，白名单外报参数错（与 {@link #normalizeTypeFilter} 同口径）。 */
    static String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim().toUpperCase();
        if (!STATUS_FILTERS.contains(value)) {
            // 不直接拼 STATUS_FILTERS：Set.of 的迭代顺序按设计是每次随机的（同一个 JVM 两次打印都能不同），
            // 那样同一次错误会在日志里给出两种文案，前端要断言也断不了。排序后再拼，消息才是确定的。
            throw new BizException(ErrorCode.PARAM_INVALID, "status 只能是 "
                    + String.join(" / ", STATUS_FILTERS.stream().sorted().toList())
                    + " 之一，或不传表示全部");
        }
        return value;
    }

    // ================================================================ SQL 条件拼装

    /**
     * 可见性 + 过期销毁 + 形式过滤，一次拼完。
     *
     * <p><b>不拼 deleted</b>：{@code Post} 上的 {@code @TableLogic} 会自动追加
     * {@code deleted = 0}，这里再拼一次只会生成两个条件，改逻辑删除位时要改两处。</p>
     */
    static void applyVisible(LambdaQueryWrapper<Post> wrapper, long viewerId, String typeFilter,
                             LocalDateTime now) {
        wrapper.and(scope -> scope
                .and(open -> open.eq(Post::getStatus, PostService.STATUS_PUBLISHED)
                        .eq(Post::getVisibility, VISIBILITY_PUBLIC))
                .or(pending -> pending.eq(Post::getUserId, viewerId)
                        .in(Post::getStatus, PostService.STATUS_MACHINE_REVIEW, PostService.STATUS_HUMAN_REVIEW)));
        if (typeFilter != null) {
            wrapper.eq(Post::getType, typeFilter);
        }
        applyNotExpired(wrapper, now);
    }

    /**
     * 「我的帖子」条件：只按 {@code user_id} 收窄，不碰公共可见性判据。
     *
     * <p>与 {@link #applyVisible} 一样<b>不拼 deleted</b>（{@code @TableLogic} 自动追加），
     * 也共用同一条 {@link #applyNotExpired}：到期树洞在自己的列表里同样不该出现，
     * 否则「销毁」就成了只对别人生效的假承诺。</p>
     */
    static void applyOwned(LambdaQueryWrapper<Post> wrapper, long userId, String statusFilter,
                           LocalDateTime now) {
        wrapper.eq(Post::getUserId, userId);
        if (statusFilter != null) {
            wrapper.eq(Post::getStatus, statusFilter);
        }
        applyNotExpired(wrapper, now);
    }

    /**
     * 公开主页条件：三重收窄，其中最要紧的一条是<b>排除匿名帖</b>。
     *
     * <p>{@code is_anonymous=1} 或挂了 {@code alias_id} 的行一旦能按 user_id 查出来，
     * 这个接口就变成解匿工具（需求 FR1.4 明令禁止普通用户做到这件事）。
     * 判据与 {@link #isAnonymous} 严格取反：{@code (is_anonymous IS NULL OR = 0) AND alias_id IS NULL}。
     * 单测把这条钉住，不靠注释。</p>
     */
    static void applyPublicProfile(LambdaQueryWrapper<Post> wrapper, long userId, LocalDateTime now) {
        wrapper.eq(Post::getUserId, userId)
                .eq(Post::getStatus, PostService.STATUS_PUBLISHED)
                .eq(Post::getVisibility, VISIBILITY_PUBLIC)
                .and(real -> real.isNull(Post::getIsAnonymous).or().eq(Post::getIsAnonymous, 0))
                .isNull(Post::getAliasId);
        applyNotExpired(wrapper, now);
    }

    /** 未到期销毁：{@code auto_destroy_at} 为空（非树洞）或晚于当前时间。三个列表共用这一条。 */
    static void applyNotExpired(LambdaQueryWrapper<Post> wrapper, LocalDateTime now) {
        wrapper.and(alive -> alive.isNull(Post::getAutoDestroyAt).or().gt(Post::getAutoDestroyAt, now));
    }

    /**
     * 游标条件：只取排序键严格位于锚点之后的行。
     *
     * <p>待审帖的 published_at 是 NULL，MySQL 在 DESC 里把 NULL 排在最后，
     * 所以「锚点有发布时间」这一支必须把 {@code published_at IS NULL} 也算进去，
     * 否则作者自己那一页永远刷不到审核中的占位帖。</p>
     */
    static void applyCursor(LambdaQueryWrapper<Post> wrapper, Post anchor, Long beforeId) {
        if (anchor == null || anchor.getPublishedAt() == null) {
            // 锚点已被删/已销毁/是待审帖：退化成纯 id 游标，宁可不重叠也别漏
            wrapper.lt(Post::getId, beforeId);
            return;
        }
        LocalDateTime key = anchor.getPublishedAt();
        wrapper.and(scope -> scope
                .and(older -> older.lt(Post::getPublishedAt, key))
                .or().isNull(Post::getPublishedAt)
                .or(tie -> tie.eq(Post::getPublishedAt, key).lt(Post::getId, beforeId)));
    }

    // ================================================================ 批量取周边（防 N+1）

    /**
     * 一屏帖子的配图、话题、作者、马甲各用一条 SQL 批量取。
     *
     * <p>不做这件事的话，20 条帖子会打出 20×4 条查询——手册 NFR3 给列表接口定的 P95 是 300ms，
     * 光往返就不够。</p>
     */
    /**
     * 签名里的 {@code viewerId} 只服务一件事：取「这个查看者对这些帖的点赞/收藏态」。
     *
     * <p>它必须是参数而不是字段——本类是无状态单例，把 viewer 存成字段会让所有请求共享同一个人的
     * 已赞状态（需求 FR4.4 的「点过就高亮」立刻变成全站高亮）。{@code PostListItem} 的 auditTip
     * 当年就是因为同样的原因走参数，此处沿用同一口径。</p>
     */
    private Relations loadRelations(long viewerId, List<Post> rows) {
        List<Long> postIds = rows.stream().map(Post::getId).filter(Objects::nonNull).toList();
        Map<Long, List<PostView.ImageBrief>> images = groupImages(postImageMapper.listByPosts(postIds));
        Map<Long, List<Long>> linkIds = groupTopicIds(postTopicMapper.listByPosts(postIds));

        List<Long> topicIds = linkIds.values().stream().flatMap(List::stream).distinct().toList();
        Map<Long, Topic> topicById = new LinkedHashMap<>();
        if (!topicIds.isEmpty()) {
            for (Topic topic : topicMapper.selectList(new LambdaQueryWrapper<Topic>().in(Topic::getId, topicIds))) {
                topicById.put(topic.getId(), topic);
            }
        }

        List<Long> authorIds = rows.stream().filter(r -> !isAnonymous(r)).map(Post::getUserId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, User> userById = new LinkedHashMap<>();
        if (!authorIds.isEmpty()) {
            for (User user : userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getId, authorIds))) {
                userById.put(user.getId(), user);
            }
        }

        List<Long> aliasIds = rows.stream().map(Post::getAliasId).filter(Objects::nonNull).distinct().toList();
        Map<Long, AnonymousAlias> aliasById = new LinkedHashMap<>();
        if (!aliasIds.isEmpty()) {
            for (AnonymousAlias alias : anonymousAliasMapper.selectList(
                    new LambdaQueryWrapper<AnonymousAlias>().in(AnonymousAlias::getId, aliasIds))) {
                aliasById.put(alias.getId(), alias);
            }
        }
        return new Relations(images, nameTopics(linkIds, topicById), userById, aliasById,
                loadActions(viewerId, postIds));
    }

    /**
     * 一屏帖子的「你赞没赞 / 你藏没藏」，<b>一条 SQL 取完</b>。
     *
     * <p>逐条查会让一屏 20 帖打出 20 条 SELECT，NFR3 给列表定的 P95 是 300ms，光往返就不够。
     * 走 Wrapper 而不是裸 SQL 是因为 {@code PostLike} 上的 {@code @TableLogic} 会自动追加
     * {@code deleted = 0}——「取消过的赞不算已赞」这条判据因此与写入侧同源，
     * 只由逻辑删除位一处定义（口径同 {@code PostInteractionStoreAdapter#activeActionsOf}）。</p>
     */
    private Map<Long, Set<String>> loadActions(long viewerId, List<Long> postIds) {
        Map<Long, Set<String>> actions = new LinkedHashMap<>();
        if (viewerId <= 0L || postIds.isEmpty()) {
            // 0 与负数都不是真实用户 id（列表接口未登录早就被过滤器挡在 401 了），
            // 这里只是把「拿不到身份」明确降级成「谁都没赞过」，而不是拿 null 去撞唯一键。
            return actions;
        }
        for (PostLike row : postLikeMapper.selectList(new LambdaQueryWrapper<PostLike>()
                .eq(PostLike::getUserId, viewerId)
                .eq(PostLike::getTargetType, PostInteractionService.TARGET_POST)
                .in(PostLike::getTargetId, postIds))) {
            if (row.getTargetId() == null || row.getActionType() == null) {
                continue;
            }
            actions.computeIfAbsent(row.getTargetId(), k -> new HashSet<>()).add(row.getActionType());
        }
        return actions;
    }

    /**
     * 求助卡片：{@code help} 恒给，L2/L3 的帖子也给（FR10.3「命中后必须展示求助卡片」）。
     *
     * <p>这里读的是 {@code risk_level} 但<b>不回传它</b>——等级只决定「给不给电话」，
     * 不进响应体，与 {@code PostView} 的口径一致（不给用户贴标签，NFR8）。</p>
     */
    private String crisisHotline(Post post) {
        boolean help = PostService.TYPE_HELP.equals(post.getType());
        return help || CrisisGrader.needsTicket(post.getRiskLevel())
                ? properties.getCrisis().getHotline() : null;
    }
}

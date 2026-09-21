package com.mindisle.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mindisle.cache.CaffeineCacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.Post;
import com.mindisle.entity.User;
import com.mindisle.post.dto.PostActionView;

/**
 * 点赞/收藏单测（任务 T3.6 · 需求 FR4.4、BR2、BR4、BR6）。
 *
 * <p><b>测的是规则，不是 SQL</b>：本类用手写内存 fake 实现 InteractionStore，并刻意把
 * {@code uk_action(user_id, target_type, target_id, action_type, day_bucket)} 与
 * 「COUNT(DISTINCT user_id) WHERE deleted=0」这两条库约束照抄成真语义——
 * fake 只实现唯一键这一条约束，真库上其它约束报错仍会照常抛出，两侧行为一致才算测到了东西
 * （口径见 {@link com.mindisle.mapper.PostLikeMapper#insertIgnore} 的注释）。
 * 至于 {@code refreshLikeCnt} 那条相关子查询到底过滤了哪些行、走没走 idx_target，
 * 由 docs/dev-log.md 阶段 3 里 root 直连 SQL 的不变式取证负责，不在这里假装测过。</p>
 *
 * <p>配额判据用<b>真的</b> {@link PostingQuotaService}（它只依赖 CacheService，
 * new 一个 CaffeineCacheService 就是最真实的实现），于是 BR6「禁言能点赞、封禁不能」
 * 这条跨类规则在这里是真的跑通了一遍，而不是两边各自 mock 得像。</p>
 */
class PostInteractionServiceTest {

    /** 固定时间基准：既当「到期销毁」判据，也决定 day_bucket；服务内部不读系统时钟。 */
    private static final LocalDateTime DAY1_NOON = LocalDateTime.of(2026, 9, 20, 12, 0);
    private static final LocalDateTime DAY2_NOON = LocalDateTime.of(2026, 9, 21, 12, 0);

    private static final long POST_ID = 501L;
    private static final long AUTHOR_ID = 900L;
    private static final long VIEWER_ID = 901L;

    private FakeStore store;
    private PostingQuotaService quota;
    private PostInteractionService service;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        store.posts.put(POST_ID, publishedPublicPost(POST_ID, AUTHOR_ID));
        store.users.put(AUTHOR_ID, activeUser(AUTHOR_ID, "作者"));
        store.users.put(VIEWER_ID, activeUser(VIEWER_ID, "看客"));
        quota = new PostingQuotaService(new CaffeineCacheService(), new MindisleProperties());
        service = new PostInteractionService(store, quota);
    }

    // ---------- 准备工具 ----------

    private static Post publishedPublicPost(long id, long authorId) {
        Post post = new Post();
        post.setId(id);
        post.setUserId(authorId);
        post.setStatus(PostService.STATUS_PUBLISHED);
        post.setVisibility(PostQueryService.VISIBILITY_PUBLIC);
        post.setIsAnonymous(0);
        // DDL 里四列计数都是 NOT NULL DEFAULT 0，fake 必须一样，否则「零写入」路径会拿到 null，
        // 断言就会因为 fake 太宽松而假绿（本轮实测踩过一次）。
        post.setViewCnt(0);
        post.setLikeCnt(0);
        post.setCollectCnt(0);
        post.setCommentCnt(0);
        return post;
    }

    private static User activeUser(long id, String nickname) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setNickname(nickname);
        user.setStatus("ACTIVE");
        user.setCreatedAt(DAY1_NOON.minusDays(30));
        return user;
    }

    private static String codeMessage(Runnable call) {
        BizException error = assertThrows(BizException.class, call::run);
        return error.getErrorCode() + "|" + error.getMessage();
    }

    private static ErrorCode codeOf(Runnable call) {
        return assertThrows(BizException.class, call::run).getErrorCode();
    }

    /** 手工塞一行真相记录，用来构造「并发跨日留下两行活动行」这种真机上要撞运气才有的状态。 */
    private void seedRow(long userId, String actionType, LocalDate dayBucket, int deleted) {
        FakeStore.Row row = store.new Row();
        row.userId = userId;
        row.targetType = PostInteractionService.TARGET_POST;
        row.targetId = POST_ID;
        row.actionType = actionType;
        row.dayBucket = dayBucket;
        row.deleted = deleted;
        store.rows.add(row);
    }

    /** 每次互动之后都必须成立的不变式：post 上的冗余列 == 真相表里的活动人数。 */
    private void assertCountsMatchTruth(long postId) {
        Post post = store.posts.get(postId);
        assertThat(post.getLikeCnt())
                .as("post.like_cnt 必须等于 COUNT(DISTINCT user_id) WHERE action_type=LIKE")
                .isEqualTo((int) store.countActiveUsers(postId, PostInteractionService.ACTION_LIKE));
        assertThat(post.getCollectCnt())
                .as("post.collect_cnt 必须等于 COUNT(DISTINCT user_id) WHERE action_type=COLLECT")
                .isEqualTo((int) store.countActiveUsers(postId, PostInteractionService.ACTION_COLLECT));
    }

    // ---------- BR2：幂等 ----------

    @Test
    @DisplayName("BR2：连点两次只计 1 人，第二次 changed=false 且一次写入都不做")
    void repeatedLikeCountsOnePersonAndWritesNothing() {
        PostActionView first = service.act(VIEWER_ID, POST_ID, "like", DAY1_NOON);
        assertThat(first.changed()).isTrue();
        assertThat(first.liked()).isTrue();
        assertThat(first.likeCnt()).isEqualTo(1);

        int inserts = store.insertCalls;
        PostActionView second = service.act(VIEWER_ID, POST_ID, "like", DAY1_NOON);
        assertThat(second.changed()).as("重复请求不是错误，但也不能算改动").isFalse();
        assertThat(second.liked()).isTrue();
        assertThat(second.likeCnt()).isEqualTo(1);
        assertThat(store.rows).hasSize(1);
        assertThat(store.insertCalls).as("短路判据必须真的短路掉写入").isEqualTo(inserts);
        assertCountsMatchTruth(POST_ID);
    }

    @Test
    @DisplayName("取消未赞过的帖子：changed=false、不报错、零写入（幂等的另一半）")
    void unlikeWithoutLikeIsSilentNoOp() {
        int writes = store.insertCalls + store.updateCalls;
        PostActionView view = service.act(VIEWER_ID, POST_ID, "unlike", DAY1_NOON);
        assertThat(view.changed()).isFalse();
        assertThat(view.liked()).isFalse();
        assertThat(view.likeCnt()).isZero();
        assertThat(store.rows).isEmpty();
        assertThat(store.insertCalls + store.updateCalls).as("负向幂等同样不许产生 UPDATE").isEqualTo(writes);
        assertCountsMatchTruth(POST_ID);
    }

    @Test
    @DisplayName("取消再点赞：复用同一行，day_bucket 仍是首次成立那天（不是最后一次点赞那天）")
    void unlikeThenLikeRevivesTheSameRowAndKeepsFirstDayBucket() {
        service.act(VIEWER_ID, POST_ID, "like", DAY1_NOON);
        service.act(VIEWER_ID, POST_ID, "unlike", DAY1_NOON);
        // 跨到第二天再赞：走的是「复活」而不是「插入」，所以 uk 上那个日期不变。
        PostActionView again = service.act(VIEWER_ID, POST_ID, "like", DAY2_NOON);
        assertThat(again.changed()).isTrue();
        assertThat(again.likeCnt()).isEqualTo(1);
        assertThat(store.rows).hasSize(1);
        assertThat(store.rows.get(0).dayBucket).as("行为留痕只留一行，日期取首次成立").isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(store.rows.get(0).deleted).isZero();
        assertThat(store.insertCalls)
                .as("整条链路只允许第一次点赞插行：复活成功就不再走 INSERT，顺序反了日期会变成最后点赞日")
                .isEqualTo(1);
        assertCountsMatchTruth(POST_ID);
    }

    @Test
    @DisplayName("并发跨日留下两行活动行：计数仍是 1 人，且取消一次把两行全置掉（自愈）")
    void duplicateActiveRowsAcrossDaysCountOnePersonAndUnlikeClearsBoth() {
        seedRow(VIEWER_ID, PostInteractionService.ACTION_LIKE, LocalDate.of(2026, 9, 20), 0);
        seedRow(VIEWER_ID, PostInteractionService.ACTION_LIKE, LocalDate.of(2026, 9, 21), 0);
        assertThat(store.countActiveUsers(POST_ID, PostInteractionService.ACTION_LIKE))
                .as("这就是计数用 COUNT(DISTINCT user_id) 而不是 COUNT(*) 的全部理由")
                .isEqualTo(1);

        service.act(VIEWER_ID, POST_ID, "unlike", DAY2_NOON);
        assertThat(store.rows).allSatisfy(row -> assertThat(row.deleted).as("取消要一次覆盖全部活动行").isEqualTo(1));
        assertThat(store.countActiveUsers(POST_ID, PostInteractionService.ACTION_LIKE)).isZero();
        assertCountsMatchTruth(POST_ID);
    }

    // ---------- 动作语义 ----------

    @Test
    @DisplayName("点赞与收藏互不干扰：两条动作各自一份活动行与计数")
    void likeAndCollectAreIndependent() {
        assertThat(service.act(VIEWER_ID, POST_ID, "like", DAY1_NOON).collected()).isFalse();
        PostActionView collected = service.act(VIEWER_ID, POST_ID, "collect", DAY1_NOON);
        assertThat(collected.changed()).isTrue();
        assertThat(collected.liked()).as("回执要说清「现在到底赞没赞、藏没藏」").isTrue();
        assertThat(collected.collected()).isTrue();
        assertThat(collected.likeCnt()).isEqualTo(1);
        assertThat(collected.collectCnt()).isEqualTo(1);

        PostActionView uncollected = service.act(VIEWER_ID, POST_ID, "uncollect", DAY1_NOON);
        assertThat(uncollected.collected()).isFalse();
        assertThat(uncollected.liked()).as("取消收藏不许把赞一起取消").isTrue();
        assertThat(uncollected.collectCnt()).isZero();
        assertThat(uncollected.likeCnt()).isEqualTo(1);
        assertThat(store.rows).hasSize(2);
        assertCountsMatchTruth(POST_ID);
    }

    @Test
    @DisplayName("动作归一化：大小写与前后空格不敏感；白名单外报 10001 且文案按字典序")
    void actionNormalizationAndWhitelist() {
        assertThat(service.act(VIEWER_ID, POST_ID, "  LIKE ", DAY1_NOON).liked()).isTrue();
        assertThat(service.act(VIEWER_ID, POST_ID, "Unlike", DAY1_NOON).liked()).isFalse();
        assertThat(codeMessage(() -> service.act(VIEWER_ID, POST_ID, "hug", DAY1_NOON)))
                .isEqualTo(ErrorCode.PARAM_INVALID + "|action 只能是 collect / like / uncollect / unlike 之一");
        assertThat(codeMessage(() -> service.act(VIEWER_ID, POST_ID, null, DAY1_NOON)))
                .startsWith(ErrorCode.PARAM_INVALID + "|");
        // Set.of 的迭代顺序随机，文案必须排序后再拼，否则同一句提示会有多种字面顺序。
        assertThat(PostInteractionService.normalize("Collect")).isEqualTo("collect");
    }

    @Test
    @DisplayName("回执里的 selfAction：作者赞自己的帖子被标记，但仍然计入 like_cnt（BR4 的「不计入」属 T3.10 埋点）")
    void selfActionIsFlaggedButStillCounted() {
        PostActionView view = service.act(AUTHOR_ID, POST_ID, "like", DAY1_NOON);
        assertThat(view.selfAction()).as("这是决策不是漏洞：标记回传给埋点层，计数口径不动").isTrue();
        assertThat(view.likeCnt()).isEqualTo(1);
        assertCountsMatchTruth(POST_ID);
        // 作者赞自己那条「仅自己可见」的帖子同样成立：可见性判据里 owner 本来就能看，
        // 而别人连这条帖子存不存在都问不出来（30001 而不是 30002）。
        long privateId = 502L;
        Post priv = publishedPublicPost(privateId, AUTHOR_ID);
        priv.setVisibility("private");
        store.posts.put(privateId, priv);
        assertThat(service.act(AUTHOR_ID, privateId, "like", DAY1_NOON).likeCnt()).isEqualTo(1);
        assertCountsMatchTruth(privateId);
        assertThat(codeOf(() -> service.act(VIEWER_ID, privateId, "like", DAY1_NOON)))
                .isEqualTo(ErrorCode.POST_NOT_FOUND);
    }

    // ---------- 可见性与资格 ----------

    @Test
    @DisplayName("不可见/不存在/已到期树洞：一律 404/30001，绝不 403；且一行都不写")
    void invisibleTargetsFailAsNotFound() {
        long privateId = 503L;
        Post priv = publishedPublicPost(privateId, AUTHOR_ID);
        priv.setVisibility("private");
        store.posts.put(privateId, priv);

        long holeId = 504L;
        Post hole = publishedPublicPost(holeId, AUTHOR_ID);
        hole.setType(PostService.TYPE_HOLE);
        hole.setAutoDestroyAt(DAY1_NOON.minusMinutes(1));
        store.posts.put(holeId, hole);

        int before = store.rows.size();
        assertThat(codeOf(() -> service.act(VIEWER_ID, privateId, "like", DAY1_NOON)))
                .as("别人的私密帖：不承认它存在")
                .isEqualTo(ErrorCode.POST_NOT_FOUND);
        assertThat(codeOf(() -> service.act(VIEWER_ID, 999_999L, "like", DAY1_NOON)))
                .isEqualTo(ErrorCode.POST_NOT_FOUND);
        assertThat(codeOf(() -> service.act(VIEWER_ID, holeId, "collect", DAY1_NOON)))
                .isEqualTo(ErrorCode.POST_NOT_FOUND);
        assertThat(codeOf(() -> service.act(VIEWER_ID, privateId, "unlike", DAY1_NOON)))
                .as("连「取消」都不许拿 30002 泄露存在性")
                .isEqualTo(ErrorCode.POST_NOT_FOUND);
        assertThat(store.rows).hasSize(before);
    }

    @Test
    @DisplayName("BR6：禁言可点赞收藏，封禁/注销/脏状态 20003；发起人查无此人 20001")
    void interactionQualificationFollowsBR6() {
        long mutedId = 902L;
        store.users.put(mutedId, activeUser(mutedId, "禁言的"));
        store.users.get(mutedId).setStatus("MUTED");
        long bannedId = 903L;
        store.users.put(bannedId, activeUser(bannedId, "封禁的"));
        store.users.get(bannedId).setStatus("BANNED");

        assertDoesNotThrow(() -> service.act(mutedId, POST_ID, "like", DAY1_NOON));
        assertDoesNotThrow(() -> service.act(mutedId, POST_ID, "collect", DAY1_NOON));
        assertThat(store.countActiveUsers(POST_ID, PostInteractionService.ACTION_LIKE)).isEqualTo(1);
        assertThat(codeOf(() -> service.act(bannedId, POST_ID, "like", DAY1_NOON)))
                .isEqualTo(ErrorCode.USER_DISABLED);
        assertThat(codeOf(() -> service.act(999_998L, POST_ID, "like", DAY1_NOON)))
                .as("账号行查不到（注销后被 @TableLogic 过滤）→ 20001，不是 500")
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertCountsMatchTruth(POST_ID);
    }

    @Test
    @DisplayName("待审帖只有作者自己能点赞：别人拿 30001，作者拿 200 且计数成立")
    void pendingPostIsInteractableByAuthorOnly() {
        long pendingId = 505L;
        Post pending = publishedPublicPost(pendingId, AUTHOR_ID);
        pending.setStatus(PostService.STATUS_HUMAN_REVIEW);
        pending.setVisibility("private");
        store.posts.put(pendingId, pending);

        assertThat(codeOf(() -> service.act(VIEWER_ID, pendingId, "like", DAY1_NOON)))
                .isEqualTo(ErrorCode.POST_NOT_FOUND);
        PostActionView mine = service.act(AUTHOR_ID, pendingId, "like", DAY1_NOON);
        assertThat(mine.likeCnt()).isEqualTo(1);
        assertThat(mine.selfAction()).isTrue();
        assertCountsMatchTruth(pendingId);
    }

    @Test
    @DisplayName("一条链路上把点赞收藏来回点八次：任何时刻 post 上的冗余列都等于真相表")
    void countersStayEqualToTruthAcrossLongSequence() {
        long otherId = 904L;
        store.users.put(otherId, activeUser(otherId, "第三个人"));
        service.act(VIEWER_ID, POST_ID, "like", DAY1_NOON);
        assertCountsMatchTruth(POST_ID);
        service.act(otherId, POST_ID, "like", DAY1_NOON);
        assertCountsMatchTruth(POST_ID);
        service.act(otherId, POST_ID, "collect", DAY2_NOON);
        assertCountsMatchTruth(POST_ID);
        assertThat(service.act(VIEWER_ID, POST_ID, "like", DAY2_NOON).changed())
                .as("同一天重复点同一动作：不改动")
                .isFalse();
        assertCountsMatchTruth(POST_ID);
        service.act(VIEWER_ID, POST_ID, "unlike", DAY2_NOON);
        assertCountsMatchTruth(POST_ID);
        service.act(otherId, POST_ID, "unlike", DAY2_NOON);
        assertCountsMatchTruth(POST_ID);
        assertThat(store.countActiveUsers(POST_ID, PostInteractionService.ACTION_LIKE)).isZero();
        assertThat(store.countActiveUsers(POST_ID, PostInteractionService.ACTION_COLLECT)).isEqualTo(1);
        service.act(otherId, POST_ID, "uncollect", DAY2_NOON);
        assertCountsMatchTruth(POST_ID);
        assertThat(store.posts.get(POST_ID).getLikeCnt()).isZero();
        assertThat(store.posts.get(POST_ID).getCollectCnt()).isZero();
    }

    // ---------- 内存 fake：实现真 uk 语义 ----------

    private static final class FakeStore implements PostInteractionService.InteractionStore {

        private final class Row {
            private long userId;
            private String targetType;
            private long targetId;
            private String actionType;
            private LocalDate dayBucket;
            private int deleted;
        }

        private final List<Row> rows = new ArrayList<>();
        private final Map<Long, Post> posts = new LinkedHashMap<>();
        private final Map<Long, User> users = new LinkedHashMap<>();

        /** 统计写入次数，用来钉住「幂等短路时一个字节都不写」这条断言。 */
        private int insertCalls;
        private int updateCalls;

        /** 同一个「人 + 目标 + 动作」三元组，不带日期，等价于 uk_action 去掉 day_bucket 后的前缀。 */
        private boolean sameTarget(Row row, long userId, long postId) {
            return row.userId == userId && PostInteractionService.TARGET_POST.equals(row.targetType)
                    && row.targetId == postId;
        }

        private boolean matches(Row row, long userId, long postId, String actionType) {
            return sameTarget(row, userId, postId) && row.actionType.equals(actionType);
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
        public Set<String> activeActionsOf(long userId, long postId) {
            Set<String> actions = new HashSet<>();
            for (Row row : rows) {
                if (row.deleted == 0 && sameTarget(row, userId, postId)) {
                    actions.add(row.actionType);
                }
            }
            return actions;
        }

        @Override
        public int reviveCancelled(long userId, long postId, String actionType) {
            int affected = 0;
            for (Row row : rows) {
                if (row.deleted == 1 && matches(row, userId, postId, actionType)) {
                    row.deleted = 0;
                    affected++;
                }
            }
            if (affected > 0) {
                updateCalls++;
            }
            return affected;
        }

        @Override
        public int insertIgnore(long userId, long postId, String actionType, LocalDate dayBucket) {
            insertCalls++;
            for (Row row : rows) {
                // uk_action(user_id, target_type, target_id, action_type, day_bucket)：撞键返回 0 不报错。
                if (row.userId == userId && PostInteractionService.TARGET_POST.equals(row.targetType)
                        && row.targetId == postId && row.actionType.equals(actionType)
                        && row.dayBucket.equals(dayBucket)) {
                    return 0;
                }
            }
            Row row = new Row();
            row.userId = userId;
            row.targetType = PostInteractionService.TARGET_POST;
            row.targetId = postId;
            row.actionType = actionType;
            row.dayBucket = dayBucket;
            row.deleted = 0;
            rows.add(row);
            return 1;
        }

        @Override
        public int cancelActive(long userId, long postId, String actionType) {
            int affected = 0;
            for (Row row : rows) {
                if (row.deleted == 0 && matches(row, userId, postId, actionType)) {
                    row.deleted = 1;
                    affected++;
                }
            }
            if (affected > 0) {
                updateCalls++;
            }
            return affected;
        }

        @Override
        public long countActiveUsers(long postId, String actionType) {
            Set<Long> people = new HashSet<>();
            for (Row row : rows) {
                if (row.deleted == 0 && PostInteractionService.TARGET_POST.equals(row.targetType)
                        && row.targetId == postId && row.actionType.equals(actionType)) {
                    people.add(row.userId);
                }
            }
            return people.size();
        }

        /** 与 PostMapper#refreshLikeCnt 同语义：把冗余列刷成真相，而不是 ±1。 */
        @Override
        public void refreshPostCounts(long postId) {
            Post post = posts.get(postId);
            post.setLikeCnt((int) countActiveUsers(postId, PostInteractionService.ACTION_LIKE));
            post.setCollectCnt((int) countActiveUsers(postId, PostInteractionService.ACTION_COLLECT));
        }
    }
}

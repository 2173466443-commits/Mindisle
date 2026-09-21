package com.mindisle.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.entity.AnonymousAlias;
import com.mindisle.entity.Post;
import com.mindisle.entity.PostImage;
import com.mindisle.entity.PostTopic;
import com.mindisle.entity.Topic;
import com.mindisle.entity.User;
import com.mindisle.post.PostQueryService.Relations;
import com.mindisle.post.dto.PostView.ImageBrief;

/**
 * 列表与详情的「不需要数据库就能判对错」那半（任务 3.5 · 手册 §6.1 行 3.5 · 需求 FR4.3、FR7.3、BR9）。
 *
 * <p>测的是三件最容易在改动里悄悄歪掉的事：
 * <b>可见性判据</b>（判歪一次就是隐私事故）、<b>匿名帖不能漏出 author_id</b>（FR1.4）、
 * <b>待审提示只能给作者本人</b>（否则等于向全广场广播「谁的内容正在被审核」）。
 * 这三件都是纯函数，能一次跑完全部状态组合。</p>
 *
 * <p><b>刻意不测 {@code applyVisible} / {@code applyCursor} 拼出来的 SQL 文本</b>：
 * 脱离 Spring 容器时 MyBatis-Plus 没有实体的 TableInfo 缓存，手工把它初始化一遍纯属给测试搭脚手架，
 * 而断言字符串里的 SQL 更是「改格式就红」。游标翻页不重叠、可见性两条分支，
 * 由 {@code docs/smoke.mjs} 第 12 步打真实 MySQL 验，那才是能证伪它的地方。</p>
 */
class PostQueryServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 10, 0);

    private static final long AUTHOR = 11L;
    private static final long OTHER = 22L;

    private static Post post(String status, String visibility, Long authorId, Integer anonymous, Long aliasId) {
        Post post = new Post();
        post.setId(101L);
        post.setUserId(authorId);
        post.setStatus(status);
        post.setVisibility(visibility);
        post.setIsAnonymous(anonymous);
        post.setAliasId(aliasId);
        return post;
    }

    /** 公开已发布实名帖，改哪个维度就 with 哪个。 */
    private static Post publishedPublic() {
        return post(PostService.STATUS_PUBLISHED, "public", AUTHOR, 0, null);
    }

    private static Relations emptyRelations() {
        return new Relations(Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static User user(long id, String username, String nickname) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setNickname(nickname);
        return user;
    }

    private static Topic topic(long id, String name, String auditStatus) {
        Topic topic = new Topic();
        topic.setId(id);
        topic.setName(name);
        topic.setAuditStatus(auditStatus);
        return topic;
    }

    // ------------------------------------------------------------------ 摘要

    @Test
    @DisplayName("摘要把换行与制表压成空格并去首尾空白")
    void excerptFlattensWhitespace() {
        assertThat(PostQueryService.excerpt(null)).isEmpty();
        assertThat(PostQueryService.excerpt("  \n 第一行\r\n\t第二行  ")).isEqualTo("第一行   第二行");
        assertThat(PostQueryService.excerpt("\n\t \r")).isEmpty();
    }

    @Test
    @DisplayName("80 字是边界：正好 80 不加省略号，81 字裁到 80 再补")
    void excerptCutsAtEightyCodePoints() {
        String exactly = "想".repeat(PostQueryService.EXCERPT_CHARS);
        assertThat(PostQueryService.excerpt(exactly)).isEqualTo(exactly);
        String longer = "想".repeat(PostQueryService.EXCERPT_CHARS) + "了";
        assertThat(PostQueryService.excerpt(longer)).isEqualTo(exactly + "…");
    }

    @Test
    @DisplayName("emoji 占两个 char 但只算一个字：不会被劈成半个代理对")
    void excerptKeepsSurrogatePairsWhole() {
        String content = "a".repeat(79) + "\uD83D\uDE00" + "bc";
        String excerpt = PostQueryService.excerpt(content);
        // 前 80 个码点 = 79 个 a + 整个表情，末尾的 bc 换成省略号
        assertThat(excerpt).isEqualTo("a".repeat(79) + "\uD83D\uDE00" + "…");
        assertThat(excerpt.codePointCount(0, excerpt.length())).isEqualTo(81);
        assertThat(Character.isLowSurrogate(excerpt.charAt(excerpt.length() - 2))).isTrue();
    }

    // ------------------------------------------------------------------ 可见性

    @Test
    @DisplayName("已发布 + public：所有人都能看到")
    void publishedPublicIsVisibleToEveryone() {
        assertThat(PostQueryService.visibleTo(publishedPublic(), OTHER, NOW)).isTrue();
        assertThat(PostQueryService.visibleTo(publishedPublic(), AUTHOR, NOW)).isTrue();
    }

    @Test
    @DisplayName("已发布 + private：只有作者能看到")
    void publishedPrivateIsVisibleToAuthorOnly() {
        Post secret = post(PostService.STATUS_PUBLISHED, "private", AUTHOR, 0, null);
        assertThat(PostQueryService.visibleTo(secret, AUTHOR, NOW)).isTrue();
        assertThat(PostQueryService.visibleTo(secret, OTHER, NOW)).isFalse();
    }

    @Test
    @DisplayName("机审/人审中：作者可见，别人不可见（FR7.3 先发后审）")
    void pendingPostsAreVisibleToAuthorOnly() {
        for (String status : List.of(PostService.STATUS_MACHINE_REVIEW, PostService.STATUS_HUMAN_REVIEW)) {
            Post pending = post(status, "public", AUTHOR, 0, null);
            assertThat(PostQueryService.visibleTo(pending, AUTHOR, NOW)).as(status + " 作者自己").isTrue();
            assertThat(PostQueryService.visibleTo(pending, OTHER, NOW)).as(status + " 别人").isFalse();
        }
    }

    @Test
    @DisplayName("草稿与被拒：连作者本人也不会在广场上刷到")
    void draftAndRejectedAreInvisibleToEveryone() {
        for (String status : List.of(PostService.STATUS_DRAFT, PostService.STATUS_REJECTED)) {
            assertThat(PostQueryService.visibleTo(post(status, "public", AUTHOR, 0, null), AUTHOR, NOW))
                    .as(status).isFalse();
        }
    }

    @Test
    @DisplayName("树洞到期销毁后谁都看不到，作者也看不到（BR9）")
    void expiredHoleIsInvisibleEvenToAuthor() {
        Post alive = publishedPublic();
        alive.setAutoDestroyAt(NOW.plusMinutes(1));
        assertThat(PostQueryService.visibleTo(alive, AUTHOR, NOW)).isTrue();
        alive.setAutoDestroyAt(NOW);
        assertThat(PostQueryService.visibleTo(alive, AUTHOR, NOW)).as("到期时刻即不可见").isFalse();
        alive.setAutoDestroyAt(NOW.minusSeconds(1));
        assertThat(PostQueryService.isExpired(alive, NOW)).isTrue();
        assertThat(PostQueryService.visibleTo(alive, AUTHOR, NOW)).isFalse();
    }

    @Test
    @DisplayName("匿名不改变可见性：匿名公开帖照样给别人看，匿名私密帖照样只有作者")
    void anonymityDoesNotChangeVisibility() {
        Post anonPublic = post(PostService.STATUS_PUBLISHED, "public", AUTHOR, 1, 9L);
        Post anonPrivate = post(PostService.STATUS_PUBLISHED, "private", AUTHOR, 1, 9L);
        assertThat(PostQueryService.visibleTo(anonPublic, OTHER, NOW)).isTrue();
        assertThat(PostQueryService.visibleTo(anonPrivate, OTHER, NOW)).isFalse();
        assertThat(PostQueryService.visibleTo(anonPrivate, AUTHOR, NOW)).isTrue();
    }

    @Test
    @DisplayName("作者 id 为空的脏行不算属主，也不能因为 viewerId 恰好是 0 就放行")
    void nullAuthorIsNeverOwner() {
        Post orphan = post(PostService.STATUS_PUBLISHED, "private", null, 0, null);
        assertThat(PostQueryService.isOwner(orphan, 0L)).isFalse();
        assertThat(PostQueryService.visibleTo(orphan, 0L, NOW)).isFalse();
        assertThat(PostQueryService.visibleTo(publishedPublic(), 0L, NOW)).as("游客看公开帖仍可以").isTrue();
    }

    // ------------------------------------------------------------------ 待审提示

    @Test
    @DisplayName("auditTip 只对「作者本人」出现——拿 post.userId 自比自己恒真那句是隐私泄露")
    void auditTipOnlyForAuthor() {
        Post pending = post(PostService.STATUS_HUMAN_REVIEW, "public", AUTHOR, 0, null);
        assertThat(PostQueryService.auditTipOf(pending, AUTHOR, NOW)).isEqualTo(PostQueryService.AUDIT_TIP);
        assertThat(PostQueryService.auditTipOf(pending, OTHER, NOW)).isNull();
        assertThat(PostQueryService.auditTipOf(publishedPublic(), AUTHOR, NOW)).as("已发布不用提示").isNull();
        Post expired = post(PostService.STATUS_MACHINE_REVIEW, "public", AUTHOR, 0, null);
        expired.setAutoDestroyAt(NOW.minusMinutes(1));
        assertThat(PostQueryService.auditTipOf(expired, AUTHOR, NOW)).as("已销毁的行不该再挂提示").isNull();
    }

    // ------------------------------------------------------------------ 匿名与作者 id

    @Test
    @DisplayName("匿名帖一律不回 authorId：is_anonymous=1 或有 alias_id 都算匿名")
    void anonymousPostsNeverLeakAuthorId() {
        assertThat(PostQueryService.isAnonymous(post("PUBLISHED", "public", AUTHOR, 1, null))).isTrue();
        assertThat(PostQueryService.isAnonymous(post("PUBLISHED", "public", AUTHOR, 0, 9L))).isTrue();
        assertThat(PostQueryService.authorIdOf(post("PUBLISHED", "public", AUTHOR, 1, null))).isNull();
        assertThat(PostQueryService.authorIdOf(post("PUBLISHED", "public", AUTHOR, 0, 9L))).isNull();
        assertThat(PostQueryService.authorIdOf(publishedPublic())).isEqualTo(AUTHOR);
    }

    // ------------------------------------------------------------------ 展示名

    @Test
    @DisplayName("展示名四种来源各有兜底，任何一条都不能回落到真实昵称")
    void displayNamesFallBackSafely() {
        AnonymousAlias alias = new AnonymousAlias();
        alias.setId(9L);
        alias.setAliasName("匿名屿民·雾");
        Relations relations = new Relations(Map.of(), Map.of(),
                Map.of(AUTHOR, user(AUTHOR, "lisi", "李四")), Map.of(9L, alias));
        assertThat(PostQueryService.displayNameOf(post("PUBLISHED", "public", AUTHOR, 1, 9L), relations))
                .isEqualTo("匿名屿民·雾");
        assertThat(PostQueryService.displayNameOf(post("PUBLISHED", "public", AUTHOR, 1, 404L), relations))
                .as("马甲行被删也不回实名").isEqualTo(PostQueryService.ANONYMOUS_FALLBACK);
        assertThat(PostQueryService.displayNameOf(publishedPublic(), relations)).isEqualTo("李四");
        assertThat(PostQueryService.displayNameOf(post("PUBLISHED", "public", 999L, 0, null), relations))
                .as("作者注销后批量查查不到行").isEqualTo(PostQueryService.DELETED_AUTHOR);
        Relations noNickname = new Relations(Map.of(), Map.of(),
                Map.of(AUTHOR, user(AUTHOR, "lisi", "  ")), Map.of());
        assertThat(PostQueryService.displayNameOf(publishedPublic(), noNickname))
                .as("没设昵称回退登录名").isEqualTo("lisi");
    }

    // ------------------------------------------------------------------ 批量组装

    private static PostImage image(long postId, String url, int sort) {
        PostImage row = new PostImage();
        row.setPostId(postId);
        row.setUrl(url);
        row.setSort(sort);
        row.setWidth(800);
        row.setHeight(600);
        return row;
    }

    private static PostTopic link(long postId, Long topicId) {
        PostTopic row = new PostTopic();
        row.setPostId(postId);
        row.setTopicId(topicId);
        return row;
    }

    @Test
    @DisplayName("配图按帖分组并保持 SQL 的展示顺序；脏行（postId 为空）不进结果")
    void groupImagesKeepsSqlOrder() {
        Map<Long, List<ImageBrief>> grouped =
                PostQueryService.groupImages(List.of(image(2L, "b.png", 0), image(1L, "a1.png", 0),
                        image(1L, "a2.png", 1), image(1L, "a3.png", 2)));
        assertThat(grouped.keySet()).containsExactly(2L, 1L);
        assertThat(grouped.get(1L)).extracting(ImageBrief::url)
                .containsExactly("a1.png", "a2.png", "a3.png");
        assertThat(grouped.get(1L).get(0)).isEqualTo(new ImageBrief("a1.png", 800, 600));
        PostImage dirty = new PostImage();
        dirty.setUrl("orphan.png"); // postId 为空的脏行：分组里没有它的位置，也不该抛 NPE
        assertThat(PostQueryService.groupImages(List.of(dirty, image(3L, "y.png", 0))).keySet()).containsExactly(3L);
        assertThat(PostQueryService.groupImages(List.of())).isEmpty();
    }

    @Test
    @DisplayName("关联分组跳过缺 postId/topicId 的行；话题只留已过审的，顺序按发帖时")
    void topicLinksFilterUnapproved() {
        Map<Long, List<Long>> linkIds = PostQueryService.groupTopicIds(List.of(
                link(1L, 30L), link(1L, 10L), link(1L, null), link(2L, 20L)));
        assertThat(linkIds.get(1L)).containsExactly(30L, 10L);
        assertThat(linkIds.get(2L)).containsExactly(20L);

        Map<Long, Topic> topicById = new LinkedHashMap<>();
        topicById.put(10L, topic(10L, "学业压力", "APPROVED"));
        topicById.put(20L, topic(20L, "待审话题", "PENDING"));
        topicById.put(30L, topic(30L, "被下架话题", "REJECTED"));
        Map<Long, List<String>> names = PostQueryService.nameTopics(linkIds, topicById);
        assertThat(names.get(1L)).as("30 未过审被剔，只剩主话题之外的 10").containsExactly("学业压力");
        assertThat(names.get(2L)).as("话题未过审就整条不显示，不给它引流").isEmpty();
        assertThat(PostQueryService.nameTopics(Map.of(5L, List.of(77L)), topicById).get(5L)).isEmpty();
    }

    // ------------------------------------------------------------------ 入参归一

    @Test
    @DisplayName("type 过滤：不传与空白都是「全部」，白名单外报 10001")
    void typeFilterWhitelist() {
        assertThat(PostQueryService.normalizeTypeFilter(null)).isNull();
        assertThat(PostQueryService.normalizeTypeFilter("   ")).isNull();
        assertThat(PostQueryService.normalizeTypeFilter(" hole ")).isEqualTo("hole");
        assertThat(PostQueryService.normalizeTypeFilter("help")).isEqualTo("help");
        // 库里存的是小写枚举；不做大小写归一，免得「参数写错」被悄悄当成「查另一类」
        assertThatThrownBy(() -> PostQueryService.normalizeTypeFilter("HOLE"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ErrorCode.PARAM_INVALID));
        assertThatThrownBy(() -> PostQueryService.normalizeTypeFilter("moment"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ErrorCode.PARAM_INVALID));
    }

    @Test
    @DisplayName("计数列可能是 NULL（历史行），出参按 0 处理而不是抛 NPE")
    void nullCountersReadAsZero() {
        assertThat(PostQueryService.longValue(null)).isZero();
        assertThat(PostQueryService.intValue(null)).isZero();
        assertThat(PostQueryService.longValue(7)).isEqualTo(7L);
        assertThat(PostQueryService.intValue(9)).isEqualTo(9);
    }
}

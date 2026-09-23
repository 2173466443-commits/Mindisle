package com.mindisle.post;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mindisle.common.LikePattern;
import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.Post;
import com.mindisle.post.dto.PostListItem;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 站内搜索与关注流拼出来的 WHERE 形状（任务 3.9 / 3.17 · 手册 §6.1 行 3.9、3.17）。
 *
 * <p><b>这两条路径最值得测的不是「能不能搜到」，而是「有没有把别人不该看的东西搜出来」</b>。
 * 搜索框是一个新的读接口，它必须与广场共用同一条可见性判据；关注流是一条按作者集合收窄的读接口，
 * 它必须排除匿名与马甲帖——否则「我关注的某某」与「匿名屿民·晚风」会被同一个人同时看到，
 * 马甲等于自己脱了（需求 FR1.4）。这两件事都只存在于 SQL 文本里：Java 侧判得再对，
 * SQL 少拼一个条件就是数据库多返回一批行。</p>
 *
 * <p>与 {@code PostListSqlConditionTest} 同一手法：不需要 Spring 上下文也不需要数据库，
 * {@code TableInfoHelper.initTableInfo} 三行把列名解析缓存建起来，
 * {@code getSqlSegment()} 走的就是真实执行那份解析代码。这里主张的是「拼了什么」，
 * 「在真库上取回哪些行」由 {@code docs/smoke.mjs} 的 root SQL 取证，两层互不替代。</p>
 */
class PostSearchSqlConditionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 10, 0);
    private static final long USER = 11L;
    private static final String PH = "#{ew.paramNameValuePairs.";
    private static final String KW = "testkw";
    private static final String PATTERN = "%" + KW + "%";
    /** 话题名命中那段 EXISTS 的开头，两条通道共用一份（本类按子串断言，不重抄全文）。 */
    private static final String TOPIC_EXISTS = "EXISTS (SELECT 1 FROM post_topic pt JOIN topic t"
            + " ON t.id = pt.topic_id WHERE pt.post_id = post.id AND t.deleted = 0 AND t.name LIKE ";

    @BeforeAll
    static void initTableInfo() {
        // 少了这一步，getSqlSegment() 直接抛 "can not find lambda cache for this entity"
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Post.class);
    }

    /** 压掉换行与缩进：断言形状而不是排版，格式化代码不该把安全用例弄红。 */
    private static String sql(LambdaQueryWrapper<Post> wrapper) {
        return wrapper.getSqlSegment().replaceAll("\\s+", " ");
    }

    /** 参数是渲染 SQL 时才登记的：不先跑一遍 getSqlSegment() 就会拿到空 Map（本轮实测）。 */
    private static Map<String, Object> params(LambdaQueryWrapper<Post> wrapper) {
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs();
    }

    /**
     * 走私有方法拼「广场判据 + 作者谓词 + 关键词」的完整 wrapper。
     *
     * <p>为什么用反射而不调公开的 {@code search}：那条方法一进来就要 {@code postMapper.selectPage}，
     * 没数据库只能再塞替身 Mapper，而本类主张的只是拼出来的 SQL。反射在这里不是绕过封装，
     * 而是把「测的是拼条件、不是测翻页」这个取舍说清楚。</p>
     */
    @SuppressWarnings("unchecked")
    private static LambdaQueryWrapper<Post> keywordWrapper(String keyword, boolean fullText) throws Exception {
        PostQueryService service = new PostQueryService(null, null, null, null, null, null, null, null,
                new MindisleProperties());
        Method method = PostQueryService.class.getDeclaredMethod("keywordWrapper", long.class, String.class,
                LocalDateTime.class, String.class, boolean.class);
        method.setAccessible(true);
        return (LambdaQueryWrapper<Post>) method.invoke(service, USER, null, NOW, keyword, fullText);
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    // ---------------------------------------------------------------- 关键词命中（LIKE 通道）

    @Test
    @DisplayName("LIKE 通道命中三处：标题、正文、话题名，且三处都带 ESCAPE '!'")
    void likeChannelMatchesTitleContentAndTopicName() {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        PostQueryService.applyLikeMatch(wrapper, PATTERN);
        String sql = sql(wrapper);

        assertThat(sql).contains("title LIKE " + PH + "MPGENVAL1} ESCAPE '!'");
        assertThat(sql).contains("content LIKE " + PH + "MPGENVAL2} ESCAPE '!'");
        assertThat(sql).contains(TOPIC_EXISTS + PH + "MPGENVAL3} ESCAPE '!')");
        // 三处分支 = 两处 OR。出现第三处 OR 说明有人加了第四个命中列（比如 nickname），
        // 那会把「用昵称反查某人发过什么」变成现实，匿名帖的保护当场失效。
        assertThat(countOf(sql, " OR ")).isEqualTo(2);
        assertThat(sql).doesNotContain("nickname");
        // 写死 3 而不是「至少 1」：正是为了盯「某一处漏了 ESCAPE」——漏的那一处就是 % 能横扫全站的那一处
        assertThat(countOf(sql, "ESCAPE '!'")).isEqualTo(3);
        assertThat(params(wrapper)).hasSize(3)
                .containsEntry("MPGENVAL1", PATTERN)
                .containsEntry("MPGENVAL2", PATTERN)
                .containsEntry("MPGENVAL3", PATTERN);
    }

    @Test
    @DisplayName("话题名走 EXISTS 而不是 JOIN：一帖挂三个话题时不会把同一条帖复制成三行")
    void topicMatchIsAnExistsSubqueryNotAJoin() {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        PostQueryService.applyLikeMatch(wrapper, PATTERN);
        String sql = sql(wrapper);

        assertThat(sql).contains(TOPIC_EXISTS);
        // JOIN 直接拼在 post 上会造重复行，而 pageResult 的游标分支靠「多取一条」判 hasMore，
        // 重复行会让同一页里出现两次同一条帖，还会提前宣布「还有更多」
        assertThat(sql).doesNotContain("JOIN post_topic");
        // 裸 SQL 里 @TableLogic 管不到 topic，这一位只能手写；post_topic 本身没有删除列，所以只有 t.deleted
        assertThat(sql).contains("t.deleted = 0");
        // 但 post 自己的删除位不能被手拼进来（那会变成两处定义）
        assertThat(sql).doesNotContain("AND deleted = 0");
    }

    @Test
    @DisplayName("关键词只能作为参数出现：SQL 文本里搜不到用户输入的任何字符")
    void keywordReachesTheSqlOnlyAsABoundParameter() {
        String nasty = "100%_!x";
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        PostQueryService.applyLikeMatch(wrapper, LikePattern.contains(nasty));

        // 模式串本身正确（与 LikePatternTest 同一例，两边串起来才是「转义生效」的完整链条）
        assertThat(params(wrapper)).hasSize(3)
                .containsEntry("MPGENVAL1", "%100!%!_!!x%")
                .containsEntry("MPGENVAL2", "%100!%!_!!x%")
                .containsEntry("MPGENVAL3", "%100!%!_!!x%");
        // 而拼出来的 SQL 里只有占位符：注入面与「模式串漏进 SQL 文本」被同一条断言排除
        assertThat(sql(wrapper)).doesNotContain(nasty).doesNotContain("100");
    }

    @Test
    @DisplayName("关键词是 AND 上去的：命中表达式不会绕过广场判据")
    void keywordBranchIsAndedWithTheFeedPredicate() throws Exception {
        String sql = sql(keywordWrapper(KW, false));

        // 「) AND (title LIKE」——广场那一组先闭合，再 AND 上关键词那一组。
        // 若有人把它改成 .or(...)，这里会变成「OR (title LIKE」，搜索当场变成全站可读。
        assertThat(sql).contains(") AND (title LIKE ");
        assertThat(countOf(sql, " OR ")).isEqualTo(4); // 广场两支 + 到期销毁 + LIKE 内部两处
        assertThat(sql).contains(TOPIC_EXISTS);
    }

    @Test
    @DisplayName("搜帖复用广场那一条判据：搜索不是可见性的第二份实现")
    void searchStartsWithTheFeedPredicateVerbatim() throws Exception {
        LambdaQueryWrapper<Post> feedOnly = new LambdaQueryWrapper<>();
        PostQueryService.applyVisible(feedOnly, USER, null, NOW);
        String feed = sql(feedOnly);
        // getSqlSegment 会把整段包一层收尾括号；去掉它之后，广场 SQL 必须是搜索 SQL 的逐字前缀
        assertThat(feed).endsWith(")");
        feed = feed.substring(0, feed.length() - 1);

        String composed = sql(keywordWrapper(KW, false));
        assertThat(composed).startsWith(feed);
        // 反过来说明这两者的唯一差别就是最后叠上去的那一段，而不是「看起来很像的另一套条件」
        assertThat(composed.length()).isGreaterThan(feed.length());
    }

    @Test
    @DisplayName("作者 ACTIVE 谓词以带别名 EXISTS 出现：表名 user 必须加反引号")
    void authorActivePredicateIsRawSqlWithQuotedTable() {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        PostQueryService.applyAuthorActive(wrapper);

        assertThat(sql(wrapper)).isEqualTo("(EXISTS (SELECT 1 FROM `user` u WHERE u.id = post.user_id"
                + " AND u.deleted = 0 AND u.status = " + PH + "MPGENVAL1}))");
        assertThat(params(wrapper)).containsEntry("MPGENVAL1", PostQueryService.AUTHOR_ACTIVE);
        // 这条断言钉的是形状。它在今天恒真（开发库无注销账号），因此它不是「注销后搜不到」的证据——
        // 那一句的复验挂在任务 4.21；这里只保证等 T4.21 真往 user 表写 DELETED 那天，读侧不用再改。
    }

    // ---------------------------------------------------------------- 全文通道

    @Test
    @DisplayName("全文通道用 NATURAL LANGUAGE MODE：BOOLEAN MODE 会把用户输入的 + - ( ) 当查询语法")
    void fullTextChannelUsesNaturalLanguageMode() {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        PostQueryService.applyFullText(wrapper, KW, PATTERN);
        String sql = sql(wrapper);

        assertThat(sql).contains("MATCH(title, content) AGAINST (" + PH + "MPGENVAL1} IN NATURAL LANGUAGE MODE)");
        assertThat(sql).doesNotContain("IN BOOLEAN MODE");
        // 话题名那一侧仍是 LIKE：ft_title_content 只建在 post 的两列上，topic.name 上写 MATCH 是换个地方抛 1191
        assertThat(sql).contains(TOPIC_EXISTS + PH + "MPGENVAL2} ESCAPE '!')");
        // 关键差别：给 MATCH 的是原词（不是 %kw%），给话题 LIKE 的才是模式串
        assertThat(params(wrapper)).hasSize(2)
                .containsEntry("MPGENVAL1", KW)
                .containsEntry("MPGENVAL2", PATTERN);
    }

    @Test
    @DisplayName("两条通道只差最后一段：广场判据与作者谓词不因通道而变")
    void bothChannelsShareTheVisibilityPredicate() throws Exception {
        String like = sql(keywordWrapper(KW, false));
        String fullText = sql(keywordWrapper(KW, true));

        // 两条通道各自「广场组 + 到期 + 作者 ACTIVE」这一段必须逐字相同
        String likeShared = like.substring(0, like.indexOf("AND (title LIKE "));
        String fullTextShared = fullText.substring(0, fullText.indexOf("AND (MATCH(title, content)"));
        assertThat(likeShared).isEqualTo(fullTextShared);
        assertThat(likeShared).contains("EXISTS (SELECT 1 FROM `user` u");
    }

    // ---------------------------------------------------------------- 关注流

    @Test
    @DisplayName("关注流收窄到公开实名帖：匿名与马甲帖一条都不进（FR1.4）")
    void followingFeedExcludesAnonymousAndAliasRows() {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        PostQueryService.applyFollowingFeed(wrapper, List.of(7L, 8L), NOW);
        String sql = sql(wrapper);

        assertThat(sql).contains("user_id IN (" + PH + "MPGENVAL1}," + PH + "MPGENVAL2})");
        assertThat(sql).contains("status = " + PH + "MPGENVAL3}");
        assertThat(sql).contains("visibility = " + PH + "MPGENVAL4}");
        assertThat(sql).contains("(is_anonymous IS NULL OR is_anonymous = " + PH + "MPGENVAL5})");
        assertThat(sql).contains("alias_id IS NULL");
        assertThat(sql).contains("EXISTS (SELECT 1 FROM `user` u");
        assertThat(sql).contains("(auto_destroy_at IS NULL OR auto_destroy_at > " + PH + "MPGENVAL7})");
        assertThat(params(wrapper)).hasSize(7)
                .containsEntry("MPGENVAL1", 7L)
                .containsEntry("MPGENVAL2", 8L)
                .containsEntry("MPGENVAL3", PostService.STATUS_PUBLISHED)
                .containsEntry("MPGENVAL4", PostQueryService.VISIBILITY_PUBLIC)
                .containsEntry("MPGENVAL5", 0)
                .containsEntry("MPGENVAL6", PostQueryService.AUTHOR_ACTIVE)
                .containsEntry("MPGENVAL7", NOW);
        // 与公开主页同一条判据：两处一旦分别演化，就会出现「主页看不到的帖在关注流里刷到」
        assertThat(sql).doesNotContain("post.deleted");
    }

    @Test
    @DisplayName("没关注任何人时一条 SQL 都不发：新人首屏不是 500 而是一句空态")
    void emptyAuthorListSendsNoQuery() {
        // 九个依赖全给 null：following 只要真走到 postMapper 那一行就会 NPE，
        // 所以「它没炸」本身就是「它没发 SQL」的证明——比塞一个假 Mapper 更直接。
        PostQueryService service = new PostQueryService(null, null, null, null, null, null, null, null,
                new MindisleProperties());

        PageResult<PostListItem> emptyList = service.following(USER, List.of(), null, NOW);
        assertThat(emptyList.getList()).isEmpty();
        assertThat(emptyList.isHasMore()).isFalse();
        assertThat(emptyList.getTotal()).isZero();
        assertThat(emptyList.getNextCursor()).isNull();

        // null 与空集合同一处理：上游 Mapper 返回 null 不该变成 500
        assertThat(service.following(USER, null, new PageQuery(), NOW).getList()).isEmpty();
    }

    @Test
    @DisplayName("作者上限只有一处定义：Service 不再二次截断")
    void authorCapIsDeclaredOnce() throws Exception {
        assertThat(PostQueryService.FOLLOWING_AUTHOR_CAP).isEqualTo(500);
        // 关注流的取数入参是 List<Long>，如果 Service 里还藏着第二处 limit，签名里会多一个 int/Integer
        Method method = PostQueryService.class.getDeclaredMethod("following", long.class, List.class,
                PageQuery.class, LocalDateTime.class);
        assertThat(method.getParameterTypes()).containsExactly(long.class, List.class, PageQuery.class,
                LocalDateTime.class);
    }
}

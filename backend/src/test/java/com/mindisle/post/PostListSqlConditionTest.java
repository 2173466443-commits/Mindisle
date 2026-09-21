package com.mindisle.post;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mindisle.entity.Post;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三种列表各自的 WHERE 形状（任务 3.13 第二批 · 手册 §6.2 U11/U12）。
 *
 * <p><b>为什么光测 {@code visibleTo} 不够，连拼出来的 SQL 也要测</b>：内存判据管的是「详情读得到读不到」，
 * 列表的正确性还取决于数据库到底返回了哪些行。以「他人主页不得出现匿名帖」为例，如果只在 Java 侧判对、
 * SQL 侧漏掉，表面结果仍然正确（多取回来的行被过滤掉了），但任何一条不走 {@code toListItems} 的新调用点
 * 都会把匿名帖原样漏出去 —— 那是需求 FR1.4 的解匿事故。这种「现在对、改一下就错」的口径必须钉在 SQL 层。</p>
 *
 * <p><b>不需要 Spring 上下文也不需要数据库</b>：{@code LambdaQueryWrapper#getSqlSegment()} 就是生成条件的地方，
 * 只要实体的 TableInfo 在缓存里，{@code Post::getUserId} 就能解析成 {@code user_id}，走的是与真实执行同一份解析代码。
 * 所以这里主张的只是「拼了什么」；「拼出来的东西在真库上取回哪些行」交给 {@code docs/smoke.mjs} 的真 HTTP
 * 加 root 直连 SQL 取证 —— 两层互为证据，不互相替代。</p>
 */
class PostListSqlConditionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 10, 0);
    private static final long USER = 11L;
    private static final String PH = "#{ew.paramNameValuePairs.";

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

    @Test
    @DisplayName("他人主页 SQL 四重收窄：user_id + PUBLISHED + public + 既非匿名也无马甲 id")
    void publicProfileSqlExcludesAnonymousRows() {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        PostQueryService.applyPublicProfile(wrapper, USER, NOW);

        assertThat(sql(wrapper)).contains("user_id = " + PH + "MPGENVAL1}");
        assertThat(sql(wrapper)).contains("status = " + PH + "MPGENVAL2}");
        assertThat(sql(wrapper)).contains("visibility = " + PH + "MPGENVAL3}");
        // 与 isAnonymous() 严格取反：NULL 与 0 都算非匿名，否则历史行（列可为空）会整片消失
        assertThat(sql(wrapper)).contains("(is_anonymous IS NULL OR is_anonymous = ");
        assertThat(sql(wrapper)).contains("alias_id IS NULL");
        assertThat(sql(wrapper)).contains("(auto_destroy_at IS NULL OR auto_destroy_at > ");
        assertThat(wrapper.getParamNameValuePairs().values()).containsExactlyInAnyOrder(
                USER, PostService.STATUS_PUBLISHED, PostQueryService.VISIBILITY_PUBLIC, 0, NOW);
    }

    @Test
    @DisplayName("公开主页判据的签名里只有一个 long：本人大一点这种事在这条路上构造不出来")
    void publicProfileSignatureHasNoViewerIdSlot() throws Exception {
        Method method = PostQueryService.class.getDeclaredMethod("applyPublicProfile",
                LambdaQueryWrapper.class, long.class, LocalDateTime.class);
        assertThat(Arrays.asList(method.getParameterTypes()))
                .containsExactly(LambdaQueryWrapper.class, long.class, LocalDateTime.class);
        // 看着像形式主义，实际是主页语义唯一的护栏：一旦有人为了「自己也看看待审与匿名」
        // 往这里补一个 viewerId，上面那条 SQL 断言依旧全绿，而解匿已经发生。
        assertThat(Arrays.asList(method.getParameterTypes())).containsOnlyOnce(long.class);
    }

    @Test
    @DisplayName("我的帖子 SQL 只按 user_id 收窄：不拼 visibility，才看得见私密；不拼匿名条件，才找得回树洞")
    void mineSqlIgnoresThePublicVisibilityFilter() {
        LambdaQueryWrapper<Post> all = new LambdaQueryWrapper<>();
        PostQueryService.applyOwned(all, USER, null, NOW);

        assertThat(sql(all)).isEqualTo("(user_id = " + PH + "MPGENVAL1}"
                + " AND (auto_destroy_at IS NULL OR auto_destroy_at > " + PH + "MPGENVAL2}))");
        assertThat(sql(all)).doesNotContain("visibility");
        assertThat(sql(all)).doesNotContain("is_anonymous");

        LambdaQueryWrapper<Post> published = new LambdaQueryWrapper<>();
        PostQueryService.applyOwned(published, USER, PostService.STATUS_PUBLISHED, NOW);
        assertThat(sql(published)).contains("status = ");
        assertThat(published.getParamNameValuePairs().values())
                .containsExactlyInAnyOrder(USER, PostService.STATUS_PUBLISHED, NOW);
    }

    @Test
    @DisplayName("广场 SQL 仍是两支：公共已发布 OR 自己的待审（私密已发布不混进公共流）")
    void feedSqlKeepsExactlyTwoBranches() {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        PostQueryService.applyVisible(wrapper, USER, null, NOW);

        assertThat(sql(wrapper)).startsWith("(((status = ");
        assertThat(sql(wrapper)).contains(") OR (user_id = ");
        assertThat(sql(wrapper)).contains("status IN (");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(
                PostService.STATUS_PUBLISHED, PostQueryService.VISIBILITY_PUBLIC, USER,
                PostService.STATUS_MACHINE_REVIEW, PostService.STATUS_HUMAN_REVIEW);
        // public 只出现一次 = 第二支没有偷偷放宽成「自己的全部 public/private」。
        // 混进来的话广场会变成个人回收站，「刷到已读三遍的旧帖」成为常态（详见 dev-log 产品边界一条）。
        assertThat(wrapper.getParamNameValuePairs().values().stream()
                .filter(PostQueryService.VISIBILITY_PUBLIC::equals).count()).isEqualTo(1L);
        // type 不传就不拼这一列，传了才收窄
        assertThat(sql(wrapper)).doesNotContain("type = ");
        LambdaQueryWrapper<Post> typed = new LambdaQueryWrapper<>();
        PostQueryService.applyVisible(typed, USER, "hole", NOW);
        assertThat(sql(typed)).contains("type = ");
    }

    @Test
    @DisplayName("三个列表都不手拼 deleted：逻辑删除位由 @TableLogic 统一追加，只改一处才生效")
    void noneOfThemHandRollsTheLogicDeleteFlag() {
        LambdaQueryWrapper<Post> feed = new LambdaQueryWrapper<>();
        PostQueryService.applyVisible(feed, USER, null, NOW);
        LambdaQueryWrapper<Post> mine = new LambdaQueryWrapper<>();
        PostQueryService.applyOwned(mine, USER, null, NOW);
        LambdaQueryWrapper<Post> profile = new LambdaQueryWrapper<>();
        PostQueryService.applyPublicProfile(profile, USER, NOW);

        for (LambdaQueryWrapper<Post> wrapper : List.of(feed, mine, profile)) {
            assertThat(sql(wrapper).toLowerCase()).doesNotContain("deleted");
        }
    }

    @Test
    @DisplayName("游标条件含 published_at IS NULL 一支：否则作者那一页永远刷不到待审帖")
    void cursorSqlKeepsNullPublishedAtRows() {
        Post anchor = new Post();
        anchor.setPublishedAt(NOW);
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        PostQueryService.applyCursor(wrapper, anchor, 50L);

        assertThat(sql(wrapper)).isEqualTo("(((published_at < " + PH + "MPGENVAL1})"
                + " OR published_at IS NULL"
                + " OR (published_at = " + PH + "MPGENVAL2} AND id < " + PH + "MPGENVAL3})))");
        assertThat(wrapper.getParamNameValuePairs().values())
                .containsExactlyInAnyOrder(NOW, NOW, 50L);

        // 锚点行取不到（已删/已销毁/并发丢失）时退化成纯 id 游标：宁可不重叠，也别报错或返回全表
        LambdaQueryWrapper<Post> plain = new LambdaQueryWrapper<>();
        PostQueryService.applyCursor(plain, null, 50L);
        assertThat(sql(plain)).isEqualTo("(id < " + PH + "MPGENVAL1})");
    }
}

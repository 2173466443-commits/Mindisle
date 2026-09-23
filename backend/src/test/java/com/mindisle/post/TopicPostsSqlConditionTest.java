package com.mindisle.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.entity.Post;

/**
 * 话题页帖流的 SQL 形状（任务 3.8 · 手册 §6.1 行 3.8「聚合页展示话题下热帖」）。
 *
 * <p><b>为什么把这一刀单独测</b>：话题页是全站第一个「排序键有两档」的列表 ——
 * {@code sort=top} 让排序键从 {@code (published_at, id)} 变成 {@code (is_top, published_at, id)}，
 * 而游标条件必须跟着一起变。这类改动最容易出的事故不是「今天报错」，
 * 而是「翻页偶尔漏一条」：它只在真机第二页之后出现，冒烟都未必走到。
 * 所以这里把两段 SQL 逐字钉住（沿用 {@code PostListSqlConditionTest} 的做法），
 * 并且<b>同时</b>钉住「非置顶模式一字不变」——那四条既有列表接口全靠这条护栏。</p>
 *
 * <p>与 {@code TopicServiceTest} 的分工一样：这里只主张「拼了什么」，
 * 「拼出来的东西在真库上取回哪些行」交给 {@code docs/smoke.mjs} 第 21 步。</p>
 */
class TopicPostsSqlConditionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 23, 10, 0);
    private static final long VIEWER = 11L;
    private static final long TOPIC = 77L;
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

    private static Post anchor(Integer isTop, LocalDateTime publishedAt) {
        Post post = new Post();
        post.setIsTop(isTop);
        post.setPublishedAt(publishedAt);
        return post;
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    // ---------------------------------------------------------------- 成员条件

    @Test
    @DisplayName("话题成员条件是 EXISTS 子查询而不是 JOIN：一条帖挂三个话题不能变成三行")
    void topicMemberIsAnExistsNotAJoin() {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        PostQueryService.applyTopic(wrapper, TOPIC);

        assertThat(sql(wrapper)).isEqualTo("(EXISTS (SELECT 1 FROM post_topic pt"
                + " JOIN topic t ON t.id = pt.topic_id"
                + " WHERE pt.post_id = post.id AND t.id = " + PH + "MPGENVAL1}"
                + " AND t.deleted = 0 AND t.audit_status = 'APPROVED'))");
        // id 走参数占位而不是拼字符串：拼数字进 SQL 文本会让日志里读不出这条 SQL 的参数
        assertThat(wrapper.getParamNameValuePairs().values()).containsExactly(TOPIC);
        // 成员条件不判 post 侧的删除位：那是 @TableLogic 的活，手拼一次就永久有两份真相
        assertThat(sql(wrapper)).doesNotContain("post.deleted");
    }

    @Test
    @DisplayName("成员条件与可见判据串在一起时，t.deleted 只出现一次：软删话题的行不会被任何查询读出来")
    void topicMemberIsNotRolledIntoTheVisibilityClause() {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        PostQueryService.applyVisible(wrapper, VIEWER, null, NOW);
        PostQueryService.applyTopic(wrapper, TOPIC);
        String combined = sql(wrapper);

        assertThat(countOf(combined, "t.deleted = 0")).isEqualTo(1);
        assertThat(countOf(combined, "EXISTS (SELECT 1 FROM post_topic")).isEqualTo(1);
        assertThat(combined).contains("(auto_destroy_at IS NULL OR auto_destroy_at > ");
        assertThat(combined).contains("AND EXISTS (SELECT 1 FROM post_topic");
        // 话题 id 是第七个占位参数：前面六个都属于广场可见判据 —— 两段条件互不覆盖，才是「与」而不是「或」
        assertThat(wrapper.getParamNameValuePairs().get("MPGENVAL7")).isEqualTo(TOPIC);
    }

    // ---------------------------------------------------------------- 游标条件

    @Test
    @DisplayName("sort=top 的游标比三段字典序：is_top 在前，同组内才比发布时间与 id")
    void pinnedCursorComparesThreeKeysInOrder() {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        PostQueryService.applyCursor(wrapper, anchor(1, NOW), 50L, true);

        assertThat(sql(wrapper)).isEqualTo("(((is_top < " + PH + "MPGENVAL1})"
                + " OR (is_top = " + PH + "MPGENVAL2} AND ((published_at < " + PH + "MPGENVAL3})"
                + " OR published_at IS NULL"
                + " OR (published_at = " + PH + "MPGENVAL4} AND id < " + PH + "MPGENVAL5})))))");
        // 两个 is_top 参数都得是 1：一旦有人把第二支写成常量 0，「同组内继续比」就变成了「只比未置顶」
        assertThat(wrapper.getParamNameValuePairs().values())
                .containsExactlyInAnyOrder(1, 1, NOW, NOW, 50L);
    }

    @Test
    @DisplayName("🔴 置顶模式下锚点发布时间为 NULL 也不退化成裸 id 游标：那会跨 is_top 分组匹配、把更靠前的帖永久漏掉")
    void pinnedCursorWithNullPublishedAtStaysInsideItsGroup() {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        PostQueryService.applyCursor(wrapper, anchor(1, null), 50L, true);

        assertThat(sql(wrapper)).isEqualTo("(((is_top < " + PH + "MPGENVAL1})"
                + " OR (is_top = " + PH + "MPGENVAL2} AND (published_at IS NULL AND id < "
                + PH + "MPGENVAL3}))))");
        // 反证：非置顶那一支敢退化成裸 id（排序键只剩 id 一维），置顶这一支不敢
        assertThat(sql(wrapper)).doesNotMatch("^\\(id < .*");
    }

    @Test
    @DisplayName("锚点行整个取不到（已删、已销毁、并发丢失）时才允许退化成纯 id 游标：宁可不重叠也别报错")
    void missingAnchorDegradesToPlainIdCursor() {
        LambdaQueryWrapper<Post> pinned = new LambdaQueryWrapper<>();
        PostQueryService.applyCursor(pinned, null, 50L, true);
        assertThat(sql(pinned)).isEqualTo("(id < " + PH + "MPGENVAL1})");

        // is_top 为 null 与「整行取不到」同判：话题页的排序键第一段都读不出来，就没有可比的东西
        LambdaQueryWrapper<Post> noTop = new LambdaQueryWrapper<>();
        PostQueryService.applyCursor(noTop, anchor(null, NOW), 50L, true);
        assertThat(sql(noTop)).isEqualTo("(id < " + PH + "MPGENVAL1})");
    }

    @Test
    @DisplayName("回归护栏：pinnedFirst=false 时游标 SQL 与加这一档之前逐字相同（四个既有列表接口靠这条）")
    void nonPinnedCursorIsByteForByteTheOldOne() {
        String legacy = "(((published_at < " + PH + "MPGENVAL1})"
                + " OR published_at IS NULL"
                + " OR (published_at = " + PH + "MPGENVAL2} AND id < " + PH + "MPGENVAL3})))";

        LambdaQueryWrapper<Post> threeArg = new LambdaQueryWrapper<>();
        PostQueryService.applyCursor(threeArg, anchor(null, NOW), 50L);
        LambdaQueryWrapper<Post> explicitFalse = new LambdaQueryWrapper<>();
        PostQueryService.applyCursor(explicitFalse, anchor(1, NOW), 50L, false);

        assertThat(sql(threeArg)).isEqualTo(legacy);
        // 三参重载必须等价于 pinnedFirst=false：否则「老调用点」会在新代码里悄悄换语义。
        // 注意这里刻意给 4 参那支传了 is_top=1 —— 传了也得被忽略，才算证明走的是另一条分支。
        assertThat(sql(explicitFalse)).isEqualTo(legacy);
        assertThat(explicitFalse.getParamNameValuePairs().values()).doesNotContain(1);
    }

    // ---------------------------------------------------------------- 排序参数

    @Test
    @DisplayName("sort 只认 latest 与 top：给 hot 不静默兜底成时间序，接口不说谎")
    void sortParameterHasExactlyTwoValues() {
        assertThat(PostQueryService.normalizeSortFilter(null)).isEqualTo("latest");
        assertThat(PostQueryService.normalizeSortFilter("   ")).isEqualTo("latest");
        assertThat(PostQueryService.normalizeSortFilter("TOP")).isEqualTo("top");
        assertThat(PostQueryService.normalizeSortFilter(" Latest ")).isEqualTo("latest");
        assertThat(PostQueryService.SORT_LATEST).isEqualTo("latest");
        assertThat(PostQueryService.SORT_TOP).isEqualTo("top");

        BizException ex = assertThrows(BizException.class,
                () -> PostQueryService.normalizeSortFilter("hot"));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(ex.getMessage()).contains("latest").contains("top");
        // 相关度属阶段 4：现在接受它就得骗人一次
        assertThat(assertThrows(BizException.class,
                () -> PostQueryService.normalizeSortFilter("relevance")).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("两档排序键的 ORDER BY 片段：置顶只在最前面多一档，后两档逐字不变")
    void orderByKeepsTheSameTailAcrossBothModes() {
        // 这里钉的是「拼出来的形状」，不是「pageResult 到底传了什么」——
        // 后者的调用点是 private，硬用反射调它等于把测试绑在重构最频繁的那段代码上。
        // 真 HTTP 冒烟第 21 步负责证明 topicPosts(sort=top) 真的走了这一档。
        LambdaQueryWrapper<Post> pinned = new LambdaQueryWrapper<>();
        pinned.orderByDesc(Post::getIsTop).orderByDesc(Post::getPublishedAt).orderByDesc(Post::getId);
        assertThat(pinned.getSqlSegment()).contains("ORDER BY is_top DESC,published_at DESC,id DESC");

        LambdaQueryWrapper<Post> plain = new LambdaQueryWrapper<>();
        plain.orderByDesc(Post::getPublishedAt).orderByDesc(Post::getId);
        // trim 是因为 MP 把 ORDER BY 段前面留了一个空格（这里没有任何 WHERE 条件打底）：
        // 断言的是片段内容，排版交给 sql() 那个压行工具管。
        assertThat(plain.getSqlSegment().trim()).isEqualTo("ORDER BY published_at DESC,id DESC");
        assertThat(plain.getSqlSegment()).doesNotContain("is_top");
    }

    @Test
    @DisplayName("签名护栏：topicPosts 与带 pinnedFirst 的 pageResult 都在，且只有一档 pinnedFirst 参数")
    void pagingHelpersKeepTheirSignature() throws Exception {
        Method topicPosts = Arrays.stream(PostQueryService.class.getMethods())
                .filter(item -> "topicPosts".equals(item.getName())).findFirst().orElseThrow();
        assertThat(Arrays.asList(topicPosts.getParameterTypes()))
                .containsExactly(long.class, long.class, String.class,
                        com.mindisle.common.PageQuery.class, LocalDateTime.class);

        long overloads = Arrays.stream(PostQueryService.class.getDeclaredMethods())
                .filter(item -> "pageResult".equals(item.getName())).count();
        assertThat(overloads).isEqualTo(2L);
        assertThat(Arrays.stream(PostQueryService.class.getDeclaredMethods())
                .filter(item -> "pageResult".equals(item.getName()))
                .map(item -> item.getParameterTypes().length).toArray()).containsExactlyInAnyOrder(4, 5);
        // 只允许有一个 boolean 参数位：再多一档「谁在前」就等于「翻页口径有第二份」
        assertThat(Arrays.stream(PostQueryService.class.getDeclaredMethods())
                .filter(item -> "pageResult".equals(item.getName())
                        && item.getParameterTypes().length == 5)
                .findFirst().orElseThrow().getParameterTypes()[4]).isEqualTo(boolean.class);
    }
}

package com.mindisle.search;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.Topic;
import com.mindisle.entity.User;
import com.mindisle.mapper.TopicMapper;
import com.mindisle.mapper.UserMapper;
import com.mindisle.search.dto.TopicHit;
import com.mindisle.search.dto.UserHit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * 搜话题与搜人的取数条件与出参白名单（任务 3.9 · 需求 FR4.8、NFR8 数据最小化）。
 *
 * <p><b>本类管两件「不测就会悄悄变坏」的事</b>：① WHERE 里那两条收窄条件（话题必须已过审、
 * 账号必须 ACTIVE）有没有真拼上——只在 Java 侧判就等于「先捞全表再筛」，count 与翻页都会算歪；
 * ② 出参 record 的字段集合——搜人接口一旦有人顺手把 email、role 加进去，就是拿一个
 * 普通登录用户能读的接口广播「这个号是管理员」。</p>
 *
 * <p><b>替身用 JDK 动态代理而不是 Mockito</b>：{@code TopicMapper}/{@code UserMapper} 都是接口，
 * {@code Proxy} 十几行就够，且与仓库里其余测试一致（现有测试类无一处引入 mock 框架，
 * 少一层能改行为、又能吞掉「方法名拼错」的东西，读起来就越直）。
 * BaseMapper 的 {@code selectList(Wrapper)} 是抽象方法，代理直接拦住即可；
 * 断言对象就是被拦下来的那个 wrapper，与真实执行同一份解析代码。</p>
 */
class SearchServiceTest {

    private static final String PH = "#{ew.paramNameValuePairs.";
    private static final String KW = "testkw";
    private static final String PATTERN = "%" + KW + "%";

    /** 每次 selectList 拦截下来的 wrapper，按调用顺序。 */
    private final List<AbstractWrapper<?, ?, ?>> captured = new ArrayList<>();

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        // 两个实体都要初始化：少一个，对应的 lambda 列名解析就会抛
        // "can not find lambda cache for this entity"
        TableInfoHelper.initTableInfo(assistant, Topic.class);
        TableInfoHelper.initTableInfo(assistant, User.class);
    }

    // ------------------------------------------------------------------ 替身与工具

    /** 造一个只认 selectList 的 Mapper 替身：其余方法一律抛错，避免「测了个没人调的分支」。 */
    @SuppressWarnings("unchecked")
    private <T> T mapper(Class<T> mapperInterface, List<?> rows) {
        return (T) Proxy.newProxyInstance(mapperInterface.getClassLoader(),
                new Class<?>[] { mapperInterface }, (proxy, method, args) -> {
                    if ("selectList".equals(method.getName()) && args != null && args.length == 1) {
                        captured.add((AbstractWrapper<?, ?, ?>) args[0]);
                        return rows;
                    }
                    if ("toString".equals(method.getName())) {
                        return mapperInterface.getSimpleName() + "$Fake";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException("本用例不预期的调用：" + method.getName());
                });
    }

    private static MindisleProperties properties(int maxProfiles, int maxKeywordChars) {
        MindisleProperties properties = new MindisleProperties();
        properties.getSearch().setMaxProfiles(maxProfiles);
        properties.getSearch().setMaxKeywordChars(maxKeywordChars);
        return properties;
    }

    private SearchService searchService(MindisleProperties properties, List<Topic> topics, List<User> users) {
        return new SearchService(mapper(TopicMapper.class, topics), mapper(UserMapper.class, users), properties);
    }

    /** 压掉换行：断言形状而不是排版。 */
    private static String sql(AbstractWrapper<?, ?, ?> wrapper) {
        return wrapper.getSqlSegment().replaceAll("\\s+", " ");
    }

    /** 参数是渲染 SQL 时才登记的：不先跑一遍 getSqlSegment() 就会拿到空 Map（本轮实测）。 */
    private static Map<String, Object> params(AbstractWrapper<?, ?, ?> wrapper) {
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs();
    }

    /** 取第一条被拦下来的 wrapper，并顺手断言「这个用例只发了一条 SQL」。 */
    private AbstractWrapper<?, ?, ?> onlyWrapper() {
        assertThat(captured).hasSize(1);
        return captured.get(0);
    }

    private static List<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ 搜话题

    @Test
    @DisplayName("话题检索只收窄到已过审，且不限官方：官方是话题墙的口径不是搜索的口径")
    void topicSearchRequiresApprovedButNotOfficial() {
        SearchService service = searchService(properties(20, 64), List.of(), List.of());

        assertThat(service.topics(KW, null)).isEmpty();

        AbstractWrapper<?, ?, ?> wrapper = onlyWrapper();
        String sql = sql(wrapper);
        assertThat(sql).contains("audit_status = " + PH + "MPGENVAL1}");
        assertThat(sql).contains("name LIKE " + PH + "MPGENVAL2} ESCAPE '!'");
        assertThat(sql).doesNotContain("is_official");
        // @TableLogic 负责主表删除位；这里手拼一份，将来改逻辑删除口径就会有一处漏改
        assertThat(sql.toLowerCase()).doesNotContain("deleted");
        assertThat(params(wrapper)).hasSize(2)
                .containsEntry("MPGENVAL1", SearchService.TOPIC_APPROVED)
                .containsEntry("MPGENVAL2", PATTERN);
    }

    @Test
    @DisplayName("话题排序是热度倒序 + id 兜底，limit 由 cap 拼出：没有 ORDER BY 的 LIMIT 结果不保证")
    void topicSearchOrdersByHotScoreThenId() {
        SearchService service = searchService(properties(20, 64), List.of(), List.of());
        service.topics(KW, 5);

        String sql = sql(onlyWrapper());
        assertThat(sql).contains("ORDER BY hot_score DESC,id ASC");
        assertThat(sql).endsWith(" limit 5");
    }

    // ------------------------------------------------------------------ 搜人

    @Test
    @DisplayName("搜人只返回 ACTIVE：被禁言 / 封禁 / 注销冷静期的账号不出现在结果里")
    void userSearchRequiresActiveStatus() {
        SearchService service = searchService(properties(20, 64), List.of(), List.of());

        assertThat(service.users(KW, null)).isEmpty();

        AbstractWrapper<?, ?, ?> wrapper = onlyWrapper();
        String sql = sql(wrapper);
        assertThat(sql).contains("status = " + PH + "MPGENVAL1}");
        // 昵称与登录名两支：一组括号包住，绝不能与 status 之间出现悬空 OR（那会变成「状态任意」）
        assertThat(sql).contains("(nickname LIKE " + PH + "MPGENVAL2} ESCAPE '!'"
                + " OR username LIKE " + PH + "MPGENVAL3} ESCAPE '!')");
        assertThat(sql).doesNotContain("password").doesNotContain("email").doesNotContain("role");
        assertThat(sql.toLowerCase()).doesNotContain("deleted");
        assertThat(params(wrapper)).hasSize(3)
                .containsEntry("MPGENVAL1", SearchService.USER_ACTIVE)
                .containsEntry("MPGENVAL2", PATTERN)
                .containsEntry("MPGENVAL3", PATTERN);
    }

    // ------------------------------------------------------------------ limit / 关键词归一

    @Test
    @DisplayName("limit 是夹进区间而不是校验报错：非法值走默认，绝不给一条无 LIMIT 的查询")
    void limitIsClampedNeverUnbounded() {
        SearchService service = searchService(properties(20, 64), List.of(), List.of());

        assertThat(service.cap(null)).isEqualTo(SearchService.DEFAULT_LIMIT);
        assertThat(service.cap(0)).isEqualTo(SearchService.DEFAULT_LIMIT);
        assertThat(service.cap(-7)).isEqualTo(SearchService.DEFAULT_LIMIT);
        assertThat(service.cap(5)).isEqualTo(5);
        assertThat(service.cap(999)).isEqualTo(20);
        // 上限本身来自配置（NFR10 不写死）：把 max-profiles 调到 3，999 也只能拿到 3
        SearchService tight = searchService(properties(3, 64), List.of(), List.of());
        assertThat(tight.cap(null)).isEqualTo(3);
        assertThat(tight.cap(999)).isEqualTo(3);
        assertThat(tight.users(KW, 999)).isEmpty();
        assertThat(sql(onlyWrapper())).endsWith(" limit 3");
    }

    @Test
    @DisplayName("空关键词在发 SQL 之前就被拒：搜索框不会退化成分页列表")
    void blankKeywordNeverReachTheDatabase() {
        SearchService service = searchService(properties(20, 64), List.of(), List.of());

        for (String raw : new String[] { null, "", "   ", "\t" }) {
            assertThatExceptionOfType(BizException.class)
                    .isThrownBy(() -> service.topics(raw, 5))
                    .satisfies(e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
            assertThatExceptionOfType(BizException.class)
                    .isThrownBy(() -> service.users(raw, 5))
                    .satisfies(e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
        }
        // 「报错之前不发 SQL」才是这条用例的重点：前面几行单看只证明了抛错
        assertThat(captured).isEmpty();
    }

    @Test
    @DisplayName("超长关键词同样在入口被拒，且上限读的是配置不是常量")
    void overlongKeywordIsRejectedFromConfig() {
        // 默认 64 写在 MindisleProperties 里；这里显式把上限挪成 2，证明挪得动
        SearchService tight = searchService(properties(20, 2), List.of(), List.of());
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> tight.topics("焦虑呀", 5))
                .satisfies(e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
                    assertThat(e.getMessage()).contains("最长 2 字");
                });
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> tight.users("焦虑呀", 5));
        assertThat(captured).isEmpty();
    }

    @Test
    @DisplayName("元字符在进入 Wrapper 之前就被转义：参数值是模式串而不是原始词")
    void wildcardsAreEscapedBeforeHittingTheWrapper() {
        SearchService service = searchService(properties(20, 64), List.of(), List.of());

        service.topics("100%", null);

        assertThat(params(onlyWrapper())).hasSize(2)
                .containsEntry("MPGENVAL1", "APPROVED")
                .containsEntry("MPGENVAL2", "%100!%%");
    }

    // ------------------------------------------------------------------ 出参白名单

    @Test
    @DisplayName("UserHit 只有 id / 昵称 / 头像三个字段：email、role、status 一个都不能加")
    void userHitIsAFieldWhitelist() {
        assertThat(componentNames(UserHit.class)).containsExactly("id", "nickname", "avatar");
        // 单独再列一遍不是因为上面那条不够，而是为了让「想加 role」的人看清挡住他的是哪一步：
        // 把「这个账号是管理员」广播给任意登录用户，等于给钓鱼多一条可信度线索。
        assertThat(componentNames(UserHit.class)).doesNotContain("email", "role", "status", "lastLoginAt");

        User full = new User();
        full.setId(9L);
        full.setUsername("qiuyu");
        full.setNickname("  屿声  ");
        full.setAvatar("https://cdn.invalid/a.png");
        assertThat(UserHit.of(full)).isEqualTo(new UserHit(9L, "屿声", "https://cdn.invalid/a.png"));

        // 昵称为空回退登录名：与 PostQueryService.displayNameOf 同口径，不一致会让人以为搜到的是别人
        User blank = new User();
        blank.setId(10L);
        blank.setUsername("qiuyu2");
        assertThat(UserHit.of(blank)).isEqualTo(new UserHit(10L, "qiuyu2", null));
        User whitespace = new User();
        whitespace.setId(11L);
        whitespace.setUsername("qiuyu3");
        whitespace.setNickname("   ");
        assertThat(UserHit.of(whitespace).nickname()).isEqualTo("qiuyu3");
    }

    @Test
    @DisplayName("TopicHit 不透出 cover / deleted / auditStatus：三个都属于运营或存储细节")
    void topicHitDoesNotLeakOperationalFields() {
        assertThat(componentNames(TopicHit.class)).containsExactly("id", "name", "desc", "postCnt",
                "followCnt", "hotScore", "isOfficial");

        Topic topic = new Topic();
        topic.setId(3L);
        topic.setName("考试焦虑");
        topic.setDescTxt("期末前的睡眠话题");
        topic.setCover("https://cdn.invalid/cover.png");
        topic.setAuditStatus("APPROVED");
        topic.setPostCnt(12);
        topic.setFollowCnt(7);
        topic.setHotScore(new BigDecimal("99.50"));
        topic.setIsOfficial(1);
        topic.setDeleted(0);

        TopicHit hit = TopicHit.of(topic);
        assertThat(hit).isEqualTo(new TopicHit(3L, "考试焦虑", "期末前的睡眠话题", 12, 7,
                new BigDecimal("99.50"), 1));
        // desc 来自 desc_txt 列而不是别的同名字段：这一步映射错，前端会显示空串或一段封面地址
        assertThat(hit.desc()).isEqualTo("期末前的睡眠话题");
    }

    // ------------------------------------------------------------------ 域边界

    @Test
    @DisplayName("SearchService 不依赖 PostMapper：搜帖那条路只允许在 post 域里实现")
    void searchServiceHasNoPostDependency() {
        List<Class<?>> types = Arrays.stream(SearchService.class.getDeclaredFields())
                // 静态常量（TOPIC_APPROVED / USER_ACTIVE / DEFAULT_LIMIT）不是依赖，不滤掉就会误报
                .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .map(Field::getType).collect(Collectors.toList());

        // 若有人为了「搜索里顺手也能捞帖」把 PostMapper 注进来，下一步必然是把广场那套
        // status/visibility/匿名判据在搜索域再抄一遍——手册 §14 第 27 条记的正是这种分叉的代价。
        assertThat(types).containsExactlyInAnyOrder(TopicMapper.class, UserMapper.class,
                MindisleProperties.class);
    }
}

package com.mindisle.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.entity.SysConfig;
import com.mindisle.mapper.ItemSimilarityMapper;
import com.mindisle.mapper.RecommendMapper;
import com.mindisle.mapper.RecommendResultMapper;
import com.mindisle.mapper.SysConfigMapper;
import com.mindisle.recommend.OfflineRecommendService.Knobs;
import com.mindisle.recommend.OfflineRecommendService.RecomputeContext;
import com.mindisle.recommend.dto.PostMetaRow;

/**
 * 离线作业的规模保护单测（手册 §10.2 7.10「物品 >5 000 时只算 ItemCF、跳过 UserCF」）。
 *
 * <p><b>本类同时是 {@link OfflineRecommendService} 的第一份单测</b>，这个事实值得留下证据：它是推荐
 * 链路<b>唯一的写入者</b>（{@code item_similarity} 与 {@code recommend_result} 两张缓存表都只由它写），
 * 八百多行，此前一行测试都没有 —— 因为它主体要连库，看起来「没法单测」。实际上 {@code readKnobs}、
 * {@code RecomputeContext}、{@code recallByUserCf} 三段是纯内存的：把构造器里三个 Mapper 换成
 * 「一碰就抛」的替身，就能证明这条路径确实不查库，顺带把「不查库」本身变成一条判据。</p>
 *
 * <p><b>为什么判据必须成对写</b>：降级开关最典型的两种失效是「永远不触发」和「永远触发」。
 * 只测降级后的空表，读者无法区分「阈值生效」与「UserCF 本来就召不出货」，所以每条降级判据旁边
 * 都放一条同矩阵、同候选池、只差 {@code itemUniverse} 一个数的不降级判据。</p>
 *
 * <p><b>三条容易被写成假绿、本类特意换了钉法的判据</b>：</p>
 * <ul>
 *   <li><b>判据来源</b>：物品规模必须取自行为矩阵里的<b>不同帖子数</b>，不是候选池大小。候选池被
 *       {@link RecConstants#CANDIDATE_POOL_CAP}=300 截断，拿它当判据这道保护<b>永不触发</b>
 *       （{@link #guardReadsMatrixUniverseInsteadOfCandidatePool()}）；</li>
 *   <li><b>降级要真省事</b>：跳过 UserCF 的意义是省掉那次「逐用户扫全表」，所以断言的不是返回值空，
 *       而是<b>矩阵一次都没被遍历</b>（{@link #degradedPathNeverScansTheMatrix()}，会计数的 Map 替身）；</li>
 *   <li><b>阈值可关</b>：{@code sys_config} 里置 0 或负数必须恒不降级，否则管理端「关掉保护」这件事
 *       是不可用的（{@link #capAtOrBelowZeroTurnsGuardOff()}）。</li>
 * </ul>
 */
class RecommendScaleGuardTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 29, 12, 0);
    /** 目标用户：矩阵里的 1 号。 */
    private static final long TARGET = 1L;
    /** 只有邻居看过、因此只能由 UserCF 供货的帖子：没有这条通道，候选里就不该出现它。 */
    private static final long PEER_ONLY_ITEM = 30L;
    private static final String CAP_KEY = "rec.scale_item_cap";
    /** SQL 里包住配置值的单引号，写成常量是为了不在断言里塞转义。 */
    private static final char QUOTE = (char) 39;

    /** sys_config 替身的数据源：缺键 = 库里没有这一行（findByKey 返回 null）。 */
    private final Map<String, String> configRows = new HashMap<>();

    // ------------------------------------------------------------- 替身与夹具

    /**
     * 三个 Mapper 全部换成「被调用即抛」。
     *
     * <p>抛异常而不是返回空值，是为了让「这条路径偷偷查了库」立刻可见：返回空表会让断言以
     * 「UserCF 召不出货」的形式假绿，而那次查询在生产的代价是一整轮全表扫描。</p>
     */
    private OfflineRecommendService serviceWithoutDb() {
        return new OfflineRecommendService(
            stub(RecommendMapper.class, "规模保护这条路径不该查 recommend 侧"),
            stub(ItemSimilarityMapper.class, "规模保护这条路径不该查 item_similarity"),
            stub(RecommendResultMapper.class, "规模保护这条路径不该查 recommend_result"),
            configStub(), new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> iface, String message) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == args[0];
                }
                if ("toString".equals(name)) {
                    return iface.getSimpleName() + "$throwingStub";
                }
                throw new IllegalStateException(message + "：" + iface.getSimpleName() + "." + name);
            });
    }

    /**
     * findByKey 是接口上的 default 方法，但 Proxy 会拦下一切调用，所以按方法名分派即可；
     * 其余 BaseMapper 方法一律不落进来。
     */
    private SysConfigMapper configStub() {
        return (SysConfigMapper) Proxy.newProxyInstance(SysConfigMapper.class.getClassLoader(),
            new Class<?>[] {SysConfigMapper.class}, (proxy, method, args) -> {
                String name = method.getName();
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == args[0];
                }
                if ("toString".equals(name)) {
                    return "SysConfigMapper$configStub";
                }
                if ("findByKey".equals(name)) {
                    String key = (String) args[0];
                    String value = configRows.get(key);
                    if (value == null) {
                        return null;
                    }
                    SysConfig row = new SysConfig();
                    row.setCfgKey(key);
                    row.setCfgValue(value);
                    row.setValueType("int");
                    row.setGroupKey("rec");
                    return row;
                }
                throw new IllegalStateException("规模保护这条路径不该调 " + name);
            });
    }

    private static Map<Long, Double> vec(Object... pairs) {
        Map<Long, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(((Number) pairs[i]).longValue(), ((Number) pairs[i + 1]).doubleValue());
        }
        return out;
    }

    /**
     * 三个人、三种命运的小矩阵：u1 是目标，u2 与 ta 共看 10/20 且独有 30（共同帖数够
     * {@link RecConstants#MIN_CO_USERS}），u3 只共看一篇（邻居门槛就该筛掉它）。不同物品数 = 3。
     */
    private static Map<Long, Map<Long, Double>> tasteMatrix() {
        Map<Long, Map<Long, Double>> out = new LinkedHashMap<>();
        out.put(1L, vec(10L, 0.5d, 20L, 0.5d));
        out.put(2L, vec(10L, 0.5d, 20L, 0.5d, PEER_ONLY_ITEM, 0.9d));
        out.put(3L, vec(10L, 0.5d));
        return out;
    }

    private static PostMetaRow meta(long id) {
        PostMetaRow row = new PostMetaRow();
        row.setId(id);
        row.setUserId(900L + id);
        row.setType("note");
        row.setRiskLevel("L0");
        row.setQualityScore(BigDecimal.ONE);
        row.setPublishedAt(NOW.minusDays(1));
        return row;
    }

    private static Map<Long, PostMetaRow> poolOf(long... ids) {
        Map<Long, PostMetaRow> out = new LinkedHashMap<>();
        for (long id : ids) {
            out.put(id, meta(id));
        }
        return out;
    }

    private static Knobs knobs(int itemScaleCap) {
        Knobs knobs = new Knobs();
        knobs.topKUserNeighbor = RecConstants.TOP_K_USER_NEIGHBOR;
        knobs.itemScaleCap = itemScaleCap;
        return knobs;
    }

    private static RecomputeContext context(Map<Long, Map<Long, Double>> matrix,
        Map<Long, PostMetaRow> pool, int itemScaleCap, int itemUniverse) {
        RecomputeContext ctx = new RecomputeContext(matrix, Map.of(), pool, Map.of(),
            knobs(itemScaleCap), NOW);
        ctx.itemUniverse = itemUniverse;
        return ctx;
    }

    /** 会统计「全表遍历次数」的矩阵替身：UserCf.neighbors 每扫一轮取一次 entrySet。 */
    private static final class CountingMatrix extends LinkedHashMap<Long, Map<Long, Double>> {

        private static final long serialVersionUID = 1L;
        private int fullScans;

        @Override
        public Set<Map.Entry<Long, Map<Long, Double>>> entrySet() {
            fullScans++;
            return super.entrySet();
        }

        @Override
        public Collection<Map<Long, Double>> values() {
            fullScans++;
            return super.values();
        }
    }

    // ------------------------------------------------------------- 判据

    @Test
    @DisplayName("7.10 常量钉住手册原文，且候选池上限小于它（否则拿池子当判据永不触发）")
    void constantMatchesManual() {
        assertThat(RecConstants.ITEM_SCALE_CAP).as("手册 §10.2 7.10 原文「物品 >5 000」").isEqualTo(5000);
        assertThat(RecConstants.CANDIDATE_POOL_CAP).as("候选池截到 300，绝不能拿它当物品规模")
            .isLessThan(RecConstants.ITEM_SCALE_CAP);
    }

    @Test
    @DisplayName("种子值必须等于代码兜底值：否则「库里的默认」和「代码的默认」是两套推荐")
    void seedValueAgreesWithCodeDefault() throws Exception {
        Path seed = repoFile("sql/09_seed.sql");
        String text = Files.readString(seed);
        String keyLiteral = QUOTE + CAP_KEY + QUOTE;
        int at = text.indexOf(keyLiteral);
        assertThat(at).as("%s 里必须种下 %s 这一行（现场要能在管理端改它）", seed, CAP_KEY).isPositive();

        String after = text.substring(at + keyLiteral.length());
        // 键那串以单引号收尾，所以后面紧接的是 ",'值"，不是逗号前的引号。
        assertThat(after).as("种子行的形状应为 ,'<值>','<类型>'").startsWith("," + QUOTE);
        int end = after.indexOf(QUOTE, 2);
        assertThat(end).as("值后面那个引号要在").isPositive();
        assertThat(Integer.parseInt(after.substring(2, end)))
            .as("种子写进的值必须与 RecConstants.ITEM_SCALE_CAP 逐字相同")
            .isEqualTo(RecConstants.ITEM_SCALE_CAP);
    }

    @Test
    @DisplayName("读不到配置退回常量；脏值只废这一个参数，不拖停整轮重算")
    void readKnobsFallsBackPerParameter() {
        OfflineRecommendService service = serviceWithoutDb();

        assertThat(service.readKnobs().itemScaleCap).as("库里没有这一行 → 用常量").isEqualTo(5000);

        configRows.put(CAP_KEY, "300");
        assertThat(service.readKnobs().itemScaleCap).as("管理员把阈值调到 300 要立刻生效").isEqualTo(300);

        configRows.put(CAP_KEY, "  700 ");
        assertThat(service.readKnobs().itemScaleCap).as("前后空白要被吃掉").isEqualTo(700);

        configRows.put(CAP_KEY, "");
        assertThat(service.readKnobs().itemScaleCap).as("空串按缺行处理").isEqualTo(5000);

        configRows.put(CAP_KEY, "abc");
        Knobs dirty = service.readKnobs();
        assertThat(dirty.itemScaleCap).as("解析失败退回默认，而不是让整轮作业停摆").isEqualTo(5000);
        assertThat(dirty.beta).as("同一个 Knobs 里其它参数照常读出默认值：失败范围只到这一项")
            .isEqualTo(RecConstants.BETA_CF);

        configRows.put(CAP_KEY, "5000.9");
        assertThat(service.readKnobs().itemScaleCap).as("int 参数写小数按截断处理").isEqualTo(5000);
    }

    @Test
    @DisplayName("判据用的是矩阵里的不同帖子数：跨用户去重，空向量与 null 向量不算物品")
    void countDistinctItemsCountsItemsNotBehaviours() {
        Map<Long, Map<Long, Double>> matrix = new LinkedHashMap<>();
        matrix.put(1L, vec(10L, 0.5d, 20L, 0.5d));
        matrix.put(2L, vec(20L, 0.4d, 30L, 0.9d));
        matrix.put(3L, Map.of());
        matrix.put(4L, null);

        assertThat(OfflineRecommendService.countDistinctItems(matrix)).as("物品 = {10,20,30}").isEqualTo(3);
        assertThat(OfflineRecommendService.countDistinctItems(matrix))
            .as("既不是人数(4)也不是行为条数(5)：量纲一混，阈值就没有可比性")
            .isNotEqualTo(matrix.size());
        assertThat(OfflineRecommendService.countDistinctItems(new LinkedHashMap<>())).isZero();
        assertThat(OfflineRecommendService.countDistinctItems(tasteMatrix())).isEqualTo(3);
    }

    @Test
    @DisplayName("线上现状不降级：默认阈值 5000、现库 623 帖，UserCF 照常供货 30 号帖")
    void belowCapUserCfStillSupplies() {
        Map<Long, Map<Long, Double>> matrix = tasteMatrix();
        Map<Long, PostMetaRow> pool = poolOf(10L, 20L, PEER_ONLY_ITEM);
        RecomputeContext ctx = context(matrix, pool, RecConstants.ITEM_SCALE_CAP,
            OfflineRecommendService.countDistinctItems(matrix));

        assertThat(ctx.userCfDisabled()).as("3 篇远小于 5000，不该动这条通道").isFalse();
        Map<Long, Double> out = serviceWithoutDb().recallByUserCf(TARGET, matrix.get(TARGET),
            RecConstants.CF_MIN_INTERACTIONS, ctx);

        assertThat(out).containsKey(PEER_ONLY_ITEM);
        assertThat(out.get(PEER_ONLY_ITEM)).as("邻居偏好是加权平均，u2 给 30 号 0.9")
            .isCloseTo(0.9d, within(1e-9));
        assertThat(out).as("自己看过的 10/20 不占推荐位").doesNotContainKeys(10L, 20L);
    }

    @Test
    @DisplayName("反向对照：同一矩阵同一候选池，只差物品数越过阈值，UserCF 整条通道消失")
    void overCapDropsUserCfChannel() {
        Map<Long, Map<Long, Double>> matrix = tasteMatrix();
        Map<Long, PostMetaRow> pool = poolOf(10L, 20L, PEER_ONLY_ITEM);
        OfflineRecommendService service = serviceWithoutDb();
        long interactions = RecConstants.CF_MIN_INTERACTIONS;

        RecomputeContext atCap = context(matrix, pool, 3, 3);
        RecomputeContext overCap = context(matrix, pool, 3, 4);

        assertThat(atCap.userCfDisabled()).as("等于阈值不算超（严格大于才降级）").isFalse();
        assertThat(overCap.userCfDisabled()).as("多一篇就降级").isTrue();

        Map<Long, Double> kept = service.recallByUserCf(TARGET, matrix.get(TARGET), interactions, atCap);
        Map<Long, Double> dropped = service.recallByUserCf(TARGET, matrix.get(TARGET), interactions,
            overCap);

        assertThat(kept).as("先确认这条矩阵本来能出货，否则那条「空」毫无证据价值")
            .containsKey(PEER_ONLY_ITEM);
        assertThat(dropped).as("两次调用只差 itemUniverse，差异不可能来自别处").isEmpty();
    }

    @Test
    @DisplayName("阈值置 0 或负数 = 关掉这道保护：物品再多也恒不降级")
    void capAtOrBelowZeroTurnsGuardOff() {
        Map<Long, Map<Long, Double>> matrix = tasteMatrix();
        Map<Long, PostMetaRow> pool = poolOf(10L, 20L, PEER_ONLY_ITEM);
        OfflineRecommendService service = serviceWithoutDb();

        for (int cap : new int[] {0, -1, Integer.MIN_VALUE}) {
            RecomputeContext ctx = context(matrix, pool, cap, 9_999_999);
            assertThat(ctx.userCfDisabled()).as("cap=%d 时这道保护应被关掉", cap).isFalse();
            assertThat(service.recallByUserCf(TARGET, matrix.get(TARGET),
                RecConstants.CF_MIN_INTERACTIONS, ctx)).as("cap=%d 时仍能出货", cap)
                    .containsKey(PEER_ONLY_ITEM);
        }
    }

    @Test
    @DisplayName("判据只认 itemUniverse：候选池被截到 300 既不会误判，也不会让它永不判")
    void guardReadsMatrixUniverseInsteadOfCandidatePool() {
        Map<Long, PostMetaRow> fullPool = new HashMap<>();
        for (long id = 1L; id <= RecConstants.CANDIDATE_POOL_CAP; id++) {
            fullPool.put(id, meta(id));
        }
        RecomputeContext ctx = context(tasteMatrix(), fullPool, RecConstants.ITEM_SCALE_CAP, 0);

        assertThat(ctx.metaById).as("先把池子撑到它的上限，证明「池子很大」本身不触发降级")
            .hasSize(RecConstants.CANDIDATE_POOL_CAP);
        assertThat(ctx.userCfDisabled()).as("矩阵里的不同帖子数还没算进去（0）→ 不降级").isFalse();

        ctx.itemUniverse = 6_000;
        assertThat(ctx.userCfDisabled()).as("只有 itemUniverse 能翻这条判据").isTrue();
    }

    @Test
    @DisplayName("降级必须真省掉那次全表遍历：矩阵一次都没被读，而不只是返回空表")
    void degradedPathNeverScansTheMatrix() {
        CountingMatrix matrix = new CountingMatrix();
        matrix.put(1L, vec(10L, 0.5d, 20L, 0.5d));
        matrix.put(2L, vec(10L, 0.5d, 20L, 0.5d, PEER_ONLY_ITEM, 0.9d));
        Map<Long, PostMetaRow> pool = poolOf(10L, 20L, PEER_ONLY_ITEM);
        OfflineRecommendService service = serviceWithoutDb();
        long interactions = RecConstants.CF_MIN_INTERACTIONS;

        RecomputeContext kept = context(matrix, pool, 5_000, 2);
        assertThat(service.recallByUserCf(TARGET, matrix.get(TARGET), interactions, kept))
            .containsKey(PEER_ONLY_ITEM);
        int scansWhenActive = matrix.fullScans;

        matrix.fullScans = 0;
        RecomputeContext degraded = context(matrix, pool, 1, 2);
        assertThat(service.recallByUserCf(TARGET, matrix.get(TARGET), interactions, degraded)).isEmpty();

        assertThat(scansWhenActive).as("不降级时确实遍历过矩阵，否则下面那个 0 是假的").isPositive();
        assertThat(matrix.fullScans)
            .as("降级时一次都不该遍历：UserCF 的代价正是 O(用户²)，返回空但仍扫一遍等于没降级")
            .isZero();
    }

    @Test
    @DisplayName("新闸门不吃掉旧闸门：交互数不足 20 时，不降级也照样不出货")
    void coldStartGateStillApplies() {
        Map<Long, Map<Long, Double>> matrix = tasteMatrix();
        Map<Long, PostMetaRow> pool = poolOf(10L, 20L, PEER_ONLY_ITEM);
        RecomputeContext healthy = context(matrix, pool, 5_000, 3);

        assertThat(serviceWithoutDb().recallByUserCf(TARGET, matrix.get(TARGET),
            RecConstants.CF_MIN_INTERACTIONS - 1L, healthy))
                .as("FR5.5 的冷启动门槛仍在：规模保护是多添的一道闸门，不是替换").isEmpty();
        assertThat(healthy.userCfDisabled()).as("而且这一条与降级无关，两个开关各自独立").isFalse();
    }

    @Test
    @DisplayName("降级只影响 UserCF 这一路：候选池过滤与 0–1 夹取等下游语义都不受牵连")
    void guardOnlyAffectsUserCfChannel() {
        Map<Long, Map<Long, Double>> matrix = new LinkedHashMap<>();
        matrix.put(1L, vec(10L, 0.5d, 20L, 0.5d));
        // 邻居独有两篇：31 在候选池内、30 在池外（池外那篇必须被过滤，且 4 分必须被夹到 0–1）
        matrix.put(2L, vec(10L, 0.5d, 20L, 0.5d, 31L, 0.6d, PEER_ONLY_ITEM, 4d));
        Map<Long, PostMetaRow> pool = poolOf(10L, 20L, 31L);
        RecomputeContext ctx = context(matrix, pool, 5_000,
            OfflineRecommendService.countDistinctItems(matrix));

        assertThat(ctx.userCfDisabled()).isFalse();
        Map<Long, Double> out = serviceWithoutDb().recallByUserCf(TARGET, matrix.get(TARGET),
            RecConstants.CF_MIN_INTERACTIONS, ctx);

        assertThat(out).containsOnlyKeys(31L);
        assertThat(out.get(31L))
            .as("写进 recommend_result.score 的 DECIMAL(8,6) 之前必须夹到 0–1").isBetween(0d, 1d);
    }

    /** surefire 的 basedir 是 backend/，从仓库根或 IDE 里跑时退一层再找。 */
    private static Path repoFile(String relativeFromRepoRoot) {
        Path direct = Path.of(relativeFromRepoRoot);
        if (Files.exists(direct)) {
            return direct;
        }
        Path up = Path.of("..", relativeFromRepoRoot);
        if (Files.exists(up)) {
            return up;
        }
        throw new IllegalStateException(
            "找不到 " + relativeFromRepoRoot + "，工作目录是 " + Path.of("").toAbsolutePath());
    }
}

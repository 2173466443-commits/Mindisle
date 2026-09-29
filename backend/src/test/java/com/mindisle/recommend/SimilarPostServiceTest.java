package com.mindisle.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindisle.cache.CacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.entity.ItemSimilarity;
import com.mindisle.mapper.ItemSimilarityMapper;
import com.mindisle.mapper.RecommendMapper;
import com.mindisle.post.PostQueryService;
import com.mindisle.post.dto.PostListItem;
import com.mindisle.recommend.dto.PostMetaRow;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 相似帖「看了又看」单测（任务 T7.16 / T7.2 · 手册 §10.6 · 需求 FR5.6、U4、§8.3）。
 *
 * <p><b>替身是「会按顺序返回卡片的假库」</b>：相似位的语义全在顺序与过滤上——邻居在前、话题兜底其次、
 * 质量分榜补位，以及六条硬判据（未发布 / 非公开 / 作者注销 / 树洞到期 / 危机 L2L3 / 本人发帖）。
 * 假 {@code feedCards} 保持入参次序返回，正好复刻真实现的契约，所以断言可以直接写顺序。</p>
 *
 * <p>三条判据单独钉：</p>
 * <ul>
 *   <li><b>源帖不可见必须 404，不是 200 空表</b>：两个状态码不一样，这个端点就成了探测别人私密帖的探针；</li>
 *   <li><b>L2/L3 危机帖不进相似位</b>（需求 §8.3 禁止二次暴露）——判据在 Java 侧，
 *     所以 SQL 里没有 {@code risk_level} 过滤是刻意的，这里必须把它钉住，否则下一个人会「顺手」删掉；</li>
 *   <li><b>已读降权到尾巴而不是删掉</b>：删的话老用户的相似位会越用越空。</li>
 * </ul>
 */
class SimilarPostServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 29, 12, 0);
    private static final long VIEWER = 486L;
    private static final long SOURCE = 1L;

    private final Map<Long, PostListItem> catalogue = new LinkedHashMap<>();
    private final Map<Long, String> simItemJson = new LinkedHashMap<>();
    private final List<PostMetaRow> pool = new ArrayList<>();
    private final Map<String, List<Long>> topicPosts = new LinkedHashMap<>();
    private final List<String> topicQueries = new ArrayList<>();
    private final Map<String, Object> cacheStore = new HashMap<>();
    private List<Long> negative = List.of();
    private List<Long> exposed = List.of();
    private LocalDateTime lastBatchCalcAt = NOW.minusMinutes(5);
    private int similarityRowReads;
    private SimilarPostService service;

    private static PostListItem card(long id, String... topics) {
        return new PostListItem(id, "note", "帖" + id, "摘要", "屿民" + id, false, 900L + id,
            List.of(topics), List.of(), "PUBLISHED", "PUBLIC", 10L, 1, 1,
            NOW.minusDays(1), null, null, null, false, false, 0);
    }

    private static PostMetaRow meta(long id, long authorId, String riskLevel) {
        PostMetaRow row = new PostMetaRow();
        row.setId(id);
        row.setUserId(authorId);
        row.setType("note");
        row.setRiskLevel(riskLevel);
        row.setQualityScore(BigDecimal.ONE);
        row.setPublishedAt(NOW.minusDays(1));
        return row;
    }

    private static List<Long> idsOf(List<FeedService.FeedItem> items) {
        List<Long> out = new ArrayList<>();
        for (FeedService.FeedItem item : items) {
            out.add(item.id());
        }
        return out;
    }

    @BeforeEach
    void setUp() {
        catalogue.clear();
        simItemJson.clear();
        pool.clear();
        topicPosts.clear();
        topicQueries.clear();
        cacheStore.clear();
        negative = List.of();
        exposed = List.of();
        lastBatchCalcAt = NOW.minusMinutes(5);
        similarityRowReads = 0;

        catalogue.put(SOURCE, card(SOURCE, "失眠"));

        RecommendMapper recommendMapper = (RecommendMapper) Proxy.newProxyInstance(
            RecommendMapper.class.getClassLoader(), new Class<?>[] {RecommendMapper.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "listNegativePostIds" -> new ArrayList<>(negative);
                case "listExposedPostIds" -> new ArrayList<>(exposed);
                case "listPostsByTopicName" -> {
                    String topic = (String) args[0];
                    topicQueries.add(topic);
                    yield new ArrayList<>(topicPosts.getOrDefault(topic, List.of()));
                }
                case "listRecommendablePosts" -> new ArrayList<>(pool);
                case "listRecommendableByIds" -> {
                    @SuppressWarnings("unchecked")
                    List<Long> wanted = (List<Long>) args[0];
                    List<PostMetaRow> out = new ArrayList<>();
                    for (PostMetaRow row : pool) {
                        if (wanted.contains(row.getId())) {
                            out.add(row);
                        }
                    }
                    yield out;
                }
                default -> throw new IllegalStateException("单测没替这条方法打桩：" + method.getName());
            });

        ItemSimilarityMapper similarityMapper = (ItemSimilarityMapper) Proxy.newProxyInstance(
            ItemSimilarityMapper.class.getClassLoader(),
            new Class<?>[] {ItemSimilarityMapper.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "selectByItem" -> {
                    similarityRowReads++;
                    String json = simItemJson.get((Long) args[0]);
                    if (json == null) {
                        yield null;
                    }
                    ItemSimilarity row = new ItemSimilarity();
                    row.setItemId((Long) args[0]);
                    row.setSimItems(json);
                    yield row;
                }
                case "lastBatchCalcAt" -> lastBatchCalcAt;
                default -> throw new IllegalStateException("单测没替这条方法打桩：" + method.getName());
            });

        PostQueryService postQueryService = new PostQueryService(null, null, null, null, null,
            null, null, null, null) {
            @Override
            public List<PostListItem> feedCards(long viewerId, List<Long> orderedIds,
                LocalDateTime now) {
                List<PostListItem> out = new ArrayList<>();
                for (Long id : orderedIds) {
                    PostListItem item = catalogue.get(id);
                    if (item != null) {
                        out.add(item);
                    }
                }
                return out;
            }
        };

        CacheService cache = (CacheService) Proxy.newProxyInstance(CacheService.class.getClassLoader(),
            new Class<?>[] {CacheService.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "get" -> cacheStore.get((String) args[0]);
                case "set" -> {
                    cacheStore.put((String) args[0], args[1]);
                    yield null;
                }
                default -> throw new IllegalStateException("单测没替这条方法打桩：" + method.getName());
            });

        service = new SimilarPostService(recommendMapper, similarityMapper, postQueryService,
            cache, new ObjectMapper());
    }

    /** 让候选通过池内校验（既不是本人发的，也不是危机帖）。 */
    private void publishable(long... ids) {
        for (long id : ids) {
            pool.add(meta(id, 900L + id, "L0"));
            if (!catalogue.containsKey(id)) {
                catalogue.put(id, card(id));
            }
        }
    }

    @Test
    @DisplayName("源帖对当前用户不可见 → 404 同码同形，不许 200 空表泄露「这帖存在」")
    void invisibleSourceThrowsNotFound() {
        catalogue.remove(SOURCE);

        assertThatThrownBy(() -> service.similar(VIEWER, SOURCE, 6, NOW))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode())
            .isEqualTo(ErrorCode.POST_NOT_FOUND);
    }

    @Test
    @DisplayName("邻居优先且保持相似度降序：通道记 itemcf、分数落 DECIMAL(8,6) 同口径")
    void neighborsComeFirstInOrder() {
        simItemJson.put(SOURCE, "[{\"item\":11,\"score\":0.9},{\"item\":12,\"score\":0.5}]");
        catalogue.put(11L, card(11L, "失眠"));
        catalogue.put(12L, card(12L, "考研"));
        publishable(11L, 12L);

        List<FeedService.FeedItem> items = service.similar(VIEWER, SOURCE, 2, NOW);

        assertThat(idsOf(items)).containsExactly(11L, 12L);
        assertThat(items.get(0).recallChannel()).isEqualTo(ColdStart.CHANNEL_ITEMCF);
        assertThat(items.get(0).score()).isEqualByComparingTo("0.9");
        assertThat(items.get(0).reason())
            .as("命中共同话题时理由带话题名，六条才不会长得一样")
            .isEqualTo("和这篇一样，看过它的人在 #失眠 里也点过");
        assertThat(items.get(1).reason()).isEqualTo("看过这篇的屿民也看过它");
        assertThat(items.get(1).score()).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("源帖自己不回填、负反馈帖直接排除，缺口由同话题兜底补上")
    void sourceAndNegativeExcludedThenTopicFallbackFills() {
        simItemJson.put(SOURCE, "[{\"item\":1,\"score\":0.99},{\"item\":11,\"score\":0.8},"
            + "{\"item\":12,\"score\":0.7}]");
        negative = List.of(12L);
        catalogue.put(11L, card(11L, "失眠"));
        catalogue.put(13L, card(13L, "失眠"));
        publishable(11L, 12L, 13L);
        topicPosts.put("失眠", List.of(12L, 13L));

        List<FeedService.FeedItem> items = service.similar(VIEWER, SOURCE, 2, NOW);

        assertThat(idsOf(items)).as("1 是源帖本身、12 被驳回过，都不许回来").containsExactly(11L, 13L);
        assertThat(items.get(1).recallChannel()).isEqualTo(ColdStart.CHANNEL_CONTENT);
        assertThat(items.get(1).reason()).isEqualTo("同属 #失眠，读完这篇的人常接着看");
        assertThat(items.get(1).score()).as("话题兜底没有相似度可言").isNull();
    }

    @Test
    @DisplayName("已读降权到尾巴而不是删掉：相似位不许越用越空")
    void exposedIsDemotedNotDropped() {
        simItemJson.put(SOURCE, "[{\"item\":12,\"score\":0.95},{\"item\":11,\"score\":0.9}]");
        exposed = List.of(12L);
        publishable(11L, 12L);

        List<FeedService.FeedItem> items = service.similar(VIEWER, SOURCE, 2, NOW);

        assertThat(idsOf(items)).as("读过的 12 排到最后，但仍然给").containsExactly(11L, 12L);
    }

    @Test
    @DisplayName("话题兜底只看源帖前 3 个话题：查得越多越像「把用户自选标签当成偏好真相」")
    void topicFallbackCapsAtThreeTopics() {
        catalogue.put(SOURCE, card(SOURCE, "a", "b", "c", "d", "e"));
        topicPosts.put("a", List.of(11L));
        publishable(11L);

        List<FeedService.FeedItem> items = service.similar(VIEWER, SOURCE, 1, NOW);

        assertThat(topicQueries).containsExactly("a", "b", "c");
        assertThat(topicQueries).doesNotContain("d", "e");
        assertThat(idsOf(items)).containsExactly(11L);
    }

    @Test
    @DisplayName("池内六条判据在 Java 侧兜住危机帖与本人帖（SQL 故意不过滤 risk_level）")
    void poolFilterDropsCrisisAndOwnPosts() {
        pool.add(meta(11L, 911L, "L2"));
        pool.add(meta(12L, VIEWER, "L0"));
        pool.add(meta(13L, 913L, "L3"));
        pool.add(meta(14L, 914L, "L0"));
        pool.add(meta(15L, 915L, null));
        List<SimilarPostService.Candidate> ranked = List.of(
            new SimilarPostService.Candidate(11L, 0.9d, ColdStart.CHANNEL_ITEMCF),
            new SimilarPostService.Candidate(12L, 0.8d, ColdStart.CHANNEL_ITEMCF),
            new SimilarPostService.Candidate(13L, 0.7d, ColdStart.CHANNEL_ITEMCF),
            new SimilarPostService.Candidate(14L, 0.6d, ColdStart.CHANNEL_ITEMCF),
            new SimilarPostService.Candidate(15L, 0.5d, ColdStart.CHANNEL_ITEMCF),
            new SimilarPostService.Candidate(16L, 0.4d, ColdStart.CHANNEL_ITEMCF));

        List<SimilarPostService.Candidate> kept = service.filterPool(ranked, VIEWER, NOW);

        assertThat(kept).extracting(SimilarPostService.Candidate::itemId).containsExactly(14L, 15L);
        assertThat(service.filterPool(List.of(), VIEWER, NOW)).as("空表不查库").isEmpty();
    }

    @Test
    @DisplayName("邻居与话题都不够时用质量分榜补位，且诚实标 hot 通道、不给分")
    void hotFillUsedWhenNeighborsAndTopicsRunShort() {
        catalogue.put(SOURCE, card(SOURCE));
        publishable(11L, 12L);
        pool.add(0, meta(11L, 911L, "L0"));

        List<FeedService.FeedItem> items = service.similar(VIEWER, SOURCE, 1, NOW);

        assertThat(idsOf(items)).containsExactly(11L);
        assertThat(items.get(0).recallChannel()).isEqualTo(ColdStart.CHANNEL_HOT);
        assertThat(items.get(0).reason()).isEqualTo("共读记录还太少，先给你社区里被读完最多的一篇");
        assertThat(items.get(0).score()).isNull();
    }

    @Test
    @DisplayName("邻居 JSON 坏了当「没有邻居」处理：一行脏数据不能把详情页拖垮")
    void corruptNeighborJsonFallsBackToTopics() {
        simItemJson.put(SOURCE, "{这不是 JSON");
        topicPosts.put("失眠", List.of(11L));
        publishable(11L);

        List<FeedService.FeedItem> items = service.similar(VIEWER, SOURCE, 1, NOW);

        assertThat(idsOf(items)).containsExactly(11L);
        assertThat(items.get(0).recallChannel()).isEqualTo(ColdStart.CHANNEL_CONTENT);
    }

    @Test
    @DisplayName("邻居表截到 40 条，脏条目跳过不整表作废")
    void neighborLoadIsCappedAndLenient() {
        StringBuilder json = new StringBuilder("[{\"item\":\"x\"},");
        for (int i = 1; i <= 45; i++) {
            json.append("{\"item\":").append(i).append(",\"score\":0.5}");
            if (i < 45) {
                json.append(',');
            }
        }
        json.append(']');
        simItemJson.put(SOURCE, json.toString());

        List<Long> neighbors = service.neighborIds(SOURCE, NOW);

        assertThat(neighbors).as("CANDIDATE_CAP 截断").hasSize(SimilarPostService.CANDIDATE_CAP);
        assertThat(neighbors).doesNotContain(0L).allMatch(id -> id > 0);
    }

    @Test
    @DisplayName("邻居走缓存：同一批内二次读库次数为 1；批次版本换了才重算")
    void neighborCandidatesAreCachedByBatchVersion() {
        simItemJson.put(SOURCE, "[{\"item\":11,\"score\":0.9}]");
        publishable(11L);

        assertThat(service.neighborIds(SOURCE, NOW)).containsExactly(11L);
        assertThat(service.neighborIds(SOURCE, NOW)).containsExactly(11L);
        assertThat(similarityRowReads).as("第二次命中缓存").isEqualTo(1);
        assertThat(cacheStore).containsKey(SimilarPostService.VERSION_KEY);
        assertThat(cacheStore.keySet()).anyMatch(key -> key.startsWith(SimilarPostService.KEY_PREFIX));

        cacheStore.clear();
        lastBatchCalcAt = lastBatchCalcAt.plusMinutes(1);
        assertThat(service.neighborIds(SOURCE, NOW)).containsExactly(11L);
        assertThat(similarityRowReads).as("版本键变了 → 缓存自然失效，不需要整批 DEL").isEqualTo(2);
    }

    @Test
    @DisplayName("size 入参夹取：不传按 6，最多 12（防一个 ?size=99999 把详情页打成全表扫）")
    void sizeIsClamped() {
        assertThat(SimilarPostService.clampSize(null)).isEqualTo(RecConstants.SIMILAR_DEFAULT_SIZE);
        assertThat(SimilarPostService.clampSize(0)).isEqualTo(RecConstants.SIMILAR_DEFAULT_SIZE);
        assertThat(SimilarPostService.clampSize(-9)).isEqualTo(RecConstants.SIMILAR_DEFAULT_SIZE);
        assertThat(SimilarPostService.clampSize(3)).isEqualTo(3);
        assertThat(SimilarPostService.clampSize(RecConstants.SIMILAR_MAX_SIZE))
            .isEqualTo(RecConstants.SIMILAR_MAX_SIZE);
        assertThat(SimilarPostService.clampSize(9_999)).isEqualTo(RecConstants.SIMILAR_MAX_SIZE);
    }

    @Test
    @DisplayName("缓存编解码：往返一致，坏段只丢那一段")
    void candidateCodecRoundTripsAndToleratesGarbage() {
        List<SimilarPostService.Candidate> candidates = List.of(
            new SimilarPostService.Candidate(11L, 0.87d, ColdStart.CHANNEL_ITEMCF),
            new SimilarPostService.Candidate(12L, 0d, ColdStart.CHANNEL_CONTENT));

        String encoded = SimilarPostService.serialize(candidates);
        assertThat(SimilarPostService.parseCandidates(encoded)).isEqualTo(candidates);
        assertThat(SimilarPostService.serialize(List.of())).isEmpty();
        assertThat(SimilarPostService.parseCandidates("")).isEmpty();
        assertThat(SimilarPostService.parseCandidates(null)).isEmpty();
        assertThat(SimilarPostService.parseCandidates("12:0.5")).as("缺通道段 → 丢")
            .isEmpty();
        assertThat(SimilarPostService.parseCandidates("abc:x:itemcf,11:0.5:itemcf"))
            .extracting(SimilarPostService.Candidate::itemId).containsExactly(11L);
    }

    @Test
    @DisplayName("相似位理由与主 feed 分开：三套文案对号，都不许出现「因为你」")
    void reasonForIsSeparateFromFeedWording() {
        PostListItem source = card(SOURCE, "失眠");
        PostListItem sameTopic = card(11L, "失眠", "考研");
        PostListItem otherTopic = card(12L, "就业");

        assertThat(SimilarPostService.reasonFor(source, sameTopic,
            new SimilarPostService.Candidate(11L, 0.9d, ColdStart.CHANNEL_ITEMCF)))
            .isEqualTo("和这篇一样，看过它的人在 #失眠 里也点过");
        assertThat(SimilarPostService.reasonFor(source, otherTopic,
            new SimilarPostService.Candidate(12L, 0.9d, ColdStart.CHANNEL_ITEMCF)))
            .isEqualTo("看过这篇的屿民也看过它");
        assertThat(SimilarPostService.reasonFor(source, otherTopic,
            new SimilarPostService.Candidate(12L, 0d, ColdStart.CHANNEL_CONTENT)))
            .isEqualTo("同属 #屿民推荐，读完这篇的人常接着看");
        for (String channel : ColdStart.CHANNELS) {
            assertThat(SimilarPostService.reasonFor(source, sameTopic,
                new SimilarPostService.Candidate(11L, 0.5d, channel)))
                .as(channel).doesNotContain("因为你");
        }
    }
}

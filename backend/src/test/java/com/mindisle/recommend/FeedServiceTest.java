package com.mindisle.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import com.mindisle.common.PageQuery;
import com.mindisle.common.PageResult;
import com.mindisle.entity.RecommendResult;
import com.mindisle.entity.UserAction;
import com.mindisle.mapper.RecommendMapper;
import com.mindisle.mapper.RecommendResultMapper;
import com.mindisle.post.PostQueryService;
import com.mindisle.post.dto.PostListItem;
import com.mindisle.recommend.dto.PostMetaRow;
import com.mindisle.track.UserActionCatalog;
import com.mindisle.track.UserActionRecorder;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 在线推荐流单测（任务 T7.7 / T7.9 / T7.10 · 手册 §10.1 · 需求 FR1.7 FR5.7 FR5.8、§6.2）。
 *
 * <p><b>这一层的正确性全在「谁被发出去、发了几次、留下什么账」</b>，不在分数怎么算——分数是离线批次
 * 的事（{@link OfflineRecommendService} 及其单测）。所以替身只替两件事：假 {@code recommend_result}
 * 按 {@code (offset, size)} 真截断（否则 {@code hasMore} 的判断就成了自说自话），假
 * {@code feedCards} 按目录返回卡片（目录里没有 = 该帖已不可见）。其余全是真代码。</p>
 *
 * <p>四条判据单独钉死：</p>
 * <ul>
 *   <li><b>兜底必须再过一次危机判据</b>：候选池 SQL 不带 {@code risk_level} 过滤是刻意的
 *     （离线侧 {@link OfflineRecommendService#isCrisis} 判），兜底路径绕过了离线作业，
 *     少了这一道就是需求 §8.3 禁止的二次暴露；</li>
 *   <li><b>兜底卡片不写理由</b>：给热度榜编一句「因为你常看 X」，等于把 §6.4 的对照组污染成实验组；</li>
 *   <li><b>曝光两件事互不替代</b>：{@code user_action(expose)} 按 3/10 确定性采样，
 *     {@code recommend_result.is_exposed} 每条都置；只断言「记了曝光」会在采样率改动时悄悄失真；</li>
 *   <li><b>「不感兴趣」三步的顺序</b>：先记行为事实再删缓存行，反过来会在崩溃窗口里留下
 *     「用户拒绝过但它不记得」的状态，重算时那条又回来。</li>
 * </ul>
 */
class FeedServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 29, 12, 0);
    /** id 尾数 6 → cf 组；487 尾数 7 → hot 组（{@link RecMode#CF_TAIL_BOUND}）。 */
    private static final long VIEWER = 486L;
    private static final long HOT_VIEWER = 487L;
    /** 486 的采样命中位：(486*31 + id) % 10 < 3 只在 id 尾数 4/5/6 时成立。 */
    private static final long SAMPLED_ID = 14L;
    private static final long UNSAMPLED_ID = 17L;

    private final List<RecommendResult> cached = new ArrayList<>();
    private final List<PostMetaRow> pool = new ArrayList<>();
    private final Map<Long, PostListItem> catalogue = new LinkedHashMap<>();
    private final Map<Long, List<Long>> neighborIds = new LinkedHashMap<>();
    private final List<UserAction> actions = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> readQueries = new ArrayList<>();
    private final List<Long> markExposedIds = new ArrayList<>();
    private final List<Long> dismissedSimilar = new ArrayList<>();
    private String markExposedMode;
    private FeedService service;

    private static PostListItem card(long id) {
        return new PostListItem(id, "note", "帖" + id, "摘要", "屿民" + id, false, 900L + id,
            List.of(), List.of(), "PUBLISHED", "PUBLIC", 10L, 1, 1,
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

    /** 进兜底池：既有元数据行（给候选池用），也有卡片（组装得出来才谈得上「可见」）。 */
    private void poolCard(long id, long authorId, String riskLevel) {
        pool.add(meta(id, authorId, riskLevel));
        catalogue.putIfAbsent(id, card(id));
    }

    private RecommendResult cachedRow(long itemId, int position, String channel, String reason,
        BigDecimal score, LocalDateTime calcAt) {
        RecommendResult row = new RecommendResult();
        row.setUserId(VIEWER);
        row.setScene(UserActionCatalog.SCENE_FEED);
        row.setItemId(itemId);
        row.setPosition(position);
        row.setRecallChannel(channel);
        row.setReason(reason);
        row.setScore(score);
        row.setMode(RecMode.MODE_CF);
        row.setCalcAt(calcAt);
        return row;
    }

    private static List<Long> idsOf(List<FeedService.FeedItem> items) {
        List<Long> out = new ArrayList<>();
        for (FeedService.FeedItem item : items) {
            out.add(item.id());
        }
        return out;
    }

    private List<Long> recordedActionTargetIds(String actionType) {
        List<Long> out = new ArrayList<>();
        for (UserAction action : actions) {
            if (actionType.equals(action.getActionType())) {
                out.add(action.getTargetId());
            }
        }
        return out;
    }

    /** 只看某一类事件（{@code events} 里同时混着埋点、缓存、邻居三类记账）。 */
    private List<String> eventsWith(String prefix) {
        List<String> out = new ArrayList<>();
        for (String event : events) {
            if (event.startsWith(prefix)) {
                out.add(event);
            }
        }
        return out;
    }

    private static PageQuery query(int page, int size) {
        PageQuery query = new PageQuery();
        query.setPage(page);
        query.setSize(size);
        return query;
    }

    @BeforeEach
    void setUp() {
        cached.clear();
        pool.clear();
        catalogue.clear();
        neighborIds.clear();
        actions.clear();
        events.clear();
        readQueries.clear();
        markExposedIds.clear();
        dismissedSimilar.clear();
        markExposedMode = null;

        RecommendResultMapper resultMapper = (RecommendResultMapper) Proxy.newProxyInstance(
            RecommendResultMapper.class.getClassLoader(),
            new Class<?>[] {RecommendResultMapper.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "selectByPosition" -> {
                    readQueries.add(args[2] + ":" + args[3] + ":" + args[4]);
                    int offset = (int) args[3];
                    int limit = (int) args[4];
                    List<RecommendResult> out = new ArrayList<>();
                    for (int i = offset; i < Math.min(offset + limit, cached.size()); i++) {
                        out.add(cached.get(i));
                    }
                    yield out;
                }
                case "markExposed" -> {
                    events.add("markExposed");
                    markExposedMode = (String) args[2];
                    @SuppressWarnings("unchecked")
                    List<Long> ids = (List<Long>) args[3];
                    markExposedIds.addAll(ids);
                    yield ids.size();
                }
                case "dismiss" -> {
                    events.add("dismiss:" + args[2]);
                    yield 1;
                }
                case "dismissItems" -> {
                    @SuppressWarnings("unchecked")
                    List<Long> ids = (List<Long>) args[2];
                    events.add("dismissItems:" + ids.size());
                    dismissedSimilar.addAll(ids);
                    yield ids.size();
                }
                default -> throw new IllegalStateException("单测没替这条方法打桩：" + method.getName());
            });

        RecommendMapper recommendMapper = (RecommendMapper) Proxy.newProxyInstance(
            RecommendMapper.class.getClassLoader(), new Class<?>[] {RecommendMapper.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "listRecommendablePosts" -> {
                    int limit = (int) args[0];
                    events.add("pool:" + limit);
                    yield new ArrayList<>(pool.subList(0, Math.min(limit, pool.size())));
                }
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

        UserActionRecorder recorder = new UserActionRecorder(new UserActionRecorder.Store() {
            @Override
            public int upsert(UserAction action) {
                events.add("action:" + action.getActionType() + ":" + action.getTargetId());
                actions.add(action);
                return 1;
            }

            @Override
            public int cancelActive(long userId, String targetType, long targetId,
                String actionType) {
                events.add("cancel");
                return 0;
            }

            @Override
            public Integer moodValenceOf(long userId, LocalDate day) {
                events.add("moodQuery");
                return null;
            }
        });

        SimilarPostService similarPostService = new SimilarPostService(null, null, null, null,
            null) {
            @Override
            public List<Long> neighborIds(long postId, LocalDateTime now) {
                events.add("neighbors:" + postId);
                return new ArrayList<>(neighborIds.getOrDefault(postId, List.of()));
            }
        };

        service = new FeedService(resultMapper, recommendMapper, postQueryService, recorder,
            similarPostService);
    }

    @Test
    @DisplayName("批次新鲜度判据：没算过/时钟回拨/刚好到点各自的结论")
    void staleCheckPinsTtlBoundary() {
        assertThat(FeedService.isStale(null, NOW)).as("从没算过 = 没有可用批次").isTrue();
        assertThat(FeedService.isStale(NOW, null)).as("没有基准时间不敢当它是新的").isTrue();
        assertThat(FeedService.isStale(NOW.minusMinutes(RecConstants.RESULT_TTL_MINUTES - 1), NOW))
            .isFalse();
        assertThat(FeedService.isStale(NOW.minusMinutes(RecConstants.RESULT_TTL_MINUTES), NOW))
            .as("刚好 90 分钟仍算新鲜（判据是严格大于）").isFalse();
        assertThat(FeedService.isStale(NOW.minusMinutes(RecConstants.RESULT_TTL_MINUTES + 1), NOW))
            .isTrue();
        assertThat(FeedService.isStale(NOW.plusMinutes(30), NOW))
            .as("时钟回拨算出的未来批次不该判过期，否则整站退兜底").isFalse();
    }

    @Test
    @DisplayName("命中缓存：顺序/理由/通道/分数原样上屏，曝光按「采样写事实 + 全量置位」两本账")
    void cacheHitKeepsOrderAndRecordsBothExposureLedgers() {
        LocalDateTime fresh = NOW.minusMinutes(3);
        cached.add(cachedRow(SAMPLED_ID, 0, ColdStart.CHANNEL_USERCF, "因为你常看 #失眠",
            new BigDecimal("0.812345"), fresh));
        cached.add(cachedRow(UNSAMPLED_ID, 1, ColdStart.CHANNEL_CONTENT, "同属 #考研",
            new BigDecimal("0.500000"), fresh));
        cached.add(cachedRow(16L, 2, ColdStart.CHANNEL_EMOTION, "今天想看点轻松的", null, fresh));
        catalogue.put(SAMPLED_ID, card(SAMPLED_ID));
        catalogue.put(UNSAMPLED_ID, card(UNSAMPLED_ID));
        catalogue.put(16L, card(16L));
        assertThat(UserActionCatalog.sampleExpose(VIEWER, SAMPLED_ID)).isTrue();
        assertThat(UserActionCatalog.sampleExpose(VIEWER, UNSAMPLED_ID)).isFalse();

        PageResult<FeedService.FeedItem> page = service.feed(VIEWER, query(1, 2), NOW);

        assertThat(page.getList()).hasSize(2);
        assertThat(idsOf(page.getList())).containsExactly(SAMPLED_ID, UNSAMPLED_ID);
        assertThat(page.isHasMore()).as("缓存里还有 position 3 → 还能往下翻").isTrue();
        assertThat(page.getList().get(0).reason()).isEqualTo("因为你常看 #失眠");
        assertThat(page.getList().get(0).recallChannel()).isEqualTo(ColdStart.CHANNEL_USERCF);
        assertThat(page.getList().get(0).score()).isEqualByComparingTo("0.812345");
        assertThat(page.getList().get(1).recallChannel()).isEqualTo(ColdStart.CHANNEL_CONTENT);
        assertThat(readQueries).containsExactly(RecMode.MODE_CF + ":0:3");
        // 两本账各记各的：埋点只记采样命中的那条，缓存位实际发出去的全部置位
        assertThat(recordedActionTargetIds(UserActionCatalog.ACTION_EXPOSE))
            .containsExactly(SAMPLED_ID);
        assertThat(markExposedIds).containsExactly(SAMPLED_ID, UNSAMPLED_ID);
        assertThat(markExposedMode).isEqualTo(RecMode.MODE_CF);
        assertThat(events).as("曝光与负反馈都不该顺手查一次打卡表").doesNotContain("moodQuery");
    }

    @Test
    @DisplayName("同一帖在两行缓存里只发一次（批次交叠时不能给用户看两条一样的）")
    void duplicateCacheRowsAreSentOnce() {
        LocalDateTime fresh = NOW.minusMinutes(2);
        cached.add(cachedRow(SAMPLED_ID, 0, ColdStart.CHANNEL_USERCF, "理由甲", null, fresh));
        cached.add(cachedRow(SAMPLED_ID, 1, ColdStart.CHANNEL_ITEMCF, "理由乙", null, fresh));
        cached.add(cachedRow(UNSAMPLED_ID, 2, ColdStart.CHANNEL_HOT, null, null, fresh));
        catalogue.put(SAMPLED_ID, card(SAMPLED_ID));
        catalogue.put(UNSAMPLED_ID, card(UNSAMPLED_ID));

        PageResult<FeedService.FeedItem> page = service.feed(VIEWER, query(1, 3), NOW);

        assertThat(idsOf(page.getList())).containsExactly(SAMPLED_ID, UNSAMPLED_ID);
        assertThat(page.getList().get(0).reason()).as("保留 position 更小的那行")
            .isEqualTo("理由甲");
        assertThat(markExposedIds).containsExactly(SAMPLED_ID, UNSAMPLED_ID);
        assertThat(page.isHasMore()).as("批次到此为止，不为重复行留空位").isFalse();
    }

    @Test
    @DisplayName("批次过期：退热度榜并诚实标 hot（不编理由、不带分数），也不再置缓存曝光位")
    void staleBatchFallsBackToHot() {
        cached.add(cachedRow(11L, 0, ColdStart.CHANNEL_USERCF, "因为你常看 #失眠",
            new BigDecimal("0.9"), NOW.minusMinutes(RecConstants.RESULT_TTL_MINUTES + 1)));
        poolCard(24L, 777L, "L0");
        poolCard(25L, 777L, "L0");

        PageResult<FeedService.FeedItem> page = service.feed(VIEWER, query(1, 2), NOW);

        assertThat(idsOf(page.getList())).containsExactly(24L, 25L);
        assertThat(page.getList()).allSatisfy(item -> {
            assertThat(item.recallChannel()).isEqualTo(ColdStart.CHANNEL_HOT);
            assertThat(item.reason()).as("兜底没有个性化理由，留空而不是编一句").isNull();
            assertThat(item.score()).isNull();
        });
        assertThat(markExposedIds).as("兜底这条不在缓存批次里，没有 position 可置").isEmpty();
        assertThat(recordedActionTargetIds(UserActionCatalog.ACTION_EXPOSE))
            .as("但曝光事实照记（这两条都命中采样）").containsExactly(24L, 25L);
    }

    @Test
    @DisplayName("兜底也过危机判据：本人帖与 L2/L3 一条都不能上首页，重复 id 与 NULL 另判")
    void hotFallbackFiltersOwnAndCrisis() {
        poolCard(1L, VIEWER, "L0");
        poolCard(2L, 777L, "L2");
        poolCard(3L, 777L, "L3");
        pool.add(meta(4L, 777L, "L0"));
        pool.add(meta(4L, 777L, "L0"));
        catalogue.put(4L, card(4L));
        poolCard(5L, 777L, null);
        poolCard(6L, 777L, "L1");
        assertThat(cached).as("没算过 = 空缓存路径").isEmpty();

        PageResult<FeedService.FeedItem> page = service.feed(VIEWER, query(1, 6), NOW);

        assertThat(idsOf(page.getList())).containsExactly(4L, 5L, 6L);
        assertThat(page.isHasMore()).isFalse();
        assertThat(eventsWith("pool:")).containsExactly("pool:7");
        assertThat(eventsWith("markExposed")).isEmpty();
    }

    @Test
    @DisplayName("兜底按 offset 翻页：第二屏不重复第一屏，翻到底 hasMore 关掉")
    void hotFallbackPagesWithoutRepeats() {
        for (long id = 1; id <= 6; id++) {
            poolCard(id, 777L, "L0");
        }

        PageResult<FeedService.FeedItem> first = service.feed(VIEWER, query(1, 2), NOW);
        PageResult<FeedService.FeedItem> second = service.feed(VIEWER, query(2, 2), NOW);
        PageResult<FeedService.FeedItem> third = service.feed(VIEWER, query(3, 2), NOW);

        assertThat(idsOf(first.getList())).containsExactly(1L, 2L);
        assertThat(idsOf(second.getList())).containsExactly(3L, 4L);
        assertThat(idsOf(third.getList())).containsExactly(5L, 6L);
        assertThat(first.isHasMore()).isTrue();
        assertThat(second.isHasMore()).isTrue();
        assertThat(third.isHasMore()).as("正好凑满一屏但池子已空 = 到底了").isFalse();
        assertThat(eventsWith("pool:")).containsExactly("pool:3", "pool:5", "pool:7");
    }

    @Test
    @DisplayName("缓存有行但一条都组不出卡片：不报错，退热度榜（半小时内全被下架是正常状态）")
    void invisibleCacheRowsFallBackToHot() {
        cached.add(cachedRow(99L, 0, ColdStart.CHANNEL_USERCF, "已被删除的帖",
            new BigDecimal("0.9"), NOW.minusMinutes(1)));
        poolCard(24L, 777L, "L0");

        PageResult<FeedService.FeedItem> page = service.feed(VIEWER, query(1, 2), NOW);

        assertThat(idsOf(page.getList())).containsExactly(24L);
        assertThat(page.getList().get(0).recallChannel()).isEqualTo(ColdStart.CHANNEL_HOT);
        assertThat(events).noneMatch(event -> event.equals("markExposed"));
    }

    @Test
    @DisplayName("「下一页探针」不算曝光：多取的那一条既不上屏也不记账，否则它被去重窗口压 7 天")
    void probeRowIsNotRecordedAsExposure() {
        poolCard(24L, 777L, "L0");
        poolCard(25L, 777L, "L0");
        poolCard(26L, 777L, "L0");
        assertThat(UserActionCatalog.sampleExpose(VIEWER, 26L)).as("26 也命中采样，才会暴露多记").isTrue();

        PageResult<FeedService.FeedItem> page = service.feed(VIEWER, query(1, 2), NOW);

        assertThat(idsOf(page.getList())).containsExactly(24L, 25L);
        assertThat(page.isHasMore()).as("池子里确实还有下一条").isTrue();
        assertThat(recordedActionTargetIds(UserActionCatalog.ACTION_EXPOSE))
            .as("被裁掉的那条不该记曝光").containsExactly(24L, 25L);
    }

    @Test
    @DisplayName("兜底池翻到底：偏移超出池上限给空页，不发那条必然被截断的 SQL")
    void deepOffsetStopsAtPoolCap() {
        poolCard(1L, 777L, "L0");

        PageResult<FeedService.FeedItem> page = service.feed(VIEWER, query(16, 20), NOW);

        assertThat(page.getList()).isEmpty();
        assertThat(page.isHasMore()).isFalse();
        assertThat(events).noneMatch(event -> event.startsWith("pool:"));
    }

    @Test
    @DisplayName("分组只按 id 尾数：486 走 cf、487 走 hot，读缓存与置曝光用同一个 mode")
    void modeFollowsUserTail() {
        cached.add(cachedRow(SAMPLED_ID, 0, ColdStart.CHANNEL_USERCF, "理由", null,
            NOW.minusMinutes(1)));
        catalogue.put(SAMPLED_ID, card(SAMPLED_ID));

        service.feed(VIEWER, query(1, 20), NOW);
        assertThat(readQueries).containsExactly(RecMode.MODE_CF + ":0:21");
        assertThat(markExposedMode).isEqualTo(RecMode.MODE_CF);

        readQueries.clear();
        markExposedIds.clear();
        service.feed(HOT_VIEWER, query(1, 20), NOW);
        assertThat(readQueries).containsExactly(RecMode.MODE_HOT + ":0:21");
        assertThat(markExposedMode).isEqualTo(RecMode.MODE_HOT);
    }

    @Test
    @DisplayName("size 越界收敛到上限而不是报错（沿用广场那套 PageQuery 口径）")
    void oversizedRequestIsClamped() {
        PageResult<FeedService.FeedItem> page = service.feed(VIEWER, query(1, 500), NOW);

        assertThat(page.getSize()).isEqualTo(PageQuery.MAX_SIZE);
        assertThat(readQueries).containsExactly(RecMode.MODE_CF + ":0:" + (PageQuery.MAX_SIZE + 1));
    }

    @Test
    @DisplayName("「不感兴趣」三步顺序不能反：先记行为事实，再删本尊，最后压邻居并滤掉自己")
    void dislikeRecordsBehaviorBeforeTouchingCache() {
        neighborIds.put(77L, Arrays.asList(78L, 79L, 77L, null));

        FeedService.DismissResult result = service.dislike(VIEWER, 77L, NOW);

        assertThat(result.removed()).isEqualTo(1);
        assertThat(result.removedSimilar()).isEqualTo(2);
        assertThat(dismissedSimilar).containsExactly(78L, 79L);
        assertThat(events).containsExactly("action:dislike:77", "dismiss:77", "neighbors:77",
            "dismissItems:2");
        assertThat(actions).as("邻居只是压缓存行，不该替用户表态两次").hasSize(1);
        assertThat(actions.get(0).getActionType()).isEqualTo(UserActionCatalog.ACTION_DISLIKE);
        assertThat(actions.get(0).getTargetType()).isEqualTo(UserActionCatalog.TARGET_POST);
        assertThat(actions.get(0).getScene()).isEqualTo(UserActionCatalog.SCENE_FEED);
        assertThat(actions.get(0).getWeight()).isEqualByComparingTo("-3");
    }

    @Test
    @DisplayName("冷启动物没有邻居：驳回本尊照样生效，不发空 IN 列表的 SQL")
    void dislikeWithoutNeighborsStillWorks() {
        neighborIds.remove(77L);

        FeedService.DismissResult result = service.dislike(VIEWER, 77L, NOW);

        assertThat(result.removed()).isEqualTo(1);
        assertThat(result.removedSimilar()).as("取不到邻居 = 0，不是异常").isZero();
        assertThat(events).containsExactly("action:dislike:77", "dismiss:77", "neighbors:77");
        assertThat(dismissedSimilar).isEmpty();
    }
}

package com.mindisle.post;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.entity.Post;
import com.mindisle.entity.User;
import com.mindisle.post.dto.PostActionView;

/**
 * 帖子点赞与收藏（任务 3.6 · 需求 FR4.4、BR2、BR4、BR6 · 手册 §6.1 行 3.6）。
 *
 * <p><b>幂等不是「加个唯一索引」就完事，而是三件事同时对</b>：
 * ① 重复点同一个动作不产生第二行活动记录；② 重复请求不产生第二个计数；
 * ③ 重复请求不能报错（用户连点两下、前端重试、网络重发都不是错误）。
 * 唯一索引 {@code uk_action} 只管住第 ① 条的一部分，第 ②③ 条必须在服务层显式设计，
 * 所以本类把「先查活动态 → 复活旧行 → 最后才插入」钉成唯一一条写入路径。</p>
 *
 * <p><b>为什么计数用重算而不是 INCR + 定期回写</b>：BR2 那句话是给浏览量写的
 * （浏览是全站最热的写路径，见 {@link ViewCountService}）。点赞收藏的频率差它两个量级，
 * 而缓存计数器一旦崩溃就会和 post_like 永久对不上——「数字和真相不一致」这种问题
 * 在答辩现场被问一次就说不清。这里每次写之后把 post 的冗余列刷成
 * {@code COUNT(DISTINCT user_id)} 的子查询结果（见 {@link com.mindisle.mapper.PostMapper#refreshLikeCnt}），
 * <b>漂移在结构上不可能发生</b>。代价与边界已写进手册 §14。</p>
 *
 * <p><b>回执里的三个数字全部读真相表</b>，不读刚写进去的那两列：同一条链路上两者必然相等，
 * 但读 post_like 少一次对 post 行的依赖（并发下架、逻辑删除都不影响这次互动回执该报多少），
 * 而且让「响应体里的 likeCnt 等于 post_like 里的活动人数」成为一句可以直接用 SQL 复查的断言。</p>
 *
 * <p><b>自赞：允许，且计入 like_cnt</b>。需求 BR4 的「自赞不计入」针对的是<b>推荐质量分</b>
 * （阶段 7 的 T3.10 行为埋点要用它反推内容质量），不是「这条帖有多少个赞」这个事实。
 * 如果这里偷偷把自赞从计数里扣掉，post.like_cnt 就不再等于 post_like 的行数真相，
 * 上面那条可复查的断言立刻作废。所以本类只回传 {@code selfAction=true} 这个标记，
 * 扣不扣由消费埋点的那一层决定——<b>这是决策，不是漏做</b>。</p>
 *
 * <p><b>不可见的帖子做互动 → 404/30001，绝不 403</b>：与详情接口同一口径
 * （见 {@link PostQueryService#detail}）。报 403 等于向调用者承认「这条存在但你没权限」，
 * 那就成了一条免费的存在性枚举通道，而点赞接口恰恰是最容易被拿来枚举的。</p>
 */
@Service
public class PostInteractionService {

    /** post_like.target_type 的取值，与 DDL 的 ENUM 逐字一致（comment 属任务 3.7）。 */
    static final String TARGET_POST = "post";

    /** post_like.action_type 的取值，DDL ENUM 原文大写。 */
    static final String ACTION_LIKE = "LIKE";
    static final String ACTION_COLLECT = "COLLECT";

    /** 客户端可传的四个动作。白名单只在这里有一份，报错文案由它生成，二者不会分叉。 */
    static final Set<String> REQUESTED_ACTIONS = Set.of("like", "unlike", "collect", "uncollect");

    /**
     * 存储端口（与 {@link AnonymousAliasRepository}、{@link ViewCountService.Flusher} 同一套路）：
     * 把「唯一键语义 + 两条裸 UPDATE + 一次重算」这套只有真库才有的行为推到接口外面，
     * 换来本类的全部规则能被单测逐条钉住——包括「并发跨日出两行活动行」这种
     * 在真机上要撞运气才能复现的场景。真环境适配器见 {@link PostInteractionStoreAdapter}。
     */
    public interface InteractionStore {

        /** 取帖子原始行（含 status/visibility/alias_id），可见性判断在服务层做。 */
        Post findPost(long postId);

        /** 取发起人账号行；查不到即「不存在或已注销」（@TableLogic 自动过滤）。 */
        User findUser(long userId);

        /** 该用户对这个目标当前<b>处于活动态</b>的动作集合，元素为 LIKE / COLLECT。 */
        Set<String> activeActionsOf(long userId, long postId);

        /** 复活已取消的行；返回影响行数，0 表示没有可复活的行。 */
        int reviveCancelled(long userId, long postId, String actionType);

        /** 插入新行；撞 uk_action 返回 0。dayBucket 只在「首次成立」那一天写入。 */
        int insertIgnore(long userId, long postId, String actionType, LocalDate dayBucket);

        /** 取消全部活动行；返回 0 表示本来就没赞/没收藏，不算错误。 */
        int cancelActive(long userId, long postId, String actionType);

        /** 真相计数：多少个不同用户处于活动态。 */
        long countActiveUsers(long postId, String actionType);

        /** 把 post.like_cnt / collect_cnt 刷成真相（两条重算 UPDATE）。 */
        void refreshPostCounts(long postId);
    }

    private final InteractionStore store;
    private final PostingQuotaService quotaService;

    public PostInteractionService(InteractionStore store, PostingQuotaService quotaService) {
        this.store = store;
        this.quotaService = quotaService;
    }

    /**
     * 执行一次互动并回传最新状态。
     *
     * @param actorId   发起人（只来自 JWT，不接受请求体里的 user_id）
     * @param postId    目标帖子
     * @param requested like / unlike / collect / uncollect，大小写与前后空格不敏感
     * @param now       时间基准：既用于「到期销毁」判据，也决定 day_bucket；服务内部不读系统时钟
     */
    @Transactional
    public PostActionView act(long actorId, long postId, String requested, LocalDateTime now) {
        String action = normalize(requested);
        boolean positive = "like".equals(action) || "collect".equals(action);
        String actionType = action.contains("collect") ? ACTION_COLLECT : ACTION_LIKE;

        Post post = store.findPost(postId);
        if (post == null || !PostQueryService.visibleTo(post, actorId, now)) {
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }
        User actor = store.findUser(actorId);
        if (actor == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        // BR6：禁言者照样能点赞收藏，封禁/注销不能。判据与发帖共用 PostingQuotaService 那一份 switch。
        quotaService.assertStatusAllowsInteract(actor);

        Set<String> before = store.activeActionsOf(actorId, postId);
        boolean wasActive = before.contains(actionType);
        // 判据只有一句：「要成的状态」等不等于「现在的状态」。相等就一个字节都不写——
        // 前端双击一下、或者用户在没赞过的点上「取消赞」，都不该产生两条 UPDATE 与一次冗余列回写。
        // 写成 positive != wasActive 而不是分别判两个分支，是为了让「正向幂等」与「负向幂等」
        // 不可能只改对一半（这类「一条规则两个方向」的代码，分叉比漏写更常见）。
        if (positive != wasActive) {
            if (positive) {
                // 顺序不能反：先复活，复活不到才插入。反过来会让 day_bucket 变成「最后一次点赞的日期」，
                // 而这一列的字面语义是「这一赞首次成立的日期」（见 com.mindisle.entity.PostLike）。
                int revived = store.reviveCancelled(actorId, postId, actionType);
                if (revived == 0) {
                    store.insertIgnore(actorId, postId, actionType, now.toLocalDate());
                }
            } else {
                // 一次置全部活动行，天然自愈「并发跨日留下的两行」；不去数影响行数，因为「取消 0 行」不是错误。
                store.cancelActive(actorId, postId, actionType);
            }
            store.refreshPostCounts(postId);
        }

        // 活动态一律以「写完之后再查一次库」为准，而不是内存里推测：
        // 并发下 insertIgnore 可能返回 0（对手刚插完），此时 changed 必须是 false，
        // 用 before 推 would 报出「我改动了」而库里其实一行没变。
        Set<String> after = store.activeActionsOf(actorId, postId);
        boolean liked = after.contains(ACTION_LIKE);
        boolean collected = after.contains(ACTION_COLLECT);
        return new PostActionView(postId, action, after.contains(actionType) != wasActive,
                liked, collected,
                store.countActiveUsers(postId, ACTION_LIKE),
                store.countActiveUsers(postId, ACTION_COLLECT),
                PostQueryService.isOwner(post, actorId));
    }

    /**
     * 动作归一化 + 白名单校验。
     *
     * <p>取值集合走 {@link TreeSet} 排序后再拼文案：{@code Set.of} 的迭代顺序每次 JVM 启动都不同，
     * 直接 join 会让同一句错误提示出现多种字面顺序，前端快照测试与冒烟断言都会跟着随机失败
     * （这条坑任务 3.13 就踩过一次，此处照同一口径处理）。</p>
     */
    static String normalize(String requested) {
        String value = requested == null ? "" : requested.trim().toLowerCase();
        if (!REQUESTED_ACTIONS.contains(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "action 只能是 " + String.join(" / ", new TreeSet<>(REQUESTED_ACTIONS)) + " 之一");
        }
        return value;
    }
}

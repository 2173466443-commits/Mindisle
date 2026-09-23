package com.mindisle.user;

import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.entity.User;
import com.mindisle.notify.NotifyService;
import com.mindisle.post.PostService;
import com.mindisle.post.PostingQuotaService;
import com.mindisle.user.dto.FollowView;
import com.mindisle.user.dto.UserHomepage;

/**
 * 关注关系与主页资料卡（任务 3.6 · 需求 FR1.5、FR4.6、BR6 · 手册 §6.1 行 3.6）。
 *
 * <p><b>为什么关注关系可以物理删、点赞记录不可以</b>：{@code user_follow} 的唯一索引
 * {@code uk_follow_pair} 不认逻辑删除位，留一条软删行会让「再关注同一个人」永远撞键。
 * 关系的价值在于「现在有没有」，行为留痕本来就在 {@code user_action}（任务 3.10），
 * 不必在这张表里再造一份。两套口径的取舍分别写在 {@code UserFollow} 与 {@code PostLike}
 * 的类注释里，不许后来人「统一」成一种。</p>
 *
 * <p><b>两个方向都幂等</b>：重复关注返回 0 行、取关一个没关注的人返回 0 行，两者都<b>不报错</b>。
 * 「按钮被连点两下」和「请求重发」在用户侧不是错误，把它报成 400 只会逼前端去做一层吞错的判断，
 * 而那一层吞掉之后错误码就再也传不上来了。</p>
 *
 * <p><b>计数读侧走真相 COUNT</b>，同时把 {@code user_profile.following_cnt/follower_cnt} 刷成同一个值：
 * 冗余列服务的是管理端与阶段 6 的数据大屏（那里按列排序分页，不能每行现 COUNT），
 * 用户界面则永远读真表——这样「冗余列落后」不会变成一条用户可见的显示故障。
 * 两者是否相等由冒烟脚本之后的 root 直连 SQL 断言（见 docs/dev-log.md 阶段 3）。</p>
 *
 * <p><b>不自创封禁展示策略</b>：目标账号存在性只靠一次 {@code selectById}
 * （{@code @TableLogic} 自动过滤注销），查不到即 20001/404。BANNED 账号的主页要不要显示、
 * 要显示成什么样，需求没写，这里就不替产品决定；<b>封禁只挡「发起人」这一侧</b>（BR6），
 * 那是唯一有明确条文依据的一条。</p>
 *
 * <p><b>关注成立才发通知</b>（任务 T3.11-b · 需求 FR9.1）：判据是 {@code positive && changed}，
 * 复用的就是刷冗余列那个 {@code changed} 标记，于是「按钮被连点两下」既不会多写一行关系、
 * 也不会多发一条提醒。取关方向<b>不撤回</b>已经发出去的那条，理由见 {@code NotifyService} 类注释第 4 条。</p>
 */
@Service
public class RelationshipService {

    /** 可传入的动作白名单，报错文案由它排序后生成（{@code Set.of} 顺序随机，必须 sorted）。 */
    static final Set<String> REQUESTED_ACTIONS = Set.of("follow", "unfollow");

    /**
     * 存储端口（套路同 {@code PostInteractionService.InteractionStore}）：
     * 唯一键幂等、两条 COUNT、一次冗余列刷新，全部推到端口外面，
     * 本类的规则就能在单测里逐条钉住而不依赖真库。
     */
    public interface RelationStore {

        /** 账号行；null 即不存在或已注销。 */
        User findUser(long userId);

        /** 个性签名；没有 user_profile 行时回空串。 */
        String bioOf(long userId);

        /** 关系是否成立。 */
        boolean isFollowing(long userId, long targetUserId);

        /** 幂等关注：撞 uk_follow_pair 返回 0。 */
        int insertFollow(long userId, long targetUserId);

        /** 物理取关：0 行表示本来就没关注。 */
        int deleteFollow(long userId, long targetUserId);

        long countFollowing(long userId);

        long countFollowers(long userId);

        /** 刷新 user_profile 上的两个冗余列。 */
        void refreshFollowCounts(long userId, long followingCnt, long followerCnt);

        /** 公开非匿名帖收到的赞（FR1.5）。 */
        long receivedLikeCnt(long userId);

        /** 公开非匿名已发布帖条数，与主页列表同口径。 */
        long publicPostCnt(long userId);
    }

    private final RelationStore store;
    private final PostingQuotaService quotaService;
    private final NotifyService notifyService;

    public RelationshipService(RelationStore store, PostingQuotaService quotaService, NotifyService notifyService) {
        this.store = store;
        this.quotaService = quotaService;
        this.notifyService = notifyService;
    }

    /**
     * 关注或取关。
     *
     * @param actorId   发起人，只来自 JWT
     * @param targetId  被关注者，来自路径变量（Controller 那条路径只匹配数字）
     * @param requested follow / unfollow
     */
    @Transactional
    public FollowView follow(long actorId, long targetId, String requested) {
        String action = normalize(requested);
        boolean positive = "follow".equals(action);

        User actor = store.findUser(actorId);
        if (actor == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        // BR6：禁言者仍可关注（关注不是「说话」），封禁/注销不行。判据与点赞共用 PostingQuotaService。
        quotaService.assertStatusAllowsInteract(actor);
        if (actorId == targetId) {
            // 结构上就不许存在「关注自己」：它会让 follower_cnt 与「他有多少人看」这件事毫无意义，
            // 还会在阶段 6 的关注者列表里给自己造出一个互推闭环。报参数错而不是 403，
            // 因为这不是权限问题，是这个取值本身没有语义。
            throw new BizException(ErrorCode.PARAM_INVALID, "不能关注自己");
        }
        if (store.findUser(targetId) == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        boolean wasFollowing = store.isFollowing(actorId, targetId);
        if (positive && wasFollowing) {
            // 已经在关注：一次写入都不做。取关方向不做这个短路，因为「删 0 行」本身廉价且无副作用。
        } else if (positive) {
            store.insertFollow(actorId, targetId);
        } else {
            store.deleteFollow(actorId, targetId);
        }

        boolean following = store.isFollowing(actorId, targetId);
        boolean changed = following != wasFollowing;
        if (changed) {
            // 关系一变就同时刷两侧：只刷发起人的话，对方主页上的粉丝数会一直停在旧值上，
            // 而那个数字恰恰是管理端与大屏唯一会去读的列。
            refreshPair(actorId);
            refreshPair(targetId);
            if (positive) {
                // 与冗余列同一个 changed 判据：关注这件事真的发生了，才值得给对方一条提醒。
                // 展示名用 PostService.displayNameOf（昵称空时兜底成「用户+id」），不传原始 nickname 也不传 id。
                notifyService.notifyFollow(targetId, PostService.displayNameOf(actor), actorId);
            }
        }
        return new FollowView(targetId, action, changed, following,
                store.countFollowers(targetId), store.countFollowing(actorId));
    }

    /**
     * 主页资料卡。
     *
     * <p>与 {@code UserPostController#userPosts} 是两个接口而不是一个：那边是翻页列表（要游标、
     * 要 hasMore），这边是一次性对象；合在一起会让资料卡在每次翻页时都被重算一遍
     * （四个 COUNT + SUM，白白多一倍读取代价）。</p>
     */
    public UserHomepage homepage(long viewerId, long targetId) {
        User target = store.findUser(targetId);
        if (target == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        boolean self = viewerId == targetId;
        return new UserHomepage(targetId,
                PostService.displayNameOf(target), target.getAvatar(),
                store.bioOf(targetId),
                store.countFollowing(targetId), store.countFollowers(targetId),
                store.receivedLikeCnt(targetId), store.publicPostCnt(targetId),
                self ? false : store.isFollowing(viewerId, targetId), self);
    }

    /** 把某个账号的两个关注计数刷新进冗余列（先 COUNT 再写，顺序不能反：反了会把旧值写回去）。 */
    private void refreshPair(long userId) {
        long following = store.countFollowing(userId);
        long followers = store.countFollowers(userId);
        store.refreshFollowCounts(userId, following, followers);
    }

    static String normalize(String requested) {
        String value = requested == null ? "" : requested.trim().toLowerCase();
        if (!REQUESTED_ACTIONS.contains(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "action 只能是 " + String.join(" / ", new TreeSet<>(REQUESTED_ACTIONS)) + " 之一");
        }
        return value;
    }
}

package com.mindisle.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mindisle.cache.CaffeineCacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.NotifyMessage;
import com.mindisle.entity.User;
import com.mindisle.notify.RecordingNotifyService;
import com.mindisle.post.PostService;
import com.mindisle.post.PostingQuotaService;
import com.mindisle.user.dto.FollowView;
import com.mindisle.user.dto.UserHomepage;

/**
 * 关注与主页资料卡单测（任务 T3.6 · 需求 FR1.5、FR4.6、BR6）。
 *
 * <p><b>fake 只验接线，不验 SQL 口径</b>：{@code countFollowing} / {@code countFollowers}
 * 在本类里是对内存 List 数数，所以它证明的是「服务在关系变化时确实刷了两侧冗余列、
 * 且刷的值与它自己读到的真相 COUNT 相等」这一条不变式；至于真库上
 * {@code user_follow} 的 {@code uk_follow_pair} 是否真挡得住重复关注、
 * {@code upsertFollowCounts} 在缺 user_profile 行时是否真能补出一行，
 * 由 root 直连 SQL 取证（见 docs/dev-log.md 阶段 3），这里不假装测过。</p>
 *
 * <p>资格判据用真的 {@link PostingQuotaService}，理由同点赞那侧：BR6 是一条跨类规则，
 * 两边各 mock 一份就变成「我以为的一致」。</p>
 */
class RelationshipServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 12, 0);
    private static final long ACTOR_ID = 701L;
    private static final long TARGET_ID = 702L;

    private FakeStore store;
    private RelationshipService service;
    private RecordingNotifyService notify;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        store.users.put(ACTOR_ID, user(ACTOR_ID, "小屿"));
        store.users.put(TARGET_ID, user(TARGET_ID, "  阿屿  "));
        PostingQuotaService quota = new PostingQuotaService(new CaffeineCacheService(), new MindisleProperties());
        notify = new RecordingNotifyService();
        service = new RelationshipService(store, quota, notify.service());
    }

    private static User user(long id, String nickname) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setNickname(nickname);
        user.setStatus("ACTIVE");
        user.setCreatedAt(NOW.minusDays(30));
        return user;
    }

    private static ErrorCode codeOf(Runnable call) {
        return assertThrows(BizException.class, call::run).getErrorCode();
    }

    // ---------- 关注 / 取关 ----------

    @Test
    @DisplayName("关注成功：关系成立、两侧冗余列等于真值、回执带双方计数")
    void followWritesRelationAndRefreshesBothSides() {
        FollowView view = service.follow(ACTOR_ID, TARGET_ID, "follow");
        assertThat(view.changed()).isTrue();
        assertThat(view.following()).isTrue();
        assertThat(view.followerCnt()).isEqualTo(1);
        assertThat(view.myFollowingCnt()).isEqualTo(1);
        assertThat(store.profileFollowing.get(ACTOR_ID)).isEqualTo(1L);
        assertThat(store.profileFollower.get(ACTOR_ID)).isZero();
        assertThat(store.profileFollowing.get(TARGET_ID)).isZero();
        assertThat(store.profileFollower.get(TARGET_ID)).as("对方主页上的粉丝数靠这一列，只刷发起人就会停在旧值").isEqualTo(1L);
    }

    @Test
    @DisplayName("重复关注幂等：changed=false、不报错、第二次零写入")
    void repeatedFollowIsIdempotent() {
        service.follow(ACTOR_ID, TARGET_ID, "follow");
        int writes = store.insertCalls + store.deleteCalls;
        FollowView again = service.follow(ACTOR_ID, TARGET_ID, "follow");
        assertThat(again.changed()).isFalse();
        assertThat(again.following()).isTrue();
        assertThat(again.followerCnt()).isEqualTo(1);
        assertThat(store.rows).hasSize(1);
        assertThat(store.insertCalls + store.deleteCalls).isEqualTo(writes);
    }

    @Test
    @DisplayName("取关是物理删（本表无 deleted 列），且取关一个没关注的人同样幂等不报错")
    void unfollowDeletesTheRowPhysicallyAndIsIdempotent() {
        service.follow(ACTOR_ID, TARGET_ID, "follow");
        FollowView off = service.follow(ACTOR_ID, TARGET_ID, "unfollow");
        assertThat(off.changed()).isTrue();
        assertThat(off.following()).isFalse();
        assertThat(off.followerCnt()).isZero();
        assertThat(store.rows).as("物理删：行直接从表里消失，不留软删占位撞唯一键").isEmpty();
        assertThat(store.profileFollower.get(TARGET_ID)).isZero();

        FollowView again = service.follow(ACTOR_ID, TARGET_ID, "unfollow");
        assertThat(again.changed()).as("取关一个没关注的人：不报错，也不算改动").isFalse();
        assertThat(again.following()).isFalse();
        // 取关方向刻意不短路（见 RelationshipService 里那段注释）：DELETE 影响 0 行本身就是幂等，
        // 比「先查一遍再决定删不删」少一次往返。所以这里确实是两次 DELETE 调用。
        assertThat(store.deleteCalls).isEqualTo(2);
        assertThat(store.insertCalls).as("两次取关都没偷偷插入关系行：整场只有一行").isEqualTo(1);
    }

    @Test
    @DisplayName("删掉再关注：同一对人只有一行，day 无关（uk_follow_pair 不含日期桶）")
    void refollowAfterUnfollowReusesSinglePair() {
        service.follow(ACTOR_ID, TARGET_ID, "follow");
        service.follow(ACTOR_ID, TARGET_ID, "unfollow");
        service.follow(ACTOR_ID, TARGET_ID, "follow");
        assertThat(store.rows).hasSize(1);
        assertThat(service.follow(ACTOR_ID, TARGET_ID, "follow").followerCnt()).isEqualTo(1);
    }

    @Test
    @DisplayName("计数不变式：三个人关注同一个人之后，每一步冗余列都等于真值 COUNT")
    void countsRemainEqualTruthAfterEveryChange() {
        long c = 703L;
        long d = 704L;
        store.users.put(c, user(c, "第三人"));
        store.users.put(d, user(d, "第四人"));
        service.follow(ACTOR_ID, TARGET_ID, "follow");
        service.follow(c, TARGET_ID, "follow");
        assertThat(store.profileFollower.get(TARGET_ID)).isEqualTo(2L);
        service.follow(d, TARGET_ID, "follow");
        service.follow(c, TARGET_ID, "unfollow");
        assertThat(store.countFollowers(TARGET_ID)).isEqualTo(2);
        assertThat(store.profileFollower.get(TARGET_ID)).as("取关也要刷对方那一侧").isEqualTo(2L);
        assertThat(store.countFollowing(c)).isZero();
        assertThat(store.profileFollowing.get(c)).isZero();
        // 被取关的那一侧同样被刷新过
        assertThat(store.profileFollower.get(TARGET_ID)).isEqualTo(store.countFollowers(TARGET_ID));
    }

    @Test
    @DisplayName("读侧回执永远来自真表：冗余列落后时也不影响返回值")
    void responseCountsComeFromTruthTableNotRedundantColumns() {
        service.follow(ACTOR_ID, TARGET_ID, "follow");
        store.profileFollower.put(TARGET_ID, 999L);
        assertThat(service.follow(ACTOR_ID, TARGET_ID, "follow").followerCnt())
                .as("管理端读冗余列、用户界面读真表，这条分界必须能被测出来")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("不能关注自己：10001 参数错而不是 403，且关系一行不写")
    void selfFollowIsRejectedAsInvalidParam() {
        BizException error = assertThrows(BizException.class, () -> service.follow(ACTOR_ID, ACTOR_ID, "follow"));
        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(error.getMessage()).isEqualTo("不能关注自己");
        assertThat(store.rows).isEmpty();
        assertThat(store.profileFollowing).isEmpty();
    }

    @Test
    @DisplayName("BR6：禁言者可关注，封禁者 20003；发起人或目标查无此人 → 20001")
    void qualificationFollowsBR6AndExistence() {
        long mutedId = 705L;
        store.users.put(mutedId, user(mutedId, "禁言"));
        store.users.get(mutedId).setStatus("MUTED");
        assertDoesNotThrow(() -> service.follow(mutedId, TARGET_ID, "follow"));

        long bannedId = 706L;
        store.users.put(bannedId, user(bannedId, "封禁"));
        store.users.get(bannedId).setStatus("BANNED");
        assertThat(codeOf(() -> service.follow(bannedId, TARGET_ID, "follow")))
                .as("封禁只挡发起人这一侧，不自创封禁展示策略")
                .isEqualTo(ErrorCode.USER_DISABLED);
        assertThat(codeOf(() -> service.follow(999_998L, TARGET_ID, "follow")))
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThat(codeOf(() -> service.follow(ACTOR_ID, 999_999L, "follow")))
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThat(store.rows).as("被拦下的请求不留半截关系").hasSize(1);
    }

    @Test
    @DisplayName("动作归一化与白名单：忽略大小写与空格，非法值文案按字典序")
    void actionNormalizationAndWhitelist() {
        assertThat(service.follow(ACTOR_ID, TARGET_ID, "  Follow ").following()).isTrue();
        assertThat(service.follow(ACTOR_ID, TARGET_ID, "UNFOLLOW").following()).isFalse();
        BizException error = assertThrows(BizException.class, () -> service.follow(ACTOR_ID, TARGET_ID, "poke"));
        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(error.getMessage()).isEqualTo("action 只能是 follow / unfollow 之一");
        assertThat(RelationshipService.normalize("UnFollow")).isEqualTo("unfollow");
    }

    // ---------- 主页资料卡 ----------

    @Test
    @DisplayName("资料卡接线：展示名与帖子同一个函数、bio 缺行回空串、self 与 following 判定")
    void homepageWiresEveryField() {
        store.bios.put(TARGET_ID, "今天也在慢慢好起来");
        store.receivedLikes.put(TARGET_ID, 42L);
        store.publicPosts.put(TARGET_ID, 7L);
        service.follow(ACTOR_ID, TARGET_ID, "follow");

        UserHomepage card = service.homepage(ACTOR_ID, TARGET_ID);
        assertThat(card.displayName()).isEqualTo(PostService.displayNameOf(store.users.get(TARGET_ID)));
        assertThat(card.displayName()).as("昵称要 trim，与帖子列表逐字相同").isEqualTo("阿屿");
        assertThat(card.bio()).isEqualTo("今天也在慢慢好起来");
        assertThat(card.userId()).isEqualTo(TARGET_ID);
        assertThat(card.followingCnt()).isZero();
        assertThat(card.followerCnt()).isEqualTo(1);
        assertThat(card.receivedLikeCnt()).isEqualTo(42);
        assertThat(card.publicPostCnt()).isEqualTo(7);
        assertThat(card.following()).isTrue();
        assertThat(card.self()).isFalse();

        UserHomepage mine = service.homepage(ACTOR_ID, ACTOR_ID);
        assertThat(mine.self()).isTrue();
        assertThat(mine.following()).as("没有「关注自己」这种关系，看自己主页恒 false").isFalse();
        assertThat(mine.bio()).as("没有 user_profile 行时回空串，而不是 null")
                .isEmpty();
    }

    @Test
    @DisplayName("主页主人不存在（含被 @TableLogic 过滤的注销账号）：20001/404")
    void homepageOfMissingUserFailsAsNotFound() {
        assertThat(codeOf(() -> service.homepage(ACTOR_ID, 999_999L))).isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    // ---------- T3.11-b：关注写通知 ----------

    @Test
    @DisplayName("关注成功：被关注者收到一条 follow 通知，ref 指向发起人的公开主页")
    void followNotifiesTheFollowedUser() {
        service.follow(ACTOR_ID, TARGET_ID, "follow");
        assertThat(notify.size()).as(notify.dump()).isEqualTo(1);
        NotifyMessage sent = notify.rows().get(0);
        assertThat(sent.getUserId()).isEqualTo(TARGET_ID);
        assertThat(sent.getType()).isEqualTo(NotifyMessage.TYPE_FOLLOW);
        assertThat(sent.getTitle()).as("展示名与主页同一个函数：昵称两侧空白被去掉").isEqualTo("小屿 关注了你");
        assertThat(sent.getRefType()).isEqualTo(NotifyMessage.REF_USER);
        assertThat(sent.getRefId()).isEqualTo(ACTOR_ID);
        assertThat(notify.pushCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("重复关注不再发、取关不发也不撤回：一条关注只对应一条通知")
    void repeatedFollowAndUnfollowSendNothing() {
        service.follow(ACTOR_ID, TARGET_ID, "follow");
        service.follow(ACTOR_ID, TARGET_ID, "follow");
        assertThat(notify.ofType(NotifyMessage.TYPE_FOLLOW)).as("第二次 changed=false").hasSize(1);
        service.follow(ACTOR_ID, TARGET_ID, "unfollow");
        assertThat(notify.size()).as("取关既不发新通知，也撤回不了已发的那条").isEqualTo(1);
    }

    @Test
    @DisplayName("关注不存在的账号 / 关注自己被拒时，一条通知都不写")
    void rejectedFollowWritesNoNotification() {
        codeOf(() -> service.follow(ACTOR_ID, 123_456L, "follow"));
        codeOf(() -> service.follow(ACTOR_ID, ACTOR_ID, "follow"));
        store.users.get(ACTOR_ID).setStatus("BANNED");
        codeOf(() -> service.follow(ACTOR_ID, TARGET_ID, "follow"));
        assertThat(notify.size()).as(notify.dump()).isZero();
    }

    // ---------- 内存 fake ----------

    private final class FakeStore implements RelationshipService.RelationStore {

        private final class Pair {
            private long userId;
            private long targetId;
        }

        private final List<Pair> rows = new ArrayList<>();
        private final Map<Long, User> users = new LinkedHashMap<>();
        private final Map<Long, String> bios = new LinkedHashMap<>();
        private final Map<Long, Long> profileFollowing = new LinkedHashMap<>();
        private final Map<Long, Long> profileFollower = new LinkedHashMap<>();
        private final Map<Long, Long> receivedLikes = new LinkedHashMap<>();
        private final Map<Long, Long> publicPosts = new LinkedHashMap<>();
        private int insertCalls;
        private int deleteCalls;

        @Override
        public User findUser(long userId) {
            return users.get(userId);
        }

        @Override
        public String bioOf(long userId) {
            return bios.getOrDefault(userId, "");
        }

        @Override
        public boolean isFollowing(long userId, long targetUserId) {
            return rows.stream().anyMatch(row -> row.userId == userId && row.targetId == targetUserId);
        }

        @Override
        public int insertFollow(long userId, long targetUserId) {
            insertCalls++;
            boolean exists = rows.stream()
                    .anyMatch(row -> row.userId == userId && row.targetId == targetUserId);
            if (exists) {
                return 0;
            }
            Pair pair = new Pair();
            pair.userId = userId;
            pair.targetId = targetUserId;
            rows.add(pair);
            return 1;
        }

        @Override
        public int deleteFollow(long userId, long targetUserId) {
            deleteCalls++;
            int before = rows.size();
            rows.removeIf(row -> row.userId == userId && row.targetId == targetUserId);
            return before - rows.size();
        }

        @Override
        public long countFollowing(long userId) {
            return rows.stream().filter(row -> row.userId == userId).count();
        }

        @Override
        public long countFollowers(long userId) {
            return rows.stream().filter(row -> row.targetId == userId).count();
        }

        /** 语义对应 upsertFollowCounts：刷完必须等于真相，所以这里直接覆盖写。 */
        @Override
        public void refreshFollowCounts(long userId, long followingCnt, long followerCnt) {
            profileFollowing.put(userId, followingCnt);
            profileFollower.put(userId, followerCnt);
        }

        @Override
        public long receivedLikeCnt(long userId) {
            return receivedLikes.getOrDefault(userId, 0L);
        }

        @Override
        public long publicPostCnt(long userId) {
            return publicPosts.getOrDefault(userId, 0L);
        }
    }
}

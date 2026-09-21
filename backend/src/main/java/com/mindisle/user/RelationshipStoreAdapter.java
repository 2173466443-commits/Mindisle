package com.mindisle.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindisle.entity.User;
import com.mindisle.entity.UserFollow;
import com.mindisle.entity.UserProfile;
import com.mindisle.mapper.PostMapper;
import com.mindisle.mapper.UserFollowMapper;
import com.mindisle.mapper.UserMapper;
import com.mindisle.mapper.UserProfileMapper;
import org.springframework.stereotype.Component;

/**
 * 关注关系存储端口的 MyBatis 适配器（任务 3.6 · {@link RelationshipService.RelationStore} 的真实现）。
 *
 * <p>本类只做翻译，不做判断，也不吞异常（口径同 {@code PostInteractionStoreAdapter}）。
 * 唯一一处「像是判断」的地方是 {@link #bioOf}：没有 user_profile 行时回空串。
 * 那不是兜底，而是把注册链路的历史数据现实（早期账号可能没有资料行）收敛在一处，
 * 免得主页 DTO 出现 {@code bio=null} 与「资料行不存在」两种看起来不同的空。</p>
 */
@Component
public class RelationshipStoreAdapter implements RelationshipService.RelationStore {

    private final UserMapper userMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserFollowMapper userFollowMapper;
    private final PostMapper postMapper;

    public RelationshipStoreAdapter(UserMapper userMapper, UserProfileMapper userProfileMapper,
                                    UserFollowMapper userFollowMapper, PostMapper postMapper) {
        this.userMapper = userMapper;
        this.userProfileMapper = userProfileMapper;
        this.userFollowMapper = userFollowMapper;
        this.postMapper = postMapper;
    }

    @Override
    public User findUser(long userId) {
        return userMapper.selectById(userId);
    }

    @Override
    public String bioOf(long userId) {
        UserProfile profile = userProfileMapper.selectById(userId);
        return profile == null || profile.getBio() == null ? "" : profile.getBio();
    }

    /**
     * 用 selectCount 而不是 selectOne：这条判据只关心「有没有」，
     * 取整行要把 created_at 也拉回来，而它在 uk_follow_pair 上是回表列——白读一列。
     */
    @Override
    public boolean isFollowing(long userId, long targetUserId) {
        return userFollowMapper.selectCount(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getUserId, userId)
                .eq(UserFollow::getFollowUserId, targetUserId)) > 0;
    }

    @Override
    public int insertFollow(long userId, long targetUserId) {
        return userFollowMapper.insertIgnore(userId, targetUserId);
    }

    @Override
    public int deleteFollow(long userId, long targetUserId) {
        return userFollowMapper.deletePair(userId, targetUserId);
    }

    @Override
    public long countFollowing(long userId) {
        return userFollowMapper.countFollowing(userId);
    }

    @Override
    public long countFollowers(long userId) {
        return userFollowMapper.countFollowers(userId);
    }

    @Override
    public void refreshFollowCounts(long userId, long followingCnt, long followerCnt) {
        userFollowMapper.upsertFollowCounts(userId, followingCnt, followerCnt);
    }

    @Override
    public long receivedLikeCnt(long userId) {
        return postMapper.sumReceivedLikes(userId);
    }

    @Override
    public long publicPostCnt(long userId) {
        return postMapper.countPublicPosts(userId);
    }
}

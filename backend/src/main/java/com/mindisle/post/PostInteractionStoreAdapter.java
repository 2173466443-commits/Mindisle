package com.mindisle.post;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindisle.entity.Post;
import com.mindisle.entity.PostLike;
import com.mindisle.entity.User;
import com.mindisle.mapper.PostLikeMapper;
import com.mindisle.mapper.PostMapper;
import com.mindisle.mapper.UserMapper;
import org.springframework.stereotype.Component;

/**
 * 点赞/收藏存储端口的 MyBatis 适配器（任务 3.6 · {@link PostInteractionService.InteractionStore} 的真实现）。
 *
 * <p>与 {@link AnonymousAliasRepositoryAdapter} 同一套路：端口是给单测用的，本类只做「翻译」，
 * 不掺任何业务判断。特别地，<b>这里不吞任何数据库异常</b>：表不存在、连接断开一律照常抛出，
 * 由 GlobalExceptionHandler 统一转成 90002/503。端口里那几个 insertIgnore/cancelActive
 * 返回 0 是「唯一键吞掉」与「本来就没有行」的<b>业务</b>幂等，不是错误兜底，两者不能混。</p>
 */
@Component
public class PostInteractionStoreAdapter implements PostInteractionService.InteractionStore {

    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final PostLikeMapper postLikeMapper;

    public PostInteractionStoreAdapter(PostMapper postMapper, UserMapper userMapper,
                                       PostLikeMapper postLikeMapper) {
        this.postMapper = postMapper;
        this.userMapper = userMapper;
        this.postLikeMapper = postLikeMapper;
    }

    @Override
    public Post findPost(long postId) {
        return postMapper.selectById(postId);
    }

    @Override
    public User findUser(long userId) {
        return userMapper.selectById(userId);
    }

    /**
     * 走 Wrapper 而不是裸 SQL：{@code PostLike} 上挂了 {@code @TableLogic}，
     * selectList 会自动追加 {@code deleted = 0}，「活动态」这个概念因此只由逻辑删除位一处定义。
     * 哪天有人把 deleted 改成别的列名或值，这里跟着 global-config 一起变，不至于漏改一处 SQL。
     */
    @Override
    public Set<String> activeActionsOf(long userId, long postId) {
        List<PostLike> rows = postLikeMapper.selectList(new LambdaQueryWrapper<PostLike>()
                .eq(PostLike::getUserId, userId)
                .eq(PostLike::getTargetType, PostInteractionService.TARGET_POST)
                .eq(PostLike::getTargetId, postId));
        Set<String> actions = new HashSet<>();
        for (PostLike row : rows) {
            if (row.getActionType() != null) {
                actions.add(row.getActionType());
            }
        }
        return actions;
    }

    @Override
    public int reviveCancelled(long userId, long postId, String actionType) {
        return postLikeMapper.reviveCancelled(userId, PostInteractionService.TARGET_POST, postId, actionType);
    }

    @Override
    public int insertIgnore(long userId, long postId, String actionType, LocalDate dayBucket) {
        return postLikeMapper.insertIgnore(userId, PostInteractionService.TARGET_POST, postId, actionType, dayBucket);
    }

    @Override
    public int cancelActive(long userId, long postId, String actionType) {
        return postLikeMapper.cancelActive(userId, PostInteractionService.TARGET_POST, postId, actionType);
    }

    @Override
    public long countActiveUsers(long postId, String actionType) {
        return postLikeMapper.countActiveUsers(PostInteractionService.TARGET_POST, postId, actionType);
    }

    @Override
    public void refreshPostCounts(long postId) {
        postMapper.refreshLikeCnt(postId);
        postMapper.refreshCollectCnt(postId);
    }
}

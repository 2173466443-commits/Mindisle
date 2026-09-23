package com.mindisle.search;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindisle.common.Keyword;
import com.mindisle.common.LikePattern;
import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.Topic;
import com.mindisle.entity.User;
import com.mindisle.mapper.TopicMapper;
import com.mindisle.mapper.UserMapper;
import com.mindisle.search.dto.TopicHit;
import com.mindisle.search.dto.UserHit;
import org.springframework.stereotype.Service;

/**
 * 关键词搜话题与搜人（任务 3.9 · 手册 §6.1 行 3.9 · 需求 FR4.8）。
 *
 * <p><b>搜帖不在本类</b>：帖子检索必须与广场共用同一套可见性判据、游标翻页与批量取周边
 * （{@link com.mindisle.post.PostQueryService#search}）。把那份 WHERE 抄进搜索域，
 * 就是给「列表里已经没了、搜索里还能搜到」这类事故制造第二个现场 —— 手册 §14 第 27 条
 * 记的正是「同一判据两处实现」的后果。</p>
 *
 * <p><b>本类只碰两张与内容可见性无关的表</b>：topic 与 user。两者的实体上都有
 * {@code @TableLogic}，逻辑删除行由框架自动排除，所以这里不手写 {@code deleted = 0}；
 * 但帖子搜索里那条裸 SQL 的 {@code EXISTS(post_topic JOIN topic)} 没有这层保护，
 * 那边必须手写（见 {@code PostQueryService#applyLikeMatch}）。同一个「删除位」在
 * 两条路径上由两个地方负责，是 Wrapper 与裸 SQL 的分工，不是遗漏。</p>
 */
@Service
public class SearchService {

    /** 与 topic.audit_status 的 ENUM 逐字一致：未过审 / 被驳回的话题不该被检索到。 */
    static final String TOPIC_APPROVED = "APPROVED";

    /** 与 user.status 的 ENUM 逐字一致：MUTED / BANNED / DELETED（注销冷静期）都不出现在「搜人」里。 */
    static final String USER_ACTIVE = "ACTIVE";

    /** limit 缺省值：与话题墙一致的一屏十条。 */
    static final int DEFAULT_LIMIT = 10;

    private final TopicMapper topicMapper;
    private final UserMapper userMapper;
    private final MindisleProperties properties;

    public SearchService(TopicMapper topicMapper, UserMapper userMapper, MindisleProperties properties) {
        this.topicMapper = topicMapper;
        this.userMapper = userMapper;
        this.properties = properties;
    }

    /**
     * 话题名检索。<b>不限官方</b>：官方只是话题墙（{@code GET /api/topics}）的展示口径，
     * 用户主动搜索时把非官方话题藏起来没有依据 —— 只要它已过审。
     */
    public List<TopicHit> topics(String keyword, Integer limit) {
        String pattern = pattern(keyword);
        LambdaQueryWrapper<Topic> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Topic::getAuditStatus, TOPIC_APPROVED)
                .apply("name LIKE {0} ESCAPE '!'", pattern)
                .orderByDesc(Topic::getHotScore).orderByAsc(Topic::getId)
                // cap 是夹进 1..max-profiles 的 int，拼进 limit 没有注入面（口径同 TopicMapper#listOfficialApproved）
                .last("limit " + cap(limit));
        return topicMapper.selectList(wrapper).stream().map(TopicHit::of).toList();
    }

    /**
     * 按昵称 / 登录名搜人。
     *
     * <p><b>为什么这里搜昵称、搜帖那条路不搜昵称</b>：主页资料卡本身就是实名展示，
     * 从昵称找到一个人的公开账号是产品功能（FR1.5）；而「用昵称命中一条帖子」会把
     * 该帖与真实作者的关联建立在读侧，匿名帖因此失去保护（FR1.4）。两条路口径不同是刻意的，
     * 各自的理由写在各自的方法上，别在下一个改动里「顺手统一」。</p>
     */
    public List<UserHit> users(String keyword, Integer limit) {
        String pattern = pattern(keyword);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getStatus, USER_ACTIVE)
                .and(hit -> hit.apply("nickname LIKE {0} ESCAPE '!'", pattern)
                        .or().apply("username LIKE {0} ESCAPE '!'", pattern))
                .orderByAsc(User::getId)
                .last("limit " + cap(limit));
        return userMapper.selectList(wrapper).stream().map(UserHit::of).toList();
    }

    /** 归一 + 转义一次做完：两个入口都必须走这里，避免出现「一个入口忘了判空」。 */
    private String pattern(String keyword) {
        String kw = Keyword.normalize(keyword, properties.getSearch().getMaxKeywordChars());
        return LikePattern.contains(kw);
    }

    /**
     * 返回条数：夹进 1..{@code mindisle.search.max-profiles}。
     *
     * <p>非法 limit 按默认值处理而不是报错 —— 这是显示量，不改变结果语义，报错只会让前端
     * 的多传参数变成 400。与 {@code PageQuery.normalize()} 的取向一致（那里也是收敛而非抛错）。</p>
     */
    int cap(Integer limit) {
        int max = properties.getSearch().getMaxProfiles();
        if (limit == null || limit < 1) {
            return Math.min(DEFAULT_LIMIT, max);
        }
        return Math.min(limit, max);
    }
}
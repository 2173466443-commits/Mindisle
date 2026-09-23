package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.Topic;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 话题 Mapper（手册 §5.2 T2.5 · 行 3.8 补齐计数与重名检查）。 */
@Mapper
public interface TopicMapper extends BaseMapper<Topic> {

  /**
   * 公开话题墙：官方 且 已过审，按热度倒序（需求 FR1.7 / §6.2 热度排序）。
   *
   * <p>这是阶段 2 里唯一「真实跑通业务语义」的内容查询，Gate 2 的 20 个接口靠它凑够内容域计数。
   */
  default List<Topic> listOfficialApproved(int limit) {
    return selectList(new LambdaQueryWrapper<Topic>()
        .eq(Topic::getIsOfficial, 1)
        .eq(Topic::getAuditStatus, "APPROVED")
        .orderByDesc(Topic::getHotScore)
        .orderByAsc(Topic::getId)
        .last("limit " + Math.max(1, Math.min(limit, 50))));
  }

  /**
   * 按名字取话题，<b>含被逻辑删除的那一行</b>。
   *
   * <p>走裸 SQL 而不是 {@code selectOne(name=?)} 是必须的：后者会被
   * {@code Topic} 上的 {@code @TableLogic} 自动追加 {@code deleted = 0}，于是
   * 「一个已被下架的同名话题」查不出来，创建时就只能等到 INSERT 撞 1062 才发现重名。
   * 而 {@code uk_name} 建在 name 单列上、<b>不含 deleted</b>（{@code sql/04_community.sql} 表 15），
   * 也就是说软删话题照样占着这个名字 —— 先把这一事实读出来，才能给用户一句
   * 「这个话题已经存在」而不是一个 500。</p>
   *
   * <p>只取一行：{@code uk_name} 保证同名至多一条，加 LIMIT 是防「将来有人改了唯一索引」
   * 时这个方法直接抛 {@code TooManyResultsException}（那会变成 90004，比原来更难查）。</p>
   */
  @Select("SELECT * FROM topic WHERE name = #{name} LIMIT 1")
  Topic findByName(@Param("name") String name);

  /**
   * 话题下「公开可见帖数」重算（任务 3.8 · 手册 §18 Gate3「话题页帖子数=关联表 count」）。
   *
   * <p><b>替换掉原来的 {@code post_cnt = post_cnt + 1}</b>：自增版本有两个补不回来的洞 ——
   * ① 它只在 {@code finalStatus=PUBLISHED} 时加一，于是「进人审、后来被管理员通过」的那批帖
   * 永远没被计入（管理端 T6.1 还没有回调刷计数的地方），话题页会出现
   * 「点进去有这条帖、卡片计数里没有它」；② 帖子被下架 / 到期销毁之后也没人把它减回去。
   * 重算版本每次发帖把这一列刷成真相，两个洞一起堵上，口径与 {@code refreshFollowCnt} 一致。</p>
   *
   * <p><b>计数口径 = 话题页第一屏能看到的口径</b>（{@code PostQueryService#applyVisible} 里
   * 与查看者无关的那一半）：已发布 + public + 未到期 + 未删除。
   * 查看者自己的待审帖<b>不</b>计进来 —— 它是「仅自己可见」的，若计入就会造出
   * 「卡片写 3 条、点进去别人只有 2 条」，正是 {@code refreshCommentCnt} 注释里避开的同一个坑。</p>
   *
   * <p>子查询读 post_topic / post、更新的是 topic，三张表互不重叠，不触发
   * {@code ER_UPDATE_TABLE_USED}；{@code pt.topic_id = topic.id} 是相关子查询，走外层行值。</p>
   *
   * <p>副作用要写明白：这条 UPDATE 会顺带把 {@code topic.updated_at} 推到当前时间
   * （DDL 的 {@code ON UPDATE CURRENT_TIMESTAMP}），而 {@code updated_at} 今天没有任何界面在读。
   * 将来谁要显示「话题创建时间」请读 {@code created_at}，别拿 {@code updated_at} 当它。</p>
   */
  @Update("UPDATE topic SET post_cnt = "
      + "(SELECT COUNT(*) FROM post_topic pt JOIN post p ON p.id = pt.post_id "
      + "WHERE pt.topic_id = topic.id AND p.deleted = 0 AND p.status = 'PUBLISHED' "
      + "AND p.visibility = 'public' "
      + "AND (p.auto_destroy_at IS NULL OR p.auto_destroy_at > NOW(3))) "
      + "WHERE id = #{topicId} AND deleted = 0")
  int refreshPostCnt(@Param("topicId") long topicId);
}

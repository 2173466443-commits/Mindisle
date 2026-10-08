package com.mindisle.mapper;

import com.mindisle.admin.dto.LabelCountRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 落地页「社区脉搏」聚合 Mapper（任务 T3.13 结转项 U1 · 需求分析文档 §6 U1 行）。
 *
 * <p><b>为什么只有三条语句</b>：U1 首页是全站唯一一个「不登录也要长得起来」的页面，
 * 它需要的不是数据，是<b>氛围</b>——发帖热度、今日签到、被温暖次数、精选笔记。
 * 因此这里只做聚合与取 id，一条明细都不取：明细一律交给
 * {@code PostQueryService#feedCards} 走公共可见性判据（见 {@code CommunityPulseService} 类注释）。
 * 在这个类里多写一句 JOIN user 就是在绕开那道判据。</p>
 *
 * <p><b>返回 {@code Map} 而不是新建 DTO</b>：与 {@code DashboardMapper#aiUsageRows} 同一口径。
 * 落地页的读数只有一份消费方（LandingView），而且它全部是「数字 + 一个占比」，
 * 为它开一个 DTO 只是多一层会漂移的翻译。注意 MyBatis 对 {@code Map} 结果<b>不做</b>
 * 下划线转驼峰（{@code MapWrapper#findProperty} 原样返回列名），
 * 所以下面的别名写成什么，接口 JSON 的键就是什么——这里刻意直接写 camelCase，
 * 让「SQL 别名 == 响应字段名」这条等式在落地页这一处是可见的。</p>
 *
 * <p><b>时间窗口全部由调用方传参</b>，SQL 里不写 {@code CURDATE()} / {@code NOW()}，
 * 理由与 {@code DashboardService} 完全相同：单测要能钉住「现在几点」，
 * 而且各处各写各的当天会造出「两张卡片对不上同一个日期」的口径漂移。</p>
 */
@Mapper
public interface CommunityPulseMapper {

  /**
   * 近 N 日的九个数，同一条 SQL、同一刻快照。
   *
   * <p>拆成一条的理由和大屏指标卡一样：分页与多次查询会让「今日发帖数」大于「近 7 日发帖数」
   * 这种事真的发生出来（两次查询之间有人发了帖）。一次快照在结构上排除它。</p>
   *
   * <p>口径逐条说明，避免下一轮把「被温暖」理解错：
   * ① 帖子侧三条都要求 {@code status='PUBLISHED' AND visibility='public'}，
   * 与广场 {@code applyVisible} 的开放分支逐字同判据——草稿和仅自己可见的帖不该出现在公开页；
   * ② {@code checkinUsers} 只算 {@code source='checkin'}：需求 FR3.1 的「签到」是主动打卡，
   * 被动识别（chat/post）不是签到，混进去会把数写得比真人多；
   * ③ {@code comfortCnt}「被温暖的次数」= 近 N 日别人的笔记被点赞的次数，取 {@code post_like}
   * （需求 §5.1 表 18，项目自己称它为点赞的<b>真相表</b>），{@code deleted=0} 才算「还点着」；
   * <b>不取 {@code user_action} 埋点流</b>：那张表按 {@code uk_action(user,target,action,day_bucket)}
   * 同日幂等，2026-10-08 同窗口现量只有 32 条，而 {@code post_like} 是 111 条 —— 差的正是
   * 「同一个人同一天反复点」与没接埋点的入口。落地页要的是「这周发生了多少次善意」，
   * 拿一张为协同过滤去重过的表回答这句话，等于系统性低报社区的温度；
   * 也不取 {@code post.like_cnt} 冗余列之和：{@code PostMapper} 的注释已经写明它是会掉队的计数器；
   * ④ {@code valenceAvg} 用 {@code ROUND(AVG(valence), 3)}：valence 是 -1/0/1 三档 TINYINT，
   * 均值落在 [-1,1]，留三位是给前端的「+0.42」这种显示用的，不做二次四舍五入；
   * ⑤ {@code positiveRatio} 分母用 {@code NULLIF(COUNT(*), 0)}：窗口内一条记录都没有时
   * 返回 null（前端显示「-」），而不是 0——「0% 正向」和「还没有数据」是两句不同的话。</p>
   */
  @Select("SELECT "
      + " (SELECT COUNT(*) FROM post "
      + "   WHERE deleted = 0 AND status = 'PUBLISHED' AND visibility = 'public' "
      + "     AND created_at >= #{fromTime}) AS postCnt, "
      + " (SELECT COUNT(*) FROM post "
      + "   WHERE deleted = 0 AND status = 'PUBLISHED' AND visibility = 'public' "
      + "     AND type = 'hole' AND created_at >= #{fromTime}) AS holeCnt, "
      + " (SELECT COUNT(*) FROM post "
      + "   WHERE deleted = 0 AND status = 'PUBLISHED' AND visibility = 'public' "
      + "     AND created_at >= #{todayTime}) AS todayPostCnt, "
      + " (SELECT COUNT(DISTINCT user_id) FROM emotion_record "
      + "   WHERE deleted = 0 AND source = 'checkin' AND record_date >= #{fromDate}) AS checkinUsers, "
      + " (SELECT COUNT(*) FROM emotion_record "
      + "   WHERE deleted = 0 AND source = 'checkin' AND record_date >= #{fromDate}) AS checkinCnt, "
      + " (SELECT ROUND(AVG(valence), 3) FROM emotion_record "
      + "   WHERE deleted = 0 AND record_date >= #{fromDate}) AS valenceAvg, "
      + " (SELECT ROUND(SUM(CASE WHEN valence = 1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 3) "
      + "   FROM emotion_record WHERE deleted = 0 AND record_date >= #{fromDate}) AS positiveRatio, "
      + " (SELECT COUNT(*) FROM post_like "
      + "   WHERE deleted = 0 AND target_type = 'post' AND action_type = 'LIKE' "
      + "     AND created_at >= #{fromTime}) AS comfortCnt, "
      + " (SELECT COUNT(*) FROM `user` "
      + "   WHERE deleted = 0 AND created_at >= #{fromTime}) AS newUserCnt")
  Map<String, Object> snapshot(@Param("fromTime") LocalDateTime fromTime,
      @Param("todayTime") LocalDateTime todayTime, @Param("fromDate") LocalDate fromDate);

  /**
   * 近 N 日的情绪标签分布（8 类，含 neutral）。
   *
   * <p>复用大屏那个 {@link LabelCountRow} 而不是再造一行对象：列就叫 label / cnt，
   * 两处要的东西本来就是同一件事。标签值域是 {@code joy/trust/anger/sadness/fear/disgust/neutral}
   * （{@code emotion_record.label} 的注释与前端 EmotionPill 的映射表同一份），
   * 中文名与颜色归前端，后端不翻译——翻译一次就多一处和 theme.css 色板不同步的可能。</p>
   */
  @Select("SELECT label, COUNT(*) AS cnt FROM emotion_record "
      + "WHERE deleted = 0 AND label IS NOT NULL AND record_date >= #{fromDate} "
      + "GROUP BY label ORDER BY cnt DESC")
  List<LabelCountRow> emotionLabels(@Param("fromDate") LocalDate fromDate);

  /**
   * 精选笔记的 id（落地页卡墙的数据源）。
   *
   * <p><b>只出 id，不出卡片</b>：可见性的唯一判据在 {@code PostQueryService#applyVisible}，
   * 在这里 JOIN user 判作者是否活跃、或者顺手把 title 也捞出来，都是给那道判据开第二条实现路径。
   * 排序取「官方置顶/加精优先，其次质量分，再次点赞数」，因为落地页要展示的是社区里
   * 最好的一面，而不是最新的一面（最新的已经在广场了）。</p>
   *
   * <p><b>同题只取一条</b>（{@code ROW_NUMBER() OVER (PARTITION BY title)}）：这一条不是防御性代码，
   * 是 2026-10-08 首屏截图逼出来的。库里 12 篇「冒烟·匿名树洞」是阶段闸门的一次性夹具，
   * 质量分都是满分 1.0000、作者各不相同，按分数取前 12 名会把落地页拍成一整屏复制粘贴——
   * 游客看到的第一眼是「这岛上是机器人」，而这正是需求 §2 要消除的那件事。
   * 无标题的帖子用 id 兜底分区，不会被并成一类。</p>
   *
   * <p>不用 {@code recommend_result}：那是个性化离线批次，游客没有 user_id，
   * 拿别人的批次结果给所有人看，等于把对照组当实验组卖（与手册 §10.1 的口径一致）。</p>
   */
  @Select("SELECT id FROM ("
      + "  SELECT id, is_featured, is_top, quality_score, like_cnt, "
      + "         ROW_NUMBER() OVER ("
      + "           PARTITION BY COALESCE(title, CONCAT('#untitled', id)) "
      + "           ORDER BY is_featured DESC, is_top DESC, quality_score DESC, "
      + "                    like_cnt DESC, id DESC) AS rn "
      + "  FROM post "
      + "  WHERE deleted = 0 AND status = 'PUBLISHED' AND visibility = 'public' "
      + "    AND created_at >= #{fromTime} "
      + ") picked WHERE picked.rn = 1 "
      + "ORDER BY picked.is_featured DESC, picked.is_top DESC, picked.quality_score DESC, "
      + "         picked.like_cnt DESC, picked.id DESC "
      + "LIMIT #{limit}")
  List<Long> featuredIds(@Param("fromTime") LocalDateTime fromTime, @Param("limit") int limit);
}

package com.mindisle.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.ItemSimilarity;

/**
 * ItemCF 相似度缓存 Mapper（任务 T7.3 / T7.4 · 手册 §10.2 第 7.3、7.4 条）。
 *
 * <p><b>这张表只有一个写入者</b>：{@code OfflineRecommendService}。在线读侧（相似位、
 * 相关推荐）永不写它 —— 需求 §6.2 把「离线算相似度、在线只查表」当成 P95 ≤ 200ms 的
 * 前提，一旦在线侧也能写，缓存的时效假设就塌了。</p>
 *
 * <p>批量 upsert 用 {@code <script>} + {@code <foreach>}：本项目没有任何 XML mapper 文件
 * （{@code application.yml} 里 {@code mapper-locations} 指向的目录是空的），全部裸 SQL 都以
 * 注解形式存在，与 {@link UserActionMapper}、{@link PostLikeMapper} 同一口径。
 * 换 XML 只会让「一条 SQL 在哪儿」多一个搜索面。</p>
 */
@Mapper
public interface ItemSimilarityMapper extends BaseMapper<ItemSimilarity> {

  /**
   * 批量幂等写入相似度。
   *
   * <p>{@code item_similarity} 的主键就是 {@code item_id}（一帖一行），离线作业每 30 分钟
   * 把全量邻居重算一遍，撞主键是<b>常态</b>而不是异常，所以必须 ON DUPLICATE KEY UPDATE。
   * 用 {@code INSERT IGNORE} 的话，邻居分数永远停在第一次算出来的值上，
   * 「新帖被点赞后邻居关系变化」这件事就再也不会反映进缓存。</p>
   *
   * <p>{@code deleted = 0} 同样要写：帖子下架时 {@link #markDeletedByPost} 会把它置 1，
   * 帖子后来恢复（申诉通过）时这一位必须跟着复活，否则相似位永久缺一帖而日志里
   * 看不出原因。</p>
   *
   * <p>{@code calc_at} 由调用方显式传入而不是靠 {@code DEFAULT CURRENT_TIMESTAMP(3)}：
   * 同一批次的所有行必须共享<b>同一个</b>时间戳，否则「这批算完了吗」无法用
   * {@code MIN(calc_at)} 判断（{@link #lastBatchCalcAt}）。列上有
   * {@code ON UPDATE CURRENT_TIMESTAMP(3)}，显式传值会覆盖默认行为，这是刻意的。</p>
   *
   * <p>空集合防护在调用方：{@code <foreach>} 拼出 {@code VALUES } 后面什么都没有时
   * MySQL 报 1064，而「这一批没有可算的物品」是冷启动期的正常状态，不该以语法错误收场。</p>
   */
  @Insert("<script>INSERT INTO item_similarity (item_id, sim_items, neighbor_cnt, calc_at, deleted) VALUES "
      + "<foreach collection='rows' item='r' separator=','>"
      + "(#{r.itemId}, #{r.simItems}, #{r.neighborCnt}, #{r.calcAt}, 0)"
      + "</foreach>"
      + " ON DUPLICATE KEY UPDATE sim_items = VALUES(sim_items), neighbor_cnt = VALUES(neighbor_cnt),"
      + " calc_at = VALUES(calc_at), deleted = 0</script>")
  int batchUpsert(@Param("rows") List<ItemSimilarity> rows);

  /**
   * 帖子下架 / 注销时让它的相似度行失效（逻辑删除），避免下架内容继续被相似位带出来。
   *
   * <p>返回 0 表示这一帖从来没有相似度行（冷启动），不是错误。</p>
   */
  @Update("UPDATE item_similarity SET deleted = 1 WHERE item_id = #{itemId} AND deleted = 0")
  int markDeletedByPost(@Param("itemId") long itemId);

  /**
   * 反向恢复：帖子从 TAKEDOWN / DELETED 回到 PUBLISHED 时清掉逻辑删除位。
   *
   * <p>不重新算邻居，只把行放回来 —— 邻居是「这一帖和谁像」，和这一帖能不能被看是两件事，
   * 后者由 {@code recommend_result} 在线组装时的可见性过滤负责。</p>
   */
  @Update("UPDATE item_similarity SET deleted = 0 WHERE item_id = #{itemId} AND deleted = 1")
  int reviveByPost(@Param("itemId") long itemId);

  /** 读一帖的邻居（在线相似位）。deleted 在这里显式判，返回 null = 这一帖没有相似度缓存行。 */
  @Select("SELECT item_id, sim_items, neighbor_cnt, calc_at, deleted, created_at, updated_at "
      + "FROM item_similarity WHERE item_id = #{itemId} AND deleted = 0")
  ItemSimilarity selectByItem(@Param("itemId") long itemId);

  /** 有邻居的行数（AdminRecController 的「缓存健康度」，0 = 离线作业从没成功过）。 */
  @Select("SELECT COUNT(*) FROM item_similarity WHERE deleted = 0 AND neighbor_cnt > 0")
  long countWithNeighbors();

  /**
   * 最近一次成功批次的时刻：取所有行里<b>最大</b>的 calc_at。
   *
   * <p>为什么不是 MIN：MIN 会被「批次跑到一半失败」的行拖住 —— 那一批里早写的行 calc_at 很旧，
   * 看起来像「缓存过期了三小时」。运维要的答案是「最后一次写成功是什么时候」，那是 MAX。</p>
   */
  @Select("SELECT MAX(calc_at) FROM item_similarity")
  LocalDateTime lastBatchCalcAt();
}
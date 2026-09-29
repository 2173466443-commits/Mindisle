package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * ItemCF 相似度缓存 item_similarity（任务 T7.3 · 需求 §7.2 #22 · 手册 §7.5「离线算 Top-K，在线只查表」）。
 *
 * <p><b>本表的主键是 item_id，不是自增 id</b>（ER 文档 §3 第 20 行「主键 item_id」）。
 * 因此 {@code @TableId} 必须写 {@code type = IdType.INPUT}：留默认的 AUTO，
 * MyBatis-Plus 会在 INSERT 语句里省掉这一列并回读一个不存在的自增值，
 * 报错形态是「Unknown column 'id'」而不是「主键冲突」，很容易被误判成建表没跑。</p>
 *
 * <p><b>一帖一行</b>：邻居全部装在 {@code sim_items} 这个 JSON 数组里
 * （{@code [{"item":101,"score":0.83},...]}），不拆子表 —— 手册 §5.1 DDL 规范 2 明确
 * 「嵌套结构用原生 json 列」。读侧永远是「按 item_id 取一行」，拆表的收益（能按邻居排序）
 * 在这个访问形态下用不上，代价却是一次 JOIN。</p>
 *
 * <p>{@code neighbor_cnt = 0} 是一个有含义的值：它表示这一帖进不了 ItemCF 召回
 * （冷启动或共现人数不足），在线侧可以直接用它跳过，不必解析 JSON。
 * 所以这个数必须与 JSON 数组长度一致，由 {@code OfflineRecommendService} 在同一处写入。</p>
 */
@Data
@TableName("item_similarity")
public class ItemSimilarity {

  /** 主键即帖子 id（无自增），逻辑外键 post.id。 */
  @TableId(value = "item_id", type = IdType.INPUT)
  private Long itemId;

  /** Top-K 邻居 JSON 数组，已按分数降序、同分按 item 升序（离线可复现的排序口径）。 */
  private String simItems;

  /** 邻居条数，与 sim_items 数组长度一致；0 = 本帖不参与 ItemCF 召回。 */
  private Integer neighborCnt;

  /** 最后一次离线计算时间，过久视为过期（在线侧据此判断要不要退回热度兜底）。 */
  private LocalDateTime calcAt;

  /** 逻辑删除：帖子下架时同步失效，避免下架内容继续从相似位被带出来。 */
  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
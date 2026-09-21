package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.Post;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 帖子 Mapper（任务 3.3 · 手册 §5.2）。
 *
 * <p>与 {@code UserMapper} 一样不写 XML：单表条件查询用 LambdaQueryWrapper 足够。
 * 目前只有楼层号一条裸 SQL，因为聚合函数在 Wrapper 里表达反而更绕。</p>
 */
@Mapper
public interface PostMapper extends BaseMapper<Post> {

  /**
   * 取下一个树洞楼层号（手册 §5.1 v1.1.2 补列「全局连续递增」）。
   *
   * <p><b>已知妥协，写白不藏</b>：MAX+1 是「读—算—写」三步，并发下两帖可能拿到同一楼层号。
   * {@code floor_no} 上没有唯一索引（DDL 有意如此：楼层是展示辅助，不是标识符，
   * post.id 才是），所以最坏结果是两个树洞同号，不会报错也不会丢帖。
   * 真要严格连续得引序列或 SELECT FOR UPDATE 锁全表，代价与收益不匹配。
   * 与任务 3.12 配额「peek-then-incr 最多多放 1 帖」是同一类口径，一并写进答辩局限。</p>
   */
  @Select("SELECT COALESCE(MAX(floor_no), 0) + 1 FROM post WHERE type = 'hole'")
  Integer nextHoleFloor();

  /**
   * 浏览量批量回写（任务 3.5 · 手册 §6.1 3.5 行）。
   *
   * <p><b>为什么是 {@code view_cnt = view_cnt + n} 而不是 {@code set view_cnt = ?}</b>：
   * 前者是数据库内的原子累加，多个实例同时回写也不会丢计数；后者是「读—算—写」，
   * 两个实例各拿到旧值再回写，就会把对方那一笔覆盖掉。<br>
   * <b>WHERE 不带 status</b>：即使帖子在这个窗口里被下架，浏览量也是已经发生的事实，
   * 没理由丢掉；但 deleted=0 要带，否则注销清理后的残留增量会写进一个永远不再读的行。</p>
   */
  @Update("UPDATE post SET view_cnt = view_cnt + #{delta} WHERE id = #{id} AND deleted = 0")
  int increaseViewCnt(long id, long delta);
}

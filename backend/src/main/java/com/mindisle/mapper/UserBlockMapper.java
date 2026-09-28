package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.UserBlock;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 拉黑 Mapper（任务 T5.6 前置 · 需求 FR6.7、§7.2 #11）。
 *
 * <p>四条语句与 {@link UserFollowMapper} 逐条对得上，差别只有一个：
 * 这里的判据要<b>双向</b>查（{@link #isBlockedAny}），而关注是有向的。
 * 「双向」这两个字是整条私信链路上最容易被写错的一处——只查「他拉黑了我」会漏掉
 * 「我拉黑了他，但他还能给我发消息」，而后者才是用户按下拉黑按钮时真正想要的那个效果。</p>
 */
@Mapper
public interface UserBlockMapper extends BaseMapper<UserBlock> {

  /** 幂等拉黑：撞 uk_block_pair 返回 0（重复点按钮与网络重试都不是错误，口径同关注）。 */
  @Insert("INSERT IGNORE INTO user_block (user_id, block_user_id, reason) "
      + "VALUES (#{userId}, #{blockUserId}, #{reason})")
  int insertIgnore(@Param("userId") long userId, @Param("blockUserId") long blockUserId,
      @Param("reason") String reason);

  /** 解除拉黑：物理删，0 行表示本来就没拉黑（表上没有 deleted 列，理由见实体注释）。 */
  @Delete("DELETE FROM user_block WHERE user_id = #{userId} AND block_user_id = #{blockUserId}")
  int deletePair(@Param("userId") long userId, @Param("blockUserId") long blockUserId);

  /** 我拉黑了谁（管理端与设置页要能取消，所以这个方向必须列得出来）。走 uk_block_pair 前缀。 */
  @Select("SELECT * FROM user_block WHERE user_id = #{userId} ORDER BY id DESC LIMIT #{limit}")
  List<UserBlock> listMyBlocks(@Param("userId") long userId, @Param("limit") int limit);

  /**
   * 两个人之间<b>任一方向</b>存在拉黑吗。
   *
   * <p>两条单列查询而不是一条 {@code OR}：{@code uk_block_pair(user_id, block_user_id)}
   * 只把第一个当有效前缀，写成 {@code (user_id=a AND block=b) OR (user_id=b AND block=a)}
   * 会让优化器放弃索引。两条各走一次主键式定位，第二条在「对方没拉黑我」时是空结果集，
   * 成本与一条范围扫同级。</p>
   *
   * <p>本表没有 deleted 列（DDL 就没有），所以这里<b>不需要</b>也<b>不该有</b>
   * {@code deleted = 0}——写了就是一句永不成立的废话，读代码的人会以为存在软删口径。</p>
   */
  default boolean isBlockedAny(long a, long b) {
    return countPair(a, b) > 0 || countPair(b, a) > 0;
  }

  @Select("SELECT COUNT(*) FROM user_block WHERE user_id = #{userId} AND block_user_id = #{targetId}")
  int countPair(@Param("userId") long userId, @Param("targetId") long targetId);

  /** 我被多少人拉黑了（只给管理端与数据大屏，普通用户接口不返回这个数，见 BlockService 注释）。 */
  @Select("SELECT COUNT(*) FROM user_block WHERE block_user_id = #{userId}")
  long countBlockedMe(@Param("userId") long userId);
}

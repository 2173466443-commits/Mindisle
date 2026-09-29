package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.SensitiveWordGroup;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 敏感词分组 Mapper（任务 T6.2）。
 *
 * <p>七类是需求 §18.3 定死的，管理端 V1 不提供「新建组」：处置口径（尤其自伤类必须 TAG）
 * 一旦可以被自由创建，就存在「新建一个自伤类、动作选 BLOCK」这条路，
 * 而那正是需求专门论证过不能发生的事。这里只开放<b>改处置参数</b>与<b>重算词数</b>。</p>
 */
@Mapper
public interface SensitiveWordGroupMapper extends BaseMapper<SensitiveWordGroup> {

  @Select("SELECT * FROM sensitive_word_group WHERE deleted = 0 ORDER BY id")
  List<SensitiveWordGroup> listActive();

  /** 按类目名精确定位（{@code uk_name} 保证最多一条）。 */
  default SensitiveWordGroup findByName(String name) {
    return selectOne(new LambdaQueryWrapper<SensitiveWordGroup>()
        .eq(SensitiveWordGroup::getName, name)
        .orderByAsc(SensitiveWordGroup::getId)
        .last("limit 1"));
  }

  /**
   * 按真相表重算组内启用词数（口径同 {@code PostMapper#refreshCommentCnt}）：
   * 覆盖写而不是 +1，这样任何一条链路的漏记都会在下一个周期自愈。
   */
  @Update("UPDATE sensitive_word_group g SET g.word_cnt = "
      + "(SELECT COUNT(*) FROM sensitive_word w WHERE w.group_id = g.id AND w.deleted = 0 "
      + "  AND w.status = 1), g.updated_at = #{now} WHERE g.deleted = 0")
  int refreshAllWordCnt(@Param("now") LocalDateTime now);

  /** 改一组的处置口径（level/action/hit_scope 都有 ENUM 白名单，由服务层校验后才走到这里）。 */
  @Update("UPDATE sensitive_word_group SET level = #{level}, action = #{action}, "
      + "hit_scope = #{hitScope}, remark = #{remark}, updated_at = #{now} "
      + "WHERE id = #{id} AND deleted = 0")
  int updatePolicy(@Param("id") long id, @Param("level") String level,
      @Param("action") String action, @Param("hitScope") String hitScope,
      @Param("remark") String remark, @Param("now") LocalDateTime now);
}
package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.UserConsent;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/**
 * 同意留痕 Mapper（手册 §5.2 T2.5）。
 *
 * <p>本表是追加式的：只提供 insert 与查询，任何地方都不许出现 update/delete。
 */
@Mapper
public interface UserConsentMapper extends BaseMapper<UserConsent> {

  /** 取某用户的全部同意流水，按时间正序，供「我的隐私」页与论文举证导出。 */
  default List<UserConsent> listByUser(Long userId) {
    return selectList(new LambdaQueryWrapper<UserConsent>()
        .eq(UserConsent::getUserId, userId)
        .orderByAsc(UserConsent::getCreatedAt)
        .orderByAsc(UserConsent::getId));
  }

  /** 判断某类事项当前是否处于「已授予」态：取该类型最后一条流水，看是不是 GRANT。 */
  default boolean isGranted(Long userId, String consentType) {
    List<UserConsent> history = selectList(new LambdaQueryWrapper<UserConsent>()
        .eq(UserConsent::getUserId, userId)
        .eq(UserConsent::getConsentType, consentType)
        .orderByDesc(UserConsent::getId)
        .last("limit 1"));
    return !history.isEmpty() && "GRANT".equals(history.get(0).getAction());
  }
}

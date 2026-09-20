package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.SysConfig;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 系统参数 Mapper（手册 §5.2 T2.5 · FR8.6）。 */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {

  default SysConfig findByKey(String key) {
    return selectOne(new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getCfgKey, key).last("limit 1"));
  }

  default List<SysConfig> listByKeys(List<String> keys) {
    if (keys == null || keys.isEmpty()) {
      return List.of();
    }
    return selectList(new LambdaQueryWrapper<SysConfig>().in(SysConfig::getCfgKey, keys));
  }

  default List<SysConfig> listByGroup(String groupKey) {
    return selectList(new LambdaQueryWrapper<SysConfig>()
        .eq(SysConfig::getGroupKey, groupKey)
        .orderByAsc(SysConfig::getCfgKey));
  }
}

package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.AnonymousAlias;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 马甲 Mapper（手册 §6.1 行 3.4 · 需求 FR1.4）。 */
@Mapper
public interface AnonymousAliasMapper extends BaseMapper<AnonymousAlias> {

  /** 取某用户在某场景生效的马甲；uk_user_alias_scene 保证最多一条。 */
  default AnonymousAlias findByUserAndScene(long userId, String scene) {
    return selectOne(new LambdaQueryWrapper<AnonymousAlias>()
        .eq(AnonymousAlias::getUserId, userId)
        .eq(AnonymousAlias::getScene, scene)
        .last("limit 1"));
  }

  /** 取该用户最早的一匹马甲，用于「全站只用一个匿名身份」（BR1）。 */
  default AnonymousAlias findFirstByUser(long userId) {
    return selectOne(new LambdaQueryWrapper<AnonymousAlias>()
        .eq(AnonymousAlias::getUserId, userId)
        .orderByAsc(AnonymousAlias::getId)
        .last("limit 1"));
  }

  /** 该用户已有的马甲数，决定取名序号。 */
  default List<AnonymousAlias> listByUser(long userId) {
    return selectList(new LambdaQueryWrapper<AnonymousAlias>()
        .eq(AnonymousAlias::getUserId, userId)
        .orderByAsc(AnonymousAlias::getId));
  }
}

package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 账号主表 Mapper（手册 §5.2 T2.5）。
 *
 * <p>刻意不写 XML：单表条件查询用 LambdaQueryWrapper 足够，且能避免 mybatis-plus
 * mapper-locations 配错导致的「绑定不报错、调用才 500」这一类隐蔽故障。
 * 需要多表连接与聚合的复杂查询（推荐召回、审核工作台）在阶段 3 再引入 XML。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

  /**
   * 按登录名取账号。@TableLogic 会自动追加 deleted = 0，所以这里查不到即「不存在或已注销」。
   */
  default User findByUsername(String username) {
    return selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username).last("limit 1"));
  }
}

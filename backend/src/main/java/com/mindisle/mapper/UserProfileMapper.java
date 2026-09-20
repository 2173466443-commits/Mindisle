package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;

/** 用户资料 Mapper（手册 §5.2 T2.5）。主键即 user_id，用 selectById 即可。 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {}

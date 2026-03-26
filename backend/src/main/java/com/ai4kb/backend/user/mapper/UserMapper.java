package com.ai4kb.backend.user.mapper;

import com.ai4kb.backend.user.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/**
 * 用户数据访问接口。
 */
public interface UserMapper extends BaseMapper<User> {
}

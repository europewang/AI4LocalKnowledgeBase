package com.ai4kb.backend.user.mapper;

import com.ai4kb.backend.user.entity.Permission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/**
 * 权限数据访问接口。
 */
public interface PermissionMapper extends BaseMapper<Permission> {
}

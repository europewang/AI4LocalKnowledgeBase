package com.ai4kb.backend.engine.mapper;

import com.ai4kb.backend.engine.entity.Memory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/**
 * 记忆实体数据访问接口。
 */
public interface MemoryMapper extends BaseMapper<Memory> {
}

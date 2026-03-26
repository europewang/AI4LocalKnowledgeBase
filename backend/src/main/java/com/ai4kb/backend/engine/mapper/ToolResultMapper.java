package com.ai4kb.backend.engine.mapper;

import com.ai4kb.backend.engine.entity.ToolResult;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/**
 * 工具执行结果数据访问接口。
 */
public interface ToolResultMapper extends BaseMapper<ToolResult> {
}

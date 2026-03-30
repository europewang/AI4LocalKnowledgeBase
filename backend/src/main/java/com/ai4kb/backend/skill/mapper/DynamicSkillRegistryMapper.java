package com.ai4kb.backend.skill.mapper;

import com.ai4kb.backend.skill.entity.DynamicSkillRegistry;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/**
 * 动态技能注册表数据访问接口。
 */
public interface DynamicSkillRegistryMapper extends BaseMapper<DynamicSkillRegistry> {
}

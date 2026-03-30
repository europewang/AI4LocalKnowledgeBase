package com.ai4kb.backend.skill.mapper;

import com.ai4kb.backend.skill.entity.DynamicSkillCallAudit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/**
 * 动态技能调用审计表数据访问接口。
 */
public interface DynamicSkillCallAuditMapper extends BaseMapper<DynamicSkillCallAudit> {
}

CREATE TABLE IF NOT EXISTS t_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    conversation_id VARCHAR(64) NOT NULL COMMENT '会话 ID',
    user_id BIGINT NOT NULL COMMENT '所属用户',
    memory_type VARCHAR(20) NOT NULL COMMENT 'SUMMARY / DECISION / FACT / TOOL_RESULT',
    content TEXT NOT NULL COMMENT '记忆内容（短文本/摘要）',
    salience FLOAT DEFAULT 0.0 COMMENT '重要性评分（0-1）',
    pinned TINYINT(1) DEFAULT 0 COMMENT '是否固定（不被淘汰）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_conversation (conversation_id),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='长期记忆表';

CREATE TABLE IF NOT EXISTS t_tool_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    conversation_id VARCHAR(64) NOT NULL COMMENT '关联会话',
    tool_call_id VARCHAR(64) NOT NULL COMMENT '关联工具调用',
    tool_name VARCHAR(64) NOT NULL COMMENT '工具名称',
    args_digest JSON COMMENT '入参摘要（脱敏后）',
    result_digest JSON COMMENT '关键出参/结果摘要（可含资源引用ID）',
    status VARCHAR(20) NOT NULL COMMENT 'SUCCESS / FAIL',
    error TEXT COMMENT '失败原因（可空）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间',
    INDEX idx_conversation (conversation_id),
    INDEX idx_tool_call (tool_call_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具执行结果表';

CREATE TABLE IF NOT EXISTS t_route_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    conversation_id VARCHAR(64) NOT NULL COMMENT '会话 ID',
    user_id BIGINT NOT NULL COMMENT '用户 ID',
    source VARCHAR(64) NOT NULL COMMENT '决策来源',
    chosen_route VARCHAR(20) NOT NULL COMMENT '最终路由',
    chosen_confidence DOUBLE DEFAULT 0.0 COMMENT '最终置信度',
    chosen_tool VARCHAR(128) NULL COMMENT '最终工具名',
    local_route VARCHAR(20) NULL COMMENT '本地候选路由',
    local_confidence DOUBLE NULL COMMENT '本地候选置信度',
    planner_route VARCHAR(20) NULL COMMENT 'Planner 候选路由',
    planner_confidence DOUBLE NULL COMMENT 'Planner 候选置信度',
    query_text TEXT NULL COMMENT '用户查询',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '采样时间',
    INDEX idx_route_sample_conversation (conversation_id),
    INDEX idx_route_sample_user (user_id),
    INDEX idx_route_sample_source (source),
    INDEX idx_route_sample_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='路由样本沉淀表';

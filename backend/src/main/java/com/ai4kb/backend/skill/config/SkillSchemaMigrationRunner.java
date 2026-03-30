package com.ai4kb.backend.skill.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
/**
 * 技能模块表结构迁移执行器。
 * 在应用启动时确保动态技能注册表与调用审计表存在。
 */
public class SkillSchemaMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS t_skill_registry (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        tool_code VARCHAR(128) NOT NULL COMMENT '全局工具编码',
                        tool_name VARCHAR(200) NOT NULL COMMENT '工具展示名称',
                        description VARCHAR(1000) NULL COMMENT '工具说明',
                        protocol_type VARCHAR(32) NOT NULL DEFAULT 'HTTP' COMMENT '协议类型',
                        invoke_url VARCHAR(500) NOT NULL COMMENT '调用地址',
                        manifest_url VARCHAR(500) NULL COMMENT '协议描述地址',
                        health_url VARCHAR(500) NULL COMMENT '健康检查地址',
                        trigger_keywords VARCHAR(1000) NULL COMMENT '触发关键词，逗号分隔',
                        input_mode VARCHAR(64) NULL COMMENT '输入模式',
                        output_mode VARCHAR(64) NULL COMMENT '输出模式',
                        upload_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否必须上传文件',
                        accepted_file_types VARCHAR(500) NULL COMMENT '可接收后缀，逗号分隔',
                        max_files INT NULL COMMENT '最大文件数量',
                        parameters_schema LONGTEXT NULL COMMENT '参数 JSON Schema',
                        draft_args_template LONGTEXT NULL COMMENT '默认草稿参数 JSON',
                        status VARCHAR(16) NOT NULL DEFAULT 'ONLINE' COMMENT 'ONLINE/OFFLINE',
                        version INT NOT NULL DEFAULT 1 COMMENT '版本号',
                        created_by BIGINT NULL COMMENT '创建人',
                        updated_by BIGINT NULL COMMENT '更新人',
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                        last_online_at DATETIME NULL COMMENT '最近上线时间',
                        UNIQUE KEY uk_skill_registry_code (tool_code),
                        KEY idx_skill_registry_status (status),
                        KEY idx_skill_registry_updated (updated_at)
                    ) COMMENT '动态技能注册表'
                    """);
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS t_skill_call_audit (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        trace_id VARCHAR(80) NOT NULL COMMENT '追踪ID',
                        conversation_id VARCHAR(80) NULL COMMENT '会话ID',
                        tool_call_id VARCHAR(80) NULL COMMENT '工具调用ID',
                        tool_code VARCHAR(128) NOT NULL COMMENT '工具编码',
                        tool_name VARCHAR(200) NULL COMMENT '工具名称',
                        user_id BIGINT NULL COMMENT '调用用户ID',
                        username VARCHAR(100) NULL COMMENT '调用用户名',
                        user_role VARCHAR(40) NULL COMMENT '调用角色',
                        request_payload LONGTEXT NULL COMMENT '请求体',
                        response_payload LONGTEXT NULL COMMENT '响应体',
                        status VARCHAR(20) NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED',
                        error_message VARCHAR(1000) NULL COMMENT '错误信息',
                        latency_ms BIGINT NULL COMMENT '调用耗时毫秒',
                        started_at DATETIME NULL COMMENT '开始时间',
                        finished_at DATETIME NULL COMMENT '完成时间',
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        KEY idx_skill_audit_tool_code (tool_code),
                        KEY idx_skill_audit_user_id (user_id),
                        KEY idx_skill_audit_conv (conversation_id),
                        KEY idx_skill_audit_status (status),
                        KEY idx_skill_audit_created (created_at)
                    ) COMMENT '动态技能调用审计表'
                    """);
            log.info("skill_schema_migration: ensured t_skill_registry and t_skill_call_audit");
        } catch (Exception ex) {
            log.warn("skill_schema_migration: skipped, reason={}", ex.getMessage());
        }
    }
}

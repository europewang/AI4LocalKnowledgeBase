-- Create Database
CREATE DATABASE IF NOT EXISTS ai4kb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ai4kb;

-- Table: t_user
DROP TABLE IF EXISTS t_user;
CREATE TABLE t_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE COMMENT '用户名',
    password_hash VARCHAR(128) NULL COMMENT 'BCrypt密码哈希',
    role VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT '角色: super_admin/admin/user',
    manager_user_id BIGINT NULL COMMENT '直属管理员ID(普通用户使用)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) COMMENT '用户表';

-- Table: t_permission
DROP TABLE IF EXISTS t_permission;
CREATE TABLE t_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    resource_type VARCHAR(20) NOT NULL COMMENT '资源类型: DATASET/SKILL',
    resource_id VARCHAR(64) NOT NULL COMMENT '外部资源ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_resource (user_id, resource_type, resource_id)
) COMMENT '权限关联表';

-- Table: t_user_conversation
DROP TABLE IF EXISTS t_user_conversation;
CREATE TABLE t_user_conversation (
    id VARCHAR(64) PRIMARY KEY COMMENT '会话ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    title VARCHAR(200) NOT NULL COMMENT '会话标题',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_conversation_user (user_id),
    INDEX idx_user_conversation_updated (updated_at)
) COMMENT '用户会话主表';

-- Table: t_user_conversation_message
DROP TABLE IF EXISTS t_user_conversation_message;
CREATE TABLE t_user_conversation_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role VARCHAR(20) NOT NULL COMMENT '消息角色: user/assistant/system',
    content LONGTEXT NOT NULL COMMENT '消息内容',
    message_payload LONGTEXT NULL COMMENT '消息扩展载荷(JSON)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    INDEX idx_user_conversation_message_conv (conversation_id),
    INDEX idx_user_conversation_message_user (user_id),
    CONSTRAINT fk_user_conversation_message_conv FOREIGN KEY (conversation_id) REFERENCES t_user_conversation(id) ON DELETE CASCADE
) COMMENT '用户会话消息表';

-- Initial Data
INSERT INTO t_user (username, role) VALUES ('superadmin', 'super_admin');
INSERT INTO t_user (username, role) VALUES ('admin', 'admin');
INSERT INTO t_user (username, role) VALUES ('zhangsan', 'user');

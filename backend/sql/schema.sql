-- Create Database
CREATE DATABASE IF NOT EXISTS ai4kb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ai4kb;

-- Table: t_user
DROP TABLE IF EXISTS t_user;
CREATE TABLE t_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE COMMENT '用户名',
    role VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT '角色: admin/user',
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

-- Initial Data
INSERT INTO t_user (username, role) VALUES ('admin', 'admin');
INSERT INTO t_user (username, role) VALUES ('zhangsan', 'user');

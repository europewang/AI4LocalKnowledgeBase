package com.ai4kb.backend.user.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserSchemaMigrationRunner implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_user' AND column_name = 'password_hash'",
                    Integer.class
            );
            if (count == null || count == 0) {
                jdbcTemplate.execute("ALTER TABLE t_user ADD COLUMN password_hash VARCHAR(128) NULL");
            }
            ensureDefaultUser("superadmin", "super_admin");
            ensureDefaultUser("admin", "admin");
            ensureDefaultUser("zhangsan", "user");
            ensureConversationTables();
            jdbcTemplate.execute("UPDATE t_user SET password_hash = password WHERE (password_hash IS NULL OR password_hash = '') AND password LIKE '$2%'");
            log.info("auth_schema_migration: ensured t_user.password_hash compatibility");
        } catch (Exception ex) {
            log.warn("auth_schema_migration: skip compatibility migration, reason={}", ex.getMessage());
        }
    }

    private void ensureDefaultUser(String username, String role) {
        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE username = ?",
                Integer.class,
                username
        );
        if (userCount == null || userCount == 0) {
            jdbcTemplate.update("INSERT INTO t_user (username, role) VALUES (?, ?)", username, role);
        }
    }

    private void ensureConversationTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_user_conversation (
                    id VARCHAR(64) PRIMARY KEY COMMENT '会话ID',
                    user_id BIGINT NOT NULL COMMENT '用户ID',
                    title VARCHAR(200) NOT NULL COMMENT '会话标题',
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    INDEX idx_user_conversation_user (user_id),
                    INDEX idx_user_conversation_updated (updated_at)
                ) COMMENT '用户会话主表'
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_user_conversation_message (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    conversation_id VARCHAR(64) NOT NULL COMMENT '会话ID',
                    user_id BIGINT NOT NULL COMMENT '用户ID',
                    role VARCHAR(20) NOT NULL COMMENT '消息角色: user/assistant/system',
                    content LONGTEXT NOT NULL COMMENT '消息内容',
                    message_payload LONGTEXT NULL COMMENT '消息扩展载荷(JSON)',
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
                    INDEX idx_user_conversation_message_conv (conversation_id),
                    INDEX idx_user_conversation_message_user (user_id)
                ) COMMENT '用户会话消息表'
                """);
        Integer payloadColumnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_user_conversation_message' AND column_name = 'message_payload'",
                Integer.class
        );
        if (payloadColumnCount == null || payloadColumnCount == 0) {
            jdbcTemplate.execute("ALTER TABLE t_user_conversation_message ADD COLUMN message_payload LONGTEXT NULL COMMENT '消息扩展载荷(JSON)'");
        }
    }
}

package com.linblog.integration;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V1/V2 迁移冒烟（无 Docker 自动跳过）：迁移可完整应用、22 张表齐全、
 * 唯一超管函数索引与 ngram 全文索引存在、V2 内容域二级索引齐全。
 */
@Testcontainers(disabledWithoutDocker = true)
class MigrationSmokeTest {

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "users", "refresh_tokens", "password_reset_tokens", "role_permissions",
            "content", "media", "labels", "content_labels", "subsets", "subset_items",
            "diaries", "interactions", "comments", "comment_praises", "comment_reports",
            "conversations", "direct_messages", "guestbook",
            "view_logs", "request_audits", "system_logs", "files");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test
    void migrationsApplyAndCriticalIndexesExist() throws Exception {
        String url = MYSQL.getJdbcUrl() + "&allowPublicKeyRetrieval=true";
        try (Connection connection = DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword())) {
            // V1 + V2 全部 pending 迁移一次性应用（Flyway 从 classpath:db/migration 自动发现）
            org.flywaydb.core.Flyway.configure()
                    .dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                    .load()
                    .migrate();

            // 1) 22 张业务表齐全
            Set<String> tables = new HashSet<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1).toLowerCase());
                }
            }
            Set<String> missing = new HashSet<>(EXPECTED_TABLES);
            missing.removeAll(tables);
            assertEquals(Set.of(), missing, "缺少数据表: " + missing);

            // 2) users 唯一超管函数索引
            assertTrue(indexExists(connection, "users", "uk_single_super_admin"),
                    "users 缺少唯一超管索引 uk_single_super_admin");

            // 3) content ngram 全文索引
            assertTrue(fulltextIndexExists(connection, "content", "ft_search"),
                    "content 缺少 FULLTEXT 索引 ft_search");

            // 4) 内容域查询索引（V1/V2）
            assertTrue(indexExists(connection, "media", "uk_storage_key"));
            assertTrue(indexExists(connection, "diaries", "uk_author_date"));
            assertTrue(indexExists(connection, "refresh_tokens", "uk_token_hash"));
            assertTrue(indexExists(connection, "view_logs", "idx_target_time"));
        }
    }

    @Test
    void singleSuperAdminConstraintEnforced() throws Exception {
        String url = MYSQL.getJdbcUrl() + "&allowPublicKeyRetrieval=true";
        try (Connection connection = DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword())) {
            org.flywaydb.core.Flyway.configure()
                    .dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                    .load()
                    .migrate();

            try (var stmt = connection.createStatement()) {
                stmt.execute("""
                        INSERT INTO users (username, email, password_hash, nickname, role_code, status, token_version)
                        VALUES ('root1', 'root1@linlog.com', 'x', 'r', 'super_admin', 1, 0)
                        """);
            }
            // 第二个 super_admin（不同用户名/邮箱）必须被函数唯一索引拒绝
            var second = connection.createStatement();
            try {
                second.execute("""
                        INSERT INTO users (username, email, password_hash, nickname, role_code, status, token_version)
                        VALUES ('root2', 'root2@linlog.com', 'x', 'r', 'super_admin', 1, 0)
                        """);
                org.junit.jupiter.api.Assertions.fail("第二个 super_admin 应被唯一索引拒绝");
            } catch (java.sql.SQLException expected) {
                // 唯一索引冲突，符合预期
            }
        }
    }

    /** V2__content_domain.sql 的 W3/W4/W5 补索引。 */
    @Test
    void v2ContentDomainIndexesExist() throws Exception {
        String url = MYSQL.getJdbcUrl() + "&allowPublicKeyRetrieval=true";
        try (Connection connection = DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword())) {
            org.flywaydb.core.Flyway.configure()
                    .dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                    .load()
                    .migrate();

            assertTrue(indexExists(connection, "subsets", "idx_owner"));
            assertTrue(indexExists(connection, "subsets", "idx_cover"));
            assertTrue(indexExists(connection, "subset_items", "idx_content"));
            assertTrue(indexExists(connection, "conversations", "idx_user_b"));
            assertTrue(indexExists(connection, "guestbook", "idx_status"));
            assertTrue(indexExists(connection, "diaries", "idx_state_date"));
        }
    }

    private static boolean indexExists(Connection connection, String table, String indexName) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static boolean fulltextIndexExists(Connection connection, String table, String indexName) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ? AND index_type = 'FULLTEXT'")) {
            ps.setString(1, table);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }
}

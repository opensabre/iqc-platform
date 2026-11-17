package io.github.opensabre.iqc.agent;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit local-only migration acceptance; guards the dedicated datadir and loopback port before DDL. */
class AgentChatSessionMysqlIT {
    private static final String SERVER = "jdbc:mysql://127.0.0.1:34308/";
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";

    @Test void freshAndUpgradePathsAreRepeatableAndPreserveHistory() throws Exception {
        String directory = System.getenv("IQC_MYSQL_VALIDATION_DIR");
        assertThat(directory).startsWith("/private/tmp/iqc-mysql-validation.");
        try (var connection = DriverManager.getConnection(SERVER + OPTIONS, "root", "");
             var query = connection.createStatement(); var server = query.executeQuery("SELECT @@port,@@datadir")) {
            assertThat(server.next()).isTrue(); assertThat(server.getInt(1)).isEqualTo(34308);
            assertThat(server.getString(2)).isEqualTo(directory + "/");
        }
        for (String schema : new String[]{"iqc_chat_fresh", "iqc_chat_upgrade"}) {
            try (var connection = DriverManager.getConnection(SERVER + OPTIONS, "root", ""); var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + schema);
                statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            String url = SERVER + schema + OPTIONS;
            if (schema.endsWith("upgrade")) {
                var previous = Flyway.configure().dataSource(url, "root", "").locations("classpath:db/migration/mysql")
                        .target("1.1.40").load();
                assertThat(previous.migrate().migrationsExecuted).isPositive();
            }
            var flyway = Flyway.configure().dataSource(url, "root", "").locations("classpath:db/migration/mysql").load();
            assertThat(flyway.migrate().migrationsExecuted).isPositive();
            flyway.validate(); assertThat(flyway.migrate().migrationsExecuted).isZero();
            String payload = "[{\"role\":\"user\",\"content\":\"" + "会话😀".repeat(10000) + "\"}]";
            try (var connection = DriverManager.getConnection(url, "root", "")) {
                try (var insert = connection.prepareStatement("""
                        INSERT INTO iqc_agent_chat_session
                        (id,agent_id,version_no,title,source,owner,config_hash,message_count,messages_json,last_message_time,created_time,updated_time)
                        VALUES ('session-a','agent-a',2,'中文😀','TEST','alice',REPEAT('a',64),2,?,NOW(3),NOW(3),NOW(3))
                        """)) { insert.setString(1, payload); assertThat(insert.executeUpdate()).isEqualTo(1); }
                try (var statement = connection.createStatement()) {
                    // The optimistic predicate permits exactly one append for the same previous count.
                    assertThat(statement.executeUpdate("UPDATE iqc_agent_chat_session SET message_count=4 WHERE id='session-a' AND message_count=2")).isEqualTo(1);
                    assertThat(statement.executeUpdate("UPDATE iqc_agent_chat_session SET message_count=4 WHERE id='session-a' AND message_count=2")).isZero();
                    try (var rows = statement.executeQuery("SELECT messages_json,title FROM iqc_agent_chat_session WHERE id='session-a'")) {
                        assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo(payload);
                        assertThat(rows.getString(2)).isEqualTo("中文😀");
                    }
                }
            }
            // Exercise the actual mapper against the migrated schema, including BasePo date fields.
            var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("chat-it",
                    new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                    new org.apache.ibatis.datasource.unpooled.UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, "root", "")));
            configuration.addMapper(io.github.opensabre.iqc.agent.dao.AgentChatSessionMapper.class);
            try (var session = new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                var mapper = session.getMapper(io.github.opensabre.iqc.agent.dao.AgentChatSessionMapper.class);
                var record = new io.github.opensabre.iqc.agent.model.AgentChatSession();
                record.setId("mapper-session"); record.setAgentId("agent-mapper"); record.setVersionNo(1);
                record.setTitle("Mapper round trip"); record.setSource("TEST"); record.setOwner("alice");
                record.setConfigHash("b".repeat(64)); record.setMessageCount(2); record.setMessagesJson(payload);
                record.setCreatedTime(new java.util.Date()); record.setUpdatedTime(new java.util.Date());
                record.setLastMessageTime(java.time.LocalDateTime.now());
                assertThat(mapper.insert(record)).isEqualTo(1);
                var saved = mapper.selectById("mapper-session");
                assertThat(saved.getMessagesJson()).isEqualTo(payload);
                assertThat(saved.getCreatedTime()).isNotNull(); assertThat(saved.getLastMessageTime()).isNotNull();
                assertThat(mapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<io.github.opensabre.iqc.agent.model.AgentChatSession>lambdaUpdate()
                        .eq(io.github.opensabre.iqc.agent.model.AgentChatSession::getId, "mapper-session")
                        .eq(io.github.opensabre.iqc.agent.model.AgentChatSession::getMessageCount, 2)
                        .set(io.github.opensabre.iqc.agent.model.AgentChatSession::getMessageCount, 4))).isEqualTo(1);
            }
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (var connection = DriverManager.getConnection(url, "root", ""); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version='1.1.41' AND success=1")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
    }
}

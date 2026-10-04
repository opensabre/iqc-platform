package io.github.opensabre.iqc.result;

import org.flywaydb.core.Flyway;
import org.springframework.boot.test.context.TestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the existing joint service scenarios against formal migrations on a verified temporary MySQL server. */
@Import(IsolatedMysqlJointTransactionIT.StableIds.class)
class IsolatedMysqlJointTransactionIT extends JointLabelMaterializationTransactionTest {
    private static final String SERVER = "jdbc:mysql://127.0.0.1:34307/";
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    @Autowired private JdbcTemplate mysql;

    /** Keeps this isolated integration suite independent from wall-clock corrections during ID generation. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StableIds {
        private static final AtomicLong IDS = new AtomicLong(6_000_000_000_000_000_000L);

        @Bean
        @Primary
        com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator testIdentifierGenerator() {
            return new com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator() {
                @Override
                public Number nextId(Object entity) {
                    return IDS.incrementAndGet();
                }

                @Override
                public String nextUUID(Object entity) {
                    return String.format("%032x", IDS.incrementAndGet());
                }
            };
        }
    }

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) throws Exception {
        String directory = System.getenv("IQC_MYSQL_VALIDATION_DIR");
        assertThat(directory).startsWith("/private/tmp/iqc-mysql-validation.");
        try (var connection = DriverManager.getConnection(SERVER + OPTIONS, "root", "");
             var statement = connection.createStatement()) {
            try (var identity = statement.executeQuery("SELECT @@port, @@datadir")) {
                assertThat(identity.next()).isTrue();
                assertThat(identity.getInt(1)).isEqualTo(34307);
                assertThat(identity.getString(2)).isEqualTo(directory + "/");
            }
            statement.execute("CREATE DATABASE IF NOT EXISTS iqc_joint_service CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        String url = SERVER + "iqc_joint_service" + OPTIONS;
        Flyway.configure().dataSource(url, "root", "").locations("classpath:db/migration/mysql")
                .baselineOnMigrate(false).cleanDisabled(true).load().migrate();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
    }

    @Override
    @BeforeEach
    void createIsolatedResultTables() {
        for (String[] constraint : new String[][]{{"iqc_inspection_result", "reject_second_message"},
                {"iqc_inspection_label_result", "reject_test_value"}}) {
            Integer count = mysql.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints "
                    + "WHERE constraint_schema=DATABASE() AND table_name=? AND constraint_name=?",
                    Integer.class, constraint[0], constraint[1]);
            if (count != null && count > 0) mysql.execute("ALTER TABLE " + constraint[0] + " DROP CHECK " + constraint[1]);
        }
        // The server was verified before schema creation; clear only fixture-owned rows in this dedicated schema.
        for (String table : new String[]{"iqc_label_rule_binding", "iqc_label_value_definition", "iqc_label", "iqc_label_group", "iqc_label_category",
                "iqc_result_review", "iqc_task_item", "iqc_task_execution", "iqc_inspection_task",
                "iqc_conversation", "iqc_inspection_scheme_version", "iqc_inspection_scheme", "iqc_quality_rule_version",
                "iqc_quality_rule", "iqc_inspection_result", "iqc_conversation_message", "iqc_inspection_label_result",
                "iqc_inspection_evidence", "iqc_inspection_rule_result", "iqc_inspection_conversation_result"})
            mysql.execute("DELETE FROM " + table);
    }

    @Override
    protected boolean usesMigratedSchema() { return true; }
}

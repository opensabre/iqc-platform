package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** Real migrated SQL capacity at existing task boundaries; no fabricated publication or shared database. */
class IsolatedMysqlTaskSnapshotCapacityIT {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void freshSchemaStoresEightMiBSelectorEvidenceWithoutTruncation() throws Exception {
        var db = migrated("iqc_task_evidence_capacity_", null);
        // InspectionTaskService accepts UTF-8 selector evidence through 8 MiB, including JSON overhead.
        String prefix = "{\"evidence\":\"";
        String body = prefix + "x".repeat(8 * 1024 * 1024 - prefix.length() - 2) + "\"}";
        assertEquals(8 * 1024 * 1024, body.getBytes(StandardCharsets.UTF_8).length);
        assertNotNull(JSON.readTree(body));
        roundTrip(db, "selection_filter_json", body);
    }

    @Test void freshSchemaStoresOneThousandLongestConversationIdsInOrder() throws Exception {
        var db = migrated("iqc_task_ids_capacity_", null);
        // The existing id column allows 64 characters and batch creation allows 1,000 distinct ids.
        var ids = IntStream.range(0, 1000).mapToObj(n -> "c" + "x".repeat(59) + String.format("%04d", n)).toList();
        assertEquals(1000, ids.stream().distinct().count());
        assertTrue(ids.stream().allMatch(id -> id.length() == 64));
        String body = JSON.writeValueAsString(ids);
        assertTrue(body.getBytes(StandardCharsets.UTF_8).length > 65_535);
        roundTrip(db, "conversation_ids_json", body);
        assertEquals(ids, JSON.readValue(db.queryForObject(
                "SELECT conversation_ids_json FROM iqc_inspection_task WHERE id='capacity'", String.class),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {}));
    }

    @Test void upgradeKeepsHistoricalNullsAndJsonAndAllowsLargerEvidence() throws Exception {
        String url = IsolatedMysqlTaskMetricsIT.createSchema("iqc_task_capacity_upgrade_");
        IsolatedMysqlTaskMetricsIT.flyway(url, "1.1.77").migrate();
        var db = new JdbcTemplate(new DriverManagerDataSource(url, "root", ""));
        db.update("INSERT INTO iqc_inspection_task(id,name,status,conversation_ids_json,selection_filter_json) "
                + "VALUES ('legacy-json','history','CREATED','[\"原会话\"]','{\"legacy\":true}')");
        db.update("INSERT INTO iqc_inspection_task(id,name,status) VALUES ('legacy-null','history','CREATED')");
        var latest = IsolatedMysqlTaskMetricsIT.flyway(url, null);
        latest.migrate(); latest.validate();
        assertEquals(0, latest.migrate().migrationsExecuted);
        assertEquals("[\"原会话\"]", db.queryForObject("SELECT conversation_ids_json FROM iqc_inspection_task WHERE id='legacy-json'", String.class));
        assertEquals("{\"legacy\":true}", db.queryForObject("SELECT selection_filter_json FROM iqc_inspection_task WHERE id='legacy-json'", String.class));
        assertTrue(Boolean.TRUE.equals(db.queryForObject("SELECT conversation_ids_json IS NULL AND selection_filter_json IS NULL FROM iqc_inspection_task WHERE id='legacy-null'", Boolean.class)));
        // Multibyte content must survive in bytes, rather than merely fitting a character count.
        roundTrip(db, "selection_filter_json", JSON.writeValueAsString(java.util.Map.of("evidence", "处理好的会话".repeat(12_000))));
    }

    private static JdbcTemplate migrated(String prefix, String target) throws Exception {
        String url = IsolatedMysqlTaskMetricsIT.createSchema(prefix);
        var flyway = IsolatedMysqlTaskMetricsIT.flyway(url, target);
        flyway.migrate(); flyway.validate(); assertEquals(0, flyway.migrate().migrationsExecuted);
        return new JdbcTemplate(new DriverManagerDataSource(url, "root", ""));
    }

    private static void roundTrip(JdbcTemplate db, String column, String body) throws Exception {
        assertTrue(java.util.Set.of("selection_filter_json", "conversation_ids_json").contains(column));
        db.update("INSERT INTO iqc_inspection_task(id,name,status," + column + ") VALUES ('capacity','capacity','CREATED',?)", body);
        assertEquals(body, db.queryForObject("SELECT " + column + " FROM iqc_inspection_task WHERE id='capacity'", String.class));
        assertEquals(body.getBytes(StandardCharsets.UTF_8).length, db.queryForObject(
                "SELECT OCTET_LENGTH(" + column + ") FROM iqc_inspection_task WHERE id='capacity'", Integer.class));
        assertTrue(Boolean.TRUE.equals(db.queryForObject("SELECT JSON_VALID(" + column + ") FROM iqc_inspection_task WHERE id='capacity'", Boolean.class)));
    }
}

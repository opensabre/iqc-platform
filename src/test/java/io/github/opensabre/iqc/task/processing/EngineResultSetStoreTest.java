package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;
import static org.assertj.core.api.Assertions.*;

class EngineResultSetStoreTest {
    @Test void migrationAndStoreRoundTripPreserveEvidenceAndRejectHistoryChanges() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/mysql/ddl/V1.1.33__ddl_add_engine_execution_result_set.sql")));
        var mapper = new ObjectMapper();
        var store = new EngineResultSetStore(jdbc, mapper);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var now = Instant.parse("2026-11-05T00:00:00Z");
        var input = new InputSnapshot("s1", now, List.of(new Message("m1", 1, "customer", "00:01", "退款")));
        var empty = new EngineExecutionResultSet(SCHEMA_VERSION, "rs1", "t1", "ex1", "c1", input, mapper.createObjectNode(), List.of());
        tx.executeWithoutResult(status -> store.save(empty));
        var engine = new EngineResult("r1", Operation.INSPECTION, "regex1", EngineType.REGEX, 1, now, now,
                ExecutionStatus.SUCCEEDED, mapper.createObjectNode(), "原始响应", List.of(), List.of(new Evidence("e1", "m1", 0, 2, "退款")), null);
        var appended = new EngineExecutionResultSet(SCHEMA_VERSION, "rs1", "t1", "ex1", "c1", input, mapper.createObjectNode(), List.of(engine));
        tx.executeWithoutResult(status -> store.save(appended));
        tx.executeWithoutResult(status -> store.save(appended));
        assertThat(store.find("ex1", "c1")).contains(appended);
        assertThat(jdbc.queryForObject("SELECT revision FROM iqc_engine_execution_result_set WHERE id='rs1'", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.save(empty))).hasMessageContaining("不能修改");
        var otherId = new EngineExecutionResultSet(SCHEMA_VERSION, "rs2", "t1", "ex1", "c1", input, mapper.createObjectNode(), List.of());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.save(otherId)))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(store.find("ex1", "c1")).contains(appended);
        assertThat(store.find("ex2", "c1")).isEmpty();
    }
}

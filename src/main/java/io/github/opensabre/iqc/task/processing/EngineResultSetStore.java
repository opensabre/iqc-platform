package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** IQC-owned checkpoints retain the frozen input and append engine evidence without rewriting history. */
@Service
public class EngineResultSetStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public EngineResultSetStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        // Checkpoints contain Instant fields; keep their codec independent of the application's legacy JSON mapper.
        this.mapper = mapper.copy().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    /** Internal execution lookup; callers must authorize the owning task before exposing this payload. */
    public Optional<EngineExecutionResultSet> find(String executionId, String conversationId) {
        return jdbc.query("SELECT payload FROM iqc_engine_execution_result_set WHERE execution_id = ? AND conversation_id = ?",
                (rs, row) -> decode(rs.getString(1)), executionId, conversationId).stream().findFirst();
    }

    /** A separate short transaction commits evidence before the next external engine call. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(EngineExecutionResultSet next) {
        var rows = jdbc.query("SELECT payload, revision FROM iqc_engine_execution_result_set WHERE id = ?",
                (rs, row) -> new Stored(decode(rs.getString(1)), rs.getInt(2)), next.resultSetId());
        String payload = encode(next);
        var now = Timestamp.from(Instant.now());
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO iqc_engine_execution_result_set (id, task_id, execution_id, conversation_id, payload, revision, created_time, updated_time) VALUES (?, ?, ?, ?, ?, 0, ?, ?)",
                    next.resultSetId(), next.taskId(), next.executionId(), next.conversationId(), payload, now, now);
            return;
        }
        var stored = rows.getFirst();
        var old = stored.resultSet();
        if (!old.taskId().equals(next.taskId()) || !old.executionId().equals(next.executionId())
                || !old.conversationId().equals(next.conversationId()) || !old.input().equals(next.input())
                || !old.taskConfiguration().equals(next.taskConfiguration())
                || next.engines().size() < old.engines().size()
                || !next.engines().subList(0, old.engines().size()).equals(old.engines()))
            throw new IllegalArgumentException("不能修改已固定输入、配置或已记录的引擎结果");
        if (old.equals(next)) return;
        int updated = jdbc.update("UPDATE iqc_engine_execution_result_set SET payload = ?, revision = revision + 1, updated_time = ? WHERE id = ? AND revision = ?",
                payload, now, next.resultSetId(), stored.revision());
        if (updated != 1) throw new IllegalStateException("结果集被其他执行者更新，请重新加载后恢复");
    }

    private String encode(EngineExecutionResultSet value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("引擎结果集无法序列化", failure); }
    }
    private EngineExecutionResultSet decode(String value) {
        try { return mapper.readValue(value, EngineExecutionResultSet.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("已保存的引擎结果集无法读取", failure); }
    }
    private record Stored(EngineExecutionResultSet resultSet, int revision) {}
}

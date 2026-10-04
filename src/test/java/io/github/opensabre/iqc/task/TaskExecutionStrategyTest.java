package io.github.opensabre.iqc.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskExecutionStrategyTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void oldSnapshotsDoNotAcquireNewPolicy() throws Exception {
        assertThat(TaskExecutionStrategy.fromSnapshot(mapper.readTree("[]"))).isNull();
        assertThat(TaskExecutionStrategy.parseRequested(null)).isNull();
    }

    @Test
    void explicitStrategyIsReadFromTaskSnapshot() throws Exception {
        var snapshot = mapper.readTree("{\"rules\":[],\"executionStrategy\":{\"schemaVersion\":\"1.0\",\"mode\":\"INDEPENDENT\"}}");
        assertThat(TaskExecutionStrategy.fromSnapshot(snapshot)).isEqualTo(TaskExecutionStrategy.Mode.INDEPENDENT);
    }

    @Test
    void malformedExplicitStrategyCannotSilentlyUseAgentDefaults() {
        assertThatThrownBy(() -> TaskExecutionStrategy.fromSnapshot(mapper.readTree("{\"executionStrategy\":null}"))).hasMessageContaining("版本");
        assertThatThrownBy(() -> TaskExecutionStrategy.fromSnapshot(mapper.readTree("{\"executionStrategy\":{\"schemaVersion\":\"1.0\"}}"))).hasMessageContaining("不支持");
        assertThatThrownBy(() -> TaskExecutionStrategy.parseRequested("fastest")).hasMessageContaining("不支持");
    }
}

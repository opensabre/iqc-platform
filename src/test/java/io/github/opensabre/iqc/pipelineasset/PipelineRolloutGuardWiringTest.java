package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Checks fail-closed rollout validation through both supported legacy constructors. */
class PipelineRolloutGuardWiringTest {
    private final ObjectMapper json = new ObjectMapper();
    private PipelineAssetService legacy(boolean labels) {
        if (labels) return new PipelineAssetService(null,null,null,null,null,null,null,null,json,null,null,null,null,null,null);
        return new PipelineAssetService(null,null,null,null,null,null,null,null,json,null,null,null,null,null);
    }
    @Test void reservedTaskIdRequiresGuardEvenWhenSnapshotEvidenceIsMissing() {
        for (boolean labels : new boolean[]{false,true}) {
            var service=legacy(labels);
            assertThatThrownBy(() -> service.validateTask(json.createObjectNode(),"g"+"a".repeat(63)))
                    .hasMessageContaining("灰度任务原始证据校验组件不可用");
        }
    }
    @Test void explicitEvidenceIncludingNullRequiresGuardWithoutTaskId() {
        for (boolean labels : new boolean[]{false,true}) {
            var service=legacy(labels);
            var snapshot=json.createObjectNode();snapshot.putNull("rolloutSnapshot");
            assertThatThrownBy(() -> service.validateTask(snapshot))
                    .hasMessageContaining("灰度任务原始证据校验组件不可用");
            snapshot.set("rolloutSnapshot",json.createObjectNode());
            assertThatThrownBy(() -> service.validateTask(snapshot,"legacy-task"))
                    .hasMessageContaining("灰度任务原始证据校验组件不可用");
        }
    }
    @Test void oldNonPipelineValidationRemainsCompatibleWithoutGuard() {
        for (boolean labels : new boolean[]{false,true}) {
            var service=legacy(labels);
            assertThatCode(() -> service.validateTask(json.createObjectNode(),"legacy-task")).doesNotThrowAnyException();
            assertThatCode(() -> service.validateTask(null)).doesNotThrowAnyException();
        }
    }
}

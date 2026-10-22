package io.github.opensabre.iqc.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.schedule.dao.*;
import io.github.opensabre.iqc.schedule.model.*;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the public background read boundary; revision storage is mocked, not SQL. */
class ExactSubscriptionSnapshotTest {
    @Test void publicPeekPreservesFrozenDecimalsAndRejectsChangedRevision() {
        var schedules=mock(EventSubscriptionMapper.class);
        var revisions=mock(EventSubscriptionRevisionMapper.class);
        var service=new EventSubscriptionService(schedules,revisions,null,null,null,null,null,null,null,new ObjectMapper().findAndRegisterModules());
        var row=new EventSubscription();row.setId("subscription");row.setStatus("ACTIVE");
        row.setActiveRevisionId("revision");row.setCreatedBy("alice");
        var revision=new EventSubscriptionRevision();revision.setId("revision");revision.setSubscriptionId("subscription");revision.setCreatedBy("alice");
        revision.setSnapshotJson("""
            {"schemaVersion":"iqc-event-subscription-release-v1","name":"Processed conversations",
             "definition":{"schemaVersion":"iqc-event-subscription-v1","eventType":"ConversationCompleted",
               "effectiveFrom":"2026-01-01T00:00:00Z","waitSeconds":60,"concurrency":1,
               "pipeline":{"pipelineId":"pipeline","versionNo":1}},
             "ownerBound":{"schemaVersion":"iqc-schedule-owner-scope-v1","userId":"user","owner":"alice","all":false},
             "pipeline":{"pipelineId":"pipeline","versionNo":1,"draftRevision":1,"contentHash":"fixture",
               "release":{"schemaVersion":"iqc-pipeline-release-v2",
                 "pipeline":{"schemaVersion":"iqc-pipeline-v1","nodes":[{
                   "nodeId":"rule","type":"RULE","name":"Rule","version":"1","dependsOn":[],"inputMapping":{},
                   "config":{"value":0.900000000000000001},"timeoutMs":1000,"retries":0,"onError":"FAIL_PIPELINE","enabled":true}]},
                 "fusion":{"id":"fusion","version":1,"method":"PRIORITY","nodePriority":{},"overrideNodeByCode":{},"calibratedNodeIds":[]},
                 "scoring":{"version":"iqc-score-v2","mode":"DEDUCTION","baseScore":100,"passingScore":60,"items":[]},"items":[]}}}
            """);
        revision.setContentHash(SelectorAssetService.hash(revision.getSnapshotJson()));
        when(schedules.selectById("subscription")).thenReturn(row);
        when(revisions.selectById("revision")).thenReturn(revision);
        var frame=service.peek("subscription",Instant.now());
        assertThat(frame.snapshot().pipeline().release().pipeline().nodes().getFirst().config().path("value").decimalValue())
                .isEqualByComparingTo("0.900000000000000001");
        assertThat(frame.revisionHash()).isEqualTo(revision.getContentHash());
        revision.setSnapshotJson(revision.getSnapshotJson().replace("0.900000000000000001","0.900000000000000002"));
        assertThatThrownBy(()->service.peek("subscription",Instant.now())).hasMessageContaining("摘要无效");
    }
}

package io.github.opensabre.iqc.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.schedule.model.EventSubscriptionRevision;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Pure immutable-parent boundary; does not replace Accepted validation, SQL scanning or the worker guard. */
class ArchiveReceiptParentEvidenceTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private record Fixture(EventSubscriptionRevision version,TaskTriggerService.Accepted accepted) { }
    private Fixture fixture() throws Exception {
        var start=Instant.parse("2026-10-23T00:00:00Z");
        var definition=new EventSubscriptionDefinition(EventSubscriptionDefinition.ROLLOUT_SCHEMA,"ConversationCompleted",
                start,start.plusSeconds(3600),60,2,null,null,null,new PeriodicDefinition.RolloutReference("pipe"));
        var owner=new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1","user","alice",false,"group");
        var release=new EventSubscriptionService.Published("iqc-event-subscription-rollout-release-v1","Processed conversations",definition,null,null,owner);
        var version=new EventSubscriptionRevision();version.setId("revision");version.setSubscriptionId("subscription");
        version.setVersionNo(1);version.setSourceDraftRevision(1);version.setCreatedBy("alice");
        version.setSnapshotJson(json.writeValueAsString(release));version.setContentHash(SelectorAssetService.hash(version.getSnapshotJson()));
        var request=new TaskTriggerRequest(TaskTriggerRequest.ROLLOUT_SCHEMA,"archive-subscription",
                EventSubscriptionService.eventId("revision","conversation","a".repeat(64)),"ConversationCompleted",
                start.plusSeconds(10),start.plusSeconds(120),release.name(),null,null,
                new TaskTriggerRequest.Target("conversation",null,null,"a".repeat(64)),null,2,false,null,definition.rollout());
        // This boundary checks the parent relation; pipeline allocation and Accepted envelope are tested separately.
        return new Fixture(version,new TaskTriggerService.Accepted("iqc-trigger-accepted-v1",request,"unused",null,null,owner,"subscription","revision"));
    }
    private void rejects(Fixture fixture) {
        assertThatThrownBy(()->EventSubscriptionService.validateReceiptParent(fixture.accepted(),fixture.version(),json))
                .hasMessageContaining("原订阅发布来源不一致");
    }
    private Fixture changedRequest(Consumer<ObjectNode> change) throws Exception {
        var fixture=fixture();var body=(ObjectNode)json.valueToTree(fixture.accepted().request());change.accept(body);
        var request=json.treeToValue(body,TaskTriggerRequest.class);var original=fixture.accepted();
        return new Fixture(fixture.version(),new TaskTriggerService.Accepted(original.schemaVersion(),request,original.payloadHash(),
                original.pipeline(),original.selector(),original.ownerBound(),original.subscriptionId(),original.subscriptionRevisionId()));
    }
    @Test void validatesOriginalImmutableParentWithoutReadingCurrentSubscription() throws Exception {
        var fixture=fixture();String before=json.writeValueAsString(fixture);
        EventSubscriptionService.validateReceiptParent(fixture.accepted(),fixture.version(),json);
        assertThat(json.writeValueAsString(fixture)).isEqualTo(before);
    }
    @Test void rejectsMissingCrossParentAndCorruptRevisionMetadata() throws Exception {
        var missing=fixture();rejects(new Fixture(null,missing.accepted()));
        for(Consumer<EventSubscriptionRevision> change:List.<Consumer<EventSubscriptionRevision>>of(
                row->row.setId("another-revision"),row->row.setSubscriptionId("another-parent"),
                row->row.setContentHash("bad"),row->row.setCreatedBy("bob"),row->row.setVersionNo(0),row->row.setSourceDraftRevision(0))){
            var fixture=fixture();change.accept(fixture.version());rejects(fixture);
        }
    }
    @Test void rejectsChangedRequestIdentityConfigurationAndEffectiveInterval() throws Exception {
        for(Consumer<ObjectNode> change:List.<Consumer<ObjectNode>>of(
                body->body.put("name","Changed"),body->body.put("concurrency",3),body->body.put("source","external"),
                body->body.put("eventType","OtherEvent"),body->body.put("eventId","another-event"),
                body->((ObjectNode)body.path("rollout")).put("pipelineId","another-pipe"),
                body->((ObjectNode)body.path("target")).put("conversationId","another-conversation"),
                body->((ObjectNode)body.path("target")).put("inputRevision","b".repeat(64)),
                body->body.put("occurredAt","2026-10-22T23:59:59Z"))){
            rejects(changedRequest(change));
        }
        rejects(changedRequest(body->{body.put("occurredAt","2026-10-23T01:00:00Z");body.put("expiresAt","2026-10-23T01:01:00Z");}));
    }
    @Test void rejectsRehashedParentWhoseBoundOwnerNoLongerMatchesReceipt() throws Exception {
        var fixture=fixture();var body=(ObjectNode)json.readTree(fixture.version().getSnapshotJson());
        ((ObjectNode)body.path("ownerBound")).put("userId","another-user");
        fixture.version().setSnapshotJson(json.writeValueAsString(body));
        fixture.version().setContentHash(SelectorAssetService.hash(fixture.version().getSnapshotJson()));
        rejects(fixture);
    }
}

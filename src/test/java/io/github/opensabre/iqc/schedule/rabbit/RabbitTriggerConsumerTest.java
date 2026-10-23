package io.github.opensabre.iqc.schedule.rabbit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.github.opensabre.common.core.util.UserContextHolder;
import io.github.opensabre.iqc.schedule.*;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real strict parser and scheduled scope; persistence and AMQP channel are isolated boundaries. */
class RabbitTriggerConsumerTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final TaskTriggerService parser=new TaskTriggerService(null,null,null,null,null,null,null,null,null,null,json,null,null);
    final TaskTriggerService triggers=mock(TaskTriggerService.class);
    final ScheduledActorAccessService actors=mock(ScheduledActorAccessService.class);
    final IqcDataScope scope=new IqcDataScope(null);
    final Channel channel=mock(Channel.class);
    final RabbitTriggerProperties properties=properties();
    final ObjectProvider<MeterRegistry> meters=mock(ObjectProvider.class);
    final RabbitTriggerConsumer consumer=new RabbitTriggerConsumer(properties,triggers,actors,scope,meters);
    static RabbitTriggerProperties properties(){var p=new RabbitTriggerProperties();p.setUri("amqp://test:fixture@127.0.0.1:35672/%2f");p.setOwnerUserId("owner-id");p.setOwner("owner");p.setGroupId("group");p.setPipelineIds(Set.of("pipeline"));return p;}
    String payload(){return """
        {"schemaVersion":"iqc-task-trigger-v1","source":"business-events","eventId":"event","eventType":"RiskDetected",
         "occurredAt":"2026-10-23T00:00:00Z","expiresAt":"2026-10-24T00:00:00Z","name":"processed conversation",
         "pipeline":{"pipelineId":"pipeline","versionNo":1},"target":{"conversationId":"conversation","inputRevision":"%s"},"concurrency":1,"requireASR":false}
        """.formatted("a".repeat(64));}
    Message message(byte[] body){var props=new MessageProperties();props.setDeliveryTag(7);return new Message(body,props);}
    void deliver(String body) throws Exception {consumer.receive(message(body.getBytes(StandardCharsets.UTF_8)),channel);}
    @BeforeEach void setup(){clear();when(triggers.externalRequest(anyString())).thenAnswer(c->parser.externalRequest(c.getArgument(0)));
        when(actors.resolve(any(),anySet())).thenReturn(new ScheduledActorAccessService.Resolved("owner-id","owner",false,"group",Set.of(),Set.of("iqc:pipeline:task:create","iqc:task:execute","iqc:trigger:receive"),Instant.now()));
        when(triggers.receiveFromBroker(any(),any(),any())).thenAnswer(c->{assertThat(scope.owner()).isEqualTo("owner");assertThat(scope.groupId()).isEqualTo("group");assertThat(scope.canViewAll()).isFalse();var task=new InspectionTask();task.setId("durable-task");return task;});}
    @AfterEach void clear(){UserContextHolder.getInstance().clear();SecurityContextHolder.clearContext();}
    @Test void acknowledgementFollowsAcceptanceReturnAndScopeIsCleared() throws Exception {deliver(payload());var ordered=inOrder(triggers,channel);ordered.verify(triggers).externalRequest(anyString());ordered.verify(triggers).receiveFromBroker(any(),eq(properties.bound()),any());ordered.verify(channel).basicAck(7,false);verify(channel,never()).basicReject(anyLong(),anyBoolean());assertThat(scope.owner()).isEqualTo("system");verify(actors).resolve(eq(properties.bound()),eq(Set.of("iqc:pipeline:task:create","iqc:task:execute","iqc:trigger:receive")));}
    @Test void acceptanceFailureOrMissingDurableIdRejectsWithoutRequeue() throws Exception {doThrow(new IllegalStateException("fixture failure")).when(triggers).receiveFromBroker(any(),any(),any());deliver(payload());verify(channel).basicReject(7,false);verify(channel,never()).basicAck(anyLong(),anyBoolean());assertThat(scope.owner()).isEqualTo("system");reset(channel);doReturn(new InspectionTask()).when(triggers).receiveFromBroker(any(),any(),any());deliver(payload());verify(channel).basicReject(7,false);verify(channel,never()).basicAck(anyLong(),anyBoolean());}
    @Test void currentAuthorizationFailureNeverCallsAcceptance() throws Exception {when(actors.resolve(any(),anySet())).thenThrow(new IllegalStateException("revoked"));deliver(payload());verify(triggers,never()).receiveFromBroker(any(),any(),any());verify(channel).basicReject(7,false);verify(channel,never()).basicAck(anyLong(),anyBoolean());}
    @Test void rejectsUnknownDuplicateFieldsAndInternalSourceWithActualParser() throws Exception {for(String body:new String[]{payload().replace("\"name\":","\"owner\":\"attacker\",\"name\":"),payload().replace("\"eventId\":\"event\"","\"eventId\":\"event\",\"eventId\":\"duplicate\""),payload().replace("business-events","archive-subscription")})deliver(body);verify(channel,times(3)).basicReject(7,false);verifyNoInteractions(actors);verify(triggers,never()).receiveFromBroker(any(),any(),any());}
    @Test void deploymentBindingRejectsOtherSourcePipelineAndEventType() throws Exception {deliver(payload().replace("business-events","other"));deliver(payload().replace("\"pipelineId\":\"pipeline\"","\"pipelineId\":\"foreign\""));deliver(payload().replace("RiskDetected","OtherEvent"));verify(channel,times(3)).basicReject(7,false);verifyNoInteractions(actors);}
    @Test void malformedUtf8AndOversizeBodyNeverReachJsonParser() throws Exception {consumer.receive(message(new byte[]{(byte)0xc3,0x28}),channel);consumer.receive(message(new byte[16*1024*1024+1]),channel);verifyNoInteractions(triggers,actors);verify(channel,times(2)).basicReject(7,false);}
    @Test void userContextCannotBeBorrowedByBackgroundConsumer() throws Exception {UserContextHolder.getInstance().setContext(Map.of("user_name","interactive","user_id","interactive-id"));deliver(payload());verifyNoInteractions(triggers,actors);verify(channel).basicReject(7,false);assertThat(UserContextHolder.getInstance().getUsername()).isEqualTo("interactive");}
    @Test void lostAcknowledgementPropagatesWithoutRejectingCommittedAcceptance() throws Exception {doThrow(new java.io.IOException("ack lost")).when(channel).basicAck(7,false);assertThatThrownBy(()->deliver(payload())).isInstanceOf(java.io.IOException.class);verify(triggers).receiveFromBroker(any(),any(),any());verify(channel,never()).basicReject(anyLong(),anyBoolean());assertThat(scope.owner()).isEqualTo("system");}
}

package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.schedule.*;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.pipelineasset.model.PipelineAsset;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual receiver transactions and InnoDB persistence; published assets remain controlled fixtures. */
class IsolatedMysqlBrokerAcceptanceIT {
    static AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    TaskTriggerService service;
    final IqcDataScope scope=new IqcDataScope(null);
    final PipelineAssetService pipelines=mock(PipelineAssetService.class);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final ScheduledActorAccessService.CreationBound bound=new ScheduledActorAccessService.CreationBound("iqc-scheduled-owner-v1","broker-owner-id","broker-owner",false,"group");
    PlatformTransactionManager transactions;

    @BeforeAll static void start() throws Exception {
        IsolatedMysqlTaskExecutionStateIT.start();
        context=new AnnotationConfigApplicationContext(IsolatedMysqlTaskExecutionStateIT.DatabaseConfiguration.class);
        var factory=context.getBean(SqlSessionFactory.class);
        factory.getConfiguration().addMapper(ConversationMapper.class);
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(factory.getConfiguration())
                .setMetaObjectHandler(new io.github.opensabre.persistence.handler.PoMetaObjectHandler());
    }
    @AfterAll static void close(){if(context!=null)context.close();IsolatedMysqlTaskExecutionStateIT.close();}
    @BeforeEach void fixture(){
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.update("DELETE FROM iqc_inspection_task WHERE trigger_source='broker-sql'");
        transactions=context.getBean(PlatformTransactionManager.class);
        var session=new SqlSessionTemplate(context.getBean(SqlSessionFactory.class));
        service=new TaskTriggerService(session.getMapper(InspectionTaskMapper.class),session.getMapper(ConversationMapper.class),null,pipelines,null,null,null,scope,null,null,json,null,transactions);
        when(pipelines.publishedReleaseForTask("pipeline",1)).thenReturn(new PipelineAssetService.Prepared("pipeline",1,1,null,"a".repeat(64)));
        var asset=new PipelineAsset();asset.setOwnerGroupId("group");when(pipelines.get("pipeline")).thenReturn(asset);
    }
    TaskTriggerRequest request(String name){var now=Instant.now();return new TaskTriggerRequest("iqc-task-trigger-v1","broker-sql","event","RiskDetected",now.minusSeconds(1),now.plusSeconds(600),name,new PeriodicDefinition.PipelineReference("pipeline",1),null,new TaskTriggerRequest.Target("missing-conversation",null,null,"b".repeat(64)),null,1,false);}
    ScheduledActorAccessService.Resolved actor(Set<String> grants){return new ScheduledActorAccessService.Resolved(bound.userId(),bound.owner(),false,"group",Set.of(),grants,Instant.now());}
    io.github.opensabre.iqc.task.model.InspectionTask receive(TaskTriggerRequest request){var actor=actor(TaskTriggerService.grants(request));return scope.asScheduledActor(actor,()->service.receiveFromBroker(request,bound,actor));}

    @Test void acceptanceCommitsIndependentlyOfCallerRollbackAndDuplicateDoesNotResolveAssetsAgain(){
        var request=request("processed conversation");
        var outer=new TransactionTemplate(transactions);
        String id=outer.execute(status->{var row=receive(request);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE id=?",Integer.class,row.getId())).isOne();status.setRollbackOnly();return row.getId();});
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_inspection_task WHERE id=?",String.class,id)).isEqualTo("WAITING_INPUT");
        assertThat(jdbc.queryForObject("SELECT created_by FROM iqc_inspection_task WHERE id=?",String.class,id)).isEqualTo(bound.owner());
        clearInvocations(pipelines);
        assertThat(receive(request).getId()).isEqualTo(id);
        verifyNoInteractions(pipelines);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE trigger_source='broker-sql'",Integer.class)).isOne();
    }
    @Test void changedPayloadCannotOverwriteCommittedEvent(){
        var original=request("original");var row=receive(original);
        var changed=new TaskTriggerRequest(original.schemaVersion(),original.source(),original.eventId(),original.eventType(),original.occurredAt(),original.expiresAt(),"changed",original.pipeline(),null,original.target(),null,1,false);
        assertThatThrownBy(()->receive(changed)).hasMessageContaining("相同触发身份对应不同载荷");
        assertThat(jdbc.queryForObject("SELECT name FROM iqc_inspection_task WHERE id=?",String.class,row.getId())).isEqualTo("original");
    }
    @Test void missingCurrentGrantAndAssetFailureLeaveNoAcceptance(){
        var request=request("unauthorized");var denied=actor(Set.of("iqc:task:execute"));
        assertThatThrownBy(()->scope.asScheduledActor(denied,()->service.receiveFromBroker(request,bound,denied))).hasMessageContaining("授权");
        when(pipelines.publishedReleaseForTask("pipeline",1)).thenThrow(new IllegalStateException("asset unavailable"));
        assertThatThrownBy(()->receive(request)).hasMessageContaining("asset unavailable");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE trigger_source='broker-sql'",Integer.class)).isZero();
        assertThat(scope.owner()).isEqualTo("system");
    }
    /** The ACK seam queries a separate JDBC connection before forwarding the real broker ACK. */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="IQC_RABBIT_VALIDATION_PORT",matches="35672")
    void realBrokerLostAckRedeliversCommittedTaskAndConflictsGoToDeadLetter() throws Exception {
        var properties=new io.github.opensabre.iqc.schedule.rabbit.RabbitTriggerProperties();
        properties.setUri("amqp://guest:guest@127.0.0.1:35672/%2f");
        properties.setQueue("iqc.sql."+java.util.UUID.randomUUID().toString().replace("-",""));
        properties.setSource("broker-sql");properties.setOwnerUserId(bound.userId());properties.setOwner(bound.owner());
        properties.setGroupId(bound.groupId());properties.setPipelineIds(Set.of("pipeline"));
        var actors=mock(ScheduledActorAccessService.class);
        when(actors.resolve(any(),anySet())).thenAnswer(call->actor(call.getArgument(1)));
        var consumer=new io.github.opensabre.iqc.schedule.rabbit.RabbitTriggerConsumer(properties,service,actors,scope,mock(org.springframework.beans.factory.ObjectProvider.class));
        var configuration=new io.github.opensabre.iqc.schedule.rabbit.RabbitTriggerConfiguration();
        var factory=configuration.iqcTriggerRabbitConnection(properties);
        var admin=configuration.iqcTriggerRabbitAdmin(factory);
        com.rabbitmq.client.Channel channel=null;
        try {
            for(var declaration:configuration.iqcTriggerQueues(properties).getDeclarables()){
                if(declaration instanceof org.springframework.amqp.core.Exchange exchange)admin.declareExchange(exchange);
                else if(declaration instanceof org.springframework.amqp.core.Queue queue)admin.declareQueue(queue);
                else if(declaration instanceof org.springframework.amqp.core.Binding binding)admin.declareBinding(binding);
            }
            var connection=factory.createConnection();channel=connection.createChannel(false);
            byte[] body=json.writeValueAsBytes(request("committed before ACK"));
            channel.confirmSelect();channel.basicPublish(properties.getQueue()+".exchange","broker-sql",true,com.rabbitmq.client.MessageProperties.PERSISTENT_TEXT_PLAIN,body);channel.waitForConfirmsOrDie(5000);
            var first=delivery(channel,properties.getQueue());
            var lostAck=spy(channel);
            doAnswer(call->{
                // No Spring transaction is active here, so JdbcTemplate must observe the committed row independently.
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE trigger_source='broker-sql' AND status='WAITING_INPUT'",Integer.class)).isOne();
                throw new java.io.IOException("controlled lost ACK after SQL commit");
            }).when(lostAck).basicAck(first.getEnvelope().getDeliveryTag(),false);
            assertThatThrownBy(()->consumer.receive(message(first),lostAck)).isInstanceOf(java.io.IOException.class);
            String taskId=jdbc.queryForObject("SELECT id FROM iqc_inspection_task WHERE trigger_source='broker-sql'",String.class);
            org.springframework.amqp.rabbit.connection.RabbitUtils.setPhysicalCloseRequired(channel,true);channel.close();channel=connection.createChannel(false);
            var redelivery=delivery(channel,properties.getQueue());assertThat(redelivery.getEnvelope().isRedeliver()).isTrue();assertThat(redelivery.getBody()).containsExactly(body);
            clearInvocations(pipelines);consumer.receive(message(redelivery),channel);verifyNoInteractions(pipelines);
            assertThat(jdbc.queryForList("SELECT id FROM iqc_inspection_task WHERE trigger_source='broker-sql'",String.class)).containsExactly(taskId);
            org.springframework.amqp.rabbit.connection.RabbitUtils.setPhysicalCloseRequired(channel,true);channel.close();channel=connection.createChannel(false);
            assertThat(channel.basicGet(properties.getQueue(),false)).isNull();
            var changed=json.readTree(body);((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("name","conflicting delivery");byte[] conflict=json.writeValueAsBytes(changed);
            channel.confirmSelect();channel.basicPublish(properties.getQueue()+".exchange","broker-sql",true,com.rabbitmq.client.MessageProperties.PERSISTENT_TEXT_PLAIN,conflict);channel.waitForConfirmsOrDie(5000);
            consumer.receive(message(delivery(channel,properties.getQueue())),channel);
            var dead=delivery(channel,properties.getQueue()+".dead");assertThat(dead.getBody()).containsExactly(conflict);channel.basicAck(dead.getEnvelope().getDeliveryTag(),false);
            assertThat(jdbc.queryForObject("SELECT name FROM iqc_inspection_task WHERE id=?",String.class,taskId)).isEqualTo("committed before ACK");
            assertThat(scope.owner()).isEqualTo("system");
        } finally {
            try {admin.deleteQueue(properties.getQueue());admin.deleteQueue(properties.getQueue()+".dead");admin.deleteExchange(properties.getQueue()+".exchange");admin.deleteExchange(properties.getQueue()+".dead.exchange");}
            finally {if(channel!=null)channel.close();factory.destroy();}
        }
    }
    private static com.rabbitmq.client.GetResponse delivery(com.rabbitmq.client.Channel channel,String queue) throws Exception {
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {var response=channel.basicGet(queue,false);if(response!=null)return response;Thread.sleep(20);}while(System.nanoTime()<end);
        throw new AssertionError("No delivery on isolated queue");
    }
    private static org.springframework.amqp.core.Message message(com.rabbitmq.client.GetResponse response){
        var properties=new org.springframework.amqp.core.MessageProperties();properties.setDeliveryTag(response.getEnvelope().getDeliveryTag());
        return new org.springframework.amqp.core.Message(response.getBody(),properties);
    }

}

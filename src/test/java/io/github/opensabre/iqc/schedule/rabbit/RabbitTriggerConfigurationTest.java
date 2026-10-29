package io.github.opensabre.iqc.schedule.rabbit;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.assertj.core.api.Assertions.*;

class RabbitTriggerConfigurationTest {
    @Test void disabledConfigurationRegistersNoTransportOrConsumerWithoutCredentials(){for(var enabled:new String[]{"false","absent"})try(var context=new AnnotationConfigApplicationContext()){
        if(!"absent".equals(enabled))context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",Map.of("iqc.trigger.rabbit.enabled",enabled)));
        context.register(RabbitTriggerConfiguration.class,RabbitTriggerConsumer.class);context.refresh();
        assertThat(context.getBeansOfType(ConnectionFactory.class)).isEmpty();assertThat(context.getBeansOfType(RabbitTriggerConsumer.class)).isEmpty();assertThat(context.getBeansOfType(RabbitTriggerProperties.class)).isEmpty();}}
    @Test void invalidConfigurationAndUriFailWithoutLeakingCredentials(){var config=new RabbitTriggerConfiguration();var p=RabbitTriggerConsumerTest.properties();p.setPipelineIds(java.util.Set.of());assertThatThrownBy(p::validate).hasMessageContaining("explicit allowed assets");p=RabbitTriggerConsumerTest.properties();p.setUri("https://fixture-user:private-fixture@localhost/");var invalid=p;assertThatThrownBy(()->config.iqcTriggerRabbitConnection(invalid)).hasMessage("IQC Rabbit connection URI or TLS configuration is invalid").hasNoCause();assertThat(invalid.toString()).doesNotContain("private-fixture");}
    @Test void topologyIsDurableAndRestrictedToDedicatedAdmin(){var p=RabbitTriggerConsumerTest.properties();var declarations=new RabbitTriggerConfiguration().iqcTriggerQueues(p).getDeclarables();assertThat(declarations).hasSize(6);for(var item:declarations)assertThat(item.getDeclaringAdmins().toArray()).containsExactly("iqcTriggerRabbitAdmin");var queues=declarations.stream().filter(Queue.class::isInstance).map(Queue.class::cast).toList();assertThat(queues).allMatch(q->q.isDurable()&&!q.isAutoDelete());var main=queues.stream().filter(q->q.getName().equals(p.getQueue())).findFirst().orElseThrow();assertThat(main.getArguments()).containsEntry("x-dead-letter-exchange",p.getQueue()+".dead.exchange").containsEntry("x-dead-letter-routing-key","rejected");}
}

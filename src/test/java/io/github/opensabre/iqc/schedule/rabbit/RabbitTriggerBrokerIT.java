package io.github.opensabre.iqc.schedule.rabbit;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Dedicated local broker only. Acceptance is mocked; these tests do not prove DB transaction atomicity. */
@EnabledIfEnvironmentVariable(named="IQC_RABBIT_VALIDATION_PORT",matches="35672")
class RabbitTriggerBrokerIT {
    static class Fixture implements AutoCloseable {
        final RabbitTriggerConsumerTest receiver=new RabbitTriggerConsumerTest();
        final CachingConnectionFactory factory;
        final Connection connection;
        final RabbitAdmin admin;
        Channel channel;
        final String queue="iqc.b."+UUID.randomUUID().toString().replace("-","");
        Fixture() throws Exception {
            receiver.setup();receiver.properties.setUri("amqp://guest:guest@127.0.0.1:35672/%2f");receiver.properties.setQueue(queue);
            var configuration=new RabbitTriggerConfiguration();factory=configuration.iqcTriggerRabbitConnection(receiver.properties);
            connection=factory.createConnection();channel=connection.createChannel(false);admin=configuration.iqcTriggerRabbitAdmin(factory);
            for(var declaration:configuration.iqcTriggerQueues(receiver.properties).getDeclarables()){
                if(declaration instanceof Exchange e)admin.declareExchange(e);
                else if(declaration instanceof Queue q)admin.declareQueue(q);
                else if(declaration instanceof Binding b)admin.declareBinding(b);
            }
        }
        void publish(byte[] body) throws Exception {channel.confirmSelect();channel.basicPublish(queue+".exchange","business-events",true,com.rabbitmq.client.MessageProperties.PERSISTENT_TEXT_PLAIN,body);channel.waitForConfirmsOrDie(5000);}
        GetResponse get(String name) throws Exception {long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);do{var d=channel.basicGet(name,false);if(d!=null)return d;Thread.sleep(20);}while(System.nanoTime()<until);throw new AssertionError("No message on dedicated fixture queue");}
        Message message(GetResponse delivery){var properties=new MessageProperties();properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());return new Message(delivery.getBody(),properties);}
        void reopen() throws Exception {org.springframework.amqp.rabbit.connection.RabbitUtils.setPhysicalCloseRequired(channel,true);channel.close();channel=connection.createChannel(false);}
        public void close() throws Exception {try{admin.deleteQueue(queue);admin.deleteQueue(queue+".dead");admin.deleteExchange(queue+".exchange");admin.deleteExchange(queue+".dead.exchange");}finally{try{channel.close();connection.close();}finally{factory.destroy();receiver.clear();}}}
    }
    @Test void realBrokerAcknowledgesAcceptedMessagesAndDeadLettersMalformedOrFailedAcceptance() throws Exception {
        try(var f=new Fixture()){
            f.publish(f.receiver.payload().getBytes(StandardCharsets.UTF_8));var accepted=f.get(f.queue);f.receiver.consumer.receive(f.message(accepted),f.channel);
            f.reopen();assertThat(f.channel.basicGet(f.queue,false)).isNull();
            byte[] malformed={(byte)0xc3,0x28};f.publish(malformed);var rejected=f.get(f.queue);f.receiver.consumer.receive(f.message(rejected),f.channel);
            var dead=f.get(f.queue+".dead");assertThat(dead.getBody()).containsExactly(malformed);f.channel.basicAck(dead.getEnvelope().getDeliveryTag(),false);
            doThrow(new IllegalStateException("acceptance unavailable")).when(f.receiver.triggers).receiveFromBroker(any(),any(),any());
            byte[] body=f.receiver.payload().getBytes(StandardCharsets.UTF_8);f.publish(body);var failed=f.get(f.queue);f.receiver.consumer.receive(f.message(failed),f.channel);
            var failedDead=f.get(f.queue+".dead");assertThat(failedDead.getBody()).containsExactly(body);f.channel.basicAck(failedDead.getEnvelope().getDeliveryTag(),false);
            assertThat(f.receiver.scope.owner()).isEqualTo("system");
        }
    }
    @Test void lostAckAllowsBrokerRedeliveryWithTheSameEventBody() throws Exception {
        try(var f=new Fixture()){
            byte[] body=f.receiver.payload().getBytes(StandardCharsets.UTF_8);f.publish(body);var first=f.get(f.queue);
            Channel lostAck=spy(f.channel);doThrow(new java.io.IOException("controlled lost ACK")).when(lostAck).basicAck(first.getEnvelope().getDeliveryTag(),false);
            assertThatThrownBy(()->f.receiver.consumer.receive(f.message(first),lostAck)).isInstanceOf(java.io.IOException.class);
            f.reopen();var redelivered=f.get(f.queue);assertThat(redelivered.getEnvelope().isRedeliver()).isTrue();assertThat(redelivered.getBody()).containsExactly(body);
            f.receiver.consumer.receive(f.message(redelivered),f.channel);f.reopen();assertThat(f.channel.basicGet(f.queue,false)).isNull();
            verify(f.receiver.triggers,times(2)).receiveFromBroker(any(),any(),any());assertThat(f.receiver.scope.owner()).isEqualTo("system");
        }
    }
}

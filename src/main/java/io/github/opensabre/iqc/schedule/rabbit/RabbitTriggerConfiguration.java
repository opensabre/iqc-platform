package io.github.opensabre.iqc.schedule.rabbit;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@EnableRabbit
@EnableConfigurationProperties(RabbitTriggerProperties.class)
@ConditionalOnProperty(prefix="iqc.trigger.rabbit",name="enabled",havingValue="true")
public class RabbitTriggerConfiguration {
    @Bean(destroyMethod="destroy")
    public CachingConnectionFactory iqcTriggerRabbitConnection(RabbitTriggerProperties properties){
        properties.validate();
        try{
            var uri=java.net.URI.create(properties.getUri());
            if(!java.util.Set.of("amqp","amqps").contains(uri.getScheme())||uri.getHost()==null||uri.getFragment()!=null||uri.getQuery()!=null
                    ||uri.getRawUserInfo()==null||uri.getRawUserInfo().indexOf(':')<1||uri.getRawUserInfo().endsWith(":"))
                throw new IllegalArgumentException();
            var client=new com.rabbitmq.client.ConnectionFactory();client.setUri(uri);
            if("amqps".equals(uri.getScheme())){client.useSslProtocol(javax.net.ssl.SSLContext.getDefault());client.enableHostnameVerification();}
            client.setAutomaticRecoveryEnabled(false);client.setConnectionTimeout(10000);client.setRequestedHeartbeat(30);
            return new CachingConnectionFactory(client);
        }catch(Exception invalid){throw new IllegalStateException("IQC Rabbit connection URI or TLS configuration is invalid");}
    }
    @Bean public RabbitAdmin iqcTriggerRabbitAdmin(@Qualifier("iqcTriggerRabbitConnection") ConnectionFactory connection){
        var admin=new RabbitAdmin(connection);admin.setExplicitDeclarationsOnly(true);return admin;
    }
    @Bean public Declarables iqcTriggerQueues(RabbitTriggerProperties properties){
        properties.validate();String queue=properties.getQueue();
        var incoming=new DirectExchange(queue+".exchange",true,false);
        var rejected=new DirectExchange(queue+".dead.exchange",true,false);
        var main=QueueBuilder.durable(queue).deadLetterExchange(rejected.getName()).deadLetterRoutingKey("rejected").build();
        var dead=QueueBuilder.durable(queue+".dead").build();
        var incomingBinding=BindingBuilder.bind(main).to(incoming).with(properties.getSource());
        var rejectedBinding=BindingBuilder.bind(dead).to(rejected).with("rejected");
        for(var declaration:java.util.List.<AbstractDeclarable>of(incoming,rejected,main,dead,incomingBinding,rejectedBinding))
            declaration.setAdminsThatShouldDeclare("iqcTriggerRabbitAdmin");
        return new Declarables(incoming,rejected,main,dead,incomingBinding,rejectedBinding);
    }
    @Bean public SimpleRabbitListenerContainerFactory iqcTriggerRabbitFactory(@Qualifier("iqcTriggerRabbitConnection") ConnectionFactory connection,RabbitTriggerProperties properties){
        properties.validate();var factory=new SimpleRabbitListenerContainerFactory();factory.setConnectionFactory(connection);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);factory.setConcurrentConsumers(1);factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);factory.setDefaultRequeueRejected(false);
        factory.setMessageConverter(new org.springframework.amqp.support.converter.MessageConverter(){
            @Override public Object fromMessage(Message message){return message.getBody();}
            @Override public Message toMessage(Object value,MessageProperties metadata){
                throw new org.springframework.amqp.support.converter.MessageConversionException("IQC trigger transport is receive-only");
            }
        });
        // Do not let the default listener exception logger print rejected conversation bodies.
        factory.setErrorHandler(error->{throw new AmqpRejectAndDontRequeueException("IQC_TRIGGER_DELIVERY_UNAVAILABLE",true,null);});
        return factory;
    }
}

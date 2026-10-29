package io.github.opensabre.iqc.schedule.rabbit;

import com.rabbitmq.client.Channel;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.schedule.TaskTriggerService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(prefix="iqc.trigger.rabbit",name="enabled",havingValue="true")
public class RabbitTriggerConsumer {
    private final RabbitTriggerProperties properties;
    private final TaskTriggerService triggers;
    private final ScheduledActorAccessService actors;
    private final IqcDataScope scope;
    private final ObjectProvider<MeterRegistry> meters;
    @RabbitListener(queues="${iqc.trigger.rabbit.queue:iqc.trigger.events}",containerFactory="iqcTriggerRabbitFactory",admin="iqcTriggerRabbitAdmin")
    public void receive(Message message,Channel channel) throws IOException {
        long tag=message.getMessageProperties().getDeliveryTag();
        try{
            ScheduledActorAccessService.requireBackground();
            byte[] body=message.getBody();if(body.length>16*1024*1024)throw new IllegalArgumentException("Oversize trigger");
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            var request=triggers.externalRequest(text);
            if(!properties.permits(request))throw new IllegalArgumentException("Trigger outside deployment binding");
            var bound=properties.bound();var actor=actors.resolve(bound,TaskTriggerService.grants(request));
            var accepted=scope.asScheduledActor(actor,()->triggers.receiveFromBroker(request,bound,actor));
            if(accepted==null||accepted.getId()==null)throw new IllegalStateException("Missing durable acceptance");
        }catch(RuntimeException|java.nio.charset.CharacterCodingException rejected){
            channel.basicReject(tag,false);count("rejected");return;
        }
        // A lost ACK can redeliver; the existing owner/source/eventId + payload hash prevents another task.
        channel.basicAck(tag,false);count("accepted");
    }
    private void count(String result){
        try{var registry=meters.getIfAvailable();if(registry!=null)registry.counter("iqc.trigger.rabbit.deliveries","result",result).increment();}
        catch(RuntimeException ignored){/* Observation cannot change the delivery decision. */}
    }
}

package io.github.opensabre.iqc.conversation;

import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationSpeakerRoleTest {
    @Test
    void canonicalizesArchivedChineseRolesOnlyInExecutionCopies() {
        var customer = message("客户", "客户：我没有房子");
        var agent = message("客服", "客服：好的");
        var system = message("系统", "系统：会话结束");

        ConversationSpeakerRole.canonicalize(List.of(customer, agent, system));

        assertThat(customer.getSpeakerRole()).isEqualTo("user");
        assertThat(agent.getSpeakerRole()).isEqualTo("agent");
        assertThat(system.getSpeakerRole()).isEqualTo("系统");
        assertThat(customer.getRawLine()).isEqualTo("客户：我没有房子");
    }

    @Test
    void existingCustomerRoleRemainsDistinctForHistoricalRules() {
        assertThat(ConversationSpeakerRole.canonical("Customer ")).isEqualTo("customer");
        assertThat(ConversationSpeakerRole.canonical(" user ")).isEqualTo("user");
    }

    private ConversationMessage message(String role, String raw) {
        var message = new ConversationMessage();
        message.setSpeakerRole(role); message.setRawLine(raw);
        return message;
    }
}

package io.github.opensabre.iqc.conversation;

import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.shared.IqcDataScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationImportServiceTest {
    @Test
    void persistsParticipantAndBusinessMetadataSnapshots() {
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        ConversationMessageMapper messageMapper = mock(ConversationMessageMapper.class);
        IqcDataScope dataScope = mock(IqcDataScope.class);
        when(dataScope.groupId()).thenReturn("import-group");
        ConversationImportService service = new ConversationImportService(conversationMapper, messageMapper, dataScope,
                new com.fasterxml.jackson.databind.ObjectMapper());
        ConversationMetadata metadata = new ConversationMetadata("employee-1", "张三", "sales",
                "customer-1", "李先生", "138****1234", "wechat",
                LocalDateTime.parse("2026-08-27T10:00:00"), null, "order", "order-1", List.of("高意向"));

        service.persist("conversation.txt", "fingerprint", new ConversationParseResult(List.of(), List.of(), 0),
                "batch-1", "FILE", null, metadata);

        var captor = org.mockito.ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper).insert(captor.capture());
        assertThat(captor.getValue().getEmployeeId()).isEqualTo("employee-1");
        assertThat(captor.getValue().getEmployeeName()).isEqualTo("张三");
        assertThat(captor.getValue().getCustomerExternalId()).isEqualTo("customer-1");
        assertThat(captor.getValue().getChannel()).isEqualTo("WECHAT");
        assertThat(captor.getValue().getBusinessType()).isEqualTo("ORDER");
        assertThat(captor.getValue().getTagsJson()).isEqualTo("[\"高意向\"]");
    }

    @Test
    void duplicateConversationMustRespectDataScope() {
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        ConversationMessageMapper messageMapper = mock(ConversationMessageMapper.class);
        IqcDataScope dataScope = mock(IqcDataScope.class);
        Conversation existing = new Conversation();
        existing.setId("conversation-other-scope");
        existing.setCreatedBy("another-user");
        existing.setOwnerGroupId("another-group");
        when(conversationMapper.selectOne(any())).thenReturn(existing);
        when(dataScope.canView("another-user", "another-group")).thenReturn(false);

        ConversationImportService service = new ConversationImportService(conversationMapper, messageMapper, dataScope,
                new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> service.persist("conversation.txt", "fingerprint", new ConversationParseResult(List.of(), List.of(), 0)))
                .hasMessageContaining("无权访问");
        verify(messageMapper, never()).insert(any(ConversationMessage.class));
    }
}

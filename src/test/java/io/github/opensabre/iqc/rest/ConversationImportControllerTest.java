package io.github.opensabre.iqc.rest;

import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.governance.usage.UsageRecord;
import io.github.opensabre.iqc.conversation.ConversationImportService;
import io.github.opensabre.iqc.conversation.ConversationParseResult;
import io.github.opensabre.iqc.conversation.TxtConversationParser;
import io.github.opensabre.iqc.conversation.ConversationUploadProperties;
import io.github.opensabre.iqc.conversation.model.Conversation;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationImportControllerTest {
    @Test
    void recordsDistinctAttemptAndOutcomeEventIds() throws Exception {
        TxtConversationParser parser = new TxtConversationParser();
        ConversationImportService importService = mock(ConversationImportService.class);
        UsageCounterRecorder recorder = mock(UsageCounterRecorder.class);
        Conversation conversation = new Conversation();
        conversation.setId("conversation-1");
        when(importService.persist(eq("sample.txt"), any(String.class), any(ConversationParseResult.class),
                isNull(), eq("FILE"), isNull(), isNull())).thenReturn(conversation);

        ConversationImportController controller = new ConversationImportController(parser, importService, recorder, uploadProperties());
        controller.importConversation(new MockMultipartFile("file", "sample.txt", "text/plain",
                "0(agent):[00:00:01] 您好\n1(user):[00:00:02] 你好".getBytes()));

        var captor = org.mockito.ArgumentCaptor.forClass(UsageRecord.class);
        verify(recorder, org.mockito.Mockito.times(2)).record(captor.capture());
        List<String> recordIds = captor.getAllValues().stream().map(UsageRecord::recordId).toList();
        assertThat(recordIds).containsExactly("conversation-import:" + recordIds.get(0).split(":")[1] + ":attempt",
                "conversation-import:" + recordIds.get(0).split(":")[1] + ":success");
        assertThat(recordIds.get(0)).isNotEqualTo(recordIds.get(1));
    }

    @Test
    void recordsFailureWhenParsingThrows() throws Exception {
        TxtConversationParser parser = mock(TxtConversationParser.class);
        when(parser.parse(any(String.class))).thenThrow(new IllegalStateException("parser failed"));
        UsageCounterRecorder recorder = mock(UsageCounterRecorder.class);
        ConversationImportController controller = new ConversationImportController(parser,
                mock(ConversationImportService.class), recorder, uploadProperties());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.importConversation(
                new MockMultipartFile("file", "broken.txt", "text/plain", "broken".getBytes())))
                .isInstanceOf(IllegalStateException.class);

        var captor = org.mockito.ArgumentCaptor.forClass(UsageRecord.class);
        verify(recorder, org.mockito.Mockito.times(2)).record(captor.capture());
        assertThat(captor.getAllValues().get(1).recordId()).endsWith(":failure");
    }

    @Test
    void rejectsNonTxtFileBeforeReadingContent() {
        ConversationImportController controller = new ConversationImportController(new TxtConversationParser(),
                mock(ConversationImportService.class), mock(UsageCounterRecorder.class), uploadProperties());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.importConversation(
                        new MockMultipartFile("file", "conversation.csv", "text/csv", "content".getBytes())))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(".txt");
    }

    @Test
    void rejectsFileAboveConfiguredLimit() {
        ConversationUploadProperties properties = uploadProperties();
        properties.setMaxFileSizeBytes(4);
        ConversationImportController controller = new ConversationImportController(new TxtConversationParser(),
                mock(ConversationImportService.class), mock(UsageCounterRecorder.class), properties);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.importConversation(
                        new MockMultipartFile("file", "conversation.txt", "text/plain", "12345".getBytes())))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("超过大小限制");
    }

    @Test
    void batchIngestIsolatesInvalidConversation() {
        ConversationImportService importService = mock(ConversationImportService.class);
        UsageCounterRecorder recorder = mock(UsageCounterRecorder.class);
        Conversation conversation = new Conversation(); conversation.setId("conversation-1"); conversation.setStatus("IMPORTED");
        when(importService.persist(any(String.class), any(String.class), any(ConversationParseResult.class), any(String.class), eq("API"), any(), any()))
                .thenReturn(conversation);
        ConversationImportController controller = new ConversationImportController(new TxtConversationParser(), importService, recorder, uploadProperties());

        var first = new ConversationImportController.ConversationIngestRequest("external-1", null, null,
                List.of(new ConversationImportController.IngestMessage("agent", "00:00:01", "您好")));
        var second = new ConversationImportController.ConversationIngestRequest("external-2", null, null, List.of());
        var result = controller.ingestBatch(new ConversationImportController.ConversationIngestBatchRequest("batch-1", List.of(first, second)));

        assertThat(result).containsEntry("batchNo", "batch-1").containsEntry("successCount", 1).containsEntry("failureCount", 1);
        assertThat((List<?>) result.get("items")).hasSize(2);
    }

    private ConversationUploadProperties uploadProperties() {
        return new ConversationUploadProperties();
    }
}

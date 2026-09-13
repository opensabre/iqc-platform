package io.github.opensabre.iqc.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LabelResultQueryServiceTest {
    @Test
    void retryResultSupersedesTheOlderConversationProjection() {
        LabelResultQueryService service = new LabelResultQueryService(
                mock(io.github.opensabre.iqc.task.dao.InspectionTaskMapper.class),
                mock(io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper.class),
                mock(io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper.class),
                mock(io.github.opensabre.iqc.label.dao.InsightLabelMapper.class),
                mock(io.github.opensabre.iqc.label.dao.LabelGroupMapper.class),
                mock(io.github.opensabre.iqc.label.dao.LabelCategoryMapper.class),
                mock(io.github.opensabre.iqc.conversation.dao.ConversationMapper.class),
                mock(io.github.opensabre.iqc.result.dao.InspectionEvidenceMapper.class),
                new ObjectMapper(), mock(io.github.opensabre.iqc.shared.IqcDataScope.class));
        ConversationInspectionResult oldResult = result("old", 1);
        ConversationInspectionResult retryResult = result("retry", 2);

        ConversationInspectionResult selected = ReflectionTestUtils.invokeMethod(
                service, "newerConversationResult", oldResult, retryResult);

        assertThat(selected).isSameAs(retryResult);
    }

    private ConversationInspectionResult result(String id, long createdTime) {
        ConversationInspectionResult value = new ConversationInspectionResult();
        value.setId(id); value.setCreatedTime(new Date(createdTime));
        return value;
    }
}

package io.github.opensabre.iqc.label;

import io.github.opensabre.iqc.label.dao.InsightLabelMapper;
import io.github.opensabre.iqc.label.dao.LabelCandidateMapper;
import io.github.opensabre.iqc.label.model.InsightLabel;
import io.github.opensabre.iqc.label.model.LabelCandidate;
import io.github.opensabre.iqc.shared.IqcDataScope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LabelCandidateServiceTest {
    private final LabelCandidateMapper mapper = mock(LabelCandidateMapper.class);
    private final InsightLabelMapper labelMapper = mock(InsightLabelMapper.class);
    private final LabelTaxonomyService taxonomy = mock(LabelTaxonomyService.class);
    private final IqcDataScope dataScope = mock(IqcDataScope.class);
    private final LabelCandidateService service = new LabelCandidateService(mapper, labelMapper, taxonomy, dataScope);

    @Test
    void newProposalCanOnlyEnterPendingPool() {
        LabelCandidate value = service.propose(new LabelCandidateService.Proposal("task-1", "conversation-1", null,
                "group-1", "投诉意图", "complaint", "", null, "[]", new BigDecimal("0.90"), "{}"));
        assertThat(value.getStatus()).isEqualTo("PENDING");
        verify(mapper).insert(value);
    }

    @Test
    void approvalCreatesDraftInsteadOfPublishing() {
        LabelCandidate candidate = new LabelCandidate(); candidate.setId("candidate-1"); candidate.setStatus("PENDING");
        candidate.setSuggestedName("投诉意图"); candidate.setSuggestedCode("complaint");
        when(mapper.selectById("candidate-1")).thenReturn(candidate);
        when(dataScope.canView(null, null)).thenReturn(true);
        InsightLabel draft = new InsightLabel(); draft.setId("label-1"); draft.setStatus("DRAFT");
        when(taxonomy.createLabel(any())).thenReturn(draft);

        LabelCandidate approved = service.approveAsDraft("candidate-1", "group-1", "all", BigDecimal.ONE, "ok");

        assertThat(draft.getStatus()).isEqualTo("DRAFT");
        assertThat(approved.getStatus()).isEqualTo("APPROVED");
        assertThat(approved.getCreatedLabelId()).isEqualTo("label-1");
        verify(labelMapper).updateById(draft);
        verify(mapper).updateById(candidate);
    }

    @Test
    void mergeRequiresVisiblePublishedTarget() {
        LabelCandidate candidate = new LabelCandidate(); candidate.setId("candidate-1"); candidate.setStatus("PENDING");
        when(mapper.selectById("candidate-1")).thenReturn(candidate);
        when(dataScope.canView(null, null)).thenReturn(true, false);
        InsightLabel target = new InsightLabel(); target.setId("label-1"); target.setStatus("PUBLISHED");
        when(labelMapper.selectById("label-1")).thenReturn(target);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.merge("candidate-1", "label-1", "merge"))
                .isInstanceOf(io.github.opensabre.iqc.governance.IqcException.class).hasMessageContaining("无权");
    }
}

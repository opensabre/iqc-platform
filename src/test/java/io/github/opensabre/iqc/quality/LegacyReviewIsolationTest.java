package io.github.opensabre.iqc.quality;

import io.github.opensabre.iqc.quality.dao.ResultReviewMapper;
import io.github.opensabre.iqc.quality.model.ResultReview;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyReviewIsolationTest {
    @Test
    void businessRecordCannotBeChangedByLegacyManualScoreEndpoint() {
        var mapper = mock(ResultReviewMapper.class);
        var service = new QualityOperationsService(null, mapper, null, null, null, null, null);
        var business = new ResultReview(); business.setTargetType("BUSINESS");
        when(mapper.selectById("business")).thenReturn(business);
        assertThatThrownBy(() -> service.decideReview("business", "CORRECTED", "HIT", 0, "HIGH", "修改"))
                .hasMessageContaining("不能通过消息改分");
        verify(mapper, never()).updateById(any(ResultReview.class));
        assertThat(new ResultReview().getTargetType()).isEqualTo("MESSAGE");
    }
}

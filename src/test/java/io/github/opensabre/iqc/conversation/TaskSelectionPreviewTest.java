package io.github.opensabre.iqc.conversation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.TaskDataSelection;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Regression coverage for sampling beyond the first database page and preview data scope. */
class TaskSelectionPreviewTest {
    private final ConversationMapper mapper = mock(ConversationMapper.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final ConversationQueryService service = new ConversationQueryService(mapper, mock(ConversationMessageMapper.class), scope);

    @BeforeEach void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "preview"), Conversation.class);
        when(scope.owner()).thenReturn("creator"); when(scope.groupId()).thenReturn("team-1");
    }

    @Test void samplesEntirePopulationAndReturnsOneStableSampleForClientPagination() {
        List<Conversation> first = new ArrayList<>();
        for (int i = 0; i < 500; i++) first.add(row(String.format("%04d", i)));
        when(mapper.selectList(any())).thenReturn(first, List.of(row("9999")), first, List.of(row("9999")));
        var selection = new TaskDataSelection(TaskDataSelection.Mode.RANDOM, null, null, 501, "stable-seed");
        var result = service.preview(selection, 1, 10);
        var again = service.preview(selection, 2, 10);
        assertThat(result.matchedTotal()).isEqualTo(501);
        assertThat(result.conversations().records()).hasSize(501).extracting(Conversation::getId).contains("9999");
        assertThat(result.conversations().records().stream().map(Conversation::getId).toList())
                .isEqualTo(again.conversations().records().stream().map(Conversation::getId).toList());
        var captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, times(4)).selectList(captor.capture());
        assertThat(captor.getAllValues().get(0).getSqlSegment()).contains("created_by", "owner_group_id", "message_count", "status");
        assertThat(captor.getAllValues().get(1).getSqlSegment()).contains("id >");
    }

    @Test void reportsShortfallWithoutPretendingRequestedCountWasReturned() {
        when(mapper.selectList(any())).thenReturn(List.of(row("one")));
        var result = service.preview(new TaskDataSelection(TaskDataSelection.Mode.RANDOM, null, null, 5, null), 1, 10);
        assertThat(result.matchedTotal()).isEqualTo(1);
        assertThat(result.selectedTotal()).isEqualTo(1);
        assertThat(result.sampleSeed()).isNotBlank();
        assertThat(result.conversations().records()).hasSize(1);
    }

    @Test void refusesAmbiguousOrInvalidSelectionPayloads() {
        assertThatThrownBy(() -> new TaskDataSelection(TaskDataSelection.Mode.FILTER, List.of("c1"), null, null, null))
                .hasMessageContaining("固定会话名单");
        assertThatThrownBy(() -> new TaskDataSelection(TaskDataSelection.Mode.RANDOM, null, null, 0, null))
                .hasMessageContaining("抽样数量");
        assertThatThrownBy(() -> service.preview(new TaskDataSelection(TaskDataSelection.Mode.BATCH, List.of("c1"), null, null, null), 1, 10))
                .hasMessageContaining("模拟执行");
        verifyNoInteractions(mapper);
    }

    private Conversation row(String id) { Conversation row = new Conversation(); row.setId(id); return row; }
}

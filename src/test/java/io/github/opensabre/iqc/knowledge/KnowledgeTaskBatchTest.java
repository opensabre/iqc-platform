package io.github.opensabre.iqc.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.knowledge.dao.KnowledgeBaseMapper;
import io.github.opensabre.iqc.knowledge.dao.KnowledgeBaseVersionMapper;
import io.github.opensabre.iqc.knowledge.model.KnowledgeBase;
import io.github.opensabre.iqc.shared.IqcDataScope;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Batch task references must be fully validated before any lock or source read occurs. */
class KnowledgeTaskBatchTest {
    private final KnowledgeBaseMapper bases=mock(KnowledgeBaseMapper.class);
    private final KnowledgeBaseVersionMapper versions=mock(KnowledgeBaseVersionMapper.class);
    private final IqcDataScope scope=mock(IqcDataScope.class);
    private final KnowledgeBaseService service=new KnowledgeBaseService(bases,versions,scope,new ObjectMapper());
    private KnowledgeBaseService.Binding binding(String id){return new KnowledgeBaseService.Binding(id,2,"version","a".repeat(64));}
    @Test void validatesWholeBatchBeforeReadingAnySource(){
        var bad=new ArrayList<KnowledgeBaseService.Binding>();bad.add(binding("valid"));bad.add(null);
        assertThatThrownBy(()->service.publishedForTask(bad)).hasMessageContaining("未精确绑定");
        assertThatThrownBy(()->service.publishedForTask(List.of(binding("valid"),new KnowledgeBaseService.Binding("valid",2,"other","a".repeat(64)))))
                .hasMessageContaining("引用冲突");
        assertThatThrownBy(()->service.publishedForTask(Collections.nCopies(401,binding("valid")))).hasMessageContaining("数量无效");
        verifyNoInteractions(bases,versions,scope);
    }
    @Test void locksAlphabeticallyFirstParentRegardlessOfCallerOrder(){
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(),"batch-test"),KnowledgeBase.class);
        when(scope.owner()).thenReturn("owner");
        when(bases.selectOne(any())).thenAnswer(invocation->{
            var query=(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeBase>)invocation.getArgument(0);
            assertThat(query.getSqlSegment()).contains("FOR UPDATE");
            assertThat(query.getParamNameValuePairs().values()).containsExactly("a");
            return null;
        });
        assertThatThrownBy(()->service.publishedForTask(List.of(binding("z"),binding("a")))).hasMessageContaining("不存在");
        verify(bases,times(1)).selectOne(any());verifyNoInteractions(versions);
    }
    @Test void emptyBatchReadsNothingAndReturnsImmutableResult(){
        var result=service.publishedForTask(List.of());assertThat(result).isEmpty();
        assertThatThrownBy(()->result.add(null)).isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(bases,versions,scope);
    }
}

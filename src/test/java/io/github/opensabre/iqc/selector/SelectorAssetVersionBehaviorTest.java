package io.github.opensabre.iqc.selector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.selector.dao.*;
import io.github.opensabre.iqc.selector.model.*;
import io.github.opensabre.iqc.shared.IqcDataScope;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual version/hash/parser behavior with isolated storage and actor scope. */
class SelectorAssetVersionBehaviorTest {
    private final SelectorAssetMapper assets=mock(SelectorAssetMapper.class);
    private final SelectorAssetVersionMapper versions=mock(SelectorAssetVersionMapper.class);
    private final ConversationSelectionService selections=mock(ConversationSelectionService.class);
    private final IqcDataScope scope=mock(IqcDataScope.class);
    private final ObjectMapper json=new ObjectMapper();
    private final SelectorAssetService service=new SelectorAssetService(assets,versions,selections,json,scope);
    private final SelectorAsset asset=new SelectorAsset();
    private final SelectorAssetVersion version=new SelectorAssetVersion();
    private final String snapshot="{\"schemaVersion\":\"iqc-selector-v1\",\"maximumCount\":17,\"condition\":{\"field\":\"durationSeconds\",\"operator\":\"GT\",\"value\":9007199254740993.123456789012345678}}";
    @BeforeEach void fixture(){
        for(Class<?> type:java.util.List.of(SelectorAsset.class,SelectorAssetVersion.class))com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"version"),type);
        asset.setId("asset");asset.setStatus("ACTIVE");asset.setCreatedBy("owner");asset.setOwnerGroupId("group");when(assets.selectOne(any())).thenReturn(asset);when(scope.canView("owner","group")).thenReturn(true);
        version.setSelectorId("asset");version.setVersionNo(7);version.setSourceDraftRevision(17);version.setSnapshotJson(snapshot);version.setContentHash(SelectorAssetService.hash(snapshot));when(versions.selectOne(any())).thenReturn(version);
    }
    @Test void inactiveHistoryCanBeViewedButCannotBeUsedForNewTasks(){
        asset.setStatus("INACTIVE");var view=service.viewVersion("asset",7);assertEquals(false,view.availableForNewTasks());assertEquals(7,view.versionNo());assertEquals(version.getContentHash(),view.contentHash());
        clearInvocations(versions);assertThrows(IqcException.class,()->service.publishedForTask("asset",7));assertThrows(IqcException.class,()->service.published("asset",7));verifyNoInteractions(versions);assertEquals("INACTIVE",asset.getStatus());
        var wrappers=org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);verify(assets,times(3)).selectOne(wrappers.capture());assertTrue(wrappers.getAllValues().get(1).getSqlSegment().contains("FOR UPDATE"));
    }
    @Test void activeInternalPreparedKeepsOnlySixOriginalFrozenFields()throws Exception{
        var internal=service.publishedForTask("asset",7);var viewed=service.viewVersion("asset",7);assertTrue(viewed.availableForNewTasks());
        assertNull(internal.privacy());assertNull(internal.definitionJson());assertNull(internal.availableForNewTasks());
        var fields=new java.util.HashSet<String>();json.valueToTree(internal).fieldNames().forEachRemaining(fields::add);assertEquals(java.util.Set.of("selectorId","versionNo","sourceDraftRevision","definition","contentHash","ownerGroupId"),fields);
        assertEquals("9007199254740993.123456789012345678",internal.definition().condition().value().asText());assertEquals(version.getContentHash(),internal.contentHash());assertEquals(snapshot,version.getSnapshotJson());
    }
    @Test void deniedScopeNeverLoadsOrParsesVersion(){when(scope.canView("owner","group")).thenReturn(false);assertThrows(IqcException.class,()->service.viewVersion("asset",7));verifyNoInteractions(versions,selections);}
    @Test void corruptedStoredHashIsRejectedBeforeSelectionValidation(){version.setContentHash("bad");assertThrows(IqcException.class,()->service.viewVersion("asset",7));verifyNoInteractions(selections);}
    @Test void exactRequestDefinitionPreservesDecimalAndCanonicalHash(){var definition=service.requestDefinition(null,snapshot);assertEquals("9007199254740993.123456789012345678",definition.condition().value().asText());assertEquals(SelectorAssetService.hash(snapshot),SelectorAssetService.hash(snapshot.replace("9007199254740993.123456789012345678","9007199254740993.1234567890123456780")));verifyNoInteractions(assets,versions);}
}

package io.github.opensabre.iqc.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.knowledge.model.KnowledgeBaseVersion;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Bounded corpus parsing and immutable release provenance use the production entry points. */
class KnowledgeCorpusTest {
    private final ObjectMapper json=new ObjectMapper();
    private final KnowledgeBaseService service=new KnowledgeBaseService(null,null,null,json);
    private KnowledgeCorpus.Document document(String id,String text){return new KnowledgeCorpus.Document(id,"Title",text,"source:reference");}
    @Test void canonicalOrderCopiesCallerListAndRoundTrips() throws Exception {
        var input=new ArrayList<>(List.of(document("b","second"),document("a","first")));
        var corpus=new KnowledgeCorpus(KnowledgeCorpus.SCHEMA,input);
        assertThat(input.getFirst().documentId()).isEqualTo("b");input.clear();
        assertThat(corpus.documents()).extracting(KnowledgeCorpus.Document::documentId).containsExactly("a","b");
        assertThatThrownBy(()->corpus.documents().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(service.requestCorpus(json.writeValueAsString(corpus))).isEqualTo(corpus);
    }
    @Test void documentAndAggregateBoundsRejectInvalidContent(){
        assertThatThrownBy(()->document("id","x".repeat(16001))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->document("id","\uD800")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new KnowledgeCorpus.Document("id","Title","body"," source")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new KnowledgeCorpus(KnowledgeCorpus.SCHEMA,List.of(document("same","a"),document("same","b")))).isInstanceOf(IllegalArgumentException.class);
        var documents=new ArrayList<KnowledgeCorpus.Document>();for(int i=0;i<13;i++)documents.add(document("d"+i,"x".repeat(16000)));
        assertThatThrownBy(()->new KnowledgeCorpus(KnowledgeCorpus.SCHEMA,documents)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new KnowledgeCorpus(KnowledgeCorpus.SCHEMA,List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void strictPublicParserRejectsUnknownDuplicateTrailingAndCoercedFields() throws Exception {
        var valid=json.writeValueAsString(new KnowledgeCorpus(KnowledgeCorpus.SCHEMA,List.of(document("id","body"))));
        for(String invalid:List.of(valid+" {}",valid.replace("\"body\"","123"),valid.replace("\"body\"","null"),
                valid.replace("\"title\":\"Title\"","\"title\":\"Title\",\"title\":\"Other\""),
                valid.replace("\"title\":\"Title\"","\"title\":\"Title\",\"unknown\":true"),"null")){
            assertThatThrownBy(()->service.requestCorpus(invalid)).hasMessageContaining("协议无效");
        }
    }
    @Test void releaseEvidenceChecksHashIdentityRevisionAndPublisher() throws Exception {
        var corpus=new KnowledgeCorpus(KnowledgeCorpus.SCHEMA,List.of(document("id","body")));
        var published=new KnowledgeBaseService.Published("iqc-knowledge-release-v1","kb",2,3,"Corpus",corpus,"owner","group","publisher");
        var version=new KnowledgeBaseVersion();version.setId("version");version.setKnowledgeBaseId("kb");version.setVersionNo(2);
        version.setSourceDraftRevision(3);version.setCreatedBy("publisher");version.setSnapshotJson(json.writeValueAsString(published));
        version.setContentHash(SelectorAssetService.hash(version.getSnapshotJson()));
        assertThat(KnowledgeBaseService.readPublishedEvidence(version,json)).isEqualTo(published);
        version.setCreatedBy("other");assertThatThrownBy(()->KnowledgeBaseService.readPublishedEvidence(version,json)).hasMessageContaining("来源或摘要无效");
        version.setCreatedBy("publisher");version.setVersionNo(4);assertThatThrownBy(()->KnowledgeBaseService.readPublishedEvidence(version,json)).hasMessageContaining("来源或摘要无效");
        version.setVersionNo(2);version.setSourceDraftRevision(4);assertThatThrownBy(()->KnowledgeBaseService.readPublishedEvidence(version,json)).hasMessageContaining("来源或摘要无效");
        version.setSourceDraftRevision(3);version.setSnapshotJson(version.getSnapshotJson().replace("body","tampered body"));
        assertThatThrownBy(()->KnowledgeBaseService.readPublishedEvidence(version,json)).hasMessageContaining("来源或摘要无效");
    }
}

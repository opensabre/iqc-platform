package io.github.opensabre.iqc.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.knowledge.model.*;
import io.github.opensabre.iqc.privacy.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Knowledge body visibility requires every existing sensitive grant; metadata never carries bodies. */
class KnowledgeReadProjectionTest {
    @Test void allSensitiveCombinationsPreserveSourcesAndWithholdMetadataBodies() {
        var projection=new ResultReadProjection(new ObjectMapper());
        var row=new KnowledgeBase();row.setId("kb");row.setCode("KB");row.setName("Reference");row.setDraftConfigJson("private draft");
        var version=new KnowledgeBaseVersion();version.setId("v1");version.setKnowledgeBaseId("kb");version.setVersionNo(1);
        version.setSourceDraftRevision(1);version.setSnapshotJson("private history");version.setContentHash("hash");
        var corpus=new KnowledgeCorpus(KnowledgeCorpus.SCHEMA,List.of(new KnowledgeCorpus.Document("doc","Title","private reference","source:manual")));
        var source=new KnowledgeBaseService.Prepared("v1",new KnowledgeBaseService.Published("iqc-knowledge-release-v1","kb",1,1,"Reference",corpus,"owner","group","publisher"),"hash","group");
        for(int mask=0;mask<8;mask++){
            var access=new SensitiveReadAccessService.Access("iqc-sensitive-read-v1",(mask&1)!=0,(mask&2)!=0,(mask&4)!=0,Instant.now());
            assertNull(projection.knowledge(row,access,false).draftConfigJson());
            assertNull(projection.knowledgeHistory(version,access).snapshotJson());
            var detail=projection.knowledge(row,access,true);var exact=projection.knowledgeVersion(source,access);
            assertEquals(mask==7,detail.privacy().get("canonicalPayloadVisible"));
            assertEquals(mask==7,exact.privacy().get("canonicalPayloadVisible"));
            if(mask==7){assertEquals("private draft",detail.draftConfigJson());assertTrue(exact.snapshotJson().contains("private reference"));}
            else {assertNull(detail.draftConfigJson());assertNull(exact.snapshotJson());}
        }
        assertEquals("private draft",row.getDraftConfigJson());assertEquals("private history",version.getSnapshotJson());
        assertEquals("private reference",source.snapshot().corpus().documents().getFirst().text());
    }
}

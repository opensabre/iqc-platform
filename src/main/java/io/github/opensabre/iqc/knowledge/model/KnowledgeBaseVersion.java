package io.github.opensabre.iqc.knowledge.model;
@lombok.Data @lombok.EqualsAndHashCode(callSuper=true)
@com.baomidou.mybatisplus.annotation.TableName("iqc_knowledge_base_version")
public class KnowledgeBaseVersion extends io.github.opensabre.persistence.entity.po.BasePo {
    private String knowledgeBaseId;
    private Integer versionNo;
    private Integer sourceDraftRevision;
    private String snapshotJson;
    private String contentHash;
}

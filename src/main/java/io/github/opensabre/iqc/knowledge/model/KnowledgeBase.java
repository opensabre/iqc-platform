package io.github.opensabre.iqc.knowledge.model;
@lombok.Data @lombok.EqualsAndHashCode(callSuper=true)
@com.baomidou.mybatisplus.annotation.TableName("iqc_knowledge_base")
public class KnowledgeBase extends io.github.opensabre.persistence.entity.po.BasePo {
    private String code;
    private String name;
    private String draftConfigJson;
    private Integer draftRevision;
    private Integer activePublishedVersion;
    private String status;
    /** Null explicitly narrows a previously shared corpus to its owner. */
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy=com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String ownerGroupId;
}

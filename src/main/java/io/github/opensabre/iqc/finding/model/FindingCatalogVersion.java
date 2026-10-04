package io.github.opensabre.iqc.finding.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iqc_finding_catalog_version")
public class FindingCatalogVersion extends BasePo {
    private String catalogId;
    private Integer versionNo;
    private Integer sourceDraftRevision;
    private String snapshotJson;
    private String contentHash;
}

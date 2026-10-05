package io.github.opensabre.iqc.selector.model;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper=true) @TableName("iqc_selector_asset_version")
public class SelectorAssetVersion extends BasePo {
    private String selectorId;
    private Integer versionNo;
    private Integer sourceDraftRevision;
    private String snapshotJson;
    private String contentHash;
}

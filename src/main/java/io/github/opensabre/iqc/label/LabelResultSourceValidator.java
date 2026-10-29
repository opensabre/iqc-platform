package io.github.opensabre.iqc.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.model.InspectionLabelResult;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.util.Objects;

/** Shared storage shape check. Actual production ownership and persisted trace verification remain separate. */
public final class LabelResultSourceValidator {
    public static final String FINDING="PIPELINE_FINDING";
    private LabelResultSourceValidator(){ }
    public static void validate(InspectionLabelResult row,ObjectMapper json) {
        Objects.requireNonNull(row);Objects.requireNonNull(json);
        if(!FINDING.equals(row.getGenerationSource())) {
            if(row.getSourceFindingCode()!=null || row.getSourceActionExecutionId()!=null
                    || row.getSourceInputHash()!=null || row.getSourceProjectionHash()!=null)
                throw IqcException.invalidState("旧标签结果不能混入Finding来源字段");
            return;
        }
        if(row.getSourceRuleResultId()!=null || row.getSourceFindingCode()==null
                || !row.getSourceFindingCode().matches("[A-Z][A-Z0-9_]{1,63}")
                || row.getSourceActionExecutionId()==null || !row.getSourceActionExecutionId().matches("[a-f0-9]{64}")
                || row.getSourceInputHash()==null || !row.getSourceInputHash().matches("[a-f0-9]{64}")
                || row.getSourceProjectionHash()==null || !row.getSourceProjectionHash().matches("[a-f0-9]{64}")
                || row.getValueJson()==null || !Objects.equals(row.getSourceProjectionHash(),SelectorAssetService.hash(row.getValueJson())))
            throw IqcException.invalidState("Finding标签来源或投影摘要无效");
        try {
            var projection=FindingLabelProjection.read(json,row.getValueJson());
            if(projection==null)throw IqcException.invalidState("Finding标签投影不能为空");
            var binding=projection.binding();
            if(!Objects.equals(row.getLabelId(),binding.labelId()) || !Objects.equals(row.getLabelVersionNo(),binding.labelVersion())
                    || !Objects.equals(row.getValueCode(),binding.valueCode()) || !Objects.equals(row.getSourceFindingCode(),binding.findingCode()))
                throw IqcException.invalidState("Finding标签投影与存储身份不一致");
        } catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException failure) {
            throw IqcException.invalidState("Finding标签投影协议损坏");
        }
    }
}

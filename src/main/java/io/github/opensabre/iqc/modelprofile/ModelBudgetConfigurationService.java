package io.github.opensabre.iqc.modelprofile;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.modelprofile.dao.IqcModelProfileMapper;
import io.github.opensabre.iqc.modelprofile.model.IqcModelProfile;
import io.github.opensabre.iqc.result.llm.gateway.GatewayCallService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Uses the persisted model version and existing physical-model ledger, without model I/O. */
@Service @RequiredArgsConstructor
public class ModelBudgetConfigurationService {
    private final IqcModelProfileMapper profiles;
    private final GatewayCallService calls;
    private final io.github.opensabre.iqc.shared.IqcDataScope scope;
    public record Command(JsonNode modelVersion,String capacityVersion,JsonNode concurrencyLimit,JsonNode budget) { }
    public record Change(String modelProfileId,int modelVersion,GatewayCallService.CapacityChange capacity) { }
    @Transactional
    public Change configure(String id,Command command){
        if(!scope.isIqcAdministrator())throw IqcException.accessDenied("仅质检管理员可配置模型预算");
        if(id==null || id.length()>64 || command==null || command.modelVersion()==null || !command.modelVersion().isIntegralNumber()
                || !command.modelVersion().canConvertToInt() || command.modelVersion().intValue()<1
                || command.capacityVersion()==null || !command.capacityVersion().matches("0|[1-9][0-9]{0,18}")
                || command.concurrencyLimit()==null || !command.concurrencyLimit().isIntegralNumber() || !command.concurrencyLimit().canConvertToInt())
            throw IqcException.invalidArgument("需要有效模型版本、容量版本及并发上限");
        long capacityVersion;
        try{capacityVersion=Long.parseLong(command.capacityVersion());}catch(NumberFormatException invalid){throw IqcException.invalidArgument("容量版本超出范围");}
        var profile=profiles.selectOne(Wrappers.<IqcModelProfile>lambdaQuery().eq(IqcModelProfile::getId,id).last("FOR UPDATE"));
        if(profile==null)throw IqcException.notFound("模型配置不存在");
        if(!"ENABLED".equals(profile.getStatus()) || !java.util.Objects.equals(profile.getVersionNo(),command.modelVersion().intValue()))
            throw IqcException.invalidState("模型版本已变化或已停用，请刷新");
        var identity=identity(profile);
        return new Change(id,profile.getVersionNo(),calls.initializeBudget(identity,command.concurrencyLimit().intValue(),capacityVersion,command.budget()));
    }
    public record Snapshot(String modelProfileId,int modelVersion,String modelKey,Integer contextWindowTokens,String deploymentRef,
            Integer maxOutputTokens,String outputTokenParameter,String pricingHash,String pricingCurrency,GatewayCallService.CapacityView capacity) { }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Snapshot read(String id){
        if(!scope.isIqcAdministrator())throw IqcException.accessDenied("仅质检管理员可查看模型预算");
        if(id==null || id.length()>64)throw IqcException.invalidArgument("模型编号无效");
        var profile=profiles.selectById(id);
        if(profile==null)throw IqcException.notFound("模型配置不存在");
        if(!"ENABLED".equals(profile.getStatus()) || profile.getVersionNo()==null || profile.getVersionNo()<1)
            throw IqcException.invalidState("模型配置已停用或版本无效");
        var model=identity(profile);var price=ModelPricing.parse(profile.getPricingJson());
        return new Snapshot(id,profile.getVersionNo(),model.key(),profile.getContextWindowTokens(),profile.getDeploymentRef(),profile.getMaxOutputTokens(),
            profile.getOutputTokenParameter(),model.price().pricingJson()==null?null:io.github.opensabre.iqc.selector.SelectorAssetService.hash(model.price().pricingJson()),
            price==null?null:price.currency(),calls.findCapacity(model.key()));
    }
    private static GatewayCallService.ModelIdentity identity(IqcModelProfile profile){
        return new GatewayCallService.ModelIdentity(profile.getProvider(),profile.getEndpoint(),profile.getModelName(),
                new GatewayCallService.PriceBinding(profile.getId(),profile.getVersionNo(),profile.getPricingJson(),true),
                profile.getMaxOutputTokens()==null?null:new GatewayCallService.OutputConstraint(profile.getMaxOutputTokens(),profile.getOutputTokenParameter(),true),
                new GatewayCallService.DeploymentDeclaration(profile.getContextWindowTokens(),profile.getDeploymentRef()));
    }

}

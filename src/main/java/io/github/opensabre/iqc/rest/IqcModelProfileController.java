package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.modelprofile.IqcModelProfileService;
import io.github.opensabre.iqc.modelprofile.IqcModelProfileService.ModelCommand;
import io.github.opensabre.iqc.modelprofile.ModelConnectionTestService;
import io.github.opensabre.iqc.modelprofile.ModelConnectionTestService.ConnectionTestResult;
import io.github.opensabre.iqc.modelprofile.model.IqcModelProfile;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Management API for reusable model profiles. */
@RestController
@RequestMapping("/api/iqc/agent-assets/models")
public class IqcModelProfileController {
    private final IqcModelProfileService service;
    private final ModelConnectionTestService connectionTestService;
    private final io.github.opensabre.iqc.modelprofile.ModelBudgetConfigurationService budgets;
    public IqcModelProfileController(IqcModelProfileService service, ModelConnectionTestService connectionTestService) { this(service,connectionTestService,null); }
    @org.springframework.beans.factory.annotation.Autowired
    public IqcModelProfileController(IqcModelProfileService service,ModelConnectionTestService connectionTestService,io.github.opensabre.iqc.modelprofile.ModelBudgetConfigurationService budgets){this.service=service;this.connectionTestService=connectionTestService;this.budgets=budgets;}
    @GetMapping @ResourcePermission(code="iqc:model:view", name="查看模型配置", type="iqc", description="查询 Agent 模型配置")
    public List<ModelView> list() { return service.list().stream().map(ModelView::from).toList(); }
    @PostMapping @ResourcePermission(code="iqc:model:manage", name="管理模型配置", type="iqc", description="创建 Agent 模型配置") @Audit(operationType=OperationType.CREATE, description="创建 IQC 模型配置", module="IQC_MODEL",request=false,response=false)
    public ModelView create(@RequestBody ModelCommand command) { return ModelView.from(service.create(command)); }
    @PutMapping("/{id}") @ResourcePermission(code="iqc:model:update", name="管理模型配置", type="iqc", description="修改 Agent 模型配置") @Audit(operationType=OperationType.UPDATE, description="修改 IQC 模型配置", module="IQC_MODEL",request=false,response=false,key="#id")
    public ModelView update(@PathVariable String id, @RequestBody ModelCommand command) { return ModelView.from(service.update(id, command)); }
    @PostMapping("/{id}/enable") @ResourcePermission(code="iqc:model:enable", name="管理模型配置", type="iqc", description="启用 Agent 模型配置") @Audit(operationType=OperationType.UPDATE, description="启用 IQC 模型配置", module="IQC_MODEL",request=false,response=false,key="#id")
    public ModelView enable(@PathVariable String id) { return ModelView.from(service.setEnabled(id, true)); }
    @PostMapping("/{id}/disable") @ResourcePermission(code="iqc:model:disable", name="管理模型配置", type="iqc", description="停用 Agent 模型配置") @Audit(operationType=OperationType.UPDATE, description="停用 IQC 模型配置", module="IQC_MODEL",request=false,response=false,key="#id")
    public ModelView disable(@PathVariable String id) { return ModelView.from(service.setEnabled(id, false)); }
    @PostMapping("/{id}/test") @ResourcePermission(code="iqc:model:test", name="测试模型连接", type="iqc", description="测试 Agent 模型配置连通性")
    public ConnectionTestResult test(@PathVariable String id) { return connectionTestService.test(id); }

    @GetMapping("/{id}/budget")
    @ResourcePermission(code="iqc:model:budget:view",name="查看模型预算配置",type="iqc",description="查看保存模型版本及首次调用前预算账本状态")
    public io.github.opensabre.iqc.modelprofile.ModelBudgetConfigurationService.Snapshot budget(@PathVariable String id){return budgets.read(id);}

    @PostMapping("/{id}/budget")
    @ResourcePermission(code="iqc:model:budget:configure",name="配置模型预算",type="iqc",description="按已保存模型版本在首次调用前配置预算")
    @Audit(operationType=OperationType.UPDATE,description="按模型版本配置预算",module="IQC_MODEL_GATEWAY",request=false,response=true)
    public io.github.opensabre.iqc.modelprofile.ModelBudgetConfigurationService.Change budget(@PathVariable String id,
            @RequestBody io.github.opensabre.iqc.modelprofile.ModelBudgetConfigurationService.Command command){return budgets.configure(id,command);}

    public record ModelView(String id, String name, String code, String description, String provider,
                            String modelName, String endpoint, Double temperature, Integer timeoutSeconds,
                            Integer maxRetries, String status, Integer versionNo, boolean secretConfigured,String pricingJson,Integer maxOutputTokens,String outputTokenParameter,Integer contextWindowTokens,String deploymentRef) {
        static ModelView from(IqcModelProfile value) { return new ModelView(value.getId(), value.getName(), value.getCode(), value.getDescription(), value.getProvider(), value.getModelName(), value.getEndpoint(), value.getTemperature(), value.getTimeoutSeconds(), value.getMaxRetries(), value.getStatus(), value.getVersionNo(), value.getSecretRef() != null && !value.getSecretRef().isBlank(),value.getPricingJson(),value.getMaxOutputTokens(),value.getOutputTokenParameter(),value.getContextWindowTokens(),value.getDeploymentRef()); }
    }
}

package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.iqc.result.llm.gateway.GatewayCallService;
import io.github.opensabre.iqc.shared.IqcPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/iqc/model-gateway")
public class ModelGatewayController {
    private final GatewayCallService calls;
    @GetMapping("/calls")
    @ResourcePermission(code="iqc:model-gateway:calls:view",name="查看模型调用事实",type="iqc",description="质检管理员查看模型并发预留和真实用量")
    public IqcPage<GatewayCallService.CallView> calls(@RequestParam(required=false) String modelKey,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="1") long current,@RequestParam(defaultValue="20") long size){return calls.page(modelKey,status,current,size);}
}

package io.github.opensabre.iqc.integration.iac;

import io.github.opensabre.common.core.entity.vo.Result;
import io.github.opensabre.iqc.agent.AgentConfiguration;
import io.github.opensabre.iqc.agent.model.QualityAgent;
import io.github.opensabre.iqc.agent.model.QualityAgentVersion;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/** Uses OpenSabre's discovery and signed internal-token RPC transport. */
@FeignClient(name = "${iqc.iac.service-id:iac-platform}", contextId = "iqcIacConsumer")
public interface IacConsumerClient {
    @GetMapping("/internal/iac/agents/{id}")
    Result<QualityAgent> agent(@PathVariable("id") String id);

    @GetMapping("/internal/iac/agents/{id}/versions/{versionNo}")
    Result<QualityAgentVersion> version(@PathVariable("id") String id, @PathVariable("versionNo") int versionNo);

    @PostMapping("/internal/iac/assets/validate")
    Result<Boolean> validate(@RequestBody AgentConfiguration configuration);

    @PostMapping("/internal/iac/assets/snapshot")
    Result<AgentConfiguration.AssetSnapshots> snapshot(@RequestBody AgentConfiguration configuration);
    @PostMapping("/internal/iac/model/invoke")
    Result<ModelReply> invoke(@RequestBody ModelRequest request);

    record Turn(String role, String content) { }
    record ModelRequest(com.fasterxml.jackson.databind.JsonNode snapshot, java.util.List<Turn> messages, boolean toolsEnabled) { }
    record ModelReply(String content, String model, Integer inputTokens, Integer outputTokens) { }
}

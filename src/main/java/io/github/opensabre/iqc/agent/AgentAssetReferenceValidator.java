package io.github.opensabre.iqc.agent;

import io.github.opensabre.iqc.integration.iac.IacConsumerClient;
import io.github.opensabre.iqc.integration.iac.IacResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Business planning resolves live capability availability through its IAC owner. */
@Component
@RequiredArgsConstructor
public class AgentAssetReferenceValidator {
    private final IacConsumerClient client;

    public void validate(AgentConfiguration configuration) {
        if (!IacResponses.required(client.validate(configuration)))
            throw io.github.opensabre.iqc.governance.IqcException.invalidState("智能体资产不可用");
    }

    public AgentConfiguration.AssetSnapshots snapshot(AgentConfiguration configuration) {
        return IacResponses.required(client.snapshot(configuration));
    }
}

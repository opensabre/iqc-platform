package io.github.opensabre.iqc.integration.iac;

import io.github.opensabre.iqc.agent.model.QualityAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Reads the single canonical agent owner; never falls back to IQC's historical tables. */
@Component
@RequiredArgsConstructor
public class IacAgentCatalog {
    private final IacConsumerClient client;

    public QualityAgent findById(String id) {
        return IacResponses.optional(client.agent(id));
    }
}

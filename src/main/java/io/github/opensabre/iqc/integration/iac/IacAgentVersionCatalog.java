package io.github.opensabre.iqc.integration.iac;

import io.github.opensabre.iqc.agent.model.QualityAgentVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Resolves a version-pinned publication without treating the mutable current body as published. */
@Component
@RequiredArgsConstructor
public class IacAgentVersionCatalog {
    private final IacConsumerClient client;

    public QualityAgentVersion findPublishedVersion(String id, int versionNo) {
        var version = IacResponses.optional(client.version(id, versionNo));
        return version != null && "PUBLISHED".equals(version.getStatus()) ? version : null;
    }
}

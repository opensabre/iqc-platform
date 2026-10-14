package io.github.opensabre.iqc.action;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned immutable exact-version endpoints; policy payloads never configure connection secrets. */
@ConfigurationProperties("iqc.actions.external")
public record ExternalActionProperties(List<ExternalActionDestinations.Destination> destinations) {
    public ExternalActionProperties {
        destinations = destinations == null ? List.of() : List.copyOf(destinations);
        new ExternalActionDestinations(destinations); // Reject duplicate versions at binding time.
    }
}

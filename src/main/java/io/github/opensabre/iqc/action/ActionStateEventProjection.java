package io.github.opensabre.iqc.action;

import lombok.Data;
import java.time.LocalDateTime;

/** Database-only whitelist projection; never selects claim tokens or frozen content. */
@Data
public class ActionStateEventProjection {
    private Long version;
    private Long previousVersion;
    private String stateJson;
    private String previousStateJson;
    private String actor;
    private String reconciliationReason;
    private LocalDateTime recordedAtUtc;
}

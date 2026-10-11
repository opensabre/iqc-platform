package io.github.opensabre.iqc.action;

import lombok.Data;
import java.time.LocalDateTime;

/** Explicit public observation metadata; stable identity and frozen execution content stay internal. */
@Data
public class ActionReconciliationAttemptProjection {
    private String id;
    private Long observedVersion;
    private String actor;
    private String reason;
    private String state;
    private LocalDateTime startedAtUtc;
    private LocalDateTime finishedAtUtc;
    private Long confirmedVersion;
}

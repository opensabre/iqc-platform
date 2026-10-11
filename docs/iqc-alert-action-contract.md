# SEND_ALERT owning-service contract

Status: contract and evidence validation implemented; the executable action adapter is not yet registered.

Reuse `base-sysadmin` notification scenes, templates, channels and `NotificationRecord`. IQC owns no notification table or delivery provider. The internal client sends with POST and reconciles using GET at `/internal/iqc/notifications/{requestId}`. The request identity is the existing action's 64-character lowercase SHA-256 idempotency key.

Configuration freezes `sceneCode`, explicit `SMS` or `EMAIL`, `target` and bounded textual `args`. It rejects automatic channel selection, arbitrary callbacks, additional fields and non-text parameters. Actual rendered template content is frozen by the notification service at its first durable reservation; IQC task publication does not freeze that content.

`notification-declared-request-v1` independently binds the original declaration. Encode schema, scene, channel and target as UTF-8, each preceded by a signed big-endian int32 byte length. Then encode the int32 argument count and keys/values in Java natural string order, using the same length framing. SHA-256 yields lowercase hexadecimal. IQC configuration rejects null argument values; the owning protocol additionally supports null framing with length -1 for legacy records.

Accept success only when status schema is `iqc-notification-status-v1`, request identity and independently calculated declared hash match, record ID is 32 lowercase hexadecimal characters, final rendered-content request hash is 64 lowercase hexadecimal characters, status is `SUCCESS`, and a bounded nonblank channel receipt is present. Retain the owning record ID as action evidence/result reference. The final hash is an owning-service assertion; IQC cannot recompute it without template content. Missing or contradictory evidence returns `UNKNOWN`, never a confirmed failure or permission to resend.

Remaining integration gates: registered send resource and grants; fresh original actor/source/frozen-policy and claimed-attempt validation; short preparation transaction committed before RPC; read-only historical reconciliation; actual MySQL notification migration validation; signed cross-service integration and action execution tests. Existing action claim/settlement and historical-source validators must be reused. No remote call may run inside the preparation or analysis transaction.

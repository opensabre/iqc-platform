# Task processing configuration

The user's current scope supersedes the older platform expansion: select existing processed conversations, select inspection and/or labeling, configure an ordered engine list separately for each selected operation, execute, and process the normalized results into scores, labels, and subsequent actions. Knowledge-base, risk-center and fact-catalog navigation are outside this delivery. Audio conversion and executable small models are excluded.

`TaskProcessingConfiguration` defines the versioned task-owned input contract. A null branch is unselected. At least one branch is required. Each selected branch has 1–32 engines, whose array order is the execution order. Engine IDs are unique within a branch, not across branches. The same engine type may appear multiple times with different IDs and parameters. Supported engine types are REGEX, DLS and LLM. Parameter trees and engine lists cannot be mutated through callers after construction.

This first change deliberately does not expose an endpoint that accepts configuration which the worker cannot yet execute. Engine-specific parameter validation, permission checks and dependency freezing must be implemented before connecting this contract to task creation. No request-supplied rule/model snapshots may be trusted as frozen assets.

## Integration work remaining

- Extend the existing task creation API with independent inspection/labeling configuration; keep old requests compatible. Reject mixed legacy and new configuration rather than guess which wins.
- Reuse existing rule validation, model routing, pipeline runtime, scoring, label projection, task queue and conversation selection. Translate each operation's engine list to ordered runtime nodes. Do not require users to create or publish a separate pipeline or fact-catalog item.
- Persist a versioned task configuration using the existing snapshot capability where compatible. Preserve old task snapshots and their hashes. Verify privacy projection and configuration-read permissions for new snapshots.
- Keep operation identity on all traces and results. Define failure/retry behavior before combining the operation runs, so scores or labels are not duplicated or mixed. Engine order alone must not introduce hit-based short circuiting or output precedence.
- Add the task form's independent engine sections and ordering controls; show only selected operations in task details/results.
- Remove out-of-scope navigation without deleting historical data.

Acceptance: inspection only, labeling only, both with different parameters and different engine order; immutable creation-time configuration; per-operation result attribution; meaningful failure/retry checks; legacy task readability. The user confirmed that an engine error must be recorded and subsequent engines must continue. Cross-operation scheduling and conflicting-output handling remain unresolved product choices, not implied by engine ordering.

`TaskProcessingPipelineCompiler` reuses the sequential runtime and forces CONTINUE for engine failures. It deliberately does not turn ordering into success dependencies or hit conditions; either would skip later engines. Cancellation still uses the existing runtime cancellation behavior. The resolver boundary must supply server-validated, frozen nodes before execution integration is enabled.

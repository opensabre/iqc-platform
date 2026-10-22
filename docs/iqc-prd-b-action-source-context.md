# Action source business and label context

ActionProductionSourceValidator previously assembled ActionPolicy.Context with empty labels and null businessContext after validating frozen inputs and facts. This made valid BUSINESS_EQUALS and LABEL_EQUALS conditions return UNKNOWN, preventing eligible actions.

The source validator now reuses PipelineDecisionEvaluator.inputContext with reproduced findings, the already computed item decisions and Prepared.businessContext. It carries that existing context's findings/items/label projections/business values into the final Action context alongside validated score, risk, result and review state. No live conversation, directory or new persistence model is queried. Existing source ownership, frozen hash, fact/scoring/risk/review reproduction and evidence checks stay in place.

Original session8777 terminated exit1:3tests2failures, `/tmp/iqc-b-action-source-context-before.log`. Actual source replay returned UNKNOWN instead of MATCH for both a frozen exact business value and a known label. Unknown-label and altered-input checks passed already. This isolates missing context assembly rather than a provider or database failure.

Final session2873 terminated exit0 with full main/test compilation, `/tmp/iqc-b-action-source-context-terminal.log`. Seven fresh reports70tests,zero failures/errors/skips: ActionSourceContext3,PolicySafety10,EnqueueIdempotency28,ExactPolicyReplay2,ExactExecutionInput3,ExecutionReceipt10,Reconciliation14.

The new test uses real ProductionInputSnapshotService, PipelineConversationEvaluator and ActionProductionSourceValidator. Detector runtime, asset publication lookup and persistence mappers are mocks; the fixture is an explicit v2 source with a frozen boolean label directory. Tests prove exact adjacent business values MATCH/NO_MATCH, absent fields UNKNOWN, known label MATCH with original selected Finding id and evidence reference, unknown label UNKNOWN, and modified frozen input rejection. They do not prove SQL transactions, actual publication validation, worker/adapters or complete enqueue/dispatch on a v2 source. Existing regression counts do not imply those gates passed.

Still pending: saved-result and label readiness/source projection numeric reads, scheduling/event audit, full persisted v2 publish/materialize/worker/review/revision/Action checks and integration with A CUSTOM/privacy changes. Public default remains v1 pending those gates. Full applicable PRD remains active; inputs are processed conversations and speech conversion is excluded.

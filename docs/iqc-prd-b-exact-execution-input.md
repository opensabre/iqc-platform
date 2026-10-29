# Exact pipeline execution input identity

Baseline: staged B v2 release protocol 0e76650. Inputs are processed conversations. No speech conversion or provider call is added.

The actual runtime test `ExactPipelineExecutionInputTest` executes a deterministic RULE adapter through the real bounded PipelineRuntime and PipelineConversationEvaluator, then verifies the result with ProductionInputSnapshotService and ReviewSampleInputValidator. It uses real serialized frozen inputs and original business context; the task heartbeat Mapper is a mock. This is not a SQL transaction, HTTP, real detector/provider or complete candidate/Gold persistence test.

Initial session 32961 failed with two fixture errors: a RULE with no finding output also lacked its frozen finding definition. This was corrected by supplying the definition and deterministic known fact. No production behavior was changed to accommodate that fixture.

Corrected pre-fix session 48149 terminated exit 1 (`/tmp/iqc-b-exact-input-reproduced.log`): two tests, one failure, no errors/skips. Actual v2 executions for business context values 0.900000000000000001 and 0.900000000000000002 both produced 7ff4aca1ce345a157a072228be9e2bacabbe101bc8eecc4cea84e0bb3a564416. Each result passed the old input and candidate validators, confirming the gap spans generation and validation. The legacy-contract case passed.

The repair selects input identity from the frozen release schema: v2 reuses SelectorAssetService.hash; v1 retains the original pipeline hash. Unknown schemas reject. PipelineConversationEvaluator owns the shared dispatch function; execution generation, frozen result verification and candidate input verification all use it.

ProductionInputSnapshotService.Prepared stores the schema from the persisted original task decoded during prepare. Result verification uses this captured value, not a caller-supplied snapshot's replacement schema. A regression supplies a forged v1 caller tree and legacy digest for an original v2 task: both result verification and candidate input validation must reject. The original task and prepared-input ownership checks remain in place.

Still open before default activation: candidate snapshot's own lineage/Gold hash protocol, Action policy copies/adapters and reconciliation, evaluation/Benchmark, exact provider input/fact source identities, and real persisted publish/task/worker/review/revision workflows. The public default release protocol remains unchanged.

Final session 32585 terminated exit 0 (`/tmp/iqc-b-exact-input-terminal.log`), full Maven test lifecycle recompiled main sources. Four fresh reports, 26 tests: ExactPipelineExecutionInput3, OfflineProductionSnapshot7, ReviewSampleInputValidator11, PipelineNumericPersistenceContract5; zero failures/errors/skips. The authoritative counts are taken from the XML reports. No default protocol activation or SQL/publish/provider claim is made.

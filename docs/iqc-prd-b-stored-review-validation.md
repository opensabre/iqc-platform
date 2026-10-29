# Business-item review stored trace replay

Baseline: B backend 0440854. Inputs are processed conversations; no speech conversion.

`StoredPipelineReviewReplayTest` invokes the public `BusinessItemReviewEvaluator.evaluatePipeline` after direct Jackson2 serialization/deserialization of the complete machine trace. It covers PRIORITY and calibrated CONFIDENCE, new and legacy (absent finalConfidence) traces, and KNOWN/UNKNOWN/NOT_EVALUATED facts. Human PASS is a separate projection; serialized machine trace must remain byte-identical. A changed canonical fusion value must be rejected.

The fixture assembles a frozen RULE node, raw fact, real fusion, item mapping and real scoring. It does not call a provider, database, worker or HTTP endpoint. It proves the review evaluator boundary, not actual published source authorization, transaction persistence or complete human review UI.

Initial session 20656 passed the three PRIORITY state cases. Expanded session 97241 ended exit 1: 20 tests, zero assertion failures and one score-equality error. Its fixture converted the whole trace through valueToTree to remove the optional field, which changed decimal representation. The actual HierarchicalResultService stores `write(evaluated)` directly. The corrected fixture uses direct serialization and removes only the optional finalConfidence field from that original text; no production comparison or mapper was changed. Final result is recorded in the collaboration ledger after terminal verification.

Final session 69530 terminated exit 0 (`/tmp/iqc-b-stored-review-replay-terminal.log`): three fresh reports, 20 tests, zero failures/errors/skips (StoredPipelineReviewReplayTest 1 test covering 12 replay combinations plus 4 tamper checks, BusinessItemReviewEvaluatorTest 10, FinalConfidenceContractTest 9). The earlier failure is retained as fixture evidence, not a production defect. No production changes were needed for these cases.

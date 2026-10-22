# Pipeline numeric persistence defects: confirmed, unresolved

Baseline: B 8184c5f (includes A's HTTP bridge as bff9250). No speech conversion is in scope.

`PipelineNumericPersistenceContractTest` invokes PipelineAssetService's actual private `read(String)` storage decoder via ReflectionTestUtils and its public `hash(String)`. The fixture is a complete PipelineRelease; this is not a database or publication-service test. It isolates the exact production parsing and identity functions without mocking either function.

Session 75226 terminated exit 1, `/tmp/iqc-b-pipeline-numeric-persistence-before.log`. Fresh XML: 3 tests, 2 failures, 0 errors, 0 skips:

- Stored config decimal `0.900000000000000001` becomes `0.9` at the release read boundary. The first config assertion fails, so this run alone does not claim the subsequent condition/schema assertions executed.
- Releases differing only by `0.900000000000000001` versus `0.900000000000000002` share digest `45036af4f17c3596d2dbbb5b93b49c690481a25156bd146be6f96ba977687c54`. This is normalization before SHA-256, not a cryptographic collision.
- Existing legacy behavior remains field-order/whitespace insensitive but distinguishes numeric `1` from `1.0`.

Source: PipelineAssetService `read -> parse -> json.readTree`, while hash builds a fresh default ObjectMapper, reads floats as binary floating point, sorts object keys and hashes the serialized tree. Published release resolution and frozen draft resolution call this read path. The HTTP fix preserves incoming values but does not change these paths.

These regressions intentionally remain red in the independent validation branch. They must not be interpreted as accepted behavior or a green full suite. B's previously validated main worktree remains at 8184c5f until a reviewed coherent repair passes.

Required repair constraints:

1. Preserve exact decimal/integer values from creation through frozen draft, publication, task materialization, execution and policy-only recalculation. Test actual public workflows after repairing the functions.
2. Give new exact releases and their source identity a recognizable versioned numeric/hash protocol. Choose semantics for decimal scale explicitly. Do not silently redefine every historical `hash(String)` caller; the function is shared across many frozen evidence types.
3. Preserve historical releases/tasks, including 1.0 formatting and legacy hashes. Resolve the protocol from frozen trusted release metadata, not current configuration; no fallback to a second hash after tampering or a failed new-protocol check.
4. Ensure envelopes retain the exact tree before digest validation. A lossless hash function cannot recover values already rounded by task or publication JSON parsing.
5. Reject unknown protocols; verify neighboring decimals cannot bypass published-source/task evidence checks. Include original historical fixtures and the current gray/worker/decision-revision validations.

No production fix, migration, shared SQL, framework change, push or deployment was performed in this diagnostic batch. The two red assertions describe desired precision; the eventual versioned API may require routing the identity assertion through the explicit new protocol while retaining the legacy guard unchanged.

## Subsequent staged repair

A later B core batch introduces explicit release v2 and reruns the precision requirements against that protocol, retaining explicit v1 behavior and the original failure evidence. See `iqc-pipeline-numeric-protocol-v2.md`. The core batch is green for its 27 selected tests; default v1 creation has not been upgraded, and full persisted workflows remain open. The earlier paragraph describing red branch state applies to diagnostic commit a4ed4c8, not subsequent staged commits.

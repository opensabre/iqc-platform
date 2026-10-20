# Pipeline exact-number protocol v2 — staged integration

Status: implementation in progress; not the default creation protocol. v1 persistence defects remain for new v1 inputs until activation is complete. Inputs are processed conversations, without speech conversion.

The release schema itself identifies the protocol: `iqc-pipeline-release-v1` keeps historical parsing/digests; `iqc-pipeline-release-v2` uses exact decimal trees and the existing `SelectorAssetService.hash` canonicalizer, already reused by StructuredToolInputHash.EXACT. There is no parallel canonicalizer or global mapper change. New-protocol numbers compare by decimal value (trailing zero scale is not identity); legacy 1 versus 1.0 remains distinct. Object keys are sorted and arrays retain order. The schema is part of the hashed body; no extra unversioned digest fallback is introduced.

`PipelineAssetService.readFrozenTree` recognizes only root Release, Prepared.release and Task.pipelineSnapshot.release. It first reads an exact probe, then re-reads legacy payloads using the historical mapper. A nested config claiming a release schema cannot opt the surrounding snapshot into v2. Unknown release schemas are rejected by the release constructor. Hash validation uses the frozen release protocol and does not retry a legacy digest on failure.

This batch wires the owning asset service's persisted release reads, worker task reads, decision-revision source/trace reads, business-item review task/trace reads and action production-source task/trace validation. It reuses the original pipeline, tables, controller and evidence structures. No data migration or framework changes are needed for a separate new release protocol.

Activation requirements still open:

- Public create/update and UI defaults must issue v2 only after every supported path is safe. This batch deliberately preserves their current default, so it does not claim the original default workflow precision defect is fixed.
- Audit Action policy-only copies/adapters/reconciliation, evaluation/Benchmark snapshots, queue/periodic/event rereads and sensitive projections; a preserved hash cannot recover a tree already rounded by another parser.
- Verify actual public draft freeze, publish, task materialization, worker execution, review and policy-only revision, including real isolated database transactions and old-version fixtures.
- Integrate A's CUSTOM extension commit and preserve its release validation; do not overwrite concurrent work.

`PipelineNumericPersistenceContractTest` retains the two original red requirements against the explicit new protocol, plus legacy behavior, known-envelope dispatch, arbitrary nested lookalike rejection and unknown-schema rejection. The old failure log remains the evidence that originally exposed the defect, not a claim that legacy v1 became lossless. Other batches' counts do not constitute full-suite coverage of this protocol.

Core verification: session 91722 terminated exit 0 (`/tmp/iqc-b-numeric-v2-core.log`), main sources recompiled using the full Maven test lifecycle. Five fresh reports, 27 tests, zero failures/errors/skips: numeric persistence5, complete HTTP3, stored review1, rollout policy9 and rollout revision9. These counts prove the covered boundaries only; they do not prove the newly wired worker/decision/review/action service paths on real v2 persisted tasks. The original precision assertions now run against explicit v2; a separate assertion confirms legacy v1 continues historical rounding. The v1 defect is therefore not misrepresented as fixed for default creation.

Further input-identity audit: PipelineConversationEvaluator and ProductionInputSnapshotService also hash execution input objects. Those objects do not themselves carry the release schema. Their protocol selection and frozen trace/source comparisons must be checked together; hashing a precise release alone does not make every input digest exact.

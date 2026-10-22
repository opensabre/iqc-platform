# Complete PipelineRelease default MVC request validation

Inputs are already processed conversations. No speech conversion is in scope.

`PipelineReleaseHttpContractTest` sends complete Create/Update bodies through the actual PipelineAssetController and default Spring MockMvc converters, with framework response/error advice. Services and grant lookup are mocks. Capturing the release at the service boundary proves decoding, not persistence, publication, authorization policy or worker execution.

The fixture contains distinct adjacent decimal values in Node.config, Condition.value, outputSchema.minimum, a 9007199254740993 integer, and a string NumericPredicate.threshold. Both endpoints must accept the body without rounding. A numeric threshold token must not reach the service.

Before repair: B aed2da1; session 97611 terminal exit 1, /tmp/iqc-b-pipeline-http-before.log. Three tests: two failures (Create/Update HTTP 500), one passing rejection. The root cause is the default Jackson3 converter attempting to construct abstract Jackson2 JsonNode, before the service is called. Thus the baseline rejection alone does not prove the threshold guard.

Repair imported from A 2bab957 as B bff9250. It adds local field bridges and strict decimal-string guards; no global mapper/framework/hash changes. The sole cherry-pick conflict was append-only iteration history; both histories were retained. A's privacy and CUSTOM work are not part of this B combination. Final evidence is recorded after terminal verification.

First repaired run: session 56033 terminal exit 0 (`/tmp/iqc-b-pipeline-http-after.log`), 13 tests across HTTP3, stored-review1 and confidence9. Main sources were recompiled through the full `test` lifecycle. Default HTTP parsing accepted exact configuration/condition/schema values; the invalid numeric threshold emitted `Numeric threshold must be a decimal JSON string`.

The final fixture additionally includes Action.configuration and Action.condition.value in the same complete Create/Update request, with distinct adjacent decimals. The rejection assertion now requires the exact deserializer error in the resolved exception, so unrelated decoding errors cannot count as a successful threshold rejection. It does not cover FindingLabelPolicy.directory, every advanced scoring/review field, or persistence/hash precision.

Final session 28941 terminal exit 0, `/tmp/iqc-b-pipeline-http-terminal.log`: three fresh reports, 13 tests, zero failures/errors/skips (HTTP3, stored-review1, confidence9). Both complete request endpoints preserve all five nested tree fields plus the large integer and exact string threshold; the numeric-token rejection reaches the intended guard and makes no service calls. No actual SQL, server deployment, live role/PDP, audit aspect or publish/runtime proof is claimed.

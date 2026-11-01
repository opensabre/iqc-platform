# Actual Prometheus registry exposition validation

The fixed-query engine fixture verifies PromQL semantics against controlled series. Three additional ordinary Maven tests now verify that the original metric producers actually export the names, units and histogram families required by those queries. They use the existing monitoring starter's real `PrometheusMeterRegistry`, not manually registered fake series.

- Original `PipelineRuntime` and `PipelineConversationEvaluator` callbacks export graph/node duration count and bucket families, including `+Inf`, production/application labels, observations and executor gauges. Private node/task/conversation identifiers are absent. Existing pipeline fixtures are reused.
- Original `ModelGateway` calls export invocation count/buckets, active gauge, usage, tokens, admission and settlement. A supplier failure exports the UNKNOWN outcome; supplier endpoint and exception text are absent. Admission, ledger and supplier behavior are controlled through the existing fixture.
- Original `DurableInspectionWorker` exports cached state/completion/unmeasured families and the `_seconds` sample-age name. Repeated scrape does not call the mapper; failed sampling exports health 0 and NaN counts, preserving unknown state. The worker remains strongly reachable during gauge observation.

Local Java 21 / explicit existing Byte Buddy agent / 512MiB test JVM: three tests, zero failures/errors/skips, Maven full test lifecycle exit 0. No SQL instance, scheduler, real provider, HTTP endpoint or deployment is involved. Original compile failed because the registry does not implement AutoCloseable; cleanup was corrected to explicit finally. The next run had two passing tests and one invalid Finding-code fixture error; that identifier was corrected without changing the export assertions. Both original failures are retained locally, alongside final reports, under `/private/tmp/iqc-b-completed-prometheus-evidence-20261101/`.

Command:

```sh
mvn -o -q -Dmaven.gitcommitid.skip=true -Dtest=PipelinePrometheusExpositionTest,ModelGatewayPrometheusExpositionTest,DurableTaskPrometheusExpositionTest test
```

Use the environment's configured test-agent JVM options as needed. This test command's locally recorded run supplied the installed Byte Buddy agent explicitly.

These are actual exporter-format checks, not proof that the candidate application is deployed or that Prometheus can scrape its authenticated HTTP endpoint. Real collection, roles/PDP, external services and million-scale validation remain open. The existing CI runs these ordinary `*Test` classes; PR #41 separately runs all 53 fixed-query assertions.

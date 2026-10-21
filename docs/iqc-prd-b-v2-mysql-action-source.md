# Release v2 persisted action source validation

On combined backend `dd0d700`, the existing `IsolatedMysqlActionSourceIT` now
adds a release v2 fixture with frozen business amount `0.900000000000000001`.
The test uses actual MySQL task/execution/result rows, MyBatis mappers and Spring
transactions, ProductionInputSnapshotService, PipelineConversationEvaluator and
ActionProductionSourceValidator. After database persistence and re-read, the
exact amount matches its Action condition, while the adjacent decimal does not.
Replacing the stored literal without changing its frozen digest is rejected.
The eleven existing source/discovery tests also pass with their legacy fixtures.

Terminal evidence: session 56639 exit 0, log
`/tmp/iqc-b-v2-source-mysql-validation.log`, fresh
`TEST-io.github.opensabre.iqc.result.IsolatedMysqlActionSourceIT.xml`: 12 tests,
zero failures, errors or skips. This run compiled tests against the previously
compiled combined production code; production code was not changed in this batch.

MySQL 8.0.34 used only `127.0.0.1:34307` and
`/private/tmp/iqc-mysql-validation.LuvVgU`. The test guard checked the actual port
and data directory. The new-database path applied 55 migrations through 1.1.74.
The script shut the instance down normally; its socket and PID file disappeared.
No shared database or deployment credentials were used. Initial sandboxed server
initialization crashed; the isolated initialization/run succeeded with escalation.

This is stronger than mapper-only source tests, but is not a complete production
flow: the task is inserted by the fixture, detector runtime and published asset
lookup are mocked. Actual asset publication, task materialization/worker,
review/recalculation and Action dispatch/reconciliation remain necessary. No
default v2 activation, schema change, speech conversion or deployment is included.

Developer A has since committed RuleSet/scheme changes through `2fca472`, with
further expert-scheme changes uncommitted when inspected. Those later changes are
not part of this tested combination and must be integrated separately.

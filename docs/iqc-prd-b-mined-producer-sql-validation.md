# Automatic candidate producer SQL validation

Validated the v2 producer with real Spring transaction proxies, source validation, MyBatis mappers and isolated MySQL 8.0. The fixture freezes adapterVersion 2 and mining configuration before evaluating the exact-schema release through the runtime; the LLM adapter is controlled and no external provider is called.

`IsolatedMysqlMinedCandidateActionIT`: 4 tests, zero failures/errors/skips, 32.399s. Original process 62816 exited 0; `/tmp/iqc-b-mined-producer-sql.log.exit` is 0. Flyway validated 76 migrations at 1.1.75. The local server used port 34307 and the existing dedicated temporary datadir/schema, then shut down; no shared database was accessed.

Verified: a committed conversation from a RUNNING task creates one UNCLASSIFIED/CANDIDATE/UNLABELLED sample from frozen text with UNMEASURED_CONFIDENCE lineage; repeated execution and read-only reconciliation return the same artifact; no Gold version is created. Missing current sample permission cannot create, empty reconciliation does not capture, cancelled source cannot create. An INSERT rejection trigger leaves zero samples and removing it permits one successful retry.

Scope limits: scheduled actors and provider output are controlled. This does not prove real PDP, browser/backend integration, discovery/claim, concurrent execution, 4MiB limits, or full PRD completion. Inputs are processed conversations; speech conversion is excluded.

## Discovery, dispatch and concurrent producer extension

Original process 48764 exited 0, `/tmp/iqc-b-mined-workflow-sql.log.exit=0`; fresh report: 6 tests, zero failures/errors/skips, 36.762s. The previous four tests were rerun alongside two new scenarios.

Real discovery SQL finds the committed result, real enqueue marks discovery complete and persists PENDING, then real claim/dispatch/settlement with both adapter versions registered creates one candidate and reaches COMPLETE (attempt 1, version 2). Re-enqueue preserves the same action, and dispatch cannot resend the completed action. Two concurrent independent producer transactions both return CONFIRMED_SUCCESS for the same artifact with one persisted candidate.

No production source changes. The local MySQL socket was absent after process completion. The earlier scope limitation concerning discovery/claim and concurrent producer creation is now covered by these scenarios; scheduler actor resolution, concurrent claim for this specific adapter, no-hit zero-ledger filtering, real PDP and full UI/backend acceptance remain unproven.

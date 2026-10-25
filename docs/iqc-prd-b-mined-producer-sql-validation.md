# Automatic candidate producer SQL validation

Validated the v2 producer with real Spring transaction proxies, source validation, MyBatis mappers and isolated MySQL 8.0. The fixture freezes adapterVersion 2 and mining configuration before evaluating the exact-schema release through the runtime; the LLM adapter is controlled and no external provider is called.

`IsolatedMysqlMinedCandidateActionIT`: 4 tests, zero failures/errors/skips, 32.399s. Original process 62816 exited 0; `/tmp/iqc-b-mined-producer-sql.log.exit` is 0. Flyway validated 76 migrations at 1.1.75. The local server used port 34307 and the existing dedicated temporary datadir/schema, then shut down; no shared database was accessed.

Verified: a committed conversation from a RUNNING task creates one UNCLASSIFIED/CANDIDATE/UNLABELLED sample from frozen text with UNMEASURED_CONFIDENCE lineage; repeated execution and read-only reconciliation return the same artifact; no Gold version is created. Missing current sample permission cannot create, empty reconciliation does not capture, cancelled source cannot create. An INSERT rejection trigger leaves zero samples and removing it permits one successful retry.

Scope limits: scheduled actors and provider output are controlled. This does not prove real PDP, browser/backend integration, discovery/claim, concurrent execution, 4MiB limits, or full PRD completion. Inputs are processed conversations; speech conversion is excluded.

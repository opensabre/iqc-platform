# Million-input materialization validation

## Result: incomplete, environment failure

The isolated MySQL 8.0.34 / Java 21 run at source `0149d32ff8ca9ee0bb670db944a8e415e62bcb90` terminated with Maven exit 1: one test, zero assertion failures, one error (1468 seconds). It did not complete the million-input materialization contract. This test requires the task snapshot storage migration from PR #35.

The actual fixture inserted 1,000,000 conversations and 1,000,000 processed messages in 330064ms. The original transactional checkpoint rollback assertion passed. The early committed crash occurred at 999 selected inputs / one child; the original worker recovery tick and reclaim assertions passed before a fresh materializer continued. A read-only stage sample observed 31,999 selected inputs / 32 children. These stages do not prove middle/tail recovery or final completeness.

The terminal cause was MySQL error 3 / OS errno 28, writing temporary file `MY2TkYYW`: `No space left on device`. The call path was `ConversationSelectionService.select:146` from `scanAllPageAt:124`. This was not another selection snapshot TEXT truncation. The dedicated MySQL server shut down normally at 2026-11-01 04:58:56 UTC. No rerun was started.

## Preserved evidence

The original log SHA-256 is `d9d59231597fe5dd5c73efb41a085bbc2d3cf67eafa3502041542287d2bb24a2`. JUnit XML SHA-256 is `43a9c6d4ce71731b6f003ded89889f396694593f2a872b9a17eb6fe1327572d5`. Original logs, XML, server shutdown log, stage samples and their manifest are archived locally under `/private/tmp/iqc-b-completed-mysql-evidence-20261031/xlctkR-*`; these are local evidence references, not portable CI artifacts.

Frozen test source hashes:

- `BulkRestartProcess.java`: `b983b53ffeb83eb1d0ad205360f1a37223b4dea610851dde5e59efb0f51f9efd`
- `IsolatedMysqlMillionMaterializationIT.java`: `20531a3c41908c378669daad2ccdb4eb1faa42f0267c357543f530646afe3715`

## Remaining validation

Preserve the failed database before diagnosis. Obtain the actual failing 1000-ID target lookup EXPLAIN and temporary-space evidence before changing query hints, permissions, ordering or limits. A bounded 1000-ID page does not by itself prove the optimizer avoided a large sort. Free space after temporary-file deletion is not proof of sufficient rerun capacity.

After sufficient storage is available, retain the full million conversations and messages, 1000-input page size, original publication/materialization/recovery paths and all early/middle/tail crash assertions. Run the final uniqueness, frozen-body and hash assertions. Stage RSS samples are not peak memory, P95 latency or an SLO result.

The test directly invokes original materialization service pages and uses original worker recovery ticks. It does not prove full million-task execution, two-worker mixed load, production PDP/provider behavior or complete PRD delivery. See [the capacity protocol](iqc-million-capacity-validation-protocol.md) for those separate gates.

## Preserved-database target lookup diagnosis

After the failed test and normal server shutdown, the retained dedicated database was started with `super_read_only` for diagnosis, then shut down again. No fixture seeding or materialization was repeated. The final checkpoint was 304,000 scanned / 303,999 selected, with 304 committed children and `scanComplete=false`; middle/tail crash and final completeness remain unproven.

The next ID page contains exactly 1,000 targets, `million0303999` through `million0304998`. The original error-log SQL was rebound with these IDs and the original owner/group/status scope. `EXPLAIN FORMAT=JSON` on the preserved database reports `PRIMARY` / `range`, 1,000 estimated rows examined per scan, and `using_filesort=true`. See [the full diagnostic artifact](validation/iqc-million-failed-target-plan-20261101.json), including the original SQL and target hashes, indexes and checkpoint.

This is a fresh EXPLAIN after restart, not a captured plan or temporary-file measurement at the time of failure. It provides no evidence of a current full-table target lookup or unbounded page. The actual errno 28 failure remains a capacity problem requiring sufficient disk and peak-space observation; no index hint, permission, ordering or limit change is justified by this evidence alone. At diagnosis, the retained data directory occupied 2,298,604KiB and free space was 1,130,045,440 bytes. These values are not peak-space requirements or proof that a rerun would fit.

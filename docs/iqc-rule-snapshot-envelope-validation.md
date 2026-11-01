# Task rule snapshot envelope capacity

Accepted processed conversation input can approach 16MiB. The original task service adds the published pipeline, rules and request fingerprint around that input. The complete task snapshot can exceed MEDIUMTEXT's 16,777,215-byte capacity even when the input is valid.

V1.1.79 expands only the existing `iqc_inspection_task.rule_snapshot_json` column to nullable LONGTEXT. It does not change the 16MiB input guard or historical migrations. Apply the new migration before relying on this capacity. The dependent selection/ID snapshot migration is PR #35.

## Actual isolated SQL validation

Java 21, MySQL 8.0.34, loopback port 34307; each run used a new dedicated data directory. Original measured publication, Gold/review and execution fixtures supply the real published release. Original `ProductionInputSnapshotService.capture` and transactional `InspectionTaskService.createUsingPipelineSnapshot` perform the freeze and INSERT. No synthetic published release or shared database is used.

- Before migration: one test, zero assertion failures, one error, 138.0s, Maven exit 1. Actual INSERT failed with `Data too long for column rule_snapshot_json`. Input capture and exact byte assertions passed before the failed INSERT.
- After migration: two tests, zero failures/errors/skips, 116.3s, full Maven test lifecycle exit 0. 512 individually valid TEXT messages produce 16,777,200 bytes of input and a 16,779,560-byte complete task snapshot. Full stored JSON equals the original returned snapshot and its production input; all 512 messages remain counted.
- Adding 32 bytes to the input still triggers the original 16MiB rejection, without creating an extra task. Larger storage does not relax accepted input size.
- Fresh schema path applies 60 migrations through 1.1.79. Separate 1.1.78 upgrade applies exactly one migration, validates 80 migrations, preserves historical Chinese JSON and NULL, confirms LONGTEXT, and a second migrate applies zero changes.

Original failure archive: `/private/tmp/iqc-b-completed-mysql-evidence-20261031/ybQ1xa-*`. Passing archive: `w2IGkT-*` in the same directory. Both include logs, JUnit reports, server shutdown evidence and SHA-256 manifests. These local paths are not CI artifact URLs.

This focused fix does not certify the separate million-input materialization run, which failed from disk exhaustion; see PR #38. No framework changes, merge or deployment were performed.

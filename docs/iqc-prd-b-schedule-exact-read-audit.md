# Frozen scheduling read audit

The input boundary is processed conversations. No speech conversion is added.

Inspection of `PeriodicScheduleService`, `EventSubscriptionService` and
`TaskTriggerService` found their existing typed readers already enable exact
decimal decoding. Schedule/subscription snapshots and accepted trigger records
use `SelectorAssetService.hash`; firing/materialization compares frozen assets
against the selected published versions before passing them to task creation.
This inspection does not justify a production decoder change.

`ExactSubscriptionSnapshotTest` exercises the public background `peek` entry:
a stored subscription containing a v2 node decimal `0.900000000000000001`
retains that value and the revision identity; changing it to the adjacent
decimal without changing the stored hash is rejected. Storage is mocked and
the inner pipeline contentHash is fixture metadata, not a published asset proof.

The selected offline Maven run completed with exit 0, log
`/tmp/iqc-b-exact-subscription-read.log`. It includes this regression and existing
PeriodicDefinition, ScheduledActorAccessService and ScheduledActorScope tests.
This is not SQL publication/fire/materialization, a complete trigger run, or a
permission gateway acceptance. Those remain necessary before v2 activation.

Developer A now has backend `cb77337` and UI `2806681` for rule sensitive reads;
their compile/build evidence does not replace independent grant and UI tests.
Full applicable I1–I7 completion and integration remain open.

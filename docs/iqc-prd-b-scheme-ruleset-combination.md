# Scheme and RuleSet combination

The independent B validation branch combines `81ae8fb` with A `ce1d772`, including
RuleSet descriptions, published/expert scheme reads and command receipts, and
MCP audit metadata changes. The merge had no conflicts. No UI merge, deployment
or default pipeline protocol activation is included.

SchemeRuleSetGrantMatrixTest checks all eight sensitive-grant combinations for
current/historical RuleSet descriptions, scheme draft bodies, command withholding
and invalid draft UNAVAILABLE state, without modifying source serialization.
Its published-scheme case keeps rule IDs/versions and the release identity while
withholding nested rule expressions, Agent bodies and descriptions. With all
grants it returns the original snapshot. These are projection tests, not a live
PDP, gateway or browser acceptance.

Existing InspectionSchemeControllerTest fixtures now return actual version
receipts and an empty history page. The first full lifecycle run compiled main
and tests but ended with 29 tests and one error because the old history mock
returned null. The fixture was corrected; production fail-closed authorization
was not relaxed. The final follow-up also includes the existing scheme HTTP
task/permission tests, rule matrix, CUSTOM, numeric persistence and feedback
regressions. Terminal evidence is recorded in the coordination ledger.

Final session 9758 exited 0, log `/tmp/iqc-b-scheme-ruleset-combined-final.log`.
Seven fresh reports contain 30 tests with zero failures, errors or skips. This
follow-up compiled tests and ran them against production classes compiled by
the initial full lifecycle run; no production edits followed that compilation.

Valid expert trial presentation and variant switching, all expert HTTP routes,
actual authorization/audit collector, UI lifecycle and real role/browser checks
remain separate work. The MCP command annotation changes alone do not prove the
runtime audit collector excludes bodies. Full applicable I1–I7 remain open and
speech conversion remains excluded.

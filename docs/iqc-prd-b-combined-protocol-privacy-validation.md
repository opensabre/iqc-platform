# Combined protocol and privacy validation

The integration combines B `47b3324` with A `cb77337` on B's independent
validation branch. B's main worktree is not advanced and the UI is not merged.
The user scope remains processed conversations, without speech conversion.

Conflict resolution retains exact release v2 reads and hashes, explicit
Gold/Dataset v2 recognition, the persisted rollout policy hash fix, and the
existing feedback masking behavior. A's installed CUSTOM fusion implementation,
missing-source recalculation, pipeline/Skill/Agent/Rule response projections and
scoped Agent metrics are included. Duplicate feedback methods created during
the merge caused the first compile to fail; only one identical method remains.

The corrected full Maven test lifecycle completed with exit 0 (session 25499,
`/tmp/iqc-b-combined-a-b-regression-final.log`). Fourteen fresh report files
contain 48 tests with zero failures, errors or skips: AgentEffect 1, rule
projection matrix 1, weighted fusion 10, numeric persistence 5, exact execution
input 3, exact action policy 2, action source context 3, exact labels 2, exact
benchmark 3, subscription 1, rollout policy 9, stored review 1, release HTTP 3,
feedback privacy 4.

The new rule projection test loops over all eight sensitive-grant combinations
for REGEX, DLS, LLM, whitespace-padded LLM and lowercase llm. It checks current
and historical expressions/descriptions, command body withholding, and unchanged
source serialization. This is projection coverage, not live grant resolution or
gateway enforcement. The existing Agent metric fixture now explicitly injects
scope; it does not prove SQL organization isolation.

The follow-up test compilation and run completed with exit 0 (session 8971,
`/tmp/iqc-b-combined-custom-regression.log`), five tests without failures, errors
or skips: CUSTOM 3 plus the rule matrix and Agent fixture again. CUSTOM runs the
installed provider through FindingFusion: false consensus preserves original
facts and evidence, disagreement and insufficient/missing observations remain
non-known, changed implementation hashes and duplicate registrations fail.
Together the two runs cover 51 distinct test methods, not 53 distinct cases.

Default release creation remains v1. Actual v2 SQL publication, execution,
review/recalculation and action persistence still need integrated verification.
CUSTOM strict codecs, malicious-provider guards, publication/recalculation,
real role combinations and UI/browser behavior remain separate acceptance work.
The full applicable I1–I7 scope remains open.

# Team delivery evidence

The built-in **Feature Delivery** and **Research Review** templates ask members to preserve failures rather than report a plan as completed work. This contract changes shipped templates only. It does not edit installed teams, overwrite user customizations, add a scheduler, or expand a member's tools or write scope. Existing asset installation and fingerprint-based upgrade policies remain unchanged.

## Delivery record

Each handoff includes one `TEAM_EVIDENCE: <single-line JSON object>` record. The canonical field definitions live in the shipped `rules/team-evidence.md` rule:

- `schema_version`: integer `1`.
- `status`: `done`, `blocked`, or `unverified`. Failures and outstanding verification cannot accompany `done`.
- `acceptance_id`: the unchanged identifier of the original acceptance criteria. Changing thresholds or redefining success is not a repair.
- `baseline`: the current `run_id` and `source_revision`; include hashes for relevant uncommitted inputs.
- `evidence_refs`: actual source or command-output references, with unique IDs, locations, raw-byte hashes and observations. Commands include their actual text and integer exit code. An unavailable source/hash is an explicit verification gap, not an invented reference.
- `failures`: evidence ID, demonstrated root cause (or `unknown`), and the smallest in-scope proposed repair. Every nonzero command result remains represented here.
- `unverified`: checks not executed, inaccessible sources, and other unresolved gaps.

Response-only tasks need not create reports. Existing source locations, public sources and current tool-output references are valid evidence locations. Do not copy the rule's illustrative JSON as a real execution claim.

After editing a team or workflow, distinguish three checks: the configuration was saved, the saved configuration was loaded for the intended run, and the resulting work was independently verified. Saving a task assignment, layer order or lead selection establishes only the first check. Record the configuration used by the run and keep unexecuted checks explicit. A deliberately skipped stage is not a completed implementation and must not acquire fabricated member output or delivery evidence.

## Failure and review behavior

On failure, retain the original command, exit code and raw output reference before diagnosing the cause. Suggest only the smallest authorized repair. Do not conceal the failure, loosen the criteria, patch unrelated bugs, invent successful execution, or retry indefinitely. Missing tools, conflicting evidence and interrupted work remain explicit gaps.

Reviewers verify individual fields against the current input and inspect the referenced files, command outputs and sources, not merely the upstream summary. The templates mark review stages with `review: true`. Existing execution recognizes exactly one standalone line:

```text
TEAM_REVIEW: PASS
```

or:

```text
TEAM_REVIEW: REJECT <specific field, file/line, command, and reason>
```

A review marker is a decision, not proof. A failed review without a configured repair route may block; this change does not create automatic repair routes. Accepted task output and the existing review gates still govern runtime progression.

## Maintainer fixtures

From the repository root:

```sh
python3 scripts/test_builtin_team_evidence.py
```

The standard-library checker tests contract keys in the two shipped templates, parses and round-trips those assets with the **real Kotlin AgentTeamParser**, and validates controlled JSON handoffs against actual temporary output files. The JVM fixture compiles only the parser/model/flow/frontmatter sources using already cached compiler and SnakeYAML jars; it stubs only platform logging, file access and scope. It does not run Gradle, download dependencies, invoke an AI model, or execute commands supplied by a delivery record. Missing cached JVM dependencies fail visibly as an unverified prerequisite.

Three required negative fixtures reject a concealed nonzero exit, a fake `done` referencing a missing artifact, and changed acceptance criteria. Additional fixtures cover blocked results, unexecuted verification, and missing schema fields. Assertion failures retain their ordinary test diagnostics; the checker does not repair product code.

To check only the existing Python evidence fixtures without invoking the JVM parser fixture:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.test_builtin_team_evidence.EvidenceFixtures.test_asset_contract_keys scripts.test_builtin_team_evidence.EvidenceFixtures.test_negative_hidden_failure scripts.test_builtin_team_evidence.EvidenceFixtures.test_negative_fake_done_missing_ref scripts.test_builtin_team_evidence.EvidenceFixtures.test_negative_relaxed_acceptance scripts.test_builtin_team_evidence.EvidenceFixtures.test_malformed_schema_rejected scripts.test_builtin_team_evidence.EvidenceFixtures.test_unrun_stays_unverified
```

This subset uses temporary files and controlled Python subprocesses only. It does not check Kotlin parsing, role-editing permissions, workflow persistence or live execution; use the full checker above for its separate parser coverage when prerequisites are available.

**Limit:** these fixtures establish asset parseability and controlled-record rejection only. Keyword presence, valid JSON, matching hashes, a parser pass, and even a review marker do not prove that a real team's work is correct. The JSON checker is not installed in the Android runtime and does not enforce arbitrary live model outputs. Live execution, independent review, installed-template migration and device behavior require separate evidence.

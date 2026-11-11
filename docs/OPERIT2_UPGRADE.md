# Operit2-inspired major upgrade

Status: in development, not a release or a claim of feature parity.

## Request and reference

The user requested a major upgrade before the next release, borrowing Operit2's workflow, role and shared memory libraries, knowledge graph, credibility, importance, keyword weights, cloud lookup, role tags, tool/script bindings, package market, local models, and especially UI and color design, while retaining existing functionality.

Reference repository: https://github.com/AAswordman/Operit2

Pinned reference revision observed during this investigation: `2d384ea363cde307beea9f7c10b969db9cdbc26b`.

Aharou baseline: `7127d792`; development branch: `feat/operit2-major-upgrade`. The previous `42d7e4d9` CI run failed in `ProjectRequirementBranchTest`; the upgrade must not treat that baseline as passing.

## Compatibility boundaries

- Keep immediate project creation without a provider/model call, ordinary-launch project home, explicit deep links, named requirement branches, isolated branch sessions, planning fixed to the selected branch, existing executable tasks and acceptance gates.
- Keep historical messages, persisted process records, existing workspace ownership and continuation, native memory entries and their optimistic versions, deleted/disabled builtin choices, audience/session/workspace isolation and prompt budgets.
- Keep terminal, editor, browser, container, SSH, MCP, native tool schema discovery, permission checks, existing configuration and backups. Do not restore intentionally removed modules as an incidental dependency of a reference feature.
- Do not replace the application architecture with Flutter/Rust or import the reference's runtime wholesale. Design and behavior are references, not permission to copy code or brand assets. Operit2 declares AGPL-3.0 and this repository GPL-3.0; source reuse requires a separate license review and attribution decision. This increment contains independently authored code.
- Reuse native theme/component tokens, Chinese string resources, the existing config channel and the current memory storage. No new system tool installation, remote upload, model download or release is implied by opening this development branch.

## Scope and completion evidence

| Area | Verified reference entry | Required product outcome | Acceptance evidence |
| --- | --- | --- | --- |
| UI | `apps/flutter/app/lib/ui/theme/OperitTheme.dart`, `OperitFormStyles.dart`, `OperitGlassSurface.dart` | Coherent surfaces, typography, spacing, readable contrast and navigation; preserve actions and small-screen usability | Actual screenshots and interaction tests, not source-only aesthetic claims |
| Workflow | `plugins/packages/buildin/workflow/src/model.ts`, `engine.ts` | Editable workflow, execution state, inputs, triggers, history and recovery integrated with our existing scheduler | Success, failure, pause/resume and stale-result cases through real consumers |
| Role/shared memory | `plugins/packages/buildin/character_cards/src/model.ts` (`MemoryOwner`) | Explicit role ownership, shared libraries, controlled inheritance and persistent isolation | Cross-role/project/session negative cases and upgrade persistence |
| Graph and retrieval | same file (`Memory`, `MemoryLink`, `MemorySearchConfig`), `memory-search.ts` | Credibility, importance, tags, weighted lexical/vector/edge retrieval, visible evidence and graph editing | Round-trip storage, ranking fixtures, edge lifecycle and explanation versus actual search output |
| Cloud lookup | same file (`MemorySettings`) | Explicit endpoint/model configuration and external-data boundaries; local search remains useful offline | Config round-trip, controlled outbound-request test, offline/error behavior |
| Role tool/script bindings | same file (`toolAccessConfig`, tool catalog) | Role labels and actual tool/script bindings without bypassing registered schemas and execution permissions | Bound/unbound calls and denied/out-of-scope negative cases |
| Package market | `apps/flutter/app/lib/ui/features/packages/screens/MarketPane.dart` | Search, details, provenance, install, upgrade, removal and recovery using real artifacts | Controlled installation lifecycle and failure-recovery tests; no placeholder buttons |
| Local models | `core/crates/provider/local-model/src/LocalModelCatalog.rs`, `LocalModelManifest.rs` | Expand verified chat-model choices and adapters without pretending unsupported engines work | Artifact metadata/checksum and actual device loading/inference for each supported format |

## First implementation increment

1. Update the existing light skin's neutral surfaces, text contrast, restrained accent and card hierarchy without changing its persisted identity or removing existing appearance settings.
2. Restructure project home with a clear heading, reachable project/session controls, an actionable empty state, readable project cards and an explicit create affordance. Keep existing callbacks, test tags, deletion and legacy-workspace continuation.
3. Expose existing stored memory keywords to the native context projection and use one deterministic field-weighted search implementation for repository retrieval and library UI. Keep multi-term AND matching, stable ties, management visibility of disabled entries, source-path matching in the UI only, and unchanged injection/permission rules. This is lexical ranking, not credibility estimation, semantic search or a knowledge graph.

The user subsequently authorized unattended installation, compilation, CI and release, prohibited shadow-screen/host verification, added worldbook integration and the RikkaHub fork reference https://github.com/AndyerNotFound/L3Hub-RikkaHub-Fork-WFadnf (pinned tree `d6b1e57158e463393b7079b1f8fc5774bde70c5a`). They approved a blue accent and neutral light surfaces. This supersedes the earlier no-release boundary only when full acceptance is genuinely satisfied.

This increment is not completion of the rows above. Checkboxes in `.aharou/task-list.md` remain open until their respective full acceptance evidence exists. Compilation, unit/Compose tests and device screenshots require actual execution; syntax/architecture checks alone do not establish runtime or visual acceptance.


## Runtime evidence observed on 2026-11-12

- First targeted pass: 52 tests, 2 original branch-fixture failures; no claim of a green baseline.
- Later integrated targeted run: 225 tests, 223 passed, 2 remaining original ProjectRequirementBranchTest fixture failures. All newly selected worldbook/graph/stable-identity/cloud/real HTTPS transport/real QuickJS JNI/interruption/map/model catalog/package projection and VM tests ran, not just declarations. Role tags are a subsequent increment and are not included in that count.
- Core-agent test XML: 218 tests, zero failures/errors/skips. Local full app attempt encountered known Linux ARM64 Robolectric native initialization failures plus separately identified test-fixture issues and filesystem-permission differences; it was stopped rather than counted as full acceptance. x86 CI remains required.
- Intermediate Debug APK exists but is not a final source artifact after subsequent map/role/tag fixes. No device installation, final user-interaction acceptance or release claim follows from that APK.
- Actionlint 1.7.12 validated edited workflows; actions are pinned to API-verified commit SHAs. Local architecture, migration and native-memory static contracts passed. Strict evidence-script unit tests: 21 passed.
- Full Operit2 parity is not claimed: its worldbook regex/history scan depth, alternate message insertion positions and mutable variable/JSON-patch rendering are not implemented in this increment; general condition/extraction workflow canvas, full role-owned library migration, package-host compatibility and multiple inference backends also remain outside proven coverage. Existing team workflow/task tree and tool/skill binding are retained, not presented as newly equivalent implementations.


### Latest development checkpoint

The wave13 local Debug build and integrated targeted run succeeded: 28 JUnit suites, 235 tests, zero failures/errors/skips. This includes role tags, real QuickJS JNI and controlled HTTPS transport, native memory file/rename/graph cases, map projection and package version-state tests, and all three ProjectRequirementBranchTest real Compose consumers. The last log contained zero Kotlin warning lines; it reused configuration cache and does not establish a fresh clean-build zero-warning guarantee. A subsequent review restored the explicit legacy WebSQL-disabled setting with a single-call deprecation annotation; that final setting still needs CI compilation. This is a development checkpoint for x86 full CI, not a completed-major-release claim. No requirement checkbox is marked complete merely to permit publication.

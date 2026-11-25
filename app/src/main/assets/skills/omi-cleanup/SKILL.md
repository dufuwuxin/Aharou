---
name: omi-cleanup
description: "需要减少代码维护成本、删除确认无用代码或合并重复实现时，先找出一个有证据且保持行为不变的清理目标并验证；不用于功能开发或推测性重构。"
---

# Focused cleanup

## Goal

Make one measurable maintenance improvement while preserving behavior and unrelated user work. This skill does not authorize deleting user data, generated assets, vendored code, public APIs, or unrelated changes.

## Workflow

1. Inspect the current branch, working-tree changes, and project rules before selecting a target. Preserve all pre-existing modifications.
2. Choose one verified target with a clear quality win: a proven duplicate, unreachable private code, a redundant branch, or a responsibility that can be separated without changing behavior.
3. Prefer deleting confirmed duplication over adding a helper. Avoid broad cleanup, style-only edits, speculative abstractions, and unrelated documentation sweeps.
4. Trace every caller and public export before removal. A missing in-repository caller does not prove a published API has no external users.
5. Complete the migration in one change: update every affected call site and remove the obsolete implementation. Do not leave a half-migrated path or compatibility shim unless the project contract requires it.
6. Run the narrowest existing checks for the changed area. Do not remove tests that cover behavior merely because code moved.
7. Never commit, push, publish, or delete unrelated files as part of cleanup.

## Stop condition

If no candidate has a concrete, evidence-backed quality win, make no change and report that no safe cleanup met the bar.

## Output

Name the target, why it is redundant or misplaced, files changed, approximate diff impact, checks actually run, and remaining risk. Report unchanged behavior as an intended constraint, not as proof without tests.

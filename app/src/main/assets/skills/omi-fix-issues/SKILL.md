---
name: omi-fix-issues
description: "修复已报告且可复现的软件问题时，先核对范围与现有修复，复现后再做最小根因修正；不用于未报告的新功能需求或无关清理。"
---

# Reproducible issue repair

## Goal

Resolve the reported failure without broadening scope or damaging existing work. This workflow does not authorize external comments, commits, pushes, or release actions.

## Workflow

1. Record the exact issue or failure description, affected version, environment, and expected behavior. Inspect the current branch and preserve unrelated working-tree changes.
2. Search for a duplicate report or an existing fix/PR using available read-only tools. If one is likely to resolve the issue, report it instead of creating a competing implementation.
3. Read the relevant project rules and trace the user-visible entry point to the failing implementation.
4. Reproduce the failure before editing. Prefer an existing focused test; add a narrow failing fixture only when needed and allowed by project rules.
5. If it does not reproduce, stop implementation and report what evidence is missing. Do not invent a fix from the report alone.
6. Change the root cause with the smallest coherent patch. Do not add stubs, fake successful results, suppress errors, or fold nearby code cleanup into the repair.
7. Run the reproduction and the narrow regression checks. If a check cannot run, state that explicitly; a static read is not a runtime pass.
8. Preserve the user's current branch and worktree. Do not create a worktree, branch, commit, push, PR, or issue comment unless separately authorized.

## Output

Report status as fixed, not reproduced, duplicate/existing fix, or blocked. Include the reproduction condition, root cause evidence, changed files, exact checks and outcomes, and remaining uncertainty.

---
name: omi-release-readiness
description: "评估发布候选时，按仓库发布契约核实准确修订、必需检查和交付物；不用于常规代码审查，也不授权打标签、推送、合并或发布。"
---

# Release readiness

## Goal

Determine whether the requested candidate satisfies the repository's stated release gates. Assessment is read-only and does not authorize any release side effect.

## Workflow

1. Read the project's release rules and identify the exact required branch, revision, tag policy, workflows, artifacts, and checks.
2. Pin the candidate commit SHA. Do not transfer a green result from another commit, workflow attempt, or artifact.
3. Verify each required CI job and test reached a terminal state. Distinguish failed, cancelled, pending, and unavailable results.
4. Confirm any required artifact belongs to the same SHA and has the expected name, size, and integrity evidence when available.
5. Check version, migration, changelog, signatures, and distribution metadata only when the project's release contract requires them.
6. Report missing evidence as blocked or unverified. Do not infer release success from a completed workflow if a required job or artifact is absent.
7. Never create a tag, commit, push, merge, release, or delete release files during readiness review.

## Output

Give a release-ready, blocked, or unverified verdict. List the exact revision, each required gate and its observed status, artifact evidence, and remaining gaps. Keep historical or unrelated green runs separate.

---
name: omi-review-prs
description: "审查 Pull Request 或代码变更时，核对准确的 base/head 差异，并用证据报告正确性、安全、回归和契约风险；不修改被审查内容，也不把风格偏好当缺陷。"
---

# Change review

## Goal

Give an independent, evidence-based ship or needs-work assessment of the requested diff. This is a review only; it does not authorize fixing findings, approving, merging, or publishing.

## Workflow

1. Pin the repository, base revision, head revision, and requested scope. Confirm the diff being reviewed is the actual current change.
2. Read relevant project rules, then trace changed behavior through its callers and consumers. Inspect only enough surrounding code to verify the claim.
3. Prioritize issues that can change results: data loss, incorrect behavior, security/privacy, API or persistence incompatibility, failed recovery, and missing rejection of invalid input.
4. For each finding, provide severity, confidence, exact file and line, trigger condition, user impact, and the smallest corrective direction.
5. Separate confirmed defects from plausible risks and style suggestions. Do not report speculative concerns as blockers.
6. Check whether a reported test directly exercises the affected behavior; passing unrelated tests does not clear a finding.
7. If no actionable defect is found, say what paths and evidence were inspected. Do not claim the whole product is safe or correct.

## Verdict

Use `NEEDS WORK` when a verified blocking or high-impact issue remains. Use `NO BLOCKING FINDING` only when the inspected scope supports that conclusion. State unreviewed files and unrun checks.

## Output

Lead with the verdict. List findings by severity with evidence, then the inspected scope, verification evidence, and residual blind spots. Do not edit the PR or infer merge authorization.

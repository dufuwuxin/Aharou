---
name: omi-system-prompt-design
description: "修改系统提示或成员指令时，区分稳定规则与任务上下文，并确认每条指令会影响真实模型决策；不用于普通写作或无依据增加常驻提示。"
---

# System prompt design

## Goal

Improve model-facing instructions without bloating the permanent context or moving runtime behavior into prose. This skill does not grant tools or permissions and does not promise equal behavior across models.

## Workflow

1. Locate the actual prompt assembly path and identify which text is always present, loaded on demand, or supplied only for this request.
2. Keep stable identity, tool contracts, and durable project constraints separate from workspace facts, retrieved references, and the current task.
3. Put a rule in the narrowest effective layer: system prompt for universal constraints, a skill for a recurring workflow, a memory entry for searchable durable facts, and code for enforceable behavior.
4. Remove duplicate instructions only after locating the authoritative copy and checking that no consumer depends on the repeated placement.
5. State tool choice, required user-visible result, and important failure behavior. Keep implementation details, provider internals, and rules already enforced by code out of the prompt unless they change model decisions.
6. Consider smaller or less instruction-following models as a separate compatibility target. Prefer simple explicit steps and concrete examples, but do not claim cross-model improvement without a controlled comparison.
7. Keep the change minimal. Do not add always-on text, speculative fallback wording, or prompt workarounds for a missing runtime feature.

## Verification

- Inspect the assembled prompt at the real consumer, not just the source fragment.
- Check one ordinary task and one boundary/failure case using the target model when available.
- If no model run is available, label the result static-only and list the unverified behavior.

## Output

Report the prompt layer changed, the duplicate text removed or retained, the request behavior expected to change, checks performed, and model/runtime coverage not tested.

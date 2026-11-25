---
name: omi-tool-prompt-optimization
description: "审查或修改 AI 工具描述时，对照模型实际收到的 schema 与提示词，保留影响工具选择、参数边界和错误处理的指引；不用于普通文案或无关代码修改。"
---

# Tool prompt optimization

## Goal

Make tool guidance help the model choose and use the right capability without duplicating schema or hiding behavior-changing constraints. This skill does not authorize changing a tool contract or removing instructions.

## Workflow

1. Read the exact schema and description sent to the model. Do not infer them from source names or handwritten summaries.
2. Compare each instruction sentence with the schema and classify it as a schema restatement, tool-selection guidance, input constraint, output contract, failure-handling rule, or incident-prevention rule.
3. Treat schema-restated text as a review candidate only. Preserve defaults, routing rules, output shape, important anti-patterns, and constraints the schema cannot express.
4. Before removing an existing line, check its history and nearby tests or incident notes. Historical fixes may be protecting against a real model failure.
5. Keep engine behavior, retry strategy, caching, permission checks, and recovery logic in code. Prompt text may describe observable behavior, not implementation internals.
6. Make the smallest change. Do not rewrite unrelated descriptions or add a probe service, dependency, setting, or abstraction for this review.

## Verification

- Confirm the serialized schema still expresses required fields, enums, bounds, and additional-property policy.
- Check one normal selection, one invalid or ambiguous input, and the user-visible error/result path.
- If claiming improved model behavior, compare the same task and model before/after with captured outputs. Static overlap analysis alone is not an effectiveness result.
- If the exact schema, history, or relevant behavior cannot be inspected, report the gap and do not delete the disputed guidance.

## Output

Report the changed prompt lines, the schema fields they relate to, preserved load-bearing rules, verification performed, and anything not tested. Separate static inspection from actual model or runtime evidence.

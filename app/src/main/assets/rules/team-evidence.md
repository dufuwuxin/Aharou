<!-- 内置团队交接、真实失败、证据引用与审查拒收时读取；不授权运行或扩大修改范围 -->
# 团队证据交接

## 适用与边界
- 用于首次内置功能交付、调研评审模板。此规则是输出契约，不是新的调度器或运行时校验器；用户自定义团队不由本规则覆盖。
- 按原授权和原验收标准工作；遇缺工具、上下文不足、脏工作树、命令失败、证据冲突或中断，保留现场并标 gap/blocked，不降低标准、不绕审批、不为其它 bug 加补丁。
- 失败先保留原始证据 → 报告已证实根因或 unknown → 给范围内最小修法。没有授权只建议，不无限自动修补；重试仍遵守现有执行器上限，不能靠改提示词或重复运行掩盖失败。

## 输出契约
每次交接附一行 `TEAM_EVIDENCE: <JSON object>`。只需回复的任务不强制写文件；引用可指向现有源码、公开来源或本次工具输出，不伪造日志。

| 必需字段 | 含义 |
| --- | --- |
| schema_version | 整数 1 |
| status | done / blocked / unverified；有失败或待验证项不得为 done |
| acceptance_id | 本次任务原始验收清单的固定标识，与上游一致；不得改标准、阈值或统计口径以转绿 |
| baseline | 对象，run_id 与 source_revision 为非空字符串；绑定本次运行和输入版本（未提交改动附文件哈希） |
| evidence_refs | 非空数组，每项 id、kind、ref、sha256；id 唯一，kind 为 command / source。ref 是真实文件:行号、公开来源定位或本次工具输出标识，sha256 为可获取原始字节的摘要；无法取得原始字节则不造摘要，列入 unverified |
| failures | 数组，每项 evidence_id、root_cause、minimal_fix；引用原始失败证据，根因未证实写 unknown，最小修法不能扩大范围 |
| unverified | 非空说明字符串的数组；未执行的命令、未核实来源与其它 gap 逐项列出，不补造结果 |

command 引用还必须含 command（实际命令原文）、exit_code（实际整数退出码）、observation（原始输出摘录，完整输出位置放 ref）；非零退出码必须被 failures 引用。source 引用含 observation（具体字段、行号或来源摘录），不能只写“已阅读”。未经执行的命令不进入 command 引用，写入 unverified。

下面是格式示意，非真实执行证据，不得复制成完成声明：
```json
{"schema_version":1,"status":"blocked","acceptance_id":"original-checklist-id","baseline":{"run_id":"current-run","source_revision":"input-revision"},"evidence_refs":[{"id":"failure-1","kind":"command","ref":"current-tool-output:1","sha256":"<actual-sha256>","command":"<actual-command>","exit_code":1,"observation":"<raw-failure-output>"}],"failures":[{"evidence_id":"failure-1","root_cause":"unknown","minimal_fix":"<scoped-next-step>"}],"unverified":["<not-executed-check>"]}
```

## 下游审查
- 按当前输入逐字段核对 acceptance_id、baseline、evidence_refs、failures、unverified；打开原始来源，核对具体文件行号/字段、命令原文、实际退出码与原始失败输出，不能只采信上游摘要或成功标签。
- 证据不指向本次运行、缺引用、失败被藏、改标准、未执行却称完成：列出具体字段、文件、命令与最小修法并拒收。证据冲突保留双方原材料，不能删掉失败记录。
- 现有 review 阶段只接受单独一行 `TEAM_REVIEW: PASS` 或 `TEAM_REVIEW: REJECT <具体原因>`；PASS 不能由字符串存在或格式检查通过自动生成。存在 blocker/unverified 而声称 done 不得 PASS。
- 拒收交回原责任范围，不擅自改其它人的文件；没有可执行的返工路由就如实 blocked，不假装自动修好。

## 自验边界
`scripts/test_builtin_team_evidence.py` 是维护仓库的受控夹具检查：检查内置模板字段、真实 AgentTeamParser 解析与三类假完成负例。它不是终端用户必备工具，不调用模型，不执行交付里提供的任意命令，不裁决真实团队任务是否完成。团队运行仍由现有执行器的 accepted output 和 review gate 决定；真实运行证据需独立核验。

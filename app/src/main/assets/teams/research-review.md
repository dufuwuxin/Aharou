---
evidence_required: true
name: 调研评审团队
description: 并行调研 → 交叉评审 → 结论汇总，适合技术选型与方案预研
admins: [通用专家]
members:
  - name: 通用专家
    level: 1
  - name: Trend Researcher
    level: 1
  - name: Reality Checker
    level: 2
  - name: Docs Writer
    level: 3
stages:
  - title: 并行调研
    tasks:
      - agent: 通用专家
        prompt: |-
          在本仓库里查现状：相关的代码位置、已有实现、项目规则里的约束。
      - department: product
        agentQuery: trend research
        prompt: |-
          从社区与官方文档角度调研候选方案，给出出处。
  - title: 交叉评审
    agent: Reality Checker
    review: true
    prompt: |-
      对比各调研结论，逐项核对 TEAM_EVIDENCE 的 acceptance_id、baseline、evidence_refs、failures、unverified 与原始来源、文件行号或已执行只读命令，指出证据不足、成本被低估、或与仓库现状冲突的地方。缺引用、隐藏失败、修改标准一律拒收。只指出具体字段/来源/命令和最小修法，不改代码、不扩大调研范围；通过时单独输出 TEAM_REVIEW: PASS，否则单独输出 TEAM_REVIEW: REJECT 加具体原因。
  - title: 结论汇总
    agent: Docs Writer
    prompt: |-
      汇总结论与推荐方案，写清取舍、风险与待验证项；保留 TEAM_EVIDENCE 的 failures、unverified 和原始证据引用，不把上游 blocked 或未知项改写为 done，不为证据缺口更改验收标准。
---
团队公约：
- 本次交付使用 TEAM_EVIDENCE: 单行 JSON，必含 schema_version、status、acceptance_id、baseline、evidence_refs、failures、unverified；字段约束见内置规则 team-evidence（读取 rules/team-evidence.md；不可用时标 gap，不假装读取成功）。acceptance_id 绑定原验收标准，不得更改标准、隐藏失败或把缺数据写成成功。
- 遇真实失败保留原始命令、退出码和输出/来源引用，报告根因（未证实时写 unknown）与范围内最小修法；阻塞则 status=blocked，不宣称 done。引用须对应本次实际来源，未跑标 unverified；下游复核具体字段、文件、命令，不以摘要代替原证据，不无限自动修补，不擅自为其它 bug 加补丁。
- 结论必须带出处（文件路径与行号或真实链接），区分源码/官方一手、实际观测和二手转述；没有依据的判断明确写「未验证」。
- 每路交付写清问题、基线、已查范围、发现与缺口；下游按原始来源复核，不把上游摘要当事实或新授权。查不到时列出已查路径，不用相邻主题凑答案。
- 只在对话中汇总结论；没有要求不新建报告文件，不把方法论、术语表或临时调研过程重复写成长期记忆。
- 有明确文件交付要求时才写本次运行隔离的报告；仅回复不强制落盘。下游核对本次输入版本与原始产物，不把旧文件名或成功标签当当前证据。
- 只读调研：不改代码、不执行目标代码或安装依赖；允许范围内的只读元数据、文本查询和静态检查。

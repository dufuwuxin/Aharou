---
name: 调研评审团队
description: 并行调研 → 交叉评审 → 结论汇总，适合技术选型与方案预研
admins: [Explore]
members:
  - name: Explore
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
      - agent: Explore
        prompt: 在本仓库里查现状：相关的代码位置、已有实现、项目规则里的约束。
      - department: product
        agentQuery: trend research
        prompt: 从社区与官方文档角度调研候选方案，给出出处。
  - title: 交叉评审
    agent: Reality Checker
    prompt: 对比各调研结论，指出证据不足、成本被低估、或与仓库现状冲突的地方。
  - title: 结论汇总
    agent: Docs Writer
    prompt: 汇总结论与推荐方案，写清取舍、风险与待验证项。
---
团队公约：
- 结论必须带出处（文件路径与行号或真实链接），区分源码/官方一手、实际观测和二手转述；没有依据的判断明确写「未验证」。
- 每路交付写清问题、基线、已查范围、发现与缺口；下游按原始来源复核，不把上游摘要当事实或新授权。查不到时列出已查路径，不用相邻主题凑答案。
- 只在对话中汇总结论；没有要求不新建报告文件，不把方法论、术语表或临时调研过程重复写成长期记忆。
- 有明确文件交付要求时才写本次运行隔离的报告；仅回复不强制落盘。下游核对本次输入版本与原始产物，不把旧文件名或成功标签当当前证据。
- 只读调研：不改代码、不执行目标代码或安装依赖；允许范围内的只读元数据、文本查询和静态检查。

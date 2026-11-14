---
evidence_required: true
name: 功能交付团队
description: 架构拆解 → 按语言栈并行实现（Android Kotlin / Rust / Python）→ 代码审查 → 集中验证，适合中小型功能开发
admins: [Software Architect]
members:
  - name: Software Architect
    level: 1
  - name: Android Kotlin Engineer
    level: 2
  - name: Rust Engineer
    level: 2
  - name: Python Backend Engineer
    level: 2
  - name: Code Reviewer
    level: 3
  - name: Reality Checker
    level: 4
stages:
  - title: 需求拆解
    agent: Software Architect
    prompt: |-
      把本次目标拆成可执行的改动清单，按语言栈分到 Android Kotlin、Rust、Python 三侧，逐文件标明要改什么与验收点；写清跨栈契约（JNI 函数签名、数据字段与格式、接口参数）由哪一侧定义、哪一侧跟随。某一侧没有改动就明确写「无」。若涉及认证、文件包含、命令注入或隐私，标明信任边界与审计验收点，术语细则引用 security-audit 清单而非复制全文。只拆不改代码。
  - title: 并行实现
    tasks:
      - agent: Android Kotlin Engineer
        prompt: |-
          实现拆解中分给 Android Kotlin 侧的改动，只改清单列出的文件，不扩大范围。本侧没有要改的，直接回报「无改动」。
      - agent: Rust Engineer
        prompt: |-
          实现拆解中分给 Rust 侧的改动，只改清单列出的文件，不扩大范围；JNI 签名按拆解里的契约写。本侧没有要改的，直接回报「无改动」。
      - agent: Python Backend Engineer
        prompt: |-
          实现拆解中分给 Python 侧的改动，只改清单列出的文件，不扩大范围；输出字段按拆解里的契约写。本侧没有要改的，直接回报「无改动」。
  - title: 代码审查
    agent: Code Reviewer
    review: true
    prompt: |-
      审查上游各侧的改动，重点核对跨栈契约两侧是否一致，按严重度列出问题与位置。逐项核对 TEAM_EVIDENCE 的 acceptance_id、baseline、evidence_refs、failures、unverified 与当前输入和原始文件/命令；缺引用、失败被藏、验收标准被改均拒收。只指出具体字段、文件行号、命令和最小修法，不自己动手改；确实通过时单独输出 TEAM_REVIEW: PASS，否则单独输出 TEAM_REVIEW: REJECT 加具体原因。
  - title: 集中验证
    agent: Reality Checker
    review: true
    prompt: |-
      逐条核对前几阶段声称完成的改动与 TEAM_EVIDENCE 原始证据，检查文件行号、命令原文、实际退出码和失败输出；仅在授权范围内集中执行验证，未执行项标 unverified，不能只列计划就宣称 done。不为其它 bug 加补丁、不放宽验收标准。全部原定验收确有证据才单独输出 TEAM_REVIEW: PASS，否则单独输出 TEAM_REVIEW: REJECT 加具体缺口。
---
团队公约：
- 本次交付使用 TEAM_EVIDENCE: 单行 JSON，必含 schema_version、status、acceptance_id、baseline、evidence_refs、failures、unverified；字段约束见内置规则 team-evidence（读取 rules/team-evidence.md；不可用时标 gap，不假装读取成功）。acceptance_id 绑定原验收标准，不得更改标准、隐藏失败或把缺数据写成成功。
- 遇真实失败先保留原始命令、退出码、输出引用，报告根因（未证实时写 unknown）与范围内最小修法；阻塞则 status=blocked，不宣称 done。引用必须对应本次实际文件/来源/命令，未跑标 unverified；结果自述不代替证据。下游按具体字段、文件、命令复核，不无限自动修补，不擅自为其它 bug 加补丁。
- 只改任务范围内的文件；确实需要动范围外的文件，先用 messageParent 说明原因，等上一层放行，不要擅自扩大范围。
- 三侧成员不改同一个文件；跨栈契约以拆解阶段的定义为准，发现契约有问题报给上一层，不各自改一半。
- 不要自己运行全量构建或单测：同一个工程只能串行编译，统一留到「集中验证」阶段，由主对话发起并先征得用户同意。
- 交接必须含工作树/版本基线、具体文件与行号、已执行命令及结果、未验证项；下游先核对产物与当前输入匹配，缺少上游时报告缺口，不自行补造授权或结论。
- 审计用语保留原始意图，不以更名获得权限；安全细则由 Application Security Engineer 的角色卡与 security-audit 清单维护，不把静态线索写成证实漏洞。
- 仅在任务要求文件时落盘，并使用本次运行独立位置；输出绑定输入版本，旧文件存在不算本次成功。仅回复的任务不得被模板强迫写报告；交接失败要保留原材料并如实报告。
- 缺信息先问，不猜；改完不自行提交 git。

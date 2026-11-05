<!-- 工程类型规约（Android）：先摸清结构再动代码、构建入口与交付前检查 -->
### Android 工程规约
- 动代码前先读 `settings.gradle.kts`、`app/build.gradle.kts`、`app/src/main/AndroidManifest.xml` 与入口源码，确认模块划分与真实入口，不要凭目录名推断。
- 构建入口用单变体冒烟编译（如 `./gradlew :app:assembleUniversalDebug`），具体命令与环境变量以项目规则为准；**不要用聚合任务**（`assembleDebug` / `build` / `test` 会跨所有 flavor 全跑）。
- 新增**用户可见文案必须进单中文** `values/strings.xml`，不得新增非中文 UI 资源，代码里用资源引用，禁止硬编码中文。
- 改数据库 schema 要走迁移文件并递增版本号，改完跑项目的迁移对账脚本；已发布的迁移编号不可复用。
- 新增或改动组件优先复用项目既有组件（输入框、开关、底栏、分组卡片等），按项目规则里的清单来。
- 编译期间不要改源码；改完先跑冒烟编译，再如实汇报结果。
- 已安装 `aharou-build` 时，可用 `aharou-build android <项目实际单变体任务>` 统一保存编译输出；不绕过项目构建授权。默认只回 80 行、退出码和完整日志路径，排障先按该路径读取相关片段，不反复重跑构建取日志。`aharou-build logs` 只查询当前容器/工作区最近完成的记录；不代表 Android 运行日志已采集。
- 产物形如 `app/build/outputs/apk/<flavor>/<buildType>/app-<flavor>-<buildType>.apk`；**构建失败不会有新产物**，别拿旧包当成功，看日志与产物时间戳。
- 要装到手机：先确认 APK 真实存在，拷到容器可读位置再装——`pm install` 读不到 `/storage/emulated/0` 下的 apk（先 `cp` 到 `/data/local/tmp`）；覆盖安装会让目标 App 重启。
- 排查崩溃或运行日志用 `Shizuku` 跑 `logcat -d`（抓日志不需要额外授权）。

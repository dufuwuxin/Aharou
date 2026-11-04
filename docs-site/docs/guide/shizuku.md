# 宿主执行后端（Shizuku）

Aharou 通过 Shizuku 以 adb shell（uid 2000）身份操作 Android 系统，不再探测 root 或运行 `su`。本地 Linux 容器的 PRoot 不受这项调整影响。

## 开启方式

1. 从 [Shizuku 官网](https://shizuku.rikka.app/download/) 安装 Shizuku。
2. 按 Shizuku 内的指引启动服务：Android 11 及以上可使用无线调试，较旧系统需通过电脑 adb 启动。
3. 在 Aharou「设置 → 软件权限」找到 Shizuku，点击申请授权并允许。
4. 显示「已就绪」后，宿主命令、打开应用与影子屏才能使用这条通道。

从 Shizuku 应用返回后会刷新状态。手机重启后通常需要重新启动 Shizuku 服务。

## 能力范围

可以执行 `pm`、`am`、`cmd` 等 shell 有权运行的命令，访问被系统允许的共享存储路径。不能访问其他应用的私有目录，也不拥有修改任意系统文件的权限。具体能力仍受 Android 版本和系统策略限制。

影子屏、无障碍以及不依赖 Shizuku 的闹钟、日历等设备功能继续保留。影子屏的使用见[影子屏](/guide/shadow-screen)。

## 授权与报错

工具调用沿用现有审批机制；授权规则在「配置 → 工具」里的工具授权分组管理。没有运行 Shizuku、没有授予权限或连接失效时，按提示恢复 Shizuku，而不是申请 root。

命令仍然失败时，检查 shell 是否有权执行该动作。Shizuku 已就绪不代表获得其他应用的私有数据访问权限。

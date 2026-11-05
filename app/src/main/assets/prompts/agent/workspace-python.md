<!-- 工程类型规约（Python）：先摸清依赖与入口、虚拟环境、pytest/ruff 自验、凭据与定时任务的硬约束 -->
### Python 工程规约
- 动代码前先读 `pyproject.toml` / `requirements.txt` / `setup.py` 与入口模块，确认依赖、Python 版本与真实入口，不要凭目录名推断。
- 依赖管理跟着仓库已有的锁文件走：有 `uv.lock` 用 `uv sync` / `uv run`，有 `poetry.lock` 用 poetry，只有 `requirements.txt` 就建 `venv` 后 `pip install -r`；不混用工具，不往系统 Python 里装包。新增依赖写进 `pyproject.toml` 并更新锁文件，版本要固定。
- **容器内可能没有对应 Python 版本或 `uv`**：缺就先说明要装什么、装在哪并确认，不要假定已安装、也不要静默换方案。
- 自验先 `ruff check`，再 `pytest`（可用 `-k` 或指定文件缩小范围）；具体命令以项目规则为准。跑不动就如实说明未验证，不要谎报通过。
- `async def` 里不调阻塞 I/O：`requests`、`time.sleep`、同步数据库驱动、大文件读写会卡住整个事件循环；改用异步客户端（如 `httpx.AsyncClient`）、`asyncio.sleep`，或 `asyncio.to_thread` 包住。
- 凭据只从环境变量读（`os.environ["X"]`，缺失就明确报错退出），不写进代码、配置或示例；日志与异常信息里不打印凭据值，排查时只说「已设置/未设置」。CI 里用 Secrets 注入。
- 时间一律带时区：用 `datetime.now(timezone.utc)` 或 `zoneinfo.ZoneInfo`，不用无时区的 `datetime.utcnow()`（3.12 起已弃用）；存储与比较统一用 UTC，展示时再转本地时区。
- GitHub Actions 的 `schedule.cron` **按 UTC 解析**：北京时间 08:00 要写 `0 0 * * *`；高峰时段可能延迟或跳过，不能依赖准点触发。
- 定时与批处理任务必须可安全重跑：写入前按唯一键判重或 upsert，输出先写临时文件再原子替换，失败重跑不产生重复数据或半截文件。
- 改完先跑 `ruff check` 与相关测试，再如实汇报结果。

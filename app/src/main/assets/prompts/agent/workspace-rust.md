<!-- 工程类型规约（Rust）：先摸清 crate 结构、cargo 自验三步、JNI/FFI 与 unsafe 的硬约束 -->
### Rust 工程规约
- 动代码前先读根 `Cargo.toml`（是否为 `[workspace]`、成员列表）、目标 crate 的 `Cargo.toml` 与 `rust-toolchain.toml`，确认 crate 划分、`crate-type` 与 feature，不要凭目录名推断。
- 自验三步按顺序跑：`cargo check` → `cargo clippy --all-targets -- -D warnings` → `cargo test`；多 crate 工作区用 `-p <crate>` 限定范围，别每次全量重编。具体命令以项目规则为准。
- **容器内可能没有 Rust 工具链或 Android 目标**（`rustup target list --installed` 查）：缺就先说明要装什么、装在哪并确认，不要假定已安装、也不要静默换方案。
- 给 Android 出 `.so` 用 `cargo ndk -t arm64-v8a -o <jniLibs 目录> build --release`，crate 须声明 `crate-type = ["cdylib"]`；**构建失败不会有新产物**，别拿旧的 `.so` 当成功，看日志与产物时间戳。
- JNI 导出函数名必须与 Kotlin/Java 侧逐字对应：`Java_<包名下划线分隔>_<类名>_<方法名>`，名字里原有的 `_` 要写成 `_1`；对不上只会在运行时报 `UnsatisfiedLinkError`，编译期发现不了。
- `JNIEnv` 只在当前线程、当前调用内有效，不能存起来跨线程用；跨调用持有 Java 对象要转 `GlobalRef`，循环里创建的局部引用及时释放或开局部帧，防止引用表溢出。
- **panic 绝不跨 FFI 边界**：每个 `extern "C"` / JNI 入口用 `std::panic::catch_unwind` 包住，失败转成错误码或抛 Java 异常；跨边界展开从 Rust 1.81 起会直接 abort 整个进程（更早的版本是未定义行为）。
- 每个 `unsafe` 块上方写 `// SAFETY:` 注释，说明它依赖的前提为什么成立；说不清前提就不要写 `unsafe`。
- 异步运行时（如 tokio）里不调阻塞 I/O、`std::thread::sleep` 或嵌套 `block_on`；重活放 `spawn_blocking`，否则会卡住整个执行器。
- `cdylib` 与二进制 crate 的 `Cargo.lock` 要提交；加依赖改 `Cargo.toml`，不手改 lock 文件。
- 改完先跑 `cargo check`，再如实汇报结果；没跑过的写「未验证」，不要谎报通过。

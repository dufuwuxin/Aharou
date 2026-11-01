package com.aharou.core.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 取消路径上的收尾：未取消时照常切到 IO 后台执行，已取消时在这个线程上就地跑完。
 *
 * 已经处于取消中的协程里再做 `withContext` 会挂起一次，恢复点上 ART 的
 * AssertNoPendingException 会看到那个还没消化的取消异常，直接把进程 abort
 * （Abort message: No pending exception expected: JobCancellationException:
 * StandaloneCoroutine was cancelled）。debug 构建跑解释器必现，release 经过 R8
 * 优化后不经过那个检查点，所以只在测试包上看得到。
 *
 * 取消路径上也不再换调度器：`runBlocking(Dispatchers.IO)` 会把 block 甩到另一条线程，
 * 收尾里的落库/日志一旦有续体压在单线程调度器（runTest 的 TestDispatcher、
 * Robolectric 主线程）上等着被推进，两边就互等——CI 的单测步骤整体挂住，
 * 直到把整步耗死也不报是哪条用例。改用无参数的 `runBlocking`：block 在 runBlocking
 * 自己新建的协程里执行（不是已取消的那个，因此不会触发上面的 abort），
 * 且完整跑在调用线程的事件循环上，跟外层调度器没有任何跨线程等待关系。
 *
 * 阻塞只在取消路径上发生一次，代价是调用线程停一下；正常路径行为不变。
 */
suspend fun runCancellationSafeTail(block: suspend () -> Unit) {
    if (currentCoroutineContext().isActive) {
        withContext(NonCancellable + Dispatchers.IO) { block() }
    } else {
        runBlocking { block() }
    }
}

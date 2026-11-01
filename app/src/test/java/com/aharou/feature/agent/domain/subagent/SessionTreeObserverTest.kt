package com.aharou.feature.agent.domain.subagent

import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.presentation.MessageRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionTreeObserver] 的用例：树解析、越权边界、摘要渲染。
 *
 * 全部用内存假实现，不依赖 MockK——本沙箱里 MockK 的字节码注入被拦（`JvmMockKGateway` 初始化失败），
 * 用假实现才能本地跑通；观察逻辑本身也不该需要 mock 框架。
 */
class SessionTreeObserverTest {

    // ── 假实现 ────────────────────────────────────────────

    private class FakeTree(private val nodes: Map<String, TreeSession>) : SessionTreeSource {
        override suspend fun find(sessionId: String) = nodes[sessionId]
        override suspend fun childrenOf(parentId: String) =
            nodes.values.filter { it.parentId == parentId }.sortedBy { it.title }
    }

    private class FakeTranscript(private val bysession: Map<String, List<AgentMessageEntity>>) :
        SessionTranscriptSource {
        override suspend fun messages(sessionId: String) = bysession[sessionId].orEmpty()
    }

    /**
     * 树结构：
     *   root(主会话)
     *     ├─ 审查员(a)
     *     │    └─ 子审查项(a1)
     *     └─ 实现者(b)
     *   另一棵树：other-root → other-child
     */
    private val nodes = mapOf(
        "root" to TreeSession("root", null, "主会话"),
        "a" to TreeSession("a", "root", "代码审查员", "code-reviewer"),
        "b" to TreeSession("b", "root", "实现者", "engineer"),
        "a1" to TreeSession("a1", "a", "子审查项", "code-reviewer"),
        "other-root" to TreeSession("other-root", null, "别的主会话"),
        "other-child" to TreeSession("other-child", "other-root", "别处的成员")
    )

    private fun message(
        sessionId: String,
        role: MessageRole,
        content: String,
        toolCallsJson: String? = null,
        toolName: String? = null,
        isError: Boolean = false
    ) = AgentMessageEntity(
        id = "m-${sessionId}-${role.name}-${content.hashCode()}",
        sessionId = sessionId,
        role = role.name,
        content = content,
        timestamp = content.hashCode().toLong(),
        toolCallsJson = toolCallsJson,
        toolName = toolName,
        isError = isError
    )

    private fun observer(transcript: Map<String, List<AgentMessageEntity>> = emptyMap()) =
        SessionTreeObserver(FakeTree(nodes), FakeTranscript(transcript))

    // ── 树解析 ────────────────────────────────────────────

    @Test
    fun `向上取祖先链，由近及远且层数为止`() = runBlocking {
        val up = observer().upward("a1", 5)
        assertEquals(listOf("a", "root"), up.map { it.session.id })
        assertEquals(listOf(-1, -2), up.map { it.depth })
    }

    @Test
    fun `向下取子树，按层展开`() = runBlocking {
        val down = observer().downward("root", 1)
        assertEquals(setOf("a", "b"), down.map { it.session.id }.toSet())
        assertEquals(listOf(1, 1), down.map { it.depth })

        val deep = observer().downward("root", 2)
        assertTrue(deep.any { it.session.id == "a1" && it.depth == 2 })
    }

    @Test
    fun `根节点解析与同树判定`() = runBlocking {
        val observer = observer()
        assertEquals("root", observer.rootOf("a1"))
        assertTrue(observer.inSameTree("a1", "b"))
        assertFalse(observer.inSameTree("a1", "other-child"))
        // 自己与自己在同一棵树，跨树的两棵树互不可见
        assertTrue(observer.inSameTree("a", "a"))
    }

    @Test
    fun `脏数据成环时根节点解析不挂死`() = runBlocking {
        val cyclic = mapOf(
            "x" to TreeSession("x", "y", "环一"),
            "y" to TreeSession("y", "x", "环二")
        )
        val observer = SessionTreeObserver(FakeTree(cyclic), FakeTranscript(emptyMap()))
        // 走到重复节点就收敛，返回链上某个 id，不死循环。
        assertTrue(observer.rootOf("x") in setOf("x", "y"))
    }

    // ── 越权边界 ──────────────────────────────────────────

    @Test
    fun `按标题解析目标，命中多个时给出候选而不是替模型猜`() = runBlocking {
        val observer = observer()
        val single = observer.resolveTarget("root", "实现者")
        assertTrue(single is TargetResolution.Found)
        assertEquals("b", (single as TargetResolution.Found).session.id)

        // 「别处的成员」虽然在 byId 里，但不在同一棵树 → 树内搜索找不到它
        val crossTree = observer.resolveTarget("root", "别处的成员")
        assertTrue(crossTree is TargetResolution.NotFound || crossTree is TargetResolution.OutOfTree)
    }

    @Test
    fun `跨树直接给 id 的观察请求被判定为越权`() = runBlocking {
        val observer = observer()
        val result = observer.resolveTarget("root", "other-child")
        assertTrue(result is TargetResolution.OutOfTree)
    }

    // ── 摘要渲染 ──────────────────────────────────────────

    @Test
    fun `渲染摘要：正文截断，工具参数折叠，工具结果带成败`() = runBlocking {
        val longText = "很长的正文".repeat(100)
        val longArg = "println(1)".repeat(50)
        val transcript = mapOf(
            "a" to listOf(
                message("a", MessageRole.USER, "请审查这个改动"),
                message(
                    "a",
                    MessageRole.ASSISTANT,
                    longText,
                    toolCallsJson = """[{"id":"c1","name":"readFile","arguments":{"path":"app/Main.kt","content":"$longArg"}}]"""
                ),
                message("a", MessageRole.TOOL, "文件读取成功", toolName = "readFile")
            )
        )
        val rendered = observer(transcript).render(
            ObservedMember(nodes.getValue("a"), 1), "下级·第 1 层", recent = 10, detail = ObserveDetail.SUMMARY
        )

        assertTrue(rendered.contains("任务：请审查这个改动"))
        assertTrue(rendered.contains("readFile(path=\"app/Main.kt\""))
        // 长参数折叠成「共 N 字」，不把整段内容灌进来
        assertTrue(rendered.contains("共") && rendered.contains("字"))
        assertTrue(rendered.contains("readFile 成功: 文件读取成功"))
        // 正文被截断，长度远小于原文
        assertTrue(rendered.length < longText.length)
    }

    @Test
    fun `只有工具粒度时不出正文，只有文本粒度时不出工具`() = runBlocking {
        val transcript = mapOf(
            "b" to listOf(
                message("b", MessageRole.USER, "干活"),
                message(
                    "b", MessageRole.ASSISTANT, "我来改文件",
                    toolCallsJson = """[{"id":"c1","name":"editFile","arguments":{"path":"a.kt"}}]"""
                )
            )
        )
        val toolsOnly = observer(transcript).render(
            ObservedMember(nodes.getValue("b"), 1), "下级·第 1 层", 10, ObserveDetail.TOOLS
        )
        assertTrue(toolsOnly.contains("editFile(path=\"a.kt\")"))
        assertFalse(toolsOnly.contains("我来改文件"))

        val textOnly = observer(transcript).render(
            ObservedMember(nodes.getValue("b"), 1), "下级·第 1 层", 10, ObserveDetail.TEXT
        )
        assertTrue(textOnly.contains("我来改文件"))
        assertFalse(textOnly.contains("editFile("))
    }

    @Test
    fun `最后一个工具没返回时提示可能卡住`() = runBlocking {
        val transcript = mapOf(
            "b" to listOf(
                message("b", MessageRole.USER, "跑测试"),
                message("b", MessageRole.TOOL, "[running] 执行中", toolName = "Bash")
            )
        )
        val rendered = observer(transcript).render(
            ObservedMember(nodes.getValue("b"), 1), "下级·第 1 层", 10, ObserveDetail.SUMMARY
        )
        assertTrue(rendered.contains("可能卡在这里"))
    }

    @Test
    fun `空会话给出明确提示而不是空字符串`() = runBlocking {
        val rendered = observer().render(
            ObservedMember(nodes.getValue("b"), 1), "下级·第 1 层", 10, ObserveDetail.SUMMARY
        )
        assertTrue(rendered.contains("还没有内容"))
    }
}

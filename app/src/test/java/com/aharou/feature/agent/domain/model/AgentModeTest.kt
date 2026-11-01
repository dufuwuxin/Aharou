package com.aharou.feature.agent.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [AgentMode] 的语义锁：重点是 **ALLOW 与 AUTO 必须是两档**，且子会话只能被收窄、不能被放宽。
 *
 * 为什么要单独锁：ALLOW 只在「用户手动切换」时出现，AUTO 却是新会话默认档。一旦有人把
 * ALLOW 当成「AUTO 的别名」（例如把 `strictness` 改成与 AUTO 相同的 0，或在 `limitedBy`
 * 里把 ALLOW 提前 return），完全放行就会悄悄变成默认档，安全防护（灾难性 rm、Shizuku 逐次
 * 确认）随之失效，而代码评审时这种回归极难看出来。
 *
 * ## 关于 [com.aharou.feature.agent.domain.permission.ToolPermissionPolicyEngine] 为什么没有在这里测
 *
 * 本类只断言纯 Kotlin 的档位收敛语义，**没有**覆盖策略引擎里 `AUTO || ALLOW` 与
 * `safetyDisabled = mode == ALLOW || ...` 这两处分支，原因如下：
 *
 * 1. 引擎的构造参数是两个**具体类**，都无法在纯 JVM 里造出来：
 *    - `ToolSafetySettingsRepository` 是 `final class`，构造参数是 Android `Context`，且属性在
 *      init 里就 `context.toolSafetyDataStore`（androidx datastore 扩展）——既不能传假 Context
 *      （本沙盒无 Robolectric Context 可用），也不能继承伪造（类非 open、无接口）；
 *    - `PermissionRulesRepository` 同样是 `final class`，依赖 `Context` + `WorkspaceRepository`
 *      + `ProjectAharouRoot` + `FileChangeHub`，同样没有接口可注入。
 * 2. 本沙箱禁用 MockK（字节码注入被拦），而工程里这两个依赖也**没有**现成的 fake/接口实现
 *    可以复用——`SessionTreeObserverTest` 能用内存假实现，是因为它依赖的
 *    `SessionTreeSource` / `SessionTranscriptSource` 本就是接口。
 *
 * 要让引擎可测，需要的最小重构（不在本次范围内）：
 * - 抽一个接口 `interface ToolSafetySettings { suspend fun isSafetyInterceptionDisabled(): Boolean }`，
 *   让 `ToolSafetySettingsRepository` 实现它，引擎构造参数改成该接口；
 * - 同样给 `PermissionRulesRepository` 抽出只含 `loadEffectiveFor` / `add` 的接口（或给引擎注入
 *   `(workspacePath) -> List<PermissionRule>` 这样的函数式依赖）；
 * 之后即可用两个内存假实现构造引擎，断言 ALLOW 下 `Bash` 灾难性 `rm` 与 `Shizuku` 都返回 ALLOW。
 */
class AgentModeTest {

    /**
     * 独立于实现的严格度期望表：ALLOW 最宽松（-1）→ AUTO（0）→ BUILD（1）→ PLAN（2）最严。
     * 刻意在测试里重新写一遍，而不是从 `AgentMode` 里读，这样实现被改动时这里不会跟着变。
     */
    private val expectedStrictness: Map<AgentMode, Int> = mapOf(
        AgentMode.ALLOW to -1,
        AgentMode.AUTO to 0,
        AgentMode.BUILD to 1,
        AgentMode.PLAN to 2
    )

    /** 参照实现：取两者中更严的一档。 */
    private fun stricter(a: AgentMode, b: AgentMode): AgentMode =
        if (expectedStrictness.getValue(a) >= expectedStrictness.getValue(b)) a else b

    // ── 1. ALLOW 与 AUTO 是两档，且收窄取更严的一方 ──────────────────────

    /**
     * ⚠️ 语义裁决点，改动前请先看这里。
     *
     * 任务书原本要求断言 `ALLOW.limitedBy(AUTO) == ALLOW`（「ALLOW 是最宽松，不被 AUTO 收窄」），
     * 但这条与 [AgentMode.limitedBy] 的既有实现、以及「父会话 PLAN 时子代理声明 ALLOW 也不能提权」
     * 这另一条要求**互相矛盾**：
     *   `limitedBy` 的定义是「取两者中更严的一档」，而 AUTO 比 ALLOW 更严
     *   （ALLOW 关掉安全防护，AUTO 保留），所以 `ALLOW.limitedBy(AUTO)` 按定义只能是 AUTO。
     *   若它真的是 ALLOW，那 ALLOW 就变成了「不可收窄」，PLAN 那条也会跟着失效。
     *
     * 因此这里按**实现的真实语义**断言，并用 [assertNotEquals] 额外钉住「两者绝不相等」这个
     * 真正要防的回归（ALLOW 被改成 AUTO 的别名）。
     */
    @Test
    fun `ALLOW 与 AUTO 是不同两档，收窄时取更严的 AUTO`() {
        // 两者绝不能被合并成同一档：这是本文件存在的首要原因。
        assertNotEquals(AgentMode.ALLOW, AgentMode.AUTO)
        assertNotEquals(expectedStrictness[AgentMode.ALLOW], expectedStrictness[AgentMode.AUTO])

        // 「取更严」：AUTO 保留安全防护，故无论方向如何，结果都应落在 AUTO 上。
        assertSame(AgentMode.AUTO, AgentMode.ALLOW.limitedBy(AgentMode.AUTO))
        assertSame(AgentMode.AUTO, AgentMode.AUTO.limitedBy(AgentMode.ALLOW))
    }

    /** 自己收敛自己恒等，否则 [limitedBy] 会变成有损运算。 */
    @Test
    fun `任何档位收敛自身都得到自身`() {
        for (mode in AgentMode.values()) {
            assertSame("$mode.limitedBy($mode) 应为其自身", mode, mode.limitedBy(mode))
        }
    }

    // ── 2. 严格度排序 ALLOW < AUTO < BUILD < PLAN 的四组抽样 ─────────────

    /** 抽样一：ALLOW < AUTO（ALLOW 最宽松，被 AUTO 收窄成 AUTO）。 */
    @Test
    fun `严格度抽样 ALLOW 小于 AUTO`() {
        assertSame(AgentMode.AUTO, AgentMode.ALLOW.limitedBy(AgentMode.AUTO))
        assertSame(AgentMode.AUTO, AgentMode.AUTO.limitedBy(AgentMode.ALLOW))
    }

    /** 抽样二：AUTO < BUILD（子代理声明 AUTO 时，BUILD 父会话把它收窄成 BUILD）。 */
    @Test
    fun `严格度抽样 AUTO 小于 BUILD`() {
        assertSame(AgentMode.BUILD, AgentMode.AUTO.limitedBy(AgentMode.BUILD))
        assertSame(AgentMode.BUILD, AgentMode.BUILD.limitedBy(AgentMode.AUTO))
    }

    /** 抽样三：BUILD < PLAN（PLAN 父会话下，声明 BUILD 的子代理只能只读）。 */
    @Test
    fun `严格度抽样 BUILD 小于 PLAN`() {
        assertSame(AgentMode.PLAN, AgentMode.BUILD.limitedBy(AgentMode.PLAN))
        assertSame(AgentMode.PLAN, AgentMode.PLAN.limitedBy(AgentMode.BUILD))
    }

    /** 抽样四：ALLOW < PLAN（跨两档也不能提权）。 */
    @Test
    fun `严格度抽样 ALLOW 小于 PLAN`() {
        assertSame(AgentMode.PLAN, AgentMode.ALLOW.limitedBy(AgentMode.PLAN))
        assertSame(AgentMode.PLAN, AgentMode.PLAN.limitedBy(AgentMode.ALLOW))
    }

    /** 两端极值：ALLOW 被任何父档收窄后都不再是 ALLOW；PLAN 不被任何声明放宽。 */
    @Test
    fun `ALLOW 是唯一最宽松档，PLAN 是唯一最严档`() {
        for (parent in AgentMode.values()) {
            // ALLOW 声明遇上任何父档：结果都是父档（父为 ALLOW 时仍是 ALLOW）。
            assertSame(parent, AgentMode.ALLOW.limitedBy(parent))
            // 任何声明遇上 PLAN 父档：结果恒为 PLAN，声明再宽也提不了权。
            assertSame(AgentMode.PLAN, parent.limitedBy(AgentMode.PLAN))
        }
    }

    /**
     * 全量 16 组交叉校验：结果必须等于按期望严格度表取更严的一档。
     * 这一条能同时抓出「改错了 strictness 数值」和「补了新档位却没更新排序」两类错误。
     */
    @Test
    fun `全量交叉校验收窄结果都等于按严格度取更严的一档`() {
        val all = AgentMode.values()
        for (child in all) {
            for (parent in all) {
                assertSame(
                    "${child}.limitedBy($parent) 应取更严的一档",
                    stricter(child, parent),
                    child.limitedBy(parent)
                )
                // 收敛必须可交换：谁声明、谁是父，取到的更严档应一致（PLAN 那条越权防线依赖此性质）。
                assertSame(
                    "${child}.limitedBy($parent) 与反向结果应一致",
                    child.limitedBy(parent),
                    parent.limitedBy(child)
                )
            }
        }
    }

    // ── 3. 父会话 PLAN 时子代理声明 ALLOW 不能提权（越权防线）────────────

    /**
     * 越权场景：父会话处于 PLAN（只读），子代理定义里写 `mode: allow` 也不能拿到写权限。
     * 这是 plan 模式「物理沙盒」防线的最后一环，破了就等于 PLAN 形同虚设。
     */
    @Test
    fun `父会话 PLAN 时子代理声明 ALLOW 不能提权`() {
        assertSame(AgentMode.PLAN, AgentMode.ALLOW.limitedBy(AgentMode.PLAN))
        // 顺带钉住同一场景下的另外两档，避免只修好 ALLOW 一条而漏掉它们。
        assertSame(AgentMode.PLAN, AgentMode.AUTO.limitedBy(AgentMode.PLAN))
        assertSame(AgentMode.PLAN, AgentMode.BUILD.limitedBy(AgentMode.PLAN))
    }

    // ── 4. 档位名字集合与顺序固定 ──────────────────────────────────────

    /**
     * 名字与**声明顺序**都锁住：别的地方（数据库迁移、会话恢复、设置页选项、子代理定义解析）
     * 存在按名字或按序号映射的代码，顺序一挪就会静默错位。
     */
    @Test
    fun `四个档位的名字与顺序固定为 BUILD PLAN AUTO ALLOW`() {
        assertEquals(
            listOf("BUILD", "PLAN", "AUTO", "ALLOW"),
            AgentMode.values().map { it.name }
        )
    }

    /** 档位数量锁死为 4：新增档位必须回来更新本文件与上面的严格度表。 */
    @Test
    fun `档位数量为四，新增档位必须同步更新严格度表`() {
        assertEquals(4, AgentMode.values().size)
        assertEquals(
            "每个档位都必须在期望严格度表里有位置",
            AgentMode.values().toSet(),
            expectedStrictness.keys
        )
    }

    /** ALLOW 必须排在最后：依赖「最后一个是全放行」的下标式写法（如设置页单选序号）不会错位。 */
    @Test
    fun `ALLOW 声明在枚举最后一位`() {
        assertSame(AgentMode.ALLOW, AgentMode.values().last())
    }
}

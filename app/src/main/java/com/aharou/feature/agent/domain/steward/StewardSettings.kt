package com.aharou.feature.agent.domain.steward

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智能托管的开关与轮数上限，**按会话隔离**：A 会话开托管不影响 B 会话。
 *
 * [StewardController] 只依赖这个接口，不依赖带 Android Context 的 [StewardSettings]——
 * 每会话隔离、轮次记账、判定互斥这些行为都能用纯 JVM 的假实现测，不必把单测拖进 Robolectric。
 */
interface StewardSettingsStore {

    /** 该会话是否开着托管。 */
    fun enabled(sessionId: String): Boolean

    fun setEnabled(sessionId: String, on: Boolean)

    /** 该会话的自动续发轮数上限，收敛到 [StewardSettings.MIN_LOOPS]..[StewardSettings.MAX_LOOPS]。 */
    fun maxLoops(sessionId: String): Int

    fun setMaxLoops(sessionId: String, value: Int)

    /** 该会话是否不限轮数（仍受控制器里的安全阀约束）。 */
    fun unlimited(sessionId: String): Boolean

    fun setUnlimited(sessionId: String, value: Boolean)

    /** 会话被删除时清掉它的全部键，别在偏好文件里留垃圾。 */
    fun forgetSession(sessionId: String)

    /** 把所有会话的「开着」收回 false（启动时回收，见 [StewardController] 的 init）。 */
    fun clearAllEnabled()

    /** 不持久化、恒为关闭的占位实现：纯逻辑测试与不关心托管的调用方用。 */
    object Noop : StewardSettingsStore {
        override fun enabled(sessionId: String): Boolean = false
        override fun setEnabled(sessionId: String, on: Boolean) = Unit
        override fun maxLoops(sessionId: String): Int = StewardSettings.DEFAULT_MAX_LOOPS
        override fun setMaxLoops(sessionId: String, value: Int) = Unit
        override fun unlimited(sessionId: String): Boolean = false
        override fun setUnlimited(sessionId: String, value: Boolean) = Unit
        override fun forgetSession(sessionId: String) = Unit
        override fun clearAllEnabled() = Unit
    }
}

/**
 * [StewardSettingsStore] 的默认实现，存 SharedPreferences。
 *
 * 存本机偏好而不是记忆目录：这是本机的运行开关，写进记忆目录会被同步到别的设备上。
 * 键形如 `steward_enabled@<sessionId>`——会话 id 是 UUID，不含 `@`，所以分隔符不会与 id 本身冲突；
 * 键里除会话 id 外不塞别的信息，[forgetSession] 靠后缀枚举就能精确清理。
 */
@Singleton
class StewardSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : StewardSettingsStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        // 旧的全局开关已被每会话开关取代，这里清掉那三个裸键，避免升级后行为漂移：
        // 留着的话它们虽然不再被读，仍会占着偏好文件、也会误导手动排查的人。
        prefs.edit()
            .remove(KEY_ENABLED)
            .remove(KEY_MAX_LOOPS)
            .remove(KEY_UNLIMITED)
            .apply()
    }

    override fun enabled(sessionId: String): Boolean =
        prefs.getBoolean(key(KEY_ENABLED, sessionId), false)

    override fun setEnabled(sessionId: String, on: Boolean) {
        prefs.edit().putBoolean(key(KEY_ENABLED, sessionId), on).apply()
    }

    override fun maxLoops(sessionId: String): Int =
        prefs.getInt(key(KEY_MAX_LOOPS, sessionId), DEFAULT_MAX_LOOPS).coerceIn(MIN_LOOPS, MAX_LOOPS)

    override fun setMaxLoops(sessionId: String, value: Int) {
        prefs.edit().putInt(key(KEY_MAX_LOOPS, sessionId), value.coerceIn(MIN_LOOPS, MAX_LOOPS)).apply()
    }

    override fun unlimited(sessionId: String): Boolean =
        prefs.getBoolean(key(KEY_UNLIMITED, sessionId), false)

    override fun setUnlimited(sessionId: String, value: Boolean) {
        prefs.edit().putBoolean(key(KEY_UNLIMITED, sessionId), value).apply()
    }

    override fun forgetSession(sessionId: String) {
        if (sessionId.isBlank()) return
        val suffix = SEPARATOR + sessionId
        val doomed = prefs.all.keys.filter { it.endsWith(suffix) }
        if (doomed.isEmpty()) return
        prefs.edit().apply { doomed.forEach { remove(it) } }.apply()
    }

    override fun clearAllEnabled() {
        val prefix = KEY_ENABLED + SEPARATOR
        val doomed = prefs.all.keys.filter { it.startsWith(prefix) }
        if (doomed.isEmpty()) return
        prefs.edit().apply { doomed.forEach { remove(it) } }.apply()
    }

    /** 会话维度的键：`<基础名>@<sessionId>`。 */
    private fun key(base: String, sessionId: String): String = base + SEPARATOR + sessionId

    companion object {
        const val DEFAULT_MAX_LOOPS = 5
        const val MIN_LOOPS = 1
        const val MAX_LOOPS = 20

        private const val PREFS = "aharou_steward_prefs"

        /** 三个基础名：不带分隔符时正是旧版的全局键，见 [StewardSettings.init]。 */
        private const val KEY_ENABLED = "steward_enabled"
        private const val KEY_MAX_LOOPS = "steward_max_loops"
        private const val KEY_UNLIMITED = "steward_unlimited"

        /** 会话维度分隔符：UUID 不含 `@`，不会与 id 本身混淆。 */
        private const val SEPARATOR = "@"
    }
}

/** 把 [StewardSettings] 绑到控制器依赖的 [StewardSettingsStore]（同 SubAgentSettings 的既有做法）。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StewardSettingsModule {

    @Binds
    abstract fun bindStewardSettingsStore(settings: StewardSettings): StewardSettingsStore
}

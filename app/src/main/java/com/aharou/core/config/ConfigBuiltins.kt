package com.aharou.core.config

import android.content.Context
import com.aharou.core.config.fields.ClosureField
import com.aharou.core.config.fields.DataStoreStrField
import com.aharou.core.identity.UserIdentityStore
import com.aharou.core.soul.SoulBodyLimitCheck
import com.aharou.core.soul.SoulFile
import com.aharou.core.soul.SoulMetadata
import com.aharou.core.soul.SoulStore
import kotlinx.coroutines.flow.map

/**
 * 内置配置字段注册（自 上游项目 移植 · 分批扩充）。
 *
 * 第一批：`soul`（人格）主题——正文 / 名字 / 头像 / 语言。
 * 后续批次：envvars / models / groups / thinkingrules 等（接 AiCode 自有存储）。
 */
object ConfigBuiltins {

    fun registerInto(registry: ConfigRegistry, context: Context) {
        val appContext = context.applicationContext
        val memoryStore = com.aharou.core.memory.AharouMemoryStore(appContext)
        com.aharou.core.memory.NativeProfileMemory.migrate(memoryStore.dir, memoryStore.isMemoryEnabled())

        registry.register(
            ClosureField(
                path = "soul.body",
                displayName = "人格正文",
                description = "记忆库「人格正文」条目的兼容编辑入口；注入方式、停用和删除在记忆库管理。",
                valueSchema = ConfigSchema.Str(maxLength = 40000),
                access = ConfigAccess.READWRITE,
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = { ConfigValue.Str(com.aharou.core.memory.NativeProfileMemory.read(memoryStore.dir, com.aharou.core.memory.NativeProfileMemory.BODY)) },
                writer = { v ->
                    val body = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val limit = SoulStore.isOverLimit(body)
                    if (limit.isOverLimit) {
                        val msg = when (limit) {
                            is SoulBodyLimitCheck.OverLimitChinese ->
                                "超中文上限（${limit.chars}/${limit.cap} 字）"
                            is SoulBodyLimitCheck.OverLimitEnglish ->
                                "超英文上限（${limit.words}/${limit.cap} 词）"
                            else -> "超限"
                        }
                        throw ConfigError.InvalidValue("人格正文$msg")
                    }
                    com.aharou.core.memory.NativeProfileMemory.write(memoryStore.dir,
                        com.aharou.core.memory.NativeProfileMemory.BODY, body)
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "soul.name",
                displayName = "名字",
                description = "聊天界面显示的名字（≤40 字）。",
                valueSchema = ConfigSchema.Str(maxLength = 40),
                revertable = true,
                reader = { ConfigValue.Str(SoulStore.load(appContext)?.metadata?.name.orEmpty()) },
                writer = { v ->
                    val name = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val current = SoulStore.load(appContext) ?: SoulFile(SoulMetadata.DEFAULT, "")
                    SoulStore.save(appContext, current.copy(metadata = current.metadata.copy(name = name.trim())))
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "soul.style",
                displayName = "风格",
                description = "记忆库「回复风格」条目的兼容编辑入口；不从旧人格文件另行注入。",
                valueSchema = ConfigSchema.Str(maxLength = 400),
                revertable = true,
                reader = { ConfigValue.Str(com.aharou.core.memory.NativeProfileMemory.read(memoryStore.dir, com.aharou.core.memory.NativeProfileMemory.STYLE)) },
                writer = { v ->
                    val style = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    com.aharou.core.memory.NativeProfileMemory.write(memoryStore.dir,
                        com.aharou.core.memory.NativeProfileMemory.STYLE, style.trim())
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "soul.icon",
                displayName = "头像",
                description = "emoji 或图片（data URI / 路径）；空 = 默认。",
                valueSchema = ConfigSchema.Str(maxLength = 200000),
                revertable = true,
                reader = { ConfigValue.Str(SoulStore.load(appContext)?.metadata?.icon.orEmpty()) },
                writer = { v ->
                    val icon = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val current = SoulStore.load(appContext) ?: SoulFile(SoulMetadata.DEFAULT, "")
                    SoulStore.save(appContext, current.copy(metadata = current.metadata.copy(icon = icon.trim())))
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "soul.lang",
                displayName = "语言",
                description = "回复语言：auto / zh / en。",
                valueSchema = ConfigSchema.StrEnum(listOf("auto", "zh", "en")),
                revertable = true,
                reader = { ConfigValue.Str(com.aharou.core.memory.NativeProfileMemory.read(memoryStore.dir,
                    com.aharou.core.memory.NativeProfileMemory.LANGUAGE).ifBlank { "auto" }) },
                writer = { v ->
                    val lang = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    com.aharou.core.memory.NativeProfileMemory.write(memoryStore.dir,
                        com.aharou.core.memory.NativeProfileMemory.LANGUAGE, lang)
                },
            ),
        )

        registry.register(
            ClosureField(
                path = "memory.core",
                displayName = "核心档案",
                description = "长期记忆的核心档案（CORE.md）——会注入系统提示；也可直接编辑容器内 /root/.aharou/memory/CORE.md。",
                valueSchema = ConfigSchema.Str(maxLength = 20000),
                revertable = true,
                reader = { ConfigValue.Str(memoryStore.readCore().orEmpty()) },
                writer = { v ->
                    val text = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    memoryStore.writeCore(text)
                },
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "identity.display_name",
                displayName = "我的昵称",
                description = "聊天里显示的你自己（用户）的名字，≤40 字；空 = 不显示昵称。",
                flow = UserIdentityStore.observe(appContext).map { it.name },
                setter = { UserIdentityStore.setName(appContext, it) },
                maxLength = 40,
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "identity.icon",
                displayName = "我的头像",
                description = "用户头像：emoji 字面量或 data:image/…;base64,…；空 = 默认头像。",
                flow = UserIdentityStore.observe(appContext).map { it.icon },
                setter = { UserIdentityStore.setIcon(appContext, it) },
                maxLength = 200000,
            ),
        )

        registry.register(
            ClosureField(
                path = "memory.enabled",
                displayName = "启用记忆",
                description = "普通记忆注入与自动整理开关，关闭后仍可读取文件；主代理和成员指令条目由记忆库各条目的使用方式独立控制。",
                valueSchema = ConfigSchema.Bool,
                access = ConfigAccess.READWRITE,
                revertable = true,
                reader = { ConfigValue.Bool(memoryStore.isMemoryEnabled()) },
                writer = { v ->
                    val on = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("boolean")
                    memoryStore.setMemoryEnabled(on)
                },
            ),
        )
    }
}

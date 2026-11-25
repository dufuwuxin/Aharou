package com.aharou.feature.settings.data

import com.aharou.core.config.ConfigAccess
import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.credentials.domain.model.GitCredential
import com.aharou.feature.credentials.domain.repository.CredentialRepository
import com.aharou.feature.git.data.GitHubTokenStore
import com.aharou.feature.git.domain.GitRepository
import com.aharou.feature.vault.VaultRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 凭据类设置 → 配置通道。
 *
 * 配置字段按真实存储形态接入：
 * - `git.github_token`：GitHub API 令牌，[GitHubTokenStore] 里按 host 存（值经 Keystore 加密），这里固定接 github.com 这一条。
 * - `vault.names`：通用凭据库的条目名列表，只读（理由见 [vaultNamesField]）。
 *
 * 令牌 / 口令一律 risk = [ConfigRisk.SENSITIVE]：读取被 `reveal` 的确认门挡住，写入走确认弹窗与审计。
 */
@Singleton
class ConfigCredentialFields @Inject constructor(
    private val githubTokens: GitHubTokenStore,
    private val vault: VaultRepository,
    private val containerSettings: com.aharou.feature.settings.data.repository.ContainerSettingsRepository,
    private val credentialRepository: CredentialRepository,
    private val gitRepository: GitRepository,
    /**
     * 工具授权规则。在这里顺带注册而不是单独挂到 AIEditorApp：那个文件卡着 1005 行的棘轮基线，
     * 再加一个注入属性就会顶破门禁，而两类字段本来就同属「授权与凭据」。
     */
    private val permissionFields: ConfigPermissionFields,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(gitHubTokenField())
        registry.register(gitUserNameField())
        registry.register(gitUserEmailField())
        registry.register(GitCredentialsCollection())
        permissionFields.registerInto(registry)
        registry.register(vaultNamesField())
        registry.register(ClosureField(
            path = "vault.keyword_names",
            displayName = "具名关键词替换",
            description = "选择凭据库条目名；开启出站脱敏后，条目原值会在新模型请求中精确替换为独立占位符，不授予工具读取原值的权限。",
            valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
            reader = { ConfigValue.Arr(vault.keywordNames.value.sorted().map { ConfigValue.Str(it) }) },
            writer = { value ->
                val names = (value as? ConfigValue.Arr)?.value?.map {
                    (it as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                }?.toSet() ?: throw ConfigError.TypeMismatch("array")
                if (names.any { it !in vault.names() }) throw ConfigError.InvalidValue("Unknown vault keyword name")
                if (!vault.setKeywordNames(names)) throw ConfigError.PermissionDenied("Keyword selection was not saved")
            },
        ))
        registry.register(ClosureField(
            path = "vault.privacy_redaction",
            displayName = "统一隐私脱敏",
            description = "与容器环境值脱敏共用一个设置。工具结果、日志与审计识别常见凭据、邮箱和大陆手机号；命令回传给 AI 时隐藏本次实际注入的环境变量值。默认开启；不修改原文件、登录认证或终端本地屏幕。",
            valueSchema = ConfigSchema.Bool,
            reader = { ConfigValue.Bool(runBlocking { containerSettings.redactEnvOutputFlow.first() }) },
            writer = { value ->
                val enabled = (value as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("boolean")
                runBlocking { containerSettings.setRedactEnvOutput(enabled) }
            },
        ))
    }

    private fun gitHubTokenField(): ConfigField = ClosureField(
        path = "git.github_token",
        displayName = "GitHub 令牌",
        description = "调用 GitHub API、读取仓库/PR 状态用的个人访问令牌（github.com）。留空即清除。",
        valueSchema = ConfigSchema.Str(),
        risk = ConfigRisk.SENSITIVE,
        revertable = true,
        reader = { ConfigValue.Str(githubTokens.token(GITHUB_HOST).orEmpty()) },
        writer = { v ->
            val token = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
            githubTokens.save(GITHUB_HOST, token)
        },
    )

    /**
     * 提交署名（`git config user.name`）。
     *
     * 与 [GitCredentialsCollection] 同属「凭据与署名」页，之前同样只能进页面手填：
     * 写入走 [GitRepository.setUserIdentity]，与 UI 一样遵「有项目级配项目级、否则写全局」。
     */
    private fun gitUserNameField(): ConfigField = ClosureField(
        path = "git.user_name",
        displayName = "提交署名（用户名）",
        description = "提交时写入的 user.name。当前仓库有项目级配置时写项目级，否则写全局。",
        valueSchema = ConfigSchema.Str(),
        revertable = true,
        reader = { ConfigValue.Str(runBlocking { gitRepository.getUserName() }) },
        writer = { v ->
            val name = (v as? ConfigValue.Str)?.value
                ?: throw ConfigError.TypeMismatch("string")
            runBlocking { gitRepository.setUserIdentity(name, "") }
        },
    )

    private fun gitUserEmailField(): ConfigField = ClosureField(
        path = "git.user_email",
        displayName = "提交署名（邮箱）",
        description = "提交时写入的 user.email。当前仓库有项目级配置时写项目级，否则写全局。",
        valueSchema = ConfigSchema.Str(),
        revertable = true,
        reader = { ConfigValue.Str(runBlocking { gitRepository.getUserEmail() }) },
        writer = { v ->
            val email = (v as? ConfigValue.Str)?.value
                ?: throw ConfigError.TypeMismatch("string")
            runBlocking { gitRepository.setUserIdentity("", email) }
        },
    )

    /**
     * 「凭据与署名」页里的 git 凭据，按主机一条。
     *
     * 之前只有 `memory.sync.token` 能写凭据，而它的主机由记忆仓库地址决定、用户名写死成 cnb，
     * 其它主机（如 github.com）只能去 UI 手填——设置项一律要接 config 通道，这是漏的那一块。
     */
    private inner class GitCredentialsCollection : ConfigCollection {
        override val basePath = "git.credentials"
        override val displayName = "Git 凭据"
        override val description =
            "按主机（如 github.com）保存 git 推送/拉取用的账号。子字段：host / username / token。" +
                "新增时传 {\"host\":\"github.com\",\"username\":\"...\",\"token\":\"...\"}。"

        override val addable = true
        override val removable = true

        override fun childIds(): List<String> = allCredentials().map { it.id }

        override fun fields(forId: String): List<ConfigField> {
            val credential = allCredentials().firstOrNull { it.id == forId } ?: return emptyList()
            return listOf(
                ClosureField(
                    path = "$basePath.$forId.host",
                    displayName = "主机（$forId）",
                    description = "凭据按主机匹配：push/pull 到哪个主机就用同名的那一条。",
                    valueSchema = ConfigSchema.Str(),
                    access = ConfigAccess.READONLY,
                    revertable = false,
                    reader = { ConfigValue.Str(credential.host) },
                    writer = { throw ConfigError.PermissionDenied("主机为只读字段，换主机请新建一条") },
                ),
                ClosureField(
                    path = "$basePath.$forId.username",
                    displayName = "用户名（$forId）",
                    description = "git 认证用户名。GitHub 用个人访问令牌时填账号名即可。",
                    valueSchema = ConfigSchema.Str(),
                    revertable = true,
                    reader = { ConfigValue.Str(credential.username) },
                    writer = { v ->
                        val name = (v as? ConfigValue.Str)?.value
                            ?: throw ConfigError.TypeMismatch("string")
                        saveCredential(credential.copy(username = name))
                    },
                ),
                ClosureField(
                    path = "$basePath.$forId.token",
                    displayName = "访问令牌（$forId）",
                    description = "该主机的密码或个人访问令牌。写空字符串即删除这条凭据。",
                    valueSchema = ConfigSchema.Str(maxLength = 512),
                    risk = ConfigRisk.SENSITIVE,
                    revertable = true,
                    reader = { ConfigValue.Str(credential.token) },
                    writer = { v ->
                        val token = (v as? ConfigValue.Str)?.value
                            ?: throw ConfigError.TypeMismatch("string")
                        if (token.isBlank()) {
                            runBlocking { credentialRepository.delete(credential.id) }
                        } else {
                            saveCredential(credential.copy(token = token))
                        }
                    },
                ),
            )
        }

        override fun add(payload: ConfigValue): String {
            val obj = (payload as? ConfigValue.Obj)?.value
                ?: throw ConfigError.InvalidValue("git.credentials 的新项需为 JSON 对象")
            val host = (obj["host"] as? ConfigValue.Str)?.value?.trim()?.lowercase()
                ?.takeIf { it.isNotBlank() }
                ?: throw ConfigError.InvalidValue("缺少 host（如 github.com）")
            val username = (obj["username"] as? ConfigValue.Str)?.value.orEmpty()
            val token = (obj["token"] as? ConfigValue.Str)?.value.orEmpty()
            saveCredential(GitCredential(id = host, host = host, username = username, token = token))
            return host
        }

        override fun remove(id: String) {
            runBlocking { credentialRepository.delete(id) }
        }
    }

    private fun allCredentials(): List<GitCredential> =
        runBlocking { credentialRepository.getAll().first() }

    private fun saveCredential(credential: GitCredential) {
        runBlocking { credentialRepository.save(credential) }
    }

    /**
     * 凭据库条目名列表，只读。
     *
     * 存储本身是整份 JSON 经 Keystore 加密后的单条 blob（`aharou_vault` 的 `blob` 键），
     * [VaultRepository] 只在内存里暴露 names/get/set/delete。这里刻意只暴露「有哪些名字」，
     * 不暴露值：凭据库的设计是模型只该看到 vault 占位符、真值由连接器在使用时解析，连凭据库页面
     * （VaultViewModel）也只列名字。值虽是敏感字段，但暴露出去等于给模型开一条读明文的旁路，
     * 与占位符机制冲突，故不接值。
     *
     * 不新增任何接口：仅调用既有的 [VaultRepository.names]。
     */
    private fun vaultNamesField(): ConfigField = ClosureField(
        path = "vault.names",
        displayName = "凭据库条目名",
        description = "凭据库里已保存条目的名字列表（只读）。要在参数里用某个凭据，写 vault 占位符（名字即凭据名）；值不在此暴露。",
        valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
        access = ConfigAccess.READONLY,
        revertable = false,
        reader = { ConfigValue.Arr(vault.names().map { ConfigValue.Str(it) }) },
        writer = { throw ConfigError.PermissionDenied("凭据库条目名为只读字段") },
    )

    private companion object {
        /** GitHub 公共站点 host，与 CredentialViewModel 的 GITHUB_HOST 同值。 */
        const val GITHUB_HOST = "github.com"
    }
}

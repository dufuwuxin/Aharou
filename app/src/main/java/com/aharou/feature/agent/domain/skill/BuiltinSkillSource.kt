package com.aharou.feature.agent.domain.skill

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.BuiltinAssetUpgradeCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Builtin instructions and executable resources share the same accepted, on-disk version. */
@Singleton
class BuiltinSkillSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val language: AppLanguageProvider,
) : SkillSource {

    override fun listSkills(): List<Skill> =
        runCatching {
            val files = selectedFiles()
            files.keys.filter { it.endsWith("/$SKILL_FILE") && it.count { char -> char == '/' } == 1 }
                .mapNotNull { path -> readSkill(path.substringBefore('/'), files) }
                .sortedBy { it.name.lowercase() }
        }.getOrElse { e ->
            FileLogger.w(TAG, "扫描内置技能失败：${e.message}")
            emptyList()
        }

    override fun loadInstructions(name: String): String? =
        listSkills().firstOrNull { it.name.equals(name, ignoreCase = true) }?.instructions

    internal fun resourceFiles(name: String): Map<String, ByteArray> {
        val files = selectedFiles()
        val directory = files.keys.filter { it.endsWith("/$SKILL_FILE") && it.count { char -> char == '/' } == 1 }
            .map { it.substringBefore('/') }.firstOrNull { readSkill(it, files)?.name == name }
            ?: return emptyMap()
        require(directory.matches(Regex("[A-Za-z0-9_-]+")))
        return files.filterKeys { it.startsWith("$directory/") }
    }

    /** 读单个内置技能目录；缺 SKILL.md 或解析失败返回 null，不影响其余技能。 */
    private fun selectedFiles(): Map<String, ByteArray> {
        val upgrade = BuiltinAssetUpgradeCoordinator.initialize(context)
        // Fresh installs may be read before the asynchronous startup extraction finishes.
        if (upgrade.canUpgrade) com.aharou.feature.agent.domain.container.ContainerInstaller.extractSkills(context)
        return upgrade.selectedFiles("skills")
    }

    private fun readSkill(dir: String, files: Map<String, ByteArray>): Skill? = runCatching {
        val text = files["$dir/$SKILL_FILE"]?.toString(Charsets.UTF_8) ?: return@runCatching null
        SkillParser.parseText(text, dir, language.promptCode)
    }.getOrElse { e ->
        FileLogger.w(TAG, "读取内置技能失败：$dir（${e.message}）")
        null
    }

    private companion object {
        const val TAG = "BuiltinSkillSource"
        const val SKILL_FILE = "SKILL.md"
    }
}

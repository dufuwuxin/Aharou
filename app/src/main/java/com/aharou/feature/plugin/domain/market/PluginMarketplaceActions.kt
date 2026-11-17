package com.aharou.feature.plugin.domain.market

import android.content.Context
import android.net.Uri
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.agent.domain.skill.SkillRepository
import com.aharou.feature.agent.domain.skill.SkillScope
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Shared UI/config consumer. Calling install/download must already have passed the caller's permission gate. */
@Singleton
class PluginMarketplaceActions internal constructor(
    private val context: Context,
    private val skills: SkillRepository,
    private val manager: PluginManager,
    private val files: FileAccessProvider,
    private val operit: OperitMarketRepository,
    engine: LinuxContainerEngine? = null,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        skills: SkillRepository,
        manager: PluginManager,
        files: FileAccessProvider,
        engine: LinuxContainerEngine,
    ) : this(context, skills, manager, files, OperitMarketRepository(context, skills), engine)
    private val dsh = DshMarketRepository(operit, engine?.let { DshContainerCatalog(it)::page })

    suspend fun query(source: String, query: String, page: Int = 1, type: String = "all"): JsonObject = withContext(Dispatchers.IO) {
        require(page > 0)
        when (source) {
            "operit" -> {
                val result = operit.page(type, page)
                buildJsonObject {
                    put("source", source); put("page", page); put("has_next", result.hasNext)
                    put("generated_at", result.generatedAt); put("query_scope", "current_page")
                    put("items", JsonArray(OperitCatalog.filter(result.items, query).map { Json.encodeToJsonElement(it) }))
                }
            }
            "dsh" -> {
                val result = dsh.pageResult(page)
                val entries = result.entries
                buildJsonObject {
                    put("source", source); put("page", page); put("query_scope", "current_page")
                    put("transport", result.transport)
                    put("catalog_url", DshMarketRepository.HOME)
                    put("compatibility", "Catalog metadata is not proof of native Aharou compatibility. DSH CLI packages require a separate runtime and are not installed by this app.")
                    put("items", JsonArray(entries.filter { entry -> query.isBlank() ||
                        listOf("fullName", "nameZh", "description", "descriptionZh", "category").any {
                            entry[it]?.jsonPrimitive?.contentOrNull?.contains(query.trim(), true) == true
                        } }))
                }
            }
            else -> error("unknown_market_source")
        }
    }

    private suspend fun resolve(source: String, id: String, page: Int, type: String, assetId: String?): OperitDownload = when (source) {
        "operit" -> {
            val entry = operit.page(type, page).items.singleOrNull { it.id == id } ?: error("market_item_not_on_page")
            val asset = assetId?.let { wanted -> entry.latestAssets.singleOrNull { it.id == wanted } ?: error("unknown_latest_asset") }
                ?: entry.latestAssets.singleOrNull()
            require(asset != null || entry.latestAssets.isEmpty()) { "choose_asset_id" }
            operit.resolve(entry, asset)
        }
        "dsh" -> {
            val entry = dsh.page(page).singleOrNull { it["fullName"]?.jsonPrimitive?.contentOrNull == id }
                ?: error("market_item_not_on_page")
            val url = entry.getValue("htmlUrl").jsonPrimitive.content
            operit.resolve(OperitEntry(id, "package", source = OperitSource("github_repo", url)), null)
        }
        else -> error("unknown_market_source")
    }

    /** Downloads can be retained even when the package is incompatible. No downloaded shell commands are run. */
    suspend fun perform(
        action: String, source: String, id: String, page: Int = 1, type: String = "all",
        assetId: String? = null, scope: SkillScope = SkillScope.GLOBAL,
        destination: String? = null, document: Uri? = null,
        workspaceRoot: String? = null, beforeMutation: () -> Unit = {}, authorizeWrite: (String) -> Unit = {},
    ): JsonObject = withContext(Dispatchers.IO) {
        require(action == "download" || action == "install") { "unknown_market_action" }
        val access = if (document == null) (workspaceRoot?.let(files::forWorkspace) ?: files).checked() else null
        val download: OperitDownload
        val staged: File
        try {
            download = resolve(source, id, page, type, assetId)
            staged = operit.stage(download, if (source == "dsh") DshMarketRepository.HOME else "https://operit.app/")
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { throw IllegalStateException("market_download_failed", failure) }
        try {
            val actualHash = staged.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            beforeMutation()
            if (action == "download") {
                check(destination != null || document != null) { "download_destination_required" }
                if (document != null) {
                    val output = context.contentResolver.openOutputStream(document, "wt") ?: error("document_unavailable")
                    output.use { target -> staged.inputStream().use { it.copyTo(target) } }
                } else {
                    val bound = requireNotNull(access)
                    val target = bound.toDisplayPath(requireNotNull(destination))
                    authorizeWrite(target)
                    val parent = requireNotNull(bound.parentPath(target)) { "download_parent_required" }
                    bound.requireResourcePath(parent, target.substringAfterLast('/'))
                    staged.inputStream().use { bound.writeStream(target, it, overwrite = false) }
                }
                return@withContext buildJsonObject {
                    put("status", "downloaded"); put("name", download.name); put("sha256", actualHash)
                    put("publisher_hash_verified", !download.sha256.isNullOrBlank())
                    put("path", destination ?: document.toString())
                }
            }
            val installed = installStaged(staged, download, scope, workspaceRoot, authorizeWrite, access)
            buildJsonObject {
                put("status", "installed"); put("id", installed); put("sha256", actualHash)
                put("publisher_hash_verified", !download.sha256.isNullOrBlank())
            }
        } finally { staged.delete() }
    }

    internal suspend fun installStaged(
        staged: File, download: OperitDownload, scope: SkillScope, workspaceRoot: String? = null,
        authorizeWrite: (String) -> Unit = {}, access: com.aharou.feature.workspace.domain.FileAccessProvider? = null,
    ): String {
        require(scope != SkillScope.BUILTIN)
        if (download.name.endsWith(".md", true)) {
            val report = skills.importMarkdown(staged.readText(Charsets.UTF_8), download.name.substringBeforeLast('.'), scope, expectedWorkspace = workspaceRoot, authorizeWrite = authorizeWrite, boundAccess = access)
            check(report.fatal == null && report.failures.isEmpty() && report.imported.isNotEmpty()) { "skill_import_failed:$report" }
            return report.imported.joinToString(",")
        }
        check(download.name.endsWith(".zip", true) || download.name.endsWith(".txplugin", true)) {
            "unsupported_format: Native txplugin/skill archives only; DSH CLI runtime is not provided. Download the original file instead."
        }
        val ready = File.createTempFile("market-normalized-", ".zip", context.cacheDir)
        try {
            staged.inputStream().use { input -> ready.outputStream().use { output ->
                OperitMarketRepository.scopeArchive(input, output, download.subPath)
            } }
            return when (detectArchive(ready)) {
                "txplugin" -> {
                    authorizeWrite("/root/.aharou/plugins")
                    val manifest = manager.importPackage(ready).getOrThrow()
                    manager.install(manifest.id).getOrThrow()
                    manifest.id
                }
                "skill" -> {
                    val report = ready.inputStream().use { skills.importZip(it, download.name.substringBeforeLast('.'), scope, expectedWorkspace = workspaceRoot, authorizeWrite = authorizeWrite, boundAccess = access) }
                    check(report.fatal == null && report.failures.isEmpty() && report.imported.isNotEmpty()) { "skill_import_failed:$report" }
                    report.imported.joinToString(",")
                }
                else -> error("unsupported_format: Archive contains neither a native txplugin manifest nor SKILL.md; DSH CLI runtime is not provided. Download remains available.")
            }
        } finally { ready.delete() }
    }

    companion object {
        internal fun failureCode(failure: Exception): String = when {
            failure.message.orEmpty().startsWith("WRITE_LEASE_DENIED:") -> "WRITE_LEASE_DENIED"
            failure.message.orEmpty().startsWith("unsupported_format:") -> "UNSUPPORTED_FORMAT"
            failure.message.orEmpty().startsWith("market_download_failed") -> "DOWNLOAD_FAILED"
            failure.message.orEmpty().startsWith("skill_import_failed:") && failure.message.orEmpty().contains("NAME_CONFLICT") -> "NAME_CONFLICT"
            failure.message.orEmpty().startsWith("skill_import_failed:") -> "SKILL_IMPORT_FAILED"
            else -> "NATIVE_INSTALL_FAILED"
        }

        internal fun detectArchive(file: File): String = ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().filterNot { it.isDirectory }.map {
                OperitMarketRepository.archivePath(it.name)
            }.toList()
            when {
                "manifest.json" in names -> "txplugin"
                names.any { it.substringAfterLast('/').equals("SKILL.md", true) || it.substringAfterLast('/').equals("CLAUDE.md", true) } -> "skill"
                else -> "unsupported"
            }
        }
    }
}

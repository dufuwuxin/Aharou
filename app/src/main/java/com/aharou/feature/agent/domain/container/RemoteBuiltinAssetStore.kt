package com.aharou.feature.agent.domain.container

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.EnumSet
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException

/** Receipts live on the selected host, so reconnecting cannot turn local consent into repeated overwrite. */
internal class RemoteBuiltinAssetStore(private val access: Access, private val version: Int) {
    interface Access {
        fun read(path: String): ByteArray?
        fun namespaceExists(path: String): Boolean
        fun write(path: String, bytes: ByteArray)
    }

    fun deploy(namespace: String, files: Map<String, ByteArray>, historical: Map<String, Set<String>> = emptyMap(),
               preserveUnknown: Boolean = false, requireExact: Boolean = false) {
        valid(namespace)
        files.keys.forEach(::valid)
        val receipt = "builtin-seed-state/remote-${hash(namespace.toByteArray())}.json"
        val previous = access.read(receipt)?.let { Json.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject }
        val legacyNamespace = previous == null && preserveUnknown && access.namespaceExists(namespace)
        val sameVersion = previous?.get("version")?.jsonPrimitive?.intOrNull == version
        val hashes = previous?.get("files")?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content }
        if (!sameVersion) {
            val next = if (preserveUnknown) hashes.toMutableMap() else linkedMapOf<String, String>()
            for ((name, bytes) in files) {
                val path = "$namespace/$name"
                val existing = access.read(path)
                val digest = existing?.let(::hash)
                val known = historical[name].orEmpty() + listOfNotNull(hashes[name])
                // Deletion after an earlier deployment is user state, not an invitation to resurrect a seed.
                if (preserveUnknown && existing == null && (name in hashes || legacyNamespace && name in historical)) {
                    next[name] = hashes[name] ?: historical.getValue(name).first()
                    continue
                }
                if (preserveUnknown && existing != null && !existing.contentEquals(bytes) && digest !in known) continue
                if (existing == null || !existing.contentEquals(bytes)) access.write(path, bytes)
                next[name] = hash(bytes)
            }
            if (requireExact) verify(namespace, files)
            access.write(receipt, buildJsonObject {
                put("version", version)
                put("files", JsonObject(next.mapValues { JsonPrimitive(it.value) }))
            }.toString().toByteArray())
        }
        if (sameVersion && requireExact) verify(namespace, files)
    }

    private fun verify(namespace: String, files: Map<String, ByteArray>) {
        for ((name, bytes) in files) check(access.read("$namespace/$name")?.contentEquals(bytes) == true) {
            "BUILTIN_SKILL_RESOURCES_CHANGED"
        }
    }

    private fun valid(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && path.split('/').all {
            it.isNotBlank() && it != "." && it != ".." && '\\' !in it && it.none(Char::isISOControl)
        }) { "Invalid remote builtin path" }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    class SftpAccess(private val client: SFTPClient, private val root: String, private val current: () -> Unit) : Access {
        private fun resolve(path: String): String {
            current()
            require(path.isNotBlank() && !path.startsWith('/') && path.split('/').all {
                it.isNotBlank() && it != "." && it != ".." && '\\' !in it && it.none(Char::isISOControl)
            })
            val target = root.trimEnd('/') + "/" + path
            var component = ""
            for (part in target.trimStart('/').split('/')) {
                component += "/$part"
                val attrs = try { client.lstat(component) } catch (error: SFTPException) {
                    if (error.statusCode == Response.StatusCode.NO_SUCH_FILE) null else throw error
                }
                check(attrs?.type != FileMode.Type.SYMLINK) { "BUILTIN_SKILL_SYMLINK" }
            }
            return target
        }

        override fun namespaceExists(path: String): Boolean = client.statExistence(resolve(path)) != null

        override fun read(path: String): ByteArray? {
            val target = resolve(path)
            if (client.statExistence(target) == null) return null
            return client.open(target).use { file ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var offset = 0L
                while (true) {
                    current()
                    val count = file.read(offset, buffer, 0, buffer.size)
                    if (count <= 0) break
                    check(offset + count <= 64L * 1024 * 1024) { "Remote builtin file is too large" }
                    output.write(buffer, 0, count)
                    offset += count
                }
                output.toByteArray()
            }
        }

        override fun write(path: String, bytes: ByteArray) {
            val target = resolve(path)
            client.mkdirs(target.substringBeforeLast('/'))
            val temporary = "$target.aharou-${UUID.randomUUID()}.tmp"
            try {
                current()
                client.open(temporary, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).use { file ->
                    file.write(0L, bytes, 0, bytes.size)
                }
                current()
                resolve(path)
                client.rename(temporary, target, EnumSet.of(RenameFlags.OVERWRITE))
            } finally { runCatching { client.rm(temporary) } }
        }
    }
}

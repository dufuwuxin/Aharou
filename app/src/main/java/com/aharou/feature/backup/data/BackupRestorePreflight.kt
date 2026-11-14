package com.aharou.feature.backup.data

import com.aharou.feature.backup.domain.AgentMessageDto
import com.aharou.feature.backup.domain.BackupMetadata
import com.aharou.feature.backup.domain.BackupSnapshot
import com.aharou.feature.backup.domain.ChatSessionDto
import com.aharou.feature.backup.domain.TodoItemDto
import com.aharou.feature.backup.domain.toMetadata
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/** 只解析，不触碰恢复目标；必须扫完归档才能交给恢复入口。 */
internal object BackupRestorePreflight {
    suspend fun <T> withValidated(
        staged: java.io.File, json: Json, checkVersion: (Int) -> Unit,
        block: suspend (java.io.File, BackupMetadata) -> T
    ): T = try {
        val metadata = staged.inputStream().buffered().use { validate(it, json, checkVersion) }
        currentCoroutineContext().ensureActive()
        block(staged, metadata)
    } finally {
        staged.delete()
    }

    suspend fun validate(
        input: InputStream,
        json: Json,
        checkVersion: (Int) -> Unit
    ): BackupMetadata {
        val context = currentCoroutineContext()
        context.ensureActive()
        return GzipCompressorInputStream(input).use { gzip ->
            TarArchiveInputStream(cancellationCheckedInput(gzip, context)).use { tar ->
                var metadata: BackupMetadata? = null
                val buffer = ByteArray(64 * 1024)
                var entry = tar.nextEntry
                while (entry != null) {
                    context.ensureActive()
                    require(entry.isCheckSumOK && tar.canReadEntryData(entry)) { "损坏的备份条目" }
                    when (entry.name) {
                        "metadata.json", "snapshot.json" -> {
                            require(metadata == null && entry.isFile) { "重复或无效的备份元数据" }
                            val text = String(tar.readBytes(), Charsets.UTF_8)
                            metadata = if (entry.name == "metadata.json") {
                                json.decodeFromString(BackupMetadata.serializer(), text)
                            } else {
                                json.decodeFromString(BackupSnapshot.serializer(), text).toMetadata()
                            }
                            require(metadata.schemaVersion > 0) { "无效备份版本" }
                            checkVersion(metadata.schemaVersion)
                        }
                        "chatSessions.jsonl", "messages.jsonl", "todoItems.jsonl" -> {
                            require(entry.isFile) { "无效的备份数据条目" }
                            val name = entry.name
                            // 与 restoreJsonl 一样只以 LF 分行，保留 CR，避免预检接受恢复端会拒绝的空 CRLF 行。
                            val reader = tar.bufferedReader(Charsets.UTF_8)
                            val chars = CharArray(8192)
                            val line = StringBuilder()
                            fun validateLine() {
                                if (line.isEmpty()) return
                                val text = line.toString()
                                when (name) {
                                    "chatSessions.jsonl" -> json.decodeFromString(ChatSessionDto.serializer(), text)
                                    "messages.jsonl" -> json.decodeFromString(AgentMessageDto.serializer(), text)
                                    else -> json.decodeFromString(TodoItemDto.serializer(), text)
                                }
                                line.setLength(0)
                            }
                            while (true) {
                                context.ensureActive()
                                val count = reader.read(chars)
                                if (count < 0) break
                                for (index in 0 until count) {
                                    if (chars[index] == '\n') validateLine() else line.append(chars[index])
                                }
                            }
                            validateLine()
                        }
                        else -> while (tar.read(buffer) >= 0) context.ensureActive()
                    }
                    entry = tar.nextEntry
                }
                // tar 的结束标记可能早于 gzip CRC/trailer；同样在任何恢复写入前校验。
                while (gzip.read(buffer) >= 0) context.ensureActive()
                context.ensureActive()
                requireNotNull(metadata) { "备份缺少 metadata.json 或 snapshot.json" }
            }
        }
    }
}

internal fun cancellationCheckedInput(input: InputStream, context: CoroutineContext): InputStream =
    object : FilterInputStream(input) {
        override fun read(): Int {
            context.ensureActive()
            return super.read().also { context.ensureActive() }
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            context.ensureActive()
            return super.read(bytes, offset, length).also { context.ensureActive() }
        }
    }

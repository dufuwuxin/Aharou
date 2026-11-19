// SPDX-License-Identifier: AGPL-3.0-only
package com.aharou.feature.sandbox

import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberElfFile(item: FileItem): State<Boolean?> =
    produceState<Boolean?>(if (item.isElfCandidate) null else false, item.file, item.size, item.modifiedMs) {
        value = if (!item.isElfCandidate) false else withContext(Dispatchers.IO) { hasElfMagic(item.file) }
    }

internal fun hasElfMagic(file: File): Boolean = runCatching {
    if (!file.isFile) false else file.inputStream().use { stream ->
        val header = ByteArray(4)
        var count = 0
        while (count < 4) { val n = stream.read(header, count, 4 - count); if (n <= 0) break; count += n }
        count == 4 && header.contentEquals(byteArrayOf(0x7f, 69, 76, 70))
    }
}.getOrDefault(false)

@Composable
internal fun rememberFilePreviewMimeType(item: FileItem): String = remember(item.file) {
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.file.extension.lowercase()) ?: "application/octet-stream"
}

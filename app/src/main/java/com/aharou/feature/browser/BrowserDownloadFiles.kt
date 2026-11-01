package com.aharou.feature.browser

import java.io.File

/**
 * 浏览器下载落盘的文件名工具。
 *
 * 从这里拆出来的原因：两个函数都属于「下载目录 + 文件名」这一件事，跟 [BrowserTabPool] 的会话/任务管理无关；
 * 而那个文件已经顶在架构门禁的棘轮基线上（只许降不许涨），新增逻辑必须落到别的文件里。
 */

/** 归一后的文件名上限：够长够用，又不至于顶爆 F2FS 的 255 字节名长限制。 */
private const val MAX_BLOB_NAME_CHARS = 120

/**
 * 把网页传来的文件名归一到「安全 basename」：去目录部分、只留白名单字符、限长。
 *
 * 桥接方法 `__aharou__.saveBlobDownload` 能被任意页面调用，文件名是页面自己传的，
 * 所以这里不能信任任何输入——`../../.aharou/scripts/x.sh` 这种名字必须被削成 `x.sh`。
 */
internal fun sanitizeBlobName(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
    val cleaned = base.filter { it.isLetterOrDigit() || it in "._-" }.take(MAX_BLOB_NAME_CHARS)
    // 全是点的名字（`..`）去掉前导点后为空，回退一个固定名。
    return cleaned.trimStart('.').ifBlank { "download.bin" }
}

/** name.ext → name-1.ext → name-2.ext … until unused. */
internal fun uniqueFile(dir: File, name: String): File {
    var f = File(dir, name)
    if (!f.exists()) return f
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "")
    var i = 1
    while (f.exists()) {
        f = File(dir, if (ext.isEmpty()) "$base-$i" else "$base-$i.$ext")
        i++
    }
    return f
}

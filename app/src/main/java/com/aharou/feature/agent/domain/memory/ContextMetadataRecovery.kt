package com.aharou.feature.agent.domain.memory

import java.io.File

internal class ContextMetadataRecovery(
    private val marker: File,
    private val readMarker: () -> String,
    private val writeMarker: (String) -> Boolean,
) {
    private val journal = File(marker.parentFile, ".context-layout-restore.json")
    var committed = false
    var active = false

    fun recover(): Boolean {
        if (active) return true
        if (!journal.isFile) return true
        return try {
            val root = org.json.JSONObject(journal.readText())
            if (root.getBoolean("committed")) {
                committed = true
                if (!journal.delete()) return false
                true
            } else {
                val originals = root.getJSONObject("files")
                for (key in originals.keys()) {
                    val file = File(key)
                    if (!(if (file == marker) writeMarker(originals.getString(key)) else
                        MemoryFileStorage.write(file, originals.getString(key)))) return false
                }
                if (!writeMarker(root.getString("marker")) || !journal.delete()) return false
                true
            }
        } catch (e: Exception) {
            System.err.println("Context metadata recovery failed: ${e.message}")
            false
        }
    }

    fun prepare(files: Map<File, String>): Boolean {
        val root = org.json.JSONObject()
        val originals = org.json.JSONObject()
        for ((file, text) in files) originals.put(file.absolutePath, text)
        root.put("files", originals)
        root.put("marker", readMarker())
        root.put("committed", false)
        committed = false
        return MemoryFileStorage.write(journal, root.toString())
    }

    fun finish(): Boolean = committed && (!journal.isFile || journal.delete())

    fun commit(): Boolean {
        if (!journal.isFile) return false
        val root = org.json.JSONObject(journal.readText())
        root.put("committed", true)
        if (!MemoryFileStorage.write(journal, root.toString())) return false
        committed = true
        MemoryFileStorage.beforeMetadataCommit(marker)
        return true
    }
}

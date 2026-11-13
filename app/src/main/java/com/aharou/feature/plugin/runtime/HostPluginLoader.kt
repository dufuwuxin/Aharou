package com.aharou.feature.plugin.runtime

import com.aharou.feature.plugin.domain.model.HostRuntimeSpec
import com.aharou.plugin.api.AharouPlugin
import dalvik.system.InMemoryDexClassLoader
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HostPluginLoader @Inject constructor() {
    fun payloadFile(root: File, relative: String): File {
        require(relative.isNotBlank() && !File(relative).isAbsolute) { "Expected a relative payload path" }
        val payload = File(root, "payload").canonicalFile
        val file = File(payload, relative).canonicalFile
        require(file.path.startsWith(payload.path + File.separator)) { "Payload path escapes plugin" }
        return file
    }

    fun validate(root: File, spec: HostRuntimeSpec) {
        require(spec.apiVersion == 1) { "Unsupported plugin API: ${spec.apiVersion}" }
        when (spec.runtime) {
            "js" -> check(payloadFile(root, spec.entry).isFile) { "JavaScript entry is missing" }
            "dex" -> {
                require(spec.entry.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))) {
                    "Invalid DEX entry class"
                }
                val file = payloadFile(root, spec.artifact)
                check(file.isFile && file.length() >= 112) { "DEX artifact is missing or truncated" }
                val header = ByteArray(8)
                file.inputStream().use { check(it.read(header) == header.size) }
                require(header.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10)) &&
                    header[7] == 0.toByte() && header.slice(4..6).all { it.toInt() in 48..57 }) { "Invalid DEX magic" }
            }
            else -> error("Unsupported host runtime: ${spec.runtime}")
        }
    }

    fun load(root: File, spec: HostRuntimeSpec): AharouPlugin {
        validate(root, spec)
        return when (spec.runtime) {
            "js" -> JavascriptHostPlugin(payloadFile(root, spec.entry).readText(Charsets.UTF_8))
            "dex" -> {
                // A fresh loader creates a new generation; already loaded classes are not replaced.
                val loader = InMemoryDexClassLoader(
                    ByteBuffer.wrap(payloadFile(root, spec.artifact).readBytes()), AharouPlugin::class.java.classLoader,
                )
                val type = loader.loadClass(spec.entry)
                require(type.classLoader === loader) { "Entry must be defined by the plugin DEX" }
                type.asSubclass(AharouPlugin::class.java).getDeclaredConstructor().newInstance()
            }
            else -> error("Unsupported runtime")
        }
    }
}

package com.aharou.feature.agent.domain.prompt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 工作区工程类型识别：返回全部命中类型，顺序固定 android → rust → python，每种至多一次。
 */
class WorkspaceTypeDetectorTest {

    private val dir: File = Files.createTempDirectory("workspace-type-test").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun touch(relative: String) {
        val file = File(dir, relative)
        file.parentFile?.mkdirs()
        file.writeText("")
    }

    @Test
    fun detect_只有Gradle工程_只识别android() {
        touch("settings.gradle.kts")
        touch("app/build.gradle.kts")

        assertEquals(listOf("android"), WorkspaceTypeDetector.detect(dir))
    }

    @Test
    fun detect_Android工程带一级子目录Rust_crate_两种都识别且android在前() {
        touch("settings.gradle")
        touch("native/Cargo.toml")

        assertEquals(listOf("android", "rust"), WorkspaceTypeDetector.detect(dir))
    }

    @Test
    fun detect_只有Python工程_只识别python() {
        touch("pyproject.toml")
        touch("requirements.txt")

        assertEquals("多个特征文件只算一次", listOf("python"), WorkspaceTypeDetector.detect(dir))
    }

    @Test
    fun detect_没有任何特征文件_返回空() {
        touch("README.md")
        touch("pubspec.yaml")
        touch("package.json")
        touch("a/b/Cargo.toml")

        assertTrue("Flutter/Node 不再识别，二级子目录的 Cargo.toml 不算", WorkspaceTypeDetector.detect(dir).isEmpty())
    }

    @Test
    fun detect_根目录只有Cargo工程_只识别rust() {
        touch("Cargo.toml")

        assertEquals(listOf("rust"), WorkspaceTypeDetector.detect(dir))
    }

    @Test
    fun detect_三种特征都在_顺序固定为android_rust_python() {
        touch("pyproject.toml")
        touch("Cargo.toml")
        touch("settings.gradle.kts")

        assertEquals(listOf("android", "rust", "python"), WorkspaceTypeDetector.detect(dir))
    }
}

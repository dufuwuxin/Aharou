package com.aharou.feature.settings.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsNavigationTest {
    @Test fun thirdLevelReturnsToThePageThatActuallyOpenedIt() {
        var path = listOf("配置")
        path = settingsNavigationPath(path, "工具")
        path = settingsNavigationPath(path, "编辑器设置")
        path = settingsNavigationPath(path, "语言服务器")
        assertEquals("编辑器设置", path[path.lastIndex - 1])
        path = settingsNavigationPath(path, path[path.lastIndex - 1])
        assertEquals(listOf("配置", "工具", "编辑器设置"), path)
        path = settingsNavigationPath(path, path[path.lastIndex - 1])
        assertEquals(listOf("配置", "工具"), path)
    }

    @Test fun returningToAnEarlierPageRemovesAbandonedChildren() {
        val path = settingsNavigationPath(listOf("配置", "技能", "市场", "详情"), "技能")
        assertEquals(listOf("配置", "技能"), path)
        assertEquals(path, settingsNavigationPath(path, "技能"))
        assertEquals(listOf("配置", "技能", "编辑"), settingsNavigationPath(path, "编辑"))
        assertEquals(listOf("配置"), settingsNavigationPath(path, "配置"))
    }
}

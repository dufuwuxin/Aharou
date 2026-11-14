package com.aharou.feature.agent.domain.vdisplay

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.aharou.accessibility.AharouAccessibilityService

internal fun accessibleShadowNodes(displayId: Int): List<VdNode>? {
    if (displayId <= 0) return null
    val roots = AharouAccessibilityService.getInstance()?.rootNodesOnDisplay(displayId) ?: return null
    return flattenShadowNodes(roots)
}

internal fun flattenShadowNodes(roots: List<AccessibilityNodeInfo>): List<VdNode> {
    val result = mutableListOf<VdNode>()
    var visited = 0
    fun walk(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 32 || visited++ >= 500) return
        val text = if (node.isPassword) "" else node.text?.toString().orEmpty()
        val description = if (node.isPassword) "" else node.contentDescription?.toString().orEmpty()
        if (text.isNotBlank() || description.isNotBlank() || node.isClickable || node.isEditable) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            result += VdNode(result.size, text, description, node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty(),
                buildString {
                    if (node.isClickable) append('c')
                    if (node.isEditable) append('e')
                    if (node.isFocused) append('f')
                    if (!node.isEnabled) append('-')
                }, bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
        for (index in 0 until node.childCount) node.getChild(index)?.let { walk(it, depth + 1) }
    }
    roots.forEach { walk(it, 0) }
    return result
}

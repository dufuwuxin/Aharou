package com.aharou.feature.agent.domain.subagent

import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.data.local.session.RoomSessionTreeSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTreeWorkspaceTest {
    @Test fun rootAndChildNodesRetainStoredWorkspaceInsteadOfGlobalSelection() = runBlocking {
        val dao = mockk<ChatSessionDao>()
        val root = ChatSessionEntity("root", "Root", 1, 1, workspacePath = "/project/A")
        val child = root.copy(id = "child", parentId = "root", workspacePath = "/project/B")
        coEvery { dao.getById("root") } returns root
        coEvery { dao.getSubSessionsByParentOnce("root") } returns listOf(child)
        val source = RoomSessionTreeSource(dao)
        assertEquals("/project/A", source.find("root")?.workspacePath)
        assertEquals("/project/B", source.childrenOf("root").single().workspacePath)
    }
}

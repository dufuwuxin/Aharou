package com.aharou.feature.agent.data.local.session

import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.subagent.SessionTranscriptSource
import com.aharou.feature.agent.domain.subagent.SessionTreeSource
import com.aharou.feature.agent.domain.subagent.TreeSession

/**
 * [SessionTreeSource] 的 Room 实现：树的边就是 `chat_sessions.parentId`。
 *
 * 只做读，且刻意不暴露实体本身——观察者需要的只有 id / 父 id / 标题 / 角色，
 * 其余字段（provider、token 用量、目标态…）留在数据层，避免领域层顺着实体越拿越多。
 */
class RoomSessionTreeSource(
    private val chatSessionDao: ChatSessionDao,
) : SessionTreeSource {

    override suspend fun find(sessionId: String): TreeSession? =
        chatSessionDao.getById(sessionId)?.toTreeSession()

    override suspend fun childrenOf(parentId: String): List<TreeSession> =
        chatSessionDao.getSubSessionsByParentOnce(parentId).map { it.toTreeSession() }

    private fun com.aharou.feature.agent.data.local.entity.ChatSessionEntity.toTreeSession() = TreeSession(
        id = id,
        parentId = parentId,
        title = title,
        subagentType = subagentType
    )
}

/**
 * [SessionTranscriptSource] 的现读实现：每次调用都回源到 [MessagePersistenceUseCase]，
 * 因此运行中的成员也能读到它「到目前为止」已经落库的内容——观察要的就是这份即时快照，
 * 不缓存、不订阅，避免把一次性诊断做成常驻监听。
 */
class MessageUseCaseTranscriptSource(
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
) : SessionTranscriptSource {

    override suspend fun messages(sessionId: String) =
        messagePersistenceUseCase.getMessagesOnce(sessionId)
}

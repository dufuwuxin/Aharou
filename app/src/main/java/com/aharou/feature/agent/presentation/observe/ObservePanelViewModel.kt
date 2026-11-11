package com.aharou.feature.agent.presentation.observe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.data.local.dao.AgentTaskDao
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.data.local.entity.AgentTaskStatus
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.subagent.ObserveDirection
import com.aharou.feature.agent.domain.subagent.SessionTreeObserver
import com.aharou.feature.agent.presentation.MessageRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 被观察会话/成员的运行态。
 *
 * 全部来自只读推导（`agent_tasks` 的任务状态），不新增任何写路径：
 * 观察面板的职责是「看」，不是「管」。
 */
enum class ObserveRunState {
    RUNNING,
    DONE,
    FAILED,
    STOPPED,
    INTERRUPTED,

    /** 树里没有对应的任务记录（典型是根会话本身，它不是被 task 派发出来的）。 */
    UNKNOWN
}

/**
 * 候选为空时的提示类型。
 *
 * 只给出「状态」而不是成品文案：具体措辞由 UI 层映射到字符串资源，
 * 这样 ViewModel 不依赖 Context，双语也只在 res 里维护一份。
 */
enum class ObserveEmptyHint {
    /** 当前方向是「上级」，但它已经是树的顶层，没有上级。 */
    NO_PARENT,

    /** 当前方向是「下级」，但它还没派生过任何下级。 */
    NO_CHILD
}

/**
 * 树快照失败的类型化描述。
 *
 * 同样不携带成品文案：必要的动态数据（会话 id 前缀、异常摘要）以字段带出，
 * 由 UI 层用位置参数拼进对应字符串资源。
 */
sealed interface ObserveError {
    /** 目标会话不存在（只剩 id 前缀用于提示，避免整串 id 撑爆一行）。 */
    data class SessionMissing(val idPrefix: String) : ObserveError

    /** 读取会话树时抛异常。 */
    data class TreeReadFailed(val detail: String) : ObserveError
}

/**
 * 观察候选成员。[depth] 的符号与 [SessionTreeObserver] 保持同一口径：
 * 负=上级（-1 父）、0=自己、正=下级（1 直接下级）。
 */
data class ObserveMemberItem(
    val id: String,
    val title: String,
    /** 子代理角色名；null 表示主会话。 */
    val roleName: String?,
    val depth: Int,
    val runState: ObserveRunState,
    val isSelf: Boolean = false
)

/** 只读消息流的展示单元：正文气泡 or 可折叠工具卡片。 */
sealed interface ObserveMessage {
    val id: String
    val timestamp: Long

    /** 用户 / AI 正文气泡。 */
    data class Bubble(
        override val id: String,
        override val timestamp: Long,
        val fromUser: Boolean,
        val body: String
    ) : ObserveMessage

    /** 一次工具调用（TOOL 行）：名字 + 参数 + 结果，长内容由 UI 折叠。 */
    data class ToolCard(
        override val id: String,
        override val timestamp: Long,
        val name: String,
        val args: String?,
        val result: String?,
        val isError: Boolean,
        val pending: Boolean
    ) : ObserveMessage
}

/** 观察面板的完整 UI 状态。不可变，只由 [ObservePanelViewModel] 单向产出。 */
data class ObservePanelUiState(
    /** 被观察树的入口会话 id；null 表示还没接线（宿主未传入）。 */
    val sessionId: String? = null,
    /** 树的入口会话（「自己」）标题。 */
    val selfTitle: String = "",
    /** 当前正在看消息流的那个成员的标题。 */
    val observedTitle: String = "",
    val observedRole: String? = null,
    val observedState: ObserveRunState = ObserveRunState.UNKNOWN,
    /** 当前被点选的成员 id；null 表示正在看自己（与 [observingSelf] 互补，供 UI 高亮用）。 */
    val observedMemberId: String? = null,
    /** true = 正在看自己；false = 正在看被点选的其它成员。 */
    val observingSelf: Boolean = true,
    val direction: ObserveDirection = ObserveDirection.DOWN,
    val depth: Int = 1,
    val members: List<ObserveMemberItem> = emptyList(),
    val messages: List<ObserveMessage> = emptyList(),
    /** 候选为空时的提示类型（没有上级 / 没有下级）；null 表示无提示。 */
    val emptyHint: ObserveEmptyHint? = null,
    /** 读取失败的类型化描述；null 表示无错误，具体文案由 UI 层映射。 */
    val error: ObserveError? = null
)

/**
 * 「只读观察面板」的状态持有者。
 *
 * 设计约束（与 domain 层一脉相承）：
 * - **只读**：对外只有 `setDirection` / `setDepth` / `selectMember` / `refresh` 这类「换看待什么」的方法，
 *   不提供发送、停止、写文件、改会话标题等任何写能力——面板不该成为操作台。
 * - **复用既有数据面**：树结构走 [SessionTreeObserver]（内部是 `chat_sessions.parentId` 的 Room 实现），
 *   消息流走 `MessagePersistenceUseCase.messagesFlow`（与聊天页同一口径，见 AIAgentViewModel.messagesState：
 *   同一个方法 + `restoreAll` 归档恢复），因此运行中的成员也能看到「到目前为止」已落库的内容。
 * - **快照会变**：成员的运行态与消息都在变，树结构用低频轮询刷新（观察者本身是 snapshot 风格、不订阅），
 *   消息流本身是 Room 的 Flow，天然实时。
 */
@HiltViewModel
class ObservePanelViewModel @Inject constructor(
    private val observer: SessionTreeObserver,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val agentTaskDao: AgentTaskDao,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private companion object {
        /** 单次最多渲染的最近消息条数：与聊天页默认窗口同量级，够看又不至于把长会话全画出来。 */
        const val MESSAGE_LIMIT = 200

        /** 树结构轮询间隔：观察是「看进展」，不需要逐帧刷新，3 秒足够且省电。 */
        const val POLL_INTERVAL_MS = 3_000L

        /** 层数上限，与 [SessionTreeObserver] 保持一致。 */
        const val MAX_DEPTH = 5

        /** 工具结果「还没返回」的前缀，与 SessionTreeObserver 同源。 */
        const val PENDING_MARKER = SessionUseCase.PENDING_TOOL_MARKER
    }

    /** 树的入口会话 id：优先取导航参数，其次由宿主显式 [bind]。 */
    private val _selfId = MutableStateFlow(savedStateHandle.get<String>("sessionId"))

    private val _direction = MutableStateFlow(ObserveDirection.DOWN)

    private val _depth = MutableStateFlow(1)

    /** 被点选的成员 id；null = 看自己。 */
    private val _selectedMemberId = MutableStateFlow<String?>(null)

    /** 手动刷新信号：变化即重建树快照（不会重启轮询循环之外的任何东西）。 */
    private val _refreshTick = MutableStateFlow(0L)

    /** 宿主在运行时（而不是导航参数里）拿到会话 id 时用这个接上。 */
    fun bind(sessionId: String) {
        _selfId.value = sessionId
    }

    /** 切换观察方向（up=上级 / down=下级），同时回到「看自己」。 */
    fun setDirection(direction: ObserveDirection) {
        if (_direction.value == direction) return
        _direction.value = direction
        _selectedMemberId.value = null
    }

    /** 切换层级深度（1..5，越界自动收敛）。 */
    fun setDepth(depth: Int) {
        _depth.value = depth.coerceIn(1, MAX_DEPTH)
    }

    /** 点选具体成员；传 null 回到「看自己」。 */
    fun selectMember(memberId: String?) {
        _selectedMemberId.value = memberId
    }

    /** 手动刷新一次树快照（消息流本身实时，无需触发）。 */
    fun refresh() {
        _refreshTick.value += 1
    }

    /** 一次树快照请求的参数打包，作为轮询流的输入。 */
    private data class TreeRequest(
        val selfId: String?,
        val direction: ObserveDirection,
        val depth: Int,
        val tick: Long
    )

    /** 一次树快照的结果。 */
    private data class TreeSnapshot(
        val selfTitle: String = "",
        val selfRole: String? = null,
        val selfState: ObserveRunState = ObserveRunState.UNKNOWN,
        val members: List<ObserveMemberItem> = emptyList(),
        val emptyHint: ObserveEmptyHint? = null,
        val error: ObserveError? = null
    )

    /**
     * 树快照：低频轮询。候选成员列表与各自运行态每次重算（状态本来就会变），
     * 只有「看谁」与「看多深」变化时才重启循环。
     */
    private val treeFlow: Flow<TreeSnapshot> = observeTree()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeTree(): Flow<TreeSnapshot> = combine(
        _selfId,
        _direction,
        _depth,
        _refreshTick
    ) { selfId, direction, depth, tick -> TreeRequest(selfId, direction, depth, tick) }
        .flatMapLatest { request ->
            val selfId = request.selfId
            if (selfId == null) {
                flowOf(TreeSnapshot())
            } else {
                flow {
                    while (true) {
                        emit(snapshotOf(selfId, request.direction, request.depth))
                        delay(POLL_INTERVAL_MS)
                    }
                }
            }
        }

    /**
     * 被观察成员的消息流：走聊天页同一个数据入口（含隐身内存缓冲），
     * 再用 [MessagePersistenceUseCase.restoreAll] 做归档恢复，最后压成只读展示单元。
     */
    private val messagesFlow: Flow<List<ObserveMessage>> = observeMessages()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeMessages(): Flow<List<ObserveMessage>> = combine(
        _selfId,
        _selectedMemberId
    ) { selfId, selected -> selected ?: selfId }
        .distinctUntilChanged()
        .flatMapLatest { targetId ->
            if (targetId == null) {
                flowOf(emptyList())
            } else {
                messagePersistenceUseCase.messagesFlow(targetId, MESSAGE_LIMIT)
                    .map { entities -> toObserveMessages(messagePersistenceUseCase.restoreAll(entities)) }
                    .catch { emit(emptyList()) }
            }
        }
        .flowOn(Dispatchers.Default)

    val uiState: StateFlow<ObservePanelUiState> = combine(
        treeFlow,
        messagesFlow,
        _direction,
        _depth,
        _selectedMemberId
    ) { snapshot, messages, direction, depth, selectedId ->
        val selected = selectedId?.let { id -> snapshot.members.firstOrNull { it.id == id } }
        // 点选的成员若已不在候选里（例如下级跑完被清理），自动回落到「看自己」，避免空面板。
        val observed = selected ?: snapshot.members.firstOrNull { it.isSelf }
        ObservePanelUiState(
            sessionId = _selfId.value,
            selfTitle = snapshot.selfTitle,
            observedTitle = observed?.title ?: snapshot.selfTitle,
            observedRole = observed?.roleName,
            observedState = observed?.runState ?: snapshot.selfState,
            observedMemberId = observed?.takeIf { !it.isSelf }?.id,
            observingSelf = selected == null,
            direction = direction,
            depth = depth,
            members = snapshot.members,
            messages = messages,
            emptyHint = snapshot.emptyHint,
            error = snapshot.error
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ObservePanelUiState(sessionId = _selfId.value, direction = _direction.value, depth = _depth.value)
    )

    /**
     * 取一次树快照：自己 + 该方向/深度下的候选成员（每个带运行态）。
     * 读失败不抛给 UI，折成 [TreeSnapshot.error]，面板继续可用。
     */
    private suspend fun snapshotOf(
        selfId: String,
        direction: ObserveDirection,
        depth: Int
    ): TreeSnapshot = runCatching {
        val self = observer.self(selfId)
            ?: return@runCatching TreeSnapshot(error = ObserveError.SessionMissing(selfId.take(8)))
        val related = when (direction) {
            ObserveDirection.UP -> observer.upward(selfId, depth)
            ObserveDirection.DOWN -> observer.downward(selfId, depth)
        }
        val members = ArrayList<ObserveMemberItem>(related.size + 1)
        members += ObserveMemberItem(
            id = self.id,
            title = self.title,
            roleName = self.subagentType,
            depth = 0,
            runState = runStateOf(self.id),
            isSelf = true
        )
        related.forEach { member ->
            members += ObserveMemberItem(
                id = member.session.id,
                title = member.session.title,
                roleName = member.session.subagentType,
                depth = member.depth,
                runState = runStateOf(member.session.id)
            )
        }
        TreeSnapshot(
            selfTitle = self.title,
            selfRole = self.subagentType,
            selfState = runStateOf(self.id),
            members = members,
            emptyHint = if (related.isEmpty()) emptyHintFor(direction) else null
        )
    }.getOrElse { throwable ->
        TreeSnapshot(
            error = ObserveError.TreeReadFailed(
                throwable.message ?: throwable::class.simpleName ?: ""
            )
        )
    }

    /** 运行态只有一处来源：`agent_tasks`（子代理会话 id 即任务 id）。查不到就是没有任务记录。 */
    private suspend fun runStateOf(sessionId: String): ObserveRunState =
        runCatching { agentTaskDao.getById(sessionId)?.status }.getOrNull()?.let { status ->
            when (status) {
                AgentTaskStatus.RUNNING.name -> ObserveRunState.RUNNING
                AgentTaskStatus.COMPLETED.name -> ObserveRunState.DONE
                AgentTaskStatus.FAILED.name -> ObserveRunState.FAILED
                AgentTaskStatus.CANCELLED.name -> ObserveRunState.STOPPED
                AgentTaskStatus.INTERRUPTED.name -> ObserveRunState.INTERRUPTED
                else -> ObserveRunState.UNKNOWN
            }
        } ?: ObserveRunState.UNKNOWN

    private fun emptyHintFor(direction: ObserveDirection): ObserveEmptyHint = when (direction) {
        ObserveDirection.UP -> ObserveEmptyHint.NO_PARENT
        ObserveDirection.DOWN -> ObserveEmptyHint.NO_CHILD
    }

    /**
     * 实体 → 只读展示单元。
     *
     * 与 [SessionTreeObserver] 的渲染口径保持一致：压缩锚点/摘要这类内部行不展示；
     * 工具行折成卡片，正文折成气泡。这里不做变体选择（观察只看「实际发生了什么」）。
     */
    private fun toObserveMessages(entities: List<AgentMessageEntity>): List<ObserveMessage> {
        val result = ArrayList<ObserveMessage>(entities.size)
        for (entity in entities) {
            if (entity.isCompacted || entity.isContextSummary || entity.isCompactionMarker) continue
            when (entity.role) {
                MessageRole.USER.name -> {
                    val body = entity.content.trim()
                    if (body.isNotEmpty()) {
                        result += ObserveMessage.Bubble(entity.id, entity.timestamp, fromUser = true, body = body)
                    }
                }

                MessageRole.ASSISTANT.name -> {
                    val body = entity.content.trim()
                    if (body.isNotEmpty()) {
                        result += ObserveMessage.Bubble(entity.id, entity.timestamp, fromUser = false, body = body)
                    }
                }

                MessageRole.TOOL.name -> {
                    val pending = entity.content.startsWith(PENDING_MARKER)
                    val body = if (pending) {
                        entity.content.removePrefix(PENDING_MARKER).trim()
                    } else {
                        entity.content.trim()
                    }
                    result += ObserveMessage.ToolCard(
                        id = entity.id,
                        timestamp = entity.timestamp,
                        // 名字缺失时留空，由 UI 层回落到 ``observe_tool_fallback_name``。
                        name = entity.toolName.orEmpty(),
                        args = entity.toolArgs?.takeIf { it.isNotBlank() },
                        result = body.takeIf { it.isNotEmpty() },
                        isError = entity.isError,
                        pending = pending
                    )
                }

                else -> Unit
            }
        }
        return result
    }
}

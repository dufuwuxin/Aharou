package com.aharou.feature.agent.domain.task

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ProjectTaskStatus { DRAFT, READY, RUNNING, REVIEW, DONE, BLOCKED }

@Serializable
data class ProjectTaskMemberRun(
    val role: String,
    val ordinal: Int,
    val requirementsVersion: Long,
    val outcome: String = "RUNNING",
    val reason: String = ""
)

/** Task identity and requirements are independent of any execution conversation. */
@Serializable
data class ProjectTask(
    val id: String = UUID.randomUUID().toString(),
    val workspace: String,
    val title: String,
    val requirement: String,
    val projectId: String? = null,
    val parentId: String? = null,
    val sessionIds: List<String> = emptyList(),
    val executionRequirementsVersions: Map<String, Long> = emptyMap(),
    val dependencyIds: List<String> = emptyList(),
    val owner: String = "",
    val members: List<String> = emptyList(),
    val status: ProjectTaskStatus = ProjectTaskStatus.DRAFT,
    val background: String = "",
    val constraints: List<String> = emptyList(),
    val acceptance: String = "",
    val brief: String = "",
    val executionReason: String = "",
    val evidenceRefs: List<String> = emptyList(),
    val version: Long = 0,
    val requirementsVersion: Long = 1,
    val acceptedRequirementsVersion: Long? = null,
    val order: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val processSessionIds: List<String> = emptyList(),
    val processRequirementsVersion: Long? = null,
    val processMembers: Map<String, ProjectTaskMemberRun> = emptyMap(),
    val processParticipants: List<String>? = null,
    /** User supplements in original wording, oldest first; recording one never invalidates work by itself. */
    val supplements: List<String> = emptyList(),
    /** Member session that added this task through project_tasks; null for user or planner tasks. */
    val createdBySessionId: String? = null
)

/** One staged write in a repository transaction; [force] bumps the requirements version (rerun) even without a field change. */
data class ProjectTaskChange(val task: ProjectTask, val force: Boolean = false)

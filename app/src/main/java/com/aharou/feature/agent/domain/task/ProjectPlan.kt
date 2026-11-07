package com.aharou.feature.agent.domain.task

import kotlinx.serialization.Serializable
import java.util.UUID

/** Project identity does not depend on an execution session or workspace title. */
@Serializable
data class ProjectPlan(
    val id: String = UUID.randomUUID().toString(),
    val workspace: String,
    val title: String,
    val requirements: String,
    val createdAt: Long = System.currentTimeMillis(),
    val flowSessionIds: List<String> = emptyList(),
    val workspaceOwnership: String = "legacy_shared",
    val continuationSourceProjectId: String? = null
)

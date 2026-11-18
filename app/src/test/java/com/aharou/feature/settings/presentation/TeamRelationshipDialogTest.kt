package com.aharou.feature.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.feature.agent.domain.subagent.*
import com.aharou.feature.settings.presentation.component.TeamRelationshipDialog
import com.aharou.feature.settings.presentation.component.editTeamCollaboration
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TeamRelationshipDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun changingInitialForwardSelectionAllowsThirdStageToConnectToFirst() {
        val stages = (1..3).map { i -> AgentTeamStageForm(id = "s$i", title = "Stage $i",
            tasks = listOf(AgentTeamTaskForm(id = "t$i", agent = "member$i", prompt = "work"))) }
        var saved: List<AgentTeamStageForm>? = null
        var closed = false
        compose.setContent { MaterialTheme {
            TeamRelationshipDialog(stages, emptyList(), linkedSetOf(TeamFlowNode("s1", "t1"), TeamFlowNode("s3", "t3")),
                onDismiss = { closed = true },
                onCollaboration = { sources, targets, both, about, remove ->
                    saved = editTeamCollaboration(stages, sources, targets, both, about, remove)
                }, onRoute = { _, _, _, _, _ -> false })
        } }
        compose.onNodeWithTag("team_relation_source_s3/t3").performClick()
        compose.onNodeWithTag("team_relation_target_s1/t1").performClick()
        compose.onNodeWithTag("team_relation_apply").performClick()
        assertTrue(closed)
        val third = requireNotNull(saved)[2].tasks.single()
        assertEquals("s1/t1", third.interfaces.single().with)
        assertTrue(requireNotNull(saved)[0].tasks.single().interfaces.isEmpty())
    }
    @Test fun popupSelectsThirdToFirstFeedbackReviewAndRejectWithStableTaskIds() {
        val stages = (1..3).map { i -> AgentTeamStageForm(id = "s$i", title = "Stage $i",
            tasks = listOf(AgentTeamTaskForm(id = "t$i", agent = "same-member", prompt = "work"))) }
        var saved = emptyList<TeamRoute>()
        compose.setContent { MaterialTheme {
            TeamRelationshipDialog(stages, emptyList(), linkedSetOf(TeamFlowNode("s3", "t3"), TeamFlowNode("s1", "t1")),
                onDismiss = {}, onCollaboration = { _, _, _, _, _ -> fail("Not collaboration") },
                onRoute = { sources, targets, action, remove, feedback ->
                    saved = requireNotNull(com.aharou.feature.settings.presentation.component.editTeamRoutes(
                        stages, saved, sources, targets, action, remove, feedback))
                    true
                })
        } }
        compose.onNodeWithTag("team_relation_flow").performClick()
        compose.onNodeWithTag("team_relation_feedback").performScrollTo().performClick()
        compose.onNodeWithTag("team_relation_action_REVIEW").performScrollTo().performClick()
        compose.onNodeWithTag("team_relation_apply").performClick()
        val review = saved.single { it.feedback }
        assertEquals(TeamFlowNode("s3", "t3"), review.from)
        assertEquals(TeamFlowNode("s1", "t1"), review.to)
        assertEquals(TeamRouteAction.REVIEW, review.action)
        compose.onNodeWithTag("team_relation_action_REJECT").performScrollTo().performClick()
        compose.onNodeWithTag("team_relation_apply").performClick()
        assertTrue(saved.contains(review))
        assertTrue(saved.any { it.from == review.from && it.to == review.to && it.action == TeamRouteAction.REJECT && it.isFeedback })
    }

}

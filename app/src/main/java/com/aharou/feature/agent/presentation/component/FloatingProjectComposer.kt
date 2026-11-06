package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing

internal class ProjectComposerState(val floating: Boolean, val canSend: Boolean,
    private val dismissKeyboard: () -> Unit) {
    var expanded by mutableStateOf(!floating)
    fun collapse() { if (floating) { expanded = false; dismissKeyboard() } }
}

@Composable
internal fun rememberContentDragConnection(onContentDrag: () -> Unit): NestedScrollConnection {
    val onDrag by rememberUpdatedState(onContentDrag)
    return remember { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available != Offset.Zero) onDrag()
            return Offset.Zero
        }
    } }
}

@Composable
internal fun rememberProjectComposer(floating: Boolean, ready: Boolean, sessionId: String?): ProjectComposerState {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val state = remember(floating, ready, sessionId) {
        ProjectComposerState(floating, !floating || ready) { focus.clearFocus(); keyboard?.hide() }
    }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var wasImeVisible by remember(state) { mutableStateOf(false) }
    LaunchedEffect(imeVisible, state) {
        if (wasImeVisible && !imeVisible) state.collapse()
        wasImeVisible = imeVisible
    }
    return state
}

@Composable
internal fun FloatingProjectComposer(state: ProjectComposerState, value: String,
    content: @Composable (FocusRequester?) -> Unit) {
    if (!state.canSend) return
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(state.expanded) {
        if (state.floating && state.expanded) { withFrameNanos { }; focusRequester.requestFocus() }
    }
    Column {
        // Keep the consumer mounted: model/attachment dialogs must survive IME dismissal.
        Box(if (state.expanded) Modifier else Modifier.height(0.dp).clipToBounds()) {
            content(if (state.floating) focusRequester else null)
        }
        if (!state.expanded) Surface(onClick = { state.expanded = true },
            shape = RoundedCornerShape(Radius.pill),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.78f),
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                .heightIn(min = 48.dp).testTag("project_composer_capsule")) {
            Text(value.ifBlank { stringResource(R.string.project_chat_input_hint) }, maxLines = 1,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

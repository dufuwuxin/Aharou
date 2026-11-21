package com.aharou.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aharou.R
import com.aharou.feature.settings.presentation.component.settingsPageBackground
import compose.icons.FeatherIcons
import compose.icons.feathericons.Home
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Sliders
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

private val primaryRoutes = listOf("projects", "config", "settings")

/** One retained owner per root: navigation entries remain aliases, never duplicate screens. */
@Composable
fun PrimaryPagesPager(
    route: String?,
    onRouteChange: (String) -> Unit,
    bottomBarModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
    projects: @Composable () -> Unit,
    settings: @Composable (config: Boolean, active: Boolean, onRootChange: (Boolean) -> Unit) -> Unit,
) {
    val routeIndex = primaryRoutes.indexOf(route)
    val visible = routeIndex >= 0
    val pager = rememberPagerState(initialPage = routeIndex.coerceAtLeast(0)) { 3 }
    var bottomDragging by remember { mutableStateOf(false) }
    var multiTouch by remember { mutableStateOf(false) }
    val navigate by rememberUpdatedState(onRouteChange)
    // Wait for both gestures to finish: scrollToPage used by the bar is instantaneous.
    LaunchedEffect(route) {
        if (!visible) return@LaunchedEffect
        if (pager.settledPage != routeIndex) pager.scrollToPage(routeIndex)
        snapshotFlow {
            if (!pager.isScrollInProgress && !bottomDragging) pager.settledPage else null
        }.distinctUntilChanged().collect { page ->
            if (page != null && primaryRoutes[page] != route) navigate(primaryRoutes[page])
        }
    }
    // Keep composition/saveable state when a secondary NavHost destination covers the roots.
    Box(
        (if (visible) modifier.fillMaxSize() else Modifier.size(0.dp)).pointerInput(visible) {
            if (!visible) return@pointerInput
            awaitEachGesture {
                var pressed: Boolean
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    multiTouch = event.changes.count { it.pressed } > 1
                    pressed = event.changes.any { it.pressed }
                } while (pressed)
                multiTouch = false
            }
        }
    ) {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 2,
            key = { primaryRoutes[it] },
            userScrollEnabled = visible && !multiTouch,
            modifier = Modifier.fillMaxSize().padding(bottom = bottomContentPadding).testTag("primary_pages_pager"),
        ) { page ->
            val active = visible && pager.currentPage == page
            PrimaryPageLifecycle(active) {
                if (page == 0) projects()
                // Section history stays local to the retained settings page.
                else settings(page == 1, active) {}
            }
        }
        if (visible) FloatingTabBar(
            pagerState = pager,
            items = listOf(
                FloatingTabItem(FeatherIcons.Home, stringResource(R.string.project_home)),
                FloatingTabItem(FeatherIcons.Sliders, stringResource(R.string.bottom_tab_config)),
                FloatingTabItem(FeatherIcons.Settings, stringResource(R.string.bottom_tab_settings)),
            ),
            maskColor = settingsPageBackground(),
            gesturesEnabled = !multiTouch,
            onDragStateChange = { bottomDragging = it },
            modifier = bottomBarModifier.align(Alignment.BottomCenter).testTag("primary_pages_tabs"),
        )
    }
}

@Composable
private fun PrimaryPageLifecycle(active: Boolean, content: @Composable () -> Unit) {
    val parent = LocalLifecycleOwner.current
    val owner = remember(parent) { object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    } }
    DisposableEffect(parent, active) {
        fun update() {
            owner.registry.currentState = if (active) parent.lifecycle.currentState
                else minOf(parent.lifecycle.currentState, Lifecycle.State.CREATED)
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        parent.lifecycle.addObserver(observer)
        update()
        onDispose { parent.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(owner) {
        onDispose { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
}

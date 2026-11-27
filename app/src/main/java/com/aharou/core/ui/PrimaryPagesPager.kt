package com.aharou.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aharou.R
import com.aharou.feature.onboarding.presentation.onboardingTarget
import compose.icons.FeatherIcons
import compose.icons.feathericons.Home
import compose.icons.feathericons.Menu
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
    modifier: Modifier = Modifier,
    projects: @Composable () -> Unit,
    settings: @Composable (config: Boolean, active: Boolean, onRootChange: (Boolean) -> Unit) -> Unit,
) {
    val routeIndex = primaryRoutes.indexOf(route)
    val visible = routeIndex >= 0
    val expandedWidth = LocalConfiguration.current.screenWidthDp >= 840
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    val pager = rememberPagerState(initialPage = routeIndex.coerceAtLeast(0)) { 3 }
    var multiTouch by remember { mutableStateOf(false) }
    val navigate by rememberUpdatedState(onRouteChange)
    // Commit a swiped root only after the pager settles; route-driven restoration remains instantaneous.
    LaunchedEffect(route) {
        if (!visible) return@LaunchedEffect
        if (pager.settledPage != routeIndex) pager.scrollToPage(routeIndex)
        snapshotFlow {
            if (!pager.isScrollInProgress) pager.settledPage else null
        }.distinctUntilChanged().collect { page ->
            if (page != null && primaryRoutes[page] != route) navigate(primaryRoutes[page])
        }
    }
    // Keep composition/saveable state when a secondary NavHost destination covers the roots.
    val pageNames = listOf(
        stringResource(R.string.project_home),
        stringResource(R.string.bottom_tab_config),
        stringResource(R.string.bottom_tab_settings),
    )
    val pageIcons = listOf(FeatherIcons.Home, FeatherIcons.Sliders, FeatherIcons.Settings)
    val drawerState = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    LaunchedEffect(visible, expandedWidth) { if (!visible || expandedWidth) drawerOpen = false }
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.isOpen }.distinctUntilChanged().collect { drawerOpen = it }
    }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }
    val selectPage: (Int) -> Unit = { page ->
        navigate(primaryRoutes[page])
        drawerOpen = false
    }
    val pageContent: @Composable () -> Unit = {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 2,
            key = { primaryRoutes[it] },
            userScrollEnabled = visible && !multiTouch,
            modifier = Modifier.fillMaxSize().testTag("primary_pages_pager"),
        ) { page ->
            val active = visible && pager.currentPage == page
            PrimaryPageLifecycle(active) {
                if (page == 0) projects()
                // Section history stays local to the retained settings page.
                else settings(page == 1, active) {}
            }
        }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet {
                Text(stringResource(R.string.primary_navigation_title), Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
                pageNames.forEachIndexed { page, title ->
                    NavigationDrawerItem(
                        label = { Text(title) },
                        selected = page == routeIndex,
                        onClick = { selectPage(page) },
                        icon = { RootNavigationIcon(pageIcons[page], page == routeIndex) },
                        modifier = Modifier.padding(horizontal = 12.dp).height(56.dp)
                            .then(
                                if (drawerOpen && page == 2) {
                                    Modifier.onboardingTarget(com.aharou.feature.onboarding.domain.OnboardingStep.ENTER_SETTINGS)
                                } else Modifier
                            )
                            .semantics { selected = page == routeIndex },
                    )
                }
            }
        },
        modifier = (if (visible) modifier.fillMaxSize() else Modifier.size(0.dp)).pointerInput(visible) {
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
        },
    ) {
        Row(Modifier.fillMaxSize()) {
            if (visible && expandedWidth) {
                NavigationRail(modifier = Modifier.fillMaxHeight().testTag("primary_pages_rail")) {
                    Spacer(Modifier.height(12.dp))
                    pageNames.forEachIndexed { page, title ->
                        NavigationRailItem(
                            selected = page == routeIndex,
                            onClick = { selectPage(page) },
                            icon = { RootNavigationIcon(pageIcons[page], page == routeIndex) },
                            label = { Text(title) },
                            modifier = Modifier
                                .then(
                                    if (page == 2) {
                                        Modifier.onboardingTarget(com.aharou.feature.onboarding.domain.OnboardingStep.ENTER_SETTINGS)
                                    } else Modifier
                                )
                                .semantics { selected = page == routeIndex },
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (visible && !expandedWidth) {
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                        IconButton(
                            onClick = { drawerOpen = true },
                            modifier = Modifier.padding(start = 4.dp).size(48.dp)
                                .onboardingTarget(com.aharou.feature.onboarding.domain.OnboardingStep.ENTER_SETTINGS)
                                .testTag("primary_pages_menu"),
                        ) {
                            Icon(FeatherIcons.Menu, contentDescription = stringResource(R.string.primary_navigation_open))
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { pageContent() }
            }
        }
    }
}

@Composable
private fun RootNavigationIcon(icon: ImageVector, selected: Boolean) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 520f),
        label = "primary_navigation_icon_scale"
    )
    Icon(icon, contentDescription = null, modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
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

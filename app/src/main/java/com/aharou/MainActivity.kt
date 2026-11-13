package com.aharou
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.EntryPointAccessors
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aharou.core.util.ACTIVITY_STATE_VERSION_KEY
import com.aharou.core.util.activityStateForVersion
import com.aharou.core.util.FileLogger
import com.aharou.core.util.PanelScriptImporter
import com.aharou.feature.agent.presentation.SharedIntakeHolder
import com.aharou.feature.agent.presentation.SharedIntakeEntryPoint
import com.aharou.feature.agent.presentation.component.ShareIntakeDialog
import com.aharou.feature.agent.presentation.component.copyUriToWorkspace
import com.aharou.feature.agent.presentation.component.toPendingAttachment
import com.aharou.feature.workspace.domain.FileAccessProvider
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aharou.core.ui.BetaWatermark
import com.aharou.core.ui.RootBackHandler
import com.aharou.core.ui.SplitPaneLayout
import com.aharou.core.ui.isExpandedWidth
import com.aharou.core.ui.pageEnter
import com.aharou.core.ui.FloatingTabBar
import com.aharou.core.ui.FloatingTabItem
import com.aharou.core.ui.pageExit
import com.aharou.feature.agent.presentation.AIAgentViewModel
import com.aharou.feature.agent.presentation.component.AIChatPanel
import com.aharou.feature.agent.presentation.component.ProjectChatScreen
import com.aharou.feature.agent.presentation.component.rememberProjectChatBinding
import com.aharou.feature.agent.presentation.component.ProjectListScreen
import com.aharou.feature.agent.presentation.component.ProjectTaskDetailScreen
import com.aharou.feature.agent.presentation.component.SessionListScreen
import com.aharou.feature.agent.presentation.ProjectTaskViewModel
import kotlinx.coroutines.flow.collect
import com.aharou.feature.editor.presentation.CodeEditorScreen
import com.aharou.feature.editor.presentation.EditorLeaveGuard
import com.aharou.feature.browser.presentation.BrowserScreen
import com.aharou.feature.git.presentation.GitViewModel
import com.aharou.feature.credentials.presentation.component.CredentialScreen
import com.aharou.feature.git.presentation.component.GitScreen
import com.aharou.feature.onboarding.data.OnboardingStatus
import com.aharou.feature.onboarding.domain.OnboardingCoordinator
import com.aharou.feature.onboarding.domain.OnboardingStateHolder
import com.aharou.feature.onboarding.domain.OnboardingStep
import com.aharou.feature.onboarding.presentation.LocalOnboardingTargetRegistry
import com.aharou.feature.onboarding.presentation.OnboardingOverlay
import com.aharou.feature.onboarding.presentation.OnboardingTargetRegistry
import com.aharou.feature.onboarding.presentation.onboardingTarget
import com.aharou.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aharou.feature.settings.data.repository.BackgroundSettingsRepository
import com.aharou.feature.settings.data.repository.ScreenOnSettingsRepository
import com.aharou.feature.settings.data.repository.ThemeSettingsRepository
import com.aharou.feature.settings.presentation.SettingsViewModel
import com.aharou.feature.settings.presentation.component.AppThemeHost
import com.aharou.feature.settings.presentation.component.SettingsScreen
import com.aharou.feature.settings.presentation.component.rememberAppAppearance
import com.aharou.feature.settings.presentation.component.decodeBackgroundBitmap
import com.aharou.feature.settings.presentation.component.settingsPageBackground
import com.aharou.feature.pet.PetOverlayService
import com.aharou.feature.terminal.domain.TerminalKeepaliveService
import com.aharou.feature.terminal.presentation.TerminalViewModel
import com.aharou.feature.terminal.presentation.component.TerminalScreen
import com.aharou.feature.workspace.presentation.WorkspaceViewModel
import com.aharou.feature.widget.WidgetActions
import dagger.hilt.android.AndroidEntryPoint
import compose.icons.FeatherIcons
import compose.icons.feathericons.Home
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Sliders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.aharou.R
/** 桌面快捷方式的自定义 action，需与 res/xml/shortcuts.xml 及清单里的 intent-filter 保持一致。 */
private const val ACTION_NEW_CHAT = "com.aharou.intent.NEW_CHAT"
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** 用于冷启动时在前台恢复常驻保活通知（App.onCreate 的启动可能被后台 FGS 限制挡掉）。 */
    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository
    @Inject
    lateinit var themeSettings: ThemeSettingsRepository
    @Inject
    lateinit var backgroundSettings: BackgroundSettingsRepository
    /** 屏幕常亮开关：只在 App 前台约束屏幕，切后台由系统自动失效。 */
    @Inject
    lateinit var screenOnSettings: ScreenOnSettingsRepository

    /** 语言偏好：attachBaseContext 时同步读取以应用 locale，变化时 recreate。 */
    @Inject
    lateinit var languageSettings: com.aharou.feature.settings.data.repository.LanguageSettingsRepository

    /** App 回到前台时，远程模式下若 SSH 断了触发重连。 */
    @Inject
    lateinit var remoteSshConnection: com.aharou.feature.agent.domain.container.RemoteSshConnection

    /** 全局主机指纹提醒：任意路径撞到未确认指纹时，App 根部弹「温柔提示」引导确认。 */
    @Inject
    lateinit var hostKeyVerifier: com.aharou.feature.agent.domain.container.SshHostKeyVerifier

    /** 外部分享文件的中转站；消费方在聊天面板。 */
    @Inject
    internal lateinit var sharedIntakeHolder: SharedIntakeHolder

    /** 分享文件要经它写进当前工作区（本地映射到宿主工作区，远程落到远程）。 */
    @Inject
    lateinit var fileAccessProvider: FileAccessProvider

    @Inject
    lateinit var executionModeHolder: com.aharou.feature.settings.data.repository.ExecutionModeHolder

    /** 三端（UI/AI Bash/交互终端）git 缺凭据统一弹窗桥：在 AIEditorApp 启动后监听 helper 的文件 IPC 请求。 */
    @Inject
    lateinit var credentialRequestBridge: com.aharou.feature.credentials.data.CredentialRequestBridge

    /** 首次启动引导的进度持久化。 */
    @Inject
    lateinit var onboardingRepository: com.aharou.feature.onboarding.data.OnboardingRepository

    /** 划词 / 桌面快捷方式等外部入口的一次性 Intent，交给 AppNavigation 落到具体界面。 */
    private val pendingExternalIntent = MutableStateFlow<Intent?>(null)

    override fun attachBaseContext(newBase: android.content.Context) {
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
        val context = newBase.createConfigurationContext(config)
        super.attachBaseContext(context)
    }

    @Suppress("DEPRECATION") // 全局更新 application resources locale，createConfigurationContext 无法替代
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop 复用本实例时 getIntent() 还指向旧的，必须显式接过来。
        setIntent(intent)
        handleShareIntent(intent)
        handleLaunchIntent(intent)
    }

    /**
     * 接收外部送来的文件（ACTION_SEND / SEND_MULTIPLE / VIEW 打开方式）：拷进工作区 attachments，
     * 投给 [sharedIntakeHolder] 由聊天面板并入输入框附件。失败只记日志，不影响正常启动。
     */
    @Suppress("DEPRECATION")
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val direct = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            // 「打开方式」来的文件把 URI 放在 data 里。
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> return
        }
        // 分享方只把 URI 塞进 clipData 的情况很常见（QQ/微信等按官方推荐这么做，EXTRA_STREAM 为空），
        // 只认 EXTRA_STREAM 会表现为「分享进来什么都没发生」：不导入、也不报错。
        val fromClip = intent.clipData?.let { clip ->
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }.orEmpty()
        val uris = direct.ifEmpty { fromClip }
        FileLogger.i(
            "MainActivity",
            "分享 intent: action=${intent.action} type=${intent.type} " +
                "extraKeys=${intent.extras?.keySet()?.joinToString()} " +
                "clipItems=${intent.clipData?.itemCount ?: 0} data=${intent.data} -> uris=$uris"
        )
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            // 脚本类文件导入 ~/.aharou/scripts/（面板脚本目录），其余当普通附件；
            // 面板分享包（.json）连同 DIY 参数一起还原。
            val importedScripts = mutableListOf<String>()
            val others = mutableListOf<Uri>()
            uris.forEach { uri ->
                val name = PanelScriptImporter.queryDisplayName(this@MainActivity, uri)
                val pkg = if (PanelScriptImporter.maybePanelPackage(name)) {
                    PanelScriptImporter.parsePackage(this@MainActivity, uri)
                } else {
                    null
                }
                when {
                    pkg != null -> PanelScriptImporter.writePackageScript(this@MainActivity, pkg)
                        ?.let { importedScripts += it }

                    PanelScriptImporter.isPanelScript(name) -> PanelScriptImporter.import(this@MainActivity, uri)
                        ?.let { importedScripts += it }

                    else -> others += uri
                }
            }
            if (importedScripts.isNotEmpty()) {
                FileLogger.i("MainActivity", "已导入面板脚本：${importedScripts.joinToString()}")
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.share_script_imported, importedScripts.joinToString()),
                    Toast.LENGTH_LONG,
                ).show()
            }
            val uploaded = others.mapNotNull { uri ->
                runCatching { copyUriToWorkspace(this@MainActivity, uri, fileAccessProvider) }
                    .onFailure { FileLogger.w("MainActivity", "分享文件导入失败：$uri", it) }
                    .getOrNull()
            }
            if (uploaded.isNotEmpty()) {
                // 文件已落地，但先不投给聊天面板：冷启动时面板可能还没组合完，投进去会落空。
                // 交给用户确认（预览 / 插入输入框 / 取消），点「插入输入框」时再投。
                sharedIntakeHolder.awaitConfirm(uploaded.map { it.toPendingAttachment() })
            }
        }
    }

    /**
     * 接收划词（ACTION_PROCESS_TEXT）与桌面快捷方式的自定义 action。
     *
     * 划词文本与「新建对话」交给 [AppNavigation]——那里才有 NavController 与 Activity 级
     * [AIAgentViewModel]，能把文本落进聊天输入框、把页面切到聊天页。
     */
    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        if (AppLaunchPolicy.accepts(intent)) pendingExternalIntent.value = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 从冷启动闪屏主题恢复为应用主主题
        setTheme(R.style.Theme_AICode)
        // 绘制到系统状态栏/导航栏之下，让应用背景与系统栏融为一体（消除割裂的色块）。
        enableEdgeToEdge()
        super.onCreate(activityStateForVersion(savedInstanceState, BuildConfig.VERSION_CODE))

        // 冷启动若由分享拉起，直接导入附件（singleTop 复用时走 [onNewIntent]）。
        handleShareIntent(intent)
        // 冷启动若由划词 / 快捷方式拉起，同样在这里接住；recreate（如切语言）时 getIntent() 仍是
        // 同一个，靠 savedInstanceState 判断，避免每次重建都重复触发。
        if (savedInstanceState == null) handleLaunchIntent(intent)

        // 屏幕常亮：窗口标志只在 Activity 可见时约束屏幕，退到后台系统自动放开，无需手动释放。
        lifecycleScope.launch {
            screenOnSettings.enabledFlow.collect { enabled ->
                if (enabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        // API 30+：全局切到 ADJUST_NOTHING，由 rememberImeBottomInset() 接管键盘内边距。
        // 必须在 Activity 级别统一设置，不能在每个 composable 里各自 save/restore——
        // NavHost 过渡动画期间新旧页面共存，旧页面 dispose 恢复 softInputMode 会触发窗口重布局导致白屏。
        if (Build.VERSION.SDK_INT >= 30) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        setContent {
            val appearance = rememberAppAppearance(themeSettings)
            val view = LocalView.current
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !appearance.darkTheme
                controller.isAppearanceLightNavigationBars = !appearance.darkTheme
            }

            AppThemeHost(appearance) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 全局自定义背景图：叠加在所有页面内容之上（水印效果），透明度可调。
                    // 不拦截触摸事件；弹窗（Dialog 独立窗口）不受层级影响。
                    val bgPath by backgroundSettings.imagePathFlow.collectAsStateWithLifecycle(initialValue = null)
                    val bgAlpha by backgroundSettings.alphaFlow.collectAsStateWithLifecycle(initialValue = BackgroundSettingsRepository.DEFAULT_ALPHA)
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppNavigation(
                            onboardingRepository = onboardingRepository,
                            pendingExternalIntent = pendingExternalIntent
                        )
                        // 全局凭据弹窗：覆盖所有页面，命令行 git 缺凭据在任意页面都能弹。
                        com.aharou.feature.credentials.presentation.component.GlobalCredentialDialogHost(
                            bridge = credentialRequestBridge
                        )
                        // 全局主机指纹确认：任意路径撞到未确认的 SSH 指纹时温柔引导确认（而非默默失败）。
                        com.aharou.feature.workspace.presentation.remote.HostKeyGlobalAlert(
                            verifier = hostKeyVerifier
                        )
                        if (bgPath != null && bgAlpha > 0f) {
                            val screen = LocalView.current
                            val bitmap by produceState<ImageBitmap?>(initialValue = null, bgPath) {
                                value = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    decodeBackgroundBitmap(
                                        bgPath!!,
                                        screen.width.coerceAtLeast(1),
                                        screen.height.coerceAtLeast(1)
                                    )
                                }
                            }
                            bitmap?.let {
                                Image(
                                    bitmap = it,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .alpha(bgAlpha)
                                )
                            }
                        }
                        // beta 包全局水印，置于最上层，不拦截触摸。
                        BetaWatermark()
                    }
                }
            }
        }

        // 此时处于前台，启动前台服务一定被允许：若用户曾开启常驻保活，补上通知。
        lifecycleScope.launch {
            if (keepaliveSettings.isEnabled()) {
                TerminalKeepaliveService.enablePersistent(this@MainActivity)
            }
        }

        // 悬浮小染同理：装包替换进程或进程被杀后服务不会自动重建，而开关还亮着，
        // 用户看到的就是「得先去设置里关一次再打开才生效」。前台时补上，不必等下一次重启。
        if (PetOverlayService.isEnabled(this) && !PetOverlayService.isRunning()) {
            PetOverlayService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // 远程模式回到前台时，若 SSH 断了立即触发重连，不等 supervisor 轮询
        if (executionModeHolder.currentMode() == com.aharou.feature.settings.data.repository.ExecutionMode.REMOTE_SSH) {
            lifecycleScope.launch {
                runCatching { remoteSshConnection.tryReconnectIfDisconnected() }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(ACTIVITY_STATE_VERSION_KEY, BuildConfig.VERSION_CODE)
    }

}

/** 大屏右栏默认占宽比，以及拖拽分栏的上下限——两边都至少留 30% 宽度。 */
private const val DEFAULT_PANE_SPLIT = 0.5f
private const val MIN_PANE_SPLIT = 0.3f
private const val MAX_PANE_SPLIT = 0.7f

@Composable
fun AppNavigation(
    onboardingRepository: com.aharou.feature.onboarding.data.OnboardingRepository,
    pendingExternalIntent: MutableStateFlow<Intent?>
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    var taskCreateRequested by remember { mutableStateOf(false) }

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val isPrimaryPage = currentRoute == "projects" || currentRoute == "config" || currentRoute == "settings"
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    val bottomBarHeight = with(LocalDensity.current) { bottomBarHeightPx.toDp() }

    LaunchedEffect(currentRoute) {
        AIEditorApp.currentRoute = currentRoute
    }

    val agentViewModel: AIAgentViewModel = hiltViewModel()
    // Activity-scoped shared owner: navigating tree/detail/chat must not cancel execution.
    val projectTaskViewModel: ProjectTaskViewModel = hiltViewModel()
    val projectTaskState by projectTaskViewModel.state.collectAsStateWithLifecycle()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val workspaceViewModel: WorkspaceViewModel = hiltViewModel()

    // ── 首次启动引导 ──
    val launchPolicy = rememberSaveable(saver = AppLaunchPolicy.StateSaver) { AppLaunchPolicy(pendingExternalIntent.value) }
    val onboardingStateHolder = remember { OnboardingStateHolder() }
    val onboardingUiState by onboardingStateHolder.state.collectAsStateWithLifecycle()
    val onboardingRegistry = remember { OnboardingTargetRegistry() }
    val onboardingScope = rememberCoroutineScope()
    val onboardingCoordinator = remember {
        OnboardingCoordinator(
            stateHolder = onboardingStateHolder,
            onPersist = { status ->
                onboardingRepository.markStatus(status)
            },
            scope = onboardingScope,
            onPersistStep = { step ->
                onboardingRepository.saveStep(step)
            }
        )
    }
    LaunchedEffect(Unit) { onboardingRepository.restartRequests.collect {
        onboardingCoordinator.initialize(OnboardingStatus.IN_PROGRESS, launchPolicy.restart())
    } }
    LaunchedEffect(Unit) {
        onboardingRepository.statusFlow.collect { status ->
            val savedStep = onboardingRepository.step()
            launchPolicy.onboardingStep(status, onboardingStateHolder.isActive, savedStep)?.let {
                onboardingCoordinator.initialize(OnboardingStatus.IN_PROGRESS, it)
            }
        }
    }

    LaunchedEffect(onboardingUiState.active) {
        val route = onboardingUiState.step.route
        if (onboardingUiState.active && currentRoute != null && currentRoute != route) {
            navController.navigate(route) { launchSingleTop = true }
        }
    }

    RootBackHandler()

    // 工作区与会话页面共用数据。
    val currentWorkspace by workspaceViewModel.current.collectAsStateWithLifecycle()
    LaunchedEffect(currentWorkspace?.path) { projectTaskViewModel.workspace(currentWorkspace?.path.orEmpty()) }
    androidx.compose.runtime.LaunchedEffect(currentWorkspace) {
        // 远程模式连接未就绪时 currentWorkspace 为 null，不触发 setWorkspace，避免空路径点燃 session 加载
        val path = currentWorkspace?.path ?: return@LaunchedEffect
        agentViewModel.setWorkspace(path)
    }

    LaunchedEffect(Unit) {
        pendingExternalIntent.collect { intent ->
            pendingExternalIntent.value = null
            if (intent == null) return@collect
            launchPolicy.consume(intent, onboardingStateHolder.state.value.step.route, { onboardingStateHolder.skip() },
                { if (navController.currentDestination?.route != "projects") navController.navigate("projects") { launchSingleTop = true; popUpTo("projects") { inclusive = false } } })
            when (intent.action) {
                Intent.ACTION_PROCESS_TEXT -> {
                    val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
                    FileLogger.i(
                        "MainActivity",
                        "划词入口：len=${text.length} " +
                            "readonly=${intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)}"
                    )
                    // 本轮只做「把文本送进聊天输入框」，不回填给原 App。
                    if (text.isNotBlank()) agentViewModel.updateInputDraft(text)
                    navController.navigate("chat") {
                        launchSingleTop = true
                        popUpTo("chat") { inclusive = false }
                    }
                }

                ACTION_NEW_CHAT -> {
                    taskCreateRequested = true
                    navController.navigate("projects") { launchSingleTop = true }
                }

                WidgetActions.ACTION_OPEN_CHAT -> {
                    // 桌面小组件的「点会话」：切到该会话再进聊天页。
                    val sessionId = WidgetActions.sessionId(intent)
                    if (sessionId != null) {
                        agentViewModel.selectSession(sessionId)
                        navController.navigate("chat") {
                            launchSingleTop = true
                            popUpTo("chat") { inclusive = false }
                        }
                    }
                }
            }
        }
    }

    // 项目会话索引覆盖工作区列表。
    val sessionsByWorkspace by agentViewModel.sessionsByWorkspace.collectAsStateWithLifecycle()
    LaunchedEffect(sessionsByWorkspace.keys) { projectTaskViewModel.indexSessions(sessionsByWorkspace.keys.toList()) }
    val currentSessionId by agentViewModel.currentSessionId.collectAsStateWithLifecycle()
    val agentStates by agentViewModel.agentStates.collectAsStateWithLifecycle()
    val awaitingPermissionSessionIds by agentViewModel.awaitingPermissionSessionIds.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    // ── 大屏（平板横屏及以上）布局 ──
    val expanded = isExpandedWidth()
    // 右栏工作台：大屏下编辑器 / 终端 / Git 与聊天并排，窄窗仍走全屏路由。
    var paneKind by rememberSaveable { mutableStateOf(WorkbenchPaneKind.NONE) }
    var paneEditorPath by rememberSaveable { mutableStateOf("") }
    var paneEditorLine by rememberSaveable { mutableIntStateOf(0) }
    var paneSplit by rememberSaveable { mutableFloatStateOf(DEFAULT_PANE_SPLIT) }
    // 右栏编辑器有未保存修改时，换文件前先弹保存提示（窄窗全屏编辑器由返回键拦截，不经过这里）。
    val editorLeaveGuard = remember { EditorLeaveGuard() }

    // Aharou：浏览器面板 + 工具消息快捷入口（地球 / 在终端运行）
    // 面板只在用户主动打开时弹出；Agent 浏览网页的过程在底部小屏幕（浮动工具条缩略图）里围观。
    var browserSheetOpen by remember { mutableStateOf(false) }
    var terminalInitCommand by rememberSaveable { mutableStateOf<String?>(null) }

    // 右栏打开时返回键先收起它。限定聊天页：其他页面右栏不渲染，不能在那里吞掉返回事件。
    BackHandler(
        enabled = expanded && currentRoute == "chat" &&
            paneKind != WorkbenchPaneKind.NONE
    ) {
        paneKind = WorkbenchPaneKind.NONE
    }

    // 打开文件：大屏进右栏，窄窗跳全屏编辑器页。
    val openFile: (String, Int) -> Unit = { filePath, line ->
        if (expanded) {
            val apply = {
                paneEditorPath = filePath
                paneEditorLine = line
                paneKind = WorkbenchPaneKind.EDITOR
            }
            // 重复点同一个文件：内容不换，只是重新走到目标行（交给编辑器的行定位 effect），
            // 不必问「要不要保存」——保存与否都不影响结果。
            if (paneKind == WorkbenchPaneKind.EDITOR && paneEditorPath == filePath) {
                apply()
            } else {
                // 编辑器里有未保存修改时，由编辑器先弹保存提示，确认后再换。
                editorLeaveGuard.requestLeave(apply)
            }
        } else {
            navController.navigate(
                "editor?path=${android.net.Uri.encode(filePath)}&line=$line"
            )
        }
    }

    // 终端 / Git：大屏在右栏内开合切换，窄窗跳全屏页。
    val openWorkbench: (WorkbenchPaneKind) -> Unit = { target ->
        if (expanded) {
            val next = if (paneKind == target) WorkbenchPaneKind.NONE else target
            // 从编辑器切走（含再点一次图标收起）同样会丢掉未保存的修改，走同一套确认。
            if (paneKind == WorkbenchPaneKind.EDITOR && next != WorkbenchPaneKind.EDITOR) {
                editorLeaveGuard.requestLeave { paneKind = next }
            } else {
                paneKind = next
            }
        } else {
            // 窄屏：跳全屏路由。若浏览器正以 sheet 形式开着，先收起它——
            // 否则路由过渡那几百毫秒里 sheet 与全屏页会同时合成，同一个 WebView 被抢挂载。
            if (target == WorkbenchPaneKind.BROWSER) browserSheetOpen = false
            val route = when (target) {
                WorkbenchPaneKind.TERMINAL -> "terminal"
                WorkbenchPaneKind.GIT -> "git"
                WorkbenchPaneKind.BROWSER -> "browser"
                else -> "chat"
            }
            navController.navigate(route)
        }
    }

    // Aharou：工具消息两个互联入口的实现
    val browserOpener: (String) -> Unit = { url ->
        // 非 http(s) 协议（tel: / mailto: / market:// / intent: 等）得在 loadUrl 之前分流：
        // loadUrl 不触发 shouldOverrideUrlLoading，直接塞进 WebView 只会得到 ERR_UNKNOWN_URL_SCHEME。
        if (com.aharou.feature.browser.presentation.BrowserExternalSchemeHandler.shouldHandleExternally(url)) {
            com.aharou.feature.browser.presentation.BrowserExternalSchemeHandler.handle(context, url)
        } else {
            runCatching {
                if (url.isNotBlank()) agentViewModel.browserTabPool.selectOrCreateTabForURL(url)
            }
            // 右栏已经是浏览器时不再叠一层 sheet —— 同一个 WebView 被两个宿主同时挂载
            // 会互相抢（BrowserWebView.update 里的 re-parent），输的那个容器空着就是白屏。
            if (paneKind != WorkbenchPaneKind.BROWSER) browserSheetOpen = true
        }
    }
    val terminalOpener: (String) -> Unit = { cmd ->
        terminalInitCommand = cmd
        openWorkbench(WorkbenchPaneKind.TERMINAL)
    }

    val navBody: @Composable () -> Unit = {
        NavHost(
            navController = navController,
            startDestination = "projects",
            // 限制过渡动画在导航正文内。
            modifier = Modifier.fillMaxSize().clipToBounds()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(bottom = if (isPrimaryPage) bottomBarHeight else 0.dp),
            enterTransition = { pageEnter(forward = true) },
            exitTransition = { pageExit(forward = true) },
            popEnterTransition = { pageEnter(forward = false) },
            popExitTransition = { pageExit(forward = false) }
        ) {
            composable("chat") {
                // 从会话详情页按返回键回到会话列表页（不再是退出 App）。
                BackHandler(enabled = true) { navController.navigate("projects") { popUpTo("chat") { inclusive = true } } }
                // 聊天区内覆盖 LocalUriHandler：Markdown 链接点击默认经此 handler 派发。
                // http/https 链接与本地 .html 文件都送内置浏览器预览；其它文件路径跳编辑器；
                // mailto/tel 等非 http 网址仍走系统外部处理。
                val defaultUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                val fileUriHandler = remember(defaultUriHandler, expanded) {
                    FileAwareUriHandler(
                        delegate = defaultUriHandler,
                        onOpenUrl = browserOpener,
                        toFileUrl = { path ->
                            runCatching {
                                val f = agentViewModel.fileAccess.copyToLocal(path)
                                if (f.isFile) android.net.Uri.fromFile(f).toString() else null
                            }.getOrNull()
                        },
                        onOpenFile = { filePath, line -> openFile(filePath, line) },
                    )
                }
                val paneOpen = expanded && paneKind != WorkbenchPaneKind.NONE
                SplitPaneLayout(
                    paneOpen = paneOpen,
                    paneSplit = paneSplit,
                    minSplit = MIN_PANE_SPLIT,
                    maxSplit = MAX_PANE_SPLIT,
                    onSplitChange = { paneSplit = it },
                    rightPane = {
                        WorkbenchPaneContent(
                            kind = paneKind,
                            editorPath = paneEditorPath,
                            editorLine = paneEditorLine,
                            onClose = { paneKind = WorkbenchPaneKind.NONE },
                            onOpenFile = { path -> openFile(path, 0) },
                            editorLeaveGuard = editorLeaveGuard,
                            onAddSelectionToInput = { text ->
                                agentViewModel.attachEditorSelectionToInput(paneEditorPath, text)
                            },
                            onSendToChat = { text -> agentViewModel.updateInputDraft(text) }, onTakeoverAgent = { agentViewModel.stopAgent() },
                        )
                    },
                    leftPane = {
                        CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalUriHandler provides fileUriHandler,
                            com.aharou.feature.browser.presentation.LocalBrowserOpener provides browserOpener,
                            com.aharou.feature.terminal.presentation.component.LocalTerminalOpener provides terminalOpener,
                            com.aharou.feature.agent.presentation.component.LocalToolPreviewOpener provides
                                { msg: com.aharou.feature.agent.presentation.AgentUIMessage ->
                                    com.aharou.feature.agent.presentation.component.ToolCallPreviewHolder.current = msg
                                    navController.navigate("toolDetail")
                                },
                        ) {
                            // 背景图铺在 AIChatPanel 之下（它的 Scaffold 已改透明）；
                            // 没设背景图时这层就是主题底色，观感与改动前一致。
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                            ) {
                            val chatAppearance =
                                com.aharou.feature.agent.presentation.component.LocalChatAppearance.current
                            com.aharou.core.ui.ChatBackgroundLayer(
                                imageName = chatAppearance.backgroundImagePath,
                                blurDp = chatAppearance.backgroundBlurDp,
                                dim = chatAppearance.backgroundDim
                            )
                            val projectChat = rememberProjectChatBinding(agentViewModel, projectTaskViewModel,
                                onBack = { navController.popBackStack() }, onToolDetail = { msg ->
                                    com.aharou.feature.agent.presentation.component.ToolCallPreviewHolder.current = msg; navController.navigate("toolDetail") })
                            AIChatPanel(
                                bodyOverride = projectChat?.body, floatingComposer = projectChat != null,
                                composerSessionId = projectChat?.sessionId,
                                viewModel = agentViewModel,
                                settingsViewModel = settingsViewModel,
                                workspaceViewModel = workspaceViewModel,
                                onBackToSessions = { navController.navigate("projects") { popUpTo(navController.graph.startDestinationId) { inclusive = true } } },
                                onCreateTask = { taskCreateRequested = true; navController.navigate("projects") { popUpTo("projects"); launchSingleTop = true } },
                                onNavigateToTerminal = { openWorkbench(WorkbenchPaneKind.TERMINAL) },
                                onNavigateToGit = { openWorkbench(WorkbenchPaneKind.GIT) },
                                onNavigateToBrowser = { openWorkbench(WorkbenchPaneKind.BROWSER) },
                                onNavigateToSandbox = { navController.navigate("sandbox") },
                                onNavigateToShared = { navController.navigate("shared") },
                                onNavigateToObserve = { navController.navigate("observe") },
                                terminalActive = paneOpen && paneKind == WorkbenchPaneKind.TERMINAL,
                                gitActive = paneOpen && paneKind == WorkbenchPaneKind.GIT,
                                browserActive = paneOpen && paneKind == WorkbenchPaneKind.BROWSER,
                                onboardingStep = if (onboardingUiState.active) onboardingUiState.step else null,
                                onSelectModelInOnboarding = {
                                    if (onboardingUiState.active && onboardingUiState.step == OnboardingStep.SIMULATE_CHOOSE_MODEL) {
                                        onboardingCoordinator.nextStep()
                                    }
                                },
                                onDismissModelSheetInOnboarding = {
                                    if (onboardingUiState.active && onboardingUiState.step == OnboardingStep.SIMULATE_CHOOSE_MODEL) {
                                        onboardingCoordinator.goToStep(OnboardingStep.OPEN_MODEL_PICKER)
                                    }
                                }
                            )
                            }
                        }
                    },
                    overlay = {
                        if (browserSheetOpen && paneKind != WorkbenchPaneKind.BROWSER) {
                            com.aharou.feature.browser.presentation.BrowserSheet(
                                tabPool = agentViewModel.browserTabPool,
                                onDismiss = { browserSheetOpen = false },
                            )
                        }
                    },
                )
            }
            composable("projects") {
                ProjectListScreen(projectTaskViewModel, taskCreateRequested, { taskCreateRequested = false },
                    onOpenProject = { id -> projectTaskViewModel.openProject(id) { navController.navigate("projects/$id") } },
                    onOpenTask = { id -> projectTaskViewModel.select(id); navController.navigate("tasks/$id") },
                    onNewSession = { scope.launch { agentViewModel.newSession().join(); navController.navigate("chat") { launchSingleTop = true } } },
                    sessionsContent = {
                val currentWsPath = currentWorkspace?.path.orEmpty()
                if (currentWsPath !in projectTaskState.indexedSessionWorkspaces) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        androidx.compose.material3.Text(stringResource(if (currentWsPath in projectTaskState.failedSessionWorkspaces)
                            R.string.project_session_index_failed else R.string.project_session_index_loading))
                    }
                } else SessionListScreen(
                    sessions = sessionsByWorkspace[currentWsPath].orEmpty().filterNot { it.id in projectTaskState.projectSessionIds },
                    currentSessionId = currentSessionId, agentStates = agentStates,
                    awaitingPermissionSessionIds = awaitingPermissionSessionIds,
                    workspaceName = currentWsPath.substringAfterLast('/'),
                    onSelect = { session -> agentViewModel.selectSession(session.id); navController.navigate("chat") { launchSingleTop = true } },
                    onDelete = { session -> agentViewModel.deleteSession(session.id) },
                    onNewSession = {}, showNewSession = false
                )
                    })
            }
            composable("projects/{projectId}") { entry ->
                ProjectChatScreen(projectId = entry.arguments?.getString("projectId").orEmpty(), agentViewModel, projectTaskViewModel,
                    settingsViewModel, workspaceViewModel, onBack = { navController.popBackStack() },
                    onToolDetail = { msg ->
                        com.aharou.feature.agent.presentation.component.ToolCallPreviewHolder.current = msg; navController.navigate("toolDetail") },
                    onNavigateToTerminal = { navController.navigate("terminal") }, onNavigateToGit = { navController.navigate("git") },
                    onNavigateToBrowser = { navController.navigate("browser") }, onNavigateToSandbox = { navController.navigate("sandbox") },
                    onNavigateToShared = { navController.navigate("shared") }, onNavigateToObserve = { navController.navigate("observe") })
            }
            composable("tasks/{taskId}") { entry ->
                ProjectTaskDetailScreen(taskId = entry.arguments?.getString("taskId").orEmpty(), viewModel = projectTaskViewModel,
                    onBack = { navController.popBackStack() },
                    onSelectSession = { id -> agentViewModel.selectSession(id); navController.navigate("chat") { launchSingleTop = true } },
                    runExecution = { sessionId, requirement, workspace ->
                        val turnId = java.util.UUID.randomUUID().toString()
                        val job = agentViewModel.executeAgentRequestStream(request = requirement,
                            projectRoot = workspace, targetSessionId = sessionId, skipTitleUpdate = true,
                            skipCommandDispatch = true, executionTurnId = turnId)
                        try { job.join() } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            agentViewModel.stopAgentSession(sessionId)
                            throw cancelled
                        }
                        agentViewModel.executionOutcome(sessionId, turnId)
                    }
                )
            }
            composable("config") {
                // 「配置」页：只展 AI 组（提示词/记忆/技能/团队/工具等 AI 相关设置）。
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onStopAllAndCloseTerminal = { agentViewModel.stopAllAndCloseTerminal() },
                    menuGroups = listOf(com.aharou.feature.settings.presentation.component.SettingsSection.AiGroup),
                    onboardingStep = onboardingUiState.step.takeIf { onboardingUiState.active },
                    onOnboardingModelAdded = {
                        if (onboardingUiState.active && onboardingUiState.step == OnboardingStep.SIMULATE_FETCH_DIALOG) {
                            navController.popBackStack()
                            onboardingCoordinator.nextStep()
                        }
                    },
                    onOnboardingDismissFetchDialog = {
                        if (onboardingUiState.active && onboardingUiState.step == OnboardingStep.SIMULATE_FETCH_DIALOG) {
                            onboardingCoordinator.goToStep(OnboardingStep.PROVIDER_FETCH_MODELS)
                        }
                    }
                )
            }
            composable("settings") {
                // 复用 Activity 级 settingsViewModel（MainActivity 顶部已创建并 init），
                // 避免进入设置页时重复创建实例与订阅。
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onStopAllAndCloseTerminal = { agentViewModel.stopAllAndCloseTerminal() }
                )
            }
            composable("terminal") { entry ->
                val terminalViewModel: TerminalViewModel = hiltViewModel()
                TerminalScreen(
                    viewModel = terminalViewModel,
                    onNavigateBack = {
                        // 退场期间仍可能收到点击，不能弹出已经成为当前页的聊天首页。
                        if (navController.currentBackStackEntry === entry) {
                            navController.popBackStack()
                        }
                    },
                    initCommand = terminalInitCommand,
                    onInitCommandConsumed = { terminalInitCommand = null },
                )
            }
            composable("browser") {
                val browserViewModel: com.aharou.feature.browser.presentation.BrowserViewModel = hiltViewModel()
                BrowserScreen(
                    tabPool = browserViewModel.tabPool,
                    onNavigateBack = { navController.popBackStack() }, onTakeoverAgent = { agentViewModel.stopAgent() },
                )
            }
            composable("sandbox") {
                val sandboxContext = androidx.compose.ui.platform.LocalContext.current
                // 浏览根取当前选中容器的 rootfs（容器内 `/`）：写死内置 rootfs 会让自定义镜像用户
                // 点进来只看到空目录。
                val sandboxRootVm: com.aharou.feature.sandbox.SandboxRootViewModel = hiltViewModel()
                val sandboxRoot by sandboxRootVm.rootfsDir.collectAsStateWithLifecycle()
                val sandboxVm = remember(sandboxRoot) {
                    com.aharou.feature.sandbox.FileBrowserViewModel(
                        rootPath = sandboxRoot,
                        rootLabel = "/",
                        appContext = sandboxContext.applicationContext,
                    )
                }
                com.aharou.feature.sandbox.FileBrowserScreen(
                    viewModel = sandboxVm,
                    onBack = { navController.popBackStack() },
                    onPreviewFile = { item ->
                        com.aharou.feature.sandbox.FilePreviewHolder.currentItem = item
                        navController.navigate("sandboxPreview")
                    },
                )
            }
            composable("observe") {
                // 只读观察：看哪棵树由入口写进 ObserveTargetHolder，这里不拼导航参数。
                com.aharou.feature.agent.presentation.observe.ObserveScreenRoute(
                    onBack = { navController.popBackStack() }
                )
            }
            composable("shared") {
                val sharedContext = androidx.compose.ui.platform.LocalContext.current
                val sharedVm = remember {
                    com.aharou.feature.sandbox.FileBrowserViewModel(
                        rootPath = com.aharou.feature.agent.domain.container.ContainerInstaller
                            .sharedDirOf(sharedContext),
                        appContext = sharedContext.applicationContext,
                    )
                }
                com.aharou.feature.sandbox.FileBrowserScreen(
                    viewModel = sharedVm,
                    onBack = { navController.popBackStack() },
                    onPreviewFile = { item ->
                        com.aharou.feature.sandbox.FilePreviewHolder.currentItem = item
                        navController.navigate("sandboxPreview")
                    },
                )
            }
            composable("sandboxPreview") {
                val previewItem = com.aharou.feature.sandbox.FilePreviewHolder.currentItem
                if (previewItem == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    com.aharou.feature.sandbox.FilePreviewScreen(
                        item = previewItem,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable("toolDetail") {
                val previewed = com.aharou.feature.agent.presentation.component.ToolCallPreviewHolder.current
                if (previewed == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    com.aharou.feature.agent.presentation.component.ToolCallPreviewScreen(
                        message = previewed,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable("git") {
                val gitViewModel: GitViewModel = hiltViewModel()
                GitScreen(
                    viewModel = gitViewModel,
                    onNavigateToCredentials = { navController.navigate("credentials") },
                    onNavigateBack = { navController.popBackStack() },
                    onOpenFile = { path -> openFile(path, 0) },
                    onAnalyzeWithAi = { text -> agentViewModel.updateInputDraft(text) }
                )
            }
            composable("credentials") {
                val credentialViewModel: com.aharou.feature.credentials.presentation.CredentialViewModel = hiltViewModel()
                CredentialScreen(
                    viewModel = credentialViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "editor?path={path}&line={line}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("line") {
                        type = NavType.IntType
                        defaultValue = 0
                    }
                )
            ) { entry ->
                CodeEditorScreen(
                    path = entry.arguments?.getString("path").orEmpty(),
                    initialLine = entry.arguments?.getInt("line") ?: 0,
                    onBack = {
                        navController.popBackStack()
                    },
                    onAddSelectionToInput = { text ->
                        agentViewModel.attachEditorSelectionToInput(
                            entry.arguments?.getString("path").orEmpty(),
                            text
                        )
                    }
                )
            }
        }
    }

    CompositionLocalProvider(
        LocalOnboardingTargetRegistry provides onboardingRegistry
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
    navBody()

    // 四个一级页共享底栏实测高度（含底边距），聊天等子路由不留空白。
    val activeTab = when (currentRoute) {
        "config" -> 1
        "settings" -> 2
        else -> 0
    }
    if (isPrimaryPage) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.BottomCenter
        ) {
            FloatingTabBar(
                selected = activeTab,
                onSelect = { i ->
                    // 点当前 tab 不重复导航；点其它一级 tab 切过去（popUpTo 起点，不叠加返回栈）。
                    if (i != activeTab) {
                        val route = when (i) {
                            0 -> "projects"
                            1 -> "config"
                            else -> "settings"
                        }
                        navController.navigate(route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                items = listOf(
                    FloatingTabItem(FeatherIcons.Home, stringResource(R.string.project_home)),
                    FloatingTabItem(FeatherIcons.Sliders, stringResource(R.string.bottom_tab_config)),
                    FloatingTabItem(FeatherIcons.Settings, stringResource(R.string.bottom_tab_settings)),
                ),
                maskColor = settingsPageBackground(),
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .onboardingTarget(OnboardingStep.ENTER_SETTINGS)
                    .onSizeChanged { bottomBarHeightPx = it.height }
                    .padding(bottom = 8.dp),
            )
        }
    }

    // 外部分享进来的文件：确认「预览 / 插入输入框 / 取消」后才投给聊天面板。
    // 本函数是独立 Composable，够不到 Activity 的注入字段，走 EntryPoint 取同一个单例。
    val intakeContext = LocalContext.current.applicationContext
    val intakeHolder = remember(intakeContext) {
        EntryPointAccessors.fromApplication(intakeContext, SharedIntakeEntryPoint::class.java)
            .sharedIntakeHolder()
    }
    val awaitingShareItems by intakeHolder.awaitingConfirm.collectAsStateWithLifecycle()
    if (awaitingShareItems.isNotEmpty()) {
        ShareIntakeDialog(
            items = awaitingShareItems,
            onPreview = { item ->
                openFile(item.containerPath, 0)
                intakeHolder.clearAwaitingConfirm()
            },
            onInsert = {
                intakeHolder.submit(awaitingShareItems)
                intakeHolder.clearAwaitingConfirm()
            },
            onDismiss = { intakeHolder.clearAwaitingConfirm() }
        )
    }

    // 首次启动引导覆层（覆盖所有路由之上，全程常驻顶层，页面切换平滑过渡绝不闪烁）。
    if (onboardingUiState.active) {
        OnboardingOverlay(
            stateHolder = onboardingStateHolder,
            coordinator = onboardingCoordinator,
            registry = onboardingRegistry,
            onNextStep = {
                val step = onboardingStateHolder.state.value.step
                when (step) {
                    OnboardingStep.ENTER_SETTINGS -> {
                        navController.navigate("config")
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.CONFIG_PROVIDER,
                    OnboardingStep.PROVIDER_ADD,
                    OnboardingStep.PROVIDER_CONFIG_INFO,
                    OnboardingStep.PROVIDER_FETCH_MODELS,
                    OnboardingStep.OPEN_MODEL_PICKER -> onboardingCoordinator.nextStep()
                    OnboardingStep.SIMULATE_FETCH_DIALOG -> {
                        // 弹窗里的模型是演示态，不落进用户真实的服务商配置
                        navController.popBackStack()
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SIMULATE_CHOOSE_MODEL -> {
                        val current = settingsViewModel.providers.value.firstOrNull()
                        if (current != null && current.models.isNotEmpty()) {
                            agentViewModel.setSessionProviderModel(current.id, current.effectiveModel.ifEmpty { current.models.first() })
                        }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SEND_MESSAGE -> {
                        onboardingCoordinator.complete()
                    }
                }
            },
            onTargetClick = {
                val step = onboardingStateHolder.state.value.step
                when (step) {
                    OnboardingStep.ENTER_SETTINGS -> {
                        navController.navigate("config")
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.CONFIG_PROVIDER,
                    OnboardingStep.PROVIDER_ADD,
                    OnboardingStep.PROVIDER_CONFIG_INFO,
                    OnboardingStep.PROVIDER_FETCH_MODELS,
                    OnboardingStep.OPEN_MODEL_PICKER -> onboardingCoordinator.nextStep()
                    OnboardingStep.SIMULATE_FETCH_DIALOG -> {
                        // 弹窗里的模型是演示态，不落进用户真实的服务商配置
                        navController.popBackStack()
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SIMULATE_CHOOSE_MODEL -> {
                        val current = settingsViewModel.providers.value.firstOrNull()
                        if (current != null && current.models.isNotEmpty()) {
                            agentViewModel.setSessionProviderModel(current.id, current.effectiveModel.ifEmpty { current.models.first() })
                        }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SEND_MESSAGE -> {
                        onboardingCoordinator.complete()
                    }
                }
            }
        )
    }
    }
    }
}

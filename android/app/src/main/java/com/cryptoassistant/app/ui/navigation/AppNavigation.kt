package com.cryptoassistant.app.ui.navigation

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.A11Y_DISCLOSURE_VERSION
import com.cryptoassistant.app.data.AppSettings
import com.cryptoassistant.app.data.SettingsRepository
import com.cryptoassistant.app.data.keys.KeyRepository
import com.cryptoassistant.app.data.log.AppLog
import com.cryptoassistant.app.feature.common.rememberOverlayPermission
import com.cryptoassistant.app.feature.contacts.ContactsScreen
import com.cryptoassistant.app.feature.files.FileTaskMode
import com.cryptoassistant.app.feature.files.FileTaskScreen
import com.cryptoassistant.app.feature.guide.AppGuideScreen
import com.cryptoassistant.app.feature.guide.GuideChapterScreen
import com.cryptoassistant.app.feature.guide.GuideRef
import com.cryptoassistant.app.feature.guide.GuideSectionScreen
import com.cryptoassistant.app.feature.home.HomeScreen
import com.cryptoassistant.app.feature.keys.KeysScreen
import com.cryptoassistant.app.feature.open.IncomingFiles
import com.cryptoassistant.app.feature.security.BiometricAuth
import com.cryptoassistant.app.feature.security.BiometricAuthResult
import com.cryptoassistant.app.feature.security.SecurityAction
import com.cryptoassistant.app.feature.security.SecurityLockPolicy
import com.cryptoassistant.app.feature.security.SecuritySession
import com.cryptoassistant.app.feature.security.findFragmentActivity
import com.cryptoassistant.app.feature.settings.AboutSettingsScreen
import com.cryptoassistant.app.feature.settings.AppearanceSettingsScreen
import com.cryptoassistant.app.feature.settings.BallSettingsScreen
import com.cryptoassistant.app.feature.settings.FileProcessingSettingsScreen
import com.cryptoassistant.app.feature.settings.LogSettingsScreen
import com.cryptoassistant.app.feature.settings.SecuritySettingsScreen
import com.cryptoassistant.app.feature.settings.SettingsScreen
import com.cryptoassistant.app.overlay.BallToggle
import com.cryptoassistant.app.overlay.OverlayService
import com.cryptoassistant.app.service.TextAccessibilityService
import com.cryptoassistant.app.tile.TileSync
import com.cryptoassistant.app.ui.components.StatusPill
import com.cryptoassistant.app.ui.components.StatusTone
import kotlinx.coroutines.launch

object AppRoutes {
    const val HOME = "home"
    const val CONTACTS = "contacts"
    const val SETTINGS = "settings"

    const val FILES_ENCRYPT = "files/encrypt"
    const val FILES_DECRYPT = "files/decrypt"
    const val KEYS = "keys"
    const val GUIDE = "guide"
    const val GUIDE_CHAPTER = "guide/{chapterId}"
    const val GUIDE_SECTION = "guide/{chapterId}/{sectionId}"

    fun guideChapter(chapterId: String): String = "guide/$chapterId"

    fun guideSection(chapterId: String, sectionId: String): String =
        "guide/$chapterId/$sectionId"

    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_BALL = "settings/ball"
    const val SETTINGS_SECURITY = "settings/security"
    const val SETTINGS_FILE_PROCESSING = "settings/file-processing"
    const val SETTINGS_LOGS = "settings/logs"
    const val SETTINGS_ABOUT = "settings/about"
}

private fun NavHostController.openRoute(route: String) {
    AppLog.debug("nav.open", "route=$route")
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * 工具书内部跳转只 push，不做 `popUpTo(startDestination)`。
 *
 * 同一个 `guide/{chapterId}/{sectionId}` 目的地会用不同参数反复进入；
 * 不能带 `launchSingleTop`，否则参数会覆盖当前文章，返回键回不到上一篇。
 */
private fun NavHostController.openGuideRoute(route: String) {
    AppLog.debug("nav.open", "route=$route")
    navigate(route)
}

private fun NavHostController.openGuideHome() {
    if (!popBackStack(AppRoutes.GUIDE, inclusive = false)) {
        navigate(AppRoutes.GUIDE) { launchSingleTop = true }
    }
}

/** 从文章点面包屑里的章名：清掉文章，落到章页。 */
private fun NavHostController.openGuideChapterFromSection(chapterId: String) {
    navigate(AppRoutes.guideChapter(chapterId)) {
        popUpTo(AppRoutes.GUIDE) { inclusive = false }
        launchSingleTop = true
    }
}

private data class BottomDestination(
    val route: String,
    @get:StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val bottomDestinations = listOf(
    BottomDestination(AppRoutes.HOME, R.string.nav_home, Icons.Outlined.Home),
    BottomDestination(AppRoutes.CONTACTS, R.string.nav_contacts, Icons.Outlined.People),
    BottomDestination(AppRoutes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings),
)

private val bottomRoutes = bottomDestinations.map { it.route }.toSet()

@StringRes
private fun titleFor(route: String?): Int = when (route) {
    AppRoutes.HOME -> R.string.app_name
    AppRoutes.CONTACTS -> R.string.nav_contacts
    AppRoutes.SETTINGS -> R.string.nav_settings
    AppRoutes.FILES_ENCRYPT -> R.string.nav_title_encrypt_files
    AppRoutes.FILES_DECRYPT -> R.string.nav_title_decrypt_files
    AppRoutes.KEYS -> R.string.nav_title_keys
    AppRoutes.GUIDE -> R.string.nav_title_guide
    AppRoutes.GUIDE_CHAPTER -> R.string.nav_title_guide
    AppRoutes.GUIDE_SECTION -> R.string.nav_title_guide
    AppRoutes.SETTINGS_APPEARANCE -> R.string.nav_title_appearance
    AppRoutes.SETTINGS_BALL -> R.string.nav_title_ball
    AppRoutes.SETTINGS_SECURITY -> R.string.nav_title_security
    AppRoutes.SETTINGS_FILE_PROCESSING -> R.string.nav_title_file_processing
    AppRoutes.SETTINGS_LOGS -> R.string.nav_title_logs
    AppRoutes.SETTINGS_ABOUT -> R.string.nav_title_about
    else -> R.string.app_name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    repository: SettingsRepository,
    keyRepository: KeyRepository,
    settings: AppSettings,
    pendingRoute: String?,
    onPendingRouteConsumed: () -> Unit,
    importRequest: IncomingFiles? = null,
    onImportConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val overlay = rememberOverlayPermission()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val keySnapshot by keyRepository.snapshot.collectAsStateWithLifecycle()
    val keyError by keyRepository.error.collectAsStateWithLifecycle()
    val logLevel by AppLog.level.collectAsStateWithLifecycle()
    // “系统设置里已启用”和“服务真的在运行”是两件事：页面与点球提示都要看后者。
    val accessibilityConnected by TextAccessibilityService.connected.collectAsStateWithLifecycle()
    var biometricAvailability by remember { mutableStateOf(BiometricAuth.availability(context)) }

    // 所有要验证的动作都走这一处：安全锁关闭时直接放行，开启时先弹系统面板。
    val securityAuthorize: suspend (SecurityAction) -> Boolean = { action ->
        // 以 DataStore 的最新值为准，避免“刚开启就立刻导出”时读到旧的组合快照。
        val lockEnabled = runCatching { repository.current().securityLockEnabled }
            .getOrDefault(settings.securityLockEnabled)
        if (!SecurityLockPolicy.requiresAuth(action, lockEnabled)) {
            true
        } else {
            val activity = context.findFragmentActivity()
            if (activity == null) {
                AppLog.warn("security.auth", "action=${action.logName} result=no_host")
                Toast.makeText(
                    context,
                    R.string.security_host_unavailable,
                    Toast.LENGTH_LONG,
                ).show()
                false
            } else {
                when (val result = BiometricAuth.authenticate(activity, action)) {
                    BiometricAuthResult.Success -> true

                    BiometricAuthResult.Cancelled -> {
                        Toast.makeText(
                            context,
                            R.string.security_cancelled,
                            Toast.LENGTH_SHORT,
                        ).show()
                        false
                    }

                    is BiometricAuthResult.Unavailable -> {
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        false
                    }

                    is BiometricAuthResult.Failed -> {
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        false
                    }
                }
            }
        }
    }

    // 启停逻辑与快捷设置磁贴共用 BallToggle，避免两处行为分叉。
    val enableBall: () -> Unit = {
        if (overlay.granted) {
            scope.launch { BallToggle.enable(context, repository) }
        } else {
            overlay.request()
        }
    }
    val disableBall: () -> Unit = {
        scope.launch { BallToggle.disable(context, repository) }
    }

    // 无障碍开关只能由用户在系统设置里改，回到应用时重新读一次。
    var accessibilityEnabled by remember {
        mutableStateOf(TextAccessibilityService.isEnabled(context))
    }
    // 权限状态只在变化时记一条 DEBUG，避免每次回到前台都刷屏。
    var lastOverlayGranted by remember { mutableStateOf(overlay.granted) }
    var lastA11yEnabled by remember { mutableStateOf(accessibilityEnabled) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = TextAccessibilityService.isEnabled(context)
                biometricAvailability = BiometricAuth.availability(context)
                if (overlay.granted != lastOverlayGranted) {
                    lastOverlayGranted = overlay.granted
                    AppLog.debug("permission.overlay", "granted=${overlay.granted}")
                }
                if (accessibilityEnabled != lastA11yEnabled) {
                    lastA11yEnabled = accessibilityEnabled
                    AppLog.debug("permission.a11y", "enabled=$accessibilityEnabled")
                }
                // 悬浮窗权限可能刚在系统设置里改过，磁贴需要跟着重新判定。
                TileSync.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val openAccessibilitySettings: () -> Unit = {
        // 权限设置页属于外部流程，停留时间不计入应用锁。
        SecuritySession.beginExternalFlow()
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { SecuritySession.endExternalFlow() }
    }

    LaunchedEffect(settings.ballEnabled, overlay.granted) {
        if (settings.ballEnabled && overlay.granted) {
            OverlayService.start(context)
        }
        // 应用内改了开关，控制中心里的磁贴要立即同步。
        TileSync.refresh(context)
    }

    var lastThemeMode by remember { mutableStateOf(settings.themeMode) }
    var lastDynamicColor by remember { mutableStateOf(settings.dynamicColor) }
    var lastAccentPalette by remember { mutableStateOf(settings.accentPalette) }
    var lastAmoledDark by remember { mutableStateOf(settings.amoledDark) }
    var lastBallSize by remember { mutableStateOf(settings.ballSize) }
    var lastBallOpacity by remember { mutableStateOf(settings.ballOpacity) }
    LaunchedEffect(
        settings.themeMode,
        settings.dynamicColor,
        settings.accentPalette,
        settings.amoledDark,
        settings.ballSize,
        settings.ballOpacity,
    ) {
        if (lastThemeMode != settings.themeMode ||
            lastDynamicColor != settings.dynamicColor ||
            lastAccentPalette != settings.accentPalette ||
            lastAmoledDark != settings.amoledDark ||
            lastBallSize != settings.ballSize ||
            lastBallOpacity != settings.ballOpacity
        ) {
            lastThemeMode = settings.themeMode
            lastDynamicColor = settings.dynamicColor
            lastAccentPalette = settings.accentPalette
            lastAmoledDark = settings.amoledDark
            lastBallSize = settings.ballSize
            lastBallOpacity = settings.ballOpacity
            if (settings.ballEnabled && overlay.granted) {
                OverlayService.refreshTheme(context)
            }
        }
    }

    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        AppLog.debug("nav.open", "route=$route external=true")
        // 外部入口（adb/测试 Intent）可能传入不存在的路由；不能让未知路由结束进程。
        val navigated = runCatching {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }.isSuccess
        if (!navigated) {
            AppLog.warn("nav.open", "route=$route result=invalid")
            runCatching {
                navController.navigate(AppRoutes.HOME) {
                    popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
        onPendingRouteConsumed()
    }

    val isHome = currentRoute == null || currentRoute == AppRoutes.HOME
    val isBottomRoute = currentRoute == null || currentRoute in bottomRoutes
    val title = stringResource(titleFor(currentRoute))

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = title, style = MaterialTheme.typography.titleLarge)
                        if (isHome) {
                            Text(
                                text = stringResource(R.string.nav_home_subtitle),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (!isBottomRoute) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.nav_back),
                            )
                        }
                    }
                },
                actions = {
                    if (isHome) {
                        StatusPill(
                            text = stringResource(R.string.nav_status_offline),
                            tone = StatusTone.SUCCESS,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            if (isBottomRoute) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    bottomDestinations.forEach { destination ->
                        val label = stringResource(destination.labelRes)
                        NavigationBarItem(
                            selected = (currentRoute ?: AppRoutes.HOME) == destination.route,
                            onClick = {
                                AppLog.debug("nav.open", "route=${destination.route}")
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = label,
                                )
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppRoutes.HOME,
            modifier = Modifier.padding(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding(),
            ),
        ) {
            composable(AppRoutes.HOME) {
                HomeScreen(
                    identity = keySnapshot.identity,
                    onOpenKeys = { navController.openRoute(AppRoutes.KEYS) },
                    onOpenEncryptFiles = { navController.openRoute(AppRoutes.FILES_ENCRYPT) },
                    onOpenDecryptFiles = { navController.openRoute(AppRoutes.FILES_DECRYPT) },
                    onOpenGuide = { navController.openRoute(AppRoutes.GUIDE) },
                )
            }

            composable(AppRoutes.CONTACTS) {
                ContactsScreen(
                    contacts = keySnapshot.contacts,
                    hasIdentity = keySnapshot.identity != null,
                    onAdd = { name, keyText -> keyRepository.addContact(name, keyText) },
                    onRemove = { id -> keyRepository.removeContact(id) },
                )
            }

            composable(AppRoutes.FILES_ENCRYPT) {
                FileTaskScreen(
                    keySnapshot = keySnapshot,
                    onOpenKeys = { navController.openRoute(AppRoutes.KEYS) },
                    onOpenContacts = { navController.openRoute(AppRoutes.CONTACTS) },
                    initialMode = FileTaskMode.ENCRYPT,
                    importRequest = importRequest,
                    onImportConsumed = onImportConsumed,
                    archiveVolumeBytes = settings.archiveVolumeSize.bytes,
                    defaultPackageEnabled = settings.packageMultipleFiles,
                )
            }

            composable(AppRoutes.FILES_DECRYPT) {
                FileTaskScreen(
                    keySnapshot = keySnapshot,
                    onOpenKeys = { navController.openRoute(AppRoutes.KEYS) },
                    onOpenContacts = { navController.openRoute(AppRoutes.CONTACTS) },
                    initialMode = FileTaskMode.DECRYPT,
                    importRequest = importRequest,
                    onImportConsumed = onImportConsumed,
                    archiveVolumeBytes = settings.archiveVolumeSize.bytes,
                    defaultPackageEnabled = settings.packageMultipleFiles,
                )
            }

            composable(AppRoutes.KEYS) {
                KeysScreen(
                    snapshot = keySnapshot,
                    error = keyError,
                    onCreate = { name -> keyRepository.createIdentity(name) },
                    onImport = { name, backup, password ->
                        keyRepository.importIdentity(name, backup, password)
                    },
                    onExport = { password -> keyRepository.exportIdentityBackup(password) },
                    onDelete = { keyRepository.deleteIdentity() },
                    onAuthorize = securityAuthorize,
                    securityLockEnabled = settings.securityLockEnabled,
                    onClearError = { keyRepository.clearError() },
                )
            }

            composable(AppRoutes.GUIDE) {
                AppGuideScreen(
                    onOpenChapter = { chapterId ->
                        navController.openGuideRoute(AppRoutes.guideChapter(chapterId))
                    },
                    onOpenSection = { ref ->
                        navController.openGuideRoute(
                            AppRoutes.guideSection(ref.chapterId, ref.sectionId),
                        )
                    },
                )
            }

            composable(
                route = AppRoutes.GUIDE_CHAPTER,
                arguments = listOf(navArgument("chapterId") { type = NavType.StringType }),
            ) { entry ->
                GuideChapterScreen(
                    chapterId = entry.arguments?.getString("chapterId").orEmpty(),
                    onOpenGuideHome = { navController.openGuideHome() },
                    onOpenSection = { ref: GuideRef ->
                        navController.openGuideRoute(
                            AppRoutes.guideSection(ref.chapterId, ref.sectionId),
                        )
                    },
                    onOpenChapter = { chapterId ->
                        navController.openGuideRoute(AppRoutes.guideChapter(chapterId))
                    },
                )
            }

            composable(
                route = AppRoutes.GUIDE_SECTION,
                arguments = listOf(
                    navArgument("chapterId") { type = NavType.StringType },
                    navArgument("sectionId") { type = NavType.StringType },
                ),
            ) { entry ->
                GuideSectionScreen(
                    chapterId = entry.arguments?.getString("chapterId").orEmpty(),
                    sectionId = entry.arguments?.getString("sectionId").orEmpty(),
                    onOpenGuideHome = { navController.openGuideHome() },
                    onOpenChapter = { chapterId ->
                        navController.openGuideChapterFromSection(chapterId)
                    },
                    onOpenSection = { ref: GuideRef ->
                        navController.openGuideRoute(
                            AppRoutes.guideSection(ref.chapterId, ref.sectionId),
                        )
                    },
                )
            }

            composable(AppRoutes.SETTINGS) {
                SettingsScreen(
                    settings = settings,
                    logLevel = logLevel,
                    overlayGranted = overlay.granted,
                    accessibilityEnabled = accessibilityEnabled,
                    accessibilityConnected = accessibilityConnected,
                    onNavigate = { route ->
                        navController.navigate(route) { launchSingleTop = true }
                    },
                )
            }

            composable(AppRoutes.SETTINGS_APPEARANCE) {
                AppearanceSettingsScreen(
                    settings = settings,
                    onThemeModeChange = { mode -> scope.launch { repository.setThemeMode(mode) } },
                    onDynamicColorChange = { enabled ->
                        scope.launch { repository.setDynamicColor(enabled) }
                    },
                    onAccentPaletteChange = { palette ->
                        scope.launch { repository.setAccentPalette(palette) }
                    },
                    onAmoledDarkChange = { enabled ->
                        scope.launch { repository.setAmoledDark(enabled) }
                    },
                )
            }

            composable(AppRoutes.SETTINGS_BALL) {
                BallSettingsScreen(
                    settings = settings,
                    overlayGranted = overlay.granted,
                    accessibilityEnabled = accessibilityEnabled,
                    accessibilityConnected = accessibilityConnected,
                    onEnableBall = enableBall,
                    onDisableBall = disableBall,
                    onRequestOverlayPermission = { overlay.request() },
                    onOpenAccessibility = openAccessibilitySettings,
                    onRecordA11yConsent = {
                        scope.launch {
                            repository.recordA11yConsent(A11Y_DISCLOSURE_VERSION)
                        }
                    },
                    onFilePipelineChange = { enabled ->
                        scope.launch { repository.setFileQuickPipeline(enabled) }
                    },
                    onBallWorkModeChange = { mode ->
                        scope.launch { repository.setBallWorkMode(mode) }
                    },
                    onBallSizeChange = { size ->
                        scope.launch { repository.setBallSize(size) }
                    },
                    onBallOpacityChange = { opacity ->
                        scope.launch { repository.setBallOpacity(opacity) }
                    },
                )
            }

            composable(AppRoutes.SETTINGS_SECURITY) {
                SecuritySettingsScreen(
                    enabled = settings.securityLockEnabled,
                    appLockEnabled = settings.appLockEnabled,
                    lockTimeoutMinutes = settings.lockTimeoutMinutes,
                    availability = biometricAvailability,
                    onSecurityLockChange = { enabled ->
                        val action = if (enabled) {
                            SecurityAction.ENABLE_LOCK
                        } else {
                            SecurityAction.DISABLE_LOCK
                        }
                        if (securityAuthorize(action)) {
                            repository.setSecurityLockEnabled(enabled)
                            // 关闭总开关时一并关闭应用锁，避免留下不再生效的孤儿配置。
                            if (!enabled) repository.setAppLockEnabled(false)
                        }
                    },
                    onAppLockChange = { enabled ->
                        val action = if (enabled) {
                            SecurityAction.APP_LOCK_ENABLE
                        } else {
                            SecurityAction.APP_LOCK_DISABLE
                        }
                        if (securityAuthorize(action)) {
                            repository.setAppLockEnabled(enabled)
                        }
                    },
                    onLockTimeoutChange = { minutes ->
                        scope.launch { repository.setLockTimeoutMinutes(minutes) }
                    },
                )
            }

            composable(AppRoutes.SETTINGS_FILE_PROCESSING) {
                FileProcessingSettingsScreen(
                    settings = settings,
                    onArchiveVolumeSizeChange = { size ->
                        scope.launch { repository.setArchiveVolumeSize(size) }
                    },
                    onPackageMultipleFilesChange = { enabled ->
                        scope.launch { repository.setPackageMultipleFiles(enabled) }
                    },
                )
            }

            composable(AppRoutes.SETTINGS_LOGS) {
                LogSettingsScreen(
                    logLevel = logLevel,
                    onLevelChange = { level -> AppLog.setLevel(level) },
                    onAuthorize = securityAuthorize,
                )
            }

            composable(AppRoutes.SETTINGS_ABOUT) {
                AboutSettingsScreen(
                    updateCheckEnabled = settings.updateCheckEnabled,
                    onUpdateCheckEnabledChange = { enabled ->
                        scope.launch { repository.setUpdateCheckEnabled(enabled) }
                    },
                )
            }
        }
    }
}

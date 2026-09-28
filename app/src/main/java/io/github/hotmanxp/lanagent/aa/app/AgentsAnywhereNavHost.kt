package io.github.hotmanxp.lanagent.aa.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.hotmanxp.lanagent.aa.feature.auth.WebLoginViewModel
import io.github.hotmanxp.lanagent.aa.feature.devices.DeviceRuntime
import io.github.hotmanxp.lanagent.aa.feature.devices.DevicePairingStatus
import io.github.hotmanxp.lanagent.aa.feature.devices.DeviceRuntimeList
import io.github.hotmanxp.lanagent.aa.feature.devices.DeviceSetupCredential
import io.github.hotmanxp.lanagent.aa.feature.files.FilesController
import io.github.hotmanxp.lanagent.aa.feature.realtime.SessionRealtimeController
import io.github.hotmanxp.lanagent.aa.feature.sessiondetail.SessionDetailController
import io.github.hotmanxp.lanagent.aa.feature.sessions.NewSessionCreateDraft
import io.github.hotmanxp.lanagent.aa.feature.sessions.NewSessionCreateOutcome
import io.github.hotmanxp.lanagent.aa.feature.sessions.NewSessionDirectory
import io.github.hotmanxp.lanagent.aa.feature.sessions.NewSessionDraft
import io.github.hotmanxp.lanagent.aa.feature.sessions.ProjectSessionLoadKey
import io.github.hotmanxp.lanagent.aa.feature.sessions.ProjectSessionStatusFilter
import io.github.hotmanxp.lanagent.aa.feature.sessions.NewSessionModelCatalog
import io.github.hotmanxp.lanagent.aa.feature.sessions.NewSessionPermissionCatalog
import io.github.hotmanxp.lanagent.aa.feature.sessions.NewSessionRuntimeCapabilities
import io.github.hotmanxp.lanagent.aa.feature.sessions.SessionBatchUpdate
import io.github.hotmanxp.lanagent.aa.feature.sessions.SessionsState
import io.github.hotmanxp.lanagent.aa.feature.update.AppUpdateViewModel
import io.github.hotmanxp.lanagent.aa.model.AgentDevice
import io.github.hotmanxp.lanagent.aa.model.AgentProject
import io.github.hotmanxp.lanagent.aa.model.AgentSession
import io.github.hotmanxp.lanagent.aa.model.MobileLoginQrPayload
import io.github.hotmanxp.lanagent.aa.navigation.AppDestination
import io.github.hotmanxp.lanagent.aa.ui.designsystem.LocalAAColors
import io.github.hotmanxp.lanagent.aa.ui.screens.auth.LoginMethodsScreen
import io.github.hotmanxp.lanagent.aa.ui.screens.auth.QrLoginScreen
import io.github.hotmanxp.lanagent.aa.ui.screens.auth.QrWaitingScreen
import io.github.hotmanxp.lanagent.aa.ui.screens.auth.WebLoginHostScreen
import io.github.hotmanxp.lanagent.aa.ui.screens.devices.AddDeviceScreen
import io.github.hotmanxp.lanagent.aa.ui.screens.devices.DeviceDetailScreen
import io.github.hotmanxp.lanagent.aa.ui.screens.devices.DevicesScreen
import io.github.hotmanxp.lanagent.aa.ui.screens.devices.rememberDeviceAgentPreviews
import io.github.hotmanxp.lanagent.aa.ui.screens.files.FilesScreen
import io.github.hotmanxp.lanagent.aa.ui.screens.home.ArchivedSessionsScreen
import io.github.hotmanxp.lanagent.aa.ui.screens.home.HomeScreen
import io.github.hotmanxp.lanagent.aa.ui.screens.home.HomeTab
import io.github.hotmanxp.lanagent.aa.ui.screens.home.NewSessionScreen
import io.github.hotmanxp.lanagent.aa.ui.screens.sessiondetail.SessionComposerDraftStore
import io.github.hotmanxp.lanagent.aa.ui.screens.sessiondetail.SessionDetailScreen

@Composable
internal fun AgentsAnywhereNavHost(
    currentDestination: AppDestination,
    sessionsState: SessionsState,
    isRefreshingSessions: Boolean,
    selectedSessionId: String?,
    preparedSessionDraft: NewSessionDraft?,
    selectedDeviceId: String?,
    deviceDetailReturnDestination: AppDestination,
    deviceSetupReturnDestination: AppDestination,
    selectedHomeTab: HomeTab,
    userId: String,
    role: String,
    serverUrl: String,
    appearanceMode: String,
    languageMode: String,
    sidebarViewMode: String,
    projectSessionsById: Map<String, List<AgentSession>>,
    loadingProjectRequests: Set<ProjectSessionLoadKey>,
    projectSessionErrors: Map<ProjectSessionLoadKey, String>,
    initialNewSessionProjectId: String?,
    sessionDetailController: SessionDetailController,
    sessionRealtimeController: SessionRealtimeController,
    filesController: FilesController,
    pendingMobileLoginQr: MobileLoginQrPayload?,
    webLoginViewModel: WebLoginViewModel,
    appUpdateViewModel: AppUpdateViewModel,
    navigate: (AppDestination) -> Unit,
    onRefreshSessions: () -> Unit,
    onLoadMoreSessions: (Boolean) -> Unit,
    onOpenSession: (AgentSession) -> Unit,
    onOpenDevice: (AgentDevice) -> Unit,
    onHomeTabSelected: (HomeTab) -> Unit,
    onAppearanceModeChange: (String) -> Unit,
    onLanguageModeChange: (String) -> Unit,
    onSidebarViewModeChange: (String) -> Unit,
    onLoadAccount: suspend () -> Result<io.github.hotmanxp.lanagent.aa.api.AuthMeResponse>,
    onLoadAccountAuthConfig: suspend () -> Result<io.github.hotmanxp.lanagent.aa.api.AuthConfigResponse>,
    onUpdateDisplayName: suspend (String) -> Result<io.github.hotmanxp.lanagent.aa.api.AuthMeResponse>,
    onSendEmailCode: suspend (String) -> Result<io.github.hotmanxp.lanagent.aa.api.EmailCodeResponse>,
    onBindEmail: suspend (String, String?) -> Result<io.github.hotmanxp.lanagent.aa.api.AuthMeResponse>,
    onUpdateAvatar: suspend (String) -> Result<io.github.hotmanxp.lanagent.aa.api.AuthMeResponse>,
    onClearAvatar: suspend () -> Result<io.github.hotmanxp.lanagent.aa.api.AuthMeResponse>,
    onChangePassword: suspend (String) -> Result<Unit>,
    onSignOut: () -> Unit,
    onRenameDevice: suspend (String, String) -> Result<AgentDevice>,
    onDeleteDevice: suspend (String) -> Result<Unit>,
    onPrepareDeviceSetup: suspend (String) -> Result<DeviceSetupCredential>,
    onCreateDeviceSetup: suspend (String) -> Result<DeviceSetupCredential>,
    onClaimDevicePairCode: suspend (DeviceSetupCredential, String) -> Result<AgentDevice>,
    devicePairingStates: Map<String, DevicePairingStatus>,
    onWaitForPairingDevice: (String) -> Unit,
    onClearDevicePairing: (String) -> Unit,
    onDevicePairingComplete: () -> Unit,
    onListDeviceRuntimes: suspend (String) -> Result<DeviceRuntimeList>,
    onSetDeviceRuntimeActive: suspend (String, String, Boolean) -> Result<DeviceRuntime>,
    onDeleteDeviceRuntimeConfig: suspend (String, String) -> Result<DeviceRuntime>,
    onBulkSetSessionsArchived: suspend (List<String>, Boolean) -> Result<SessionBatchUpdate>,
    onArchiveAllDeviceSessions: suspend (String, Boolean, String) -> Result<List<AgentSession>>,
    onRenameSession: suspend (String, String) -> Result<AgentSession>,
    onSetSessionPinned: suspend (String, Boolean) -> Result<AgentSession>,
    onSetSessionArchived: suspend (String, Boolean) -> Result<AgentSession>,
    onLoadProjectSessions: (String, ProjectSessionStatusFilter) -> Unit,
    onLoadProjects: suspend () -> Result<List<AgentProject>>,
    onLoadArchivedPage: suspend (String?, String?) -> Result<io.github.hotmanxp.lanagent.aa.feature.sessions.SessionPageAppend>,
    onRestoreProject: suspend (String) -> Result<List<AgentSession>>,
    onUpdateProject: suspend (String, String?, Boolean?) -> Result<AgentProject>,
    onArchiveProjectSessions: suspend (String) -> Result<List<AgentSession>>,
    onCreateProject: suspend (String, String, String) -> Result<AgentProject>,
    onNewSessionInProject: (AgentProject) -> Unit,
    onCreateSession: suspend (NewSessionCreateDraft) -> NewSessionCreateOutcome,
    onPrepareSession: (NewSessionDraft) -> Unit,
    onPreparedSessionCreated: (AgentSession) -> Unit,
    onListDirectory: suspend (String, String, String) -> Result<NewSessionDirectory>,
    onListNewSessionRuntimes: suspend (String) -> Result<DeviceRuntimeList>,
    onLoadNewSessionRuntimeCapabilities: suspend (String, String) -> Result<NewSessionRuntimeCapabilities>,
    onLoadNewSessionModelCatalog: suspend (String, String) -> Result<NewSessionModelCatalog>,
    onLoadNewSessionPermissionCatalog: suspend (String, String) -> Result<NewSessionPermissionCatalog>,
    onSessionChanged: (AgentSession) -> Unit,
    onMobileLoginQrRequested: (MobileLoginQrPayload) -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalAAColors.current
    var profileOpen by rememberSaveable(serverUrl, userId) { mutableStateOf(false) }
    var deviceAgentPreviewRefreshKey by remember { mutableLongStateOf(0L) }
    val deviceAgentPreviews = rememberDeviceAgentPreviews(
        devices = sessionsState.devices,
        isRefreshing = isRefreshingSessions,
        refreshKey = deviceAgentPreviewRefreshKey,
        onListDeviceRuntimes = onListDeviceRuntimes,
    )
    val sessionComposerDraftStore = remember(context, userId) {
        SessionComposerDraftStore(context.applicationContext, userId)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.canvas,
    ) {
        AnimatedContent(
            targetState = currentDestination,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val enterOffset: (Int) -> Int = { width -> if (forward) width / 5 else -width / 5 }
                val exitOffset: (Int) -> Int = { width -> if (forward) -width / 5 else width / 5 }

                slideInHorizontally(
                    animationSpec = tween(durationMillis = 260),
                    initialOffsetX = enterOffset,
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 180),
                ) togetherWith slideOutHorizontally(
                    animationSpec = tween(durationMillis = 260),
                    targetOffsetX = exitOffset,
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 160),
                )
            },
            label = "App destination transition",
        ) { destination ->
            when (destination) {
                AppDestination.LoginMethods -> LoginMethodsScreen(navigate)
                AppDestination.ServerSetup -> WebLoginHostScreen(webLoginViewModel, navigate)
                AppDestination.QrLogin -> QrLoginScreen(
                    navigate = navigate,
                    onMobileLoginQrRequested = onMobileLoginQrRequested,
                )
                AppDestination.QrWaiting -> QrWaitingScreen(
                    navigate = navigate,
                    mobileLoginQr = pendingMobileLoginQr,
                )
                AppDestination.Sessions -> HomeScreen(
                    navigate = navigate,
                    state = sessionsState,
                    selectedTab = selectedHomeTab,
                    onLoadProjects = onLoadProjects,
                    isRefreshing = isRefreshingSessions,
                    userId = userId,
                    role = role,
                    serverUrl = serverUrl,
                    appearanceMode = appearanceMode,
                    languageMode = languageMode,
                    sidebarViewMode = sidebarViewMode,
                    appUpdateViewModel = appUpdateViewModel,
                    projectSessionsById = projectSessionsById,
                    loadingProjectRequests = loadingProjectRequests,
                    projectSessionErrors = projectSessionErrors,
                    onRefresh = onRefreshSessions,
                    onLoadMore = { tab -> onLoadMoreSessions(tab == HomeTab.Archived) },
                    onTabSelected = onHomeTabSelected,
                    onAppearanceModeChange = onAppearanceModeChange,
                    onLanguageModeChange = onLanguageModeChange,
                    onSidebarViewModeChange = onSidebarViewModeChange,
                    profileOpen = profileOpen,
                    onProfileOpenChange = { profileOpen = it },
                    onOpenArchivedSessions = { navigate(AppDestination.ArchivedSessions) },
                    onLoadAccount = onLoadAccount,
                    onLoadAccountAuthConfig = onLoadAccountAuthConfig,
                    onUpdateDisplayName = onUpdateDisplayName,
                    onSendEmailCode = onSendEmailCode,
                    onBindEmail = onBindEmail,
                    onUpdateAvatar = onUpdateAvatar,
                    onClearAvatar = onClearAvatar,
                    onChangePassword = onChangePassword,
                    onSignOut = onSignOut,
                    onRenameSession = onRenameSession,
                    onSetSessionPinned = onSetSessionPinned,
                    onSetSessionArchived = onSetSessionArchived,
                    onLoadProjectSessions = onLoadProjectSessions,
                    onUpdateProject = onUpdateProject,
                    onArchiveProjectSessions = onArchiveProjectSessions,
                    onNewSessionInProject = onNewSessionInProject,
                    onOpenSession = onOpenSession,
                    onOpenDevice = onOpenDevice,
                    deviceAgentPreviews = deviceAgentPreviews,
                    onPairDevice = { navigate(AppDestination.DeviceSetup) },
                )
                AppDestination.NewSession, AppDestination.NewProject -> androidx.compose.runtime.key(serverUrl, userId, destination) { NewSessionScreen(
                    navigate = navigate,
                    sessionsState = sessionsState,
                    projectSessionsById = projectSessionsById,
                    serverUrl = serverUrl,
                    userId = userId,
                    onListDirectory = onListDirectory,
                    onListRuntimes = onListNewSessionRuntimes,
                    onLoadRuntimeCapabilities = onLoadNewSessionRuntimeCapabilities,
                    onLoadModelCatalog = onLoadNewSessionModelCatalog,
                    onLoadPermissionCatalog = onLoadNewSessionPermissionCatalog,
                    onPrepareSession = onPrepareSession,
                    onRefreshDevices = onRefreshSessions,
                    devicesRefreshing = isRefreshingSessions,
                    initialProjectId = initialNewSessionProjectId.takeIf { destination == AppDestination.NewSession },
                    projectOnly = destination == AppDestination.NewProject,
                    sidebarViewMode = sidebarViewMode,
                    onLoadProjects = onLoadProjects,
                    onCreateProject = onCreateProject,
                ) }
                AppDestination.SessionDetail -> SessionDetailScreen(
                    navigate = navigate,
                    sessionId = selectedSessionId,
                    initialSession = preparedSessionDraft?.previewSession() ?: sessionsState.sessions
                        .asSequence()
                        .plus(sessionsState.archivedSessions.asSequence())
                        .firstOrNull { it.id == selectedSessionId },
                    preparedSession = preparedSessionDraft,
                    onCreatePreparedSession = onCreateSession,
                    onPreparedSessionCreated = onPreparedSessionCreated,
                    onLoadPreparedModelCatalog = onLoadNewSessionModelCatalog,
                    onLoadPreparedPermissionCatalog = onLoadNewSessionPermissionCatalog,
                    devices = sessionsState.devices,
                    controller = sessionDetailController,
                    realtimeController = sessionRealtimeController,
                    filesController = filesController,
                    composerDraftStore = sessionComposerDraftStore,
                    onSessionChanged = onSessionChanged,
                )
                AppDestination.DeviceDetail -> DeviceDetailScreen(
                    navigate = navigate,
                    state = sessionsState,
                    selectedDeviceId = selectedDeviceId,
                    backDestination = deviceDetailReturnDestination,
                    onOpenSession = onOpenSession,
                    onRenameDevice = onRenameDevice,
                    onDeleteDevice = onDeleteDevice,
                    onPrepareDeviceSetup = onPrepareDeviceSetup,
                    onClaimDevicePairCode = onClaimDevicePairCode,
                    onListDeviceRuntimes = onListDeviceRuntimes,
                    onSetDeviceRuntimeActive = { connectorId, runtime, active ->
                        onSetDeviceRuntimeActive(connectorId, runtime, active).onSuccess {
                            deviceAgentPreviewRefreshKey += 1L
                        }
                    },
                    onDeleteDeviceRuntimeConfig = { connectorId, runtime ->
                        onDeleteDeviceRuntimeConfig(connectorId, runtime).onSuccess {
                            deviceAgentPreviewRefreshKey += 1L
                        }
                    },
                    onBulkSetSessionsArchived = onBulkSetSessionsArchived,
                    onArchiveAllDeviceSessions = onArchiveAllDeviceSessions,
                )
                AppDestination.Devices -> DevicesScreen(
                    state = sessionsState,
                    isRefreshing = isRefreshingSessions,
                    onRefresh = onRefreshSessions,
                    onOpenDevice = onOpenDevice,
                    onBack = { navigate(AppDestination.Sessions) },
                    agentPreviews = deviceAgentPreviews,
                    onAddDevice = { navigate(AppDestination.DeviceSetup) },
                )
                // 0.24.2:远程终端改放「服务」栏独立入口(与局域网 SSH 并排),
                // 共用本项目的 xterm.js 渲染器,这里不再有 Terminal 分支。
                AppDestination.Files -> FilesScreen(
                    navigate = navigate,
                    state = sessionsState,
                    controller = filesController,
                    onPairDevice = { navigate(AppDestination.DeviceSetup) },
                )
                AppDestination.DeviceSetup -> androidx.compose.runtime.key(serverUrl, userId) {
                    AddDeviceScreen(
                        devices = sessionsState.devices,
                        pairingStates = devicePairingStates,
                        onBack = { navigate(deviceSetupReturnDestination) },
                        onComplete = {
                            onDevicePairingComplete()
                            navigate(deviceSetupReturnDestination)
                        },
                        onCreateCredential = onCreateDeviceSetup,
                        onRenameDevice = onRenameDevice,
                        onClaimPairCode = onClaimDevicePairCode,
                        onWaitForDevice = onWaitForPairingDevice,
                        onClearPairing = onClearDevicePairing,
                    )
                }
                AppDestination.ArchivedSessions -> androidx.compose.runtime.key(serverUrl, userId) {
                    ArchivedSessionsScreen(
                        projects = sessionsState.projects,
                        onLoadPage = onLoadArchivedPage,
                        onRestoreSession = { onSetSessionArchived(it, false) },
                        onRestoreProject = onRestoreProject,
                        onBack = { navigate(AppDestination.Sessions) },
                    )
                }
            }
        }
    }
}

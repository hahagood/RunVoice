package com.runvoice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.runvoice.history.archive.RunArchiveCoordinator
import com.runvoice.history.archive.RunSummaryImageArchiver
import com.runvoice.history.data.RunHistoryDatabase
import com.runvoice.history.data.RunHistoryFileCleaner
import com.runvoice.history.data.RunHistoryRepository
import com.runvoice.history.ui.HistoryRoute
import com.runvoice.history.ui.RunArchiveViewModel
import com.runvoice.history.ui.RunHistoryDetailRoute
import com.runvoice.model.RunData
import com.runvoice.recovery.RunCheckpointStore
import com.runvoice.service.RunningService
import com.runvoice.tracker.TraceSaveResult
import com.runvoice.tracker.HeartRateState
import com.runvoice.ui.*
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private var serviceState = mutableStateOf<RunningService?>(null)
    private var permissionsGranted = mutableStateOf(false)
    private var bindingRequested = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceState.value = (binder as RunningService.RunBinder).service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceState.value = null
        }
    }

    // All permissions to request (location is mandatory, others are optional)
    private val allPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Only location is mandatory to proceed
        permissionsGranted.value = hasLocationPermission()
        if (permissionsGranted.value) bindRunningService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionsGranted.value = hasLocationPermission()

        // Handle test announce intent from ADB
        if (intent?.action == RunningService.ACTION_TEST_ANNOUNCE) {
            val i = Intent(this, RunningService::class.java).apply {
                action = RunningService.ACTION_TEST_ANNOUNCE
            }
            startService(i)
        }

        setContent {
            val service by serviceState
            val hasPermissions by permissionsGranted
            val navController = rememberNavController()
            val historyRepository = remember {
                RunHistoryRepository(
                    dao = RunHistoryDatabase.getInstance(applicationContext).runHistoryDao(),
                    fileCleaner = RunHistoryFileCleaner(applicationContext)
                )
            }
            val archiveCoordinator = remember {
                RunArchiveCoordinator(
                    recordWriter = historyRepository,
                    imageArchiver = RunSummaryImageArchiver(applicationContext)
                )
            }
            val archiveViewModel: RunArchiveViewModel = viewModel(
                factory = RunArchiveViewModel.Factory(archiveCoordinator)
            )
            val archiveUiState by archiveViewModel.uiState.collectAsStateWithLifecycle()

            if (!hasPermissions) {
                PermissionScreen(onRequestPermissions = {
                    permissionLauncher.launch(allPermissions)
                })
                return@setContent
            }

            // Always collect flows unconditionally — Compose requires stable call structure
            val fallbackRunData = remember { MutableStateFlow(RunData()) }
            val runData by (service?.runData ?: fallbackRunData).collectAsStateWithLifecycle()

            val fallbackBool = remember { MutableStateFlow(false) }
            val fallbackDevices = remember { MutableStateFlow(emptyList<com.runvoice.tracker.HeartRateMonitor.BleDevice>()) }
            val fallbackHrState = remember { MutableStateFlow<HeartRateState>(HeartRateState.Idle) }
            val fallbackRecovery = remember { MutableStateFlow(false) }
            val fallbackRecoveryError = remember { MutableStateFlow<String?>(null) }

            val hrScanning by (service?.heartRateScanning ?: fallbackBool).collectAsStateWithLifecycle()
            val hrDevices by (service?.heartRateDevices ?: fallbackDevices).collectAsStateWithLifecycle()
            val hrState by (service?.heartRateState ?: fallbackHrState).collectAsStateWithLifecycle()
            val savedAddr = service?.savedHeartRateDeviceAddress()
            val hrConnected = runData.hrDeviceConnected
            val recoveryInProgress by (
                service?.recoveryInProgress ?: fallbackRecovery
            ).collectAsStateWithLifecycle()
            val recoveryError by (
                service?.recoveryError ?: fallbackRecoveryError
            ).collectAsStateWithLifecycle()
            var pendingRecovery by remember {
                mutableStateOf(RunCheckpointStore(applicationContext).load())
            }

            LaunchedEffect(runData.isRunning, recoveryInProgress) {
                // Recovery marks the run active before the CSV has finished reopening. Keep the
                // recovery card until that work succeeds so a read/start failure remains visible
                // and can be retried instead of dropping the only way back to the checkpoint.
                if (runData.isRunning && !recoveryInProgress) pendingRecovery = null
            }

            val checkpoint = pendingRecovery
            if (service == null && checkpoint != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1A1A2E)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF00E676))
                }
                return@setContent
            }
            if (service != null && !runData.isRunning && checkpoint != null) {
                RunRecoveryScreen(
                    checkpoint = checkpoint,
                    recoveryInProgress = recoveryInProgress,
                    errorMessage = recoveryError,
                    onContinue = {
                        startForegroundServiceAction(RunningService.ACTION_CONTINUE_PREVIOUS)
                    },
                    onStartNew = {
                        pendingRecovery = null
                        startForegroundServiceAction(RunningService.ACTION_START_NEW)
                    }
                )
                return@setContent
            }

            val startDest = remember {
                val prefs = getSharedPreferences("runvoice", MODE_PRIVATE)
                if (prefs.getBoolean("about_seen", false)) "run" else "about"
            }

            NavHost(navController = navController, startDestination = startDest) {
                composable("run") {
                    RunScreen(
                        runData = runData,
                        onStart = { startRunService() },
                        onPause = { sendServiceAction(RunningService.ACTION_PAUSE) },
                        onResume = { sendServiceAction(RunningService.ACTION_RESUME) },
                        onInterruptAndExit = {
                            sendServiceAction(RunningService.ACTION_INTERRUPT_FOR_RECOVERY)
                            finishAndRemoveTask()
                        },
                        hrConnected = hrConnected,
                        onArchiveAndStop = { snapshot ->
                            archiveViewModel.archive(snapshot) {
                                service?.stopRun(saveSession = true)
                                    ?: TraceSaveResult.Failed("跑步服务未连接，数据尚未保存")
                            }
                        },
                        archiveUiState = archiveUiState,
                        onClearArchiveState = archiveViewModel::clear,
                        onDiscardAndStop = {
                            service?.stopRun(saveSession = false)
                                ?: TraceSaveResult.Failed("跑步服务未连接，无法结束本次记录")
                        },
                        onOpenHrSettings = { navController.navigate("hr_settings") },
                        onOpenHistory = { navController.navigate("history") },
                        onOpenAbout = { navController.navigate("about") },
                        onToggleMetronome = { service?.toggleMetronome() },
                        onBpmChange = { bpm -> service?.setMetronomeBpm(bpm) },
                        currentTracePathForSnapshot = { service?.currentTracePathForSnapshot() },
                        currentRunStartedAtEpochMillis = {
                            service?.currentSessionStartedAtEpochMillis()
                        }
                    )
                }
                composable("history") {
                    HistoryRoute(
                        repository = historyRepository,
                        onBack = { navController.popBackStack() },
                        onOpenRecord = { recordId ->
                            navController.navigate("history_detail/$recordId")
                        }
                    )
                }
                composable("history_detail/{recordId}") { backStackEntry ->
                    backStackEntry.arguments?.getString("recordId")?.let { recordId ->
                        RunHistoryDetailRoute(
                            recordId = recordId,
                            repository = historyRepository,
                            onBack = { navController.popBackStack() },
                            onDeleted = { navController.popBackStack() }
                        )
                    }
                }
                composable("about") {
                    AboutScreen(onBack = {
                        getSharedPreferences("runvoice", MODE_PRIVATE)
                            .edit().putBoolean("about_seen", true).apply()
                        if (!navController.popBackStack()) {
                            navController.navigate("run") {
                                popUpTo("about") { inclusive = true }
                            }
                        }
                    })
                }
                composable("hr_settings") {
                    HrDeviceScreen(
                        state = HrDeviceUiState(
                            available = service != null && hrState !is HeartRateState.Unavailable &&
                                hrState !is HeartRateState.PermissionDenied,
                            scanning = hrScanning,
                            devices = hrDevices.map { HrDeviceItem(it.name, it.address, it.rssi) },
                            connectedAddress = if (hrConnected) savedAddr else null,
                            savedAddress = savedAddr,
                            statusMessage = when (val state = hrState) {
                                HeartRateState.PermissionDenied -> "蓝牙权限未授予或已被撤销。请在系统设置中允许蓝牙权限。"
                                HeartRateState.Unavailable -> "蓝牙当前不可用。请确认设备支持蓝牙并已打开蓝牙开关。"
                                is HeartRateState.Error -> state.message
                                else -> null
                            }
                        ),
                        onStartScan = { service?.startHeartRateScan() },
                        onStopScan = { service?.stopHeartRateScan() },
                        onSelectDevice = { address ->
                            service?.stopHeartRateScan()
                            service?.selectHeartRateDevice(address)
                            navController.popBackStack()
                        },
                        onDisconnect = { service?.disconnectHeartRateDevice() },
                        onBack = {
                            service?.stopHeartRateScan()
                            navController.popBackStack()
                        }
                    )
                }
            }
        }
    }

    private fun startRunService() {
        startForegroundServiceAction(RunningService.ACTION_START)
    }

    private fun startForegroundServiceAction(action: String) {
        val intent = Intent(this, RunningService::class.java).apply {
            this.action = action
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, RunningService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }

    override fun onStart() {
        super.onStart()
        if (hasLocationPermission()) bindRunningService()
    }

    override fun onStop() {
        if (bindingRequested) {
            runCatching { unbindService(connection) }
            bindingRequested = false
            serviceState.value = null
        }
        super.onStop()
    }

    private fun bindRunningService() {
        if (bindingRequested) return
        bindingRequested = bindService(
            Intent(this, RunningService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }
}

@Composable
private fun PermissionScreen(onRequestPermissions: () -> Unit) {
    val bgColor = Color(0xFF1A1A2E)
    val accentGreen = Color(0xFF00E676)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "RunVoice",
            color = accentGreen,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "需要以下权限才能正常使用：",
            color = Color.White,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        val items = listOf("精确定位 — GPS 追踪", "蓝牙 — 连接心率监控设备", "通知 — 前台服务运行")
        items.forEach {
            Text("• $it", color = Color(0xFFB0BEC5), fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accentGreen),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("授予权限", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = bgColor)
        }
    }
}

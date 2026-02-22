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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.runvoice.model.RunData
import com.runvoice.service.RunningService
import com.runvoice.ui.*
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private var serviceState = mutableStateOf<RunningService?>(null)
    private var permissionsGranted = mutableStateOf(false)

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
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
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

            if (!hasPermissions) {
                PermissionScreen(onRequestPermissions = {
                    permissionLauncher.launch(allPermissions)
                })
                return@setContent
            }

            // Bind to service when we have permissions
            LaunchedEffect(Unit) {
                val intent = Intent(this@MainActivity, RunningService::class.java)
                bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }

            // Always collect flows unconditionally — Compose requires stable call structure
            val fallbackRunData = remember { MutableStateFlow(RunData()) }
            val runData by (service?.runData ?: fallbackRunData).collectAsStateWithLifecycle()

            val hrMonitor = service?.heartRateMonitor
            val fallbackBool = remember { MutableStateFlow(false) }
            val fallbackInt = remember { MutableStateFlow(0) }
            val fallbackDevices = remember { MutableStateFlow(emptyList<com.runvoice.tracker.HeartRateMonitor.BleDevice>()) }

            val hrScanning by (hrMonitor?.scanning ?: fallbackBool).collectAsStateWithLifecycle()
            val hrDevices by (hrMonitor?.discoveredDevices ?: fallbackDevices).collectAsStateWithLifecycle()
            val hrConnected by (hrMonitor?.connected ?: fallbackBool).collectAsStateWithLifecycle()
            val savedAddr = hrMonitor?.getSavedDeviceAddress()

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
                        hrConnected = hrConnected,
                        onStop = {
                            service?.stopRun()
                            // Re-bind since service stopped itself
                            val intent = Intent(this@MainActivity, RunningService::class.java)
                            bindService(intent, connection, Context.BIND_AUTO_CREATE)
                        },
                        onOpenHrSettings = { navController.navigate("hr_settings") },
                        onOpenAbout = { navController.navigate("about") },
                        onToggleMetronome = { service?.toggleMetronome() },
                        onBpmChange = { bpm -> service?.setMetronomeBpm(bpm) }
                    )
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
                            scanning = hrScanning,
                            devices = hrDevices.map { HrDeviceItem(it.name, it.address, it.rssi) },
                            connectedAddress = if (hrConnected) savedAddr else null,
                            savedAddress = savedAddr
                        ),
                        onStartScan = { hrMonitor?.startScan() },
                        onStopScan = { hrMonitor?.stopScan() },
                        onSelectDevice = { address ->
                            hrMonitor?.stopScan()
                            hrMonitor?.saveDevice(address)
                            hrMonitor?.connectToDevice(address)
                            navController.popBackStack()
                        },
                        onDisconnect = { hrMonitor?.clearSavedDevice() },
                        onBack = {
                            hrMonitor?.stopScan()
                            navController.popBackStack()
                        }
                    )
                }
            }
        }
    }

    private fun startRunService() {
        val intent = Intent(this, RunningService::class.java).apply {
            action = RunningService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        // Ensure we are bound
        bindService(Intent(this, RunningService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, RunningService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }

    override fun onDestroy() {
        if (serviceState.value != null) {
            unbindService(connection)
        }
        super.onDestroy()
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
        val items = listOf("精确定位 — GPS 追踪", "蓝牙 — 连接心率带", "通知 — 前台服务运行")
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

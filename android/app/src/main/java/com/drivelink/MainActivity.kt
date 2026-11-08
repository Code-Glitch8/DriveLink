package com.drivelink

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.drivelink.notifications.JourneyService
import com.drivelink.ui.AppViewModel
import com.drivelink.ui.AuthScreen
import com.drivelink.ui.ConfirmScreen
import com.drivelink.ui.DashboardScreen
import com.drivelink.ui.DriveLinkTheme
import com.drivelink.ui.HomeScreen
import com.drivelink.ui.Screen
import com.drivelink.ui.SettingsScreen
import com.drivelink.ui.VehiclesScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var afterPermissions: (() -> Unit)? = null

    private fun missing(perms: List<String>) =
        perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private val locationPerms = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun journeyPerms(): List<String> {
        val p = locationPerms.toMutableList()
        if (Build.VERSION.SDK_INT >= 31) {
            p += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            p += listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.POST_NOTIFICATIONS)
        }
        return p
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val cb = afterPermissions
        afterPermissions = null
        if (cb != null) cb()
    }

    private fun withPermissions(perms: List<String>, onResult: () -> Unit) {
        val need = missing(perms)
        if (need.isEmpty()) onResult() else { afterPermissions = onResult; permLauncher.launch(need.toTypedArray()) }
    }

    private fun applyImmersiveMode(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val st by vm.ui.collectAsState()
            LaunchedEffect(st.screen) {
                applyImmersiveMode(st.screen == Screen.DASHBOARD)
            }
            DriveLinkTheme(st.darkTheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (st.screen) {
                        Screen.AUTH -> AuthScreen(st, vm)
                        Screen.HOME -> HomeScreen(st, vm) { withPermissions(locationPerms) { vm.refreshLocation() } }
                        Screen.CONFIRM -> ConfirmScreen(st, vm) {
                            withPermissions(journeyPerms()) {
                                if (missing(locationPerms).isNotEmpty()) vm.toast("Location permission is required to start a journey")
                                else vm.prepareJourney {
                                    JourneyService.start(this@MainActivity)
                                    vm.go(Screen.DASHBOARD)
                                }
                            }
                        }
                        Screen.DASHBOARD -> DashboardScreen(st, vm) {
                            JourneyService.stop(this@MainActivity)
                            vm.go(Screen.HOME)
                        }
                        Screen.VEHICLES -> VehiclesScreen(st, vm)
                        Screen.SETTINGS -> SettingsScreen(st, vm)
                    }
                }
            }
        }
    }
}

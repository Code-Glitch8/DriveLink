package com.drivelink.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drivelink.authentication.Session
import com.drivelink.authentication.SessionStore
import com.drivelink.authentication.SupabaseApi
import com.drivelink.driving.JourneyConfig
import com.drivelink.driving.JourneyManager
import com.drivelink.location.Geo
import com.drivelink.navigation.NavigationHelper
import com.drivelink.navigation.Place
import com.drivelink.navigation.RoutePlan
import com.drivelink.vehicles.Vehicle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

enum class Screen { AUTH, HOME, CONFIRM, DASHBOARD, VEHICLES, SETTINGS }

data class UiState(
    val screen: Screen = Screen.AUTH,
    val session: Session? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val vehicles: List<Vehicle> = emptyList(),
    val activeVehicleId: String? = null,
    val profileName: String = "",
    val profilePhone: String = "",
    val darkTheme: Boolean = true,
    val voiceAlerts: Boolean = true,
    val myLat: Double? = null,
    val myLon: Double? = null,
    val destination: Place? = null,
    val searchResults: List<Place> = emptyList(),
    val route: RoutePlan? = null,
    val stepIndex: Int = 0,
) {
    val activeVehicle: Vehicle? get() = vehicles.firstOrNull { it.id == activeVehicleId } ?: vehicles.firstOrNull()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SessionStore(app)
    private val prefs = app.getSharedPreferences("drivelink_prefs", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(UiState(
        darkTheme = prefs.getBoolean("dark", true), voiceAlerts = prefs.getBoolean("voice", true)))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        val saved = store.load()
        if (saved != null) viewModelScope.launch {
            try {
                val s = if (saved.expiresAtSec - 60 < System.currentTimeMillis() / 1000) SupabaseApi.refresh(saved) else saved
                store.save(s)
                _ui.update { it.copy(session = s, screen = if (JourneyManager.isRunning) Screen.DASHBOARD else Screen.HOME) }
                loadAll()
            } catch (e: Exception) {
                // offline or expired: stay on the sign-in screen
                _ui.update { it.copy(message = "Please sign in again") }
            }
        }
    }

    fun toast(m: String?) = _ui.update { it.copy(message = m) }

    private fun launchBusy(block: suspend () -> Unit) = viewModelScope.launch {
        _ui.update { it.copy(busy = true, message = null) }
        try { block() } catch (e: Exception) { _ui.update { it.copy(message = e.message ?: "Something went wrong") } }
        _ui.update { it.copy(busy = false) }
    }

    private suspend fun token(): String {
        var s = _ui.value.session ?: throw IllegalStateException("Not signed in")
        if (s.expiresAtSec - 60 < System.currentTimeMillis() / 1000) {
            s = SupabaseApi.refresh(s); store.save(s); _ui.update { it.copy(session = s) }
        }
        return s.accessToken
    }

    // ---- auth ----
    fun signIn(email: String, pw: String) = launchBusy {
        val s = SupabaseApi.signIn(email.trim(), pw)
        store.save(s)
        _ui.update { it.copy(session = s, screen = Screen.HOME) }
        loadAll()
    }

    fun signUp(email: String, pw: String, name: String) = launchBusy {
        val immediate = SupabaseApi.signUp(email.trim(), pw, name.trim())
        toast(if (immediate) "Account created — sign in" else "Account created. Check your email to verify, then sign in.")
    }

    fun recover(email: String) = launchBusy { SupabaseApi.recover(email.trim()); toast("Password reset email sent") }

    fun signOut() {
        JourneyManager.stop()
        store.clear()
        _ui.update { UiState(darkTheme = it.darkTheme, voiceAlerts = it.voiceAlerts) }
    }

    fun changePassword(pw: String) = launchBusy {
        if (pw.length < 8) throw IllegalArgumentException("Use at least 8 characters")
        SupabaseApi.changePassword(token(), pw); toast("Password changed")
    }

    fun deleteAccount() = launchBusy {
        SupabaseApi.deleteAccount(token()); signOut(); toast("Account deleted")
    }

    // ---- data ----
    fun loadAll() = viewModelScope.launch {
        try {
            val t = token(); val uid = _ui.value.session!!.userId
            val vehicles = SupabaseApi.listVehicles(t)
            val settings = SupabaseApi.getSettings(t, uid)
            val profile = SupabaseApi.getProfile(t, uid)
            _ui.update {
                it.copy(vehicles = vehicles,
                    activeVehicleId = settings.optString("active_vehicle_id").takeIf { v -> v.isNotBlank() && v != "null" },
                    profileName = profile.optString("full_name"),
                    profilePhone = profile.optString("phone_number").let { p -> if (p == "null") "" else p })
            }
        } catch (e: Exception) { toast(e.message) }
    }

    fun saveProfile(name: String, phone: String) = launchBusy {
        SupabaseApi.updateProfile(token(), _ui.value.session!!.userId, name.trim(), phone.trim())
        _ui.update { it.copy(profileName = name, profilePhone = phone) }; toast("Profile saved")
    }

    fun addVehicle(fields: Map<String, String>, onDone: () -> Unit) = launchBusy {
        val v = SupabaseApi.addVehicle(token(), _ui.value.session!!.userId, fields)
        if (_ui.value.activeVehicleId == null) setActiveInternal(v.id)
        loadAll(); toast("Registered as ${v.driveLinkId}"); onDone()
    }

    fun deleteVehicle(id: String) = launchBusy { SupabaseApi.deleteVehicle(token(), id); loadAll() }

    private suspend fun setActiveInternal(id: String) {
        SupabaseApi.updateSettings(token(), _ui.value.session!!.userId, JSONObject().put("active_vehicle_id", id))
        _ui.update { it.copy(activeVehicleId = id) }
    }

    fun setActive(id: String) = launchBusy { setActiveInternal(id) }

    fun setTheme(dark: Boolean) {
        prefs.edit().putBoolean("dark", dark).apply(); _ui.update { it.copy(darkTheme = dark) }
        viewModelScope.launch { try { SupabaseApi.updateSettings(token(), _ui.value.session!!.userId, JSONObject().put("theme", if (dark) "dark" else "light")) } catch (_: Exception) {} }
    }

    fun setVoice(on: Boolean) {
        prefs.edit().putBoolean("voice", on).apply(); _ui.update { it.copy(voiceAlerts = on) }
        viewModelScope.launch { try { SupabaseApi.updateSettings(token(), _ui.value.session!!.userId, JSONObject().put("voice_alerts", on)) } catch (_: Exception) {} }
    }

    fun go(screen: Screen) = _ui.update { it.copy(screen = screen, message = null) }

    // ---- navigation ----
    @SuppressLint("MissingPermission")
    fun refreshLocation() = viewModelScope.launch {
        val ctx = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return@launch
        try {
            val loc = LocationServices.getFusedLocationProviderClient(ctx)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
            if (loc != null) _ui.update { it.copy(myLat = loc.latitude, myLon = loc.longitude) }
        } catch (_: Exception) {}
    }

    fun search(q: String) = launchBusy {
        val r = NavigationHelper.search(getApplication(), q)
        _ui.update { it.copy(searchResults = r) }
        if (r.isEmpty()) toast("No places found")
    }

    fun pickDestination(p: Place) {
        _ui.update { it.copy(destination = p, searchResults = emptyList(), route = null, stepIndex = 0) }
        previewRoute()
    }

    fun previewRoute() {
        val st = _ui.value
        val dest = st.destination ?: return
        val lat = st.myLat ?: return toast("Current position unknown — allow location and try again")
        val lon = st.myLon ?: return
        launchBusy {
            val r = NavigationHelper.route(lat, lon, dest)
            _ui.update { it.copy(route = r, stepIndex = 0) }
        }
    }

    fun clearDestination() = _ui.update { it.copy(destination = null, route = null, searchResults = emptyList(), stepIndex = 0) }

    /** Advance the in-app step list when the driver reaches the end of the current step. */
    fun onPosition(lat: Double, lon: Double) {
        val st = _ui.value
        val route = st.route ?: return
        val step = route.steps.getOrNull(st.stepIndex) ?: return
        if (Geo.distanceM(lat, lon, step.endLat, step.endLon) < 30) _ui.update { it.copy(stepIndex = it.stepIndex + 1) }
    }

    // ---- journey ----
    /** Refreshes the token, configures the journey for the active vehicle, then calls onReady. */
    fun prepareJourney(onReady: () -> Unit) = viewModelScope.launch {
        val t = try { token() } catch (_: Exception) { null } // offline is fine: local communication still works
        val st = _ui.value
        val v = st.activeVehicle
        JourneyManager.configure(JourneyConfig(v?.id, v?.driveLinkId ?: "UNREGISTERED", v?.type ?: "car", t, st.voiceAlerts))
        onReady()
    }
}

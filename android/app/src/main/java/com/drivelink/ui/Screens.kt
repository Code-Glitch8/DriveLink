package com.drivelink.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.drivelink.driving.AlertItem
import com.drivelink.driving.JourneyManager
import com.drivelink.driving.PeerView
import com.drivelink.location.Geo
import com.drivelink.map.CameraCommand
import com.drivelink.map.DriveLinkMap
import com.drivelink.map.MAP_DEBUG_ENABLED
import com.drivelink.map.MapAttribution
import com.drivelink.map.MapControlButton
import com.drivelink.map.MapDebugPanel
import com.drivelink.map.MapDestination
import com.drivelink.map.MapHandle
import com.drivelink.map.MapMe
import com.drivelink.map.MapRoute
import com.drivelink.map.MapStatus
import com.drivelink.map.MapStatusBanner
import com.drivelink.map.MapVehicleMapper
import com.drivelink.navigation.NavigationHelper
import com.drivelink.orientation.CameraUpdateGate
import com.drivelink.orientation.HeadingTracker
import com.drivelink.orientation.MapOrientationMode
import com.drivelink.orientation.MapOrientationSession
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.drivelink.safety.RiskLevel
import com.drivelink.vehicles.VEHICLE_TYPES
import kotlin.math.cos
import kotlin.math.sin

private val Pad = 18.dp
private val Pill = RoundedCornerShape(50)

@Composable
private fun Msg(st: UiState) {
    if (st.busy) CircularProgressIndicator(Modifier.padding(8.dp).size(26.dp), strokeWidth = 3.dp)
    st.message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp)) }
}

@Composable
private fun TopBar(title: String, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onBack) { Text("‹ Back", fontSize = 16.sp) }
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = if (onBack == null) 4.dp else 0.dp))
    }
}

// ---------------------------------------------------------------- Auth
@Composable
fun AuthScreen(st: UiState, vm: AppViewModel) {
    var signup by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(Pad).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BrandMark(64.dp)
        Spacer(Modifier.height(14.dp))
        Text("DriveLink", style = MaterialTheme.typography.headlineMedium)
        Text("Connect. Communicate. Navigate.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Spacer(Modifier.height(26.dp))
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (signup) "Create your account" else "Welcome back", style = MaterialTheme.typography.titleLarge)
                if (signup) OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pw, { pw = it }, label = { Text("Password (8+ characters)") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Msg(st)
                GradientButton(if (signup) "Create account" else "Sign in", { if (signup) vm.signUp(email, pw, name) else vm.signIn(email, pw) },
                    Modifier.fillMaxWidth(), enabled = !st.busy && email.isNotBlank() && pw.length >= 8)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ signup = !signup }) { Text(if (signup) "I have an account" else "Create account") }
                    if (!signup) TextButton({ if (email.isNotBlank()) vm.recover(email) else vm.toast("Enter your email first") }) { Text("Forgot password?") }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Experimental driver-awareness tool. Not a certified safety system. Never operate your phone while driving.",
            fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- Home
@Composable
fun HomeScreen(st: UiState, vm: AppViewModel, requestLocation: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val handle = remember { MapHandle() }
    var camera by remember { mutableStateOf<CameraCommand?>(null) }
    var mapStatus by remember { mutableStateOf<MapStatus>(MapStatus.Loading) }
    var showDebug by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    LaunchedEffect(Unit) { requestLocation(); vm.refreshLocation() }
    LaunchedEffect(st.myLat, st.myLon) {
        val la = st.myLat; val lo = st.myLon
        if (la != null && lo != null) camera = CameraCommand.Center(la, lo, 15.0)
    }
    LaunchedEffect(st.route) {
        val r = st.route
        if (r != null && r.points.size >= 2) {
            // Leave room for the search card at the top and the vehicle card at the bottom.
            camera = with(density) { CameraCommand.Fit(r.points, 60.dp.roundToPx(), 230.dp.roundToPx(), 60.dp.roundToPx(), 330.dp.roundToPx()) }
        }
    }
    val me = remember(st.myLat, st.myLon) { st.myLat?.let { la -> st.myLon?.let { lo -> MapMe(la, lo) } } }
    val dest = remember(st.destination) { st.destination?.let { MapDestination(it.name, it.lat, it.lon) } }
    val route = remember(st.route) { st.route?.let { MapRoute(it.points, it.alternatives) } }

    Box(Modifier.fillMaxSize()) {
        // No real vehicles exist before a journey starts (they come from Nearby Connections), so none are drawn here.
        DriveLinkMap(handle = handle, dark = st.darkTheme, me = me, destination = dest, route = route,
            vehicles = emptyList(), selectedVehicleId = null, camera = camera, onStatus = { mapStatus = it })
        Column(Modifier.statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(query, { query = it }, placeholder = { Text("Where to?") }, singleLine = true, shape = Pill, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Button({ if (query.isNotBlank()) vm.search(query) }, enabled = !st.busy, shape = Pill) { Text("Go") }
                    }
                    st.searchResults.forEach { p ->
                        Text(p.name, Modifier.fillMaxWidth().clickable { vm.pickDestination(p) }.padding(horizontal = 8.dp, vertical = 11.dp), maxLines = 2, fontSize = 14.sp)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    }
                    Msg(st)
                }
            }
            st.destination?.let { d ->
                AppCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontSize = 14.sp, maxLines = 2, fontWeight = FontWeight.SemiBold)
                            st.route?.let { Text("${it.distanceText} · ${it.durationText}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
                        }
                        TextButton({ vm.clearDestination() }) { Text("Clear") }
                    }
                }
            }
            if (mapStatus !is MapStatus.Ready) MapStatusBanner(mapStatus, { handle.retryStyle() }, Modifier.fillMaxWidth())
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                MapAttribution()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (MAP_DEBUG_ENABLED) MapControlButton("🛠", { showDebug = !showDebug })
                    MapControlButton("+", { handle.zoomBy(1.0) })
                    MapControlButton("−", { handle.zoomBy(-1.0) })
                    MapControlButton("◎", {
                        val la = st.myLat; val lo = st.myLon
                        if (la != null && lo != null) handle.center(la, lo) else { requestLocation(); vm.refreshLocation() }
                    })
                }
            }
            if (MAP_DEBUG_ENABLED && showDebug) MapDebugPanel(handle, Modifier.fillMaxWidth())
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BrandMark(40.dp)
                        Column(Modifier.weight(1f)) {
                            Text(st.activeVehicle?.displayName ?: "No vehicle registered", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(st.activeVehicle?.driveLinkId ?: "Add a vehicle to get a DriveLink ID", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Chip("Nearby: idle", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GradientButton("Start journey", { vm.go(Screen.CONFIRM) }, Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton({ vm.go(Screen.VEHICLES) }, Modifier.weight(1f)) { Text("Vehicles") }
                        FilledTonalButton({ vm.go(Screen.SETTINGS) }, Modifier.weight(1f)) { Text("Settings") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Confirm
@Composable
fun ConfirmScreen(st: UiState, vm: AppViewModel, onStart: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(Pad).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TopBar("Start journey") { vm.go(Screen.HOME) }
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val v = st.activeVehicle
                SectionLabel("Vehicle")
                if (v == null) Text("No vehicle registered. Register one first (Vehicles). You can still start; you will appear as an unregistered vehicle.", color = MaterialTheme.colorScheme.error)
                else { Text(v.displayName, style = MaterialTheme.typography.titleMedium); Chip(v.driveLinkId) }
                SectionLabel("Destination")
                Text(st.destination?.name ?: "None — communication only")
                st.route?.let { Chip("${it.distanceText} · ${it.durationText}") }
            }
        }
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Enabled during the journey")
                Text("• Direct phone-to-phone messages with nearby DriveLink vehicles (Bluetooth/Wi-Fi, no internet needed)\n" +
                    "• Approximate position, speed and heading shared under a temporary session ID\n" +
                    "• Merge requests, hazard and emergency alerts, experimental collision-risk awareness\n" +
                    "• Voice alerts: ${if (st.voiceAlerts) "on" else "off"}\n" +
                    "• Simplified driving dashboard; typing is disabled while driving", fontSize = 14.sp)
            }
        }
        Text("Experimental awareness only — not a certified safety system. Set everything up before you drive.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Msg(st)
        GradientButton("Start journey monitoring", onStart, Modifier.fillMaxWidth())
        st.destination?.let { d -> OutlinedButton({ if (!NavigationHelper.openTurnByTurn(ctx, d)) vm.toast("No maps app found to hand off turn-by-turn navigation") }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) { Text("Open turn-by-turn in a maps app") } }
    }
}

// ---------------------------------------------------------------- Dashboard
@Composable
fun DashboardScreen(st: UiState, vm: AppViewModel, onEnd: () -> Unit) {
    val j by JourneyManager.state.collectAsState()
    var confirm1 by remember { mutableStateOf(false) }
    var confirm2 by remember { mutableStateOf(false) }
    var hazards by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<PeerView?>(null) }
    var mapMode by rememberSaveable { mutableStateOf(true) }
    var mapStatus by remember { mutableStateOf<MapStatus>(MapStatus.Loading) }
    var showDebug by remember { mutableStateOf(false) }
    val handle = remember { MapHandle() }
    // Only real, authenticated peers from JourneyManager are drawn; stale ones are filtered out by the mapper.
    val mapVehicles = remember(j.peers, j.laneRequests) { MapVehicleMapper.fromPeers(j.peers, j.laneRequests, System.currentTimeMillis()) }
    // Heading pipeline (compass + GPS course). Sensors run only while this screen is at least STARTED.
    val heading by HeadingTracker.heading.collectAsState()
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var held = false
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START && !held) { HeadingTracker.acquire(ctx); held = true }
            if (e == Lifecycle.Event.ON_STOP && held) { HeadingTracker.release(); held = false }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); if (held) HeadingTracker.release() }
    }
    var orientMode by remember { mutableStateOf(MapOrientationSession.mode) }
    var following by remember { mutableStateOf(true) }
    val gate = remember { CameraUpdateGate() }
    var followCam by remember { mutableStateOf<CameraCommand?>(null) }
    LaunchedEffect(j.self, heading.deg, orientMode, following) {
        val s = j.self ?: return@LaunchedEffect
        if (!following) return@LaunchedEffect
        val bearing = if (orientMode == MapOrientationMode.HEADING_UP) heading.deg else null
        if (gate.shouldUpdate(System.currentTimeMillis(), s.lat, s.lon, bearing)) followCam = CameraCommand.Track(s.lat, s.lon, bearing)
    }
    // The marker always uses the true heading (never the map rotation), so it stays geographically correct.
    val dashMe = remember(j.self?.lat, j.self?.lon, heading.deg) { j.self?.let { MapMe(it.lat, it.lon, heading.deg) } }
    val dashDest = remember(st.destination) { st.destination?.let { MapDestination(it.name, it.lat, it.lon) } }
    val dashRoute = remember(st.route) { st.route?.let { MapRoute(it.points, it.alternatives) } }
    LaunchedEffect(j.self) { j.self?.let { vm.onPosition(it.lat, it.lon) } }
    BackHandler { confirm1 = true }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(if (j.connected > 0) "Linked · ${j.connected} vehicle(s)" else "Searching…", if (j.connected > 0) Color(0xFF22C55E) else Color(0xFF8B98B0))
                Text(j.comm, fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SpeedDisplay(j.speed)
        }
        val step = st.route?.steps?.getOrNull(st.stepIndex)
        if (step != null) Banner("${step.instruction} (${step.distanceText})", MaterialTheme.colorScheme.primary)
        j.dataQualityWarning?.let { Banner(it, levelColor(1)) }
        j.alerts.take(3).forEach { a -> AlertCard(a) }

        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
            if (mapMode) {
                DriveLinkMap(handle = handle, dark = st.darkTheme, me = dashMe, destination = dashDest, route = dashRoute,
                    vehicles = mapVehicles, selectedVehicleId = detail?.sid, camera = followCam,
                    onVehicleTap = { id -> detail = id?.let { sid -> j.peers.firstOrNull { it.sid == sid } } },
                    onStatus = { mapStatus = it }, onUserGesture = { following = false })
                OrientationControls(orientMode, following,
                    onToggleMode = { orientMode = if (orientMode == MapOrientationMode.HEADING_UP) MapOrientationMode.NORTH_UP else MapOrientationMode.HEADING_UP
                        MapOrientationSession.mode = orientMode; following = true; gate.reset() },
                    onRecenter = { following = true; gate.reset() },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(6.dp))
                MapAttribution(Modifier.align(Alignment.BottomStart).padding(6.dp))
                if (mapStatus !is MapStatus.Ready) MapStatusBanner(mapStatus, { handle.retryStyle() }, Modifier.align(Alignment.TopCenter).padding(6.dp))
                if (MAP_DEBUG_ENABLED) {
                    MapControlButton("🛠", { showDebug = !showDebug }, Modifier.align(Alignment.BottomEnd).padding(6.dp))
                    if (showDebug) MapDebugPanel(handle, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 58.dp, start = 6.dp, end = 6.dp))
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Radar(j.self?.bearing ?: 0.0, j.self?.lat, j.self?.lon, j.peers) }
            }
            Text(if (mapMode) "Radar view" else "Map view",
                Modifier.align(Alignment.TopEnd).padding(6.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), Pill)
                    .clickable { mapMode = !mapMode }.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        }

        if (j.peers.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            j.peers.take(3).forEach { p ->
                OutlinedButton({ detail = p }, Modifier.weight(1f), shape = Pill) { Text("${p.distanceM.toInt()} m ${p.direction}", fontSize = 12.sp, maxLines = 1) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ JourneyManager.sendLaneChange("LEFT") }, Modifier.weight(1f).height(60.dp), shape = RoundedCornerShape(16.dp)) { Text("◀ Merge", fontSize = 15.sp) }
            FilledTonalButton({ JourneyManager.sendLaneChange("RIGHT") }, Modifier.weight(1f).height(60.dp), shape = RoundedCornerShape(16.dp)) { Text("Merge ▶", fontSize = 15.sp) }
            FilledTonalButton({ hazards = true }, Modifier.weight(1f).height(60.dp), shape = RoundedCornerShape(16.dp)) { Text("⚠ Hazard", fontSize = 15.sp) }
        }
        if (j.canSendEmergency) Button({ JourneyManager.sendEmergency() }, Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3001B))) { Text("Send emergency alert", fontSize = 16.sp) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ confirm1 = true }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("End journey") }
            OutlinedButton(onEnd, Modifier.height(52.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6B6B))) { Text("Emergency exit") }
        }
    }

    if (confirm1) AlertDialog(onDismissRequest = { confirm1 = false }, shape = RoundedCornerShape(24.dp),
        title = { Text("End journey?") }, text = { Text("Are you sure you want to end your journey?") },
        confirmButton = { TextButton({ confirm1 = false; confirm2 = true }) { Text("Yes, end") } },
        dismissButton = { TextButton({ confirm1 = false }) { Text("Keep driving") } })
    if (confirm2) AlertDialog(onDismissRequest = { confirm2 = false }, shape = RoundedCornerShape(24.dp),
        title = { Text("Confirm") }, text = { Text("Confirm that you want to disable journey monitoring and communication.") },
        confirmButton = { TextButton({ confirm2 = false; onEnd() }) { Text("Confirm") } },
        dismissButton = { TextButton({ confirm2 = false }) { Text("Cancel") } })
    if (hazards) AlertDialog(onDismissRequest = { hazards = false }, shape = RoundedCornerShape(24.dp), title = { Text("Report hazard") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ACCIDENT" to "Accident", "OBSTRUCTION" to "Obstruction", "BROKEN_DOWN" to "Broken-down vehicle",
                    "ROAD_DAMAGE" to "Road damage", "ROAD_CLOSURE" to "Road closure", "OTHER" to "Other hazard").forEach { (k, label) ->
                    FilledTonalButton({ JourneyManager.sendHazard(k); hazards = false }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) { Text(label) }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton({ hazards = false }) { Text("Cancel") } })
    detail?.let { p ->
        AlertDialog(onDismissRequest = { detail = null }, shape = RoundedCornerShape(24.dp), title = { Text("Nearby vehicle") },
            text = {
                Text("Temporary ID: ${p.sid}\nCategory: ${p.vehicleType.replace('_', ' ')}\nApprox. distance: ${p.distanceM.toInt()} m\n" +
                    "Direction: ${p.direction}\nLink: connected, last update ${p.ageMs / 1000} s ago\nRisk: ${p.risk.level} — ${p.risk.reason}" +
                    (p.verifiedEmergency?.let { "\nVerified emergency vehicle: ${it.replace('_', ' ')}" } ?: ""))
            }, confirmButton = { TextButton({ detail = null }) { Text("Close") } })
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().background(color, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun AlertCard(a: AlertItem) {
    Text(a.text, Modifier.fillMaxWidth().background(levelColor(a.level), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
}

/** Simplified heading-up radar: you are at the lower centre, forward is up. Needs no map tiles or network. */
@Composable
private fun Radar(heading: Double, lat: Double?, lon: Double?, peers: List<PeerView>) {
    val rangeM = 300.0
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2, size.height * 0.64f)
        val r = minOf(size.width / 2, size.height * 0.62f) - 6f
        val ring = Color(0xFF8B98B0).copy(alpha = 0.28f)
        listOf(1f, 0.66f, 0.33f).forEach { drawCircle(ring, r * it, c, style = Stroke(2f)) }
        drawLine(ring, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f)
        drawLine(ring, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f)
        val cone = Path().apply { moveTo(c.x, c.y); lineTo(c.x - r * 0.38f, c.y - r); lineTo(c.x + r * 0.38f, c.y - r); close() }
        drawPath(cone, Color(0xFF4C8DFF).copy(alpha = 0.10f))
        val me = Path().apply { moveTo(c.x, c.y - 20f); lineTo(c.x - 13f, c.y + 13f); lineTo(c.x, c.y + 6f); lineTo(c.x + 13f, c.y + 13f); close() }
        drawPath(me, Color(0xFF4C8DFF))
        if (lat == null || lon == null) return@Canvas
        val h = Math.toRadians(heading)
        val scale = r / rangeM
        peers.forEach { p ->
            val v = Geo.toLocal(lat, lon, p.lat, p.lon)
            val x = (v.x * cos(h) - v.y * sin(h)) * scale
            val y = (v.x * sin(h) + v.y * cos(h)) * scale
            val mag = Math.hypot(x, y)
            val f = if (mag > r) r / mag else 1.0
            val col = when {
                p.verifiedEmergency != null -> Color(0xFFB3001B)
                p.risk.level == RiskLevel.HIGH_RISK -> Color(0xFFE5392B)
                p.risk.level == RiskLevel.CAUTION -> Color(0xFFD99100)
                else -> Color(0xFF9FB0CC)
            }
            val pt = Offset(c.x + (x * f).toFloat(), c.y - (y * f).toFloat())
            drawCircle(col.copy(alpha = 0.25f), 26f, pt)
            drawCircle(col, 13f, pt)
        }
    }
}

// ---------------------------------------------------------------- Vehicles
@Composable
fun VehiclesScreen(st: UiState, vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    val f = remember { mutableStateOf(mapOf("country_code" to "IND", "vehicle_type" to "car")) }
    fun set(k: String, v: String) { f.value = f.value + (k to v) }
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(Pad).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TopBar("My vehicles") { vm.go(Screen.HOME) }
        Msg(st)
        st.vehicles.forEach { v ->
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Chip(v.driveLinkId)
                        if (v.id == st.activeVehicle?.id) Chip("ACTIVE", Color(0xFF22C55E))
                        Chip(v.verification, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(v.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("${v.type.replace('_', ' ')} · Reg. ${v.registrationNumber} (${v.region})", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton({ vm.setActive(v.id) }) { Text("Set active") }
                        OutlinedButton({ vm.deleteVehicle(v.id) }) { Text("Delete") }
                    }
                }
            }
        }
        if (st.vehicles.isEmpty()) Text("No vehicles registered yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!adding) GradientButton("Add vehicle", { adding = true }, Modifier.fillMaxWidth())
        else AppCard(Modifier.fillMaxWidth()) {
            val m = f.value
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Register vehicle", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(m["registration_number"] ?: "", { set("registration_number", it) }, label = { Text("Registration number") }, singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(m["registration_region"] ?: "", { set("registration_region", it) }, label = { Text("Registration region") }, singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(m["country_code"] ?: "", { set("country_code", it.uppercase().take(3)) }, label = { Text("Country code (3 letters)") }, singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth())
                FilledTonalButton({ val cur = VEHICLE_TYPES.indexOf(m["vehicle_type"]); set("vehicle_type", VEHICLE_TYPES[(cur + 1) % VEHICLE_TYPES.size]) }, Modifier.fillMaxWidth().height(50.dp), shape = shape) {
                    Text("Category: ${(m["vehicle_type"] ?: "car").replace('_', ' ')}  (tap to change)")
                }
                OutlinedTextField(m["manufacturer"] ?: "", { set("manufacturer", it) }, label = { Text("Manufacturer") }, singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(m["model"] ?: "", { set("model", it) }, label = { Text("Model") }, singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(m["manufacturing_year"] ?: "", { set("manufacturing_year", it.filter(Char::isDigit).take(4)) }, label = { Text("Year") }, singleLine = true, shape = shape,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(m["colour"] ?: "", { set("colour", it) }, label = { Text("Colour") }, singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(m["nickname"] ?: "", { set("nickname", it) }, label = { Text("Vehicle name") }, singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth())
                Text("The DriveLink Vehicle ID is generated by the server. Emergency categories need administrator verification (request it on the website).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GradientButton("Register", { vm.addVehicle(f.value) { adding = false } }, Modifier.fillMaxWidth(),
                    enabled = !st.busy && (m["registration_number"]?.length ?: 0) >= 3 && (m["registration_region"]?.length ?: 0) >= 2 && (m["country_code"]?.length ?: 0) == 3)
                TextButton({ adding = false }, Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}

// ---------------------------------------------------------------- Settings
@Composable
fun SettingsScreen(st: UiState, vm: AppViewModel) {
    var name by remember(st.profileName) { mutableStateOf(st.profileName) }
    var phone by remember(st.profilePhone) { mutableStateOf(st.profilePhone) }
    var pw by remember { mutableStateOf("") }
    var del by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(Pad).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TopBar("Settings") { vm.go(Screen.HOME) }
        Msg(st)
        SectionLabel("Profile")
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(st.session?.email ?: "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone (optional)") }, singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth())
                GradientButton("Save profile", { vm.saveProfile(name, phone) }, Modifier.fillMaxWidth())
            }
        }
        SectionLabel("Preferences")
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Dark theme"); Switch(st.darkTheme, { vm.setTheme(it) }) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Voice alerts"); Switch(st.voiceAlerts, { vm.setVoice(it) }) }
            }
        }
        Text("Privacy: live positions are exchanged only directly with nearby DriveLink phones under a temporary session ID. Your registration number never leaves your account.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton({ vm.go(Screen.VEHICLES) }, Modifier.fillMaxWidth().height(50.dp), shape = shape) { Text("Manage vehicles") }
        SectionLabel("Security")
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pw, { pw = it }, label = { Text("New password") }, singleLine = true, shape = shape, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                FilledTonalButton({ vm.changePassword(pw); pw = "" }, Modifier.fillMaxWidth().height(50.dp), shape = shape) { Text("Change password") }
            }
        }
        SectionLabel("Danger zone")
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(del, { del = it }, label = { Text("Type DELETE to delete your account") }, singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth())
                Button({ vm.deleteAccount() }, Modifier.fillMaxWidth().height(50.dp), enabled = del == "DELETE", shape = shape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3001B))) { Text("Delete account") }
            }
        }
        OutlinedButton({ vm.signOut() }, Modifier.fillMaxWidth().height(50.dp), shape = shape) { Text("Log out") }
    }
}

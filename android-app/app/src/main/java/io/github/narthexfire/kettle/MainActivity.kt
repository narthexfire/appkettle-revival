package io.github.narthexfire.kettle

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.narthexfire.kettle.ui.KettleViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import io.github.narthexfire.kettle.ui.screens.AboutScreen
import io.github.narthexfire.kettle.ui.screens.DiagnosticsScreen
import io.github.narthexfire.kettle.ui.screens.HelpScreen
import io.github.narthexfire.kettle.ui.screens.KettleEditor
import io.github.narthexfire.kettle.ui.screens.LicensesScreen
import io.github.narthexfire.kettle.ui.screens.MainActions
import io.github.narthexfire.kettle.ui.screens.MainScreen
import io.github.narthexfire.kettle.ui.screens.SettingsScreen
import io.github.narthexfire.kettle.ui.theme.K
import io.github.narthexfire.kettle.ui.theme.KType
import io.github.narthexfire.kettle.ui.theme.KettleTheme

class MainActivity : ComponentActivity() {
    private val vm: KettleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { KettleTheme { App(vm) } }
    }
}

private val VERSION = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

@Composable
private fun App(vm: KettleViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val localState by vm.localWifi.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    // Route: "main", "settings", "edit:<id>", "about", "help", "licenses", "diag:<id>"
    var route by rememberSaveable { mutableStateOf("main") }
    // The back stack is saveable (rotation / process death).
    var stackSaved by rememberSaveable { mutableStateOf(listOf<String>()) }
    fun go(r: String) { stackSaved = stackSaved + route; route = r }
    fun back() { route = stackSaved.lastOrNull() ?: "main"; stackSaved = stackSaved.dropLast(1) }
    BackHandler(enabled = route != "main") { back() }

    // Connect only while the app is visible (the kettle links are cheap to re-open).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> vm.setForeground(true)
                Lifecycle.Event.ON_STOP -> vm.setForeground(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshWifi() }

    fun view(id: String) = state.kettles.firstOrNull { it.config.id == id } ?: state.selected
    var crashText by remember { mutableStateOf(CrashLog.read(ctx)) }
    LaunchedEffect(route) { if (route.startsWith("diag:")) crashText = CrashLog.read(ctx) }

    Box(Modifier.fillMaxSize()) {
        when {
            route == "main" -> MainScreen(
                state, reduceMotion,
                MainActions(
                    select = { vm.select(it) }, setTarget = vm::setTarget, preset = vm::choosePreset,
                    keepWarm = vm::setKeepWarm, boil = { vm.boil() }, stop = vm::stop, reconnect = { vm.reconnect() },
                    openSettings = { go("settings") }, openHelp = { go("help") }, openAbout = { go("about") },
                    openDiagnostics = { go("diag:${state.selectedId}") },
                    changeAddress = { go("edit:${state.selectedId}:address") },
                    editPreset = { i, p -> vm.updatePreset(state.selectedId, i, p) },
                    saveAsPreset = { slot, name -> vm.saveCurrentAsPreset(slot, name) },
                    retryKettleWifi = { vm.retryKettleWifi() },
                    boilAnyway = { vm.boil(allowLowWater = true) },
                    searchAgain = { vm.startSearch(state.selectedId) },
                    pullRefresh = { vm.pullRefresh() },
                ),
                local = localState, concurrency = vm.localWifi.concurrencySupported,
            )
            route == "settings" -> SettingsScreen(state.kettles, state.now, ::back, onAdd = { vm.addKettle { id -> go("edit:$id") } }) { go("edit:$it") }
            route.startsWith("edit:") -> {
                val parts = route.removePrefix("edit:").split(":")
                val id = parts[0]
                KettleEditor(
                    view(id), ::back, onSave = { vm.saveKettle(it) },
                    onDetails = { go("diag:$id") },
                    onSavePresets = { vm.savePresets(id, it) },
                    onSaveEdits = { vm.saveEdits(id, it) },
                    focusAddress = parts.getOrNull(1) == "address",
                    onTestConnection = { ip, port -> vm.testConnection(id, ip, port) },
                    onSearch = { vm.startSearch(id) }, searching = state.search[id]?.running == true,
                    searchResult = state.search[id]?.result,
                    onSaveAddress = { ip, port -> vm.saveAddress(id, ip, port) },
                    onRemove = if (state.kettles.size > 1) ({ vm.removeKettle(id); back() }) else null,
                )
            }
            route.startsWith("diag:") -> {
                val id = route.removePrefix("diag:")
                DiagnosticsScreen(
                    view(id), state.phone, state.now, VERSION, "", ::back,
                    onReconnect = { vm.reconnect(id) },
                    onCopy = { text ->
                        ctx.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Kettle details", text))
                    },
                    onGrantLocation = {
                        locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                    lastCrash = crashText,
                    local = localState, concurrency = vm.localWifi.concurrencySupported,
                    onClearCrash = { CrashLog.clear(ctx); crashText = null },
                    onChangeAddress = { go("edit:$id:address") },
                    search = state.search[id], onSearch = { vm.startSearch(id) },
                )
            }
            route == "about" -> AboutScreen(state.kettles.map { it.config }, VERSION, "", ::back) { go("licenses") }
            route == "licenses" -> LicensesScreen(::back)
            route == "help" -> HelpScreen(::back) { go("diag:${state.selectedId}") }
            else -> route = "main"
        }
    }
}

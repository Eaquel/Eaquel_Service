@file:Suppress("DEPRECATION", "OPT_IN_USAGE")
package com.eaquel.service

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import androidx.core.app.NotificationCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

const val PKG           = "com.eaquel.service"
const val AUTHORITY     = "$PKG.provider"
const val PERM_USE      = "$PKG.permission.USE"
const val PERM_MANAGE   = "$PKG.permission.MANAGE"
const val PERM_AC       = "$PKG.permission.ANTICHEAT"
val APP_VERSION         = BuildConfig.VERSION_CODE
const val MIN_SDK       = Build.VERSION_CODES.R   
const val MAX_SDK       = 36

const val NOTIF_CH_SVC  = "eaquel_service"
const val NOTIF_CH_AC   = "eaquel_anticheat"
const val NOTIF_CH_WARN = "eaquel_warning"
const val NOTIF_CH_PAIR = "eaquel_pairing"

const val NOTIF_ID_SVC        = 1001
const val NOTIF_ID_AC         = 1002
const val NOTIF_ID_PAIR       = 1003
const val NOTIF_ID_PAIR_REPLY = 1004
const val NOTIF_ID_PAIR_SCAN  = 1005
const val NOTIF_ID_ADB_PERM   = 1011
const val NOTIF_ID_ADB_STEP   = 1012
const val NOTIF_ID_ADB_CONNECTED = 1013
const val NOTIF_ID_ADB_DISCONNECTED = 1014
const val NOTIF_CH_ADB        = "eaquel_adb_setup"

const val TAG_MAIN   = "EaquelMain"
const val TAG_ADB    = "EaquelAdb"
const val TAG_SERVER = "EaquelServer"
const val TAG_AC     = "EaquelAC"
const val TAG_SEC    = "EaquelSec"

const val ACTION_PAIR_CODE        = "$PKG.action.PAIR_CODE"
const val EXTRA_PAIR_CODE         = "pair_code"
const val ACTION_ADB_OPEN_DEV     = "$PKG.action.ADB_OPEN_DEV"
const val ACTION_ADB_OPEN_PAIR    = "$PKG.action.ADB_OPEN_PAIR"
const val ACTION_ADB_DISMISS      = "$PKG.action.ADB_DISMISS"
const val ACTION_ADB_RECONNECT    = "$PKG.action.ADB_RECONNECT"

fun sdkOk()      = Build.VERSION.SDK_INT in MIN_SDK..MAX_SDK
fun is64()       = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
fun has32()      = Build.SUPPORTED_32_BIT_ABIS.isNotEmpty()
fun primaryAbi() = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

data class LogEntry(
    val msg: String,
    val level: Level,
    val tag: String = "",
    val ts: Long    = System.currentTimeMillis()
) {
    enum class Level { VERBOSE, DEBUG, INFO, SUCCESS, WARN, ERROR, CRITICAL }
}

operator fun LogEntry.Level.compareTo(other: LogEntry.Level): Int = this.ordinal - other.ordinal
operator fun LogEntry.Level.compareTo(other: Int): Int            = this.ordinal - other

object GameUtils {
    fun bytesToHuman(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L         -> "%.1f KB".format(bytes / 1_024.0)
        else                    -> "$bytes B"
    }

    fun msToHuman(ms: Long): String {
        val s = ms / 1000; val m = s / 60; val h = m / 60; val d = h / 24
        return when { d > 0 -> "${d}g ${h%24}s"; h > 0 -> "${h}s ${m%60}d"; m > 0 -> "${m}d ${s%60}sn"; else -> "${s}sn" }
    }

    fun sha256(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun generateToken(length: Int = 32): String {
        val bytes = ByteArray(length)
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
    }

    fun isValidPackageName(name: String): Boolean =
        name.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))

    fun isValidPort(port: Int): Boolean = port in 1..65535

    fun extractPackagesFromPmList(output: String): List<String> =
        output.lines().mapNotNull { l ->
            if (l.startsWith("package:")) l.removePrefix("package:").trim() else null
        }
}

fun <T> Res<T>.onSuccess(b: (T) -> Unit): Res<T>              { if (this is Res.Ok) b(data); return this }
fun <T> Res<T>.onFailure(b: (Int, String) -> Unit): Res<T>    { if (this is Res.Err) b(code, msg); return this }
fun <T, R> Res<T>.map(t: (T) -> R): Res<R>                   = when (this) { is Res.Ok -> Res.Ok(t(data)); is Res.Err -> Res.Err(code, msg, cause) }
fun <T, R> Res<T>.flatMap(t: (T) -> Res<R>): Res<R>          = when (this) { is Res.Ok -> t(data); is Res.Err -> Res.Err(code, msg, cause) }
fun <T> Res<T>.getOrDefault(default: T): T                    = getOrNull() ?: default

fun formatUptime(ms: Long): String {
    val s = ms / 1000; val m = s / 60; val h = m / 60
    return when { h > 0 -> "${h}s ${m%60}d"; m > 0 -> "${m}d ${s%60}sn"; else -> "${s}sn" }
}
fun sdkToName(sdk: Int) = when (sdk) { 30->"11"; 31,32->"12"; 33->"13"; 34->"14"; 35->"15"; 36->"16"; else->"?" }
fun formatBytes(bytes: Long) = GameUtils.bytesToHuman(bytes)
fun formatDuration(ms: Long) = GameUtils.msToHuman(ms)

fun levelColor(l: LogEntry.Level): Color = when (l) {
    LogEntry.Level.VERBOSE  -> Color(0xFF6C7086)
    LogEntry.Level.DEBUG    -> Color(0xFFA6ADC8)
    LogEntry.Level.INFO     -> Color(0xFFCDD6F4)
    LogEntry.Level.SUCCESS  -> Color(0xFFA6E3A1)
    LogEntry.Level.WARN     -> Color(0xFFF9E2AF)
    LogEntry.Level.ERROR    -> Color(0xFFF38BA8)
    LogEntry.Level.CRITICAL -> Color(0xFFEBA0AC)
}

sealed class Res<out T> {
    data class Ok<T>(val data: T)                  : Res<T>()
    data class Err(val code: Int, val msg: String,
                   val cause: Throwable? = null)   : Res<Nothing>()
    val ok     get() = this is Ok
    val failed get() = this is Err
    fun getOrNull()  = (this as? Ok)?.data
    fun getOrThrow() = when (this) {
        is Ok  -> data
        is Err -> throw GameException(code, msg, cause)
    }
    companion object {
        fun <T> ok(d: T): Res<T>                 = Ok(d)
        fun err(c: Int, m: String): Res<Nothing> = Err(c, m)
    }
}

class GameException(val code: Int, msg: String, cause: Throwable? = null) : Exception(msg, cause)

object Err {
    const val OK=0; const val PERM_DENIED=-1; const val INVALID=-2
    const val DEAD=-3; const val BINDER_NULL=-4; const val BAD_SDK=-5
    const val OP_FAIL=-6; const val TIMEOUT=-7; const val NOT_FOUND=-8
    const val EXISTS=-9; const val NO_ADB=-10; const val NATIVE=-11
    const val ANTICHEAT=-20; const val TAMPERED=-21; const val DEBUGGER=-22
    const val HOOK=-23; const val EMULATOR=-24
}

sealed class Cmd {
    data class Shell(val cmd: String, val dir: String? = null,
                     val env: Map<String,String> = emptyMap(), val timeoutMs: Long = 30_000L) : Cmd()
    data class PkgInstall(val apk: String, val replace: Boolean = true,
                          val grant: Boolean = false, val allowDowngrade: Boolean = false) : Cmd()
    data class PkgUninstall(val pkg: String, val keepData: Boolean = false, val user: Int = 0) : Cmd()
    data class PkgEnable(val pkg: String, val enabled: Boolean, val user: Int = 0)      : Cmd()
    data class PkgHide(val pkg: String, val hidden: Boolean, val user: Int = 0)         : Cmd()
    data class PkgClear(val pkg: String, val user: Int = 0)                             : Cmd()
    data class PkgStop(val pkg: String)                                                  : Cmd()
    data class PkgList(val user: Int = 0, val system: Boolean = false)                  : Cmd()
    data class PkgInfo(val pkg: String, val user: Int = 0)                              : Cmd()
    data class PermGrant(val pkg: String, val perm: String, val user: Int = 0)          : Cmd()
    data class PermRevoke(val pkg: String, val perm: String, val user: Int = 0)         : Cmd()
    data class PermList(val pkg: String, val user: Int = 0)                             : Cmd()
    data class SettingWrite(val ns: NS, val key: String, val value: String)             : Cmd()
    data class SettingRead(val ns: NS, val key: String)                                 : Cmd()
    data class SettingDelete(val ns: NS, val key: String)                               : Cmd()
    data class SettingList(val ns: NS)                                                  : Cmd()
    data class ComponentToggle(val pkg: String, val comp: String, val enabled: Boolean) : Cmd()
    data class WifiSet(val on: Boolean)                                                 : Cmd()
    data class MobileDataSet(val on: Boolean)                                           : Cmd()
    data class AirplaneSet(val on: Boolean)                                             : Cmd()
    data class AdbWifiSet(val on: Boolean)                                              : Cmd()
    data class DnsSet(val primary: String, val secondary: String = "8.8.8.8")          : Cmd()
    data class AppMove(val pkg: String, val toExternal: Boolean)                        : Cmd()
    data class UserCreate(val name: String)                                             : Cmd()
    data class UserRemove(val userId: Int)                                              : Cmd()
    data class UserList(val all: Boolean = true)                                        : Cmd()
    data class PropGet(val key: String)                                                 : Cmd()
    data class PropSet(val key: String, val value: String)                              : Cmd()
    data class PropList(val prefix: String = "")                                        : Cmd()
    data class ActivityStart(val pkg: String, val activity: String, val user: Int = 0) : Cmd()
    data class BroadcastSend(val action: String, val extras: Map<String,String> = emptyMap()) : Cmd()
    data class ServiceStart(val pkg: String, val service: String)                       : Cmd()
    data class DumpsysRun(val service: String, val args: String = "")                  : Cmd()
    object Ping : Cmd(); object Info : Cmd(); object Shutdown : Cmd(); object ClientList : Cmd(); object SysStats : Cmd()
}

sealed class CmdResult {
    data class ShellResult(val exit: Int, val out: String, val err: String, val ms: Long) : CmdResult()
    data class PkgResult(val pkg: String, val ok: Boolean, val msg: String = "")         : CmdResult()
    data class PkgListResult(val packages: List<GamePkgInfo>)                            : CmdResult()
    data class PermResult(val pkg: String, val perm: String, val granted: Boolean)       : CmdResult()
    data class PermListResult(val perms: List<GamePermInfo>)                             : CmdResult()
    data class SettingResult(val ns: NS, val key: String, val value: String?)            : CmdResult()
    data class SettingListResult(val settings: Map<String,String>)                       : CmdResult()
    data class NetResult(val ok: Boolean, val msg: String = "")                          : CmdResult()
    data class PropResult(val key: String, val value: String?)                           : CmdResult()
    data class PropListResult(val props: Map<String,String>)                             : CmdResult()
    data class InfoResult(val info: ServiceInfo)                                         : CmdResult()
    data class ClientListResult(val clients: List<ClientInfo>)                           : CmdResult()
    data class SysStatsResult(val stats: GameSysStats)                                   : CmdResult()
    object Pong : CmdResult(); object Done : CmdResult()
    data class Failure(val code: Int, val msg: String) : CmdResult()
}

enum class NS { GLOBAL, SECURE, SYSTEM }

data class AnticheatState(
    val enabled: Boolean          = false,
    val rootDetected: Boolean     = false,
    val emulatorDetected: Boolean = false,
    val debuggerDetected: Boolean = false,
    val hookDetected: Boolean     = false,
    val tamperDetected: Boolean   = false,
    val integrityOk: Boolean      = true,
    val lastCheckMs: Long         = 0L,
    val threatLevel: ThreatLevel  = ThreatLevel.NONE,
    val activeThreats: List<String> = emptyList()
) {
    enum class ThreatLevel { NONE, LOW, MEDIUM, HIGH, CRITICAL }
    val safe get() = threatLevel == ThreatLevel.NONE && integrityOk
}

data class SecurityState(
    val encryptionEnabled: Boolean = true,
    val sessionToken: String       = ""
)

data class AdbState(
    val status: Status      = Status.IDLE,
    val port: Int           = -1,
    val host: String        = "127.0.0.1",
    val error: String       = "",
    val connectedAt: Long   = 0L,
    val pairingPort: Int    = -1,
    val pairingStep: String = ""
) {
    enum class Status { IDLE, SCANNING, FOUND, SCAN_PAIRING, PAIRING, PAIRED, CONNECTING, CONNECTED, ERROR, RECONNECTING }
    val connected    get() = status == Status.CONNECTED
    val errored      get() = status == Status.ERROR
    val busy         get() = status in listOf(Status.SCANNING, Status.CONNECTING, Status.PAIRING, Status.RECONNECTING, Status.SCAN_PAIRING)
    val pairingReady get() = pairingPort in 1..65535
}

data class ServiceState(
    val running: Boolean = false, val clients: Int = 0,
    val startedAt: Long  = 0L,    val version: Int  = APP_VERSION
) {
    val uptime get() = if (running && startedAt > 0) System.currentTimeMillis() - startedAt else 0L
}

data class ServiceInfo(
    val version: Int    = APP_VERSION,       val sdk: Int      = Build.VERSION.SDK_INT,
    val abi: String     = primaryAbi(),      val has32: Boolean = has32(),
    val has64: Boolean  = is64(),            val clients: Int   = 0,
    val startedAt: Long = System.currentTimeMillis(), val uptime: Long = 0L,
    val anticheatActive: Boolean = false,    val secureMode: Boolean = false
)

data class ClientInfo(
    val uid: Int, val pid: Int, val pkg: String, val token: String,
    val connectedAt: Long = System.currentTimeMillis(), val alive: Boolean = true
)

data class GamePkgInfo(
    val packageName: String, val versionName: String?, val versionCode: Long,
    val label: String, val isSystem: Boolean, val isEnabled: Boolean,
    val targetSdk: Int, val installedAt: Long, val updatedAt: Long,
    val sourceDir: String?, val dataDir: String?, val uid: Int
)

data class GamePermInfo(val name: String, val granted: Boolean, val flags: Int, val protectionLevel: Int)

data class GameSysStats(
    val totalMem: Long, val availMem: Long, val freeMem: Long,
    val bootMs: Long,   val monoMs: Long,   val sdk: Int, val abi: String,
    val selinux: String,val cpuInfo: String, val pid: Int, val uid: Int,
    val has32: Boolean, val has64: Boolean, val debuggable: Boolean
)

enum class AppTab { HOME, PAIR, SHELL, PKGS, SECURITY, LOGS, SETTINGS }

data class UiState(
    val service: ServiceState      = ServiceState(),
    val adb: AdbState              = AdbState(),
    val anticheat: AnticheatState  = AnticheatState(),
    val security: SecurityState    = SecurityState(),
    val logs: List<LogEntry>       = emptyList(),
    val tab: AppTab                = AppTab.HOME,
    val sdkOk: Boolean             = sdkOk(),
    val sdk: Int                   = Build.VERSION.SDK_INT,
    val isLoading: Boolean         = false,
    val pkgList: List<GamePkgInfo> = emptyList(),
    val shellHistory: List<String> = emptyList(),
    val sysStats: GameSysStats?    = null,
    val wizardDone: Boolean        = false,
    val language: String           = "tr"
)

sealed class UiEvent {
    data class Snack(val msg: String, val err: Boolean = false) : UiEvent()
    data class ShowDialog(val title: String, val body: String)  : UiEvent()
    object RestartActivity                                       : UiEvent()
}

object GameAnticheat {
    private val _state   = MutableStateFlow(AnticheatState())
    val state: StateFlow<AnticheatState> = _state.asStateFlow()
    private val scope    = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scanJob: Job?       = null
    private var appMonitorJob: Job? = null

    private val SUSPICIOUS_PKGS = setOf(
        "com.topjohnwu.magisk","io.github.huskydg.magisk","com.kingroot.kinguser",
        "com.kingo.root","com.smedialink.oneclickroot","com.zhiqupk.root.global",
        "com.alephzain.framaroot","com.noshufou.android.su","com.thirdparty.superuser",
        "eu.chainfire.supersu","com.koushikdutta.superuser","com.zachspong.temprootremovejb",
        "com.ramdroid.appquarantine","de.robv.android.xposed.installer","com.saurik.substrate",
        "com.formyhm.hideroot","com.amphoras.hidemyroot","com.devadvance.rootcloak",
        "com.devadvance.rootcloakplus","me.phh.superuser","com.nv.hardware.overlay",
        "catch_.me_.if_.you_.can_","com.cih.gamecih","com.cih.gamecih2",
        "com.niemoney.my.cheatengine","com.zune.gamekiller"
    )

    fun start() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch { while (true) { performScan(); delay(30_000L) } }
    }

    fun startAppMonitor(ctx: Context) {
        if (appMonitorJob?.isActive == true) return
        appMonitorJob = scope.launch {
            while (true) { try { monitorRunningApps(ctx) } catch (_: Exception) {}; delay(15_000L) }
        }
    }

    private fun monitorRunningApps(ctx: Context) {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java) ?: return
        val pm = ctx.packageManager
        @Suppress("DEPRECATION") val running = am.runningAppProcesses ?: return
        val newThreats = mutableListOf<String>()
        for (proc in running) {
            for (pkg in (proc.pkgList ?: continue)) {
                if (pkg == PKG || !SUSPICIOUS_PKGS.contains(pkg)) continue
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
                newThreats += ctx.getString(R.string.threat_dangerous_app, label)
            }
        }
        if (newThreats.isEmpty()) return
        val cur    = _state.value
        val merged = (cur.activeThreats + newThreats).distinct()
        _state.value = cur.copy(
            activeThreats = merged,
            threatLevel   = when { merged.size >= 4 -> AnticheatState.ThreatLevel.CRITICAL; merged.size >= 3 -> AnticheatState.ThreatLevel.HIGH; merged.size >= 2 -> AnticheatState.ThreatLevel.MEDIUM; else -> AnticheatState.ThreatLevel.LOW }
        )
    }

    fun stop() { scanJob?.cancel(); scanJob = null; appMonitorJob?.cancel(); appMonitorJob = null }

    fun performScan() {
        val root = nativeCheckRoot(); val emu = nativeCheckEmulator()
        val dbg  = nativeCheckDebugger(); val hook = nativeCheckHook(); val tamper = nativeCheckTamper()
        val threats = listOfNotNull(
            "threat_root".takeIf { root }, "threat_emulator".takeIf { emu },
            "threat_debugger".takeIf { dbg }, "threat_hook".takeIf { hook }, "threat_tamper".takeIf { tamper }
        )
        val all = (threats + _state.value.activeThreats.filter { it.startsWith("Tehlikeli") }).distinct()
        _state.value = AnticheatState(
            enabled = true, rootDetected = root, emulatorDetected = emu,
            debuggerDetected = dbg, hookDetected = hook, tamperDetected = tamper,
            integrityOk = true, lastCheckMs = System.currentTimeMillis(),
            threatLevel = when (all.size) { 0 -> AnticheatState.ThreatLevel.NONE; 1 -> AnticheatState.ThreatLevel.LOW; 2 -> AnticheatState.ThreatLevel.MEDIUM; 3 -> AnticheatState.ThreatLevel.HIGH; else -> AnticheatState.ThreatLevel.CRITICAL },
            activeThreats = all
        )
    }

    external fun nativeCheckRoot(): Boolean
    external fun nativeCheckEmulator(): Boolean
    external fun nativeCheckDebugger(): Boolean
    external fun nativeCheckHook(): Boolean
    external fun nativeCheckTamper(): Boolean
    external fun nativeCheckIntegrity(pkg: String): Boolean
    external fun nativeFullScan(): String
    external fun nativeComprehensiveScan(pkg: String): String
    external fun nativeSetThreatLevel(lvl: Int)
    external fun nativeGetThreatLevel(): Int
    external fun nativeDetectSuspiciousProcs(): Boolean
    external fun nativeDetectSuspiciousLibs(): Boolean
    external fun nativeDetectMitm(): Boolean
    external fun nativeCheckVerifiedBoot(): Boolean
    external fun nativeCheckEncryption(): Boolean
    external fun nativeCheckTee(): Boolean
    external fun nativeCheckFridaGadget(): Boolean
    external fun nativeCountLibraries(): Int
    external fun nativeCheckArtIntegrity(): Boolean
    external fun nativeCheckZygoteParent(): Boolean
    external fun nativeDetectVpn(): Boolean
    external fun nativeDetectAdbAlwaysOn(): Boolean
    external fun nativeCheckTimingAttack(): Boolean
    external fun nativeCheckVmAcceleration(): Boolean
    external fun nativeProtectSelf(): Boolean
}

class GameSecurityManager(private val ctx: Context) {
    private val _state = MutableStateFlow(SecurityState())
    val state: StateFlow<SecurityState> = _state.asStateFlow()

    fun init() {}

    fun generateSessionToken(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
    }

    fun hashString(input: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun verifyPermission(callerUid: Int): Boolean {
        val pkgs = ctx.packageManager.getPackagesForUid(callerUid) ?: return false
        return pkgs.any { ctx.packageManager.checkPermission(PERM_USE, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun getCallerPackage(uid: Int): String? = ctx.packageManager.getPackagesForUid(uid)?.firstOrNull()
}

class GameHealthWorker {
    fun run() {
        if (!sdkOk()) return
        if (GameServer.isRunning()) GameAnticheat.performScan()
    }
}

object GameDiagnostics {
    fun runAll(): Map<String, String> = buildMap {
        put("sdk_ok",        sdkOk().toString())
        put("sdk",           Build.VERSION.SDK_INT.toString())
        put("abi",           primaryAbi())
        put("has_32bit",     has32().toString())
        put("has_64bit",     is64().toString())
        put("server_running",GameServer.isRunning().toString())
        put("ac_enabled",    GameAnticheat.state.value.enabled.toString())
        put("ac_safe",       GameAnticheat.state.value.safe.toString())
        put("ac_level",      GameAnticheat.state.value.threatLevel.name)
        put("client_count",  GameServer.clientCount().toString())
        put("cmd_count",     GameServer.cmdCount().toString())
        put("server_uptime", formatUptime(GameServer.uptime()))
        put("app_process32", java.io.File("/system/bin/app_process32").exists().toString())
        put("app_process64", java.io.File("/system/bin/app_process64").exists().toString())
        put("pkg",           PKG)
        put("build",         Build.FINGERPRINT.take(40))
    }

    fun toReport(): String = runAll().entries.joinToString("\n") { "${it.key} = ${it.value}" }

    fun checkCompatibility(): List<String> = buildList {
        if (!sdkOk()) add("SDK ${Build.VERSION.SDK_INT} desteklenmiyor (30-36 gerekli)")
        if (!has32() && !is64()) add("Desteklenen ABI bulunamadı")
        if (!java.io.File("/system/bin/app_process").exists()) add("app_process bulunamadı")
    }
}

data class LogDbEntry(val id: Long = 0, val msg: String, val level: String, val tag: String, val ts: Long = System.currentTimeMillis())
data class SecurityEvent(val id: Long = 0, val type: String, val detail: String, val severity: Int, val ts: Long = System.currentTimeMillis(), val resolved: Boolean = false)
data class SessionRecord(val token: String, val uid: Int, val pkg: String, val pid: Int, val startedAt: Long = System.currentTimeMillis(), val endedAt: Long = 0L, val active: Boolean = true)

object GameDatabase {
    private val logs     = mutableListOf<LogDbEntry>()
    private val events   = mutableListOf<SecurityEvent>()
    private val sessions = mutableListOf<SessionRecord>()
    private val lock     = Any()

    fun insertLog(entry: LogDbEntry)       { synchronized(lock) { logs.add(entry); if (logs.size > 1000) logs.removeAt(0) } }
    fun insertEvent(event: SecurityEvent)  { synchronized(lock) { events.add(event) } }
    fun cleanOlderThan(before: Long)       { synchronized(lock) { logs.removeAll { it.ts < before }; events.removeAll { it.ts < before } } }
    fun get(ctx: Context) = this
    fun logDao()           = this
    fun securityEventDao() = this
    fun deleteOlderThan(before: Long) { cleanOlderThan(before) }
    suspend fun insert(entry: LogDbEntry) { insertLog(entry) }
    suspend fun insert(event: SecurityEvent) { insertEvent(event) }
}

object GameServer {
    private val running   = AtomicBoolean(false)
    private val clients   = ConcurrentHashMap<String, ClientInfo>()
    private val startedAt = AtomicLong(0L)
    private val scope     = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cmdCount  = AtomicLong(0L)

    fun start()        { if (!running.compareAndSet(false, true)) return; startedAt.set(System.currentTimeMillis()); startHeartbeat() }
    fun stop()         { running.set(false); scope.cancel() }
    fun isRunning()    = running.get()
    fun clientCount()  = clients.size
    fun cmdCount()     = cmdCount.get()
    fun uptime()       = if (running.get() && startedAt.get() > 0) System.currentTimeMillis() - startedAt.get() else 0L

    fun register(uid: Int, pkg: String, pid: Int, binder: IBinder): String {
        val token = "${uid.toString(16)}${UUID.randomUUID().toString().replace("-","").take(8)}"
        clients[token] = ClientInfo(uid, pid, pkg, token)
        binder.linkToDeath({ unregister(token) }, 0)
        return token
    }

    fun unregister(token: String) { clients.remove(token) }
    fun getClients(): List<ClientInfo> = clients.values.toList()

    fun execute(cmd: Cmd, @Suppress("UNUSED_PARAMETER") callerUid: Int = 0): Res<CmdResult> {
        if (!running.get()) return Res.err(Err.DEAD, "Service not running")
        cmdCount.incrementAndGet()
        return try {
            when (cmd) {
                is Cmd.Shell           -> doShell(cmd)
                is Cmd.PkgInstall      -> doPkgShell("pm install${if(cmd.replace)" -r" else ""}${if(cmd.grant)" -g" else ""}${if(cmd.allowDowngrade)" -d" else ""} ${cmd.apk}", cmd.apk)
                is Cmd.PkgUninstall    -> doPkgShell("pm uninstall${if(cmd.keepData)" -k" else ""} --user ${cmd.user} ${cmd.pkg}", cmd.pkg)
                is Cmd.PkgEnable       -> doPkgShell("pm ${if(cmd.enabled)"enable" else "disable-user --user ${cmd.user}"} ${cmd.pkg}", cmd.pkg)
                is Cmd.PkgHide         -> doPkgShell("pm ${if(cmd.hidden)"hide" else "unhide"} --user ${cmd.user} ${cmd.pkg}", cmd.pkg)
                is Cmd.PkgClear        -> doPkgShell("pm clear --user ${cmd.user} ${cmd.pkg}", cmd.pkg, "Success")
                is Cmd.PkgStop         -> doPkgShell("am force-stop ${cmd.pkg}", cmd.pkg)
                is Cmd.PkgList         -> doPkgList(cmd)
                is Cmd.PkgInfo         -> Res.ok(CmdResult.PkgListResult(listOf(GamePkgInfo(cmd.pkg,null,0,cmd.pkg,false,true,0,0,0,null,null,0))))
                is Cmd.PermGrant       -> doPermShell("pm grant --user ${cmd.user} ${cmd.pkg} ${cmd.perm}", cmd.pkg, cmd.perm, true)
                is Cmd.PermRevoke      -> doPermShell("pm revoke --user ${cmd.user} ${cmd.pkg} ${cmd.perm}", cmd.pkg, cmd.perm, false)
                is Cmd.PermList        -> doPermList(cmd)
                is Cmd.SettingWrite    -> doSettingWrite(cmd)
                is Cmd.SettingRead     -> doSettingRead(cmd)
                is Cmd.SettingDelete   -> doSettingDelete(cmd)
                is Cmd.SettingList     -> doSettingList(cmd)
                is Cmd.ComponentToggle -> doPkgShell("pm ${if(cmd.enabled)"enable" else "disable"} ${cmd.pkg}/${cmd.comp}", cmd.pkg)
                is Cmd.WifiSet         -> doNetShell("svc wifi ${if(cmd.on)"enable" else "disable"}")
                is Cmd.MobileDataSet   -> doNetShell("svc data ${if(cmd.on)"enable" else "disable"}")
                is Cmd.AirplaneSet     -> doAirplane(cmd)
                is Cmd.AdbWifiSet      -> doNetShell("settings put global adb_wifi_enabled ${if(cmd.on)1 else 0}")
                is Cmd.DnsSet          -> doNetShell("settings put global private_dns_specifier ${cmd.primary}")
                is Cmd.AppMove         -> doPkgShell("pm move-package ${cmd.pkg} ${if(cmd.toExternal)1 else 0}", cmd.pkg)
                is Cmd.UserCreate      -> doShell(Cmd.Shell("pm create-user \"${cmd.name}\""))
                is Cmd.UserRemove      -> doShell(Cmd.Shell("pm remove-user ${cmd.userId}"))
                is Cmd.UserList        -> doShell(Cmd.Shell("pm list users"))
                is Cmd.PropGet         -> doPropGet(cmd)
                is Cmd.PropSet         -> doPropSet(cmd)
                is Cmd.PropList        -> doPropList(cmd)
                is Cmd.ActivityStart   -> doShell(Cmd.Shell("am start-activity -n ${cmd.pkg}/${cmd.activity}${if(cmd.user>0)" --user ${cmd.user}" else ""}"))
                is Cmd.BroadcastSend   -> { val ex=cmd.extras.entries.joinToString(" "){" --es ${it.key} ${it.value}"}; doShell(Cmd.Shell("am broadcast -a ${cmd.action}$ex")) }
                is Cmd.ServiceStart    -> doShell(Cmd.Shell("am start-service -n ${cmd.pkg}/${cmd.service}"))
                is Cmd.DumpsysRun      -> doShell(Cmd.Shell("dumpsys ${cmd.service} ${cmd.args}"))
                Cmd.Ping               -> Res.ok(CmdResult.Pong)
                Cmd.Info               -> Res.ok(CmdResult.InfoResult(buildInfo()))
                Cmd.ClientList         -> Res.ok(CmdResult.ClientListResult(getClients()))
                Cmd.SysStats           -> Res.ok(CmdResult.SysStatsResult(buildSysStats()))
                Cmd.Shutdown           -> { scope.launch { stop(); System.exit(0) }; Res.ok(CmdResult.Done) }
            }
        } catch (e: Exception) { Res.err(Err.OP_FAIL, e.message ?: "Unknown error") }
    }

    private fun doShell(c: Cmd.Shell): Res<CmdResult> {
        val t  = System.currentTimeMillis()
        val pb = ProcessBuilder("sh", "-c", c.cmd).apply {
            c.dir?.let { directory(java.io.File(it)) }
            c.env.forEach { (k,v) -> environment()[k] = v }
            redirectErrorStream(false)
        }
        val p   = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        val to  = !p.waitFor(c.timeoutMs, TimeUnit.MILLISECONDS)
        if (to) p.destroyForcibly()
        return Res.ok(CmdResult.ShellResult(if (to) -1 else p.exitValue(), out, err, System.currentTimeMillis() - t))
    }

    private fun doPkgShell(s: String, pkg: String, check: String = ""): Res<CmdResult> {
        val r  = doShell(Cmd.Shell(s)).getOrNull() as? CmdResult.ShellResult
        val ok = r != null && (r.exit == 0 || (check.isNotEmpty() && r.out.contains(check)))
        return Res.ok(CmdResult.PkgResult(pkg, ok, r?.out?.trim() ?: ""))
    }

    private fun doPermShell(s: String, pkg: String, perm: String, grant: Boolean): Res<CmdResult> {
        val r = doShell(Cmd.Shell(s)).getOrNull() as? CmdResult.ShellResult
        return Res.ok(CmdResult.PermResult(pkg, perm, r?.exit == 0 && grant))
    }

    private fun doPkgList(c: Cmd.PkgList): Res<CmdResult> {
        val r    = doShell(Cmd.Shell("pm list packages ${if(!c.system)"-3" else ""} --user ${c.user}")).getOrNull() as? CmdResult.ShellResult
        val pkgs = r?.out?.lines()?.mapNotNull { l -> l.removePrefix("package:").trim().takeIf { it.isNotBlank() } }
            ?.map { GamePkgInfo(it,null,0,it,false,true,0,0,0,null,null,0) } ?: emptyList()
        return Res.ok(CmdResult.PkgListResult(pkgs))
    }

    private fun doPermList(c: Cmd.PermList): Res<CmdResult> {
        val r     = doShell(Cmd.Shell("pm list permissions -g ${c.pkg}")).getOrNull() as? CmdResult.ShellResult
        val perms = r?.out?.lines()?.mapNotNull { l -> l.removePrefix("permission:").trim().takeIf { it.isNotBlank() } }
            ?.map { GamePermInfo(it,false,0,0) } ?: emptyList()
        return Res.ok(CmdResult.PermListResult(perms))
    }

    private fun doSettingWrite(c: Cmd.SettingWrite): Res<CmdResult>  { doShell(Cmd.Shell("settings put ${c.ns.name.lowercase()} ${c.key} ${c.value}")); return Res.ok(CmdResult.SettingResult(c.ns,c.key,c.value)) }
    private fun doSettingRead(c: Cmd.SettingRead): Res<CmdResult>    { val r=doShell(Cmd.Shell("settings get ${c.ns.name.lowercase()} ${c.key}")).getOrNull() as? CmdResult.ShellResult; return Res.ok(CmdResult.SettingResult(c.ns,c.key,r?.out?.trim())) }
    private fun doSettingDelete(c: Cmd.SettingDelete): Res<CmdResult>{ doShell(Cmd.Shell("settings delete ${c.ns.name.lowercase()} ${c.key}")); return Res.ok(CmdResult.SettingResult(c.ns,c.key,null)) }
    private fun doSettingList(c: Cmd.SettingList): Res<CmdResult> {
        val r   = doShell(Cmd.Shell("settings list ${c.ns.name.lowercase()}")).getOrNull() as? CmdResult.ShellResult
        val map = r?.out?.lines()?.mapNotNull { l -> val i=l.indexOf('='); if(i>0) l.substring(0,i) to l.substring(i+1) else null }?.toMap() ?: emptyMap()
        return Res.ok(CmdResult.SettingListResult(map))
    }
    private fun doNetShell(s: String): Res<CmdResult> { val r=doShell(Cmd.Shell(s)).getOrNull() as? CmdResult.ShellResult; return Res.ok(CmdResult.NetResult(r?.exit==0)) }
    private fun doAirplane(c: Cmd.AirplaneSet): Res<CmdResult> {
        doSettingWrite(Cmd.SettingWrite(NS.GLOBAL,"airplane_mode_on",if(c.on)"1" else "0"))
        doShell(Cmd.Shell("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state ${c.on}"))
        return Res.ok(CmdResult.NetResult(true))
    }
    private fun doPropGet(c: Cmd.PropGet): Res<CmdResult> { val r=doShell(Cmd.Shell("getprop ${c.key}")).getOrNull() as? CmdResult.ShellResult; return Res.ok(CmdResult.PropResult(c.key,r?.out?.trim())) }
    private fun doPropSet(c: Cmd.PropSet): Res<CmdResult> { doShell(Cmd.Shell("setprop ${c.key} ${c.value}")); return Res.ok(CmdResult.PropResult(c.key,c.value)) }
    private fun doPropList(c: Cmd.PropList): Res<CmdResult> {
        val r   = doShell(Cmd.Shell(if(c.prefix.isBlank())"getprop" else "getprop | grep '${c.prefix}'")).getOrNull() as? CmdResult.ShellResult
        val map = r?.out?.lines()?.mapNotNull { l -> val m=Regex("\\[(.+?)]: \\[(.*)]").find(l); m?.let{it.groupValues[1] to it.groupValues[2]} }?.toMap() ?: emptyMap()
        return Res.ok(CmdResult.PropListResult(map))
    }

    private fun buildInfo() = ServiceInfo(clients=clients.size, startedAt=startedAt.get(), uptime=uptime(), anticheatActive=GameAnticheat.state.value.enabled, secureMode=true)

    private fun buildSysStats(): GameSysStats {
        val memInfo = doShell(Cmd.Shell("cat /proc/meminfo")).getOrNull() as? CmdResult.ShellResult
        fun memKb(field: String): Long { val line=memInfo?.out?.lines()?.firstOrNull{it.startsWith(field)}?:return 0L; return line.replace(Regex("[^0-9]"),"").toLongOrNull()?:0L }
        val uptimeR = doShell(Cmd.Shell("cat /proc/uptime")).getOrNull() as? CmdResult.ShellResult
        val bootMs  = uptimeR?.out?.trim()?.split(" ")?.firstOrNull()?.toDoubleOrNull()?.let{(it*1000).toLong()}?:0L
        val selinux = (doShell(Cmd.Shell("cat /sys/fs/selinux/enforce")).getOrNull() as? CmdResult.ShellResult)?.out?.trim()?.let{when(it){"1"->"enforcing";"0"->"permissive";else->"unknown"}}?:"unknown"
        val cpuR    = doShell(Cmd.Shell("grep -m1 'Hardware\\|model name' /proc/cpuinfo || getprop ro.hardware")).getOrNull() as? CmdResult.ShellResult
        return GameSysStats(totalMem=memKb("MemTotal:")*1024L, availMem=memKb("MemAvailable:")*1024L, freeMem=memKb("MemFree:")*1024L, bootMs=bootMs, monoMs=System.currentTimeMillis(), sdk=Build.VERSION.SDK_INT, abi=primaryAbi(), selinux=selinux, cpuInfo=cpuR?.out?.substringAfter(":")?.trim()?:Build.HARDWARE, pid=Process.myPid(), uid=Process.myUid(), has32=has32(), has64=is64(), debuggable=Build.VERSION.SDK_INT<=28)
    }

    private fun startHeartbeat() {
        scope.launch { while (running.get()) { delay(30_000L); clients.entries.filter{!it.value.alive}.map{it.key}.forEach{clients.remove(it)} } }
    }
}

object GameServerEntry {
    @JvmStatic fun main(args: Array<String>) {
        if (!sdkOk()) { System.err.println("Desteklenmeyen SDK: ${Build.VERSION.SDK_INT}"); System.exit(1) }
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        GameServer.start(); GameAnticheat.start(); Looper.loop()
    }
}

class GameProvider : ContentProvider() {
    private lateinit var security: GameSecurityManager

    override fun onCreate(): Boolean {
        if (!sdkOk()) return false
        security = GameSecurityManager(context!!); security.init(); GameServer.start(); return true
    }

    override fun call(m: String, a: String?, ex: Bundle?): Bundle? {
        val uid = Binder.getCallingUid(); val pid = Binder.getCallingPid()
        if (!security.verifyPermission(uid)) return Bundle().apply { putInt("r", Err.PERM_DENIED) }
        return when (m) {
            "send"             -> handleSend(uid, pid, ex)
            "get"              -> Bundle().apply { putInt("r",if(GameServer.isRunning())Err.OK else Err.DEAD); putInt("clients",GameServer.clientCount()) }
            "version"          -> Bundle().apply { putInt("v",APP_VERSION); putInt("r",Err.OK) }
            "ping"             -> Bundle().apply { putInt("r",Err.OK) }
            "info"             -> Bundle().apply { putInt("r",Err.OK); putInt("version",APP_VERSION); putInt("sdk",Build.VERSION.SDK_INT); putString("abi",primaryAbi()); putBoolean("has32",has32()); putBoolean("has64",is64()); putInt("clients",GameServer.clientCount()) }
            "shell"            -> execCmd(Cmd.Shell(ex?.getString("cmd")?:""))
            "pkg_install"      -> execCmd(Cmd.PkgInstall(ex?.getString("apk")?:"",ex?.getBoolean("replace",true)?:true,ex?.getBoolean("grant",false)?:false,ex?.getBoolean("downgrade",false)?:false))
            "pkg_uninstall"    -> execCmd(Cmd.PkgUninstall(ex?.getString("pkg")?:"",ex?.getBoolean("keepData",false)?:false,ex?.getInt("user",0)?:0))
            "pkg_enable"       -> execCmd(Cmd.PkgEnable(ex?.getString("pkg")?:"",ex?.getBoolean("enabled",true)?:true,ex?.getInt("user",0)?:0))
            "pkg_hide"         -> execCmd(Cmd.PkgHide(ex?.getString("pkg")?:"",ex?.getBoolean("hidden",true)?:true,ex?.getInt("user",0)?:0))
            "pkg_clear"        -> execCmd(Cmd.PkgClear(ex?.getString("pkg")?:"",ex?.getInt("user",0)?:0))
            "pkg_stop"         -> execCmd(Cmd.PkgStop(ex?.getString("pkg")?:""))
            "pkg_list"         -> execCmd(Cmd.PkgList(ex?.getInt("user",0)?:0,ex?.getBoolean("system",false)?:false))
            "pkg_info"         -> execCmd(Cmd.PkgInfo(ex?.getString("pkg")?:""))
            "perm_grant"       -> execCmd(Cmd.PermGrant(ex?.getString("pkg")?:"",ex?.getString("perm")?:"",ex?.getInt("user",0)?:0))
            "perm_revoke"      -> execCmd(Cmd.PermRevoke(ex?.getString("pkg")?:"",ex?.getString("perm")?:"",ex?.getInt("user",0)?:0))
            "setting_write"    -> execCmd(Cmd.SettingWrite(NS.valueOf((ex?.getString("ns")?:"global").uppercase()),ex?.getString("key")?:"",ex?.getString("value")?:""))
            "setting_read"     -> execCmd(Cmd.SettingRead(NS.valueOf((ex?.getString("ns")?:"global").uppercase()),ex?.getString("key")?:""))
            "setting_delete"   -> execCmd(Cmd.SettingDelete(NS.valueOf((ex?.getString("ns")?:"global").uppercase()),ex?.getString("key")?:""))
            "net_wifi"         -> execCmd(Cmd.WifiSet(a=="true"))
            "net_data"         -> execCmd(Cmd.MobileDataSet(a=="true"))
            "net_airplane"     -> execCmd(Cmd.AirplaneSet(a=="true"))
            "net_dns"          -> execCmd(Cmd.DnsSet(ex?.getString("primary")?:"8.8.8.8"))
            "prop_get"         -> execCmd(Cmd.PropGet(a?:""))
            "prop_set"         -> execCmd(Cmd.PropSet(ex?.getString("key")?:"",ex?.getString("value")?:""))
            "activity_start"   -> execCmd(Cmd.ActivityStart(ex?.getString("pkg")?:"",ex?.getString("activity")?:"",ex?.getInt("user",0)?:0))
            "service_start"    -> execCmd(Cmd.ServiceStart(ex?.getString("pkg")?:"",ex?.getString("service")?:""))
            "broadcast_send"   -> execCmd(Cmd.BroadcastSend(ex?.getString("action")?:"",emptyMap()))
            "component_toggle" -> execCmd(Cmd.ComponentToggle(ex?.getString("pkg")?:"",ex?.getString("comp")?:"",ex?.getBoolean("enabled",true)?:true))
            "user_create"      -> execCmd(Cmd.UserCreate(a?:""))
            "user_remove"      -> execCmd(Cmd.UserRemove(a?.toIntOrNull()?:0))
            "dumpsys"          -> execCmd(Cmd.DumpsysRun(ex?.getString("service")?:"",ex?.getString("args")?:""))
            else               -> null
        }
    }

    private fun execCmd(cmd: Cmd): Bundle {
        val res = GameServer.execute(cmd)
        return Bundle().apply {
            when (val r = res) {
                is Res.Ok  -> {
                    putInt("r",Err.OK)
                    when (val d=r.data) {
                        is CmdResult.ShellResult   -> { putInt("exit",d.exit); putString("out",d.out); putString("err",d.err); putLong("ms",d.ms) }
                        is CmdResult.PkgResult     -> { putBoolean("ok",d.ok); putString("msg",d.msg) }
                        is CmdResult.PermResult    -> { putBoolean("granted",d.granted); putString("perm",d.perm) }
                        is CmdResult.SettingResult -> putString("value",d.value)
                        is CmdResult.NetResult     -> putBoolean("ok",d.ok)
                        is CmdResult.PropResult    -> putString("value",d.value)
                        is CmdResult.PkgListResult -> putStringArray("packages",d.packages.map{it.packageName}.toTypedArray())
                        else -> {}
                    }
                }
                is Res.Err -> { putInt("r",r.code); putString("msg",r.msg) }
            }
        }
    }

    private fun handleSend(uid: Int, pid: Int, ex: Bundle?): Bundle {
        val b = ex?.getParcelable<GameBinder>("binder")?.b
        if (b==null||!b.pingBinder()) return Bundle().apply{putInt("r",Err.BINDER_NULL)}
        val pkg   = security.getCallerPackage(uid)?:"unknown"
        val token = GameServer.register(uid,pkg,pid,b)
        return Bundle().apply{putInt("r",Err.OK);putString("token",token)}
    }

    override fun query(u:Uri,p:Array<String>?,s:String?,a:Array<String>?,o:String?):Cursor?=null
    override fun getType(u:Uri):String?=null
    override fun insert(u:Uri,v:ContentValues?):Uri?=null
    override fun delete(u:Uri,s:String?,a:Array<String>?):Int=0
    override fun update(u:Uri,v:ContentValues?,s:String?,a:Array<String>?):Int=0
}

val Context.ds: DataStore<Preferences> by preferencesDataStore("eaquel")

object PK {
    val THEME     = intPreferencesKey("theme")
    val DYNAMIC   = booleanPreferencesKey("dynamic")
    val ANTICHEAT = booleanPreferencesKey("anticheat")
    val LAST_ADB  = intPreferencesKey("last_adb_port")
    val WIZARD    = booleanPreferencesKey("wizard_done")
    val LANGUAGE  = stringPreferencesKey("language")
}

class GameApp : Application() {
    override fun onCreate() {
        super.onCreate(); instance = this
        createNotificationChannels()
        if (sdkOk()) scheduleHealthWorker()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(listOf(
            NotificationChannel(NOTIF_CH_SVC,  getString(R.string.channel_name),      NotificationManager.IMPORTANCE_LOW).apply     { setShowBadge(false) },
            NotificationChannel(NOTIF_CH_AC,   getString(R.string.channel_anticheat), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(NOTIF_CH_WARN, getString(R.string.channel_warnings),  NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(NOTIF_CH_PAIR, getString(R.string.adb_pairing),       NotificationManager.IMPORTANCE_HIGH).apply     { description=getString(R.string.channel_desc); enableLights(true); lightColor=android.graphics.Color.BLUE },
            NotificationChannel(NOTIF_CH_ADB,  getString(R.string.channel_adb_setup), NotificationManager.IMPORTANCE_HIGH).apply     { description=getString(R.string.channel_adb_setup_desc); enableLights(true); lightColor=android.graphics.Color.GREEN; setShowBadge(true) }
        ))
    }

    private fun scheduleHealthWorker() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            while (true) {
                kotlinx.coroutines.delay(15 * 60 * 1000L)
                try { GameHealthWorker().run() } catch (_: Exception) {}
            }
        }
    }

    companion object { lateinit var instance: GameApp private set }
}

class GameForegroundService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID_SVC, NotificationCompat.Builder(this, NOTIF_CH_SVC)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build())
    }
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int = START_STICKY
    override fun onDestroy() { super.onDestroy(); GameServer.stop(); GameAnticheat.stop() }
}

class GameAnticheatService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            startForeground(NOTIF_ID_AC, NotificationCompat.Builder(this, NOTIF_CH_AC)
                .setContentTitle(getString(R.string.anticheat_title))
                .setContentText(getString(R.string.anticheat_text))
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT).setOngoing(true).build())
        }
        GameAnticheat.start(); GameAnticheat.startAppMonitor(this)
    }
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int = START_STICKY
    override fun onDestroy() { super.onDestroy(); GameAnticheat.stop() }
}

class GameBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (!sdkOk()) return
        fun start(cls: Class<*>) { val intent=Intent(ctx,cls); if(Build.VERSION.SDK_INT>=26) ctx.startForegroundService(intent) else ctx.startService(intent) }
        start(GameForegroundService::class.java); start(GameAnticheatService::class.java)
    }
}

class GamePackageReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) { @Suppress("UNUSED_VARIABLE") val pkg=i.data?.schemeSpecificPart?:return }
}

class PairCodeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_PAIR_CODE) return
        val code = androidx.core.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(EXTRA_PAIR_CODE)?.toString()?.trim() ?: return
        if (code.length != 6 || !code.all { it.isDigit() }) return
        val port = intent.getIntExtra("port", -1)
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID_PAIR)
        if (Build.VERSION.SDK_INT >= 30) GameAdbBridge(ctx).pair(port, code)
    }
}

class AdbPermReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        when (intent.action) {
            ACTION_ADB_DISMISS -> {
                nm.cancel(NOTIF_ID_ADB_PERM)
                nm.cancel(NOTIF_ID_ADB_STEP)
            }
            ACTION_ADB_OPEN_DEV -> {
                nm.cancel(NOTIF_ID_ADB_PERM)
                val devIntent = Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val altIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { ctx.startActivity(devIntent) } catch (_: Exception) {
                    try { ctx.startActivity(altIntent) } catch (_: Exception) {}
                }
            }
            ACTION_ADB_OPEN_PAIR -> {
                nm.cancel(NOTIF_ID_ADB_STEP)
                val pairIntent = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { ctx.startActivity(pairIntent) } catch (_: Exception) {}
            }
            ACTION_ADB_RECONNECT -> {
                nm.cancel(NOTIF_ID_ADB_DISCONNECTED)
                if (Build.VERSION.SDK_INT >= 30) {
                    val appIntent = Intent(ctx, GameActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("action_reconnect", true)
                    }
                    ctx.startActivity(appIntent)
                }
            }
        }
    }
}

object AdbNotificationManager {

    fun showSetupGuide(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.cancel(NOTIF_ID_ADB_PERM)

        val openAppPi = PendingIntent.getActivity(
            ctx, NOTIF_ID_ADB_PERM,
            Intent(ctx, GameActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("show_pair_tab", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openDevPi = PendingIntent.getBroadcast(
            ctx, NOTIF_ID_ADB_PERM + 1,
            Intent(ctx, AdbPermReceiver::class.java).apply { action = ACTION_ADB_OPEN_DEV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissPi = PendingIntent.getBroadcast(
            ctx, NOTIF_ID_ADB_PERM + 2,
            Intent(ctx, AdbPermReceiver::class.java).apply { action = ACTION_ADB_DISMISS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val lang = ctx.getSharedPreferences("eaquel_locale", Context.MODE_PRIVATE).getString("lang", "system") ?: "system"
        val title = LangManager.getString(ctx, lang, "notif_adb_setup_title")
        val body  = LangManager.getString(ctx, lang, "notif_adb_setup_body")
        val step1 = LangManager.getString(ctx, lang, "notif_adb_step1")
        val step2 = LangManager.getString(ctx, lang, "notif_adb_step2")
        val step3 = LangManager.getString(ctx, lang, "notif_adb_step3")
        val bigText = "$body\n\n1. $step1\n2. $step2\n3. $step3"
        val openDevLabel = LangManager.getString(ctx, lang, "notif_btn_open_dev")
        val dismissLabel = LangManager.getString(ctx, lang, "notif_btn_dismiss")

        nm.notify(NOTIF_ID_ADB_PERM,
            NotificationCompat.Builder(ctx, NOTIF_CH_ADB)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openAppPi)
                .setAutoCancel(false)
                .setOngoing(false)
                .addAction(android.R.drawable.ic_menu_preferences, openDevLabel, openDevPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, dismissLabel, dismissPi)
                .build()
        )
    }

    fun showPairingStepGuide(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.cancel(NOTIF_ID_ADB_STEP)

        val openAppPi = PendingIntent.getActivity(
            ctx, NOTIF_ID_ADB_STEP,
            Intent(ctx, GameActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("show_pair_tab", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPairPi = PendingIntent.getBroadcast(
            ctx, NOTIF_ID_ADB_STEP + 1,
            Intent(ctx, AdbPermReceiver::class.java).apply { action = ACTION_ADB_OPEN_PAIR },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissPi = PendingIntent.getBroadcast(
            ctx, NOTIF_ID_ADB_STEP + 2,
            Intent(ctx, AdbPermReceiver::class.java).apply { action = ACTION_ADB_DISMISS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val lang = ctx.getSharedPreferences("eaquel_locale", Context.MODE_PRIVATE).getString("lang", "system") ?: "system"
        val title      = LangManager.getString(ctx, lang, "notif_pair_step_title")
        val body       = LangManager.getString(ctx, lang, "notif_pair_step_body")
        val stepDetail = LangManager.getString(ctx, lang, "notif_pair_step_detail")
        val openPairLabel = LangManager.getString(ctx, lang, "notif_btn_open_pair")
        val dismissLabel  = LangManager.getString(ctx, lang, "notif_btn_dismiss")

        nm.notify(NOTIF_ID_ADB_STEP,
            NotificationCompat.Builder(ctx, NOTIF_CH_ADB)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$body\n\n$stepDetail"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openAppPi)
                .setAutoCancel(false)
                .setOngoing(false)
                .addAction(android.R.drawable.ic_menu_send, openPairLabel, openPairPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, dismissLabel, dismissPi)
                .build()
        )
    }

    fun showConnectedNotification(ctx: Context, host: String, port: Int) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.cancel(NOTIF_ID_ADB_PERM)
        nm.cancel(NOTIF_ID_ADB_STEP)
        nm.cancel(NOTIF_ID_ADB_DISCONNECTED)

        val openAppPi = PendingIntent.getActivity(
            ctx, NOTIF_ID_ADB_CONNECTED,
            Intent(ctx, GameActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val lang  = ctx.getSharedPreferences("eaquel_locale", Context.MODE_PRIVATE).getString("lang", "system") ?: "system"
        val title = LangManager.getString(ctx, lang, "notif_connected_title")
        val body  = LangManager.getString(ctx, lang, "notif_connected_body").replace("{host}", host).replace("{port}", port.toString())

        nm.notify(NOTIF_ID_ADB_CONNECTED,
            NotificationCompat.Builder(ctx, NOTIF_CH_ADB)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(openAppPi)
                .setAutoCancel(true)
                .build()
        )
    }

    fun showDisconnectedNotification(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.cancel(NOTIF_ID_ADB_CONNECTED)

        val openAppPi = PendingIntent.getActivity(
            ctx, NOTIF_ID_ADB_DISCONNECTED,
            Intent(ctx, GameActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("show_pair_tab", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val reconnectPi = PendingIntent.getBroadcast(
            ctx, NOTIF_ID_ADB_DISCONNECTED + 1,
            Intent(ctx, AdbPermReceiver::class.java).apply { action = ACTION_ADB_RECONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val lang = ctx.getSharedPreferences("eaquel_locale", Context.MODE_PRIVATE).getString("lang", "system") ?: "system"
        val title      = LangManager.getString(ctx, lang, "notif_disconnected_title")
        val body       = LangManager.getString(ctx, lang, "notif_disconnected_body")
        val reconnectLabel = LangManager.getString(ctx, lang, "notif_btn_reconnect")

        nm.notify(NOTIF_ID_ADB_DISCONNECTED,
            NotificationCompat.Builder(ctx, NOTIF_CH_ADB)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(openAppPi)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_rotate, reconnectLabel, reconnectPi)
                .build()
        )
    }

    fun cancelAll(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIF_ID_ADB_PERM)
        nm.cancel(NOTIF_ID_ADB_STEP)
        nm.cancel(NOTIF_ID_ADB_CONNECTED)
        nm.cancel(NOTIF_ID_ADB_DISCONNECTED)
    }
}

object LocaleHelper {
    private const val PREFS = "eaquel_locale"
    private const val KEY   = "lang"

    fun getSaved(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "system") ?: "system"

    fun save(ctx: Context, lang: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang).apply()

    fun wrap(ctx: Context, lang: String): Context {
        if (lang == "system" || lang.isBlank()) return ctx
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val lm = ctx.applicationContext.getSystemService(android.app.LocaleManager::class.java)
                val current = lm?.applicationLocales?.toLanguageTags() ?: ""
                if (current != lang) lm?.applicationLocales = android.os.LocaleList.forLanguageTags(lang)
            } catch (_: Exception) {}
        }
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(ctx.resources.configuration)
        config.setLocale(locale)
        return ctx.createConfigurationContext(config)
    }

    fun detectSystemLang(ctx: Context): String {
        val sysLang = java.util.Locale.getDefault().language
        val supported = listOf("tr", "en")
        return if (sysLang in supported) sysLang else "en"
    }
}

object LangManager {
    private val CODES = listOf("tr", "en")
    private val NAMES = listOf("Türkçe", "English")

    fun getCodes(ctx: Context): List<String> = CODES
    fun getNames(ctx: Context): List<String> = NAMES
    fun getLanguagesMap(ctx: Context): Map<String, String> = CODES.zip(NAMES).toMap()
    fun detectLanguage(ctx: Context): String {
        val l = java.util.Locale.getDefault().language
        return if (CODES.contains(l)) l else "en"
    }
    fun getString(ctx: Context, lang: String, key: String): String {
        val eff = if (lang == "system" || lang.isBlank()) detectLanguage(ctx) else lang
        for (n in listOf("lang_${eff}_$key", "lang_en_$key", key)) {
            val id = ctx.resources.getIdentifier(n, "string", ctx.packageName)
            if (id != 0) return ctx.getString(id)
        }
        return key
    }
}

class GameBinder(val b: IBinder?) : Parcelable {
    constructor(p: Parcel) : this(p.readStrongBinder())
    override fun writeToParcel(p: Parcel, f: Int) { p.writeStrongBinder(b) }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<GameBinder> {
        override fun createFromParcel(p: Parcel) = GameBinder(p)
        override fun newArray(s: Int) = arrayOfNulls<GameBinder>(s)
    }
}

@Composable
fun sComp(key: String): String {
    val ctx  = LocalContext.current
    val lang = ctx.getSharedPreferences("eaquel_locale", Context.MODE_PRIVATE).getString("lang","system") ?: "system"
    return LangManager.getString(ctx, lang, key)
}

fun s(ctx: Context, lang: String, key: String): String = LangManager.getString(ctx, lang, key)

fun Context.startGameService()  { val i=Intent(this,GameForegroundService::class.java); if(Build.VERSION.SDK_INT>=26) startForegroundService(i) else startService(i) }
fun Context.stopGameService()   = stopService(Intent(this, GameForegroundService::class.java))
fun Context.isServiceRunning()  = GameServer.isRunning()

class GameViewModel(app: Application) : AndroidViewModel(app) {
    internal val _ui    = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    val theme     = app.ds.data.map { it[PK.THEME]     ?: 0    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val dynamic   = app.ds.data.map { it[PK.DYNAMIC]   ?: true }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val anticheat = app.ds.data.map { it[PK.ANTICHEAT] ?: true }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private var bridge: Any?                  = null
    private val security: GameSecurityManager = GameSecurityManager(app)
    private val db: GameDatabase              = GameDatabase.get(app)

    init {
        security.init()
        if (Build.VERSION.SDK_INT >= 30) initBridge()
        observeAnticheat(); observeSecurity()
        viewModelScope.launch {
            val savedLang = app.ds.data.first()[PK.LANGUAGE]
            if (savedLang == null) {
                app.ds.edit { it[PK.LANGUAGE] = "system" }
                LocaleHelper.save(app, "system")
                _ui.value = _ui.value.copy(language = "system")
            } else {
                _ui.value = _ui.value.copy(language = savedLang)
            }
        }
    }

    @RequiresApi(30)
    private fun initBridge() {
        val b = GameAdbBridge(getApplication<GameApp>()); bridge = b
        viewModelScope.launch(Dispatchers.IO) {
            try { b.initPairing() } catch (_: UnsatisfiedLinkError) {
                log(getApplication<GameApp>().getString(R.string.msg_native_lib_failed), LogEntry.Level.WARN, TAG_ADB)
            }
        }
        viewModelScope.launch {
            b.adb.collect { s ->
                _ui.value = _ui.value.copy(adb = s)
                when (s.status) {
                    AdbState.Status.CONNECTED    -> {
                        log("adb_ok:${s.port}", LogEntry.Level.SUCCESS, TAG_ADB)
                        launchServer()
                        AdbNotificationManager.showConnectedNotification(getApplication(), s.host, s.port)
                    }
                    AdbState.Status.ERROR        -> {
                        log("adb_err:${s.error}", LogEntry.Level.ERROR, TAG_ADB)
                        AdbNotificationManager.showSetupGuide(getApplication())
                    }
                    AdbState.Status.PAIRED       -> log("pair_ok", LogEntry.Level.SUCCESS, TAG_ADB)
                    AdbState.Status.RECONNECTING -> {
                        log("reconnect", LogEntry.Level.WARN, TAG_ADB)
                        AdbNotificationManager.showDisconnectedNotification(getApplication())
                    }
                    else -> {}
                }
            }
        }
        viewModelScope.launch { delay(1_500L); b.tryAutoReconnect() }
        viewModelScope.launch {
            b.events.collect { e ->
                val app = getApplication<GameApp>()
                when {
                    e.startsWith("error:") -> emit(UiEvent.Snack(e.removePrefix("error:"), true))
                    e == "connected"       -> emit(UiEvent.Snack(app.getString(R.string.msg_adb_connected_snack)))
                    e == "disconnected"    -> {
                        emit(UiEvent.Snack(app.getString(R.string.msg_adb_disconnected_snack)))
                        AdbNotificationManager.showDisconnectedNotification(app)
                    }
                    e == "reconnect"       -> emit(UiEvent.Snack(app.getString(R.string.msg_reconnecting_snack)))
                }
            }
        }
    }

    private fun getBridge(): GameAdbBridge? = if (Build.VERSION.SDK_INT >= 30) bridge as? GameAdbBridge else null

    private fun observeAnticheat() {
        viewModelScope.launch {
            GameAnticheat.state.collect { ac ->
                _ui.value = _ui.value.copy(anticheat = ac)
                if (!ac.safe) {
                    val threats = ac.activeThreats.joinToString(", ")
                    log(getApplication<GameApp>().getString(R.string.msg_threat_detected, threats), LogEntry.Level.CRITICAL, TAG_AC)
                    db.insert(SecurityEvent(type="ANTICHEAT", detail=threats, severity=ac.threatLevel.ordinal))
                }
            }
        }
    }

    private fun observeSecurity() {
        viewModelScope.launch { security.state.collect { s -> _ui.value = _ui.value.copy(security = s) } }
    }

    private fun launchServer() {
        viewModelScope.launch {
            log(getApplication<GameApp>().getString(R.string.msg_server_starting), LogEntry.Level.INFO, TAG_SERVER)
            val ok = getBridge()?.startServer() == true
            log(if(ok)"srv_start" else "srv_fail", if(ok) LogEntry.Level.SUCCESS else LogEntry.Level.ERROR, TAG_SERVER)
            _ui.value = _ui.value.copy(service = _ui.value.service.copy(running = ok))
        }
    }

    fun scan() {
        if (Build.VERSION.SDK_INT < 30) { emit(UiEvent.Snack(getApplication<GameApp>().getString(R.string.msg_android_11_required), true)); return }
        log("scan_start", LogEntry.Level.INFO, TAG_ADB); getBridge()?.scanMdns()
    }

    fun startPairingScan() {
        if (Build.VERSION.SDK_INT < 30) { emit(UiEvent.Snack(getApplication<GameApp>().getString(R.string.msg_android_11_required), true)); return }
        getBridge()?.startPairingScan()
    }

    fun stopScan()                  { getBridge()?.stopScan(); log(getApplication<GameApp>().getString(R.string.msg_scan_stopped), LogEntry.Level.WARN, TAG_ADB) }
    fun getDeviceIpPublic(): String? = getBridge()?.getDeviceIpPublic()

    fun connectManual(host: String, port: Int) {
        if (Build.VERSION.SDK_INT < 30) { emit(UiEvent.Snack(getApplication<GameApp>().getString(R.string.msg_android_11_required), true)); return }
        getBridge()?.connectManual(host, port)
    }

    fun pair(port: Int, code: String) {
        if (port !in 1..65535 || code.length != 6) { emit(UiEvent.Snack(getApplication<GameApp>().getString(R.string.msg_invalid_port_code), true)); return }
        getBridge()?.pair(port, code)
    }

    fun pairWithAutoScan(code: String) {
        if (Build.VERSION.SDK_INT < 30) { emit(UiEvent.Snack(getApplication<GameApp>().getString(R.string.msg_android_11_required), true)); return }
        if (code.length != 6) { emit(UiEvent.Snack(s(getApplication(), getApplication<GameApp>().getSharedPreferences("eaquel_locale",Context.MODE_PRIVATE).getString("lang","system")?:"system","pair_code_short_err"), true)); return }
        getBridge()?.pairWithAutoScan(code)
    }

    fun disconnect()     { getBridge()?.disconnect(); log(getApplication<GameApp>().getString(R.string.msg_disconnect), LogEntry.Level.WARN, TAG_ADB) }
    fun getAdbPairingPort(): Int = getBridge()?.adb?.value?.pairingPort ?: 0
    fun pairDirect(port: Int, code: String) { if (Build.VERSION.SDK_INT >= 30) getBridge()?.pair(port, code) }

    fun shell(cmd: String) {
        if (cmd.isBlank()) return
        viewModelScope.launch {
            log("$ $cmd", LogEntry.Level.INFO, "Shell")
            _ui.value = _ui.value.copy(shellHistory = (_ui.value.shellHistory + cmd).takeLast(50))
            val r = getBridge()?.run(cmd) ?: "No connection"
            log(r, if(r.startsWith("hata",ignoreCase=true)) LogEntry.Level.ERROR else LogEntry.Level.SUCCESS, "Shell")
        }
    }

    suspend fun shellRaw(cmd: String): String = getBridge()?.run(cmd) ?: ""

    fun runAnticheatScan() {
        viewModelScope.launch(Dispatchers.Default) {
            GameAnticheat.performScan()
            val s = GameAnticheat.state.value
            log(if(s.safe)"scan_ok" else "scan_threats:${s.activeThreats.size}", if(s.safe) LogEntry.Level.SUCCESS else LogEntry.Level.WARN, TAG_AC)
        }
    }

    fun grantAllPermissions(pkg: String) {
        val perms = listOf("android.permission.READ_CONTACTS","android.permission.WRITE_CONTACTS","android.permission.READ_CALL_LOG","android.permission.WRITE_CALL_LOG","android.permission.READ_SMS","android.permission.SEND_SMS","android.permission.CAMERA","android.permission.RECORD_AUDIO","android.permission.ACCESS_FINE_LOCATION","android.permission.ACCESS_COARSE_LOCATION","android.permission.ACCESS_BACKGROUND_LOCATION","android.permission.READ_EXTERNAL_STORAGE","android.permission.WRITE_EXTERNAL_STORAGE","android.permission.READ_PHONE_STATE","android.permission.CALL_PHONE","android.permission.BLUETOOTH","android.permission.BLUETOOTH_CONNECT","android.permission.BLUETOOTH_SCAN","android.permission.BODY_SENSORS","android.permission.ACTIVITY_RECOGNITION")
        viewModelScope.launch { perms.forEach { perm -> shellRaw("pm grant $pkg $perm") }; emit(UiEvent.Snack(getApplication<GameApp>().getString(R.string.msg_all_perms_granted, pkg))) }
    }

    fun loadSysStats() {
        viewModelScope.launch(Dispatchers.IO) {
            (GameServer.execute(Cmd.SysStats).getOrNull() as? CmdResult.SysStatsResult)?.let { _ui.value = _ui.value.copy(sysStats = it.stats) }
        }
    }

    fun setLanguage(lang: String) {
        viewModelScope.launch {
            getApplication<GameApp>().ds.edit { it[PK.LANGUAGE] = lang }
            _ui.value = _ui.value.copy(language = lang)
            LocaleHelper.save(getApplication(), lang)
            emit(UiEvent.RestartActivity)
        }
    }

    fun completeWizard() {
        viewModelScope.launch {
            getApplication<GameApp>().ds.edit { it[PK.WIZARD] = true }
            _ui.value = _ui.value.copy(wizardDone = true)
        }
    }

    fun showScanningNotification() {
        if (Build.VERSION.SDK_INT < 30) return
        viewModelScope.launch(Dispatchers.IO) { try { getBridge()?.sendScanningNotification() } catch (_: Exception) {} }
    }

    fun showAdbPermissionNotification() {
        if (Build.VERSION.SDK_INT < 30) return
        val app = getApplication<GameApp>()
        val nm  = app.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled() || _ui.value.adb.connected) return
        AdbNotificationManager.showSetupGuide(app)
    }

    fun showPairingStepNotification() {
        if (Build.VERSION.SDK_INT < 30) return
        AdbNotificationManager.showPairingStepGuide(getApplication())
    }

    fun dismissAdbNotifications() {
        AdbNotificationManager.cancelAll(getApplication())
    }

    fun tab(t: AppTab)  { _ui.value = _ui.value.copy(tab = t) }
    fun clearLogs()     { _ui.value = _ui.value.copy(logs = emptyList()) }

    internal fun log(m: String, l: LogEntry.Level = LogEntry.Level.INFO, t: String = "") {
        val e   = LogEntry(m, l, t)
        val cur = _ui.value.logs.toMutableList()
        cur.add(0, e)
        if (cur.size > 300) cur.subList(300, cur.size).clear()
        _ui.value = _ui.value.copy(logs = cur)
        viewModelScope.launch(Dispatchers.IO) { db.insert(LogDbEntry(msg=m,level=l.name,tag=t)) }
    }

    internal fun emit(e: UiEvent) { viewModelScope.launch { _events.emit(e) } }
    override fun onCleared() { getBridge()?.destroy(); super.onCleared() }
}

class GameActivity : ComponentActivity() {
    private val vm: GameViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        val lang      = LocaleHelper.getSaved(newBase)
        val effective = if (lang == "system") LocaleHelper.detectSystemLang(newBase) else lang
        super.attachBaseContext(LocaleHelper.wrap(newBase, effective))
    }

    private val notifPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedState: Bundle?) {
        installSplashScreen(); super.onCreate(savedState); enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        handleLaunchIntent(intent)
        setContent {
            val ui      by vm.ui.collectAsState()
            val theme   by vm.theme.collectAsState()
            val dynamic by vm.dynamic.collectAsState()
            val snack   = remember { SnackbarHostState() }
            LaunchedEffect(Unit) {
                vm.events.collect { e ->
                    when (e) {
                        is UiEvent.Snack       -> snack.showSnackbar(e.msg)
                        is UiEvent.ShowDialog  -> snack.showSnackbar("${e.title}: ${e.body}")
                        is UiEvent.RestartActivity -> recreate()
                        else -> {}
                    }
                }
            }
            val dark = when (theme) { 1 -> false; 2 -> true; else -> androidx.compose.foundation.isSystemInDarkTheme() }
            GameTheme(dark, dynamic && Build.VERSION.SDK_INT >= 31) { GameRoot(ui, snack, vm, this) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("show_pair_tab", false)) {
            vm.tab(AppTab.PAIR)
        }
        if (i.getBooleanExtra("action_reconnect", false)) {
            vm.scan()
        }
    }

    override fun onResume() {
        super.onResume()
        val adb = vm.ui.value.adb
        if (!adb.connected && Build.VERSION.SDK_INT >= 30) {
            vm.showAdbPermissionNotification()
        }
    }
}

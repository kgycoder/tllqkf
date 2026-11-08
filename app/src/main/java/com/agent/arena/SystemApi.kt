package com.agent.arena

import android.app.NotificationManager
import android.app.SearchManager
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * UI(탭/스크롤) 없이 시스템 API / 인텐트로 바로 처리하는 "빠른 경로" 실행기.
 * 모델은 가능하면 이 액션들을 우선 사용한다 (볼륨/밝기/손전등/알람/타이머/설정 화면 직행 등).
 */
object SystemApi {

    /** 화면을 바꾸지 않는 액션: 실행 후 DEVICE_STATE(화면 덤프)를 다시 보낼 필요가 없다. */
    private val SILENT = setOf(
        "get_status", "set_volume", "set_ringer", "set_brightness", "flashlight", "set_dnd",
        "media", "set_system_setting", "set_alarm", "set_timer", "set_clipboard", "list_apps",
        "read_screen", "wait",
    )

    fun isSilent(action: String): Boolean = action.lowercase().trim() in SILENT


    // ------------------------------------------------------------------ entry

    /** 처리할 수 있는 액션이면 결과 문자열("ok: ..."/"error: ..."), 아니면 null. */
    suspend fun handle(ctx: Context, c: JSONObject): String? {
        val a = c.optString("action").lowercase().trim()
        return when (a) {
            "get_status" -> status(ctx)
            "set_volume" -> setVolume(ctx, c)
            "set_ringer" -> setRinger(ctx, c)
            "set_brightness" -> setBrightness(ctx, c)
            "flashlight", "torch" -> flashlight(ctx, c)
            "set_dnd" -> setDnd(ctx, c)
            "media" -> media(ctx, c)
            "set_system_setting" -> setSystemSetting(ctx, c)
            "set_alarm" -> setAlarm(ctx, c)
            "set_timer" -> setTimer(ctx, c)
            "set_clipboard" -> setClipboard(ctx, c)
            "list_apps" -> listApps(ctx, c)
            "open_settings" -> openSettings(ctx, c)
            "open_panel" -> openPanel(ctx, c)
            "dial" -> start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(c.optString("number")))), "dialer")
            "sms" -> sms(ctx, c)
            "email" -> email(ctx, c)
            "web_search" -> start(ctx, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, c.optString("query")), "web search")
            "maps" -> maps(ctx, c)
            "calendar_event" -> calendarEvent(ctx, c)
            "camera" -> camera(ctx, c)
            "play_music" -> playMusic(ctx, c)
            "share_text" -> shareText(ctx, c)
            "intent" -> genericIntent(ctx, c)
            else -> null
        }
    }

    // ------------------------------------------------------------------ status

    @Volatile private var torchOn = false
    @Volatile private var torchRegistered = false

    private fun ensureTorch(ctx: Context) {
        if (torchRegistered) return
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn = enabled }
            }, Handler(Looper.getMainLooper()))
            torchRegistered = true
        } catch (_: Exception) {}
    }

    /** 모델에게 매 턴 함께 주는 "지금 기기 상태". 이것만으로 답할 수 있는 질문은 액션이 필요 없다. */
    fun status(ctx: Context): String {
        ensureTorch(ctx)
        val sb = StringBuilder()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            sb.appendLine("battery: ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}% charging=${bm.isCharging} power_saver=${pm.isPowerSaveMode}")
        } catch (_: Exception) {}
        try {
            fun v(n: String, s: Int) = "$n ${am.getStreamVolume(s)}/${am.getStreamMaxVolume(s)}"
            val ringer = when (am.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
            sb.appendLine(
                "volume: " + listOf(
                    v("music", AudioManager.STREAM_MUSIC), v("ring", AudioManager.STREAM_RING),
                    v("alarm", AudioManager.STREAM_ALARM), v("notification", AudioManager.STREAM_NOTIFICATION),
                    v("call", AudioManager.STREAM_VOICE_CALL),
                ).joinToString(", ") + " | ringer=$ringer music_playing=${am.isMusicActive}"
            )
        } catch (_: Exception) {}
        try {
            val cr = ctx.contentResolver
            val b = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1
            val timeout = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30000) / 1000
            val rot = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
            sb.appendLine("display: brightness=${(b * 100f / 255f).roundToInt()}% auto_brightness=$auto screen_timeout_s=$timeout auto_rotate=$rot")
        } catch (_: Exception) {}
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val net = when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            val bt = try {
                (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled
            } catch (_: Exception) { null }
            val air = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            sb.appendLine("network: $net bluetooth=${bt?.let { if (it) "on" else "off" } ?: "unknown"} airplane=$air flashlight=${if (torchOn) "on" else "off"}")
        } catch (_: Exception) {}
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val f = when (nm.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms_only"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "total_silence"
                else -> "unknown"
            }
            sb.appendLine("dnd: $f")
            val st = StatFs(Environment.getDataDirectory().path)
            val gb = 1024.0 * 1024.0 * 1024.0
            sb.appendLine("storage: free ${"%.1f".format(st.availableBytes / gb)}GB of ${"%.0f".format(st.totalBytes / gb)}GB")
            sb.append(
                "permissions: write_settings=${if (Settings.System.canWrite(ctx)) "granted" else "missing"} " +
                    "dnd_access=${if (nm.isNotificationPolicyAccessGranted) "granted" else "missing"}"
            )
        } catch (_: Exception) {}
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------------ audio

    private fun streamOf(n: String): Int? = when (n.lowercase().trim()) {
        "", "music", "media" -> AudioManager.STREAM_MUSIC
        "ring", "ringtone" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "system" -> AudioManager.STREAM_SYSTEM
        "call", "voice" -> AudioManager.STREAM_VOICE_CALL
        else -> null
    }

    private fun setVolume(ctx: Context, c: JSONObject): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val name = c.optString("stream", "music").ifBlank { "music" }
        val s = streamOf(name) ?: return "error: unknown stream '$name' (music|ring|alarm|notification|system|call)"
        val max = am.getStreamMaxVolume(s)
        val min = if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(s) else 0
        val before = am.getStreamVolume(s)
        val target = when {
            c.optBoolean("mute", false) -> min
            c.optBoolean("max", false) -> max
            c.has("percent") -> (c.getDouble("percent") / 100.0 * max).roundToInt().coerceIn(min, max)
            c.has("steps") -> (before + c.getInt("steps")).coerceIn(min, max)
            else -> return "error: set_volume needs percent(0-100), steps(+N/-N), mute or max"
        }
        try {
            am.setStreamVolume(s, target, AudioManager.FLAG_SHOW_UI)
        } catch (e: SecurityException) {
            return "error: blocked by Do Not Disturb policy. Use set_dnd (needs dnd_access) or change via UI."
        }
        val now = am.getStreamVolume(s)
        val pct = if (max > 0) (now * 100f / max).roundToInt() else 0
        return if (now == target) "ok: $name volume $before -> $now (max $max, $pct%)"
        else "error: requested $target but $name volume is $now (max $max)"
    }

    private fun setRinger(ctx: Context, c: JSONObject): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mode = when (c.optString("mode").lowercase().trim()) {
            "normal", "sound", "ring" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent", "mute" -> AudioManager.RINGER_MODE_SILENT
            else -> return "error: mode must be normal|vibrate|silent"
        }
        try { am.ringerMode = mode } catch (e: SecurityException) {
            return "error: silent mode needs dnd_access (Do Not Disturb access). Use mode=vibrate or ask the user to grant it."
        }
        return if (am.ringerMode == mode) "ok: ringer mode ${c.optString("mode")}" else "error: ringer mode unchanged (needs dnd_access)"
    }

    private fun media(ctx: Context, c: JSONObject): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cmd = c.optString("command").lowercase().trim()
        val code = when (cmd) {
            "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return "error: command must be play_pause|play|pause|next|previous|stop"
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "ok: media $cmd sent"
    }

    // ------------------------------------------------------------------ display / system settings

    private fun openWriteSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }

    private const val NEED_WRITE =
        "error: write_settings permission missing. I opened the permission page; use need_user to ask the user to enable " +
            "'Allow modifying system settings' for Arena Agent, then retry. (Or fall back to the quick_settings UI slider.)"

    private fun setBrightness(ctx: Context, c: JSONObject): String {
        if (!Settings.System.canWrite(ctx)) { openWriteSettings(ctx); return NEED_WRITE }
        val cr = ctx.contentResolver
        return try {
            if (c.has("auto")) {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, if (c.getBoolean("auto")) 1 else 0)
            }
            if (c.has("percent")) {
                val v = (c.getDouble("percent") / 100.0 * 255).roundToInt().coerceIn(1, 255)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v)
            }
            if (!c.has("auto") && !c.has("percent")) return "error: set_brightness needs percent(0-100) and/or auto(true/false)"
            val b = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1
            "ok: brightness ${(b * 100f / 255f).roundToInt()}% auto=$auto"
        } catch (e: Exception) {
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun setSystemSetting(ctx: Context, c: JSONObject): String {
        if (!Settings.System.canWrite(ctx)) { openWriteSettings(ctx); return NEED_WRITE }
        val cr = ctx.contentResolver
        return try {
            when (val key = c.optString("key").lowercase().trim()) {
                "screen_off_timeout", "screen_timeout" -> {
                    val sec = c.getInt("value").coerceIn(5, 1800)
                    Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, sec * 1000)
                    "ok: screen timeout ${sec}s"
                }
                "auto_rotate" -> {
                    val on = c.getBoolean("value")
                    Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
                    "ok: auto_rotate=$on"
                }
                "font_scale" -> {
                    val f = c.getDouble("value").toFloat().coerceIn(0.85f, 1.5f)
                    Settings.System.putFloat(cr, Settings.System.FONT_SCALE, f)
                    "ok: font_scale=$f"
                }
                else -> "error: unsupported key '$key' (screen_off_timeout[seconds]|auto_rotate[bool]|font_scale[0.85-1.5])"
            }
        } catch (e: Exception) {
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun flashlight(ctx: Context, c: JSONObject): String {
        ensureTorch(ctx)
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "error: this device has no flashlight"
            val on = if (c.has("on")) c.getBoolean("on") else !torchOn
            cm.setTorchMode(id, on)
            torchOn = on
            "ok: flashlight ${if (on) "on" else "off"}"
        } catch (e: Exception) {
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun setDnd(ctx: Context, c: JSONObject): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
            return "error: dnd_access missing. I opened the permission page; use need_user to ask the user to allow Arena Agent, then retry."
        }
        val f = when (c.optString("mode").lowercase().trim()) {
            "off", "all" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms", "alarms_only" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "none", "total", "total_silence" -> NotificationManager.INTERRUPTION_FILTER_NONE
            else -> return "error: mode must be off|priority|alarms|none"
        }
        nm.setInterruptionFilter(f)
        return "ok: do-not-disturb ${c.optString("mode")}"
    }

    // ------------------------------------------------------------------ intents

    private suspend fun start(ctx: Context, i: Intent, what: String): String = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        delay(150)
        "ok: opened $what"
    } catch (e: Exception) {
        "error: cannot open $what (${e.javaClass.simpleName}: ${e.message})"
    }

    private val SETTINGS_PAGES = mapOf(
        "wifi" to "android.settings.WIFI_SETTINGS",
        "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
        "display" to "android.settings.DISPLAY_SETTINGS",
        "sound" to "android.settings.SOUND_SETTINGS",
        "battery" to "android.settings.BATTERY_SAVER_SETTINGS",
        "apps" to "android.settings.APPLICATION_SETTINGS",
        "all_apps" to "android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS",
        "location" to "android.settings.LOCATION_SOURCE_SETTINGS",
        "accessibility" to "android.settings.ACCESSIBILITY_SETTINGS",
        "storage" to "android.settings.INTERNAL_STORAGE_SETTINGS",
        "date" to "android.settings.DATE_SETTINGS",
        "language" to "android.settings.LOCALE_SETTINGS",
        "nfc" to "android.settings.NFC_SETTINGS",
        "airplane" to "android.settings.AIRPLANE_MODE_SETTINGS",
        "wireless" to "android.settings.WIRELESS_SETTINGS",
        "data_usage" to "android.settings.DATA_USAGE_SETTINGS",
        "security" to "android.settings.SECURITY_SETTINGS",
        "privacy" to "android.settings.PRIVACY_SETTINGS",
        "developer" to "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
        "default_apps" to "android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
        "keyboard" to "android.settings.INPUT_METHOD_SETTINGS",
        "about" to "android.settings.DEVICE_INFO_SETTINGS",
        "night_light" to "android.settings.NIGHT_DISPLAY_SETTINGS",
        "dnd" to "android.settings.ZEN_MODE_SETTINGS",
        "cast" to "android.settings.CAST_SETTINGS",
        "sync" to "android.settings.SYNC_SETTINGS",
        "home" to "android.settings.HOME_SETTINGS",
        "hotspot" to "android.settings.TETHER_SETTINGS",
        "vpn" to "android.net.vpn.SETTINGS",
        "settings" to Settings.ACTION_SETTINGS,
    )

    private suspend fun openSettings(ctx: Context, c: JSONObject): String {
        val page = c.optString("page").lowercase().trim().ifBlank { "settings" }
        val pkg = c.optString("package")
        val intent = when (page) {
            "app_info", "app" -> {
                if (pkg.isBlank()) return "error: app_info needs package"
                Intent("android.settings.APPLICATION_DETAILS_SETTINGS", Uri.parse("package:$pkg"))
            }
            "app_notifications" -> {
                if (pkg.isBlank()) return "error: app_notifications needs package"
                Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE", pkg)
            }
            else -> Intent(SETTINGS_PAGES[page] ?: return "error: unknown page '$page' (${SETTINGS_PAGES.keys.joinToString("|")}|app_info|app_notifications)")
        }
        val r = start(ctx, intent, "settings/$page")
        // 일부 제조사에서 해당 화면이 없을 때 메인 설정으로 폴백
        return if (r.startsWith("error") && page != "settings") start(ctx, Intent(Settings.ACTION_SETTINGS), "settings (fallback from $page)") else r
    }

    /** Wi-Fi/모바일 데이터/NFC/볼륨은 Android 10+ 에서 API 로 켜고 끌 수 없다 → 스위치가 있는 하단 패널을 바로 연다. */
    private suspend fun openPanel(ctx: Context, c: JSONObject): String {
        if (Build.VERSION.SDK_INT < 29) return openSettings(ctx, JSONObject().put("page", "wifi"))
        val action = when (c.optString("panel").lowercase().trim()) {
            "internet", "wifi", "data", "mobile_data" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            "volume" -> Settings.Panel.ACTION_VOLUME
            "nfc" -> Settings.Panel.ACTION_NFC
            else -> return "error: panel must be internet|volume|nfc"
        }
        val r = start(ctx, Intent(action), "panel ${c.optString("panel")}")
        return if (r.startsWith("ok")) "$r (switches are on screen: use tap_text/tap on the toggle)" else r
    }

    private suspend fun sms(ctx: Context, c: JSONObject): String =
        start(
            ctx,
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.optString("number"))))
                .putExtra("sms_body", c.optString("body")),
            "SMS composer (NOT sent; sending requires user confirmation)",
        )

    private suspend fun email(ctx: Context, c: JSONObject): String =
        start(
            ctx,
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(c.optString("to"), "@,")))
                .putExtra(Intent.EXTRA_SUBJECT, c.optString("subject"))
                .putExtra(Intent.EXTRA_TEXT, c.optString("body")),
            "email composer (NOT sent; sending requires user confirmation)",
        )

    private suspend fun maps(ctx: Context, c: JSONObject): String {
        val q = c.optString("query")
        val dest = c.optString("destination")
        val uri = when {
            dest.isNotBlank() -> Uri.parse("google.navigation:q=" + Uri.encode(dest))
            c.has("lat") && c.has("lng") -> Uri.parse("geo:${c.getDouble("lat")},${c.getDouble("lng")}?q=" + Uri.encode(q.ifBlank { "${c.getDouble("lat")},${c.getDouble("lng")}" }))
            q.isNotBlank() -> Uri.parse("geo:0,0?q=" + Uri.encode(q))
            else -> return "error: maps needs query, destination or lat/lng"
        }
        return start(ctx, Intent(Intent.ACTION_VIEW, uri), "maps")
    }

    private fun parseMillis(s: String): Long? = try {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (_: Exception) {
        try {
            java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) { null }
    }

    private suspend fun calendarEvent(ctx: Context, c: JSONObject): String {
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, c.optString("title"))
            .putExtra(CalendarContract.Events.EVENT_LOCATION, c.optString("location"))
            .putExtra(CalendarContract.Events.DESCRIPTION, c.optString("description"))
        c.optString("begin").takeIf { it.isNotBlank() }?.let { s ->
            val ms = parseMillis(s) ?: return "error: begin must be ISO-8601 like 2026-11-09T15:00"
            i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ms)
            val end = c.optString("end").takeIf { it.isNotBlank() }?.let { parseMillis(it) } ?: (ms + 60 * 60 * 1000)
            i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        }
        return start(ctx, i, "calendar event form (user must press Save)")
    }

    private suspend fun camera(ctx: Context, c: JSONObject): String {
        val video = c.optString("mode").lowercase() == "video"
        return start(ctx, Intent(if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "camera")
    }

    private suspend fun playMusic(ctx: Context, c: JSONObject): String {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, c.optString("query"))
        c.optString("package").takeIf { it.isNotBlank() }?.let { i.setPackage(it) }
        return start(ctx, i, "music search")
    }

    private suspend fun shareText(ctx: Context, c: JSONObject): String {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c.optString("text"))
        return start(ctx, Intent.createChooser(send, null), "share sheet")
    }

    private fun setAlarm(ctx: Context, c: JSONObject): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, c.getInt("hour").coerceIn(0, 23))
            .putExtra(AlarmClock.EXTRA_MINUTES, c.optInt("minute", 0).coerceIn(0, 59))
            .putExtra(AlarmClock.EXTRA_MESSAGE, c.optString("label"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, c.optBoolean("skip_ui", true))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val days = c.optJSONArray("days")
        if (days != null && days.length() > 0) {
            val map = mapOf(
                "sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY,
                "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY,
            )
            val list = ArrayList<Int>()
            for (k in 0 until days.length()) map[days.optString(k).lowercase().take(3)]?.let { list.add(it) }
            if (list.isNotEmpty()) i.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, list)
        }
        return try {
            ctx.startActivity(i)
            "ok: alarm set ${"%02d:%02d".format(c.getInt("hour"), c.optInt("minute", 0))}"
        } catch (e: Exception) {
            "error: cannot set alarm (${e.javaClass.simpleName}: ${e.message})"
        }
    }

    private fun setTimer(ctx: Context, c: JSONObject): String {
        val sec = c.getInt("seconds").coerceIn(1, 86_400)
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, sec)
            .putExtra(AlarmClock.EXTRA_MESSAGE, c.optString("label"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, c.optBoolean("skip_ui", true))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            "ok: timer started ${sec}s"
        } catch (e: Exception) {
            "error: cannot set timer (${e.javaClass.simpleName}: ${e.message})"
        }
    }

    private fun setClipboard(ctx: Context, c: JSONObject): String {
        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("agent", c.optString("text")))
        return "ok: clipboard set (${c.optString("text").length} chars)"
    }

    private fun listApps(ctx: Context, c: JSONObject): String {
        val q = c.optString("query").trim()
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hits = pm.queryIntentActivities(main, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .filter { q.isEmpty() || it.first.contains(q, true) || it.second.contains(q, true) }
            .sortedBy { it.first.lowercase() }
            .take(40)
        return if (hits.isEmpty()) "ok: no installed app matches '$q'" else "ok:\n" + hits.joinToString("\n") { "${it.first} | ${it.second}" }
    }

    /** 앱 API / 딥링크 등 임의 인텐트. extras 는 JSON 객체. */
    private suspend fun genericIntent(ctx: Context, c: JSONObject): String {
        val i = Intent()
        c.optString("intent_action").ifBlank { c.optString("name") }.takeIf { it.isNotBlank() }?.let { i.action = it }
        val data = c.optString("data")
        val type = c.optString("type")
        when {
            data.isNotBlank() && type.isNotBlank() -> i.setDataAndType(Uri.parse(data), type)
            data.isNotBlank() -> i.data = Uri.parse(data)
            type.isNotBlank() -> i.type = type
        }
        val pkg = c.optString("package")
        val cls = c.optString("class")
        if (pkg.isNotBlank() && cls.isNotBlank()) i.component = ComponentName(pkg, cls) else if (pkg.isNotBlank()) i.setPackage(pkg)
        c.optString("category").takeIf { it.isNotBlank() }?.let { i.addCategory(it) }
        c.optJSONObject("extras")?.let { ex ->
            val keys = ex.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = ex.get(k)) {
                    is Boolean -> i.putExtra(k, v)
                    is Int -> i.putExtra(k, v)
                    is Long -> i.putExtra(k, v)
                    is Double -> i.putExtra(k, v)
                    is JSONArray -> i.putExtra(k, v.toString())
                    else -> i.putExtra(k, v.toString())
                }
            }
        }
        if (i.action == null && i.component == null && i.`package` == null) return "error: intent needs intent_action, package or class"
        return start(ctx, i, "intent ${i.action ?: i.`package`}")
    }
}

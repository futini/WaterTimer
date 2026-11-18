package com.example.reminder

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MIN_SEC = 1
private const val MAX_SEC = 24 * 3600
private const val DEFAULT_SEC = 30 * 60
private const val DEFAULT_TEXT = "Выпить стакан воды"

private data class PendingStart(
    val text: String,
    val intervalMs: Long
)

// =====================================================================================
//  Activity: логика запуска, разрешений и диалогов осталась прежней.
//  Изменилось только то, как состояние показывается на экране (Compose вместо XML).
// =====================================================================================

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var pending: PendingStart? = null

    // Состояние экрана: Compose перерисовывается при их изменении.
    private var running by mutableStateOf(false)
    private var activeIntervalMs by mutableLongStateOf(0L)
    private var nextTriggerMs by mutableLongStateOf(0L)
    private var nowMs by mutableLongStateOf(System.currentTimeMillis())
    private var batteryButtonVisible by mutableStateOf(false)
    private var intervalSec by mutableIntStateOf(DEFAULT_SEC)
    private var reminderText by mutableStateOf("")
    private var textError by mutableStateOf(false)
    private var themeId by mutableStateOf(ThemeId.WATER)

    /** Раз в секунду обновляет обратный отсчёт до следующего напоминания. */
    private val ticker = object : Runnable {
        override fun run() {
            renderCountdown()
            handler.postDelayed(this, 1000L)
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) ensurePermissionsAndStart() else showNotificationsDisabledDialog()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        NotificationHelper.ensureChannel(this)
        restoreInputs()
        themeId = ThemeStore.load(this)

        setContent {
            AppTheme(themeId) {
                MainScreen(
                    totalSec = intervalSec,
                    onTotalChange = { intervalSec = it.coerceIn(MIN_SEC, MAX_SEC) },
                    reminderText = reminderText,
                    onTextChange = {
                        reminderText = it
                        textError = false
                    },
                    textError = textError,
                    running = running,
                    activeIntervalMs = activeIntervalMs,
                    remainingMs = (nextTriggerMs - nowMs).coerceAtLeast(0L),
                    batteryVisible = batteryButtonVisible,
                    onBattery = { requestIgnoreBatteryOptimizations() },
                    onToggle = { if (running) onStopClicked() else onStartClicked() },
                    themeId = themeId,
                    onThemeChange = {
                        themeId = it
                        ThemeStore.save(this@MainActivity, it)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderState()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------------------------------------------------------------- старт / стоп

    private fun onStartClicked() {
        val text = reminderText.trim()
        textError = text.isEmpty()
        if (textError) return

        pending = PendingStart(text, intervalSec * 1000L)
        ensurePermissionsAndStart()
    }

    /** Проверяет разрешения по очереди; когда всё в порядке — запускает напоминания. */
    private fun ensurePermissionsAndStart() {
        if (pending == null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            showNotificationsDisabledDialog()
            return
        }
        if (!ReminderScheduler.canScheduleExact(this)) {
            showExactAlarmDialog()
            return
        }
        startReminders()
    }

    private fun startReminders() {
        val p = pending ?: return
        pending = null

        ReminderScheduler.start(this, p.text, p.intervalMs)

        hideKeyboard()
        renderState()
        Toast.makeText(
            this,
            getString(R.string.toast_started, formatInterval(p.intervalMs)),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun onStopClicked() {
        ReminderScheduler.stop(this)
        renderState()
        Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- состояние экрана

    private fun restoreInputs() {
        reminderText = Prefs.text(this).ifBlank { DEFAULT_TEXT }
        val savedSec = Prefs.intervalMs(this) / 1000L
        intervalSec =
            if (savedSec >= MIN_SEC) savedSec.coerceAtMost(MAX_SEC.toLong()).toInt()
            else DEFAULT_SEC
    }

    private fun renderState() {
        running = Prefs.isRunning(this)
        activeIntervalMs = Prefs.intervalMs(this)

        val pm = getSystemService(PowerManager::class.java)
        batteryButtonVisible = !pm.isIgnoringBatteryOptimizations(packageName)

        renderCountdown()
    }

    private fun renderCountdown() {
        nextTriggerMs = Prefs.nextTrigger(this)
        nowMs = System.currentTimeMillis()
    }

    private fun hideKeyboard() {
        currentFocus?.let {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
    }

    // ---------------------------------------------------------------- диалоги и настройки

    private fun showNotificationsDisabledDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_notif_title)
            .setMessage(R.string.dlg_notif_message)
            .setPositiveButton(R.string.dlg_open_settings) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }

    private fun showExactAlarmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_alarm_title)
            .setMessage(R.string.dlg_alarm_message)
            .setPositiveButton(R.string.dlg_open_settings) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this, R.string.toast_no_settings, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.dlg_start_anyway) { _, _ -> startReminders() }
            .setNeutralButton(R.string.dlg_cancel, null)
            .show()
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, R.string.toast_no_settings, Toast.LENGTH_LONG).show()
            }
        }
    }
}

// =====================================================================================
//  Форматирование времени
// =====================================================================================

private fun formatInterval(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    val parts = buildList {
        if (h > 0) add("$h ч")
        if (m > 0) add("$m мин")
        if (s > 0 || isEmpty()) add("$s с")
    }
    return parts.joinToString(" ")
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    else String.format(Locale.getDefault(), "%02d:%02d", m, s)
}

private fun two(v: Int): String = v.toString().padStart(2, '0')

// =====================================================================================
//  Темы оформления: 5 палитр, у каждой светлый и тёмный вариант
// =====================================================================================

private enum class ThemeId(val title: String) {
    WATER("Водная"),
    MONO("Монохром"),
    EMERALD("Изумрудная"),
    AMBER("Янтарная"),
    LAVENDER("Лавандовая");

    companion object {
        fun fromName(name: String?): ThemeId = values().firstOrNull { it.name == name } ?: WATER
    }
}

/** Выбранная тема хранится в SharedPreferences и переживает перезапуск приложения. */
private object ThemeStore {
    private const val FILE = "ui_prefs"
    private const val KEY_THEME = "color_theme"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context): ThemeId = ThemeId.fromName(sp(ctx).getString(KEY_THEME, null))

    fun save(ctx: Context, id: ThemeId) {
        sp(ctx).edit().putString(KEY_THEME, id.name).apply()
    }
}

@Immutable
private class AppPalette(
    val dark: Boolean,
    val bgTop: Color,
    val bgBottom: Color,
    val card: Color,
    val cardBorder: Color,
    val faceCenter: Color,
    val faceEdge: Color,
    val tick: Color,
    val tickStrong: Color,
    val hour: Color,
    val minute: Color,
    val second: Color,
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val error: Color,
    val stop: Color,
    val startFrom: Color,
    val startTo: Color,
)

// ---- Водная ----------------------------------------------------------------------

private val WaterLight = AppPalette(
    dark = false,
    bgTop = Color(0xFFDDF1FA), bgBottom = Color(0xFFF4FAFD),
    card = Color(0xFFFFFFFF), cardBorder = Color(0xFFCFE3EE),
    faceCenter = Color(0xFFFFFFFF), faceEdge = Color(0xFFD9EEF8),
    tick = Color(0xFF5F8CA8), tickStrong = Color(0xFF0B3F73),
    hour = Color(0xFF0B4F94), minute = Color(0xFF12B3A0), second = Color(0xFF00B8D9),
    primary = Color(0xFF0B5CA8), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFF00828F),
    onSurface = Color(0xFF0C1C28), onSurfaceVariant = Color(0xFF3D4A54),
    outline = Color(0xFF6C7A85), outlineVariant = Color(0xFFBFCDD6),
    error = Color(0xFFC0443C), stop = Color(0xFFC84A41),
    startFrom = Color(0xFF0B5CA8), startTo = Color(0xFF0090B0),
)

private val WaterDark = AppPalette(
    dark = true,
    bgTop = Color(0xFF08233A), bgBottom = Color(0xFF050F18),
    card = Color(0xFF0F2536), cardBorder = Color(0xFF1E3A4E),
    faceCenter = Color(0xFF123349), faceEdge = Color(0xFF0B2234),
    tick = Color(0xFF6FA0BC), tickStrong = Color(0xFFBFE3F8),
    hour = Color(0xFF6DB6FF), minute = Color(0xFF3FE0C5), second = Color(0xFF67E8F9),
    primary = Color(0xFF8CC8FF), onPrimary = Color(0xFF00315A), secondary = Color(0xFF52D7E8),
    onSurface = Color(0xFFDDEAF2), onSurfaceVariant = Color(0xFFB4C4CF),
    outline = Color(0xFF7D8E9A), outlineVariant = Color(0xFF2E434F),
    error = Color(0xFFFF8A80), stop = Color(0xFFC84A41),
    startFrom = Color(0xFF1767B8), startTo = Color(0xFF0A8FB0),
)

// ---- Строгий монохром ------------------------------------------------------------

private val MonoLight = AppPalette(
    dark = false,
    bgTop = Color(0xFFEDEDED), bgBottom = Color(0xFFF8F8F8),
    card = Color(0xFFFFFFFF), cardBorder = Color(0xFFDADADA),
    faceCenter = Color(0xFFFFFFFF), faceEdge = Color(0xFFF1F1F1),
    tick = Color(0xFF8A8A8A), tickStrong = Color(0xFF111111),
    hour = Color(0xFF111111), minute = Color(0xFF474747), second = Color(0xFF858585),
    primary = Color(0xFF1C1C1C), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFF555555),
    onSurface = Color(0xFF111111), onSurfaceVariant = Color(0xFF4A4A4A),
    outline = Color(0xFF7A7A7A), outlineVariant = Color(0xFFCFCFCF),
    error = Color(0xFFB3261E), stop = Color(0xFFB23B32),
    startFrom = Color(0xFF1C1C1C), startTo = Color(0xFF3A3A3A),
)

private val MonoDark = AppPalette(
    dark = true,
    bgTop = Color(0xFF1A1A1A), bgBottom = Color(0xFF0B0B0B),
    card = Color(0xFF171717), cardBorder = Color(0xFF2C2C2C),
    faceCenter = Color(0xFF1F1F1F), faceEdge = Color(0xFF131313),
    tick = Color(0xFF8C8C8C), tickStrong = Color(0xFFF2F2F2),
    hour = Color(0xFFF2F2F2), minute = Color(0xFFBDBDBD), second = Color(0xFF8E8E8E),
    primary = Color(0xFFEDEDED), onPrimary = Color(0xFF111111), secondary = Color(0xFFB0B0B0),
    onSurface = Color(0xFFEDEDED), onSurfaceVariant = Color(0xFFB5B5B5),
    outline = Color(0xFF8A8A8A), outlineVariant = Color(0xFF3A3A3A),
    error = Color(0xFFFF8A80), stop = Color(0xFFC0463C),
    startFrom = Color(0xFF4A4A4A), startTo = Color(0xFF6A6A6A),
)

// ---- Изумрудная ------------------------------------------------------------------

private val EmeraldLight = AppPalette(
    dark = false,
    bgTop = Color(0xFFD9F2E6), bgBottom = Color(0xFFF3FBF7),
    card = Color(0xFFFFFFFF), cardBorder = Color(0xFFC6E5D4),
    faceCenter = Color(0xFFFFFFFF), faceEdge = Color(0xFFDDF3E8),
    tick = Color(0xFF5D8F76), tickStrong = Color(0xFF0B3D2A),
    hour = Color(0xFF0A4D36), minute = Color(0xFF12805C), second = Color(0xFF10B081),
    primary = Color(0xFF0A6B4B), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFF1F7A5C),
    onSurface = Color(0xFF0E2019), onSurfaceVariant = Color(0xFF3F544B),
    outline = Color(0xFF6E8479), outlineVariant = Color(0xFFC2D6CB),
    error = Color(0xFFC0443C), stop = Color(0xFFC84A41),
    startFrom = Color(0xFF0A5A41), startTo = Color(0xFF12936B),
)

private val EmeraldDark = AppPalette(
    dark = true,
    bgTop = Color(0xFF0A2A20), bgBottom = Color(0xFF04120D),
    card = Color(0xFF0F2A21), cardBorder = Color(0xFF1E4535),
    faceCenter = Color(0xFF123629), faceEdge = Color(0xFF0B2219),
    tick = Color(0xFF6FA790), tickStrong = Color(0xFFD3F5E5),
    hour = Color(0xFFA7EBD0), minute = Color(0xFF34B98A), second = Color(0xFF5BE0AE),
    primary = Color(0xFF7FE0B8), onPrimary = Color(0xFF00382A), secondary = Color(0xFF6FD3AB),
    onSurface = Color(0xFFDDEFE6), onSurfaceVariant = Color(0xFFB3C9BE),
    outline = Color(0xFF7E968A), outlineVariant = Color(0xFF2C473B),
    error = Color(0xFFFF8A80), stop = Color(0xFFC84A41),
    startFrom = Color(0xFF0F6B4C), startTo = Color(0xFF0E8A67),
)

// ---- Янтарная --------------------------------------------------------------------

private val AmberLight = AppPalette(
    dark = false,
    bgTop = Color(0xFFFCEBD2), bgBottom = Color(0xFFFFF8EE),
    card = Color(0xFFFFFFFF), cardBorder = Color(0xFFF0DCBC),
    faceCenter = Color(0xFFFFFFFF), faceEdge = Color(0xFFFBEBD3),
    tick = Color(0xFFA07C45), tickStrong = Color(0xFF3A2400),
    hour = Color(0xFF7C3F00), minute = Color(0xFFD97706), second = Color(0xFFC2410C),
    primary = Color(0xFF9A5B00), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFFB45309),
    onSurface = Color(0xFF2A1B05), onSurfaceVariant = Color(0xFF5B4A30),
    outline = Color(0xFF8C7650), outlineVariant = Color(0xFFE2D2B5),
    error = Color(0xFFB3261E), stop = Color(0xFFC84A41),
    startFrom = Color(0xFFA8480A), startTo = Color(0xFFC96A00),
)

private val AmberDark = AppPalette(
    dark = true,
    bgTop = Color(0xFF2B1A06), bgBottom = Color(0xFF120B03),
    card = Color(0xFF22160A), cardBorder = Color(0xFF4A3414),
    faceCenter = Color(0xFF2E1F0C), faceEdge = Color(0xFF1D1307),
    tick = Color(0xFFB08A4E), tickStrong = Color(0xFFFFE8BF),
    hour = Color(0xFFFFD08A), minute = Color(0xFFFFB020), second = Color(0xFFFF8A3D),
    primary = Color(0xFFFFC266), onPrimary = Color(0xFF3A2400), secondary = Color(0xFFFFB86B),
    onSurface = Color(0xFFF6E9D2), onSurfaceVariant = Color(0xFFCDBA98),
    outline = Color(0xFF9A8660), outlineVariant = Color(0xFF4A3A1E),
    error = Color(0xFFFF8A80), stop = Color(0xFFC84A41),
    startFrom = Color(0xFFA8480A), startTo = Color(0xFFC96A00),
)

// ---- Лавандовая ------------------------------------------------------------------

private val LavenderLight = AppPalette(
    dark = false,
    bgTop = Color(0xFFE8E4FA), bgBottom = Color(0xFFF7F5FE),
    card = Color(0xFFFFFFFF), cardBorder = Color(0xFFDAD3F2),
    faceCenter = Color(0xFFFFFFFF), faceEdge = Color(0xFFECE8FB),
    tick = Color(0xFF7C74A8), tickStrong = Color(0xFF2B2160),
    hour = Color(0xFF2F2780), minute = Color(0xFF5B5BD6), second = Color(0xFF9A5CE6),
    primary = Color(0xFF4C3FB5), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFF6B4FC0),
    onSurface = Color(0xFF1A1633), onSurfaceVariant = Color(0xFF4C4870),
    outline = Color(0xFF7D7AA0), outlineVariant = Color(0xFFD3CFEA),
    error = Color(0xFFC0443C), stop = Color(0xFFC84A41),
    startFrom = Color(0xFF3B2F8F), startTo = Color(0xFF7458D6),
)

private val LavenderDark = AppPalette(
    dark = true,
    bgTop = Color(0xFF1E1740), bgBottom = Color(0xFF0D0A1F),
    card = Color(0xFF17122E), cardBorder = Color(0xFF2F2860),
    faceCenter = Color(0xFF1F1844), faceEdge = Color(0xFF130F2C),
    tick = Color(0xFF8F88C8), tickStrong = Color(0xFFE6E1FF),
    hour = Color(0xFFC7BFFF), minute = Color(0xFF8F8CF5), second = Color(0xFFC58BFF),
    primary = Color(0xFFB8AEFF), onPrimary = Color(0xFF241A66), secondary = Color(0xFFC1A9FF),
    onSurface = Color(0xFFE8E4FA), onSurfaceVariant = Color(0xFFBDB8DD),
    outline = Color(0xFF8A86AE), outlineVariant = Color(0xFF36305F),
    error = Color(0xFFFF8A80), stop = Color(0xFFC84A41),
    startFrom = Color(0xFF3B2F8F), startTo = Color(0xFF6A52D0),
)

private fun paletteFor(id: ThemeId, dark: Boolean): AppPalette = when (id) {
    ThemeId.WATER -> if (dark) WaterDark else WaterLight
    ThemeId.MONO -> if (dark) MonoDark else MonoLight
    ThemeId.EMERALD -> if (dark) EmeraldDark else EmeraldLight
    ThemeId.AMBER -> if (dark) AmberDark else AmberLight
    ThemeId.LAVENDER -> if (dark) LavenderDark else LavenderLight
}

/** Каждый цвет темы плавно перетекает в новый через animateColorAsState. */
@Composable
private fun animColor(target: Color): Color =
    animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "themeColor",
    ).value

@Composable
private fun animatedPalette(t: AppPalette): AppPalette = AppPalette(
    dark = t.dark,
    bgTop = animColor(t.bgTop),
    bgBottom = animColor(t.bgBottom),
    card = animColor(t.card),
    cardBorder = animColor(t.cardBorder),
    faceCenter = animColor(t.faceCenter),
    faceEdge = animColor(t.faceEdge),
    tick = animColor(t.tick),
    tickStrong = animColor(t.tickStrong),
    hour = animColor(t.hour),
    minute = animColor(t.minute),
    second = animColor(t.second),
    primary = animColor(t.primary),
    onPrimary = animColor(t.onPrimary),
    secondary = animColor(t.secondary),
    onSurface = animColor(t.onSurface),
    onSurfaceVariant = animColor(t.onSurfaceVariant),
    outline = animColor(t.outline),
    outlineVariant = animColor(t.outlineVariant),
    error = animColor(t.error),
    stop = animColor(t.stop),
    startFrom = animColor(t.startFrom),
    startTo = animColor(t.startTo),
)

private val LocalPalette = compositionLocalOf { WaterLight }

private fun buildScheme(p: AppPalette): ColorScheme {
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.primary,
        onPrimary = p.onPrimary,
        secondary = p.secondary,
        onSecondary = p.onPrimary,
        background = p.bgBottom,
        onBackground = p.onSurface,
        surface = p.card,
        onSurface = p.onSurface,
        surfaceVariant = p.card,
        onSurfaceVariant = p.onSurfaceVariant,
        outline = p.outline,
        outlineVariant = p.outlineVariant,
        error = p.error,
        onError = Color.White,
    )
}

@Composable
private fun AppTheme(themeId: ThemeId, content: @Composable () -> Unit) {
    val palette = animatedPalette(paletteFor(themeId, isSystemInDarkTheme()))
    MaterialTheme(colorScheme = buildScheme(palette)) {
        CompositionLocalProvider(
            LocalPalette provides palette,
            LocalContentColor provides palette.onSurface,
            content = content
        )
    }
}

private fun Modifier.appCard(pal: AppPalette, radius: Dp = 28.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(pal.card)
        .border(1.dp, pal.cardBorder, shape)
}

// =====================================================================================
//  Выбор темы: кнопка-палитра в углу раскрывает ряд цветных кружков
// =====================================================================================

@Composable
private fun ThemePickerBar(
    current: ThemeId,
    onSelect: (ThemeId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pal = LocalPalette.current
    val dark = isSystemInDarkTheme()
    var open by remember { mutableStateOf(false) }

    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(200)) + expandHorizontally(
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                expandFrom = Alignment.End,
            ),
            exit = fadeOut(tween(150)) + shrinkHorizontally(
                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.End,
            ),
        ) {
            Row(
                Modifier
                    .padding(end = 8.dp)
                    .appCard(pal, 22.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThemeId.values().forEach { id ->
                    ThemeDot(
                        id = id,
                        dark = dark,
                        isSelected = id == current,
                        onClick = { onSelect(id) },
                    )
                }
            }
        }
        PaletteButton(open = open, onClick = { open = !open })
    }
}

@Composable
private fun ThemeDot(id: ThemeId, dark: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    val ink = LocalPalette.current.onSurface
    val sample = remember(id, dark) { paletteFor(id, dark) }
    val dotScale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.8f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "dotScale",
    )
    val ring by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(250),
        label = "dotRing",
    )

    Canvas(
        Modifier
            .size(32.dp)
            .graphicsLayer {
                scaleX = dotScale
                scaleY = dotScale
            }
            .clip(CircleShape)
            .semantics {
                contentDescription = "Тема: ${id.title}"
                selected = isSelected
            }
            .clickable(role = Role.RadioButton, onClick = onClick)
    ) {
        val c = center
        val r = minOf(size.width, size.height) / 2f
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(sample.startFrom, sample.startTo),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            ),
            radius = r - 4.dp.toPx(),
            center = c,
        )
        if (ring > 0.01f) {
            drawCircle(
                color = ink.copy(alpha = ring.coerceIn(0f, 1f)),
                radius = r - 1.dp.toPx(),
                center = c,
                style = Stroke(2.dp.toPx()),
            )
        }
    }
}

/** Компактная кнопка с иконкой палитры: в кружках — цвета стрелок текущей темы. */
@Composable
private fun PaletteButton(open: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "paletteScale",
    )
    val turn by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "paletteTurn",
    )

    Box(
        Modifier
            .size(40.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .appCard(pal, 20.dp)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClickLabel = "Цветовая тема",
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val c = center
            val r = minOf(size.width, size.height) / 2f
            rotate(degrees = turn) {
                drawCircle(
                    color = pal.onSurfaceVariant,
                    radius = r - 1.dp.toPx(),
                    center = c,
                    style = Stroke(1.8.dp.toPx()),
                )
                val dots = listOf(pal.hour, pal.minute, pal.second)
                val angles = floatArrayOf(300f, 60f, 180f)
                for (i in 0..2) {
                    drawCircle(
                        color = dots[i],
                        radius = 2.6.dp.toPx(),
                        center = polar(c, r * 0.5f, angles[i]),
                    )
                }
            }
        }
    }
}

// =====================================================================================
//  Главный экран: циферблат → цифровой ввод → подсказки/статус/кнопка
// =====================================================================================

private val TIPS = listOf(
    "Потяните стрелку или введите точное время",
    "Коснитесь циферблата — ближайшая стрелка сама перейдёт в нужное место",
    "Удерживайте + или −, чтобы менять время быстрее",
    "Короткая стрелка — часы, средняя — минуты, тонкая — секунды",
)

@Composable
private fun MainScreen(
    totalSec: Int,
    onTotalChange: (Int) -> Unit,
    reminderText: String,
    onTextChange: (String) -> Unit,
    textError: Boolean,
    running: Boolean,
    activeIntervalMs: Long,
    remainingMs: Long,
    batteryVisible: Boolean,
    onBattery: () -> Unit,
    onToggle: () -> Unit,
    themeId: ThemeId,
    onThemeChange: (ThemeId) -> Unit,
) {
    val pal = LocalPalette.current
    val focusManager = LocalFocusManager.current

    // Одно состояние запускает каскад: циферблат (0 мс) → цифры (150 мс) → низ (300 мс).
    val enter = remember { MutableTransitionState(false).apply { targetState = true } }

    val ringFraction = animateFloatAsState(
        targetValue = if (running && activeIntervalMs > 0L)
            (remainingMs.toFloat() / activeIntervalMs.toFloat()).coerceIn(0f, 1f)
        else 0f,
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "ringFraction",
    )
    val ringAlpha = animateFloatAsState(
        targetValue = if (running) 1f else 0f,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "ringAlpha",
    )

    val h = totalSec / 3600
    val m = totalSec % 3600 / 60
    val s = totalSec % 60
    fun commit(v: Int) = onTotalChange(v.coerceIn(MIN_SEC, MAX_SEC))
    val setHours: (Int) -> Unit = { commit(it * 3600 + m * 60 + s) }
    val setMinutes: (Int) -> Unit = { commit(h * 3600 + it * 60 + s) }
    val setSeconds: (Int) -> Unit = { commit(h * 3600 + m * 60 + it) }
    val step: (Int) -> Unit = { commit(totalSec + it) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(pal.bgTop, pal.bgBottom)))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            }
    ) {
        val dialSize = minOf(maxWidth - 40.dp, 320.dp, maxOf(maxHeight * 0.36f, 220.dp))

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 0. Кнопка выбора цветовой темы (появляется вместе с циферблатом)
            AnimatedVisibility(
                visibleState = enter,
                enter = fadeIn(
                    animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing)
                ),
            ) {
                ThemePickerBar(current = themeId, onSelect = onThemeChange)
            }

            // 1. Циферблат: опускается сверху
            AnimatedVisibility(
                visibleState = enter,
                enter = slideInVertically(
                    animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing)
                ) { -it / 2 } + fadeIn(
                    animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing)
                ),
            ) {
                AnalogDial(
                    totalSec = totalSec,
                    enabled = !running,
                    ringFraction = ringFraction,
                    ringAlpha = ringAlpha,
                    onSetHours = setHours,
                    onSetMinutes = setMinutes,
                    onSetSeconds = setSeconds,
                    modifier = Modifier.size(dialSize),
                )
            }

            // 2. Цифровой ввод: вылетает следом, через 150 мс
            AnimatedVisibility(
                visibleState = enter,
                enter = slideInVertically(
                    animationSpec = tween(
                        durationMillis = 700, delayMillis = 150, easing = FastOutSlowInEasing
                    )
                ) { it / 2 } + fadeIn(
                    animationSpec = tween(
                        durationMillis = 700, delayMillis = 150, easing = FastOutSlowInEasing
                    )
                ),
            ) {
                DigitalPicker(
                    totalSec = totalSec,
                    enabled = !running,
                    onSetH = setHours,
                    onSetM = setMinutes,
                    onSetS = setSeconds,
                    onStep = step,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 3. Подсказки, текст и кнопка: вылетают снизу последними, через 300 мс
            AnimatedVisibility(
                visibleState = enter,
                enter = slideInVertically(
                    animationSpec = tween(
                        durationMillis = 700, delayMillis = 300, easing = FastOutSlowInEasing
                    )
                ) { it } + fadeIn(
                    animationSpec = tween(
                        durationMillis = 700, delayMillis = 300, easing = FastOutSlowInEasing
                    )
                ),
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusTipsCard(
                        running = running,
                        totalSec = totalSec,
                        activeIntervalMs = activeIntervalMs,
                        remainingMs = remainingMs,
                        batteryVisible = batteryVisible,
                        onBattery = onBattery,
                    )
                    ReminderTextField(
                        value = reminderText,
                        onValueChange = onTextChange,
                        isError = textError,
                        enabled = !running,
                    )
                    StartStopButton(running = running, onClick = onToggle)
                }
            }
        }
    }
}

// =====================================================================================
//  Аналоговый циферблат: три стрелки (часы 0–24, минуты, секунды)
// =====================================================================================

private const val HOUR_LEN = 0.46f
private const val MIN_LEN = 0.70f
private const val SEC_LEN = 0.86f

private fun polar(c: Offset, radius: Float, deg: Float): Offset {
    val a = Math.toRadians(deg.toDouble())
    return Offset(c.x + radius * sin(a).toFloat(), c.y - radius * cos(a).toFloat())
}

/** Угол касания: 0° — «12 часов», по часовой стрелке. */
private fun touchAngleDeg(p: Offset, c: Offset): Float {
    val a = Math.toDegrees(atan2((p.x - c.x).toDouble(), (c.y - p.y).toDouble())).toFloat()
    return if (a < 0f) a + 360f else a
}

/** Часы — ограниченный слайдер 0…24 (через «12 часов» не перескакивает с 24 на 0). */
private fun hourFromAngle(angle: Float, previous: Int): Int {
    val raw = angle / 15f
    val best = listOf(raw, raw + 24f, raw - 24f).minBy { abs(it - previous) }
    return best.roundToInt().coerceIn(0, 24)
}

/** Кратчайший путь по кругу: стрелка не делает «обратный оборот» при переходе 59 → 0. */
private fun unwrapAngle(previousTarget: Float, valueAngle: Float): Float {
    var delta = (valueAngle - previousTarget) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return previousTarget + delta
}

@Composable
private fun rememberHandAngle(valueAngle: Float, wrap: Boolean): State<Float> {
    val anim = remember { Animatable(valueAngle) }
    LaunchedEffect(valueAngle) {
        val target = if (wrap) unwrapAngle(anim.targetValue, valueAngle) else valueAngle
        anim.animateTo(
            target,
            spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium)
        )
    }
    return anim.asState()
}

@Composable
private fun AnalogDial(
    totalSec: Int,
    enabled: Boolean,
    ringFraction: State<Float>,
    ringAlpha: State<Float>,
    onSetHours: (Int) -> Unit,
    onSetMinutes: (Int) -> Unit,
    onSetSeconds: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pal = LocalPalette.current
    val haptic = LocalHapticFeedback.current

    val h = totalSec / 3600
    val m = totalSec % 3600 / 60
    val s = totalSec % 60

    val hourAngle = rememberHandAngle(h * 15f, wrap = false)
    val minuteAngle = rememberHandAngle(m * 6f, wrap = true)
    val secondAngle = rememberHandAngle(s * 6f, wrap = true)

    // Какая стрелка сейчас перетаскивается (-1 — никакая) и степень её подсветки.
    var active by remember { mutableIntStateOf(-1) }
    val glowSpec = spring<Float>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)
    val glow0 = animateFloatAsState(if (active == 0) 1f else 0f, glowSpec, label = "glow0")
    val glow1 = animateFloatAsState(if (active == 1) 1f else 0f, glowSpec, label = "glow1")
    val glow2 = animateFloatAsState(if (active == 2) 1f else 0f, glowSpec, label = "glow2")
    val handsAlpha = animateFloatAsState(
        if (enabled) 1f else 0.55f, tween(300), label = "handsAlpha"
    )

    // Актуальные значения для жестов (pointerInput живёт дольше одной композиции).
    val latestH = rememberUpdatedState(h)
    val latestM = rememberUpdatedState(m)
    val latestS = rememberUpdatedState(s)
    val setH = rememberUpdatedState(onSetHours)
    val setM = rememberUpdatedState(onSetMinutes)
    val setS = rememberUpdatedState(onSetSeconds)

    val description = "Циферблат выбора интервала: ${formatInterval(totalSec * 1000L)}"

    Canvas(
        modifier
            .shadow(elevation = if (pal.dark) 0.dp else 10.dp, shape = CircleShape)
            .semantics { contentDescription = description }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = minOf(size.width, size.height) / 2f

                    // Берём ближайшую к пальцу стрелку (по кончику).
                    val tips = listOf(
                        polar(c, r * HOUR_LEN, latestH.value * 15f),
                        polar(c, r * MIN_LEN, latestM.value * 6f),
                        polar(c, r * SEC_LEN, latestS.value * 6f),
                    )
                    val hand = tips.indices.minBy { (tips[it] - down.position).getDistance() }
                    active = hand

                    var lastKey = -1
                    fun update(pos: Offset) {
                        val a = touchAngleDeg(pos, c)
                        val key = when (hand) {
                            0 -> hourFromAngle(a, latestH.value).also { setH.value(it) }
                            1 -> ((a / 6f).roundToInt() % 60).also { setM.value(it) }
                            else -> ((a / 6f).roundToInt() % 60).also { setS.value(it) }
                        }
                        if (key != lastKey) {
                            if (lastKey != -1) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            lastKey = key
                        }
                    }

                    update(down.position)
                    down.consume()
                    try {
                        drag(down.id) { change ->
                            update(change.position)
                            change.consume()
                        }
                    } finally {
                        active = -1
                    }
                }
            }
    ) {
        val c = center
        val r = minOf(size.width, size.height) / 2f

        // Лицо циферблата
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(pal.faceCenter, pal.faceEdge),
                center = c,
                radius = r,
            ),
            radius = r,
            center = c,
        )

        // Тонкое кольцо обратного отсчёта (виден только при работающем таймере)
        val ringStroke = 4.dp.toPx()
        val ringR = r - ringStroke / 2f - 3.dp.toPx()
        drawCircle(
            color = pal.tick.copy(alpha = 0.16f),
            radius = ringR,
            center = c,
            style = Stroke(ringStroke),
        )
        if (ringAlpha.value > 0.01f && ringFraction.value > 0.002f) {
            drawArc(
                brush = Brush.linearGradient(
                    colors = listOf(pal.second, pal.minute),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                ),
                startAngle = -90f,
                sweepAngle = 360f * ringFraction.value,
                useCenter = false,
                topLeft = Offset(c.x - ringR, c.y - ringR),
                size = Size(ringR * 2f, ringR * 2f),
                alpha = ringAlpha.value,
                style = Stroke(ringStroke, cap = StrokeCap.Round),
            )
        }

        // Шкала: прямые радиальные риски.
        // Длинные и толстые — на 12, 3, 6 и 9 часах, на остальных делениях короткие.
        val tickOuter = ringR - ringStroke / 2f - 6.dp.toPx()
        for (i in 0 until 60) {
            val cardinal = i % 15 == 0
            val len = if (cardinal) 16.dp.toPx() else 6.dp.toPx()
            drawLine(
                color = if (cardinal) pal.tickStrong
                else pal.tick.copy(alpha = if (i % 5 == 0) 0.85f else 0.5f),
                start = polar(c, tickOuter - len, i * 6f),
                end = polar(c, tickOuter, i * 6f),
                strokeWidth = if (cardinal) 3.5.dp.toPx() else 1.5.dp.toPx(),
                cap = StrokeCap.Butt,
            )
        }

        // Стрелки: прямые линии со скруглёнными краями и небольшим противовесом за осью.
        val colors = listOf(pal.hour, pal.minute, pal.second)
        val lens = floatArrayOf(r * HOUR_LEN, r * MIN_LEN, r * SEC_LEN)
        val tails = floatArrayOf(r * 0.10f, r * 0.12f, r * 0.18f)
        val widths = floatArrayOf(7.dp.toPx(), 5.dp.toPx(), 2.dp.toPx())
        val angles = floatArrayOf(hourAngle.value, minuteAngle.value, secondAngle.value)
        val glow = floatArrayOf(glow0.value, glow1.value, glow2.value)
        val alpha = handsAlpha.value

        for (i in 0..2) {
            val g = glow[i].coerceIn(0f, 1f)
            val start = polar(c, -tails[i], angles[i])
            val end = polar(c, lens[i], angles[i])
            if (g > 0.01f) {
                // Мягкое свечение вокруг перетаскиваемой стрелки
                drawLine(
                    color = colors[i].copy(alpha = 0.25f * g),
                    start = start,
                    end = end,
                    strokeWidth = widths[i] + 12.dp.toPx() * g,
                    cap = StrokeCap.Round,
                )
            }
            drawLine(
                color = colors[i].copy(alpha = alpha),
                start = start,
                end = end,
                strokeWidth = widths[i] * (1f + 0.35f * g),
                cap = StrokeCap.Round,
            )
        }

        // Компактная ось
        drawCircle(color = pal.hour, radius = 5.dp.toPx(), center = c)
        drawCircle(color = pal.faceCenter, radius = 1.8.dp.toPx(), center = c)
    }
}

// =====================================================================================
//  Цифровой ввод: часы / минуты / секунды с кнопками + и −
// =====================================================================================

@Composable
private fun DigitalPicker(
    totalSec: Int,
    enabled: Boolean,
    onSetH: (Int) -> Unit,
    onSetM: (Int) -> Unit,
    onSetS: (Int) -> Unit,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pal = LocalPalette.current
    val h = totalSec / 3600
    val m = totalSec % 3600 / 60
    val s = totalSec % 60
    val dim by animateFloatAsState(if (enabled) 1f else 0.6f, tween(300), label = "digitalDim")

    Row(
        modifier
            .appCard(pal)
            .graphicsLayer { this.alpha = dim }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeColumn("часы", "часы", h, 24, pal.hour, enabled,
            onPlus = { onStep(3600) }, onMinus = { onStep(-3600) }, onType = onSetH)
        Colon()
        TimeColumn("мин", "минуты", m, 59, pal.minute, enabled,
            onPlus = { onStep(60) }, onMinus = { onStep(-60) }, onType = onSetM)
        Colon()
        TimeColumn("сек", "секунды", s, 59, pal.second, enabled,
            onPlus = { onStep(1) }, onMinus = { onStep(-1) }, onType = onSetS)
    }
}

@Composable
private fun Colon() {
    Text(
        text = ":",
        fontSize = 32.sp,
        fontWeight = FontWeight.Light,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun TimeColumn(
    unitLabel: String,
    a11yName: String,
    value: Int,
    max: Int,
    color: Color,
    enabled: Boolean,
    onPlus: () -> Unit,
    onMinus: () -> Unit,
    onType: (Int) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RoundStepButton("+", "Увеличить $a11yName", color, enabled, onPlus)
        TimeField(unitLabel, value, max, color, enabled, onType)
        RoundStepButton("−", "Уменьшить $a11yName", color, enabled, onMinus)
    }
}

/** Кнопка +/−: пружинистая анимация нажатия, при удержании повторяет шаг всё быстрее. */
@Composable
private fun RoundStepButton(
    symbol: String,
    description: String,
    color: Color,
    enabled: Boolean,
    onStep: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onStep)
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "stepScale",
    )

    Box(
        Modifier
            .size(40.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                alpha = if (enabled) 1f else 0.4f
            }
            .clip(CircleShape)
            .background(color.copy(alpha = if (pressed) 0.28f else 0.14f))
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                onClick(label = description) {
                    latest.value.invoke()
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    pressed = true
                    latest.value.invoke()
                    val repeat = scope.launch {
                        delay(420L)
                        var pause = 110L
                        while (true) {
                            latest.value.invoke()
                            delay(pause)
                            if (pause > 40L) pause -= 6L
                        }
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        repeat.cancel()
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            color = color,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Редактируемое двузначное поле: ввод цифр сразу двигает стрелки. */
@Composable
private fun TimeField(
    unitLabel: String,
    value: Int,
    max: Int,
    color: Color,
    enabled: Boolean,
    onType: (Int) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(two(value)) }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)

    // Синхронизация «снаружи» (стрелки, кнопки +/−) → текст поля.
    LaunchedEffect(value, text, focused) {
        if (!focused) {
            if (text != two(value)) text = two(value)
        } else if (text.isNotEmpty() && text.toIntOrNull() != value) {
            text = value.toString()
        }
    }
    val borderColor by animateColorAsState(
        targetValue = if (focused) color else Color.Transparent,
        animationSpec = tween(200),
        label = "fieldBorder",
    )

    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.takeLast(2)
            text = digits
            digits.toIntOrNull()?.let { onType(it.coerceAtMost(max)) }
        },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 38.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            fontFeatureSettings = "tnum",
        ),
        cursorBrush = SolidColor(color),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier
            .size(width = 84.dp, height = 78.dp)
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(color.copy(alpha = 0.10f))
                    .border(2.dp, borderColor, shape),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    inner()
                    Text(text = unitLabel, fontSize = 12.sp, color = color)
                }
            }
        },
    )
}

// =====================================================================================
//  Нижний блок: статус и подсказки, текст напоминания, кнопка запуска/остановки
// =====================================================================================

@Composable
private fun StatusTipsCard(
    running: Boolean,
    totalSec: Int,
    activeIntervalMs: Long,
    remainingMs: Long,
    batteryVisible: Boolean,
    onBattery: () -> Unit,
) {
    val pal = LocalPalette.current
    var tipIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(running) {
        if (!running) {
            while (true) {
                delay(5000L)
                tipIndex = (tipIndex + 1) % TIPS.size
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .appCard(pal)
            .animateContentSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(running)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (running) "Работает" else "Остановлено",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(6.dp))

        AnimatedContent(
            targetState = running,
            transitionSpec = {
                (fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 }) togetherWith
                    fadeOut(tween(150))
            },
            modifier = Modifier.heightIn(min = 44.dp),
            label = "statusBody",
        ) { isRunning ->
            if (isRunning) {
                Text(
                    text = stringResource(
                        R.string.status_next,
                        formatInterval(activeIntervalMs),
                        formatDuration(remainingMs),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Crossfade(
                    targetState = tipIndex,
                    animationSpec = tween(450),
                    label = "tips",
                ) { i ->
                    Text(
                        text = TIPS[i],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        AnimatedVisibility(visible = totalSec < 60) {
            Text(
                text = "Интервалы короче минуты Android может сдвигать при погашенном экране",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        AnimatedVisibility(visible = batteryVisible) {
            TextButton(onClick = onBattery) {
                Text(stringResource(R.string.btn_battery))
            }
        }
    }
}

@Composable
private fun StatusDot(running: Boolean) {
    val pal = LocalPalette.current
    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse = infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseValue",
    )
    val dotColor by animateColorAsState(
        targetValue = if (running) pal.minute else MaterialTheme.colorScheme.outline,
        animationSpec = tween(400),
        label = "dotColor",
    )
    Canvas(Modifier.size(20.dp)) {
        if (running) {
            val p = pulse.value
            drawCircle(
                color = dotColor.copy(alpha = (1f - p) * 0.45f),
                radius = 5.dp.toPx() + p * 5.dp.toPx(),
                center = center,
            )
        }
        drawCircle(color = dotColor, radius = 5.dp.toPx(), center = center)
    }
}

@Composable
private fun ReminderTextField(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    enabled: Boolean,
) {
    val pal = LocalPalette.current
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        isError = isError,
        shape = RoundedCornerShape(20.dp),
        label = { Text(stringResource(R.string.hint_text)) },
        supportingText = {
            Text(
                stringResource(
                    if (isError) R.string.error_text else R.string.helper_text
                )
            )
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = pal.card,
            unfocusedContainerColor = pal.card,
            disabledContainerColor = pal.card,
            errorContainerColor = pal.card,
        ),
    )
}

@Composable
private fun StartStopButton(running: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "buttonScale",
    )
    val stopMix by animateFloatAsState(
        targetValue = if (running) 1f else 0f,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "stopMix",
    )
    val shape = RoundedCornerShape(32.dp)

    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(pal.startFrom, pal.startTo)))
            .drawBehind { drawRect(pal.stop.copy(alpha = stopMix)) }
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlayStopIcon(mix = stopMix)
            AnimatedContent(
                targetState = running,
                transitionSpec = {
                    (fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 2 }) togetherWith
                        (fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 2 })
                },
                modifier = Modifier.clipToBounds(),
                label = "buttonLabel",
            ) { isRunning ->
                Text(
                    text = stringResource(if (isRunning) R.string.btn_stop else R.string.btn_start),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Иконка «Пуск» плавно превращается в «Стоп» (mix: 0 → треугольник, 1 → квадрат). */
@Composable
private fun PlayStopIcon(mix: Float) {
    Canvas(Modifier.size(22.dp)) {
        val s = minOf(size.width, size.height)
        rotate(degrees = 90f * mix) {
            if (mix < 0.99f) {
                val play = Path().apply {
                    moveTo(s * 0.28f, s * 0.16f)
                    lineTo(s * 0.86f, s * 0.50f)
                    lineTo(s * 0.28f, s * 0.84f)
                    close()
                }
                drawPath(play, Color.White.copy(alpha = 1f - mix))
            }
            if (mix > 0.01f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = mix),
                    topLeft = Offset(s * 0.2f, s * 0.2f),
                    size = Size(s * 0.6f, s * 0.6f),
                    cornerRadius = CornerRadius(s * 0.12f, s * 0.12f),
                )
            }
        }
    }
}

package com.example.reminder

import android.Manifest
import android.content.ActivityNotFoundException
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

        setContent {
            WaterTheme {
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
//  Тема: «водная» палитра, светлая и тёмная
// =====================================================================================

@Immutable
private class WaterPalette(
    val dark: Boolean,
    val scheme: ColorScheme,
    val bgTop: Color,
    val bgBottom: Color,
    val card: Color,
    val cardBorder: Color,
    val faceCenter: Color,
    val faceEdge: Color,
    val tick: Color,
    val hour: Color,
    val minute: Color,
    val second: Color,
    val stop: Color,
    val startFrom: Color,
    val startTo: Color,
)

private val LightPalette = WaterPalette(
    dark = false,
    scheme = lightColorScheme(
        primary = Color(0xFF0B5CA8), onPrimary = Color.White,
        primaryContainer = Color(0xFFD3E8FF), onPrimaryContainer = Color(0xFF00213F),
        secondary = Color(0xFF00828F), onSecondary = Color.White,
        secondaryContainer = Color(0xFFB8EEF5), onSecondaryContainer = Color(0xFF001F24),
        tertiary = Color(0xFF00A58E), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFB9F4E6), onTertiaryContainer = Color(0xFF00201A),
        background = Color(0xFFEFF8FC), onBackground = Color(0xFF0C1C28),
        surface = Color(0xFFF6FBFE), onSurface = Color(0xFF0C1C28),
        surfaceVariant = Color(0xFFDCE8EF), onSurfaceVariant = Color(0xFF3D4A54),
        outline = Color(0xFF6C7A85), outlineVariant = Color(0xFFBFCDD6),
        error = Color(0xFFC0443C), onError = Color.White,
    ),
    bgTop = Color(0xFFDDF1FA), bgBottom = Color(0xFFF4FAFD),
    card = Color(0xFFFFFFFF), cardBorder = Color(0xFFCFE3EE),
    faceCenter = Color(0xFFFFFFFF), faceEdge = Color(0xFFD9EEF8),
    tick = Color(0xFF5F8CA8),
    hour = Color(0xFF0B4F94), minute = Color(0xFF12B3A0), second = Color(0xFF00B8D9),
    stop = Color(0xFFC84A41),
    startFrom = Color(0xFF0B5CA8), startTo = Color(0xFF0090B0),
)

private val DarkPalette = WaterPalette(
    dark = true,
    scheme = darkColorScheme(
        primary = Color(0xFF8CC8FF), onPrimary = Color(0xFF00315A),
        primaryContainer = Color(0xFF0F4577), onPrimaryContainer = Color(0xFFD3E8FF),
        secondary = Color(0xFF52D7E8), onSecondary = Color(0xFF00363D),
        secondaryContainer = Color(0xFF004F58), onSecondaryContainer = Color(0xFFB8EEF5),
        tertiary = Color(0xFF4FDCC2), onTertiary = Color(0xFF00382F),
        tertiaryContainer = Color(0xFF005045), onTertiaryContainer = Color(0xFFB9F4E6),
        background = Color(0xFF06141F), onBackground = Color(0xFFDDEAF2),
        surface = Color(0xFF0A1B28), onSurface = Color(0xFFDDEAF2),
        surfaceVariant = Color(0xFF223541), onSurfaceVariant = Color(0xFFB4C4CF),
        outline = Color(0xFF7D8E9A), outlineVariant = Color(0xFF2E434F),
        error = Color(0xFFFF8A80), onError = Color(0xFF3A0A07),
    ),
    bgTop = Color(0xFF08233A), bgBottom = Color(0xFF050F18),
    card = Color(0xFF0F2536), cardBorder = Color(0xFF1E3A4E),
    faceCenter = Color(0xFF123349), faceEdge = Color(0xFF0B2234),
    tick = Color(0xFF6FA0BC),
    hour = Color(0xFF6DB6FF), minute = Color(0xFF3FE0C5), second = Color(0xFF67E8F9),
    stop = Color(0xFFC84A41),
    startFrom = Color(0xFF1767B8), startTo = Color(0xFF0A8FB0),
)

private val LocalWater = staticCompositionLocalOf { LightPalette }

@Composable
private fun WaterTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    MaterialTheme(colorScheme = palette.scheme) {
        CompositionLocalProvider(
            LocalWater provides palette,
            LocalContentColor provides palette.scheme.onSurface,
            content = content
        )
    }
}

private fun Modifier.waterCard(water: WaterPalette, radius: Dp = 28.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(water.card)
        .border(1.dp, water.cardBorder, shape)
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
) {
    val water = LocalWater.current
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
            .background(Brush.verticalGradient(listOf(water.bgTop, water.bgBottom)))
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

private const val HOUR_LEN = 0.42f
private const val MIN_LEN = 0.66f
private const val SEC_LEN = 0.84f

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
    val water = LocalWater.current
    val haptic = LocalHapticFeedback.current

    val h = totalSec / 3600
    val m = totalSec % 3600 / 60
    val s = totalSec % 60

    val hourAngle = rememberHandAngle(h * 15f, wrap = false)
    val minuteAngle = rememberHandAngle(m * 6f, wrap = true)
    val secondAngle = rememberHandAngle(s * 6f, wrap = true)

    var active by remember { mutableIntStateOf(-1) }
    val knobSpec = spring<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
    val knob0 = animateFloatAsState(if (active == 0) 1f else 0f, knobSpec, label = "knob0")
    val knob1 = animateFloatAsState(if (active == 1) 1f else 0f, knobSpec, label = "knob1")
    val knob2 = animateFloatAsState(if (active == 2) 1f else 0f, knobSpec, label = "knob2")
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
            .shadow(elevation = if (water.dark) 0.dp else 10.dp, shape = CircleShape)
            .semantics { contentDescription = description }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = size.minDimension / 2f

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
        val r = size.minDimension / 2f

        // Лицо циферблата
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(water.faceCenter, water.faceEdge),
                center = c,
                radius = r,
            ),
            radius = r,
            center = c,
        )

        // Кольцо обратного отсчёта (виден только при работающем таймере)
        val ringStroke = 7.dp.toPx()
        val ringR = r - ringStroke / 2f - 4.dp.toPx()
        drawCircle(
            color = water.tick.copy(alpha = 0.18f),
            radius = ringR,
            center = c,
            style = Stroke(ringStroke),
        )
        if (ringAlpha.value > 0.01f && ringFraction.value > 0.002f) {
            drawArc(
                brush = Brush.linearGradient(
                    colors = listOf(water.second, water.minute),
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

        // Шкала минут/секунд: 60 делений
        val tickOuter = ringR - ringStroke / 2f - 6.dp.toPx()
        for (i in 0 until 60) {
            val major = i % 5 == 0
            val len = if (major) 10.dp.toPx() else 5.dp.toPx()
            drawLine(
                color = water.tick.copy(alpha = if (major) 0.9f else 0.45f),
                start = polar(c, tickOuter - len, i * 6f),
                end = polar(c, tickOuter, i * 6f),
                strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // Шкала часов: 24 точки (крупные — 0, 6, 12, 18)
        val dotR = tickOuter - 26.dp.toPx()
        for (i in 0 until 24) {
            val major = i % 6 == 0
            drawCircle(
                color = water.hour.copy(alpha = if (major) 0.75f else 0.35f),
                radius = if (major) 3.2.dp.toPx() else 1.8.dp.toPx(),
                center = polar(c, dotR, i * 15f),
            )
        }

        // Стрелки
        val colors = listOf(water.hour, water.minute, water.second)
        val lens = floatArrayOf(r * HOUR_LEN, r * MIN_LEN, r * SEC_LEN)
        val angles = floatArrayOf(hourAngle.value, minuteAngle.value, secondAngle.value)
        val widths = floatArrayOf(10.dp.toPx(), 7.dp.toPx(), 3.dp.toPx())
        val knobs = floatArrayOf(9.dp.toPx(), 8.dp.toPx(), 6.dp.toPx())
        val press = floatArrayOf(knob0.value, knob1.value, knob2.value)
        val alpha = handsAlpha.value

        for (i in 0..2) {
            drawLine(
                color = colors[i].copy(alpha = alpha),
                start = polar(c, -14.dp.toPx(), angles[i]),
                end = polar(c, lens[i], angles[i]),
                strokeWidth = widths[i],
                cap = StrokeCap.Round,
            )
        }
        for (i in 0..2) {
            val tip = polar(c, lens[i], angles[i])
            val t = press[i]
            if (t > 0.01f) {
                drawCircle(
                    color = colors[i].copy(alpha = 0.22f * t),
                    radius = knobs[i] + 14.dp.toPx() * t,
                    center = tip,
                )
            }
            val kr = knobs[i] * (1f + 0.3f * t)
            drawCircle(color = water.faceCenter, radius = kr + 2.dp.toPx(), center = tip)
            drawCircle(color = colors[i].copy(alpha = alpha), radius = kr, center = tip)
        }

        // Втулка в центре
        drawCircle(color = water.faceCenter, radius = 11.dp.toPx(), center = c)
        drawCircle(
            color = water.hour,
            radius = 11.dp.toPx(),
            center = c,
            style = Stroke(3.5.dp.toPx()),
        )
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
    val water = LocalWater.current
    val h = totalSec / 3600
    val m = totalSec % 3600 / 60
    val s = totalSec % 60
    val dim by animateFloatAsState(if (enabled) 1f else 0.6f, tween(300), label = "digitalDim")

    Row(
        modifier
            .waterCard(water)
            .graphicsLayer { this.alpha = dim }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeColumn("часы", "часы", h, 24, water.hour, enabled,
            onPlus = { onStep(3600) }, onMinus = { onStep(-3600) }, onType = onSetH)
        Colon()
        TimeColumn("мин", "минуты", m, 59, water.minute, enabled,
            onPlus = { onStep(60) }, onMinus = { onStep(-60) }, onType = onSetM)
        Colon()
        TimeColumn("сек", "секунды", s, 59, water.second, enabled,
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
    val water = LocalWater.current
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
            .waterCard(water)
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
    val water = LocalWater.current
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
        targetValue = if (running) water.minute else MaterialTheme.colorScheme.outline,
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
    val water = LocalWater.current
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
            focusedContainerColor = water.card,
            unfocusedContainerColor = water.card,
            disabledContainerColor = water.card,
            errorContainerColor = water.card,
        ),
    )
}

@Composable
private fun StartStopButton(running: Boolean, onClick: () -> Unit) {
    val water = LocalWater.current
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
            .background(Brush.horizontalGradient(listOf(water.startFrom, water.startTo)))
            .drawBehind { drawRect(water.stop.copy(alpha = stopMix)) }
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

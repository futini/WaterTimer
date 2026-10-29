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
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.reminder.databinding.ActivityMainBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

private data class PendingStart(
    val text: String,
    val intervalMs: Long,
    val rawValue: String,
    val hours: Boolean
)

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var pending: PendingStart? = null

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
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        NotificationHelper.ensureChannel(this)
        restoreInputs()

        b.btnStart.setOnClickListener { onStartClicked() }
        b.btnStop.setOnClickListener { onStopClicked() }
        b.btnBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
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
        val text = b.etText.text?.toString()?.trim().orEmpty()
        val rawValue = b.etInterval.text?.toString()?.trim().orEmpty()
        val value = rawValue.replace(',', '.').toDoubleOrNull()
        val hours = b.toggleUnit.checkedButtonId == R.id.btnHours

        val minutes: Long =
            if (value != null && value.isFinite() && value > 0.0)
                Math.round(value * if (hours) 60.0 else 1.0)
            else -1L

        val textError = if (text.isEmpty()) getString(R.string.error_text) else null
        val intervalError = when {
            value == null || !value.isFinite() || value <= 0.0 -> getString(R.string.error_interval)
            minutes < 1L -> getString(R.string.error_interval_min)
            minutes > MAX_MINUTES -> getString(R.string.error_interval_max)
            else -> null
        }
        b.tilText.error = textError
        b.tilInterval.error = intervalError
        if (textError != null || intervalError != null) return

        pending = PendingStart(text, minutes * MINUTE_MS, rawValue, hours)
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

        Prefs.saveUi(this, p.rawValue, p.hours)
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

    // ---------------------------------------------------------------- UI

    private fun restoreInputs() {
        b.etText.setText(Prefs.text(this))
        b.etInterval.setText(Prefs.uiValue(this))
        b.toggleUnit.check(if (Prefs.uiHours(this)) R.id.btnHours else R.id.btnMinutes)
    }

    private fun renderState() {
        val running = Prefs.isRunning(this)

        b.btnStart.isEnabled = !running
        b.btnStop.isEnabled = running
        b.tilText.isEnabled = !running
        b.tilInterval.isEnabled = !running
        b.btnMinutes.isEnabled = !running
        b.btnHours.isEnabled = !running

        b.tvStatus.setText(if (running) R.string.status_running else R.string.status_stopped)
        b.tvStatus.setTextColor(
            MaterialColors.getColor(
                b.tvStatus,
                if (running) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurfaceVariant
            )
        )

        val pm = getSystemService(PowerManager::class.java)
        b.btnBattery.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE

        renderCountdown()
    }

    private fun renderCountdown() {
        if (!Prefs.isRunning(this)) {
            b.tvDetails.setText(R.string.status_idle_hint)
            return
        }
        val left = (Prefs.nextTrigger(this) - System.currentTimeMillis()).coerceAtLeast(0L)
        b.tvDetails.text = getString(
            R.string.status_next,
            formatInterval(Prefs.intervalMs(this)),
            formatDuration(left)
        )
    }

    private fun formatInterval(ms: Long): String =
        if (ms % HOUR_MS == 0L) getString(R.string.interval_hours, ms / HOUR_MS)
        else getString(R.string.interval_minutes, ms / MINUTE_MS)

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        else String.format(Locale.getDefault(), "%02d:%02d", m, s)
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

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        const val MAX_MINUTES = 30L * 24 * 60 // 30 дней
    }
}

package com.example.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * Планировщик на AlarmManager.
 *
 * Почему не WorkManager: минимальный период у PeriodicWorkRequest — 15 минут,
 * и система может сдвигать запуск. Точный будильник setExactAndAllowWhileIdle
 * срабатывает вовремя даже в режиме сна (Doze) и при погашенном экране.
 *
 * Схема: каждый будильник одноразовый. Когда он срабатывает, ReminderReceiver
 * показывает уведомление и сразу ставит следующий — от прежнего расписания
 * (а не от "сейчас"), поэтому задержки не накапливаются.
 */
object ReminderScheduler {

    private const val REQUEST_CODE = 1001
    private const val ACTION_FIRE = "com.example.reminder.action.FIRE"

    fun canScheduleExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager(ctx).canScheduleExactAlarms()

    /** Запуск: первое напоминание — через один интервал. */
    fun start(ctx: Context, text: String, intervalMs: Long) {
        val app = ctx.applicationContext
        val firstAt = System.currentTimeMillis() + intervalMs
        Prefs.setRunning(app, text, intervalMs, firstAt)
        schedule(app, firstAt)
    }

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        alarmManager(app).cancel(pendingIntent(app))
        Prefs.setStopped(app)
        NotificationManagerCompat.from(app).cancel(NotificationHelper.NOTIFICATION_ID)
    }

    /** Вызывается из ReminderReceiver, когда сработал будильник. */
    fun onAlarmFired(ctx: Context) {
        val app = ctx.applicationContext
        if (!Prefs.isRunning(app)) return
        val interval = Prefs.intervalMs(app)
        if (interval <= 0L) return

        val now = System.currentTimeMillis()
        val scheduled = Prefs.nextTrigger(app).takeIf { it > 0L } ?: now
        val next = nextSlot(scheduled + interval, interval, now)

        // Сначала ставим следующий будильник, потом показываем уведомление —
        // так цепочка не оборвётся, даже если показ уведомления упадёт.
        Prefs.setNext(app, next)
        schedule(app, next)
        NotificationHelper.show(app, Prefs.text(app))
    }

    /** Восстановление после перезагрузки, обновления приложения или смены разрешения. */
    fun restore(ctx: Context) {
        val app = ctx.applicationContext
        if (!Prefs.isRunning(app)) return
        val interval = Prefs.intervalMs(app)
        if (interval <= 0L) return

        val now = System.currentTimeMillis()
        val next = nextSlot(Prefs.nextTrigger(app), interval, now)
        Prefs.setNext(app, next)
        schedule(app, next)
    }

    /**
     * Ближайший момент сетки base + k * interval, строго в будущем.
     * Пропущенные (пока телефон был выключен) напоминания не "догоняются" пачкой.
     */
    private fun nextSlot(base: Long, interval: Long, now: Long): Long = when {
        base <= 0L -> now + interval
        base > now -> base
        else -> base + ((now - base) / interval + 1) * interval
    }

    private fun schedule(ctx: Context, triggerAt: Long) {
        val am = alarmManager(ctx)
        val pi = pendingIntent(ctx)
        try {
            if (canScheduleExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                // Нет разрешения на точные будильники — запасной вариант (может чуть опаздывать).
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun alarmManager(ctx: Context): AlarmManager =
        ctx.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_FIRE)
        return PendingIntent.getBroadcast(
            ctx,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

package com.example.reminder

import android.content.Context
import androidx.core.content.edit

/**
 * Всё состояние хранится в SharedPreferences, чтобы переживать
 * перезапуск процесса и перезагрузку телефона.
 */
object Prefs {
    private const val FILE = "reminder_prefs"
    private const val K_RUNNING = "running"
    private const val K_TEXT = "text"
    private const val K_INTERVAL_MS = "interval_ms"
    private const val K_NEXT_TRIGGER = "next_trigger"
    private const val K_UI_VALUE = "ui_value"
    private const val K_UI_HOURS = "ui_hours"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isRunning(ctx: Context): Boolean = sp(ctx).getBoolean(K_RUNNING, false)
    fun text(ctx: Context): String = sp(ctx).getString(K_TEXT, "").orEmpty()
    fun intervalMs(ctx: Context): Long = sp(ctx).getLong(K_INTERVAL_MS, 0L)
    fun nextTrigger(ctx: Context): Long = sp(ctx).getLong(K_NEXT_TRIGGER, 0L)

    fun uiValue(ctx: Context): String = sp(ctx).getString(K_UI_VALUE, "30").orEmpty()
    fun uiHours(ctx: Context): Boolean = sp(ctx).getBoolean(K_UI_HOURS, false)

    fun setRunning(ctx: Context, text: String, intervalMs: Long, nextTrigger: Long) {
        sp(ctx).edit {
            putBoolean(K_RUNNING, true)
            putString(K_TEXT, text)
            putLong(K_INTERVAL_MS, intervalMs)
            putLong(K_NEXT_TRIGGER, nextTrigger)
        }
    }

    fun setStopped(ctx: Context) {
        sp(ctx).edit {
            putBoolean(K_RUNNING, false)
            putLong(K_NEXT_TRIGGER, 0L)
        }
    }

    fun setNext(ctx: Context, nextTrigger: Long) {
        sp(ctx).edit { putLong(K_NEXT_TRIGGER, nextTrigger) }
    }

    fun saveUi(ctx: Context, value: String, hours: Boolean) {
        sp(ctx).edit {
            putString(K_UI_VALUE, value)
            putBoolean(K_UI_HOURS, hours)
        }
    }
}

package com.example.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Принимает сигнал будильника: показывает уведомление и планирует следующее. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.onAlarmFired(context)
    }
}

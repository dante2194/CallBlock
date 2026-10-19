package com.dante.callblock

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Environment
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "CallBlock"

private inline fun safe(what: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        Log.e(TAG, "$what failed", t)
    }
}

/** Everything that happens AFTER a call has been rejected (same steps as the macro). */
object BlockActions {
    fun stamp(): String = SimpleDateFormat("HH:mm MMM dd", Locale.ENGLISH).format(Date())

    fun toastText(who: String): String = "⚠️تماس $who بلاک شد"

    fun onBlocked(ctx: Context, p: Prefs, number: String?, name: String?) {
        val who = name ?: number?.let { Numbers.local(it) } ?: "Hidden number"
        val stamp = stamp()
        p.blockedCount = p.blockedCount + 1

        safe("sound") { if (p.playSound) Sound.play(ctx) }
        safe("sms") { if (p.sendSms && number != null) Sms.reply(ctx, p, number) }
        safe("toast") {
            if (p.showToast) Toast.makeText(ctx, toastText(who), Toast.LENGTH_LONG).show()
        }
        safe("notification") { if (p.showNotification) Notifs.blocked(ctx, who, stamp) }
        safe("log") { LogStore.append(ctx, p, "🦁تماس \"$who\" بلاک شد  $stamp") }
        safe("status") { Notifs.refreshStatus(ctx) }
        safe("tile") { BlockTile.refresh(ctx) }
    }

    /** "Test alerts" button: sound + toast + notification only (no SMS, no log). */
    fun preview(ctx: Context) {
        val p = Prefs(ctx)
        val who = "09120000000"
        safe("sound") { if (p.playSound) Sound.play(ctx) }
        safe("toast") {
            if (p.showToast) Toast.makeText(ctx, toastText(who), Toast.LENGTH_LONG).show()
        }
        safe("notification") { if (p.showNotification) Notifs.blocked(ctx, who, stamp()) }
    }
}

object Sound {
    private var current: Ringtone? = null

    fun play(ctx: Context) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val r = RingtoneManager.getRingtone(ctx.applicationContext, uri) ?: return
        r.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        current?.stop()
        current = r
        r.play()
    }
}

object Sms {
    private val last = ConcurrentHashMap<String, Long>()

    @Suppress("DEPRECATION")
    private fun manager(ctx: Context, subId: Int): SmsManager {
        if (Build.VERSION.SDK_INT >= 31) {
            val m = ctx.getSystemService(SmsManager::class.java)
            return if (subId >= 0) m.createForSubscriptionId(subId) else m
        }
        return if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
    }

    fun reply(ctx: Context, p: Prefs, rawNumber: String) {
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return
        val target = Numbers.smsTarget(rawNumber) ?: return
        val text = p.smsText
        if (text.isBlank()) return

        val now = System.currentTimeMillis()
        val cooldown = p.smsCooldownMin * 60_000L
        val prev = last[target]
        if (prev != null && now - prev < cooldown) return
        last[target] = now

        val m = manager(ctx, p.smsSubId)
        val parts = m.divideMessage(text)
        if (parts.size > 1) {
            m.sendMultipartTextMessage(target, null, parts, null, null)
        } else {
            m.sendTextMessage(target, null, text, null, null)
        }
    }
}

/** rejected_calls.txt — kept in the app AND mirrored to Download/ when allowed. */
object LogStore {
    private const val NAME = "rejected_calls.txt"

    @Synchronized
    fun append(ctx: Context, p: Prefs, line: String) {
        val text = line + "\n"
        try {
            val f = File(ctx.filesDir, NAME)
            f.appendText(text, Charsets.UTF_8)
            if (f.length() > 200_000) {
                val keep = f.readLines().takeLast(300)
                f.writeText(keep.joinToString("\n") + "\n", Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.e(TAG, "internal log failed", e)
        }
        if (p.writeLogFile && Environment.isExternalStorageManager()) {
            try {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, NAME).appendText(text, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "Download log failed", e)
            }
        }
    }

    @Synchronized
    fun recent(ctx: Context, n: Int): String {
        val f = File(ctx.filesDir, NAME)
        if (!f.exists()) return ""
        return try {
            f.readLines().takeLast(n).reversed().joinToString("\n")
        } catch (e: Exception) {
            ""
        }
    }

    @Synchronized
    fun clear(ctx: Context) {
        File(ctx.filesDir, NAME).delete()
    }
}

object Notifs {
    private const val CH_BLOCKED = "blocked"
    private const val CH_STATUS = "status"
    const val ID_STATUS = 1001

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val blocked = NotificationChannel(CH_BLOCKED, "Blocked calls", NotificationManager.IMPORTANCE_DEFAULT)
        blocked.setSound(null, null)
        blocked.enableVibration(false)
        nm.createNotificationChannel(blocked)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Protection status", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun blocked(ctx: Context, who: String, stamp: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        ensureChannels(ctx)
        val n = Notification.Builder(ctx, CH_BLOCKED)
            .setSmallIcon(R.drawable.ic_block)
            .setContentTitle("⚠️ Call blocked")
            .setContentText("$who · $stamp")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt() + 2000, n)
    }

    fun status(ctx: Context): Notification {
        ensureChannels(ctx)
        val p = Prefs(ctx)
        val on = p.enabled
        val toggle = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, ToggleReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_block),
            if (on) "Pause" else "Resume",
            toggle
        ).build()
        return Notification.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_block)
            .setContentTitle(if (on) "🛡️ Call Block is ON" else "⏸️ Call Block is paused")
            .setContentText("${p.blockedCount} calls blocked")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .addAction(action)
            .build()
    }

    /** Re-post the status notification (only if the keep-alive service is running). */
    fun refreshStatus(ctx: Context) {
        if (!KeepAliveService.running) return
        ctx.getSystemService(NotificationManager::class.java).notify(ID_STATUS, status(ctx))
    }
}

/** One place that flips the master switch, so app / tile / notification stay in sync. */
object Controller {
    fun setEnabled(ctx: Context, on: Boolean) {
        Prefs(ctx).enabled = on
        BlockTile.refresh(ctx)
        Notifs.refreshStatus(ctx)
    }
}

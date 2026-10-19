package com.dante.callblock

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import android.util.Log
import android.widget.Toast

private const val TAG = "CallBlock"

/**
 * The blocker. Android binds this service by itself for every incoming call
 * (once Call Block holds the "call screening app" role), even if the app was
 * killed or the phone just rebooted — no foreground service is needed for it.
 */
class CallScreener : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        var response = CallScreeningService.CallResponse.Builder().build() // default: let it ring
        var after: (() -> Unit)? = null

        try {
            val p = Prefs(this)
            if (callDetails.callDirection == Call.Details.DIRECTION_INCOMING && p.enabled) {
                val raw = callDetails.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
                val hidden = raw == null ||
                    callDetails.handlePresentation != TelecomManager.PRESENTATION_ALLOWED
                val name = if (raw != null && !hidden) ContactLookup.name(this, raw) else null
                val isContact = name != null
                val block = if (isContact) p.blockContacts else p.blockUnknown

                if (block) {
                    response = CallScreeningService.CallResponse.Builder()
                        .setDisallowCall(true)
                        .setRejectCall(true)
                        .setSkipNotification(true) // we post our own notification
                        .build()
                    val number = if (hidden) null else raw
                    after = { BlockActions.onBlocked(this, p, number, name) }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "screening failed — letting the call through", t)
        }

        respondToCall(callDetails, response)

        try {
            after?.invoke()
        } catch (t: Throwable) {
            Log.e(TAG, "post-block actions failed", t)
        }
    }
}

/** Optional: persistent notification + keeps the process warm on aggressive ROMs. */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifs.status(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifs.ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifs.ID_STATUS, n)
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        @Volatile
        var running = false

        /** Start or stop the service to match the "keep-alive" setting. */
        fun sync(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java)
            try {
                if (Prefs(ctx).keepAlive) ctx.startForegroundService(i) else ctx.stopService(i)
            } catch (e: Exception) {
                Log.w(TAG, "keep-alive sync failed", e)
            }
        }
    }
}

/** Quick Settings tile: tap = ON/OFF, long-press = open the app. */
class BlockTile : TileService() {

    override fun onStartListening() {
        render()
    }

    override fun onClick() {
        if (!Setup.hasRole(this)) {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE)
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
            return
        }
        val on = !Prefs(this).enabled
        Controller.setEnabled(this, on)
        Toast.makeText(
            this,
            if (on) "🛡️ Call blocking ON" else "⏸️ Call blocking OFF",
            Toast.LENGTH_SHORT
        ).show()
        render()
    }

    private fun render() {
        val t = qsTile ?: return
        val p = Prefs(this)
        val ready = Setup.hasRole(this)
        t.state = if (ready && p.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = getString(R.string.tile_label)
        t.subtitle = when {
            !ready -> "Setup needed"
            p.enabled -> "ON · ${p.blockedCount} blocked"
            else -> "OFF"
        }
        t.icon = Icon.createWithResource(this, R.drawable.ic_block)
        t.updateTile()
    }

    companion object {
        /** Ask the system to refresh the tile (no-op if it isn't added). */
        fun refresh(ctx: Context) {
            try {
                TileService.requestListeningState(ctx, ComponentName(ctx, BlockTile::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "tile refresh failed", e)
            }
        }
    }
}

/** After reboot / app update: refresh the tile and restart the keep-alive service. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BlockTile.refresh(context)
        KeepAliveService.sync(context)
    }
}

/** The Pause / Resume button on the status notification. */
class ToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Controller.setEnabled(context, !Prefs(context).enabled)
    }
}

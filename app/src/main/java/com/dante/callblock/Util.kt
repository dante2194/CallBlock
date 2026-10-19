package com.dante.callblock

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.ContactsContract

object Numbers {
    /** +98901... / 0098901... → 0901...  (other numbers are left alone). */
    fun local(raw: String): String {
        val n = raw.filter { it.isDigit() || it == '+' }
        return when {
            n.startsWith("+98") -> "0" + n.removePrefix("+98")
            n.startsWith("0098") -> "0" + n.removePrefix("0098")
            else -> n
        }
    }

    /** A number we can actually text, or null (short codes, names, garbage). */
    fun smsTarget(raw: String): String? {
        val n = raw.filter { it.isDigit() || it == '+' }
        return if (Regex("^\\+?[0-9]{5,15}$").matches(n)) n else null
    }
}

object ContactLookup {
    /** Contact display name for this number, or null if it's not a saved contact. */
    fun name(ctx: Context, number: String): String? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) (c.getString(0)?.takeIf { it.isNotBlank() } ?: "Contact") else null
            }
        } catch (e: Exception) {
            null
        }
    }
}

object Setup {
    fun hasRole(ctx: Context): Boolean = try {
        ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    } catch (e: Exception) {
        false
    }

    fun notificationsOn(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun batteryOk(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun allFilesOk(): Boolean = Environment.isExternalStorageManager()
}

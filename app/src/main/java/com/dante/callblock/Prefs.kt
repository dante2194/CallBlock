package com.dante.callblock

import android.content.Context

const val DEFAULT_SMS = "بعدا باهاتون تماس میگیرم"

/** All settings, stored in one SharedPreferences file. */
class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("callblock", Context.MODE_PRIVATE)

    private fun bool(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    private fun put(key: String, v: Boolean) { sp.edit().putBoolean(key, v).apply() }

    /** Master switch (also the Quick Settings tile). */
    var enabled: Boolean
        get() = bool("enabled", true)
        set(v) { put("enabled", v) }

    var blockUnknown: Boolean
        get() = bool("blockUnknown", true)
        set(v) { put("blockUnknown", v) }

    var blockContacts: Boolean
        get() = bool("blockContacts", true)
        set(v) { put("blockContacts", v) }

    var sendSms: Boolean
        get() = bool("sendSms", true)
        set(v) { put("sendSms", v) }

    var showToast: Boolean
        get() = bool("showToast", true)
        set(v) { put("showToast", v) }

    var playSound: Boolean
        get() = bool("playSound", true)
        set(v) { put("playSound", v) }

    var showNotification: Boolean
        get() = bool("showNotification", true)
        set(v) { put("showNotification", v) }

    var writeLogFile: Boolean
        get() = bool("writeLogFile", true)
        set(v) { put("writeLogFile", v) }

    var keepAlive: Boolean
        get() = bool("keepAlive", false)
        set(v) { put("keepAlive", v) }

    var smsText: String
        get() = sp.getString("smsText", null) ?: DEFAULT_SMS
        set(v) { sp.edit().putString("smsText", v).apply() }

    /** -1 = system default SMS SIM. */
    var smsSubId: Int
        get() = sp.getInt("smsSubId", -1)
        set(v) { sp.edit().putInt("smsSubId", v).apply() }

    /** Don't text the same number again within this many minutes (0 = always). */
    var smsCooldownMin: Int
        get() = sp.getInt("smsCooldownMin", 10)
        set(v) { sp.edit().putInt("smsCooldownMin", v).apply() }

    var blockedCount: Int
        get() = sp.getInt("blockedCount", 0)
        set(v) { sp.edit().putInt("blockedCount", v).apply() }
}

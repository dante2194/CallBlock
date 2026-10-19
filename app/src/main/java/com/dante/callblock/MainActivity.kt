package com.dante.callblock

import android.Manifest
import android.app.StatusBarManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.dante.callblock.databinding.ActivityMainBinding
import com.dante.callblock.databinding.ItemSetupBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var p: Prefs
    private var simIds: List<Int> = listOf(-1)

    private class Step(
        val title: String,
        val desc: String,
        val ok: (() -> Boolean)?,   // null = optional / can't be checked
        val btn: String,
        val action: () -> Unit
    )

    private val generic = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh() }

    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val stuck = result.filter { (perm, granted) ->
            !granted && !shouldShowRequestPermissionRationale(perm)
        }
        if (stuck.isNotEmpty()) {
            toast("Android won't ask again — enable it in App info → Permissions")
            openAppInfo()
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        p = Prefs(this)
        Notifs.ensureChannels(this)

        b.etSms.setText(p.smsText)
        b.etCooldown.setText(p.smsCooldownMin.toString())
        b.etSms.doAfterTextChanged { p.smsText = it?.toString().orEmpty() }
        b.etCooldown.doAfterTextChanged {
            p.smsCooldownMin = (it?.toString()?.toIntOrNull() ?: 0).coerceIn(0, 1440)
        }

        b.btnNext.setOnClickListener { nextStep() }
        b.btnTest.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 && !Setup.notificationsOn(this)) {
                perms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
            BlockActions.preview(this)
        }
        b.btnClear.setOnClickListener {
            LogStore.clear(this)
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------------------------------------------------------- UI

    private fun CompoundButton.bind(get: () -> Boolean, set: (Boolean) -> Unit) {
        setOnCheckedChangeListener(null)
        isChecked = get()
        setOnCheckedChangeListener { _, checked -> set(checked) }
    }

    private fun updateStatus() {
        b.tvStatus.text = when {
            !Setup.hasRole(this) -> "⚠️ Setup needed — grant the call-screening role"
            p.enabled -> "🛡️ Protection ON · ${p.blockedCount} calls blocked"
            else -> "⏸️ Paused"
        }
    }

    private fun refresh() {
        updateStatus()

        b.swEnabled.bind({ p.enabled }) {
            Controller.setEnabled(this, it)
            updateStatus()
            if (it && !Setup.hasRole(this)) nextStep()
        }
        b.swUnknown.bind({ p.blockUnknown }) { p.blockUnknown = it }
        b.swContacts.bind({ p.blockContacts }) { p.blockContacts = it }
        b.swSms.bind({ p.sendSms }) { p.sendSms = it }
        b.swToast.bind({ p.showToast }) { p.showToast = it }
        b.swSound.bind({ p.playSound }) { p.playSound = it }
        b.swNotif.bind({ p.showNotification }) { p.showNotification = it }
        b.swLogFile.bind({ p.writeLogFile }) { p.writeLogFile = it }
        b.swKeepAlive.bind({ p.keepAlive }) {
            p.keepAlive = it
            KeepAliveService.sync(this)
        }

        loadSims()

        b.setupList.removeAllViews()
        for (s in steps()) {
            val row = ItemSetupBinding.inflate(layoutInflater, b.setupList, false)
            row.tvTitle.text = s.title
            row.tvDesc.text = s.desc
            val ok = s.ok?.invoke()
            row.tvState.text = when (ok) {
                true -> "✅"
                false -> "❌"
                null -> "➕"
            }
            row.btnAction.text = s.btn
            row.btnAction.visibility = if (ok == true) View.GONE else View.VISIBLE
            row.btnAction.setOnClickListener { s.action() }
            b.setupList.addView(row.root)
        }
        val pending = steps().any { it.ok?.invoke() == false }
        b.btnNext.text = if (pending) "Continue setup" else "All set ✔"
        b.btnNext.isEnabled = pending

        val log = LogStore.recent(this, 30)
        b.tvLog.text = if (log.isBlank()) "No blocked calls yet." else log
    }

    private fun loadSims() {
        val ids = mutableListOf(-1)
        val labels = mutableListOf("Default SMS SIM")
        if (granted(Manifest.permission.READ_PHONE_STATE)) {
            try {
                val subs = getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList
                subs?.forEach {
                    ids.add(it.subscriptionId)
                    labels.add("SIM ${it.simSlotIndex + 1} · ${it.displayName}")
                }
            } catch (e: SecurityException) {
                // permission vanished — keep default only
            }
        }
        simIds = ids
        b.spSim.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = ids.indexOf(p.smsSubId)
        b.spSim.setSelection(if (idx >= 0) idx else 0)
        b.spSim.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                p.smsSubId = simIds.getOrElse(position) { -1 }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ------------------------------------------------------------- setup

    private fun granted(perm: String) =
        checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun steps(): List<Step> = listOf(
        Step(
            "Call-screening role",
            "Lets Call Block see and reject incoming calls. Required.",
            { Setup.hasRole(this) }, "Grant"
        ) { requestRole() },
        Step(
            "Contacts",
            "To tell saved contacts from unknown numbers.",
            { granted(Manifest.permission.READ_CONTACTS) }, "Grant"
        ) { perms.launch(arrayOf(Manifest.permission.READ_CONTACTS)) },
        Step(
            "SMS",
            "To text callers \"I'll call you back\" after blocking.",
            { granted(Manifest.permission.SEND_SMS) }, "Grant"
        ) { perms.launch(arrayOf(Manifest.permission.SEND_SMS)) },
        Step(
            "Phone / SIM info",
            "To let you pick which SIM sends the SMS.",
            { granted(Manifest.permission.READ_PHONE_STATE) }, "Grant"
        ) { perms.launch(arrayOf(Manifest.permission.READ_PHONE_STATE)) },
        Step(
            "Notifications",
            "Blocked-call alerts and the optional status notification.",
            { Setup.notificationsOn(this) }, "Grant"
        ) {
            if (Build.VERSION.SDK_INT >= 33) {
                perms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                launch(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        },
        Step(
            "All-files access",
            "Only needed to write rejected_calls.txt into Download.",
            { Setup.allFilesOk() }, "Grant"
        ) { requestAllFiles() },
        Step(
            "Battery: don't optimize",
            "Stops the OS from freezing the app (keeps toasts / SMS reliable).",
            { Setup.batteryOk(this) }, "Grant"
        ) { requestBattery() },
        Step(
            "Quick Settings tile",
            "Adds the ON/OFF tile to your notification panel.",
            null, "Add"
        ) { addTile() }
    )

    private fun nextStep() {
        steps().firstOrNull { it.ok?.invoke() == false }?.action?.invoke()
    }

    private fun requestRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
        } else {
            toast("This phone doesn't offer the call-screening role")
        }
    }

    private fun requestAllFiles() {
        try {
            generic.launch(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun requestBattery() {
        try {
            generic.launch(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            val sbm = getSystemService(StatusBarManager::class.java)
            sbm.requestAddTileService(
                ComponentName(this, BlockTile::class.java),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_block),
                mainExecutor
            ) { _ -> toast("Done — look for “Call Block” in your Quick Settings") }
        } else {
            toast("Swipe down twice → tap the ✏️ edit button → drag “Call Block” into your tiles")
        }
    }

    private fun openAppInfo() {
        launch(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    private fun launch(i: Intent) {
        try {
            generic.launch(i)
        } catch (e: Exception) {
            toast("Can't open that screen on this phone")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

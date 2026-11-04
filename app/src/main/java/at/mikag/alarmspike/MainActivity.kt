package at.mikag.alarmspike

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var linkInput: EditText
    private lateinit var queryInput: EditText
    private lateinit var strategyPicker: Spinner
    private lateinit var logView: TextView
    private val refresh: () -> Unit = { logView.text = TestLog.read(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.heading("1 · Set up")
        statusView = root.note("")
        linkInput = EditText(this).apply {
            hint = "TIDAL link (or share a playlist/track from TIDAL to this app)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Prefs.link(this@MainActivity))
        }
        root.addView(linkInput)
        queryInput = EditText(this).apply {
            hint = "Its name, as you'd search for it"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(Prefs.query(this@MainActivity))
        }
        root.addView(queryInput)
        root.button("Optional: allow notification access (only for test H)") { openNotificationAccess() }
        root.note(
            "Optional, skip it if it's blocked. It lets the log show which song plays. If Android says the setting " +
                "is unavailable: Settings → Apps → Alarm Test → ⋮ (top right) → Allow restricted settings, then try again."
        )

        root.heading("2 · Test with the phone unlocked")
        root.note(
            "Before EACH test: play something in Audible for a moment and pause it. Then play a song in TIDAL " +
                "that is NOT in your test playlist and pause it too, so you can hear whether TIDAL starts YOUR " +
                "playlist or just resumes the old song. Run J first with a playlist link, then share a TRACK from " +
                "TIDAL to this app and run J again."
        )
        Strategy.values().forEach { strategy -> root.button(strategy.label) { runNow(strategy) } }
        root.button("Pause TIDAL") { Tidal.pause(this) { log(it) } }

        root.heading("3 · Test with the phone locked")
        root.note(
            "Use J. Schedule the alarm, lock the phone and wait. Let it ring for a full minute before you " +
                "dismiss, so we see whether the music keeps playing."
        )
        strategyPicker = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                Strategy.values().map { it.label }
            )
            setSelection(Strategy.PLAY_LINK.ordinal)
        }
        root.addView(strategyPicker)
        root.button("Schedule test alarm in 1 minute") { scheduleAlarm() }

        root.heading("Log")
        root.note("When you're done, tap Copy log and paste it into the chat with Claude.")
        root.button("Copy log") { copyLog() }
        root.button("Clear log") { TestLog.clear(this) }
        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(logView)

        val scroll = ScrollView(this).apply { addView(root) }
        scroll.padForSystemBars()
        setContentView(scroll)

        if (savedInstanceState == null) {
            log(
                "Round 3. Device:${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                    "(API ${Build.VERSION.SDK_INT}), TIDAL app: ${Tidal.installedVersion(this) ?: "not installed"}"
            )
            handleShare(intent)
        }
        askForNotifications()
    }

    override fun onStart() {
        super.onStart()
        TestLog.addListener(refresh)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onStop() {
        TestLog.removeListener(refresh)
        saveInputs()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        log("Received from share: $text" + if (subject != null) " (subject: $subject)" else "")
        linkInput.setText(text)
        if (queryInput.text.isBlank() && subject != null) queryInput.setText(subject)
        saveInputs()
        updateStatus()
    }

    private fun updateStatus() {
        val lines = mutableListOf<String>()
        val tidal = Tidal.installedVersion(this)
        lines += if (tidal != null) "✓ TIDAL app installed ($tidal)" else "✗ TIDAL app not found"
        val item = Tidal.parse(linkInput.text.toString())
        lines += if (item != null) "✓ Link understood: ${item.type} ${item.id}" else "• No TIDAL link yet"
        lines += if (Sessions.granted(this)) "✓ Notification access on" else "✗ Notification access off (needed for test H)"
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            lines += "✗ Notifications not allowed (needed for the locked test)"
        }
        if (Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            lines += "✗ Full-screen alarms not allowed (needed for the locked test)"
        }
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            lines += "✗ Exact alarms not allowed (needed for the locked test)"
        }
        statusView.text = lines.joinToString("\n")
    }

    private fun openNotificationAccess() {
        val detail = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, Sessions.component(this).flattenToString())
        } else {
            null
        }
        try {
            startActivity(detail ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        toast("Turn on notification access for Alarm Test")
    }

    private fun runNow(strategy: Strategy) {
        saveInputs()
        updateStatus()
        handler.removeCallbacksAndMessages(null)
        log("=== ${strategy.label}, phone unlocked, media volume ${Tidal.mediaVolume(this)} ===")
        log("Before: ${Tidal.playingSummary(this)}")
        Tidal.run(this, handler, strategy, linkInput.text.toString(), queryInput.text.toString(), alarmMode = false) {
            log(it)
        }
        if (!strategy.ownChecks) Tidal.checkPlaybackLater(handler, this, listOf(10, 20, 30), { log(it) })
    }

    private fun scheduleAlarm() {
        saveInputs()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Please allow notifications first")
            askForNotifications()
            return
        }
        if (Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            toast("Please allow full-screen alarms for Alarm Test")
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            return
        }
        val alarms = getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
            toast("Please allow exact alarms for Alarm Test")
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            return
        }
        val strategy = Strategy.values()[strategyPicker.selectedItemPosition]
        val at = System.currentTimeMillis() + 60_000L
        val fire = PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_FIRE)
                .putExtra(AlarmReceiver.EXTRA_STRATEGY, strategy.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val show = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))
        log("Test alarm set for $time using ${strategy.label}. Lock the phone now.")
        toast("Alarm in 1 minute: lock your phone now")
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun copyLog() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Alarm test log", TestLog.read(this)))
        toast("Log copied")
    }

    private fun saveInputs() = Prefs.save(this, linkInput.text.toString(), queryInput.text.toString())

    private fun log(message: String) = TestLog.add(this, message)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

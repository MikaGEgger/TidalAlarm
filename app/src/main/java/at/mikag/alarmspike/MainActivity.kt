package at.mikag.alarmspike

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.CheckBox
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
    private lateinit var desktopMode: CheckBox
    private lateinit var logView: TextView
    private val refresh: () -> Unit = { logView.text = TestLog.read(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.heading("1 · Pick something to play")
        statusView = root.note("")
        linkInput = EditText(this).apply {
            hint = "TIDAL link (or share a playlist/track from TIDAL to this app)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Prefs.link(this@MainActivity))
        }
        root.addView(linkInput)
        queryInput = EditText(this).apply {
            hint = "Its name, as you'd search for it (only used by test D)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(Prefs.query(this@MainActivity))
        }
        root.addView(queryInput)

        root.heading("2 · Test with the phone unlocked")
        root.note("Each test asks TIDAL to start playing, then checks for 15 s whether music really comes out. Pause TIDAL between tests.")
        Strategy.values().forEach { strategy -> root.button(strategy.label) { runNow(strategy) } }
        root.button("Pause TIDAL (like Snooze)") {
            Tidal.mediaKey(this, KeyEvent.KEYCODE_MEDIA_PAUSE) { log(it) }
        }

        root.heading("3 · Test with the phone locked")
        root.note("Pick the test that worked best above, schedule the alarm, lock the phone and wait.")
        strategyPicker = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                Strategy.values().map { it.label }
            )
            setSelection(Strategy.LINK_PLUS_PLAY.ordinal)
        }
        root.addView(strategyPicker)
        root.button("Schedule test alarm in 1 minute") { scheduleAlarm() }

        root.heading("4 · TIDAL web player inside the app")
        root.note("Log in, start the playlist yourself and listen for at least a minute. Does it stop after 30 s?")
        desktopMode = CheckBox(this).apply { text = "Pretend to be a desktop browser" }
        root.addView(desktopMode)
        root.button("Open web player") {
            saveInputs()
            startActivity(
                Intent(this, WebViewActivity::class.java)
                    .putExtra(WebViewActivity.EXTRA_LINK, linkInput.text.toString())
                    .putExtra(WebViewActivity.EXTRA_DESKTOP, desktopMode.isChecked)
            )
        }

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
                "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
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

    private fun runNow(strategy: Strategy) {
        saveInputs()
        updateStatus()
        handler.removeCallbacksAndMessages(null)
        log("=== ${strategy.label}, phone unlocked, media volume ${Tidal.mediaVolume(this)} ===")
        Tidal.run(this, strategy, linkInput.text.toString(), queryInput.text.toString()) { log(it) }
        Tidal.checkPlaybackLater(handler, this, { log(it) }, null)
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

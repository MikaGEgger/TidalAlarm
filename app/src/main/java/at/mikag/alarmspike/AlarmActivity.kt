package at.mikag.alarmspike

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference

/** The screen shown over the lock screen when the test alarm goes off. */
class AlarmActivity : Activity() {
    companion object {
        const val EXTRA_STRATEGY = "strategy"
        const val EXTRA_RESHOW = "reshow"
        private var current: WeakReference<AlarmActivity>? = null

        fun dismissAll(context: Context, source: String) {
            current?.get()?.handler?.removeCallbacksAndMessages(null)
            Tidal.pause(context) { TestLog.add(context, it) }
            Fallback.stop()
            AlarmNotifier.cancel(context)
            TestLog.add(context, "Dismissed via $source")
            current?.get()?.finish()
        }
    }

    val handler = Handler(Looper.getMainLooper())
    private var snoozed = false
    private lateinit var logView: TextView
    private val refresh: () -> Unit = { showLog() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Test alarm"
            textSize = 32f
        })
        root.button("Snooze test: pause TIDAL") {
            snoozed = true
            Fallback.stop()
            Tidal.pause(this) { log(it) }
        }
        root.button("Dismiss: stop everything") { dismissAll(this, "alarm screen") }
        logView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        root.addView(logView)
        val scroll = ScrollView(this).apply { addView(root) }
        scroll.padForSystemBars()
        setContentView(scroll)

        if (savedInstanceState == null && !intent.getBooleanExtra(EXTRA_RESHOW, false)) startTest()
    }

    private fun startTest() {
        val strategy = Strategy.valueOf(intent.getStringExtra(EXTRA_STRATEGY) ?: Strategy.LINK_PLUS_TIDAL_KEY.name)
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        log("Alarm screen shown. Phone locked: $locked, media volume ${Tidal.mediaVolume(this)}")
        Tidal.run(this, handler, strategy, Prefs.link(this), Prefs.query(this), alarmMode = true) { log(it) }
        // TIDAL's screen can't show over the lock screen, so bring ours back on top.
        handler.postDelayed({
            log("Bringing the alarm screen back to the front")
            AlarmNotifier.show(applicationContext, strategy.name, reshow = true)
        }, 7000)
        Tidal.checkPlaybackLater(handler, this, listOf(10, 20, 30), { log(it) }) {
            if (!snoozed) {
                log("No music after 30 s, starting fallback ringtone")
                Fallback.start(this) { log(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        log("Alarm screen is back on top")
    }

    override fun onStart() {
        super.onStart()
        TestLog.addListener(refresh)
        showLog()
    }

    override fun onStop() {
        TestLog.removeListener(refresh)
        super.onStop()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun log(message: String) = TestLog.add(this, message)

    private fun showLog() {
        logView.text = TestLog.read(this).lines().takeLast(25).joinToString("\n")
    }
}

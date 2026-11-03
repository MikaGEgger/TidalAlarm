package at.mikag.alarmspike

import android.app.Activity
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/** Test 4: TIDAL's own web player inside the app. Log shows whether audio keeps playing past 30 s. */
class WebViewActivity : Activity() {
    companion object {
        const val EXTRA_LINK = "link"
        const val EXTRA_DESKTOP = "desktop"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    }

    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        val container = FrameLayout(this)
        container.addView(web)
        container.padForSystemBars()
        setContentView(container)

        val desktop = intent.getBooleanExtra(EXTRA_DESKTOP, false)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            if (desktop) {
                userAgentString = DESKTOP_USER_AGENT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val drm = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (drm.isNotEmpty()) {
                    request.grant(drm.toTypedArray())
                    TestLog.add(this@WebViewActivity, "Web player asked for protected (DRM) playback: allowed")
                } else {
                    request.deny()
                }
            }
        }

        val item = Tidal.parse(intent.getStringExtra(EXTRA_LINK) ?: "")
        val url = item?.let { "https://listen.tidal.com/${it.type}/${it.id}" } ?: "https://listen.tidal.com/"
        TestLog.add(this, "=== Web player test (desktop mode: $desktop): $url ===")
        web.loadUrl(url)

        // Every 15 s, note whether audio is playing, to spot 30-second previews.
        for (step in 1..8) {
            handler.postDelayed({
                val playing = getSystemService(AudioManager::class.java).isMusicActive
                TestLog.add(this, "Web player, ${step * 15} s after opening: music playing = ${if (playing) "YES" else "no"}")
            }, step * 15_000L)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        TestLog.add(this, "Web player closed")
        web.destroy()
        super.onDestroy()
    }
}

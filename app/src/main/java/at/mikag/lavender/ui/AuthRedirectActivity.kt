package at.mikag.lavender.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import at.mikag.lavender.R
import at.mikag.lavender.container
import kotlinx.coroutines.launch

/** Receives tidalarm://auth?code=… after the TIDAL login page, finishes the login, and returns to the app. */
class AuthRedirectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val message = try {
                container.tidalAuth.handleRedirect(uri)
                getString(R.string.login_success)
            } catch (e: Exception) {
                getString(R.string.login_failed, e.message ?: "")
            }
            Toast.makeText(this@AuthRedirectActivity, message, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this@AuthRedirectActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
    }
}

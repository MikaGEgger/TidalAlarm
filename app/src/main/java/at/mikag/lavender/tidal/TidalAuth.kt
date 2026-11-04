package at.mikag.lavender.tidal

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import at.mikag.lavender.AppLog
import at.mikag.lavender.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * TIDAL login with OAuth 2.1 "Authorization Code + PKCE", the flow TIDAL documents for mobile apps.
 * No client secret is involved. Tokens are stored encrypted with a key kept in the Android Keystore.
 */
class TidalAuth(context: Context, private val http: OkHttpClient) {
    private val prefs = context.getSharedPreferences("tidal_auth", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshLock = Mutex()

    private val _loggedIn = MutableStateFlow(loadTokens() != null)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    val isConfigured: Boolean get() = BuildConfig.TIDAL_CLIENT_ID.isNotBlank()

    @Serializable
    private data class Tokens(val access: String, val refresh: String?, val expiresAt: Long, val userId: String? = null)

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 3600,
        @SerialName("user_id") val userId: Long? = null,
    )

    class AuthException(message: String) : Exception(message)

    /** Builds the TIDAL login page address and remembers the PKCE verifier for the way back. */
    fun loginUri(): Uri {
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(16)
        prefs.edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).apply()
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        return Uri.parse(LOGIN_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", BuildConfig.TIDAL_CLIENT_ID)
            .appendQueryParameter("redirect_uri", BuildConfig.TIDAL_REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("lang", Locale.getDefault().language)
            .build()
    }

    /** Handles tidalarm://auth?code=…&state=… after the user logged in. */
    suspend fun handleRedirect(uri: Uri) {
        uri.getQueryParameter("error")?.let { error ->
            AppLog.w("Tidal", "Login returned error=$error ${uri.getQueryParameter("error_description").orEmpty()}")
            throw AuthException(uri.getQueryParameter("error_description") ?: error)
        }
        val code = uri.getQueryParameter("code") ?: throw AuthException("No login code received")
        val expectedState = prefs.getString(KEY_STATE, null)
        if (expectedState == null || uri.getQueryParameter("state") != expectedState) {
            throw AuthException("Login could not be verified. Please try again.")
        }
        val verifier = prefs.getString(KEY_VERIFIER, null) ?: throw AuthException("Login expired. Please try again.")
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", BuildConfig.TIDAL_CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", BuildConfig.TIDAL_REDIRECT_URI)
            .add("code_verifier", verifier)
            .add("scope", SCOPES)
            .build()
        val response = try {
            tokenRequest(body)
        } catch (e: Exception) {
            AppLog.w("Tidal", "Login: code exchange failed", e)
            throw e
        }
        AppLog.i("Tidal", "Login ok (token valid for ${response.expiresIn} s)")
        prefs.edit().remove(KEY_VERIFIER).remove(KEY_STATE).apply()
        saveTokens(Tokens(response.accessToken, response.refreshToken, expiry(response), response.userId?.toString()))
        _loggedIn.value = true
    }

    /** A valid access token, refreshed when it's about to expire. Null when logged out. */
    suspend fun accessToken(forceRefresh: Boolean = false): String? = refreshLock.withLock {
        val tokens = loadTokens() ?: return@withLock null
        if (!forceRefresh && tokens.expiresAt > System.currentTimeMillis() + 60_000) return@withLock tokens.access
        val refresh = tokens.refresh ?: run {
            logout()
            return@withLock null
        }
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", BuildConfig.TIDAL_CLIENT_ID)
            .add("refresh_token", refresh)
            .add("scope", SCOPES)
            .build()
        try {
            val response = tokenRequest(body)
            val updated = Tokens(response.accessToken, response.refreshToken ?: refresh, expiry(response), tokens.userId)
            saveTokens(updated)
            updated.access
        } catch (e: AuthException) {
            // The refresh token was rejected: the user has to log in again.
            AppLog.w("Tidal", "Token refresh rejected: logged out", e)
            logout()
            null
        }
    }

    fun logout() {
        prefs.edit().remove(KEY_TOKENS).apply()
        _loggedIn.value = false
    }

    private suspend fun tokenRequest(body: FormBody): TokenResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw AuthException("TIDAL login failed (${response.code}): ${text.take(200)}")
            json.decodeFromString(TokenResponse.serializer(), text)
        }
    }

    private fun expiry(response: TokenResponse) = System.currentTimeMillis() + response.expiresIn * 1000

    // ---- encrypted storage ----

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun saveTokens(tokens: Tokens) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(json.encodeToString(Tokens.serializer(), tokens).toByteArray())
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(KEY_TOKENS, stored).apply()
    }

    private fun loadTokens(): Tokens? {
        val stored = prefs.getString(KEY_TOKENS, null) ?: return null
        return try {
            val (iv, data) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            json.decodeFromString(Tokens.serializer(), String(cipher.doFinal(data)))
        } catch (e: Exception) {
            null
        }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buffer = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(buffer, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private companion object {
        const val LOGIN_URL = "https://login.tidal.com/authorize"
        const val TOKEN_URL = "https://auth.tidal.com/v1/oauth2/token"
        const val SCOPES = "user.read collection.read playlists.read"
        const val KEY_ALIAS = "lavender_tidal_tokens"
        const val KEY_TOKENS = "tokens"
        const val KEY_VERIFIER = "pkce_verifier"
        const val KEY_STATE = "pkce_state"
    }
}

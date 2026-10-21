package app.nova.tv

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Écran principal : affiche l'interface Nova (fichier assets/index.html) dans une WebView.
 * Les connexions au serveur partent de l'appli (pas de blocage « navigateur »),
 * et la lecture vidéo est confiée au lecteur natif (PlayerActivity).
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView

    private val playerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val json = res.data?.getStringExtra(PlayerActivity.EXTRA_RESULT) ?: return@registerForActivityResult
            web.evaluateJavascript("window.novaNativeResult && window.novaNativeResult($json)", null)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        web.setBackgroundColor(0xFF06070C.toInt())
        web.isFocusable = true
        web.isFocusableInTouchMode = true

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.allowFileAccessFromFileURLs = true
        s.allowUniversalAccessFromFileURLs = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("file:")) return false
                // Les liens externes s'ouvrent hors de l'appli
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: Exception) {
                }
                return true
            }

            // Les requêtes de l'interface vers https://nova.proxy/?u=<adresse> sont faites par Android
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.host != "nova.proxy") return null
                val target = u.getQueryParameter("u") ?: return proxyError("other", "adresse manquante")
                return proxyFetch(target)
            }
        }
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "NovaAndroid")

        setContentView(web)
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl("file:///android_asset/index.html")
        }
        web.requestFocus()

        // Touche Retour : l'interface décide (fermer une fiche, revenir à l'accueil, quitter)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.novaBack && window.novaBack()) ? 'yes' : 'no'") { r ->
                    if (r == null || !r.contains("yes")) finish()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    // ---------- Connexions réseau faites par Android ----------

    private val corsHeaders: Map<String, String>
        get() = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store")

    private fun openFollowingRedirects(target: String): HttpURLConnection {
        var url = URL(target)
        repeat(6) {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Nova/1.0 (Linux; Android)")
            c.setRequestProperty("Accept", "*/*")
            val code = c.responseCode
            val loc = c.getHeaderField("Location")
            if (code in 300..399 && loc != null) {
                c.disconnect()
                url = URL(url, loc)
            } else {
                return c
            }
        }
        throw IOException("trop de redirections")
    }

    private fun proxyFetch(target: String): WebResourceResponse {
        return try {
            val c = openFollowingRedirects(target)
            val code = c.responseCode
            val ok = code in 200..299
            val stream = if (ok) c.inputStream else (c.errorStream ?: ByteArrayInputStream(ByteArray(0)))
            val status = if (ok || code in 400..598) code else 502
            val reason = c.responseMessage?.takeIf { it.isNotBlank() } ?: "Statut $status"
            WebResourceResponse("text/plain", "utf-8", status, reason, corsHeaders, stream)
        } catch (e: UnknownHostException) {
            proxyError("dns", e.message)
        } catch (e: SocketTimeoutException) {
            proxyError("timeout", e.message)
        } catch (e: ConnectException) {
            proxyError("refused", e.message)
        } catch (e: SSLException) {
            proxyError("ssl", e.message)
        } catch (e: Exception) {
            proxyError("other", e.javaClass.simpleName + " " + (e.message ?: ""))
        }
    }

    /** Erreur réseau renvoyée à l'interface avec le statut 599 et un code lisible. */
    private fun proxyError(kind: String, detail: String?): WebResourceResponse {
        val body = (kind + "|" + (detail ?: "")).toByteArray(Charsets.UTF_8)
        return WebResourceResponse("text/plain", "utf-8", 599, "Erreur reseau", corsHeaders, ByteArrayInputStream(body))
    }

    private fun isTv(): Boolean {
        val ui = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature("android.software.leanback")
    }

    /** Fonctions accessibles depuis l'interface (window.NovaAndroid). */
    inner class Bridge {
        @JavascriptInterface
        fun isTv(): Boolean = this@MainActivity.isTv()

        @JavascriptInterface
        fun play(json: String) {
            runOnUiThread {
                val i = Intent(this@MainActivity, PlayerActivity::class.java)
                i.putExtra(PlayerActivity.EXTRA_PLAYLIST, json)
                playerLauncher.launch(i)
            }
        }

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Nova", text))
            }
        }

        @JavascriptInterface
        fun openExternal(url: String) {
            runOnUiThread {
                try {
                    val i = Intent(Intent.ACTION_VIEW)
                    i.setDataAndType(Uri.parse(url), "video/*")
                    startActivity(Intent.createChooser(i, "Ouvrir avec"))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Aucune appli vidéo trouvée", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun exit() {
            runOnUiThread { finish() }
        }
    }
}

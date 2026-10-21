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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts

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

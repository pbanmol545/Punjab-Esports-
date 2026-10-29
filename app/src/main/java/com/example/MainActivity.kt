package com.example

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import org.json.JSONObject

class MainActivity : ComponentActivity() {

  private var webViewInstance: WebView? = null

  inner class WebAppInterface {
    @JavascriptInterface
    fun setSystemBars(colorHex: String, isDark: Boolean) {
      applySystemBars(colorHex, isDark)
    }

    @JavascriptInterface
    fun updateSystemBars(colorHex: String, isDark: Boolean) {
      applySystemBars(colorHex, isDark)
    }

    @JavascriptInterface
    fun setStatusBarColor(colorHex: String, isDark: Boolean) {
      applySystemBars(colorHex, isDark)
    }

    @JavascriptInterface
    fun setNavigationBarColor(colorHex: String, isDark: Boolean) {
      applySystemBars(colorHex, isDark)
    }

    @JavascriptInterface
    fun postMessage(jsonString: String) {
      try {
        val json = JSONObject(jsonString)
        val color = json.optString("color", if (json.optBoolean("isDark", true)) "#000000" else "#ffffff")
        val isDark = json.optBoolean("isDark", true)
        applySystemBars(color, isDark)
      } catch (e: Exception) {
        Log.w("MainActivity", "Ignored non-JSON postMessage: $jsonString")
      }
    }
  }

  fun applySystemBars(colorHex: String, isDark: Boolean) {
    runOnUiThread {
      try {
        val parsedColor = Color.parseColor(colorHex.trim())
        window.statusBarColor = parsedColor
        window.navigationBarColor = parsedColor
        window.decorView.setBackgroundColor(parsedColor)

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        // isAppearanceLightStatusBars = true produces dark icons (for white/light status bar)
        // isAppearanceLightStatusBars = false produces light/white icons (for black/dark status bar)
        insetsController.isAppearanceLightStatusBars = !isDark
        insetsController.isAppearanceLightNavigationBars = !isDark
      } catch (e: Exception) {
        Log.e("MainActivity", "Failed to parse system bar color: $colorHex", e)
      }
    }
  }

  private fun cleanVariationsSeed() {
    try {
      val dataDirPath = applicationInfo.dataDir
      if (dataDirPath != null) {
        val seedFile = java.io.File(dataDirPath, "app_webview/variations_seed")
        if (seedFile.exists()) {
          seedFile.delete()
        }
        val seedNew = java.io.File(dataDirPath, "app_webview/variations_seed_new")
        if (seedNew.exists()) {
          seedNew.delete()
        }
      }
      val prefs = getSharedPreferences("WebViewChromiumPrefs", MODE_PRIVATE)
      if (prefs.all.isNotEmpty()) {
        prefs.edit().clear().apply()
      }
    } catch (t: Throwable) {
      Log.d("MainActivity", "Variations seed cleanup non-fatal", t)
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    cleanVariationsSeed()

    // Initial state: default to dark system bars matching default amoled/dark theme
    window.statusBarColor = Color.BLACK
    window.navigationBarColor = Color.BLACK
    window.decorView.setBackgroundColor(Color.BLACK)
    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
    insetsController.isAppearanceLightStatusBars = false
    insetsController.isAppearanceLightNavigationBars = false

    setContent {
      var canGoBack by remember { mutableStateOf(false) }

      BackHandler(enabled = canGoBack) {
        if (webViewInstance?.canGoBack() == true) {
          webViewInstance?.goBack()
        }
      }

      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
          WebView(context).apply {
            webViewInstance = this
            layoutParams = android.view.ViewGroup.LayoutParams(
              android.view.ViewGroup.LayoutParams.MATCH_PARENT,
              android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )

            setBackgroundColor(Color.BLACK)

            // Enable cookies for Firebase auth and session persistence
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            settings.apply {
              javaScriptEnabled = true
              domStorageEnabled = true
              databaseEnabled = true
              allowFileAccess = true
              allowContentAccess = true
              loadWithOverviewMode = true
              useWideViewPort = true
              mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
              cacheMode = WebSettings.LOAD_DEFAULT
              mediaPlaybackRequiresUserGesture = false
              if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
              }
            }

            val jsInterface = WebAppInterface()
            addJavascriptInterface(jsInterface, "Android")
            addJavascriptInterface(jsInterface, "AndroidInterface")
            addJavascriptInterface(jsInterface, "AndroidBridge")

            webChromeClient = object : WebChromeClient() {
              override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("WebViewConsole", "${consoleMessage?.message()} [line ${consoleMessage?.lineNumber()}]")
                return true
              }
            }

            webViewClient = object : WebViewClient() {
              override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // Internal assets load inside the WebView
                if (url.startsWith("file:///android_asset/")) {
                  return false
                }
                // External URLs (WhatsApp, Telegram, UPI, external browsers) open via system intents
                return try {
                  val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                  context.startActivity(intent)
                  true
                } catch (e: Exception) {
                  Log.w("MainActivity", "Cannot open external intent for: $url", e)
                  false
                }
              }

              override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                canGoBack = view?.canGoBack() == true
                view?.evaluateJavascript(
                  """
                  (function() {
                    try {
                      var isDark = (localStorage.getItem('pe_bg') || 'dark') === 'dark';
                      if (window.syncSystemBars) {
                        window.syncSystemBars(isDark);
                      }
                    } catch(e) {}
                  })();
                  """.trimIndent(),
                  null
                )
              }

              override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                  Log.w("MainActivity", "Main frame load error: ${error?.description}")
                }
              }

              override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                canGoBack = view?.canGoBack() == true
              }
            }

            loadUrl("file:///android_asset/index.html")
          }
        }
      )
    }
  }

  override fun onResume() {
    super.onResume()
    webViewInstance?.onResume()
  }

  override fun onPause() {
    webViewInstance?.onPause()
    super.onPause()
  }

  override fun onDestroy() {
    webViewInstance?.destroy()
    webViewInstance = null
    super.onDestroy()
  }
}

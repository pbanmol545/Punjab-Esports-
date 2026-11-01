package com.example

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

  private var webView: WebView? = null
  private var filePathCallback: ValueCallback<Array<Uri>>? = null

  private val fileChooserLauncher =
      registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
          filePathCallback?.onReceiveValue(arrayOf(uri))
        } else {
          filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
      }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

    val wv = WebView(this)
    webView = wv

    val settings = wv.settings
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.allowFileAccess = true
    settings.allowContentAccess = true
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.mediaPlaybackRequiresUserGesture = false

    wv.setBackgroundColor(Color.BLACK)

    wv.addJavascriptInterface(
        object {
          @JavascriptInterface
          fun updateSystemBars(isDark: Boolean, colorHex: String) {
            runOnUiThread {
              try {
                val color = Color.parseColor(colorHex)
                window.statusBarColor = color
                window.navigationBarColor = color
                windowInsetsController.isAppearanceLightStatusBars = !isDark
                windowInsetsController.isAppearanceLightNavigationBars = !isDark
              } catch (_: Exception) {}
            }
          }
        },
        "AndroidInterface"
    )

    wv.webViewClient = object : WebViewClient() {
      override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        // Sync initial background and system bars
        wv.evaluateJavascript(
            "(function() { return (localStorage.getItem('pe_bg') || 'dark') === 'dark'; })();"
        ) { result ->
          val isDark = result != "false"
          val color = if (isDark) Color.BLACK else Color.WHITE
          window.statusBarColor = color
          window.navigationBarColor = color
          windowInsetsController.isAppearanceLightStatusBars = !isDark
          windowInsetsController.isAppearanceLightNavigationBars = !isDark
        }
      }
    }

    wv.webChromeClient = object : WebChromeClient() {
      override fun onShowFileChooser(
          webView: WebView?,
          filePathCallback: ValueCallback<Array<Uri>>?,
          fileChooserParams: FileChooserParams?
      ): Boolean {
        this@MainActivity.filePathCallback?.onReceiveValue(null)
        this@MainActivity.filePathCallback = filePathCallback
        try {
          fileChooserLauncher.launch("image/*")
        } catch (_: Exception) {
          this@MainActivity.filePathCallback = null
          return false
        }
        return true
      }
    }

    wv.loadUrl("file:///android_asset/index.html")

    setContentView(
        wv,
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    )

    onBackPressedDispatcher.addCallback(
        this,
        object : OnBackPressedCallback(true) {
          override fun handleOnBackPressed() {
            if (wv.canGoBack()) {
              wv.goBack()
            } else {
              isEnabled = false
              onBackPressedDispatcher.onBackPressed()
            }
          }
        }
    )
  }

  override fun onDestroy() {
    webView?.destroy()
    webView = null
    super.onDestroy()
  }
}

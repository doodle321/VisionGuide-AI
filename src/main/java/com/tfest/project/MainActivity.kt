package com.tfest.project

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.http.SslError
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.reecedunn.espeak.CheckVoiceData
import com.reecedunn.espeak.EspeakApp
import java.io.InputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private lateinit var voskManager: OfflineVoiceManager

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 101
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        configureWebView()

        // Inject JS Interfaces
        webView.addJavascriptInterface(WebAppInterface(), "AndroidSTT")
        webView.addJavascriptInterface(WebAppTTSInterface(), "AndroidTTS")

        // Unpack eSpeak voice data if not already present
        val storageContext = EspeakApp.getStorageContext() ?: applicationContext
        if (!CheckVoiceData.hasBaseResources(storageContext) || CheckVoiceData.canUpgradeResources(storageContext)) {
            Log.d(TAG, "Extracting eSpeak voice data...")
            Thread {
                try {
                    CheckVoiceData.extractVoiceData(storageContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting voice data", e)
                }
            }.start()
        }

        // Initialize TTS with graceful fallback to system default if embedded engine fails to bind
        initTTS()

        // Initialize Vosk offline speech manager for continuous background listening
        voskManager = OfflineVoiceManager(this) { command ->
            sendSpeechResultToJs(command)
        }
        voskManager.initialize {
            runOnUiThread {
                checkAndStartListening()
            }
        }

        // Request runtime permissions
        requestAppPermissions()

        // Load the main HTML page via secure local origin intercepted offline
        webView.loadUrl("https://localhost/www/index.html")
    }

    private fun initTTS() {
        try {
            tts = TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    Log.d(TAG, "eSpeak TTS Ready")
                } else {
                    Log.w(TAG, "eSpeak TTS binding failed with status $status, falling back to system default TTS...")
                    fallbackToDefaultTTS()
                }
            }, packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to instantiate eSpeak TTS, falling back to system default TTS", e)
            fallbackToDefaultTTS()
        }
    }

    private fun fallbackToDefaultTTS() {
        try {
            tts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    Log.d(TAG, "System Default TTS Ready")
                } else {
                    Log.e(TAG, "System Default TTS initialization failed with status $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate System Default TTS", e)
        }
    }

    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.mediaPlaybackRequiresUserGesture = false

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                val scheme = url.scheme?.lowercase(Locale.ROOT) ?: ""
                val host = url.host?.lowercase(Locale.ROOT) ?: ""

                // Intercept localhost or androidplatform asset requests completely offline
                if ((scheme == "https" || scheme == "http") && (host == "localhost" || host == "appassets.androidplatform.net")) {
                    val path = url.path?.removePrefix("/") ?: return null
                    val assetPath = if (path.startsWith("assets/")) path.removePrefix("assets/") else path
                    return try {
                        val inputStream: InputStream = assets.open(assetPath)
                        val mimeType = when {
                            assetPath.endsWith(".html") -> "text/html"
                            assetPath.endsWith(".js") -> "application/javascript"
                            assetPath.endsWith(".css") -> "text/css"
                            assetPath.endsWith(".json") -> "application/json"
                            assetPath.endsWith(".png") -> "image/png"
                            assetPath.endsWith(".jpg") || assetPath.endsWith(".jpeg") -> "image/jpeg"
                            assetPath.endsWith(".svg") -> "image/svg+xml"
                            assetPath.endsWith(".wasm") -> "application/wasm"
                            else -> "application/octet-stream"
                        }
                        val headers = mapOf(
                            "Access-Control-Allow-Origin" to "*",
                            "Access-Control-Allow-Methods" to "GET, OPTIONS",
                            "Access-Control-Allow-Headers" to "*"
                        )
                        WebResourceResponse(mimeType, "UTF-8", 200, "OK", headers, inputStream)
                    } catch (e: Exception) {
                        Log.e(TAG, "Asset not found: $assetPath", e)
                        WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                // Ensure intercepted local requests are not blocked by SSL checks
                handler?.proceed()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    request.grant(request.resources)
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                callback.invoke(origin, true, false)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    val msg = "[WebView JS] ${consoleMessage.message()} (at ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                    when (consoleMessage.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, msg)
                        ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, msg)
                        else -> Log.d(TAG, msg)
                    }
                }
                return true
            }
        }
    }

    private fun checkAndStartListening() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            voskManager.startListening()
        }
    }

    private fun sendSpeechResultToJs(command: String) {
        // Do not process recognized voice commands if TTS is currently speaking
        if (tts?.isSpeaking == true) {
            Log.d(TAG, "Ignoring recognized speech '$command' because TTS is currently speaking")
            return
        }
        val escapedCommand = command
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        runOnUiThread {
            webView.evaluateJavascript("window.onSpeechResult('$escapedCommand')", null)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == packageName &&
                service.resolveInfo.serviceInfo.name == AccessibilityShortcutService::class.java.name) {
                return true
            }
        }
        return false
    }

    private fun checkAccessibilityPrompt() {
        // Ensure camera and audio runtime permissions are granted before showing accessibility prompt
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val prefs = getPreferences(MODE_PRIVATE)
        val dismissed = prefs.getBoolean("accessibility_prompt_dismissed", false)
        if (!dismissed && !isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Enable Quick Launch")
                .setMessage("Enabling Accessibility settings allows you to quickly launch Vision Guide anytime by pressing the Volume Up and Volume Down buttons simultaneously.")
                .setPositiveButton("Enable") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error opening accessibility settings", e)
                    }
                }
                .setNegativeButton("Not Now") { dialog, _ ->
                    dialog.dismiss()
                    prefs.edit().putBoolean("accessibility_prompt_dismissed", true).apply()
                    Toast.makeText(
                        this,
                        "You can always enable accessibility settings later for easier use.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .setCancelable(false)
                .show()
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun startListening(language: String?) {
            runOnUiThread {
                checkAndStartListening()
            }
        }

        @JavascriptInterface
        fun stopListening() {
            // Background continuous listening stays active
        }
    }

    inner class WebAppTTSInterface {
        @JavascriptInterface
        fun speak(text: String?, language: String?) {
            if (text.isNullOrEmpty()) return
            runOnUiThread {
                try {
                    val locale = if (language == "ur") Locale("ur", "PK") else Locale.US
                    var result = tts?.setLanguage(locale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Language $locale not supported by current engine, falling back to Locale.US")
                        result = tts?.setLanguage(Locale.US)
                    }
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VisionGuideTTS")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in TTS speak", e)
                }
            }
        }

        @JavascriptInterface
        fun isSpeaking(): Boolean {
            return tts?.isSpeaking == true
        }

        @JavascriptInterface
        fun stop() {
            runOnUiThread {
                tts?.stop()
            }
        }
    }

    private fun requestAppPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        } else {
            checkAndStartListening()
            checkAccessibilityPrompt()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            checkAndStartListening()
            checkAccessibilityPrompt()
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndStartListening()
        checkAccessibilityPrompt()
    }

    override fun onPause() {
        super.onPause()
        voskManager.stopListening()
    }

    override fun onDestroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        }
        voskManager.destroy()
        webView.destroy()
        super.onDestroy()
    }
}

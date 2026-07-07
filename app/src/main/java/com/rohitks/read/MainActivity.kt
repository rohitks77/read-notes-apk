package com.rohitks.read

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.rohitks.read.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val homeHost = "read.rohitks.com.np"
    private val startUrl = "https://read.rohitks.com.np"

    private var pendingPermissionRequest: PermissionRequest? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private var blockPullToRefresh = false

    private val fileChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        pendingPermissionRequest?.let { req ->
            val granted = req.resources.filter { res ->
                val perm = webPermissionToAndroid(res) ?: return@filter false
                grants[perm] == true
            }.toTypedArray()
            if (granted.isNotEmpty()) req.grant(granted) else req.deny()
        }
        pendingPermissionRequest = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CookieManager.getInstance().setAcceptCookie(true)

        setupWebView()
        setupOffline()
        setupBackNav()
        registerConnectivityListener()

        if (savedInstanceState == null) {
            loadOrOffline(startUrl)
        } else {
            binding.webView.restoreState(savedInstanceState)
        }
        // Safety: never let the splash linger past 8s
        binding.root.postDelayed({ hideSplash() }, 8000)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = binding.webView
        val s: WebSettings = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadsImagesAutomatically = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(false)
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Present as regular mobile Chrome so Google OAuth (and other providers) don't block us.
        // WebView's default UA contains "; wv)" which triggers "disallowed_useragent" on Google login.
        wv.setBackgroundColor(android.graphics.Color.parseColor("#0F172A"))
        s.userAgentString =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Mobile Safari/537.36 ReadNotes/1.0"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase() ?: return false

                // #3: handle non-http schemes (mailto, tel, sms, intent://, market://, geo:, etc.)
                if (scheme !in listOf("http", "https")) {
                    return handleExternalScheme(url)
                }

                val host = url.host ?: return false
                return if (host.equals(homeHost, true) || host.endsWith(".$homeHost", true)) {
                    false
                } else {
                    try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: Exception) {}
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
                setPullToRefreshBlocked(isPdfLikeUrl(url))
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
                detectEmbeddedPdfViewer(view, url)
                hideSplash()
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (isPdfLikeUrl(request?.url?.toString())) {
                    runOnUiThread { setPullToRefreshBlocked(true) }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) showOffline()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            // #6: top progress bar
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePath: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = filePath
                return try {
                    fileChooser.launch(params.createIntent())
                    true
                } catch (_: Exception) {
                    filePathCallback = null
                    false
                }
            }

            // #9: camera & mic permission bridge
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val needed = request.resources.mapNotNull { webPermissionToAndroid(it) }.distinct()
                    if (needed.isEmpty()) { request.deny(); return@runOnUiThread }
                    val missing = needed.filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) {
                        request.grant(request.resources)
                    } else {
                        pendingPermissionRequest = request
                        permissionLauncher.launch(missing.toTypedArray())
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                if (pendingPermissionRequest == request) pendingPermissionRequest = null
            }
        }

        wv.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(url))
                req.setMimeType(mimetype)
                req.addRequestHeader("User-Agent", userAgent)
                req.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            } catch (_: Exception) {}
        })

        // #2: brand-color pull-to-refresh
        binding.swipe.setColorSchemeResources(R.color.brand_accent, R.color.brand_accent_soft)
        binding.swipe.setProgressBackgroundColorSchemeResource(android.R.color.white)
        binding.swipe.setOnChildScrollUpCallback { _, _ ->
            // Returning true tells SwipeRefreshLayout that the child is still scrolling,
            // so it must NOT steal the gesture and refresh. This is stronger than
            // toggling isEnabled after scrollY changes.
            blockPullToRefresh || wv.canScrollVertically(-1) || wv.scrollY > 0
        }
        binding.swipe.setOnRefreshListener {
            if (blockPullToRefresh || wv.canScrollVertically(-1) || wv.scrollY > 0) {
                binding.swipe.isRefreshing = false
                return@setOnRefreshListener
            }
            // #13: haptic tick on pull-to-refresh trigger
            binding.swipe.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            loadOrOffline(wv.url ?: startUrl)
            binding.swipe.isRefreshing = false
        }

        wv.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    binding.swipe.requestDisallowInterceptTouchEvent(
                        blockPullToRefresh || wv.canScrollVertically(-1) || wv.scrollY > 0
                    )
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.swipe.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
    }

    private fun setPullToRefreshBlocked(blocked: Boolean) {
        blockPullToRefresh = blocked
        binding.swipe.isEnabled = !blocked
        if (blocked) binding.swipe.isRefreshing = false
    }

    private fun isPdfLikeUrl(url: String?): Boolean {
        val u = url?.lowercase().orEmpty()
        return u.contains(".pdf") ||
            u.contains("gview") ||
            u.contains("docs.google.com/viewer") ||
            u.contains("drive.google.com/viewerng") ||
            (u.contains("viewer") && u.contains("pdf"))
    }

    private fun detectEmbeddedPdfViewer(view: WebView?, fallbackUrl: String?) {
        if (view == null) {
            setPullToRefreshBlocked(isPdfLikeUrl(fallbackUrl))
            return
        }

        view.evaluateJavascript(
            """
            (function() {
                function pdfish(value) {
                    var s = String(value || '').toLowerCase();
                    return s.indexOf('.pdf') !== -1 ||
                        s.indexOf('gview') !== -1 ||
                        s.indexOf('docs.google.com/viewer') !== -1 ||
                        s.indexOf('drive.google.com/viewerng') !== -1 ||
                        (s.indexOf('viewer') !== -1 && s.indexOf('pdf') !== -1);
                }
                if (pdfish(location.href) || pdfish(document.contentType)) return true;
                var nodes = document.querySelectorAll('iframe, embed, object');
                for (var i = 0; i < nodes.length; i++) {
                    var node = nodes[i];
                    if (pdfish(node.src) || pdfish(node.data) ||
                        pdfish(node.getAttribute('src')) || pdfish(node.getAttribute('data'))) {
                        return true;
                    }
                }
                return false;
            })();
            """.trimIndent()
        ) { result ->
            setPullToRefreshBlocked(result == "true" || isPdfLikeUrl(view.url) || isPdfLikeUrl(fallbackUrl))
        }
    }

    private fun webPermissionToAndroid(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> android.Manifest.permission.CAMERA
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> android.Manifest.permission.RECORD_AUDIO
        else -> null
    }

    private fun handleExternalScheme(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        try {
            if (scheme == "intent") {
                val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                val fallback = intent.getStringExtra("browser_fallback_url")
                try { startActivity(intent) } catch (_: Exception) {
                    if (fallback != null) binding.webView.loadUrl(fallback)
                }
                return true
            }
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {}
        return true
    }

    private fun setupOffline() {
        binding.retryButton.setOnClickListener {
            // #13: haptic tick on retry
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            it.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                loadOrOffline(binding.webView.url ?: startUrl)
            }.start()
        }
    }

    private fun setupBackNav() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
            }
        })
    }

    // #4: auto-detect coming back online
    private fun registerConnectivityListener() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        connectivityCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (binding.offlineView.visibility == View.VISIBLE) {
                        loadOrOffline(binding.webView.url ?: startUrl)
                    }
                }
            }
        }
        try { cm.registerNetworkCallback(req, connectivityCallback!!) } catch (_: Exception) {}
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun loadOrOffline(url: String) {
        if (isOnline()) {
            binding.offlineView.visibility = View.GONE
            binding.webView.visibility = View.VISIBLE
            binding.webView.loadUrl(url)
        } else showOffline()
    }

    private fun showOffline() {
        hideSplash()
        binding.webView.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        val v = binding.offlineView
        if (v.visibility != View.VISIBLE) {
            v.visibility = View.VISIBLE
            binding.offlineContent.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in))
            binding.offlineIcon.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse))
        }
    }



    private var splashHidden = false
    private fun hideSplash() {
        if (splashHidden) return
        splashHidden = true
        val overlay = binding.splashOverlay
        overlay.animate()
            .alpha(0f)
            .setDuration(280)
            .withEndAction { overlay.visibility = View.GONE }
            .start()
    }

    override fun onResume() {
        super.onResume()
        // Auto-recover if internet came back while app was in background
        if (binding.offlineView.visibility == View.VISIBLE && isOnline()) {
            loadOrOffline(binding.webView.url ?: startUrl)
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        connectivityCallback?.let {
            try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }
}

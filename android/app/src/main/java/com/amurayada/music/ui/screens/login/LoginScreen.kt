package com.amurayada.music.ui.screens.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.amurayada.music.data.auth.YouTubeAuthManager

/**
 * YouTube Music Login Screen using WebView.
 * 
 * Flow:
 * 1. Load Google login page.
 * 2. User enters credentials.
 * 3. On redirect to music.youtube.com, extract cookies.
 * 4. Save cookies and navigate back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onLoginFailed: (String) -> Unit,
    onNavigateBack: () -> Unit,
    authManager: YouTubeAuthManager
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    
    val loginUrl = "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3Den%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F%26feature%3D__FEATURE__&hl=en"
    val targetUrl = "https://music.youtube.com/"
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Iniciar sesión en YouTube Music") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // WebView
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        @SuppressLint("SetJavaScriptEnabled")
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        
                        // Clear existing cookies before login and wait before loading loginUrl
                        var loginHandled = false
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                            }
                            
                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                
                                // Check if we reached YouTube Music (login success)
                                if (!loginHandled && url != null && url.startsWith(targetUrl)) {
                                    val cookies = cm.getCookie(url)
                                    if (cookies != null && cookies.contains("SID")) {
                                        loginHandled = true
                                        view?.stopLoading()
                                        cm.flush()
                                        
                                        // Offload cookie saving to IO thread to prevent ANR
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                authManager.saveCookies(cookies)
                                                withContext(Dispatchers.Main) {
                                                    onLoginSuccess()
                                                }
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                                withContext(Dispatchers.Main) {
                                                    onLoginFailed("Error saving cookies: ${e.message}")
                                                }
                                            }
                                        }
                                    } else {
                                        onLoginFailed("Failed to extract authentication cookies")
                                    }
                                }
                            }
                            
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                if (url.startsWith(targetUrl)) {
                                    return false
                                }
                                return false
                            }
                        }
                        
                        cm.removeAllCookies {
                            post { loadUrl(loginUrl) }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { webView ->
                    try {
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                        webView.removeAllViews()
                        webView.destroy()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            )
            
            // Loading indicator
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
        }
    }
}

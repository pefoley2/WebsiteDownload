package com.pefoley.websitedownload.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    mirrorId: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val mirrorsDir = File(context.filesDir, "mirrors")
    val targetDir = File(mirrorsDir, mirrorId)
    val indexFile = File(targetDir, "index.html")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline Viewer: $mirrorId") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (indexFile.exists()) {
            WebViewComponent(
                file = indexFile,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else {
            Text(
                "Mirror not found or index.html missing at ${indexFile.absolutePath}",
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewComponent(file: File, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    domStorageEnabled = true
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        // Keep navigation within the WebView if it's a local file
                        return false 
                    }
                }
            }
        },
        modifier = modifier,
        update = { webView ->
            webView.loadUrl("file://${file.absolutePath}")
        }
    )
}

@Preview(showBackground = true)
@Composable
fun ViewerScreenPreview() {
    MaterialTheme {
        ViewerScreen(mirrorId = "preview-id", onNavigateBack = {})
    }
}

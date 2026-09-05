package com.pefoley.websitedownload.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    mirrorId: String,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val mirrorsDir = File(context.filesDir, "mirrors")
    val targetDir = File(mirrorsDir, mirrorId)
    val indexFile = File(targetDir, "index.html")

    val displayTitle = remember(targetDir) {
        val metadataFile = File(targetDir, "metadata.json")
        if (metadataFile.exists()) {
            try {
                JSONObject(metadataFile.readText()).optString("url").takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        } ?: mirrorId.replace("___", "://").replace("_", "/")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
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
                        val uri = request?.url ?: return false
                        // Allow local file navigation within the mirror
                        if (uri.scheme == "file") {
                            return false
                        }
                        // Block navigating to external links
                        Toast.makeText(
                            context,
                            "External link blocked in offline mode",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return true
                    }
                }
            }
        },
        modifier = modifier,
        update = { webView -> webView.loadUrl("file://${file.absolutePath}") }
    )
}

@Preview(showBackground = true)
@Composable
fun ViewerScreenPreview() {
    MaterialTheme {
        ViewerScreen(mirrorId = "preview-id", onNavigateBack = {})
    }
}

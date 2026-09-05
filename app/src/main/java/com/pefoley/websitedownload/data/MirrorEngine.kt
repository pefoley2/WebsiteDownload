package com.pefoley.websitedownload.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File

class MirrorEngine(
    private val client: OkHttpClient,
    private val rootDir: File,
    private val onProgress: (downloadedCount: Int, currentUrl: String) -> Unit = { _, _ -> }
) {
    private val TAG = "MirrorEngine"
    private val downloadedUrls = mutableSetOf<String>()
    private val downloadMutex = Mutex()
    private var downloadedCount = 0

    /**
     * Mirrors a website starting from the given [startUrl].
     * @param startUrl The initial URL to download.
     * @param maxDepth Maximum recursion depth for internal links.
     */
    suspend fun mirror(startUrl: String, maxDepth: Int = 2) = withContext(Dispatchers.IO) {
        val httpUrl = startUrl.toHttpUrlOrNull()
        if (httpUrl == null) {
            Log.e(TAG, "Invalid start URL: $startUrl")
            return@withContext
        }
        val host = httpUrl.host
        
        downloadRecursive(httpUrl.toString(), host, 0, maxDepth)
    }

    private suspend fun downloadRecursive(url: String, host: String, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        
        val normalizedUrl = normalizeUrl(url)
        
        downloadMutex.withLock {
            if (downloadedUrls.contains(normalizedUrl)) return
            downloadedUrls.add(normalizedUrl)
            downloadedCount++
            onProgress(downloadedCount, normalizedUrl)
        }

        try {
            val response = fetchUrl(normalizedUrl) ?: return
            val contentType = response.contentType ?: ""
            val bodyBytes = response.bytes

            val localFile = getLocalFile(normalizedUrl)
            localFile.parentFile?.mkdirs()

            if (contentType.contains("text/html")) {
                val html = String(bodyBytes)
                val doc = Jsoup.parse(html, normalizedUrl)
                
                // Remap links and gather new URLs to download
                val nextUrls = remapAndCollect(doc, normalizedUrl, host)
                
                // Write remapped HTML
                localFile.writeText(doc.outerHtml())
                
                // Recursively download next URLs
                nextUrls.forEach { nextUrl ->
                    downloadRecursive(nextUrl, host, depth + 1, maxDepth)
                }
            } else {
                // For images, CSS, etc.
                localFile.writeBytes(bodyBytes)
                
                if (contentType.contains("text/css")) {
                    val css = String(bodyBytes)
                    val nextUrls = collectFromCss(css, normalizedUrl, host)
                    nextUrls.forEach { nextUrl ->
                        downloadRecursive(nextUrl, host, depth + 1, maxDepth)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download $url: ${e.message}")
        }
    }

    private fun normalizeUrl(url: String): String {
        return url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString() ?: url
    }

    private fun collectFromCss(css: String, cssUrl: String, host: String): List<String> {
        val nextUrls = mutableListOf<String>()
        val regex = Regex("""url\(['"]?([^'")]+)['"]?\)""", RegexOption.IGNORE_CASE)
        val baseHttpUrl = cssUrl.toHttpUrlOrNull() ?: return emptyList()

        regex.findAll(css).forEach { match ->
            val relUrl = match.groupValues[1].trim()
            if (!relUrl.startsWith("data:")) {
                val absUrl = baseHttpUrl.resolve(relUrl)?.toString()
                if (absUrl != null && absUrl.toHttpUrlOrNull()?.host == host) {
                    nextUrls.add(absUrl)
                }
            }
        }
        return nextUrls
    }

    private suspend fun fetchUrl(url: String): FetchResult? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                FetchResult(body.bytes(), response.header("Content-Type"))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getLocalFile(urlStr: String): File {
        val httpUrl = urlStr.toHttpUrlOrNull() ?: return File(rootDir, "error.html")
        val pathSegments = httpUrl.pathSegments
        
        var path = pathSegments.joinToString("/")
        if (path.isEmpty() || path == "/") {
            path = "index.html"
        } else if (httpUrl.encodedPath.endsWith("/")) {
            path = if (path.isEmpty()) "index.html" else "$path/index.html"
        } else if (!path.contains(".")) {
            path += ".html"
        }
        
        return File(rootDir, path)
    }

    private fun remapAndCollect(doc: Document, currentUrl: String, host: String): List<String> {
        val nextUrls = mutableListOf<String>()
        val currentFile = getLocalFile(currentUrl)

        // Helper to handle various tags
        fun remap(selector: String, attr: String) {
            doc.select(selector).forEach { element ->
                val absUrl = element.attr("abs:$attr")
                val targetHttpUrl = absUrl.toHttpUrlOrNull()
                if (targetHttpUrl != null && targetHttpUrl.host == host) {
                    val targetFile = getLocalFile(absUrl)
                    val relativePath = getRelativePath(currentFile, targetFile)
                    element.attr(attr, relativePath)
                    nextUrls.add(absUrl)
                }
            }
        }

        remap("a[href]", "href")
        remap("img[src]", "src")
        remap("link[href]", "href")
        remap("script[src]", "src")

        return nextUrls
    }

    private fun getRelativePath(fromFile: File, toFile: File): String {
        val fromPath = fromFile.parentFile?.toPath()?.toAbsolutePath() ?: rootDir.toPath().toAbsolutePath()
        val toPath = toFile.toPath().toAbsolutePath()
        
        return try {
            fromPath.relativize(toPath).toString().replace("\\", "/")
        } catch (e: Exception) {
            toFile.absolutePath.removePrefix(rootDir.absolutePath).removePrefix(File.separator).replace("\\", "/")
        }
    }

    private class FetchResult(val bytes: ByteArray, val contentType: String?)
}

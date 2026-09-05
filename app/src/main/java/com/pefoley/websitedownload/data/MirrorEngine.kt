package com.pefoley.websitedownload.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File

@Serializable
data class CacheEntry(
    val etag: String? = null,
    val lastModified: String? = null,
    val contentType: String? = null,
)

data class MirrorProgress(
    val downloadedCount: Int,
    val unchangedCount: Int,
    val currentUrl: String,
)

class MirrorEngine(
    private val client: OkHttpClient,
    private val rootDir: File,
    private val onProgress: (progress: MirrorProgress) -> Unit = {},
) {
    private val tag = "MirrorEngine"
    private val visitedUrls = mutableSetOf<String>()
    private val failures = mutableMapOf<String, String>()
    private val cacheMap = mutableMapOf<String, CacheEntry>()
    private val downloadMutex = Mutex()
    private var downloadedCount = 0
    private var unchangedCount = 0
    private val cacheFile = File(rootDir, "cache_index.json")

    init {
        loadCache()
    }

    private fun loadCache() {
        if (cacheFile.exists()) {
            try {
                val loaded = Json.decodeFromString<Map<String, CacheEntry>>(cacheFile.readText())
                cacheMap.putAll(loaded)
            } catch (e: Exception) {
                Log.w(tag, "Failed to load cache index: ${e.message}")
            }
        }
    }

    private fun saveCache() {
        try {
            cacheFile.writeText(Json.encodeToString(cacheMap))
        } catch (e: Exception) {
            Log.w(tag, "Failed to save cache index: ${e.message}")
        }
    }

    val failedUrls: Map<String, String>
        get() = failures.toMap()

    /**
     * Mirrors or refreshes a website starting from the given [startUrl].
     * @param startUrl The initial URL to download.
     * @param maxDepth Maximum recursion depth for internal links.
     * @return true if mirroring succeeded and at least one file was downloaded or already cached, false otherwise.
     */
    suspend fun mirror(startUrl: String, maxDepth: Int = 2): Boolean = withContext(Dispatchers.IO) {
        val httpUrl = startUrl.toHttpUrlOrNull()
        if (httpUrl == null) {
            Log.e(tag, "Invalid start URL: $startUrl")
            failures[startUrl] = "Invalid start URL"
            return@withContext false
        }
        val host = httpUrl.host
        
        downloadRecursive(httpUrl.toString(), host, 0, maxDepth)
        val success = downloadMutex.withLock { (downloadedCount + unchangedCount) > 0 }
        if (success) {
            saveCache()
        }
        success
    }

    private suspend fun downloadRecursive(url: String, host: String, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        
        val normalizedUrl = normalizeUrl(url)
        
        downloadMutex.withLock {
            if (visitedUrls.contains(normalizedUrl)) return
            visitedUrls.add(normalizedUrl)
        }

        val localFile = getLocalFile(normalizedUrl, host)
        val cachedEntry = downloadMutex.withLock { cacheMap[normalizedUrl] }

        try {
            val response = fetchUrl(normalizedUrl, cachedEntry, localFile.exists())
            when (response) {
                is FetchResult.Failure -> {
                    downloadMutex.withLock {
                        failures[normalizedUrl] = response.error
                    }
                    return
                }

                is FetchResult.NotModified -> {
                    val contentType = response.contentType ?: cachedEntry?.contentType ?: ""
                    downloadMutex.withLock {
                        unchangedCount++
                        onProgress(MirrorProgress(downloadedCount, unchangedCount, normalizedUrl))
                    }

                    if (contentType.contains("text/html") && localFile.exists()) {
                        val doc = Jsoup.parse(localFile.readText(), normalizedUrl)
                        val nextUrls = remapAndCollect(doc, normalizedUrl, host)
                        nextUrls.forEach { nextUrl ->
                            downloadRecursive(nextUrl, host, depth + 1, maxDepth)
                        }
                    } else if (contentType.contains("text/css") && localFile.exists()) {
                        val css = localFile.readText()
                        val nextUrls = collectFromCss(css, normalizedUrl)
                        nextUrls.forEach { nextUrl ->
                            downloadRecursive(nextUrl, host, depth + 1, maxDepth)
                        }
                    }
                }

                is FetchResult.Success -> {
                    val contentType = response.contentType ?: ""
                    val bodyBytes = response.bytes

                    localFile.parentFile?.mkdirs()

                    downloadMutex.withLock {
                        cacheMap[normalizedUrl] = CacheEntry(
                            etag = response.etag,
                            lastModified = response.lastModified,
                            contentType = contentType,
                        )
                    }

                    if (contentType.contains("text/html")) {
                        val html = String(bodyBytes)
                        val doc = Jsoup.parse(html, normalizedUrl)
                        
                        // Remap links and gather new URLs to download
                        val nextUrls = remapAndCollect(doc, normalizedUrl, host)
                        
                        // Write remapped HTML
                        localFile.writeText(doc.outerHtml())
                        
                        downloadMutex.withLock {
                            downloadedCount++
                            onProgress(MirrorProgress(downloadedCount, unchangedCount, normalizedUrl))
                        }

                        // Recursively download next URLs
                        nextUrls.forEach { nextUrl ->
                            downloadRecursive(nextUrl, host, depth + 1, maxDepth)
                        }
                    } else {
                        // For images, CSS, etc.
                        localFile.writeBytes(bodyBytes)
                        
                        downloadMutex.withLock {
                            downloadedCount++
                            onProgress(MirrorProgress(downloadedCount, unchangedCount, normalizedUrl))
                        }

                        if (contentType.contains("text/css")) {
                            val css = String(bodyBytes)
                            val nextUrls = collectFromCss(css, normalizedUrl)
                            nextUrls.forEach { nextUrl ->
                                downloadRecursive(nextUrl, host, depth + 1, maxDepth)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to download $url: ${e.message}")
            downloadMutex.withLock {
                failures[normalizedUrl] = e.message ?: "Unknown error"
            }
        }
    }

    private fun normalizeUrl(url: String): String {
        return url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString() ?: url
    }

    private fun collectFromCss(css: String, cssUrl: String): List<String> {
        val nextUrls = mutableListOf<String>()
        val regex = Regex("""url\(['"]?([^'")]+)['"]?\)""", RegexOption.IGNORE_CASE)
        val baseHttpUrl = cssUrl.toHttpUrlOrNull() ?: return emptyList()

        regex.findAll(css).forEach { match ->
            val relUrl = match.groupValues[1].trim()
            if (!relUrl.startsWith("data:")) {
                val absUrl = baseHttpUrl.resolve(relUrl)?.toString()
                if (absUrl != null) {
                    nextUrls.add(absUrl)
                }
            }
        }
        return nextUrls
    }

    private suspend fun fetchUrl(
        url: String,
        cachedEntry: CacheEntry?,
        localFileExists: Boolean,
    ): FetchResult = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url)
        if (localFileExists && cachedEntry != null) {
            cachedEntry.etag?.let { requestBuilder.header("If-None-Match", it) }
            cachedEntry.lastModified?.let { requestBuilder.header("If-Modified-Since", it) }
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.code == 304) {
                    return@withContext FetchResult.NotModified(response.header("Content-Type"))
                }
                if (!response.isSuccessful) {
                    return@withContext FetchResult.Failure("HTTP ${response.code}: ${response.message}")
                }
                val body = response.body
                FetchResult.Success(
                    bytes = body.bytes(),
                    contentType = response.header("Content-Type"),
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified"),
                )
            }
        } catch (e: Exception) {
            FetchResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun getLocalFile(urlStr: String, startHost: String = ""): File {
        val httpUrl = urlStr.toHttpUrlOrNull() ?: return File(rootDir, "error.html")
        val pathSegments = httpUrl.pathSegments
        
        var path = pathSegments.joinToString("/")
        if ((path.isEmpty()) || path == "/") {
            path = "index.html"
        } else if (httpUrl.encodedPath.endsWith("/")) {
            path = if (path.isEmpty()) "index.html" else "$path/index.html"
        } else if (!path.contains(".")) {
            path += ".html"
        }

        return if (startHost.isNotEmpty() && httpUrl.host != startHost) {
            val safeHost = httpUrl.host.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            File(File(rootDir, "_external/$safeHost"), path)
        } else {
            File(rootDir, path)
        }
    }

    private fun remapAndCollect(doc: Document, currentUrl: String, host: String): List<String> {
        val nextUrls = mutableListOf<String>()
        val currentFile = getLocalFile(currentUrl, host)

        // Helper to handle elements: remapUrls(selector, attr, isNavigationLink)
        fun remap(selector: String, attr: String, isNavigationLink: Boolean) {
            doc.select(selector).forEach { element ->
                val absUrl = element.attr("abs:$attr")
                val targetHttpUrl = absUrl.toHttpUrlOrNull() ?: return@forEach

                if (isNavigationLink) {
                    // For HTML hyperlinks (<a>): only follow and remap if on the same host
                    if (targetHttpUrl.host == host) {
                        val targetFile = getLocalFile(absUrl, host)
                        val relativePath = getRelativePath(currentFile, targetFile)
                        element.attr(attr, relativePath)
                        nextUrls.add(absUrl)
                    }
                } else {
                    // For embedded resources (img, stylesheet, script): save even if external
                    val targetFile = getLocalFile(absUrl, host)
                    val relativePath = getRelativePath(currentFile, targetFile)
                    element.attr(attr, relativePath)
                    nextUrls.add(absUrl)
                }
            }
        }

        remap("a[href]", "href", isNavigationLink = true)
        remap("img[src]", "src", isNavigationLink = false)
        remap("link[href]", "href", isNavigationLink = false)
        remap("script[src]", "src", isNavigationLink = false)

        return nextUrls
    }

    private fun getRelativePath(fromFile: File, toFile: File): String {
        val fromPath = fromFile.parentFile?.toPath()?.toAbsolutePath() ?: rootDir.toPath().toAbsolutePath()
        val toPath = toFile.toPath().toAbsolutePath()
        
        return try {
            fromPath.relativize(toPath).toString().replace("\\", "/")
        } catch (_: Exception) {
            toFile.absolutePath.removePrefix(rootDir.absolutePath).removePrefix(File.separator).replace("\\", "/")
        }
    }

    private sealed interface FetchResult {
        class Success(
            val bytes: ByteArray,
            val contentType: String?,
            val etag: String?,
            val lastModified: String?,
        ) : FetchResult
        class NotModified(val contentType: String?) : FetchResult
        class Failure(val error: String) : FetchResult
    }
}

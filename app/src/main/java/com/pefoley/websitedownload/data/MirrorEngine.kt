package com.pefoley.websitedownload.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    val children: List<String> = emptyList(),
)

data class MirrorProgress(
    val downloadedCount: Int,
    val unchangedCount: Int,
    val failedCount: Int,
    val currentUrl: String,
    val recentFailure: Pair<String, String>? = null,
    val failedUrls: Map<String, String> = emptyMap(),
)

class MirrorEngine(
    private val client: OkHttpClient,
    private val rootDir: File,
    maxConcurrency: Int = 4,
    private val onProgress: (progress: MirrorProgress) -> Unit = {},
) {
    private val tag = "MirrorEngine"
    private val visitedUrls = mutableSetOf<String>()
    private val failures = mutableMapOf<String, String>()
    private val cacheMap = mutableMapOf<String, CacheEntry>()
    private val downloadMutex = Mutex()
    private val concurrencySemaphore = Semaphore(maxConcurrency)
    private var downloadedCount = 0
    private var unchangedCount = 0
    private var lastProgressReportTime = 0L
    private val cacheFile = File(rootDir, "cache_index.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        loadCache()
    }

    private fun loadCache() {
        if (cacheFile.exists()) {
            try {
                val loaded = json.decodeFromString<Map<String, CacheEntry>>(cacheFile.readText())
                cacheMap.putAll(loaded)
            } catch (e: Exception) {
                Log.w(tag, "Failed to load cache index: ${e.message}")
            }
        }
    }

    private fun saveCache() {
        try {
            cacheFile.writeText(json.encodeToString(cacheMap))
        } catch (e: Exception) {
            Log.w(tag, "Failed to save cache index: ${e.message}")
        }
    }

    val failedUrls: Map<String, String>
        get() = failures.toMap()

    var entryPath: String = "index.html"
        private set

    /**
     * Mirrors or refreshes a website starting from the given [startUrl].
     * @param startUrl The initial URL to download.
     * @param maxDepth Maximum recursion depth for internal links.
     * @return true if mirroring succeeded and at least one file was downloaded or already cached, false otherwise.
     */
    suspend fun mirror(startUrl: String, maxDepth: Int = 2): Boolean = withContext(Dispatchers.IO) {
        val httpUrl = startUrl.toHttpUrlOrNull()
        if (httpUrl == null) {
            val error = "Invalid start URL: $startUrl"
            Log.e(tag, error)
            downloadMutex.withLock {
                failures[startUrl] = error
                notifyProgressLocked(
                    currentUrl = startUrl,
                    recentFailure = startUrl to error,
                    force = true,
                )
            }
            return@withContext false
        }
        val host = httpUrl.host
        
        downloadRecursive(httpUrl.toString(), host, 0, maxDepth)
        val success = downloadMutex.withLock {
            notifyProgressLocked(currentUrl = "", force = true)
            (downloadedCount + unchangedCount) > 0
        }
        if (success) {
            saveCache()
        }
        success
    }

    private fun notifyProgressLocked(
        currentUrl: String,
        recentFailure: Pair<String, String>? = null,
        force: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        if (force || recentFailure != null || (now - lastProgressReportTime >= 150)) {
            lastProgressReportTime = now
            onProgress(
                MirrorProgress(
                    downloadedCount = downloadedCount,
                    unchangedCount = unchangedCount,
                    failedCount = failures.size,
                    currentUrl = currentUrl,
                    recentFailure = recentFailure,
                    failedUrls = failures.toMap(),
                )
            )
        }
    }

    private suspend fun downloadRecursive(url: String, host: String, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        
        val normalizedUrl = normalizeUrl(url)
        
        val isNew = downloadMutex.withLock {
            if (visitedUrls.contains(normalizedUrl)) {
                false
            } else {
                visitedUrls.add(normalizedUrl)
                true
            }
        }
        if (!isNew) return

        val localFile = getLocalFile(normalizedUrl, host)
        val cachedEntry = downloadMutex.withLock { cacheMap[normalizedUrl] }

        try {
            var nextUrlsToProcess: List<String> = emptyList()

            concurrencySemaphore.withPermit {
                val response = fetchUrl(normalizedUrl, cachedEntry, localFile.exists(), host)
                when (response) {
                    is FetchResult.Failure -> {
                        downloadMutex.withLock {
                            failures[normalizedUrl] = response.error
                            notifyProgressLocked(
                                currentUrl = normalizedUrl,
                                recentFailure = normalizedUrl to response.error,
                                force = true,
                            )
                        }
                        return@withPermit
                    }

                    is FetchResult.NotModified -> {
                        val contentType = response.contentType ?: cachedEntry?.contentType ?: ""
                        val effectiveUrl = response.finalUrl
                        val effectiveNormalizedUrl = normalizeUrl(effectiveUrl)
                        val effectiveLocalFile = getLocalFile(effectiveNormalizedUrl, host)

                        downloadMutex.withLock {
                            unchangedCount++
                            if (depth == 0) {
                                entryPath = computeEntryPath(effectiveLocalFile)
                            }
                            notifyProgressLocked(currentUrl = effectiveNormalizedUrl)
                        }

                        if (cachedEntry != null && cachedEntry.children.isNotEmpty()) {
                            // Use stored original URLs from cache instead of re-parsing remapped local file
                            nextUrlsToProcess = cachedEntry.children
                        } else {
                            // Fallback if cache is missing children info
                            if (contentType.contains(
                                    "text/html",
                                    ignoreCase = true
                                ) && effectiveLocalFile.exists()
                            ) {
                                val doc =
                                    Jsoup.parse(effectiveLocalFile, "UTF-8", effectiveNormalizedUrl)
                                nextUrlsToProcess =
                                    remapAndCollect(doc, effectiveNormalizedUrl, host)
                            } else if (contentType.contains(
                                    "text/css",
                                    ignoreCase = true
                                ) && effectiveLocalFile.exists()
                            ) {
                                val css = effectiveLocalFile.readText()
                                val (remappedCss, nextUrls) = remapAndCollectCss(
                                    css,
                                    effectiveNormalizedUrl,
                                    host
                                )
                                effectiveLocalFile.writeText(remappedCss)
                                nextUrlsToProcess = nextUrls
                            }
                        }
                    }

                    is FetchResult.Success -> {
                        val contentType = response.contentType ?: ""
                        val effectiveUrl = response.finalUrl
                        val effectiveNormalizedUrl = normalizeUrl(effectiveUrl)
                        val effectiveLocalFile = getLocalFile(effectiveNormalizedUrl, host)

                        effectiveLocalFile.parentFile?.mkdirs()

                        var nextUrls: List<String> = emptyList()

                        if (contentType.contains("text/html", ignoreCase = true)) {
                            val doc = if (response.inMemoryText != null) {
                                Jsoup.parse(response.inMemoryText, effectiveNormalizedUrl)
                            } else {
                                Jsoup.parse(effectiveLocalFile, "UTF-8", effectiveNormalizedUrl)
                            }

                            // Remap links and gather new URLs to download
                            nextUrls = remapAndCollect(doc, effectiveNormalizedUrl, host)

                            // Write remapped HTML
                            effectiveLocalFile.writeText(doc.outerHtml())

                            // If the URL was redirected (e.g. /docs -> /docs/index.html),
                            // also write a redirect helper at the alias location so links to /docs work
                            if (effectiveLocalFile.canonicalPath != localFile.canonicalPath) {
                                try {
                                    localFile.parentFile?.mkdirs()
                                    val relToTarget = getRelativePath(localFile, effectiveLocalFile)
                                    localFile.writeText("<!DOCTYPE html><html><head><meta http-equiv=\"refresh\" content=\"0;url=$relToTarget\"></head><body></body></html>")
                                } catch (_: Exception) {
                                    // Ignore alias write failure
                                }
                            }
                        } else if (contentType.contains("text/css", ignoreCase = true)) {
                            val css = response.inMemoryText ?: effectiveLocalFile.readText()
                            val (remappedCss, urls) = remapAndCollectCss(
                                css,
                                effectiveNormalizedUrl,
                                host
                            )
                            effectiveLocalFile.writeText(remappedCss)
                            nextUrls = urls
                        }

                        downloadMutex.withLock {
                            if (effectiveNormalizedUrl != normalizedUrl) {
                                visitedUrls.add(effectiveNormalizedUrl)
                            }
                            if (depth == 0) {
                                entryPath = computeEntryPath(effectiveLocalFile)
                            }
                            cacheMap[effectiveNormalizedUrl] = CacheEntry(
                                etag = response.etag,
                                lastModified = response.lastModified,
                                contentType = contentType,
                                children = nextUrls
                            )
                            downloadedCount++
                            notifyProgressLocked(currentUrl = effectiveNormalizedUrl)
                        }
                        nextUrlsToProcess = nextUrls
                    }
                }
            }

            if (nextUrlsToProcess.isNotEmpty()) {
                coroutineScope {
                    nextUrlsToProcess.forEach { nextUrl ->
                        launch {
                            downloadRecursive(nextUrl, host, depth + 1, maxDepth)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to download $url: ${e.message}")
            val errorMsg = e.message ?: "Unknown error"
            downloadMutex.withLock {
                failures[normalizedUrl] = errorMsg
                notifyProgressLocked(
                    currentUrl = normalizedUrl,
                    recentFailure = normalizedUrl to errorMsg,
                    force = true,
                )
            }
        }
    }

    private fun computeEntryPath(file: File): String {
        return try {
            file.canonicalFile.relativeTo(rootDir.canonicalFile).path.replace('\\', '/')
        } catch (_: Exception) {
            file.absolutePath.removePrefix(rootDir.absolutePath).removePrefix(File.separator).replace('\\', '/')
        }
    }

    private fun normalizeUrl(url: String): String {
        return url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString() ?: url
    }

    private fun remapAndCollectCss(css: String, cssUrl: String, host: String): Pair<String, List<String>> {
        val nextUrls = mutableListOf<String>()
        val currentFile = getLocalFile(cssUrl, host)
        val baseHttpUrl = cssUrl.toHttpUrlOrNull() ?: return css to emptyList()
        val regex = Regex("""url\(\s*['"]?([^'")]+?)['"]?\s*\)""", RegexOption.IGNORE_CASE)

        val remappedCss = regex.replace(css) { match ->
            val rawUrl = match.groupValues[1].trim()
            if (rawUrl.startsWith("data:") || rawUrl.startsWith("#")) {
                match.value
            } else {
                val absUrl = baseHttpUrl.resolve(rawUrl)?.toString()
                if (absUrl != null) {
                    nextUrls.add(absUrl)
                    val targetFile = getLocalFile(absUrl, host)
                    val relativePath = getRelativePath(currentFile, targetFile)
                    "url(\"$relativePath\")"
                } else {
                    match.value
                }
            }
        }
        return remappedCss to nextUrls
    }

    private suspend fun fetchUrl(
        url: String,
        cachedEntry: CacheEntry?,
        localFileExists: Boolean,
        host: String,
    ): FetchResult = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url)
        if (localFileExists && cachedEntry != null) {
            cachedEntry.etag?.let { requestBuilder.header("If-None-Match", it) }
            cachedEntry.lastModified?.let { requestBuilder.header("If-Modified-Since", it) }
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val finalUrl = response.request.url.toString()
                if (response.code == 304) {
                    return@withContext FetchResult.NotModified(
                        contentType = response.header("Content-Type"),
                        finalUrl = finalUrl,
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext FetchResult.Failure("HTTP ${response.code}: ${response.message}")
                }
                val body = response.body
                val contentType = response.header("Content-Type") ?: ""
                val isTextAsset = contentType.contains("text/html", ignoreCase = true) ||
                        contentType.contains("text/css", ignoreCase = true)

                val inMemoryText = if (isTextAsset) {
                    body.string()
                } else {
                    val effectiveNormalizedUrl = normalizeUrl(finalUrl)
                    val effectiveLocalFile = getLocalFile(effectiveNormalizedUrl, host)
                    effectiveLocalFile.parentFile?.mkdirs()
                    body.byteStream().use { input ->
                        effectiveLocalFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    null
                }

                FetchResult.Success(
                    inMemoryText = inMemoryText,
                    contentType = response.header("Content-Type"),
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified"),
                    finalUrl = finalUrl,
                )
            }
        } catch (e: Exception) {
            FetchResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    fun getLocalFile(urlStr: String, startHost: String = ""): File {
        val httpUrl = urlStr.toHttpUrlOrNull() ?: return File(rootDir, "error.html")
        val pathSegments = httpUrl.pathSegments.filter { it.isNotEmpty() }
        
        var path = pathSegments.joinToString("/")
        if (path.isEmpty()) {
            path = "index.html"
        } else if (httpUrl.encodedPath.endsWith("/")) {
            path = "$path/index.html"
        } else if (!path.substringAfterLast("/").contains(".")) {
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

        // Remove any <base> tags so local relative paths work properly in offline viewer
        doc.select("base").remove()

        // Helper to handle elements: remapUrls(selector, attr, isNavigationLink)
        fun remap(selector: String, attr: String, isNavigationLink: Boolean) {
            doc.select(selector).forEach { element ->
                val rawVal = element.attr(attr).trim()
                // Pure page-internal anchors like href="#section" should remain unchanged
                if (isNavigationLink && rawVal.startsWith("#")) {
                    return@forEach
                }

                val absUrl = element.attr("abs:$attr")
                val targetHttpUrl = absUrl.toHttpUrlOrNull() ?: return@forEach

                if (isNavigationLink) {
                    // For HTML hyperlinks (<a>): only follow and remap if on the same host
                    if (targetHttpUrl.host == host) {
                        val targetFile = getLocalFile(absUrl, host)
                        val relativePath = getRelativePath(currentFile, targetFile)
                        val fragment = targetHttpUrl.fragment
                        val finalRemapped = when {
                            fragment != null && targetFile.canonicalPath == currentFile.canonicalPath -> "#$fragment"
                            fragment != null -> "$relativePath#$fragment"
                            else -> relativePath
                        }
                        element.attr(attr, finalRemapped)
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

        // Assets (img, stylesheet, script) should be processed before links to keep resource downloads predictable
        remap("link[href]", "href", isNavigationLink = false)
        remap("script[src]", "src", isNavigationLink = false)
        remap("img[src]", "src", isNavigationLink = false)
        remap("source[src]", "src", isNavigationLink = false)
        remap("video[src]", "src", isNavigationLink = false)
        remap("audio[src]", "src", isNavigationLink = false)
        remap("iframe[src]", "src", isNavigationLink = false)
        remap("a[href]", "href", isNavigationLink = true)

        return nextUrls
    }

    private fun getRelativePath(fromFile: File, toFile: File): String {
        val fromDir = (fromFile.parentFile ?: rootDir).canonicalFile
        val target = toFile.canonicalFile
        val root = rootDir.canonicalFile

        return try {
            fromDir.toPath().relativize(target.toPath()).toString().replace('\\', '/')
        } catch (_: Exception) {
            try {
                val fromToRoot = fromDir.toPath().relativize(root.toPath())
                val rootToTarget = root.toPath().relativize(target.toPath())
                fromToRoot.resolve(rootToTarget).normalize().toString().replace('\\', '/')
            } catch (_: Exception) {
                target.absolutePath.removePrefix(root.absolutePath).removePrefix(File.separator).replace('\\', '/')
            }
        }
    }

    private sealed interface FetchResult {
        class Success(
            val inMemoryText: String?,
            val contentType: String?,
            val etag: String?,
            val lastModified: String?,
            val finalUrl: String,
        ) : FetchResult
        class NotModified(val contentType: String?, val finalUrl: String) : FetchResult
        class Failure(val error: String) : FetchResult
    }
}

package com.pefoley.websitedownload.ui.viewmodels

import android.app.Application
import android.os.Parcelable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pefoley.websitedownload.data.MirrorEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File

data class MirrorState(
    val mirrors: List<MirrorItem> = emptyList(),
    val isDownloading: Boolean = false,
    val currentDownloadUrl: String = "",
    val downloadedCount: Int = 0,
    val unchangedCount: Int = 0,
    val failedCount: Int = 0,
    val activeMirrorId: String? = null,
    val inProgressFailures: Map<String, String> = emptyMap(),
    val error: String? = null,
)

@Serializable
@Parcelize
data class MirrorItem(
    val id: String,
    val url: String,
    val rootPath: String,
    val fileCount: Int = 0,
    val failureCount: Int = 0,
    val lastRefreshedAt: Long = 0L,
    val entryPath: String = "index.html",
) : Parcelable

class MirrorViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MirrorState())
    val uiState: StateFlow<MirrorState> = _uiState.asStateFlow()

    private val mirrorsDir = File(application.filesDir, "mirrors")
    private val client = OkHttpClient()

    init {
        loadMirrors()
    }

    private fun loadMirrors() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!mirrorsDir.exists()) {
                mirrorsDir.mkdirs()
            }
            val items = mirrorsDir.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory }
                ?.map { dir ->
                    val metadataFile = File(dir, "metadata.json")
                    val item = if (metadataFile.exists()) {
                        try {
                            Json.decodeFromString<MirrorItem>(metadataFile.readText())
                        } catch (_: Exception) {
                            fallbackMirrorItem(dir)
                        }
                    } else {
                        fallbackMirrorItem(dir)
                    }
                    if (item.fileCount <= 0) {
                        item.copy(fileCount = countMirrorFiles(dir))
                    } else {
                        item
                    }
                }
                ?.toList() ?: emptyList()
            _uiState.update { it.copy(mirrors = items) }
        }
    }

    private fun countMirrorFiles(dir: File): Int {
        return dir.walkTopDown().count { it.isFile && (it.name != "metadata.json") && (it.name != "cache_index.json") && (it.name != "failures.json") }
    }

    private fun fallbackMirrorItem(dir: File): MirrorItem {
        val entry = findDefaultEntryFile(dir)
        return MirrorItem(
            id = dir.name,
            url = dir.name.replace("___", "://").replace("_", "/"),
            rootPath = dir.absolutePath,
            fileCount = countMirrorFiles(dir),
            entryPath = entry,
        )
    }

    private fun findDefaultEntryFile(dir: File): String {
        val metadataFile = File(dir, "metadata.json")
        if (metadataFile.exists()) {
            try {
                val metadata = Json.decodeFromString<MirrorItem>(metadataFile.readText())
                if (metadata.entryPath.isNotBlank() && File(dir, metadata.entryPath).exists()) {
                    return metadata.entryPath
                }
            } catch (_: Exception) {
                // Ignore metadata parse failure
            }
        }
        if (File(dir, "index.html").exists()) return "index.html"
        val htmlFile = dir.walkTopDown().firstOrNull { it.isFile && it.extension.equals("html", ignoreCase = true) }
        return htmlFile?.relativeTo(dir)?.path?.replace('\\', '/') ?: "index.html"
    }

    fun deleteMirror(mirrorId: String) {
        val targetDir = File(mirrorsDir, mirrorId)
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        loadMirrors()
    }

    fun getFailedUrls(mirrorId: String): Map<String, String> {
        val targetDir = File(mirrorsDir, mirrorId)
        val failuresFile = File(targetDir, "failures.json")
        if (!failuresFile.exists()) return emptyMap()
        return try {
            Json.decodeFromString<Map<String, String>>(failuresFile.readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun startMirror(url: String, onSuccess: (mirrorId: String) -> Unit = {}) {
        val mirrorId = url.replace(Regex("[^a-zA-Z0-9]"), "_")
        if (_uiState.value.mirrors.any { (it.id == mirrorId) || it.url.equals(url, ignoreCase = true) }) {
            _uiState.update { it.copy(error = "This website has already been mirrored.") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isDownloading = true,
                    error = null,
                    downloadedCount = 0,
                    unchangedCount = 0,
                    failedCount = 0,
                    inProgressFailures = emptyMap(),
                    activeMirrorId = mirrorId,
                )
            }
            val targetDir = File(mirrorsDir, mirrorId)
            
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val dummyEngine = MirrorEngine(client, targetDir)
            val localStartFile = dummyEngine.getLocalFile(url)
            val relativeEntryPath = try {
                localStartFile.relativeTo(targetDir).path.replace('\\', '/')
            } catch (_: Exception) {
                "index.html"
            }
            
            // Save metadata
            val item = MirrorItem(
                id = mirrorId,
                url = url,
                rootPath = targetDir.absolutePath,
                lastRefreshedAt = System.currentTimeMillis(),
                entryPath = relativeEntryPath,
            )
            try {
                File(targetDir, "metadata.json").writeText(Json.encodeToString(item))
            } catch (_: Exception) {
                // Ignore metadata write failure for now
            }
            
            val engine = MirrorEngine(client, targetDir) { progress ->
                _uiState.update { current ->
                    current.copy(
                        downloadedCount = progress.downloadedCount,
                        unchangedCount = progress.unchangedCount,
                        failedCount = progress.failedCount,
                        currentDownloadUrl = progress.currentUrl,
                        inProgressFailures = if (progress.failedUrls.isNotEmpty()) {
                            progress.failedUrls
                        } else if (progress.recentFailure != null) {
                            current.inProgressFailures + progress.recentFailure
                        } else {
                            current.inProgressFailures
                        },
                    )
                }
            }

            var mirrorCreated = false
            try {
                val success = engine.mirror(url)
                val finalCount = countMirrorFiles(targetDir)
                val failures = engine.failedUrls
                val normalizedStartUrl = url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString() ?: url

                if (!success || (finalCount == 0)) {
                    targetDir.deleteRecursively()
                    val actualError = failures[url]
                        ?: failures[normalizedStartUrl]
                        ?: failures.values.firstOrNull()
                        ?: "Could not download site. The URL may be invalid or unreachable."
                    _uiState.value = _uiState.value.copy(error = actualError)
                } else {
                    mirrorCreated = true
                    val verifiedEntryPath = if (engine.entryPath.isNotBlank() && File(targetDir, engine.entryPath).exists()) {
                        engine.entryPath
                    } else if (File(targetDir, relativeEntryPath).exists()) {
                        relativeEntryPath
                    } else {
                        findDefaultEntryFile(targetDir)
                    }
                    val updatedItem = item.copy(
                        fileCount = finalCount,
                        failureCount = failures.size,
                        lastRefreshedAt = System.currentTimeMillis(),
                        entryPath = verifiedEntryPath,
                    )
                    try {
                        File(targetDir, "metadata.json").writeText(Json.encodeToString(updatedItem))
                        if (failures.isNotEmpty()) {
                            File(targetDir, "failures.json").writeText(Json.encodeToString(failures))
                        }
                    } catch (_: Exception) {
                        // Ignore metadata write failure
                    }
                    loadMirrors()
                    withContext(Dispatchers.Main) {
                        onSuccess(mirrorId)
                    }
                }
            } catch (e: Exception) {
                if (!mirrorCreated) {
                    targetDir.deleteRecursively()
                }
                _uiState.update { it.copy(error = e.message ?: "Download failed") }
            } finally {
                _uiState.update { it.copy(isDownloading = false, activeMirrorId = null) }
            }
        }
    }

    fun refreshMirror(mirrorId: String, onSuccess: () -> Unit = {}) {
        val existingItem = _uiState.value.mirrors.find { it.id == mirrorId } ?: return
        val targetDir = File(mirrorsDir, mirrorId)
        if (!targetDir.exists()) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isDownloading = true,
                    error = null,
                    downloadedCount = 0,
                    unchangedCount = 0,
                    failedCount = 0,
                    inProgressFailures = emptyMap(),
                    activeMirrorId = mirrorId,
                )
            }

            val engine = MirrorEngine(client, targetDir) { progress ->
                _uiState.update { current ->
                    current.copy(
                        downloadedCount = progress.downloadedCount,
                        unchangedCount = progress.unchangedCount,
                        failedCount = progress.failedCount,
                        currentDownloadUrl = progress.currentUrl,
                        inProgressFailures = if (progress.failedUrls.isNotEmpty()) {
                            progress.failedUrls
                        } else if (progress.recentFailure != null) {
                            current.inProgressFailures + progress.recentFailure
                        } else {
                            current.inProgressFailures
                        },
                    )
                }
            }

            try {
                val success = engine.mirror(existingItem.url)
                val finalCount = countMirrorFiles(targetDir)
                val failures = engine.failedUrls

                if (success) {
                    val verifiedEntryPath = if (engine.entryPath.isNotBlank() && File(targetDir, engine.entryPath).exists()) {
                        engine.entryPath
                    } else if (File(targetDir, existingItem.entryPath).exists()) {
                        existingItem.entryPath
                    } else {
                        findDefaultEntryFile(targetDir)
                    }
                    val updatedItem = existingItem.copy(
                        fileCount = finalCount,
                        failureCount = failures.size,
                        lastRefreshedAt = System.currentTimeMillis(),
                        entryPath = verifiedEntryPath,
                    )
                    try {
                        File(targetDir, "metadata.json").writeText(Json.encodeToString(updatedItem))
                        File(targetDir, "failures.json").writeText(Json.encodeToString(failures))
                    } catch (_: Exception) {
                        // Ignore metadata write failure
                    }
                    loadMirrors()
                    withContext(Dispatchers.Main) {
                        onSuccess()
                    }
                } else {
                    val normalizedExistingUrl = existingItem.url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString() ?: existingItem.url
                    val actualError = failures[existingItem.url]
                        ?: failures[normalizedExistingUrl]
                        ?: failures.values.firstOrNull()
                        ?: "Refresh failed: could not reach host"
                    _uiState.update { it.copy(error = actualError) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Refresh failed") }
            } finally {
                _uiState.update { it.copy(isDownloading = false, activeMirrorId = null) }
            }
        }
    }
}

package com.pefoley.websitedownload.ui.viewmodels

import android.app.Application
import android.os.Parcelable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pefoley.websitedownload.data.MirrorEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File

data class MirrorState(
    val mirrors: List<MirrorItem> = emptyList(),
    val isDownloading: Boolean = false,
    val currentDownloadUrl: String = "",
    val downloadedCount: Int = 0,
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
        _uiState.value = _uiState.value.copy(mirrors = items)
    }

    private fun countMirrorFiles(dir: File): Int {
        return dir.walkTopDown().count { it.isFile && (it.name != "metadata.json") }
    }

    private fun fallbackMirrorItem(dir: File): MirrorItem {
        return MirrorItem(
            id = dir.name,
            url = dir.name.replace("___", "://").replace("_", "/"),
            rootPath = dir.absolutePath,
            fileCount = countMirrorFiles(dir),
        )
    }

    fun deleteMirror(mirrorId: String) {
        val targetDir = File(mirrorsDir, mirrorId)
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        loadMirrors()
    }

    fun startMirror(url: String, onSuccess: (mirrorId: String) -> Unit = {}) {
        val mirrorId = url.replace(Regex("[^a-zA-Z0-9]"), "_")
        if (_uiState.value.mirrors.any { (it.id == mirrorId) || it.url.equals(url, ignoreCase = true) }) {
            _uiState.value = _uiState.value.copy(error = "This website has already been mirrored.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDownloading = true, error = null, downloadedCount = 0)
            val targetDir = File(mirrorsDir, mirrorId)
            
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            
            // Save metadata
            val item = MirrorItem(id = mirrorId, url = url, rootPath = targetDir.absolutePath)
            try {
                File(targetDir, "metadata.json").writeText(Json.encodeToString(item))
            } catch (_: Exception) {
                // Ignore metadata write failure for now
            }
            
            val engine = MirrorEngine(client, targetDir) { count, currentUrl ->
                _uiState.value = _uiState.value.copy(
                    downloadedCount = count,
                    currentDownloadUrl = currentUrl,
                )
            }

            var mirrorCreated = false
            try {
                val success = engine.mirror(url)
                val finalCount = countMirrorFiles(targetDir)
                val failures = engine.failedUrls

                if (!success || (finalCount == 0)) {
                    targetDir.deleteRecursively()
                    val errorMessage = failures[url] ?: "Could not download site. The URL may be invalid or unreachable."
                    _uiState.value = _uiState.value.copy(error = errorMessage)
                } else {
                    mirrorCreated = true
                    val updatedItem = item.copy(
                        fileCount = finalCount,
                        failureCount = failures.size,
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
                    onSuccess(mirrorId)
                }
            } catch (e: Exception) {
                if (!mirrorCreated) {
                    targetDir.deleteRecursively()
                }
                _uiState.value = _uiState.value.copy(error = e.message ?: "Download failed")
            } finally {
                _uiState.value = _uiState.value.copy(isDownloading = false)
            }
        }
    }
}

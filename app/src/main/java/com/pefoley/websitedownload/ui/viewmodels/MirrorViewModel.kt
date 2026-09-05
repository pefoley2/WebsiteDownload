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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File

data class MirrorState(
    val mirrors: List<MirrorItem> = emptyList(),
    val isDownloading: Boolean = false,
    val currentDownloadUrl: String = "",
    val downloadedCount: Int = 0,
    val error: String? = null
)

@Serializable
@Parcelize
data class MirrorItem(
    val id: String,
    val url: String,
    val rootPath: String
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
        val items = mirrorsDir.listFiles()?.filter { it.isDirectory }?.map { dir ->
            val metadataFile = File(dir, "metadata.json")
            if (metadataFile.exists()) {
                try {
                    Json.decodeFromString<MirrorItem>(metadataFile.readText())
                } catch (e: Exception) {
                    fallbackMirrorItem(dir)
                }
            } else {
                fallbackMirrorItem(dir)
            }
        } ?: emptyList()
        _uiState.value = _uiState.value.copy(mirrors = items)
    }

    private fun fallbackMirrorItem(dir: File): MirrorItem {
        return MirrorItem(
            id = dir.name,
            url = dir.name.replace("___", "://").replace("_", "/"),
            rootPath = dir.absolutePath
        )
    }

    fun startMirror(url: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDownloading = true, error = null, downloadedCount = 0)
            val mirrorId = url.replace(Regex("[^a-zA-Z0-9]"), "_")
            val targetDir = File(mirrorsDir, mirrorId)
            
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            
            // Save metadata
            val item = MirrorItem(id = mirrorId, url = url, rootPath = targetDir.absolutePath)
            try {
                File(targetDir, "metadata.json").writeText(Json.encodeToString(item))
            } catch (e: Exception) {
                // Ignore metadata write failure for now
            }
            
            val engine = MirrorEngine(client, targetDir) { count, currentUrl ->
                _uiState.value = _uiState.value.copy(
                    downloadedCount = count,
                    currentDownloadUrl = currentUrl
                )
            }

            try {
                engine.mirror(url)
                loadMirrors()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            } finally {
                _uiState.value = _uiState.value.copy(isDownloading = false)
            }
        }
    }
}

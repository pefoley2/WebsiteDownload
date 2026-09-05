package com.pefoley.websitedownload.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
data class WebsiteMirror(
    val id: String,
    val url: String,
    val name: String,
    val status: MirrorStatus,
    val progress: Float = 0f,
    val localPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable

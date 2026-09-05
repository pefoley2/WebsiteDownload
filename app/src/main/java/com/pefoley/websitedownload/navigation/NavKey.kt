package com.pefoley.websitedownload.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface WebsiteNavKey : NavKey

@Serializable
data object Dashboard : WebsiteNavKey

@Serializable
data class Viewer(val mirrorId: String) : WebsiteNavKey

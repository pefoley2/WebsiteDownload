package com.pefoley.websitedownload

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pefoley.websitedownload.navigation.NavigationGraph
import com.pefoley.websitedownload.ui.theme.WebsiteDownloadTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WebsiteDownloadTheme {
                NavigationGraph()
            }
        }
    }
}

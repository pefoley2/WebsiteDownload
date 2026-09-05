package com.pefoley.websitedownload.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pefoley.websitedownload.ui.viewmodels.MirrorItem
import com.pefoley.websitedownload.ui.viewmodels.MirrorViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun DashboardScreen(
    onNavigateToViewer: (String) -> Unit,
    viewModel: MirrorViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()

    BackHandler(enabled = navigator.canNavigateBack()) {
        scope.launch {
            navigator.navigateBack()
        }
    }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane {
                ListPaneContent(
                    items = uiState.mirrors,
                    onItemClick = { item ->
                        scope.launch {
                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, item.id)
                        }
                    },
                    onAddClick = {
                        scope.launch {
                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, null)
                        }
                    }
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val selectedItemId = navigator.currentDestination?.contentKey
                val selectedItem = uiState.mirrors.find { it.id == selectedItemId }
                DetailPaneContent(
                    selectedItem = selectedItem,
                    isDownloading = uiState.isDownloading,
                    currentDownloadUrl = uiState.currentDownloadUrl,
                    downloadedCount = uiState.downloadedCount,
                    error = uiState.error,
                    onStartMirror = { url -> viewModel.startMirror(url) },
                    onOpenMirror = { item -> onNavigateToViewer(item.id) }
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListPaneContent(
    items: List<MirrorItem>,
    onItemClick: (MirrorItem) -> Unit,
    onAddClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Mirrored Sites") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = "Add Website")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(items) { item ->
                ListItem(
                    headlineContent = { Text(item.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("ID: ${item.id}") },
                    leadingContent = { Icon(Icons.Default.Language, contentDescription = null) },
                    modifier = Modifier.clickable { onItemClick(item) }
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailPaneContent(
    selectedItem: MirrorItem?,
    isDownloading: Boolean,
    currentDownloadUrl: String,
    downloadedCount: Int,
    error: String?,
    onStartMirror: (String) -> Unit,
    onOpenMirror: (MirrorItem) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (selectedItem == null) "Add New Mirror" else "Mirror Details") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (selectedItem == null) {
                URLInputSection(
                    isDownloading = isDownloading,
                    currentDownloadUrl = currentDownloadUrl,
                    downloadedCount = downloadedCount,
                    error = error,
                    onStartMirror = onStartMirror
                )
            } else {
                MirrorInfoSection(
                    item = selectedItem,
                    onOpenMirror = onOpenMirror
                )
            }
        }
    }
}

@Composable
private fun URLInputSection(
    isDownloading: Boolean,
    currentDownloadUrl: String,
    downloadedCount: Int,
    error: String?,
    onStartMirror: (String) -> Unit
) {
    var url by rememberSaveable { mutableStateOf("https://") }

    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Website URL") },
        modifier = Modifier.fillMaxWidth(),
        enabled = !isDownloading
    )

    if (error != null) Text(error, color = MaterialTheme.colorScheme.error)

    if (isDownloading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text("Downloading: $downloadedCount files")
        Text(
            currentDownloadUrl,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    } else {
        Button(
            onClick = { onStartMirror(url) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Mirroring")
        }
    }
}

@Composable
private fun MirrorInfoSection(
    item: MirrorItem,
    onOpenMirror: (MirrorItem) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("URL: ${item.url}", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Storage Path: ${item.rootPath}", style = MaterialTheme.typography.bodySmall)
        }
    }

    Button(
        onClick = { onOpenMirror(item) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Open Offline Content")
    }
}

@Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,orientation=landscape")
@Composable
fun DashboardScreenPreview() {
    MaterialTheme {
        DashboardScreen(onNavigateToViewer = {})
    }
}

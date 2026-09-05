package com.pefoley.websitedownload.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
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
    var isAddingSite by rememberSaveable { mutableStateOf(value = false) }

    fun navigateBackFromDetail() {
        isAddingSite = false
        scope.launch {
            if (navigator.canNavigateBack()) {
                navigator.navigateBack(BackNavigationBehavior.PopUntilScaffoldValueChange)
            } else {
                navigator.navigateTo(ListDetailPaneScaffoldRole.List)
            }
        }
    }

    BackHandler(enabled = isAddingSite || navigator.canNavigateBack()) {
        navigateBackFromDetail()
    }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane {
                ListPaneContent(
                    items = uiState.mirrors,
                    onItemClick = { item ->
                        isAddingSite = false
                        scope.launch {
                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, item.id)
                        }
                    },
                    onAddClick = {
                        isAddingSite = true
                        scope.launch {
                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
                        }
                    },
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val selectedItemId = navigator.currentDestination?.contentKey
                val selectedItem = if (isAddingSite) null else uiState.mirrors.find { it.id == selectedItemId }
                DetailPaneContent(
                    selectedItem = selectedItem,
                    isAdding = isAddingSite,
                    isDownloading = uiState.isDownloading,
                    currentDownloadUrl = uiState.currentDownloadUrl,
                    downloadedCount = uiState.downloadedCount,
                    error = uiState.error,
                    onStartMirror = { url ->
                        viewModel.startMirror(url) { newMirrorId ->
                            isAddingSite = false
                            scope.launch {
                                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, newMirrorId)
                            }
                        }
                    },
                    onOpenMirror = { item -> onNavigateToViewer(item.id) },
                    onDeleteMirror = { item ->
                        viewModel.deleteMirror(item.id)
                        navigateBackFromDetail()
                    },
                    getFailedUrls = { mirrorId -> viewModel.getFailedUrls(mirrorId) },
                    showBackButton = isAddingSite || navigator.canNavigateBack(),
                    onNavigateBack = { navigateBackFromDetail() },
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
                    supportingContent = {
                        Text(
                            when (item.fileCount) {
                                1 -> "1 file downloaded"
                                else -> "${item.fileCount} files downloaded"
                            }
                        )
                    },
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
    isAdding: Boolean,
    isDownloading: Boolean,
    currentDownloadUrl: String,
    downloadedCount: Int,
    error: String?,
    onStartMirror: (String) -> Unit,
    onOpenMirror: (MirrorItem) -> Unit,
    onDeleteMirror: (MirrorItem) -> Unit,
    getFailedUrls: (String) -> Map<String, String>,
    showBackButton: Boolean = false,
    onNavigateBack: () -> Unit = {},
) {
    val titleText = when {
        isAdding -> "Add New Mirror"
        selectedItem != null -> "Mirror Details"
        else -> ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleText) },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isAdding) {
                URLInputSection(
                    isDownloading = isDownloading,
                    currentDownloadUrl = currentDownloadUrl,
                    downloadedCount = downloadedCount,
                    error = error,
                    onStartMirror = onStartMirror,
                )
            } else if (selectedItem != null) {
                MirrorInfoSection(
                    item = selectedItem,
                    onOpenMirror = onOpenMirror,
                    onDeleteMirror = onDeleteMirror,
                    getFailedUrls = getFailedUrls,
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
    onStartMirror: (String) -> Unit,
) {
    var urlValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val initialText = "https://"
        mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length)))
    }

    OutlinedTextField(
        value = urlValue,
        onValueChange = { urlValue = it },
        label = { Text("Website URL") },
        modifier = Modifier.fillMaxWidth(),
        enabled = !isDownloading,
        isError = error != null,
        supportingText = if (error != null) {
            { Text(error, color = MaterialTheme.colorScheme.error) }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(
            onGo = {
                if (!isDownloading && urlValue.text.isNotBlank()) {
                    onStartMirror(urlValue.text)
                }
            },
        ),
    )

    if (isDownloading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text("Downloading: $downloadedCount files")
        Text(
            currentDownloadUrl,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        Button(
            onClick = { onStartMirror(urlValue.text) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start Mirroring")
        }
    }
}

@Composable
private fun MirrorInfoSection(
    item: MirrorItem,
    onOpenMirror: (MirrorItem) -> Unit,
    onDeleteMirror: (MirrorItem) -> Unit,
    getFailedUrls: (String) -> Map<String, String> = { emptyMap() },
) {
    var showDeleteConfirmDialog by remember { mutableStateOf(value = false) }
    var showFailuresDialog by remember { mutableStateOf(value = false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("URL: ${item.url}", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Downloaded: ${item.fileCount} files", style = MaterialTheme.typography.bodyMedium)
            if (item.failureCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Failed downloads: ${item.failureCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = { showFailuresDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("View Details")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Storage Path: ${item.rootPath}", style = MaterialTheme.typography.bodySmall)
        }
    }

    Button(
        onClick = { onOpenMirror(item) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Open Offline Content")
    }

    OutlinedButton(
        onClick = { showDeleteConfirmDialog = true },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) {
        Icon(Icons.Default.Delete, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Delete Mirror")
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Mirror") },
            text = { Text("Are you sure you want to delete the mirror for \"${item.url}\"? This will permanently delete all downloaded files.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteMirror(item)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showFailuresDialog) {
        val failedUrls = remember(item.id) { getFailedUrls(item.id) }
        AlertDialog(
            onDismissRequest = { showFailuresDialog = false },
            title = { Text("Failed Downloads (${failedUrls.size})") },
            text = {
                if (failedUrls.isEmpty()) {
                    Text("No failure details available.")
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 350.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(failedUrls.toList()) { (url, error) ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            ) {
                                Text(
                                    text = url,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFailuresDialog = false }) {
                    Text("Close")
                }
            },
        )
    }
}

@Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,orientation=landscape")
@Composable
fun DashboardScreenPreview() {
    MaterialTheme {
        DashboardScreen(onNavigateToViewer = {})
    }
}

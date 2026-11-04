package com.gmodkit.tools.ui

import android.net.Uri
import com.gmodkit.tools.network.SteamApps
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gmodkit.tools.MainActivity
import com.gmodkit.tools.MainViewModel
import com.gmodkit.tools.ProgressState
import com.gmodkit.tools.ResultState
import com.gmodkit.tools.SelectedFile
import com.gmodkit.tools.UiState
import com.gmodkit.tools.VpkFolderFile
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GmadScreen(activity: MainActivity, viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    var showSplash by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(1100)
        showSplash = false
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Gmad",
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    actions = {
                        IconButton(onClick = viewModel::notif_on) {
                            Icon(Icons.Default.Info, contentDescription = "About")
                        }
                        IconButton(onClick = viewModel::notiff_onn) {
                            Icon(Icons.Default.Warning, contentDescription = "Errors")
                        }
                        IconButton(onClick = viewModel::openSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        ) { padding ->
            val scroll = rememberScrollState()
            LaunchedEffect(state.tab) { scroll.scrollTo(0) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Tabs(state = state, viewModel = viewModel)
                Crossfade(
                    targetState = state.tab,
                    modifier = Modifier.weight(1f),
                    animationSpec = tween(180),
                    label = "tab"
                ) { tab ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (tab) {
                            0 -> GmaTab(state, viewModel, activity)
                            1 -> VpkTab(state, viewModel, activity)
                            else -> WorkshopTab(state, viewModel)
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = showSplash,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(350))
        ) {
            SplashScreen(onDismiss = { showSplash = false })
        }
    }

    state.progress?.let { progress ->
        ProgressDialog(progress = progress, viewModel = viewModel)
    }
    state.result?.let { result ->
        ResultDialog(result = result, activity = activity, viewModel = viewModel)
    }
    if (state.showInfo) {
        AboutDialog(state = state, viewModel = viewModel)
    }
    if (state.showErrors) {
        ErrorsDialog(viewModel)
    }
    if (state.showSettings) {
        SettingsDialog(state = state, viewModel = viewModel, activity = activity)
    }
    if (state.showStartup && !showSplash) {
        StartupDialog(state = state, viewModel = viewModel)
    }
    if (state.showVpkPicker) {
        VpkPickerDialog(files = state.vpkFolderFiles, viewModel = viewModel)
    }
}

@Composable
private fun SplashScreen(onDismiss: () -> Unit) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onDismiss)
    ) {
        AnimatedVisibility(
            visible = entered,
            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { -it / 8 },
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GmadLogo(size = 52.dp)
                Column {
                    Text(
                        text = "Gmad",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Garry's Mod addon tools",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.Center)
                .size(30.dp),
            strokeWidth = 2.dp
        )
    }
}

@Composable
private fun Tabs(state: UiState, viewModel: MainViewModel) {
    TabRow(selectedTabIndex = state.tab) {
        Tab(
            selected = state.tab == 0,
            onClick = viewModel::switch_to_main_layout,
            text = { Text("GMA") }
        )
        Tab(
            selected = state.tab == 1,
            onClick = viewModel::switch_to_second_layout,
            text = { Text("VPK") }
        )
        Tab(
            selected = state.tab == 2,
            onClick = viewModel::switch_to_workshop_layout,
            text = { Text("Workshop") }
        )
    }
}

@Composable
private fun GmaTab(state: UiState, viewModel: MainViewModel, activity: MainActivity) {
    FileCard(
        title = "Addon file",
        selected = state.gmaFile,
        preparing = state.preparing,
        busy = state.busy,
        note = "Pick a .gma file, or a compressed Workshop addon (.bin, .xz, .gz, .zip).",
        onChoose = activity::show_file_manager
    )

    state.gmaMeta?.let { meta ->
        InfoCard(title = "Addon details") {
            InfoRow("Name", meta.displayName)
            InfoRow("Author", meta.addonAuthor.ifBlank { "Unknown" })
            InfoRow("Addon version", meta.addonVersion.toString())
            InfoRow("Format", "GMAD v${meta.formatVersion}")
            InfoRow("Files", meta.files.size.toString())
            InfoRow("Unpacked size", formatBytes(meta.totalContentSize))
            InfoRow(
                "Required content",
                meta.requiredContent.joinToString(", ").ifBlank { "None" }
            )
        }
    }

    state.gmaDescription?.let { description ->
        if (description.description.isNotBlank() ||
            description.type.isNotBlank() ||
            description.tags.isNotEmpty()
        ) {
            InfoCard(title = "Description") {
                if (description.type.isNotBlank()) {
                    InfoRow("Type", description.type)
                }
                if (description.tags.isNotEmpty()) {
                    InfoRow("Tags", description.tags.joinToString(", "))
                }
                if (description.description.isNotBlank()) {
                    Text(
                        text = description.description,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    OutputCard(state = state, activity = activity)
    ExtractButton(
        enabled = state.gmaMeta != null && !state.busy && !state.preparing,
        onClick = viewModel::on_clickk
    )
    LastOutputCard(state = state, activity = activity)
}

@Composable
private fun VpkTab(state: UiState, viewModel: MainViewModel, activity: MainActivity) {
    FileCard(
        title = "VPK file",
        selected = state.vpkFile,
        preparing = state.preparing,
        busy = state.busy,
        note = "Pick a VPK file, or choose the folder for a multi-part VPK.",
        onChoose = activity::show_file_manager,
        onChooseFolder = activity::show_vpk_folder_picker
    )

    state.vpkFolderLabel?.let { folder ->
        Text(
            text = "Folder: $folder",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    state.vpkMeta?.let { meta ->
        InfoCard(title = "VPK details") {
            InfoRow(
                "Format",
                "VPK v${meta.version}" + if (meta.signed) " (signed)" else ""
            )
            InfoRow("Files", meta.fileCount.toString())
            InfoRow("Unpacked size", formatBytes(meta.totalSize))
            InfoRow("Directory size", formatBytes(meta.treeSize))
            InfoRow(
                "External parts",
                if (meta.externalArchives.isEmpty()) {
                    "None"
                } else {
                    meta.externalArchives.size.toString()
                }
            )
            if (meta.externalArchives.isNotEmpty()) {
                Text(
                    text = "If extraction reports a missing part, choose the folder that contains every VPK part.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    OutputCard(state = state, activity = activity)
    ExtractButton(
        enabled = state.vpkMeta != null && !state.busy && !state.preparing,
        onClick = viewModel::on_clickkkk
    )
    LastOutputCard(state = state, activity = activity)
}

@Composable
private fun WorkshopTab(state: UiState, viewModel: MainViewModel) {
    InfoCard(title = "Steam Workshop") {
        Text(
            text = "Paste a Workshop item URL or numeric ID. Metadata comes from Steam's public API.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = state.workshopUrl,
            onValueChange = viewModel::setWorkshopUrl,
            label = { Text("Workshop URL or ID") },
            placeholder = {
                Text("https://steamcommunity.com/sharedfiles/filedetails/?id=...")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = viewModel::fetchWorkshop,
                enabled = !state.workshopLoading && !state.busy
            ) {
                Text("Fetch info")
            }
            if (state.workshopLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
        }
        state.workshopError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    state.workshopInfo?.let { info ->
        InfoCard(title = info.title) {
            Text(
                text = "Addon",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            val steamApp = info.appId
            if (steamApp > 0) {
                InfoRow("Steam app", SteamApps.label(steamApp))
            }
            when (SteamApps.canDownload(steamApp)) {
                true -> Text(
                    text = "${SteamApps.label(steamApp)} Workshop content downloads without an account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                false -> Text(
                    text = "Steam only gives ${SteamApps.label(steamApp)} content to accounts that " +
                        "own it, so this item cannot be downloaded here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                null -> if (steamApp > 0) {
                    Text(
                        text = "This app has not been checked yet, so the download will just try.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            RemotePreviewImage(
                url = info.previewUrl,
                contentDescription = "Preview of ${info.title}",
                modifier = Modifier.fillMaxWidth()
            )
            InfoRow("Creator", info.creator.ifBlank { "Unknown" })
            InfoRow("Size", formatBytes(info.fileSize))
            InfoRow("Updated", formatDate(info.updatedAt))
            if (info.description.isNotBlank()) {
                Text(
                    text = info.description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HorizontalDivider()
            val canDownload = info.manifestGid > 0L ||
                info.directUrl != null ||
                state.workshopApiBase.isNotBlank()
            Button(
                onClick = viewModel::on_submit,
                enabled = canDownload && !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Download and extract")
            }
            OutlinedButton(
                onClick = viewModel::download_workshop_only,
                enabled = canDownload && !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Download only")
            }
            Text(
                text = "Download only saves the addon file as it is, without unpacking it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!canDownload) {
                Text(
                    text = "Steam has no depot content for this item, so the phone cannot " +
                        "fetch it from the content network. Add a resolver URL in Settings " +
                        "if you run one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = viewModel::openWorkshopPage,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Workshop page")
            }
        }
    }

    OutputCard(state = state, activity = null)
    Text(
        text = "The app does not ask for your Steam account and does not bypass Steam ownership checks.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    LastOutputCard(state = state, activity = null)
}

@Composable
private fun FileCard(
    title: String,
    selected: SelectedFile?,
    preparing: Boolean,
    busy: Boolean,
    note: String,
    onChoose: () -> Unit,
    onChooseFolder: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (selected != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selected.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatBytes(selected.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = onChoose,
                        enabled = !busy && !preparing
                    ) {
                        Text("Change")
                    }
                }
            } else {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (preparing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "Reading file...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onChoose,
                    enabled = !busy && !preparing
                ) {
                    Text(if (selected == null) "Choose file" else "Choose another")
                }
                if (onChooseFolder != null) {
                    OutlinedButton(
                        onClick = onChooseFolder,
                        enabled = !busy && !preparing
                    ) {
                        Text("Choose folder")
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.width(112.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun OutputCard(state: UiState, activity: MainActivity?) {
    InfoCard(title = "Output") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Save to",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = state.outputLabel,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (activity != null) {
                TextButton(onClick = activity::show_output_folder_picker) {
                    Text("Change")
                }
            }
        }
        Text(
            text = "ZIP files are written directly to this folder. " +
                "Choose a folder if you want the ZIP to appear in Files or Downloads.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExtractButton(enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Text("Extract to ZIP", style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun LastOutputCard(state: UiState, activity: MainActivity?) {
    val output = state.lastOutput ?: return
    if (state.busy) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Last export",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = output.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (activity != null) {
                TextButton(onClick = { activity.shareOutput(output.uri, output.name) }) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }
        }
    }
}

@Composable
private fun ProgressDialog(progress: ProgressState, viewModel: MainViewModel) {
    val fraction = if (progress.total > 0) {
        (progress.done.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(progress.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = progress.phase,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (progress.indeterminate) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (progress.total > 0) {
                    Text(
                        text = "${formatBytes(progress.done)} / ${formatBytes(progress.total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Please wait...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::cancelWork) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ResultDialog(
    result: ResultState,
    activity: MainActivity,
    viewModel: MainViewModel
) {
    val isError = result.title == "Error"
    AlertDialog(
        onDismissRequest = viewModel::dismissResult,
        title = { Text(result.title) },
        text = { Text(result.message) },
        confirmButton = {
            TextButton(onClick = viewModel::dismissResult) {
                Text(if (isError) "OK" else "Done")
            }
        },
        dismissButton = {
            Row {
                if (isError) {
                    TextButton(onClick = viewModel::open_errors_help) {
                        Text("Help")
                    }
                }
                if (result.shareUri != null) {
                    TextButton(
                        onClick = {
                            activity.shareOutput(
                                result.shareUri,
                                result.shareName
                            )
                        }
                    ) {
                        Text("Share")
                    }
                }
            }
        }
    )
}

@Composable
private fun AboutDialog(state: UiState, viewModel: MainViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissInfo,
        title = { Text("About Gmad") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Version ${state.versionName} (${state.versionCode})",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "A local toolkit for Garry's Mod addons (.gma), Valve Pak files (.vpk), " +
                        "and Garry's Mod Workshop items.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Pick a file, check the details, then extract to a ZIP. Everything is " +
                        "processed on your device and your files are not uploaded. Workshop " +
                        "metadata comes from Steam's public API. The app never asks for a Steam " +
                        "account and has no ads or tracking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                Text("Credits", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Created and maintained by boo271.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Help and bug reports: boo271 on Discord.",
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider()
                Text("Open source", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Gmad is open source under the MIT License. It includes zstd " +
                        "(Apache-2.0), XZ for Java (public domain), and AndroidX, Jetpack " +
                        "Compose, Kotlin, and Kotlin Coroutines (Apache-2.0). Full notices " +
                        "are in the NOTICE file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Unofficial app. Not affiliated with, endorsed by, or sponsored by " +
                        "Valve Corporation or Facepunch Studios. Not the gmad tool bundled with " +
                        "Valve's SDK. Steam, Garry's Mod, and Valve are trademarks of Valve " +
                        "Corporation. Addon content belongs to its creators.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::dismissInfo) {
                Text("OK")
            }
        }
    )
}

@Composable
private fun ErrorsDialog(viewModel: MainViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissErrors,
        title = { Text("Help") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("How to use", style = MaterialTheme.typography.titleSmall)
                Text(
                    "1. Pick a .gma file, or choose the folder that holds a .vpk.\n" +
                        "2. Check the details.\n" +
                        "3. Tap Extract to ZIP.\n" +
                        "4. Use Share to send the ZIP somewhere.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("GMA", style = MaterialTheme.typography.titleSmall)
                Text(
                    "If the app says \"not a GMAD file\", the file may be compressed. " +
                        "This app reads .bin (LZMA), .xz, .gz, and .zip containers automatically. " +
                        "If it still fails, extract the file with a file archiver, then pick the .gma.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("VPK", style = MaterialTheme.typography.titleSmall)
                Text(
                    "For a multi-part VPK, choose the folder that contains the _dir.vpk file " +
                        "and every numbered part. Do not rename the parts.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("Output", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The app uses the system file picker. If the ZIP does not appear where you expect, " +
                        "choose an output folder in Settings. On Android 11 and newer, apps cannot freely " +
                        "write to the root of internal storage.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("Workshop", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Steam serves most Workshop items as depot content, which this app " +
                        "downloads the way SteamCMD does, with no account. An item Steam " +
                        "does not publish that way needs a resolver URL in Settings. " +
                        "The app does not bypass Steam ownership checks.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Bug reports: boo271 on Discord.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::dismissErrors) {
                Text("OK")
            }
        }
    )
}

@Composable
private fun SettingsDialog(
    state: UiState,
    viewModel: MainViewModel,
    activity: MainActivity
) {
    var workshopApi by remember { mutableStateOf(state.workshopApiBase) }

    AlertDialog(
        onDismissRequest = viewModel::dismissSettings,
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Output folder", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.outputLabel,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = activity::show_output_folder_picker) {
                        Text("Change")
                    }
                }

                HorizontalDivider()

                Text("Workshop resolver (optional)", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = workshopApi,
                    onValueChange = { workshopApi = it },
                    label = { Text("Resolver URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Only needed for Workshop items that Steam does not publish as depot " +
                        "content. The app calls {url}/resolve and expects download_url in the response.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()
                TextButton(onClick = viewModel::resetStartupMessage) {
                    Text("Show startup message again")
                }
                Text(
                    text = "Version ${state.versionName} (${state.versionCode})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (viewModel.saveWorkshopSettings(workshopApi)) {
                        viewModel.dismissSettings()
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissSettings) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun StartupDialog(state: UiState, viewModel: MainViewModel) {
    AlertDialog(
        onDismissRequest = {},
        title = null,
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GmadLogo(size = 48.dp)
                    Text(
                        "Gmad 2.1",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    "Native Kotlin rewrite.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "New VPK reader with version 1 and 2, preload data, embedded data, " +
                        "and multi-part archive support.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Compressed GMA support: .bin (LZMA), .xz, .gz, and .zip.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "SteamCMD support.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.startupDontShow,
                        onCheckedChange = viewModel::setStartupDontShow
                    )
                    Text(
                        "Don't show this again",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::close_startup_dialog) {
                Text("OK")
            }
        }
    )
}

@Composable
private fun VpkPickerDialog(files: List<VpkFolderFile>, viewModel: MainViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissVpkPicker,
        title = { Text("Choose a VPK") },
        text = {
            if (files.isEmpty()) {
                Text("No VPK files found.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(files) { file ->
                        TextButton(
                            onClick = { viewModel.pickVpkFromFolder(file) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatBytes(file.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::dismissVpkPicker) {
                Text("Cancel")
            }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "Unknown"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}

private fun formatDate(millis: Long): String {
    if (millis <= 0) return "Unknown"
    return try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
    } catch (_: Exception) {
        "Unknown"
    }
}


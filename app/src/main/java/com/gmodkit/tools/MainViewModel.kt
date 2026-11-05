package com.gmodkit.tools

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gmodkit.tools.android.AndroidSources
import com.gmodkit.tools.android.DocumentInfo
import com.gmodkit.tools.core.ExtractionStats
import com.gmodkit.tools.core.FileSource
import com.gmodkit.tools.core.GmaFormatException
import com.gmodkit.tools.core.GmaMetadata
import com.gmodkit.tools.core.GmaOpener
import com.gmodkit.tools.core.OpenedGma
import com.gmodkit.tools.core.ProgressSink
import com.gmodkit.tools.core.SeekableSource
import com.gmodkit.tools.core.VpkArchive
import com.gmodkit.tools.core.VpkFormatException
import com.gmodkit.tools.core.VpkMetadata
import com.gmodkit.tools.core.VpkMissingArchiveException
import com.gmodkit.tools.core.ZipNames
import com.gmodkit.tools.network.Http
import com.gmodkit.tools.network.UpdateChecker
import com.gmodkit.tools.network.UpdateRelease
import com.gmodkit.tools.network.WorkshopClient
import com.gmodkit.tools.network.WorkshopException
import com.gmodkit.tools.network.WorkshopInfo
import com.gmodkit.tools.network.cleanWorkshopDescription
import com.gmodkit.tools.network.SteamApps
import com.gmodkit.tools.steam.DepotAccessDeniedException
import com.gmodkit.tools.steam.WorkshopDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipOutputStream

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val size: Long
)

data class OutputRef(
    val uri: Uri,
    val name: String
)

data class ProgressState(
    val title: String,
    val phase: String,
    val done: Long,
    val total: Long,
    val indeterminate: Boolean
)

data class ResultState(
    val title: String,
    val message: String,
    val shareUri: Uri? = null,
    val shareName: String? = null
)

data class VpkFolderFile(
    val uri: Uri,
    val name: String,
    val size: Long
)

data class GmaDescription(
    val description: String = "",
    val type: String = "",
    val tags: List<String> = emptyList()
)

data class UiState(
    val tab: Int = 0,
    val gmaFile: SelectedFile? = null,
    val gmaMeta: GmaMetadata? = null,
    val gmaDescription: GmaDescription? = null,
    val vpkFile: SelectedFile? = null,
    val vpkMeta: VpkMetadata? = null,
    val preparing: Boolean = false,
    val busy: Boolean = false,
    val progress: ProgressState? = null,
    val result: ResultState? = null,
    val outputTreeUri: Uri? = null,
    val outputLabel: String = "App storage",
    val lastOutput: OutputRef? = null,
    val showInfo: Boolean = false,
    val showErrors: Boolean = false,
    val showSettings: Boolean = false,
    val showStartup: Boolean = false,
    val startupDontShow: Boolean = false,
    val vpkFolderUri: Uri? = null,
    val vpkFolderLabel: String? = null,
    val vpkFolderFiles: List<VpkFolderFile> = emptyList(),
    val showVpkPicker: Boolean = false,
    val workshopUrl: String = "",
    val workshopInfo: WorkshopInfo? = null,
    val workshopLoading: Boolean = false,
    val workshopError: String? = null,
    val workshopApiBase: String = "",
    val updatesEnabled: Boolean = true,
    val updateChecking: Boolean = false,
    val availableUpdate: UpdateRelease? = null,
    val updateStatus: String = "",
    val lastUpdateCheck: Long = 0L,
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cacheDir = File(application.cacheDir, "gmad")
    private val workshopClient = WorkshopClient()
    private val workshopDownloader = WorkshopDownloader()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var openedGma: OpenedGma? = null
    private var vpkArchive: VpkArchive? = null

    private var prepareJob: Job? = null
    private var extractJob: Job? = null
    private var workshopJob: Job? = null
    private var updateJob: Job? = null

    private var prepareGeneration = 0
    private var workshopGeneration = 0
    private var lastProgressUpdate = 0L

    init {
        ensure_storage_permissions()
        val tree = loadOutputTree()
        val workshopApi = prefs.getString(KEY_WORKSHOP_API, null)
            ?: BuildConfig.WORKSHOP_API_BASE

        _state.update {
            it.copy(
                outputTreeUri = tree,
                outputLabel = outputLabel(tree),
                workshopApiBase = workshopApi,
                updatesEnabled = prefs.getBoolean(KEY_UPDATES_ENABLED, true),
                lastUpdateCheck = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
            )
        }

        maybe_show_startup_dialog()
        maybeCheckForUpdates()
    }

    /**
     * The app uses the system file picker, so it does not need all-files
     * access. This only prepares the app-specific fallback output folder.
     */
    fun ensure_storage_permissions() {
        try {
            cacheDir.mkdirs()
            val root = getApplication<Application>().getExternalFilesDir(null)
            if (root != null) {
                File(root, OUTPUT_DIR).mkdirs()
            }
        } catch (_: Exception) {
        }
    }

    fun on_select(uri: Uri) {
        if (_state.value.busy) return
        AndroidSources.takeReadPermission(getApplication(), uri)
        when (_state.value.tab) {
            0 -> prepareGma(uri)
            else -> prepareVpk(uri, null)
        }
    }

    /**
     * Handles a file opened from another app (for example a file manager).
     * The tab is chosen from the file name so .vpk files open in the VPK tab.
     */
    fun openExternal(uri: Uri) {
        if (_state.value.busy) return
        val info = AndroidSources.query(getApplication(), uri)
        val lower = info.name.lowercase(Locale.US)
        val tab = if (lower.endsWith(".vpk")) 1 else 0
        _state.update { it.copy(tab = tab, result = null) }
        on_select(uri)
    }

    fun setVpkFolder(uri: Uri) {
        if (_state.value.busy) return
        AndroidSources.takeReadPermission(getApplication(), uri)
        prepareJob?.cancel()
        val generation = ++prepareGeneration
        _state.update {
            it.copy(
                preparing = true,
                progress = ProgressState("Preparing...", "Reading folder", 0, 0, true),
                result = null,
                showVpkPicker = false
            )
        }

        prepareJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val folder = DocumentFile.fromTreeUri(context, uri)
                    ?: throw IOException("Could not open that folder.")
                val files = folder.listFiles()
                    .asSequence()
                    .filter { it.isFile && it.name?.endsWith(".vpk", ignoreCase = true) == true }
                    .map {
                        VpkFolderFile(
                            uri = it.uri,
                            name = it.name ?: "file.vpk",
                            size = it.length()
                        )
                    }
                    .sortedBy { it.name.lowercase(Locale.US) }
                    .toList()

                if (files.isEmpty()) {
                    throw IOException("No .vpk files were found in that folder.")
                }

                if (generation != prepareGeneration) return@launch
                _state.update {
                    it.copy(
                        preparing = false,
                        progress = null,
                        vpkFolderUri = uri,
                        vpkFolderLabel = folder.name ?: "Selected folder",
                        vpkFolderFiles = files,
                        showVpkPicker = true
                    )
                }
            } catch (e: CancellationException) {
                if (generation == prepareGeneration) {
                    _state.update { it.copy(preparing = false, progress = null) }
                }
            } catch (e: Exception) {
                if (generation == prepareGeneration) {
                    _state.update {
                        it.copy(preparing = false, progress = null)
                    }
                    show_dialogg("Error", friendlyMessage(e))
                }
            }
        }
    }

    fun pickVpkFromFolder(file: VpkFolderFile) {
        _state.update { it.copy(showVpkPicker = false) }
        prepareVpk(file.uri, _state.value.vpkFolderUri)
    }

    fun dismissVpkPicker() {
        _state.update { it.copy(showVpkPicker = false) }
    }

    private fun prepareGma(uri: Uri) {
        prepareJob?.cancel()
        openedGma?.close()
        openedGma = null

        val generation = ++prepareGeneration
        val context = getApplication<Application>()
        val info = AndroidSources.query(context, uri)
        _state.update {
            it.copy(
                gmaFile = SelectedFile(uri, info.name, info.size),
                gmaMeta = null,
                gmaDescription = null,
                preparing = true,
                progress = ProgressState("Preparing...", "Reading file", 0, 0, true),
                result = null,
                lastOutput = null
            )
        }

        prepareJob = viewModelScope.launch(Dispatchers.IO) {
            var source: SeekableSource? = null
            var opened: OpenedGma? = null
            val active = activeCheck()
            try {
                source = AndroidSources.open(
                    context,
                    uri,
                    info.name,
                    cacheDir,
                    progressSink("Reading"),
                    active
                )
                val gma = GmaOpener.open(
                    source,
                    cacheDir,
                    progressSink("Unpacking"),
                    active
                )
                opened = gma
                source = null

                if (generation != prepareGeneration) {
                    gma.close()
                    return@launch
                }
                openedGma = gma
                _state.update {
                    it.copy(
                        gmaMeta = gma.metadata,
                        gmaDescription = parseGmaDescription(gma.metadata.addonDescription),
                        preparing = false,
                        progress = null
                    )
                }
            } catch (e: CancellationException) {
                if (opened != null) opened.close() else source?.close()
                if (generation == prepareGeneration) {
                    _state.update {
                        it.copy(
                            gmaFile = null,
                            gmaMeta = null,
                            gmaDescription = null,
                            preparing = false,
                            progress = null
                        )
                    }
                }
            } catch (e: Exception) {
                if (opened != null) opened.close() else source?.close()
                if (generation == prepareGeneration) {
                    _state.update {
                        it.copy(
                            gmaFile = null,
                            gmaMeta = null,
                            gmaDescription = null,
                            preparing = false,
                            progress = null
                        )
                    }
                    show_dialogg("Error", friendlyMessage(e))
                }
            }
        }
    }

    private fun prepareVpk(uri: Uri, folderUri: Uri?) {
        prepareJob?.cancel()
        vpkArchive?.close()
        vpkArchive = null

        val generation = ++prepareGeneration
        val context = getApplication<Application>()
        var finalUri = uri
        var info = AndroidSources.query(context, uri)
        val wasNumbered = VpkArchive.numberedIndex(info.name) != null

        if (wasNumbered) {
            val resolved = AndroidSources.resolveVpkDirUri(context, uri, info.name, folderUri)
            if (resolved != null) {
                finalUri = resolved.first
                val resolvedInfo = AndroidSources.query(context, finalUri)
                info = DocumentInfo(
                    name = resolved.second,
                    size = resolvedInfo.size
                )
            }
        }

        val folderLabel = if (folderUri != null) _state.value.vpkFolderLabel else null
        _state.update {
            it.copy(
                vpkFile = SelectedFile(finalUri, info.name, info.size),
                vpkMeta = null,
                preparing = true,
                progress = ProgressState("Preparing...", "Reading VPK directory", 0, 0, true),
                result = null,
                lastOutput = null,
                vpkFolderUri = folderUri,
                vpkFolderLabel = folderLabel
            )
        }

        val selectedUri = finalUri
        val selectedName = info.name
        prepareJob = viewModelScope.launch(Dispatchers.IO) {
            var source: SeekableSource? = null
            var archive: VpkArchive? = null
            val active = activeCheck()
            try {
                source = AndroidSources.open(
                    context,
                    selectedUri,
                    selectedName,
                    cacheDir,
                    progressSink("Reading"),
                    active
                )
                val vpk = VpkArchive.open(source)
                archive = vpk
                source = null

                if (generation != prepareGeneration) {
                    vpk.close()
                    return@launch
                }
                vpkArchive = vpk
                _state.update {
                    it.copy(
                        vpkMeta = vpk.metadata,
                        preparing = false,
                        progress = null
                    )
                }
            } catch (e: CancellationException) {
                if (archive != null) archive.close() else source?.close()
                if (generation == prepareGeneration) {
                    _state.update {
                        it.copy(
                            vpkFile = null,
                            vpkMeta = null,
                            preparing = false,
                            progress = null
                        )
                    }
                }
            } catch (e: Exception) {
                if (archive != null) archive.close() else source?.close()
                if (generation == prepareGeneration) {
                    _state.update {
                        it.copy(
                            vpkFile = null,
                            vpkMeta = null,
                            preparing = false,
                            progress = null
                        )
                    }
                    if (wasNumbered) {
                        val base = VpkArchive.baseNameFor(selectedName)
                        show_dialogg(
                            "Error",
                            "This looks like a VPK data part, not the directory file.\n\n" +
                                "Choose the folder that contains ${VpkArchive.dirFileName(base)} " +
                                "and all of its numbered parts."
                        )
                    } else {
                        show_dialogg("Error", friendlyMessage(e))
                    }
                }
            }
        }
    }

    fun on_clickk() {
        val file = _state.value.gmaFile
        if (file == null) {
            show_dialogg("Error", "Choose a GMA file first.")
            return
        }
        on_button_press(file.name)
    }

    fun on_clickkkk() {
        val file = _state.value.vpkFile
        if (file == null) {
            show_dialogg("Error", "Choose a VPK file first.")
            return
        }
        extract_vpk(file.name)
    }

    fun on_button_press(value: String) {
        val opened = openedGma
        if (opened == null) {
            show_dialogg("Error", "The GMA file is not ready. Choose it again.")
            return
        }
        startGmaExtraction(value, opened)
    }

    fun extract_vpk(valuee: String) {
        val archive = vpkArchive
        val dirUri = _state.value.vpkFile?.uri
        if (archive == null || dirUri == null) {
            show_dialogg("Error", "The VPK file is not ready. Choose it again.")
            return
        }
        startVpkExtraction(valuee, archive, dirUri)
    }

    private fun startGmaExtraction(baseName: String, opened: OpenedGma) {
        startExtraction(baseName) { zip, active ->
            opened.extract(zip, progressSink("Extracting"), active)
        }
    }

    private fun startVpkExtraction(baseName: String, archive: VpkArchive, dirUri: Uri) {
        startExtraction(baseName) { zip, active ->
            val archives = openVpkArchives(archive, dirUri, active)
            try {
                archive.extract(zip, archives, progressSink("Extracting"), active)
            } finally {
                archives.values.forEach { source ->
                    try {
                        source.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun startExtraction(
        baseName: String,
        block: (ZipOutputStream, () -> Boolean) -> ExtractionStats
    ) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, result = null) }
        show_dialog()

        extractJob = viewModelScope.launch(Dispatchers.IO) {
            val active = activeCheck()
            var output: OutputHandle? = null
            try {
                val target = createOutput(baseName)
                output = target

                val stats = ZipOutputStream(
                    BufferedOutputStream(target.stream, 64 * 1024)
                ).use { zip ->
                    zip.setLevel(Deflater.BEST_SPEED)
                    val result = block(zip, active)
                    zip.finish()
                    result
                }

                val ref = OutputRef(target.shareUri, target.name)
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        lastOutput = ref,
                        result = ResultState(
                            title = "Extracted!",
                            message = buildResultMessage(target.locationLabel, stats),
                            shareUri = target.shareUri,
                            shareName = target.name
                        )
                    )
                }
            } catch (e: CancellationException) {
                output?.delete()
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        result = ResultState("Cancelled", "Extraction was cancelled.")
                    )
                }
            } catch (e: Exception) {
                output?.delete()
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        result = ResultState("Error", friendlyMessage(e))
                    )
                }
            } finally {
                try {
                    output?.stream?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun openVpkArchives(
        archive: VpkArchive,
        dirUri: Uri,
        active: () -> Boolean
    ): Map<Int, SeekableSource> {
        val sources = LinkedHashMap<Int, SeekableSource>()
        try {
            for (index in archive.metadata.externalArchives) {
                val fileName = VpkArchive.archiveFileName(archive.baseName, index)
                sources[index] = AndroidSources.resolveVpkPart(
                    getApplication(),
                    dirUri,
                    _state.value.vpkFolderUri,
                    fileName,
                    cacheDir,
                    progressSink("Opening VPK part"),
                    active
                )
            }
            return sources
        } catch (e: Exception) {
            sources.values.forEach {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }
            throw e
        }
    }

    private fun createOutput(
        baseName: String,
        extension: String = "zip",
        mimeType: String = "application/zip"
    ): OutputHandle {
        val context = getApplication<Application>()
        val safe = ZipNames.sanitizeFileName(baseName, "addon")
        val suffix = ".$extension"
        val outName = if (safe.endsWith(suffix, ignoreCase = true)) safe else safe + suffix

        val treeUri = _state.value.outputTreeUri
        if (treeUri != null) {
            val folder = DocumentFile.fromTreeUri(context, treeUri)
            if (folder == null || !folder.canWrite()) {
                throw IOException("The output folder is not writable. Choose another folder in Settings.")
            }
            val document = folder.createFile(mimeType, outName)
                ?: throw IOException("Could not create the output file in the output folder.")
            val stream = context.contentResolver.openOutputStream(document.uri, "w")
                ?: throw IOException("Could not open the output file for writing.")
            return OutputHandle(
                shareUri = document.uri,
                name = document.name ?: outName,
                stream = stream,
                locationLabel = folder.name ?: "the selected folder",
                delete = {
                    try {
                        document.delete()
                    } catch (_: Exception) {
                    }
                }
            )
        }

        val root = context.getExternalFilesDir(null)
            ?: throw IOException("External storage is not available.")
        val folder = File(root, OUTPUT_DIR)
        if (!folder.exists() && !folder.mkdirs()) {
            throw IOException("Could not create the output folder.")
        }

        var file = File(folder, outName)
        var counter = 2
        while (file.exists()) {
            val stem = outName.removeSuffix(suffix)
            file = File(folder, "$stem ($counter)$suffix")
            counter++
        }

        val stream = FileOutputStream(file)
        val share = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return OutputHandle(
            shareUri = share,
            name = file.name,
            stream = stream,
            locationLabel = "app storage ($OUTPUT_DIR)",
            delete = {
                try {
                    file.delete()
                } catch (_: Exception) {
                }
            }
        )
    }

    fun setOutputTree(uri: Uri) {
        AndroidSources.takeWritePermission(getApplication(), uri)
        val folder = DocumentFile.fromTreeUri(getApplication(), uri)
        if (folder != null && !folder.canWrite()) {
            show_dialogg("Error", "That folder is read-only. Choose another folder.")
            return
        }
        prefs.edit().putString(KEY_OUTPUT_TREE, uri.toString()).apply()
        _state.update {
            it.copy(
                outputTreeUri = uri,
                outputLabel = outputLabel(uri)
            )
        }
    }

    private fun loadOutputTree(): Uri? {
        val raw = prefs.getString(KEY_OUTPUT_TREE, null) ?: return null
        val uri = try {
            Uri.parse(raw)
        } catch (_: Exception) {
            return null
        }
        val allowed = getApplication<Application>().contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        return if (allowed) uri else null
    }

    private fun outputLabel(uri: Uri?): String {
        if (uri == null) return "App storage"
        val folder = try {
            DocumentFile.fromTreeUri(getApplication(), uri)
        } catch (_: Exception) {
            null
        }
        return folder?.name ?: "Selected folder"
    }

    fun notif_on() {
        _state.update { it.copy(showInfo = true) }
    }

    fun notiff_onn() {
        _state.update { it.copy(showErrors = true) }
    }

    fun dismissInfo() {
        _state.update { it.copy(showInfo = false) }
    }

    fun dismissErrors() {
        _state.update { it.copy(showErrors = false) }
    }

    fun open_errors_help() {
        _state.update { it.copy(result = null, showErrors = true) }
    }

    fun show_dialog() {
        _state.update {
            it.copy(
                progress = ProgressState(
                    title = "Extracting...",
                    phase = "Starting",
                    done = 0,
                    total = 0,
                    indeterminate = true
                )
            )
        }
    }

    fun show_dialog22() {
        _state.update {
            it.copy(
                progress = ProgressState(
                    title = "Downloading...",
                    phase = "Starting",
                    done = 0,
                    total = 0,
                    indeterminate = true
                )
            )
        }
    }

    fun update_progress(phase: String, done: Long, total: Long) {
        if (!_should_report(done, total)) return
        _state.update { current ->
            val progress = current.progress
                ?: ProgressState("Working...", phase, done, total, total <= 0)
            current.copy(
                progress = progress.copy(
                    phase = phase,
                    done = done,
                    total = total,
                    indeterminate = total <= 0
                )
            )
        }
    }

    fun _should_report(done: Long, total: Long): Boolean {
        val now = System.currentTimeMillis()
        if (done <= 1 || (total > 0 && done >= total) || now - lastProgressUpdate >= 150) {
            lastProgressUpdate = now
            return true
        }
        return false
    }

    fun show_dialogg(title: String, text: String) {
        _state.update {
            it.copy(
                progress = null,
                result = ResultState(title, text)
            )
        }
    }

    fun dismissResult() {
        _state.update { it.copy(result = null) }
    }

    fun cancelWork() {
        prepareJob?.cancel()
        extractJob?.cancel()
        workshopJob?.cancel()
    }

    fun switch_to_second_layout() {
        _state.update { it.copy(tab = 1, result = null) }
    }

    fun switch_to_main_layout() {
        _state.update { it.copy(tab = 0, result = null) }
    }

    fun switch_to_workshop_layout() {
        _state.update { it.copy(tab = 2, result = null) }
    }

    fun setWorkshopUrl(value: String) {
        _state.update { it.copy(workshopUrl = value, workshopError = null) }
    }

    fun fetchWorkshop() {
        val input = _state.value.workshopUrl.trim()
        if (input.isEmpty()) {
            _state.update { it.copy(workshopError = "Enter a Workshop URL or ID.") }
            return
        }
        if (_state.value.busy) return

        workshopJob?.cancel()
        val generation = ++workshopGeneration
        _state.update {
            it.copy(
                workshopLoading = true,
                workshopError = null,
                workshopInfo = null
            )
        }

        workshopJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = workshopClient.fetch(input)
                if (generation != workshopGeneration) return@launch
                _state.update {
                    it.copy(
                        workshopLoading = false,
                        workshopInfo = info,
                        workshopError = null
                    )
                }
            } catch (e: CancellationException) {
                if (generation == workshopGeneration) {
                    _state.update { it.copy(workshopLoading = false) }
                }
            } catch (e: Exception) {
                if (generation == workshopGeneration) {
                    _state.update {
                        it.copy(
                            workshopLoading = false,
                            workshopError = friendlyMessage(e)
                        )
                    }
                }
            }
        }
    }

    /**
     * Downloads a Workshop item and extracts it to a ZIP. Addons that Steam
     * stores as depot content come straight from the content network; anything
     * else falls back to a direct URL or the optional resolver in Settings.
     * The app never handles Steam credentials.
     */
    fun on_submit() {
        val info = _state.value.workshopInfo ?: run {
            show_dialogg("Error", "Fetch the Workshop item first.")
            return
        }
        if (_state.value.busy) return

        _state.update { it.copy(busy = true, result = null) }
        show_dialog22()

        extractJob = viewModelScope.launch(Dispatchers.IO) {
            val active = activeCheck()
            var downloaded: File? = null
            var opened: OpenedGma? = null
            var output: OutputHandle? = null
            try {
                val file = File.createTempFile("workshop_", ".bin", cacheDir)
                downloaded = file
                downloadWorkshopItem(info, file, active)

                _state.update {
                    it.copy(
                        progress = it.progress?.copy(
                            title = "Extracting...",
                            phase = "Opening addon",
                            done = 0,
                            total = 0,
                            indeterminate = true
                        )
                    )
                }

                val gma = GmaOpener.open(
                    FileSource(file),
                    cacheDir,
                    progressSink("Unpacking"),
                    active
                )
                opened = gma
                val baseName = info.title.ifBlank {
                    info.fileName?.substringBeforeLast('.') ?: "workshop_${info.id}"
                }
                val target = createOutput(baseName)
                output = target

                val stats = ZipOutputStream(
                    BufferedOutputStream(target.stream, 64 * 1024)
                ).use { zip ->
                    zip.setLevel(Deflater.BEST_SPEED)
                    val result = gma.extract(zip, progressSink("Extracting"), active)
                    zip.finish()
                    result
                }

                val ref = OutputRef(target.shareUri, target.name)
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        lastOutput = ref,
                        result = ResultState(
                            title = "Extracted!",
                            message = buildResultMessage(target.locationLabel, stats),
                            shareUri = target.shareUri,
                            shareName = target.name
                        )
                    )
                }
            } catch (e: CancellationException) {
                output?.delete()
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        result = ResultState("Cancelled", "The download was cancelled.")
                    )
                }
            } catch (e: Exception) {
                output?.delete()
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        result = ResultState("Error", friendlyMessage(e))
                    )
                }
            } finally {
                try {
                    opened?.close()
                } catch (_: Exception) {
                }
                try {
                    downloaded?.delete()
                } catch (_: Exception) {
                }
                try {
                    output?.stream?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Downloads a Workshop item and saves the addon file as it is, without
     * unpacking it. The result is a plain .gma that Garry's Mod reads directly.
     */
    fun download_workshop_only() {
        val info = _state.value.workshopInfo ?: run {
            show_dialogg("Error", "Fetch the Workshop item first.")
            return
        }
        if (_state.value.busy) return

        _state.update { it.copy(busy = true, result = null) }
        show_dialog22()

        extractJob = viewModelScope.launch(Dispatchers.IO) {
            val active = activeCheck()
            var downloaded: File? = null
            var output: OutputHandle? = null
            try {
                val file = File.createTempFile("workshop_", ".bin", cacheDir)
                downloaded = file
                downloadWorkshopItem(info, file, active)

                _state.update {
                    it.copy(
                        progress = it.progress?.copy(
                            title = "Saving...",
                            phase = "Writing file",
                            done = 0,
                            total = file.length(),
                            indeterminate = false
                        )
                    )
                }

                val baseName = info.title.ifBlank {
                    info.fileName?.substringBeforeLast('.') ?: "workshop_${info.id}"
                }
                val target = createOutput(baseName, "gma", "application/octet-stream")
                output = target
                copyFile(file, target.stream, progressSink("Saving"), active)

                val ref = OutputRef(target.shareUri, target.name)
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        lastOutput = ref,
                        result = ResultState(
                            title = "Downloaded!",
                            message = "Saved ${target.name} to ${target.locationLabel}.",
                            shareUri = target.shareUri,
                            shareName = target.name
                        )
                    )
                }
            } catch (e: CancellationException) {
                output?.delete()
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        result = ResultState("Cancelled", "The download was cancelled.")
                    )
                }
            } catch (e: Exception) {
                output?.delete()
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        result = ResultState("Error", friendlyMessage(e))
                    )
                }
            } finally {
                try {
                    downloaded?.delete()
                } catch (_: Exception) {
                }
                try {
                    output?.stream?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Fetches the item bytes into [file]. Addons that Steam stores as depot
     * content come from the content network; anything else falls back to a
     * direct URL or the optional resolver in Settings. The app never handles
     * Steam credentials.
     */
    private suspend fun downloadWorkshopItem(
        info: WorkshopInfo,
        file: File,
        active: () -> Boolean
    ) {
        if (SteamApps.canDownload(info.appId) == false) {
            throw WorkshopException(
                "Steam only gives ${SteamApps.label(info.appId)} content to accounts that own it, " +
                    "and this app signs in without an account. Look for the same addon on the " +
                    "Garry's Mod Workshop."
            )
        }
        if (info.manifestGid > 0L) {
            workshopDownloader.download(info, file, progressSink("Downloading"), active)
        } else {
            val url = info.directUrl
                ?: workshopClient.resolve(_state.value.workshopApiBase, info.workshopUrl)
                ?: throw WorkshopException(
                    "Steam does not provide a direct download for this item.\n\n" +
                        "Add a resolver URL in Settings, or open the Workshop page."
                )
            workshopClient.download(url, file, progressSink("Downloading"), active)
        }
    }

    private fun copyFile(
        source: File,
        target: OutputStream,
        sink: ProgressSink,
        active: () -> Boolean
    ) {
        val total = source.length()
        FileInputStream(source).use { input ->
            BufferedOutputStream(target, 64 * 1024).use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    if (!active()) throw CancellationException("Cancelled")
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    sink.onProgress(done, total)
                }
                output.flush()
            }
        }
    }

    fun openWorkshopPage() {
        val info = _state.value.workshopInfo
        val url = info?.workshopUrl
        if (url != null) {
            openUrl(url)
        }
    }

    fun openSettings() {
        _state.update { it.copy(showSettings = true) }
    }

    fun dismissSettings() {
        _state.update { it.copy(showSettings = false) }
    }

    fun saveWorkshopSettings(workshopApi: String): Boolean {
        val newWorkshopApi = workshopApi.trim()
        if (newWorkshopApi.isNotEmpty() && !Http.isAllowedLocalUrl(newWorkshopApi)) {
            show_dialogg("Error", "The resolver URL must use HTTPS or a private network address.")
            return false
        }

        prefs.edit().putString(KEY_WORKSHOP_API, newWorkshopApi).apply()
        _state.update { it.copy(workshopApiBase = newWorkshopApi) }
        return true
    }

    fun setUpdatesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATES_ENABLED, enabled).apply()
        _state.update { it.copy(updatesEnabled = enabled, updateStatus = "") }
        if (enabled) maybeCheckForUpdates()
    }

    fun checkForUpdatesNow() {
        checkForUpdates(manual = true)
    }

    fun dismissUpdate() {
        _state.update { it.copy(availableUpdate = null) }
    }

    fun downloadUpdate() {
        val update = _state.value.availableUpdate ?: return
        val url = update.downloadUrl
        if (!url.startsWith("https://", ignoreCase = true)) {
            show_dialogg("Error", "The update link is not secure.")
            return
        }
        dismissUpdate()
        openUrl(url)
    }

    private fun maybeCheckForUpdates() {
        if (!_state.value.updatesEnabled) return
        val now = System.currentTimeMillis()
        if (now - _state.value.lastUpdateCheck < CHECK_INTERVAL_MS) return
        checkForUpdates(manual = false)
    }

    private fun checkForUpdates(manual: Boolean) {
        if (_state.value.updateChecking) return
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            _state.update { it.copy(updateChecking = true, updateStatus = "") }
            try {
                val release = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, now).apply()
                val newer = UpdateChecker.isNewer(release.version, _state.value.versionName)
                _state.update {
                    it.copy(
                        updateChecking = false,
                        lastUpdateCheck = now,
                        availableUpdate = if (newer) release else null,
                        updateStatus = if (manual && !newer) "Gmad is up to date." else ""
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, now).apply()
                _state.update {
                    it.copy(
                        updateChecking = false,
                        lastUpdateCheck = now,
                        updateStatus = if (manual) "Could not check for updates." else ""
                    )
                }
            }
        }
    }

    fun setStartupDontShow(value: Boolean) {
        _state.update { it.copy(startupDontShow = value) }
    }

    fun maybe_show_startup_dialog() {
        if (prefs.getBoolean(KEY_STARTUP_HIDDEN, false)) return
        _state.update { it.copy(showStartup = true) }
    }

    fun close_startup_dialog() {
        if (_state.value.startupDontShow) {
            prefs.edit().putBoolean(KEY_STARTUP_HIDDEN, true).apply()
        }
        _state.update { it.copy(showStartup = false) }
    }

    fun resetStartupMessage() {
        prefs.edit().remove(KEY_STARTUP_HIDDEN).apply()
        _state.update { it.copy(showStartup = true, showSettings = false) }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        } catch (_: Exception) {
            show_dialogg("Error", "No app can open this link.")
        }
    }

    private fun progressSink(phase: String): ProgressSink {
        return ProgressSink { done, total -> update_progress(phase, done, total) }
    }

    private suspend fun activeCheck(): () -> Boolean {
        val job = currentCoroutineContext()[Job]
        return { job?.isActive == true }
    }

    private fun buildResultMessage(location: String, stats: ExtractionStats): String {
        val builder = StringBuilder()
        builder.append("Saved to ").append(location).append(".")
        builder.append("\n").append(stats.entryCount).append(" files")
        if (stats.crcMismatches > 0) {
            builder.append("\n").append(stats.crcMismatches)
                .append(" files had a CRC warning.")
        }
        return builder.toString()
    }

    private fun parseGmaDescription(raw: String): GmaDescription {
        val value = raw.trim()
        if (value.isEmpty()) return GmaDescription()
        return try {
            val json = JSONObject(value)
            val tags = ArrayList<String>()
            val array = json.optJSONArray("tags")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val tag = array.optString(i).trim()
                    if (tag.isNotEmpty()) tags.add(tag)
                }
            }
            GmaDescription(
                description = cleanWorkshopDescription(json.optString("description")),
                type = json.optString("type").trim(),
                tags = tags
            )
        } catch (_: Exception) {
            GmaDescription(description = value)
        }
    }

    private fun friendlyMessage(error: Throwable): String {
        return when (error) {
            is GmaFormatException,
            is VpkFormatException,
            is WorkshopException,
            is DepotAccessDeniedException,
            is VpkMissingArchiveException -> error.message ?: "Unknown error."
            is java.io.FileNotFoundException ->
                "The file could not be opened. It may have been moved or deleted."
            is SecurityException ->
                "The app lost permission to read this file. Pick it again."
            else -> error.message?.takeIf { it.isNotBlank() } ?: "Unknown error."
        }
    }

    override fun onCleared() {
        prepareJob?.cancel()
        extractJob?.cancel()
        workshopJob?.cancel()
        updateJob?.cancel()
        try {
            openedGma?.close()
        } catch (_: Exception) {
        }
        try {
            vpkArchive?.close()
        } catch (_: Exception) {
        }
        openedGma = null
        vpkArchive = null
        super.onCleared()
    }

    private class OutputHandle(
        val shareUri: Uri,
        val name: String,
        val stream: OutputStream,
        val locationLabel: String,
        val delete: () -> Unit
    )

    companion object {
        private const val PREFS = "gmad_prefs"
        private const val KEY_OUTPUT_TREE = "output_tree"
        private const val KEY_STARTUP_HIDDEN = "startup_hidden"
        private const val KEY_WORKSHOP_API = "workshop_api_base"
        private const val KEY_UPDATES_ENABLED = "updates_enabled"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private const val OUTPUT_DIR = "Gmad_Extracted"
    }
}

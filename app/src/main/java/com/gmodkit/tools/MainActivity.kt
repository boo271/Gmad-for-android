package com.gmodkit.tools

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.gmodkit.tools.android.AndroidSources
import com.gmodkit.tools.ui.GmadScreen
import com.gmodkit.tools.ui.GmadTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val openGmaFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) on_select(uri)
    }

    private val openVpkFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) on_select(uri)
    }

    private val openVpkFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            AndroidSources.takeReadPermission(this, uri)
            viewModel.setVpkFolder(uri)
        }
    }

    private val openOutputFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            AndroidSources.takeWritePermission(this, uri)
            viewModel.setOutputTree(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        viewModel.ensure_storage_permissions()
        setContent {
            GmadTheme {
                GmadScreen(activity = this, viewModel = viewModel)
            }
        }
        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        AndroidSources.takeReadPermission(this, uri)
        viewModel.openExternal(uri)
    }

    fun show_file_manager() {
        val mimeTypes = arrayOf("*/*")
        if (viewModel.state.value.tab == 0) {
            openGmaFile.launch(mimeTypes)
        } else {
            openVpkFile.launch(mimeTypes)
        }
    }

    fun show_vpk_folder_picker() {
        openVpkFolder.launch(null)
    }

    fun show_output_folder_picker() {
        openOutputFolder.launch(null)
    }

    fun on_select(uri: Uri) {
        AndroidSources.takeReadPermission(this, uri)
        viewModel.on_select(uri)
    }

    fun shareOutput(uri: Uri, name: String?) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (name != null && name.endsWith(".gma", ignoreCase = true)) {
                "application/octet-stream"
            } else {
                "application/zip"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, name ?: "Share file"))
        } catch (_: Exception) {
            viewModel.show_dialogg("Error", "No app can share this file.")
        }
    }
}

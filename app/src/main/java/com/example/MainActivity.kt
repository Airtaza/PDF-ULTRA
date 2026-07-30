package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import com.example.ui.ManhwaReaderApp
import com.example.ui.ManhwaViewModel
import com.example.ui.ManhwaViewModelFactory
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ManhwaViewModel by viewModels {
        val repository = (application as ManhwaApplication).repository
        ManhwaViewModelFactory(application, repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle incoming PDF intent when app starts
        intent?.let { handlePdfIntent(it) }

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ManhwaReaderApp(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePdfIntent(intent)
    }

    private fun handlePdfIntent(intent: Intent) {
        val action = intent.action
        val type = intent.type
        if (Intent.ACTION_VIEW == action && type == "application/pdf") {
            intent.data?.let { uri ->
                viewModel.importPdfFile(uri)
            }
        } else if (Intent.ACTION_SEND == action && type == "application/pdf") {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uri ->
                viewModel.importPdfFile(uri)
            }
        } else if (Intent.ACTION_VIEW == action) {
            // Support cases where files are opened without MIME type but scheme represents a PDF file
            intent.data?.let { uri ->
                val scheme = uri.scheme
                val path = uri.path
                if (scheme == "content" || (scheme == "file" && path?.endsWith(".pdf", ignoreCase = true) == true)) {
                    viewModel.importPdfFile(uri)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (viewModel.volumeScrollEnabled.value) {
            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
                viewModel.triggerVolumeKey(keyCode)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            viewModel.clearMemoryCache()
            viewModel.triggerMemoryPressure()
        }
    }
}

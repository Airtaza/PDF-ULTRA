package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.ManhwaViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("PDF ULTRA", appName)
  }

  @Test
  fun `test view settings undo and redo`() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val database = com.example.data.ManhwaDatabase.getDatabase(application, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()))
    val repository = com.example.data.ManhwaRepository(application, database.manhwaDao())
    val viewModel = ManhwaViewModel(application, repository)

    val initialBrightness = viewModel.brightness.value
    val initialSnapshot = viewModel.getCurrentViewSettingsSnapshot()

    // Simulate change with explicit snapshot capture
    viewModel.pushExplicitUndoSnapshot(initialSnapshot)
    viewModel.setBrightness(1.4f)

    assertEquals(1.4f, viewModel.brightness.value, 0.01f)
    assertTrue(viewModel.canUndoViewSettings.value)
    assertFalse(viewModel.canRedoViewSettings.value)

    // Perform Undo
    viewModel.undoViewSettings()
    assertEquals(initialBrightness, viewModel.brightness.value, 0.01f)
    assertTrue(viewModel.canRedoViewSettings.value)

    // Perform Redo
    viewModel.redoViewSettings()
    assertEquals(1.4f, viewModel.brightness.value, 0.01f)
    assertTrue(viewModel.canUndoViewSettings.value)
  }
}

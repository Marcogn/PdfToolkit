package com.marcogn.pdftoolkit.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.marcogn.pdftoolkit.data.files.AllFilesAccess
import com.marcogn.pdftoolkit.data.files.FilePathResolver
import com.marcogn.pdftoolkit.data.settings.FileAccessPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IncomingPdfViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val handedOver = Uri.parse("content://com.example.files/root/Download/a.pdf")
    private val onDisk = File("/storage/emulated/0/Download/a.pdf")

    private var granted = false
    private var resolved: File? = onDisk
    private var inSharedStorage = true
    private lateinit var preferences: FileAccessPreferences
    private lateinit var viewModel: IncomingPdfViewModel

    @Before
    fun setUp() {
        preferences = FileAccessPreferences(context)
        val access = object : AllFilesAccess {
            override fun isGranted() = granted
        }
        val resolver = object : FilePathResolver {
            override fun resolve(uri: Uri) = resolved
            override fun isInSharedStorage(uri: Uri) = inSharedStorage
        }
        viewModel = IncomingPdfViewModel(context, access, resolver, preferences, SavedStateHandle())
    }

    @Test
    fun aKeptGrantOpensTheUriAsItIs() = runBlocking {
        assertEquals(IncomingStep.Open(handedOver), viewModel.firstStep(handedOver, persisted = true))
        val file = Uri.parse("file:///storage/emulated/0/x.pdf")
        assertEquals(IncomingStep.Open(file), viewModel.firstStep(file, persisted = false))
    }

    @Test
    fun aTemporaryGrantWithAccessOpensTheFileByPath() = runBlocking {
        granted = true
        assertEquals(IncomingStep.Open(Uri.fromFile(onDisk)), viewModel.firstStep(handedOver, persisted = false))
    }

    @Test
    fun withAccessButNoFileBehindItTheUriIsKept() = runBlocking {
        granted = true
        resolved = null
        assertEquals(IncomingStep.Open(handedOver), viewModel.firstStep(handedOver, persisted = false))
    }

    @Test
    fun withoutAccessTheUserIsAskedUntilTheyDecline() = runBlocking {
        assertEquals(IncomingStep.Ask(handedOver), viewModel.firstStep(handedOver, persisted = false))
        preferences.setPromptDeclined()
        assertTrue(preferences.promptDeclined.first())
        assertEquals(IncomingStep.Open(handedOver), viewModel.firstStep(handedOver, persisted = false))
    }

    @Test
    fun aFileOutsideSharedStorageOpensWithoutAsking() = runBlocking {
        inSharedStorage = false
        assertEquals(IncomingStep.Open(handedOver), viewModel.firstStep(handedOver, persisted = false))
    }

    @Test
    fun aSecondAnswerDoesNothing() {
        val saved = SavedStateHandle(mapOf("incomingAllFilesPrompt" to handedOver.toString()))
        val access = object : AllFilesAccess {
            override fun isGranted() = false
        }
        val resolver = object : FilePathResolver {
            override fun resolve(uri: Uri) = null
            override fun isInSharedStorage(uri: Uri) = true
        }
        val asked = IncomingPdfViewModel(context, access, resolver, preferences, saved)
        asked.answered()
        assertEquals(null, asked.asking.value)
        // The prompt closed at once: the second call finds nothing to answer, and the one URI goes on.
        asked.answered()
        assertEquals(null, asked.asking.value)
        val goingOn = listOf(saved.get<String>("incomingResolving"), saved.get<String>("incomingToOpen"))
        assertTrue(handedOver.toString() in goingOn)
    }

    @Test
    fun afterThePromptTheAnswerDecides() = runBlocking {
        assertEquals(handedOver, viewModel.afterPrompt(handedOver))
        granted = true
        assertEquals(Uri.fromFile(onDisk), viewModel.afterPrompt(handedOver))
    }
}

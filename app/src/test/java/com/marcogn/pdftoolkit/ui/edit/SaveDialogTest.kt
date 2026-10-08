package com.marcogn.pdftoolkit.ui.edit

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.marcogn.pdftoolkit.ui.theme.PdfToolkitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The save dialog is the one explicit confirmation of an overwrite (plan U6). Italian locale set explicitly, see HomeScreenTest. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "it-w360dp-h800dp")
class SaveDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var confirmed = 0
    private var overwriteChoice: Boolean? = null

    private fun setDialog(overwrite: Boolean, canOverwrite: Boolean = true) {
        composeRule.setContent {
            PdfToolkitTheme {
                SaveDialog(
                    overwrite = overwrite,
                    canOverwrite = canOverwrite,
                    onOverwriteChange = { overwriteChoice = it },
                    onConfirm = { confirmed++ },
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun `saving a copy says Salva and shows no warning`() {
        setDialog(overwrite = false)
        composeRule.onNodeWithText("Salva").assertIsDisplayed()
        composeRule.onNodeWithText("Il file originale sarà sostituito e non si potrà recuperare.").assertDoesNotExist()
    }

    @Test
    fun `overwriting warns under the option and the button names the action`() {
        setDialog(overwrite = true)
        composeRule.onNodeWithText("Il file originale sarà sostituito e non si potrà recuperare.").assertIsDisplayed()
        // "Sovrascrivi" is both the option and the confirm button.
        composeRule.onAllNodesWithText("Sovrascrivi").assertCountEquals(2)
    }

    @Test
    fun `one tap on the confirm button is enough to overwrite`() {
        setDialog(overwrite = true)
        composeRule.onAllNodesWithText("Sovrascrivi")[1].performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun `choosing overwrite is reported`() {
        setDialog(overwrite = false)
        composeRule.onNodeWithText("Sovrascrivi").performClick()
        assertEquals(true, overwriteChoice)
    }
}

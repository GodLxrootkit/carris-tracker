package pt.carrismetropolitana.tracker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The re-order arrows must not be on screen until re-ordering is asked for.
 *
 * A regression guard for a cosmetic complaint, which is the sort of thing that gets "fixed" by
 * adding a control and quietly re-cluttering the card. The arrows sat permanently beside the
 * expand and remove buttons — four buttons in a row — and are now behind a long-press.
 *
 * Robolectric cannot run on a bionic JVM, so these run in CI on ubuntu-latest, not locally.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StopReorderUiTest {

    @get:Rule
    val compose = createComposeRule()

    private var longPressed = false

    private fun render(editing: Boolean, collapsed: Boolean = true) {
        compose.setContent {
            StopCard(
                stop = SavedStop("1", "Paragem A"),
                arrivals = emptyList(),
                lines = emptyList(),
                now = 1_700_000_000L,
                collapsed = collapsed,
                editing = editing,
                canMoveUp = false,
                canMoveDown = true,
                onToggle = {},
                onEdit = { longPressed = true },
                onMoveUp = {},
                onMoveDown = {},
                onRemove = {}
            )
        }
    }

    @Test
    fun arrowsAreHiddenUntilACardIsLongPressed() {
        render(editing = false)
        compose.onNodeWithContentDescription("Mover para cima").assertDoesNotExist()
        compose.onNodeWithContentDescription("Mover para baixo").assertDoesNotExist()

        compose.onNodeWithText("Paragem A").performTouchInput { longClick() }

        // The card asked to be edited; the parent screen is what flips into edit mode.
        assertTrue("long press should ask the screen to enter edit mode", longPressed)
    }

    @Test
    fun expandingStaysAvailableOutsideEditMode() {
        render(editing = false)
        compose.onNodeWithContentDescription("Expandir paragem").assertIsDisplayed()
    }

    @Test
    fun editingSwapsTheArrowsInForTheExpandControl() {
        render(editing = true)

        compose.onNodeWithContentDescription("Mover para baixo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Expandir paragem").assertDoesNotExist()
    }
}
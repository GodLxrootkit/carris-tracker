package pt.carrismetropolitana.tracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout assertions for the map tab.
 *
 * These exist because of what shipped in 1.3.0: the provider divider used `fillMaxHeight`
 * inside the chip `Row`, so Compose gave it `minHeight` equal to the Row's full available
 * height, the row grew to fill the screen, and the map was left a ~200 px strip. All 34 unit
 * tests passed straight through it. Layout has to be measured, not inferred.
 *
 * Robolectric cannot run on a bionic JVM — it loads a linux-aarch_64 Conscrypt .so and
 * Termux is Android userland — so these run in CI on ubuntu-latest, not locally.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private companion object {
        const val MAP = "map"

        /** 746 is two route_ids under one short name — the case that produced two chips. */
        val LINES = listOf(
            LineInfo("cm:1707", "1707", "Algés", "#005EB8", emptyList()),
            LineInfo("cm:1717", "1717", "Algés", "#005EB8", emptyList()),
            LineInfo("carris:164_0", "716", "Alameda D. A. Henriques - Benfica", "#C61D23", emptyList()),
            LineInfo("carris:108_0", "746", "Marquês Pombal - Estação Damaia", "#1F5FA9", emptyList()),
            LineInfo("carris:108_3", "746", "Rossio - Sul e Sueste", "#0E7C7B", emptyList())
        )

        val BUS_COUNT = mapOf("1707" to 4, "1717" to 2, "716" to 1, "746" to 3)
    }

    private fun render(padding: PaddingValues = PaddingValues(0.dp)) {
        compose.setContent {
            MapLayout(
                padding = padding,
                filterRow = {
                    LineFilterRow(
                        lines = LINES,
                        busCount = BUS_COUNT,
                        filter = null,
                        onFilter = {}
                    )
                }
            ) { mapModifier ->
                Box(mapModifier.testTag(MAP).background(Color.Black))
            }
        }
    }

    /** DpRect.height does not resolve in this Compose version; bottom - top is unambiguous. */
    private fun androidx.compose.ui.unit.DpRect.heightPx(): Float = (bottom - top).value

    private fun rootHeight(): Float = compose.onRoot().getUnclippedBoundsInRoot().heightPx()

    /**
     * The 1.3.0 regression. If any child of the chip row claims the Row's full available
     * height, this goes to screen height and the map collapses to a strip.
     */
    @Test
    fun filterRowStaysChipSized() {
        render()
        val root = rootHeight()
        val row = compose.onNodeWithTag(FILTER_ROW_TAG).getUnclippedBoundsInRoot().heightPx()

        assertTrue(
            "filter row took ${row}dp of a ${root}dp screen; a child claimed the full height",
            row < root * 0.25f
        )
        compose.onNodeWithTag(MAP).assertHeightIsAtLeast((root * 0.6f).dp)
    }

    /**
     * The map must start below both the status bar inset and the filter row.
     *
     * The inset applies to the whole column, so the map's top is `inset + filterRowHeight`,
     * not the inset alone.
     */
    @Test
    fun mapStartsBelowTheInsetAndTheFilterRow() {
        val inset = 48.dp
        render(padding = PaddingValues(top = inset))
        val row = compose.onNodeWithTag(FILTER_ROW_TAG).getUnclippedBoundsInRoot().heightPx()
        compose.onNodeWithTag(MAP).assertTopPositionInRootIsEqualTo(inset + Dp(row))
    }

    /** And it must reach the bottom of the padded area rather than stopping short. */
    @Test
    fun mapFillsEverythingBelowTheFilterRow() {
        render()
        val root = rootHeight()
        val row = compose.onNodeWithTag(FILTER_ROW_TAG).getUnclippedBoundsInRoot().heightPx()
        val map = compose.onNodeWithTag(MAP).getUnclippedBoundsInRoot()

        assertTrue(
            "map bottom ${map.bottom.value}dp short of the ${root}dp root",
            map.bottom.value >= root - 1f
        )
        assertTrue(
            "map top ${map.top.value}dp overlaps a ${row}dp filter row",
            map.top.value >= row - 1f
        )
    }

    /**
     * Two route_ids for 746 must collapse to one chip, or the row shows a duplicate label.
     *
     * Chip text is "shortName · count", and onAllNodesWithText matches exactly, so the count
     * is part of what is asserted.
     */
    @Test
    fun variantsProduceOneChipEach() {
        render()
        compose.onAllNodesWithText("716 · 1").assertCountEquals(1)
        compose.onAllNodesWithText("746 · 3").assertCountEquals(1)
    }

    /** Counts make a live line distinguishable from a dead one, which previously looked broken. */
    @Test
    fun liveLinesShowTheirVehicleCount() {
        render()
        compose.onAllNodesWithText("746 · 3").assertCountEquals(1)
    }
}

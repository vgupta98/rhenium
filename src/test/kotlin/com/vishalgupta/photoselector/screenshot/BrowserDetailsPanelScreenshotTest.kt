package com.vishalgupta.photoselector.screenshot

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.domain.insight.InsightBand
import com.vishalgupta.photoselector.domain.insight.InsightRange
import com.vishalgupta.photoselector.domain.insight.InsightValue
import com.vishalgupta.photoselector.domain.insight.LabeledInsight
import com.vishalgupta.photoselector.presentation.designsystem.organism.BrowserDetailsPanel
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import org.junit.Rule
import org.junit.Test

/**
 * The browser details panel now renders on-device AI insights generically over the taxonomy, replacing
 * the old "AI insights - coming soon" placeholder. Eyeball `build/screenshots/`:
 *  - `browser-details-panel-sharp`: the file/EXIF facts, then an "AI insights" section with a "Sharpness"
 *    row showing the "Sharp" band chip and a labelled bar.
 *  - `browser-details-panel-no-insights`: the same panel when nothing is assessable (empty insight list).
 */
class BrowserDetailsPanelScreenshotTest {

    @get:Rule val rule = createComposeRule()

    private fun render(insights: List<LabeledInsight>, name: String) {
        rule.setContent {
            AppTheme {
                Surface {
                    BrowserDetailsPanel(
                        fileName = "DSC_0421.NEF",
                        relativePath = "day-2/ceremony/DSC_0421.NEF",
                        sizeBytes = 28_400_000,
                        captureTimeEpochMs = 1_700_000_000_000,
                        cameraId = "NIKON Z 6",
                        insights = insights,
                        modifier = Modifier.width(320.dp).fillMaxHeight(),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot(name)
    }

    @Test fun `panel renders a scalar sharpness insight row`() {
        render(
            insights = listOf(
                LabeledInsight(
                    label = "Sharpness",
                    value = InsightValue.Scalar(raw = 340f, range = InsightRange(0f, 500f), band = InsightBand.Sharp),
                ),
            ),
            name = "browser-details-panel-sharp",
        )
        rule.onNodeWithText("Sharp").assertIsDisplayed()
        rule.onNodeWithText("Sharpness").assertIsDisplayed()
    }

    @Test fun `panel shows a placeholder when there are no insights`() {
        render(insights = emptyList(), name = "browser-details-panel-no-insights")
        rule.onNodeWithText("No insights for this photo").assertIsDisplayed()
    }
}

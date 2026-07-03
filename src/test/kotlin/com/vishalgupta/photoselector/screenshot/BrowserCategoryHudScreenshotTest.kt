package com.vishalgupta.photoselector.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.presentation.designsystem.organism.BrowserCategoryHud
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import org.junit.Rule
import org.junit.Test

/**
 * Renders the browser's category HUD after the [CategoryHudChip] slot refactor — verifies the
 * built-in scopes lead (Favourites `F` + gold star, Rejects `X` + red flag) followed by a custom
 * category, with the current photo a member of Favourites and Rejects so both built-in chips light.
 * Eyeball build/screenshots/browser-hud-reject.png.
 */
class BrowserCategoryHudScreenshotTest {

    @get:Rule val rule = createComposeRule()

    private val categories = listOf(
        Category.favourites(),
        Category.rejects(),
        Category(CategoryId("keepers"), "Keepers", builtIn = false),
    )

    @Test fun `hud shows favourite reject and custom chips`() {
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(720.dp, 160.dp)) {
                    Box(Modifier.background(Color.Black).padding(24.dp), contentAlignment = Alignment.Center) {
                        BrowserCategoryHud(
                            categories = categories,
                            currentMemberships = setOf(Category.FAVOURITES_ID, Category.REJECTS_ID),
                            onToggle = {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.dumpScreenshot("browser-hud-reject")
    }
}

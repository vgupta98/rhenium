package com.vishalgupta.photoselector.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.presentation.common.GroupingMode
import com.vishalgupta.photoselector.presentation.designsystem.atom.AppButton
import com.vishalgupta.photoselector.presentation.designsystem.atom.AppOutlinedButton
import com.vishalgupta.photoselector.presentation.designsystem.atom.AppTextButton
import com.vishalgupta.photoselector.presentation.designsystem.atom.FavouriteStar
import com.vishalgupta.photoselector.presentation.designsystem.atom.LoadingIndicator
import com.vishalgupta.photoselector.presentation.designsystem.molecule.BackgroundGroupingChip
import com.vishalgupta.photoselector.presentation.designsystem.molecule.BusyBar
import com.vishalgupta.photoselector.presentation.designsystem.molecule.ConflictPolicyButton
import com.vishalgupta.photoselector.presentation.designsystem.molecule.ErrorPlaceholder
import com.vishalgupta.photoselector.presentation.designsystem.molecule.FavouritesButton
import com.vishalgupta.photoselector.presentation.designsystem.molecule.GroupingModeToggle
import com.vishalgupta.photoselector.presentation.designsystem.molecule.LatchedPill
import com.vishalgupta.photoselector.presentation.designsystem.molecule.PillToast
import com.vishalgupta.photoselector.presentation.designsystem.molecule.PillToastDefaults
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Renders the design-system atoms and molecules in their key states and dumps a
 * single gallery PNG to `build/screenshots/`. This is the eyeball surface for
 * the token + component layer — when a token changes, this is the screenshot to
 * diff. Screen-level organisms are covered by [ScreenSplitScreenshotTest].
 */
class DesignSystemGalleryScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun design_system_gallery() {
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(720.dp, 880.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(AppTheme.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg),
                    ) {
                        Text("Buttons", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                            AppButton(text = "Primary", onClick = {})
                            AppOutlinedButton(text = "Outlined", onClick = {})
                            AppTextButton(text = "Change folder", leadingIcon = Icons.Default.Folder, onClick = {})
                            AppButton(text = "Disabled", enabled = false, onClick = {})
                        }

                        Text("Favourite star", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)) {
                            FavouriteStar(filled = true, tint = AppTheme.colors.favourite, modifier = Modifier.size(AppTheme.dimens.iconSm))
                            FavouriteStar(filled = false, modifier = Modifier.size(AppTheme.dimens.iconSm))
                            FavouritesButton(count = 12, onClick = {})
                            ConflictPolicyButton(enabled = true, onSelect = {})
                        }

                        Text("Pill toasts", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)) {
                            PillToast(
                                text = "Favourited",
                                leadingIcon = { FavouriteStar(filled = true, modifier = Modifier.size(AppTheme.dimens.iconSm)) },
                                colors = PillToastDefaults.favouriteColors(),
                            )
                            PillToast(
                                text = "Unfavourited",
                                leadingIcon = { FavouriteStar(filled = false, modifier = Modifier.size(AppTheme.dimens.iconSm)) },
                            )
                            // The shared latch-and-fade wrapper the grid/browser pills are built from;
                            // with a present value it renders its body (here a PillToast) unchanged.
                            LatchedPill(value = "Added to Ceremony") { PillToast(text = it) }
                        }

                        Text("Background grouping", style = MaterialTheme.typography.titleMedium)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg),
                        ) {
                            // The Similar tab carries a determinate ring while the pass runs (here while
                            // the Bursts lens is shown), and the off-grid chip mirrors it.
                            GroupingModeToggle(
                                mode = GroupingMode.Time,
                                onSelect = {},
                                similarityProgress = 0.42f,
                            )
                            BackgroundGroupingChip(processed = 42, total = 100)
                        }

                        Text("Busy bar", style = MaterialTheme.typography.titleMedium)
                        BusyBar(label = "Copying… 34 / 120")

                        Text("Loading + placeholder", style = MaterialTheme.typography.titleMedium)
                        Row(
                            Modifier.fillMaxWidth().height(160.dp),
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg),
                        ) {
                            LoadingIndicator()
                            ErrorPlaceholder("No favourites yet.", Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val file = rule.dumpScreenshot("design-system-gallery")
        assertTrue(file.exists() && file.length() > 0, "gallery screenshot should be written")
    }
}

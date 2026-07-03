package com.vishalgupta.photoselector.presentation.browser

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.presentation.designsystem.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.nio.file.Path

class BrowserKeyboardTest {

    @get:Rule
    val rule = createComposeRule()

    private val selectsId = CategoryId("selects")
    private val categories = listOf(
        Category.favourites(),
        Category(selectsId, "Selects", builtIn = false),
    )

    private val photos = listOf(
        Photo(
            id = PhotoId("a"),
            absolutePath = Path.of("/photos/sunset.jpg"),
            relativePath = "sunset.jpg",
            fileName = "sunset.jpg",
            sizeBytes = 1024,
            lastModifiedEpochMs = 0,
        ),
    )

    private fun state() = BrowserUiState(
        photos = photos,
        currentIndex = 0,
        currentPhoto = photos[0],
        currentBitmap = null,
        isLoadingBitmap = false,
        isCurrentFavourite = false,
        readOnly = false,
        categories = categories,
        currentMemberships = emptySet(),
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fKey_togglesFavourites_bareDigit_togglesNthCustomCategory() {
        val toggled = mutableListOf<CategoryId>()
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    BrowserScreen(
                        state = state(),
                        toast = null,
                        onPrevious = {},
                        onNext = {},
                        onToggleCategory = { toggled += it },
                        onViewportSizeChanged = {},
                        onBackToGrid = {},
                    )
                }
            }
        }
        rule.waitForIdle()

        // "1" toggles the 1st custom category; "F" the built-in Favourites; "X" the built-in Rejects.
        rule.onRoot().performKeyInput { pressKey(Key.One) }
        rule.onRoot().performKeyInput { pressKey(Key.F) }
        rule.onRoot().performKeyInput { pressKey(Key.X) }
        rule.waitForIdle()

        assertEquals(listOf(selectsId, Category.FAVOURITES_ID, Category.REJECTS_ID), toggled)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aKey_showsInAllPhotos_onlyWhenBrowsingACategory() {
        var jumps = 0
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    BrowserScreen(
                        state = state(),
                        toast = null,
                        onPrevious = {},
                        onNext = {},
                        onToggleCategory = {},
                        onViewportSizeChanged = {},
                        onBackToGrid = {},
                        onShowInAllPhotos = { jumps++ },
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.A) }
        rule.waitForIdle()

        assertEquals("A jumps to the photo in All Photos when the handler is wired", 1, jumps)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aKey_isInert_inTheAllPhotosBrowser() {
        // No onShowInAllPhotos (the All-Photos browser): plain A must do nothing, not crash or consume.
        rule.setContent {
            AppTheme {
                Surface(Modifier.size(800.dp, 600.dp)) {
                    BrowserScreen(
                        state = state(),
                        toast = null,
                        onPrevious = {},
                        onNext = {},
                        onToggleCategory = {},
                        onViewportSizeChanged = {},
                        onBackToGrid = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        // Nothing to assert beyond "doesn't throw / consume into an action": the handler is null, so A
        // falls through. Pressing it must not blow up.
        rule.onRoot().performKeyInput { pressKey(Key.A) }
        rule.waitForIdle()
    }
}

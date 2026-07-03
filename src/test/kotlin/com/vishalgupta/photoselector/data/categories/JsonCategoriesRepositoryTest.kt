package com.vishalgupta.photoselector.data.categories

import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertFailsWith

class JsonCategoriesRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun photo(relative: String, size: Long, mtime: Long) = Photo(
        id = PhotoId(relative),
        absolutePath = tmp.root.toPath().resolve(relative),
        relativePath = relative,
        fileName = relative.substringAfterLast('/'),
        sizeBytes = size,
        lastModifiedEpochMs = mtime,
    )

    /** A repository whose generated ids are deterministic (cat-0, cat-1, …) for assertions. */
    private fun repo(scanned: List<Photo>): Pair<JsonCategoriesRepository, RootFolder> {
        val root = RootFolder(tmp.root.toPath())
        var counter = 0
        val repo = JsonCategoriesRepository(
            json = json,
            scannedPhotos = { scanned },
            idGenerator = { CategoryId("cat-${counter++}") },
        )
        return repo to root
    }

    private fun writeCategoriesFile(root: RootFolder, content: String) =
        Files.writeString(root.categoriesFile, content)

    private fun writeFavouritesFile(root: RootFolder, content: String) =
        Files.writeString(root.favouritesFile, content)

    private fun favouriteIds(repo: JsonCategoriesRepository, root: RootFolder): Set<PhotoId> =
        repo.observeMemberships(root).value[Category.FAVOURITES_ID].orEmpty()

    @Test
    fun freshRoot_exposesBuiltInFavouritesAndRejects() {
        val (repo, root) = repo(listOf(photo("a.jpg", 1, 1)))

        val categories = repo.observeCategories(root).value
        // Both built-ins exist, in canonical order, and both are flagged built-in.
        assertEquals(listOf(Category.FAVOURITES_ID, Category.REJECTS_ID), categories.map { it.id })
        assertTrue(categories.all { it.builtIn })
        assertEquals(emptySet<PhotoId>(), favouriteIds(repo, root))
    }

    @Test
    fun v1FavouritesFile_migratesIntoFavouritesAndBacksUpOldFile() {
        val (repo, root) = repo(listOf(photo("a.jpg", 100, 5)))
        writeFavouritesFile(root, """{"favourites":["a.jpg"]}""")

        assertEquals(setOf(PhotoId("a.jpg")), favouriteIds(repo, root))
        assertTrue("v2 categories file written", Files.exists(root.categoriesFile))
        assertFalse("legacy favourites file retired", Files.exists(root.favouritesFile))
        assertTrue("legacy favourites file kept as .bak", Files.exists(root.favouritesBackupFile))
    }

    @Test
    fun createToggleAndReopen_persistsCustomCategoryMembership() = runTest {
        val (repo, root) = repo(listOf(photo("a.jpg", 100, 5), photo("b.jpg", 200, 6)))

        val selects = repo.create(root, "Selects")
        repo.toggleMembership(root, selects, PhotoId("b.jpg"))

        // A fresh repository reading the same file sees the persisted category + membership.
        val (reopened, _) = repo(listOf(photo("a.jpg", 100, 5), photo("b.jpg", 200, 6)))
        val categories = reopened.observeCategories(root).value
        assertEquals(listOf("Favourites", "Rejects", "Selects"), categories.map { it.name })
        assertEquals(
            setOf(PhotoId("b.jpg")),
            reopened.observeMemberships(root).value[selects],
        )
    }

    @Test
    fun addMemberships_filesOnlyNewPhotosAndReportsCount() = runTest {
        val (repo, root) = repo(listOf(photo("a.jpg", 1, 1), photo("b.jpg", 2, 2), photo("c.jpg", 3, 3)))
        val selects = repo.create(root, "Selects")
        repo.toggleMembership(root, selects, PhotoId("a.jpg"))

        // a.jpg is already filed; only b and c are new, so the count reflects two additions.
        val added = repo.addMemberships(
            root,
            selects,
            listOf(PhotoId("a.jpg"), PhotoId("b.jpg"), PhotoId("c.jpg")),
        )

        assertEquals(2, added)
        assertEquals(
            setOf(PhotoId("a.jpg"), PhotoId("b.jpg"), PhotoId("c.jpg")),
            repo.observeMemberships(root).value[selects],
        )
    }

    @Test
    fun addMemberships_isAdditiveAndPersists() = runTest {
        val (repo, root) = repo(listOf(photo("a.jpg", 1, 1), photo("b.jpg", 2, 2)))
        val selects = repo.create(root, "Selects")

        repo.addMemberships(root, selects, listOf(PhotoId("a.jpg"), PhotoId("b.jpg")))
        // Re-filing the same set adds nothing and never removes existing members.
        val again = repo.addMemberships(root, selects, listOf(PhotoId("a.jpg")))
        assertEquals(0, again)

        val (reopened, _) = repo(listOf(photo("a.jpg", 1, 1), photo("b.jpg", 2, 2)))
        assertEquals(
            setOf(PhotoId("a.jpg"), PhotoId("b.jpg")),
            reopened.observeMemberships(root).value[selects],
        )
    }

    @Test
    fun photoCanBelongToMultipleCategories() = runTest {
        val (repo, root) = repo(listOf(photo("a.jpg", 100, 5)))

        val selects = repo.create(root, "Selects")
        repo.toggleMembership(root, Category.FAVOURITES_ID, PhotoId("a.jpg"))
        repo.toggleMembership(root, selects, PhotoId("a.jpg"))

        val memberships = repo.observeMemberships(root).value
        assertEquals(setOf(PhotoId("a.jpg")), memberships[Category.FAVOURITES_ID])
        assertEquals(setOf(PhotoId("a.jpg")), memberships[selects])
    }

    @Test
    fun builtIns_cannotBeRenamedOrDeleted() = runTest {
        val (repo, root) = repo(listOf(photo("a.jpg", 1, 1)))

        assertFailsWith<IllegalArgumentException> { repo.rename(root, Category.FAVOURITES_ID, "Nope") }
        assertFailsWith<IllegalArgumentException> { repo.delete(root, Category.FAVOURITES_ID) }
        assertFailsWith<IllegalArgumentException> { repo.rename(root, Category.REJECTS_ID, "Nope") }
        assertFailsWith<IllegalArgumentException> { repo.delete(root, Category.REJECTS_ID) }
    }

    @Test
    fun rejectsMembership_togglesAndPersists() = runTest {
        val (repo, root) = repo(listOf(photo("a.jpg", 100, 5)))

        repo.toggleMembership(root, Category.REJECTS_ID, PhotoId("a.jpg"))
        assertEquals(
            setOf(PhotoId("a.jpg")),
            repo.observeMemberships(root).value[Category.REJECTS_ID],
        )

        // A fresh repository reading the same file sees the persisted reject.
        val (reopened, _) = repo(listOf(photo("a.jpg", 100, 5)))
        assertEquals(
            setOf(PhotoId("a.jpg")),
            reopened.observeMemberships(root).value[Category.REJECTS_ID],
        )
    }

    @Test
    fun legacyFavouritesMigration_alsoSeedsEmptyRejects() = runTest {
        // Migrating the v1 favourites file must add the new Rejects built-in (empty) alongside it.
        val (repo, root) = repo(listOf(photo("a.jpg", 100, 5)))
        writeFavouritesFile(root, """{"favourites":["a.jpg"]}""")

        val categories = repo.observeCategories(root).value
        assertEquals(listOf(Category.FAVOURITES_ID, Category.REJECTS_ID), categories.map { it.id })
        assertEquals(setOf(PhotoId("a.jpg")), favouriteIds(repo, root))
        assertEquals(emptySet<PhotoId>(), repo.observeMemberships(root).value[Category.REJECTS_ID].orEmpty())
    }

    @Test
    fun renameAndDelete_customCategory() = runTest {
        val (repo, root) = repo(listOf(photo("a.jpg", 1, 1)))

        val id = repo.create(root, "Maybes")
        repo.rename(root, id, "Maybe Later")
        assertEquals("Maybe Later", repo.observeCategories(root).value.first { it.id == id }.name)

        repo.delete(root, id)
        assertEquals(
            listOf(Category.FAVOURITES_ID, Category.REJECTS_ID),
            repo.observeCategories(root).value.map { it.id },
        )
    }

    @Test
    fun customCategoryMembership_survivesFolderRename() = runTest {
        // v2 file stores a custom category whose photo is under the old folder name; the
        // scan now sees the renamed folder, so it must re-attach via (size, mtime).
        val (repo, root) = repo(listOf(photo("01-Ceremony/a.jpg", 100, 1700)))
        writeCategoriesFile(
            root,
            """
            {
              "version": 2,
              "categories": [
                { "id": "favourites", "name": "Favourites", "builtIn": true, "photos": [] },
                { "id": "cat-x", "name": "Selects", "builtIn": false,
                  "photos": [ { "path": "Ceremony/a.jpg", "size": 100, "mtimeMs": 1700 } ] }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(
            setOf(PhotoId("01-Ceremony/a.jpg")),
            repo.observeMemberships(root).value[CategoryId("cat-x")],
        )
    }

    @Test
    fun toggle_writesV2DescriptorWithSizeAndMtime() = runTest {
        val (repo, root) = repo(listOf(photo("a.jpg", 4823901, 1730812401000)))

        repo.toggleMembership(root, Category.FAVOURITES_ID, PhotoId("a.jpg"))

        val written = Files.readString(root.categoriesFile)
        assertTrue("expected v2 version field, got: $written", written.contains("\"version\": 2"))
        assertTrue("expected size hint, got: $written", written.contains("\"size\": 4823901"))
        assertTrue("expected mtime hint, got: $written", written.contains("\"mtimeMs\": 1730812401000"))
    }

    @Test
    fun observingBeforeScanIsSet_doesNotStickToEmpty() {
        // The scan result can arrive after the first observe(); binding against an empty
        // scan must not cache the root as bound, or persisted memberships would vanish.
        var scanned = emptyList<Photo>()
        val root = RootFolder(tmp.root.toPath())
        val repo = JsonCategoriesRepository(json, scannedPhotos = { scanned })
        writeCategoriesFile(
            root,
            """{"version":2,"categories":[{"id":"favourites","name":"Favourites","builtIn":true,"photos":[{"path":"a.jpg","size":100,"mtimeMs":5}]}]}""",
        )

        assertEquals(emptySet<PhotoId>(), favouriteIds(repo, root))

        scanned = listOf(photo("a.jpg", 100, 5))
        assertEquals(setOf(PhotoId("a.jpg")), favouriteIds(repo, root))
    }

    @Test
    fun corruptCategoriesFile_reportsReadOnlyAndIsNotClobberedByALaterWrite() = runTest {
        // A present-but-undecodable file must not be mistaken for an empty model: the next
        // toggle would otherwise overwrite it with just the built-in Favourites, destroying
        // every salvageable custom category and its memberships.
        val (repo, root) = repo(listOf(photo("a.jpg", 100, 5)))
        val corrupt = "{ this is not valid json"
        writeCategoriesFile(root, corrupt)

        assertTrue("decode failure should surface as read-only", repo.isReadOnly(root).value)
        assertEquals(emptySet<PhotoId>(), favouriteIds(repo, root))

        repo.toggleMembership(root, Category.FAVOURITES_ID, PhotoId("a.jpg"))

        assertEquals("unreadable file must be left untouched", corrupt, Files.readString(root.categoriesFile))
    }
}

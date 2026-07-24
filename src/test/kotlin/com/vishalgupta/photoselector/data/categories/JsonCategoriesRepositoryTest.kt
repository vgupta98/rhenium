package com.vishalgupta.photoselector.data.categories

import com.vishalgupta.photoselector.data.format.RawDecoder
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.CategoryKind
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RawFilesResolver
import com.vishalgupta.photoselector.domain.model.RootFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
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

    // "What is RAW" comes from the same set production uses (the RAW decoder's format), never a fork.
    private val rawResolver = RawFilesResolver(RawDecoder.Companion.RawFormat.extensions)
    // Unconfined so the launched rule pass runs inline during observe() — deterministic in-memory
    // state; SupervisorJob so a test can join the (real-IO) prune write via [awaitResolution].
    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Awaits any in-flight rule pass (incl. its off-thread prune write). */
    private suspend fun awaitResolution() =
        resolverScope.coroutineContext.job.children.toList().joinAll()

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
            ruleResolver = rawResolver,
            scope = resolverScope,
            idGenerator = { CategoryId("cat-${counter++}") },
        )
        return repo to root
    }

    private fun smartRawMembers(repo: JsonCategoriesRepository, root: RootFolder): Set<PhotoId> =
        repo.observeMemberships(root).value[Category.SMART_RAW_ID].orEmpty()

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
        // Both built-ins plus the always-seeded "RAW files" smart category, in canonical order.
        assertEquals(
            listOf(Category.FAVOURITES_ID, Category.REJECTS_ID, Category.SMART_RAW_ID, Category.SMART_SHARP_ID),
            categories.map { it.id },
        )
        assertTrue(categories.filter { it.builtIn }.all { it.id in Category.BUILT_IN_IDS })
        assertEquals(CategoryKind.SMART, categories.first { it.id == Category.SMART_RAW_ID }.kind)
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
        assertEquals(listOf("Favourites", "Rejects", "RAW files", "Sharp", "Selects"), categories.map { it.name })
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
        assertEquals(
            listOf(Category.FAVOURITES_ID, Category.REJECTS_ID, Category.SMART_RAW_ID, Category.SMART_SHARP_ID),
            categories.map { it.id },
        )
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
            listOf(Category.FAVOURITES_ID, Category.REJECTS_ID, Category.SMART_RAW_ID, Category.SMART_SHARP_ID),
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
        val repo = JsonCategoriesRepository(
            json,
            scannedPhotos = { scanned },
            ruleResolver = rawResolver,
            scope = resolverScope,
        )
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

    // --- Smart categories (RAW files) ---

    @Test
    fun smartRaw_resolvesMembershipFromFileExtension() = runTest {
        val (repo, root) = repo(
            listOf(photo("a.jpg", 1, 1), photo("b.arw", 2, 2), photo("c.CR2", 3, 3), photo("d.png", 4, 4)),
        )

        // Rule-resolved: the two RAW files (case-insensitive), never the JPEG/PNG. No manual overrides.
        assertEquals(setOf(PhotoId("b.arw"), PhotoId("c.CR2")), smartRawMembers(repo, root))
    }

    @Test
    fun smartRaw_toggleOffARuleMatch_writesAnExcludeAndDropsIt() = runTest {
        val (repo, root) = repo(listOf(photo("b.arw", 2, 2), photo("c.cr2", 3, 3)))
        assertEquals(setOf(PhotoId("b.arw"), PhotoId("c.cr2")), smartRawMembers(repo, root))

        // (i) Toggling OFF a rule-matched photo writes a manual EXCLUDE (never a pin) and it leaves.
        val nowMember = repo.toggleMembership(root, Category.SMART_RAW_ID, PhotoId("b.arw"))
        assertFalse(nowMember)
        assertEquals(setOf(PhotoId("c.cr2")), smartRawMembers(repo, root))

        // Persisted as an exclude, not a pin.
        val (reopened, _) = repo(listOf(photo("b.arw", 2, 2), photo("c.cr2", 3, 3)))
        assertEquals(setOf(PhotoId("c.cr2")), smartRawMembers(reopened, root))

        // (iii) Re-toggling ON removes the exclude, returning to the rule's natural state.
        assertTrue(repo.toggleMembership(root, Category.SMART_RAW_ID, PhotoId("b.arw")))
        assertEquals(setOf(PhotoId("b.arw"), PhotoId("c.cr2")), smartRawMembers(repo, root))
    }

    @Test
    fun smartRaw_toggleOnANonMatch_writesAPinAndAddsIt() = runTest {
        val (repo, root) = repo(listOf(photo("a.jpg", 1, 1), photo("b.arw", 2, 2)))

        // (ii) Toggling ON a non-matched photo writes a manual PIN and it joins.
        val nowMember = repo.toggleMembership(root, Category.SMART_RAW_ID, PhotoId("a.jpg"))
        assertTrue(nowMember)
        assertEquals(setOf(PhotoId("a.jpg"), PhotoId("b.arw")), smartRawMembers(repo, root))

        // Persisted as a pin.
        val (reopened, _) = repo(listOf(photo("a.jpg", 1, 1), photo("b.arw", 2, 2)))
        assertEquals(setOf(PhotoId("a.jpg"), PhotoId("b.arw")), smartRawMembers(reopened, root))

        // (iii) Re-toggling OFF removes the pin, back to rule-only.
        assertFalse(repo.toggleMembership(root, Category.SMART_RAW_ID, PhotoId("a.jpg")))
        assertEquals(setOf(PhotoId("b.arw")), smartRawMembers(repo, root))
    }

    @Test
    fun smartRaw_combinesRuleMatchesWithPinsMinusExcludes() = runTest {
        val (repo, root) = repo(
            listOf(photo("a.jpg", 1, 1), photo("b.arw", 2, 2), photo("c.nef", 3, 3)),
        )

        // Pin a non-match (a.jpg) and exclude a match (c.nef): members = (matches ∪ pins) \ excludes.
        repo.toggleMembership(root, Category.SMART_RAW_ID, PhotoId("a.jpg")) // pin
        repo.toggleMembership(root, Category.SMART_RAW_ID, PhotoId("c.nef")) // exclude
        assertEquals(setOf(PhotoId("a.jpg"), PhotoId("b.arw")), smartRawMembers(repo, root))
    }

    @Test
    fun smartRaw_prunesRedundantOverridesOnRescan() = runTest {
        // A stale PIN (rule now matches it) and a stale EXCLUDE (rule no longer matches it) on disk
        // must both be pruned on load so they can't resurrect a wrong membership on a later rescan.
        val (repo, root) = repo(listOf(photo("x.arw", 10, 10), photo("y.jpg", 20, 20)))
        writeCategoriesFile(
            root,
            """
            {
              "version": 2,
              "categories": [
                { "id": "smart-raw", "name": "RAW files", "builtIn": false,
                  "rule": { "type": "raw-files" },
                  "photos":   [ { "path": "x.arw", "size": 10, "mtimeMs": 10 } ],
                  "excluded": [ { "path": "y.jpg", "size": 20, "mtimeMs": 20 } ] }
              ]
            }
            """.trimIndent(),
        )

        // In-memory membership is already correct: x.arw via the rule, y.jpg not RAW so absent.
        assertEquals(setOf(PhotoId("x.arw")), smartRawMembers(repo, root))

        // The prune pass rewrites the file without the now-redundant pin/exclude.
        awaitResolution()
        val rewritten = Files.readString(root.categoriesFile)
        assertFalse("redundant pin must be pruned", rewritten.contains("x.arw"))
        assertFalse("redundant exclude must be pruned", rewritten.contains("y.jpg"))
    }

    @Test
    fun unknownRuleType_treatedAsManualAndPreservedAcrossRewrite() = runTest {
        // A rule type this build doesn't understand (a newer file after a downgrade, or a hand-edit)
        // must be treated as manual — its stored `photos` are the membership — and a later rewrite must
        // NOT silently wipe its `rule`/`excluded`/`photos`.
        val (repo, root) = repo(listOf(photo("a.jpg", 1, 1), photo("b.jpg", 2, 2)))
        writeCategoriesFile(
            root,
            """
            {
              "version": 2,
              "categories": [
                { "id": "future", "name": "Future", "builtIn": false,
                  "rule": { "type": "people-ai" },
                  "photos":   [ { "path": "a.jpg", "size": 1, "mtimeMs": 1 } ],
                  "excluded": [ { "path": "b.jpg", "size": 2, "mtimeMs": 2 } ] }
              ]
            }
            """.trimIndent(),
        )

        // Treated as manual: membership is exactly the stored photos, no rule interpretation.
        assertEquals(setOf(PhotoId("a.jpg")), repo.observeMemberships(root).value[CategoryId("future")])
        assertEquals(
            CategoryKind.MANUAL,
            repo.observeCategories(root).value.first { it.id == CategoryId("future") }.kind,
        )

        // A mutation elsewhere rewrites the file; the unknown rule + excluded + photos all survive.
        repo.toggleMembership(root, Category.FAVOURITES_ID, PhotoId("a.jpg"))
        val written = Files.readString(root.categoriesFile)
        assertTrue("unknown rule type preserved", written.contains("people-ai"))
        assertTrue("photos preserved", written.contains("\"a.jpg\""))
        assertTrue("excluded preserved", written.contains("\"b.jpg\""))

        // Reopening reads it back identically (still manual, same membership).
        val (reopened, _) = repo(listOf(photo("a.jpg", 1, 1), photo("b.jpg", 2, 2)))
        assertEquals(setOf(PhotoId("a.jpg")), reopened.observeMemberships(root).value[CategoryId("future")])
    }

    @Test
    fun smartRaw_cannotBeRenamedOrDeleted() = runTest {
        val (repo, root) = repo(listOf(photo("b.arw", 2, 2)))

        assertFailsWith<IllegalArgumentException> { repo.rename(root, Category.SMART_RAW_ID, "Nope") }
        assertFailsWith<IllegalArgumentException> { repo.delete(root, Category.SMART_RAW_ID) }
    }
}

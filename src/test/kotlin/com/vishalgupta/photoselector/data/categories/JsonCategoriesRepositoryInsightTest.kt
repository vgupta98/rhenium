package com.vishalgupta.photoselector.data.categories

import com.vishalgupta.photoselector.data.format.RawDecoder
import com.vishalgupta.photoselector.domain.insight.InsightBand
import com.vishalgupta.photoselector.domain.insight.InsightProviderId
import com.vishalgupta.photoselector.domain.insight.InsightSource
import com.vishalgupta.photoselector.domain.insight.InsightValue
import com.vishalgupta.photoselector.domain.insight.PredicateTreeResolver
import com.vishalgupta.photoselector.domain.insight.InsightAnalysisStatus
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryKind
import com.vishalgupta.photoselector.domain.model.CategoryRule
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RawFilesResolver
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.CategoryAnalysisState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/** Insight-backed smart-category behaviour: predicate-tree persistence, graceful degrade, and tri-state. */
class JsonCategoriesRepositoryInsightTest {

    @get:Rule val tmp = TemporaryFolder()

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val rawResolver = RawFilesResolver(RawDecoder.Companion.RawFormat.extensions)
    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private suspend fun awaitResolution() = resolverScope.coroutineContext.job.children.toList().joinAll()

    private fun photo(relative: String) = Photo(
        id = PhotoId(relative),
        absolutePath = tmp.root.toPath().resolve(relative),
        relativePath = relative,
        fileName = relative,
        sizeBytes = 1,
        lastModifiedEpochMs = 1,
    )

    /** Gated fake InsightSource: null until [analyzed], then a band per photo id. Mirrors the coordinator. */
    private class GatedSource(private val sharp: Set<PhotoId>) : InsightSource {
        var analyzed = false
        override suspend fun bandedValue(providerId: InsightProviderId, photo: Photo): InsightValue? {
            if (!analyzed) return null
            val band = if (photo.id in sharp) InsightBand.Sharp else InsightBand.Soft
            return InsightValue.Scalar(raw = 0f, band = band)
        }
    }

    private fun repo(
        scanned: List<Photo>,
        source: InsightSource,
        status: MutableStateFlow<InsightAnalysisStatus>,
    ): Pair<JsonCategoriesRepository, RootFolder> {
        val root = RootFolder(tmp.root.toPath())
        val repo = JsonCategoriesRepository(
            json = json,
            scannedPhotos = { scanned },
            ruleResolver = PredicateTreeResolver(rawResolver, source),
            scope = resolverScope,
            insightStatus = status,
        )
        return repo to root
    }

    private fun sharpMembers(repo: JsonCategoriesRepository, root: RootFolder) =
        repo.observeMemberships(root).value[Category.SMART_SHARP_ID].orEmpty()

    private fun analysisOf(repo: JsonCategoriesRepository, root: RootFolder, id: com.vishalgupta.photoselector.domain.model.CategoryId) =
        repo.observeAnalysisStates(root).value[id]

    @Test
    fun sharpSeed_persistsAsAnInsightLeafRule() = runTest {
        val (repo, root) = repo(listOf(photo("a.jpg")), GatedSource(emptySet()), MutableStateFlow(InsightAnalysisStatus.NotAnalyzed))
        awaitResolution()

        val sharp = repo.observeCategories(root).value.first { it.id == Category.SMART_SHARP_ID }
        assertEquals(CategoryKind.SMART, sharp.kind)
        assertTrue("sharp seed carries an insight leaf", sharp.rule is CategoryRule.Leaf)

        // Force a write (a manual pin), then assert the leaf serialized through toDto to disk.
        repo.toggleMembership(root, Category.SMART_SHARP_ID, PhotoId("a.jpg"))
        awaitResolution()
        assertTrue(
            "leaf rule persisted as insight-leaf",
            Files.readString(root.categoriesFile).contains("insight-leaf"),
        )

        // Round-trips: a fresh repo over the written file decodes the Sharp category back to a smart leaf.
        val (reopened, _) = repo(listOf(photo("a.jpg")), GatedSource(emptySet()), MutableStateFlow(InsightAnalysisStatus.NotAnalyzed))
        awaitResolution()
        assertTrue(reopened.observeCategories(root).value.first { it.id == Category.SMART_SHARP_ID }.rule is CategoryRule.Leaf)
    }

    @Test
    fun triState_notAnalyzedThenAnalyzed_fillsSharpAndFlipsSignal() = runTest {
        val photos = listOf(photo("a.jpg"), photo("b.jpg"))
        val source = GatedSource(sharp = setOf(PhotoId("a.jpg")))
        val status = MutableStateFlow(InsightAnalysisStatus.NotAnalyzed)
        val (repo, root) = repo(photos, source, status)
        awaitResolution()

        // Pre-analysis: Sharp is empty and its signal is NotAnalyzed; RawFiles / Favourites are AlwaysReady.
        assertEquals(emptySet<PhotoId>(), sharpMembers(repo, root))
        assertEquals(CategoryAnalysisState.NotAnalyzed, analysisOf(repo, root, Category.SMART_SHARP_ID))
        assertEquals(CategoryAnalysisState.AlwaysReady, analysisOf(repo, root, Category.SMART_RAW_ID))
        assertEquals(CategoryAnalysisState.AlwaysReady, analysisOf(repo, root, Category.FAVOURITES_ID))

        // Analysis completes: the pass gate opens and the status flips → Sharp fills, signal → Analyzed.
        source.analyzed = true
        status.value = InsightAnalysisStatus.Analyzed
        awaitResolution()

        assertEquals(setOf(PhotoId("a.jpg")), sharpMembers(repo, root))
        assertEquals(CategoryAnalysisState.Analyzed, analysisOf(repo, root, Category.SMART_SHARP_ID))
    }

    @Test
    fun staleSignal_isMappedThrough() = runTest {
        val source = GatedSource(setOf(PhotoId("a.jpg")))
        val status = MutableStateFlow(InsightAnalysisStatus.NotAnalyzed)
        val (repo, root) = repo(listOf(photo("a.jpg")), source, status)
        awaitResolution()
        source.analyzed = true
        status.value = InsightAnalysisStatus.Stale
        awaitResolution()
        assertEquals(CategoryAnalysisState.Stale, analysisOf(repo, root, Category.SMART_SHARP_ID))
    }

    @Test
    fun unknownProviderLeaf_staysSmartAndEmpty_notManual() = runTest {
        // A custom smart category whose leaf references an unregistered provider ("faces"). Its tree shape
        // is known, so it must stay SMART (not fall to the manual #120 path) and resolve to nothing.
        Files.writeString(
            RootFolder(tmp.root.toPath()).categoriesFile,
            """
            { "version": 2, "categories": [
              { "id": "faces-cat", "name": "Faces", "builtIn": false,
                "rule": { "type": "insight-leaf", "providerId": "faces", "comparator": "in-band", "band": "yes" } }
            ] }
            """.trimIndent(),
        )
        val (repo, root) = repo(listOf(photo("a.jpg")), GatedSource(emptySet()), MutableStateFlow(InsightAnalysisStatus.Analyzed))
        awaitResolution()

        val faces = repo.observeCategories(root).value.first { it.id.value == "faces-cat" }
        assertEquals(CategoryKind.SMART, faces.kind)
        assertEquals(emptySet<PhotoId>(), repo.observeMemberships(root).value[faces.id].orEmpty())
    }
}

package com.vishalgupta.photoselector.presentation.people

import com.vishalgupta.photoselector.data.faces.JsonPeopleRepository
import com.vishalgupta.photoselector.domain.faces.FaceBox
import com.vishalgupta.photoselector.domain.faces.FaceId
import com.vishalgupta.photoselector.domain.faces.FaceRef
import com.vishalgupta.photoselector.domain.faces.Person
import com.vishalgupta.photoselector.domain.faces.PersonId
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.CategoryRule
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.presentation.common.FaceScanCoordinator
import com.vishalgupta.photoselector.testing.FakeCategoriesRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The People screen's view model owns the one thing the headless engine deliberately does not: the
 * *join* between a person and their smart category. These tests pin that join — naming creates the
 * category, renaming follows it, a duplicate name is refused, and the purge tears all three stores
 * down — over the real [JsonPeopleRepository] (a temp sidecar) and the shared categories fake.
 */
class PeopleViewModelTest {

    @get:Rule val tmp = TemporaryFolder()

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    private fun photo(id: String) = Photo(
        id = PhotoId(id),
        absolutePath = Path.of("/photos/$id.jpg"),
        relativePath = "$id.jpg",
        fileName = "$id.jpg",
        sizeBytes = 1,
        lastModifiedEpochMs = 0,
    )

    private val photos = listOf("a", "b", "c").map(::photo)

    private fun person(id: String, name: String? = null, photoIds: List<String> = listOf("a"), dismissed: Boolean = false) =
        Person(
            id = PersonId(id),
            name = name,
            faces = photoIds.mapIndexed { i, p ->
                FaceRef(FaceId(PhotoId(p), i), box = FaceBox(0.4f, 0.3f, 0.2f, 0.2f), score = 0.9f + i)
            },
            centroid = listOf(1f, 0f),
            dismissed = dismissed,
        )

    private class Fixture(
        val vm: PeopleViewModel,
        val people: JsonPeopleRepository,
        val categories: FakeCategoriesRepository,
        val root: RootFolder,
    )

    private fun fixture(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        categories: FakeCategoriesRepository = FakeCategoriesRepository(),
    ): Fixture {
        val root = RootFolder(tmp.root.toPath())
        val peopleRepo = JsonPeopleRepository(json, ioDispatcher = dispatcher)
        // Deleting a person's category disposes of the person, exactly as AppContainer wires it —
        // the purge test would otherwise pass against a hook production has and the test doesn't.
        categories.onCategoryDeleted = { r, category ->
            (category.rule as? CategoryRule.Person)?.let { peopleRepo.delete(r, PersonId(it.personId)) }
        }
        val coordinator = FaceScanCoordinator(
            // No models in a unit test: the coordinator resolves this on the pass, which is exactly
            // how the real "face scanning unavailable" signal is produced.
            scanner = { null },
            people = peopleRepo,
            currentRoot = { root },
            parentJob = null,
            dispatcher = dispatcher,
        )
        val vm = PeopleViewModel(
            root = root,
            people = peopleRepo,
            categories = categories,
            scanCoordinator = coordinator,
            photosForRoot = { photos },
            dispatcher = dispatcher,
        )
        return Fixture(vm, peopleRepo, categories, root)
    }

    @Test
    fun namingAPersonCreatesTheirSmartCategory() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val f = fixture(dispatcher)
        f.people.replaceAll(f.root, listOf(person("p1", photoIds = listOf("a", "b"))))
        advanceUntilIdle()

        f.vm.name(PersonId("p1"), "  Alice  ")
        advanceUntilIdle()

        val category = assertNotNull(
            f.categories.observeCategories(f.root).value.firstOrNull { it.rule is CategoryRule.Person },
            "naming a person must create their smart category, or they are invisible outside People",
        )
        assertEquals("Alice", category.name)
        assertEquals(CategoryRule.Person("p1"), category.rule)
        assertEquals("Alice", f.people.observePeople(f.root).value.single().name)
        assertEquals(listOf("Alice"), f.vm.state.value.named.map { it.name })
        assertTrue(f.vm.state.value.toName.isEmpty())
    }

    @Test
    fun renamingAPersonRenamesTheirExistingCategoryRatherThanAddingASecond() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val f = fixture(dispatcher)
        f.people.replaceAll(f.root, listOf(person("p1")))
        advanceUntilIdle()

        f.vm.name(PersonId("p1"), "Alice")
        advanceUntilIdle()
        f.vm.name(PersonId("p1"), "Alicia")
        advanceUntilIdle()

        val personCategories = f.categories.observeCategories(f.root).value.filter { it.rule is CategoryRule.Person }
        assertEquals(1, personCategories.size, "a rename must not fork a second person category")
        assertEquals("Alicia", personCategories.single().name)
    }

    @Test
    fun aNameAlreadyUsedByAnotherCategoryIsRefused() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val f = fixture(
            dispatcher,
            FakeCategoriesRepository(listOf(Category.favourites(), Category(CategoryId("k"), "Keepers", builtIn = false))),
        )
        f.people.replaceAll(f.root, listOf(person("p1")))
        advanceUntilIdle()

        f.vm.name(PersonId("p1"), "keepers")
        advanceUntilIdle()

        assertNotNull(f.vm.state.value.nameError, "a duplicate name must be reported, not silently taken")
        assertNull(f.people.observePeople(f.root).value.single().name)
        assertTrue(f.categories.observeCategories(f.root).value.none { it.rule is CategoryRule.Person })
    }

    @Test
    fun skippingAClusterPersistsSoARescanCannotThrowTheDecisionAway() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val f = fixture(dispatcher)
        f.people.replaceAll(f.root, listOf(person("p1"), person("p2", photoIds = listOf("b"))))
        advanceUntilIdle()

        f.vm.dismiss(PersonId("p1"))
        advanceUntilIdle()

        assertEquals(listOf(PersonId("p2")), f.vm.state.value.toName.map { it.id })
        assertEquals(listOf(PersonId("p1")), f.vm.state.value.skipped.map { it.id })
        // Re-read from disk: the dismissal is what anchors the cluster through the next scan.
        assertTrue(JsonPeopleRepository(json, ioDispatcher = dispatcher).observePeople(f.root).value.first { it.id == PersonId("p1") }.dismissed)

        f.vm.restore(PersonId("p1"))
        advanceUntilIdle()
        assertEquals(2, f.vm.state.value.toName.size)
    }

    @Test
    fun purgeDeletesPersonCategoriesThePeopleFileAndTheCache() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val f = fixture(dispatcher)
        f.people.replaceAll(f.root, listOf(person("p1"), person("p2", photoIds = listOf("b"))))
        advanceUntilIdle()
        f.vm.name(PersonId("p1"), "Alice")
        advanceUntilIdle()
        assertEquals(1, f.vm.personCategoryCount())

        var cacheCleared = false
        f.vm.purgeAllFaceData { cacheCleared = true }
        advanceUntilIdle()

        assertTrue(cacheCleared, "the shared face cache is the third store a purge must clear")
        assertTrue(f.categories.observeCategories(f.root).value.none { it.rule is CategoryRule.Person })
        assertTrue(f.people.observePeople(f.root).value.isEmpty())
        assertTrue(f.vm.state.value.isEmpty)
        assertTrue(java.nio.file.Files.notExists(f.root.peopleFile), "the sidecar itself is removed")
    }

    @Test
    fun anUnreadableSidecarIsReportedRatherThanShownAsAnEmptyLibrary() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val root = RootFolder(tmp.root.toPath())
        java.nio.file.Files.writeString(root.peopleFile, "{ not json at all")
        val f = fixture(dispatcher)
        advanceUntilIdle()

        assertTrue(
            f.vm.state.value.unreadable,
            "a sidecar we cannot read silently discards every write; the screen must say so",
        )
    }

    @Test
    fun scanningWithoutModelsLatchesTheUnavailableState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val f = fixture(dispatcher)
        advanceUntilIdle()
        // Availability is unknown until something actually tries — the models load lazily.
        assertTrue(f.vm.state.value.available)

        f.vm.scan()
        advanceUntilIdle()

        assertTrue(
            !f.vm.state.value.available,
            "a scan that found no loadable models must read as unavailable, never as 'no faces here'",
        )
    }
}

package com.vishalgupta.photoselector.data.faces

import com.vishalgupta.photoselector.domain.faces.FaceId
import com.vishalgupta.photoselector.domain.faces.Person
import com.vishalgupta.photoselector.domain.faces.PersonId
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JsonPeopleRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun repo() = JsonPeopleRepository(json) to RootFolder(tmp.root.toPath())

    private fun person(id: String, name: String? = null, photos: List<String> = listOf("a")) = Person(
        id = PersonId(id),
        name = name,
        faces = photos.mapIndexed { i, p -> FaceId(PhotoId(p), i) },
        centroid = listOf(0.5f, -0.25f),
    )

    private fun writePeopleFile(root: RootFolder, content: String) =
        Files.writeString(root.peopleFile, content)

    private fun readPeopleFile(root: RootFolder) = Files.readString(root.peopleFile)

    @Test
    fun writeThenReadRoundTripsEveryField() = runTest {
        val (repo, root) = repo()
        repo.replaceAll(root, listOf(person("p1", name = "Alice", photos = listOf("a", "b")), person("p2")))

        val (reopened, _) = repo()
        val people = reopened.observePeople(root).value

        assertEquals(2, people.size)
        val alice = people.first { it.id == PersonId("p1") }
        assertEquals("Alice", alice.name)
        assertTrue(alice.isNamed)
        assertEquals(listOf(FaceId(PhotoId("a"), 0), FaceId(PhotoId("b"), 1)), alice.faces)
        assertEquals(listOf(0.5f, -0.25f), alice.centroid)
        assertEquals(null, people.first { it.id == PersonId("p2") }.name)
    }

    @Test
    fun theFileIsWrittenAsV1() = runTest {
        val (repo, root) = repo()
        repo.replaceAll(root, listOf(person("p1")))

        assertTrue(readPeopleFile(root).contains("\"version\": 1"))
    }

    @Test
    fun renameIsThePersistedUserEdit() = runTest {
        val (repo, root) = repo()
        repo.replaceAll(root, listOf(person("p1")))

        repo.rename(root, PersonId("p1"), "  Bob  ")

        assertEquals("Bob", repo.observePeople(root).value.single().name)
        val (reopened, _) = repo()
        assertEquals("Bob", reopened.observePeople(root).value.single().name)
    }

    @Test
    fun renamingToBlankClearsTheName() = runTest {
        val (repo, root) = repo()
        repo.replaceAll(root, listOf(person("p1", name = "Alice")))

        repo.rename(root, PersonId("p1"), "   ")

        assertEquals(null, repo.observePeople(root).value.single().name)
    }

    @Test
    fun deleteRemovesThePersonFromDisk() = runTest {
        val (repo, root) = repo()
        repo.replaceAll(root, listOf(person("p1", name = "Alice"), person("p2")))

        repo.delete(root, PersonId("p1"))

        assertEquals(listOf(PersonId("p2")), repo.observePeople(root).value.map { it.id })
        assertTrue(!readPeopleFile(root).contains("Alice"))
    }

    @Test
    fun photosOfIsTheLookupThePersonRuleResolves() = runTest {
        val (repo, root) = repo()
        repo.replaceAll(root, listOf(person("p1", photos = listOf("a", "b", "a"))))

        assertEquals(setOf(PhotoId("a"), PhotoId("b")), repo.photosOf(PersonId("p1")))
        assertEquals(emptySet(), repo.photosOf(PersonId("unknown")))
    }

    @Test
    fun aFieldFromANewerBuildSurvivesARewrite() = runTest {
        // `ignoreUnknownKeys` only keeps an unknown key from *failing* the decode. Preserving it is
        // the repository's job, or a downgrade silently destroys a newer build's data.
        val (_, root) = repo()
        writePeopleFile(
            root,
            """
            {
              "version": 1,
              "people": [
                { "id": "p1", "name": "Alice", "faces": [], "centroid": [],
                  "thumbnailFaceIndex": 3, "confidence": 0.87 }
              ]
            }
            """.trimIndent(),
        )

        val (repo, _) = repo()
        repo.rename(root, PersonId("p1"), "Alicia")

        val written = readPeopleFile(root)
        assertTrue(written.contains("thumbnailFaceIndex"), "unknown field must survive a rewrite")
        assertTrue(written.contains("0.87"))
        assertTrue(written.contains("Alicia"), "and this build's edit must still win")
    }

    @Test
    fun aCorruptFileDegradesToNoPeople() = runTest {
        val (_, root) = repo()
        writePeopleFile(root, "{ this is not json")

        val (repo, _) = repo()
        assertEquals(emptyList(), repo.observePeople(root).value)

        // And the repository still works from there - a new scan simply overwrites the bad file.
        repo.replaceAll(root, listOf(person("p1", name = "Alice")))
        assertEquals("Alice", assertNotNull(repo.observePeople(root).value.singleOrNull()).name)
    }

    @Test
    fun clearContextDropsTheBoundRoot() = runTest {
        val (repo, root) = repo()
        repo.replaceAll(root, listOf(person("p1")))

        repo.clearContext()

        assertEquals(emptySet(), repo.photosOf(PersonId("p1")))
    }
}

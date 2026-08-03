package com.vishalgupta.photoselector.data.faces

import com.vishalgupta.photoselector.domain.faces.FaceBox
import com.vishalgupta.photoselector.domain.faces.FaceId
import com.vishalgupta.photoselector.domain.faces.FaceRef
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
        faces = photos.mapIndexed { i, p -> FaceRef(FaceId(PhotoId(p), i)) },
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
        assertEquals(listOf(FaceId(PhotoId("a"), 0), FaceId(PhotoId("b"), 1)), alice.faceIds)
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
        repo.replaceAll(root, listOf(person("p1", photos = listOf("a", "b", "a")), person("p2", photos = emptyList())))

        assertEquals(setOf(PhotoId("a"), PhotoId("b")), repo.photosOf(root, PersonId("p1")))
        // A person who exists but appears in nothing is an authoritative EMPTY answer...
        assertEquals(emptySet(), repo.photosOf(root, PersonId("p2")))
        // ...while a person this root has never heard of is "can't answer": null, never empty. The
        // categories repository prunes stored pins/excludes against a match set, so conflating the
        // two would delete the user's corrections from disk.
        assertEquals(null, repo.photosOf(root, PersonId("unknown")))
    }

    @Test
    fun photosOfBindsTheRootItself_soNothingHasToObserveFirst() = runTest {
        // The lookup is the *first* thing that touches this repository in production - the categories
        // rule pass calls it. Before this bound on demand, it answered from an empty flow for every
        // root, which is exactly the answer that prunes a user's overrides away.
        val (writer, root) = repo()
        writer.replaceAll(root, listOf(person("p1", name = "Alice", photos = listOf("a"))))

        val (fresh, _) = repo()
        assertEquals(setOf(PhotoId("a")), fresh.photosOf(root, PersonId("p1")))
    }

    @Test
    fun photosOfOnARootWithNoSidecarCannotAnswer() = runTest {
        val (repo, root) = repo()

        assertEquals(null, repo.photosOf(root, PersonId("p1")))
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
    fun aCorruptFileIsSurfacedAsEmptyAndNeverClobbered() = runTest {
        // The file holds the one thing a rescan cannot regenerate - the names - so an unreadable one
        // is preserved for salvage, not overwritten. Same posture as JsonCategoriesRepository.
        val (_, root) = repo()
        val corrupt = "{ this is not json"
        writePeopleFile(root, corrupt)

        val (repo, _) = repo()
        assertEquals(emptyList(), repo.observePeople(root).value)

        repo.replaceAll(root, listOf(person("p1", name = "Alice")))
        repo.rename(root, PersonId("p1"), "Bob")
        repo.delete(root, PersonId("p1"))

        assertEquals(corrupt, readPeopleFile(root), "unreadable file must be left untouched")
    }

    @Test
    fun aFileFromANewerVersionSurvivesAScanRenameAndDeleteByteIntact() = runTest {
        // Reading a future v2 as "no people yet" and then rewriting it as v1 would destroy the whole
        // newer file - strictly worse than not understanding it. So a version we don't know leaves
        // the root unbound, exactly like a corrupt one.
        val (_, root) = repo()
        val v2 = """
            {
              "version": 2,
              "people": [ { "id": "p1", "displayName": "Alice", "clusters": [ 1, 2, 3 ] } ]
            }
        """.trimIndent()
        writePeopleFile(root, v2)

        val (repo, _) = repo()
        assertEquals(emptyList(), repo.observePeople(root).value)
        // "Can't answer" - so a person category won't prune the user's overrides against it either.
        assertEquals(null, repo.photosOf(root, PersonId("p1")))

        repo.replaceAll(root, listOf(person("p9", name = "Someone")))
        repo.rename(root, PersonId("p9"), "Else")
        repo.delete(root, PersonId("p9"))

        assertEquals(v2, readPeopleFile(root), "a newer-version file must survive byte-intact")
    }

    @Test
    fun aFaceBoxAndScoreRoundTripSoACropCanBeDrawnWithoutTheModels() = runTest {
        // The whole point of storing the box: composing a face-cache key needs both ONNX model ids,
        // so a UI that looked the box up there would open both sessions just to draw a thumbnail.
        val (repo, root) = repo()
        val ref = FaceRef(FaceId(PhotoId("a"), 2), box = FaceBox(0.1f, 0.2f, 0.3f, 0.4f), score = 0.94f)
        repo.replaceAll(root, listOf(Person(PersonId("p1"), name = "Alice", faces = listOf(ref))))

        val (reopened, _) = repo()
        val restored = reopened.observePeople(root).value.single().faces.single()
        assertEquals(ref, restored)
        assertEquals(ref, reopened.observePeople(root).value.single().coverFace)
    }

    @Test
    fun aDismissalRoundTripsSoARescanCannotUndoIt() = runTest {
        val (repo, root) = repo()
        repo.replaceAll(root, listOf(person("p1")))

        repo.setDismissed(root, PersonId("p1"), dismissed = true)

        val (reopened, _) = repo()
        val restored = reopened.observePeople(root).value.single()
        assertTrue(restored.dismissed)
        assertTrue(restored.isAnchor, "a dismissal must anchor the cluster through the next recluster")
    }

    @Test
    fun aBoxWrittenWithTheWrongShapeDegradesToNoCropRatherThanFailingTheDecode() = runTest {
        val (_, root) = repo()
        writePeopleFile(
            root,
            """
            { "version": 1, "people": [ { "id": "p1", "faces": [ { "photo": "a", "box": [0.1, 0.2] } ] } ] }
            """.trimIndent(),
        )

        val (repo, _) = repo()
        val face = repo.observePeople(root).value.single().faces.single()
        assertEquals(null, face.box)
        assertEquals(FaceId(PhotoId("a"), 0), face.id)
    }

    @Test
    fun deleteAllRemovesTheSidecarEvenWhenItCouldNotBeRead() = runTest {
        // The refuse-to-write posture protects names the user might still want salvaged. A purge is
        // precisely the statement that they do not, so it is the one mutation that overrides it.
        val (_, root) = repo()
        writePeopleFile(root, "{ this is not json")

        val (repo, _) = repo()
        assertTrue(repo.isUnreadable(root).value, "an undecodable sidecar must be reported, not read as empty")

        repo.deleteAll(root)

        assertTrue(Files.notExists(root.peopleFile))
        assertTrue(!repo.isUnreadable(root).value, "after the purge the root is readable again")
        // And it can be written to again, which was the whole problem while it was unreadable.
        repo.replaceAll(root, listOf(person("p1", name = "Alice")))
        assertEquals("Alice", repo.observePeople(root).value.single().name)
    }

    @Test
    fun clearContextDropsTheBoundRoot() = runTest {
        val (repo, root) = repo()
        repo.replaceAll(root, listOf(person("p1")))

        repo.clearContext()

        // Unbound again - but photosOf re-binds on demand, so the answer stays correct rather than
        // silently becoming "no photos" for the next root that asks.
        assertEquals(setOf(PhotoId("a")), repo.photosOf(root, PersonId("p1")))
    }
}

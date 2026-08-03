package com.vishalgupta.photoselector.data.faces

import com.vishalgupta.photoselector.data.io.AtomicJsonWriter
import com.vishalgupta.photoselector.domain.faces.FaceId
import com.vishalgupta.photoselector.domain.faces.Person
import com.vishalgupta.photoselector.domain.faces.PersonId
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.PeopleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files

/**
 * Persists the face pipeline's people to `<root>/.photo-selector-people.json` (**v1**), written
 * atomically through the shared [AtomicJsonWriter] — the same machinery categories, browse position
 * and the XMP toggle already use, no new persistence layer.
 *
 * Deliberately simpler than `JsonCategoriesRepository`: there is no membership merge, no migration
 * and no async rule pass, because a person's photos are pure scan output. The one user-authored
 * field is [rename]'s name, so that is the only thing this file exists to keep.
 *
 * Faces are stored by [PhotoId] rather than by the categories file's path+size+mtime descriptor: a
 * face reference is *derived* data that the next scan rebuilds anyway, and a named person survives a
 * folder move through the stored centroid re-match, not through its face list. Keeping the shape
 * minimal is the point — the file is rewritten wholesale on every scan.
 *
 * A file that exists but cannot be decoded — corrupt, or written by a build that knows a later
 * version — leaves the root **unbound**, so no write can clobber it. That is deliberately the same
 * refuse-to-write posture `JsonCategoriesRepository` takes, and for the same reason: the file holds
 * the one thing a rescan cannot regenerate (the names), so an unreadable one is to be preserved for
 * salvage, not overwritten.
 *
 * The root then stays unbound until the file becomes readable, and **mutations do not take effect**:
 * every entry point re-binds first, which re-clears the in-memory set, so a rename or delete finds
 * nothing to act on and a [replaceAll] is discarded by the next read. People are surfaced as empty
 * throughout — and [photosOf] answers "can't resolve", which is what stops a person category pruning
 * the user's pins and excludes against a membership this repository could not compute. Nothing here
 * reports *why* the set is empty; PR-1b adds that signal alongside whatever renders it.
 *
 * PII: never log a person's name.
 */
class JsonPeopleRepository(
    private val json: Json,
) : PeopleRepository {

    private val mutex = Mutex()
    private val peopleFlow = MutableStateFlow<List<Person>>(emptyList())

    private var boundRoot: RootFolder? = null

    // Raw JSON per person id, so a field a newer build wrote survives our rewrite untouched.
    private var rawById: Map<String, StoredPerson> = emptyMap()

    override fun observePeople(root: RootFolder): StateFlow<List<Person>> {
        if (boundRoot?.path != root.path) bind(root)
        return peopleFlow.asStateFlow()
    }

    override suspend fun replaceAll(root: RootFolder, people: List<Person>) {
        if (boundRoot?.path != root.path) bind(root)
        mutex.withLock {
            peopleFlow.value = people
            // Drop carried-through JSON for people that no longer exist; keep it for those that do.
            rawById = rawById.filterKeys { id -> people.any { it.id.value == id } }
            writeToDisk(root)
        }
    }

    override suspend fun rename(root: RootFolder, id: PersonId, name: String?) {
        if (boundRoot?.path != root.path) bind(root)
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        mutex.withLock {
            val current = peopleFlow.value
            if (current.none { it.id == id }) return@withLock
            peopleFlow.value = current.map { if (it.id == id) it.copy(name = trimmed) else it }
            writeToDisk(root)
        }
    }

    override suspend fun delete(root: RootFolder, id: PersonId) {
        if (boundRoot?.path != root.path) bind(root)
        mutex.withLock {
            val current = peopleFlow.value
            if (current.none { it.id == id }) return@withLock
            peopleFlow.value = current.filterNot { it.id == id }
            rawById = rawById - id.value
            writeToDisk(root)
        }
    }

    override fun photosOf(root: RootFolder, id: PersonId): Set<PhotoId>? {
        // Binds on demand, exactly like observePeople - the rule pass is the first thing that ever
        // asks about people, and an unbound repository answering "no photos" would let the
        // categories repository prune the user's pins/excludes away (see PeopleRepository.photosOf).
        if (boundRoot?.path != root.path) bind(root)
        return peopleFlow.value.firstOrNull { it.id == id }?.photos
    }

    override suspend fun clearContext() {
        mutex.withLock {
            boundRoot = null
            rawById = emptyMap()
            peopleFlow.value = emptyList()
        }
    }

    private fun bind(root: RootFolder) {
        val stored = loadFromDisk(root)
        if (stored == null) {
            // An existing file we cannot read (corrupt, or a version from a newer build). Leave the
            // root UNBOUND: writeToDisk early-returns while boundRoot is null, so the next scan or
            // rename cannot overwrite - and thereby destroy - a file that may still hold the user's
            // names. The next call re-reads and recovers once the file is readable again. Same
            // posture, and the same reasoning, as JsonCategoriesRepository.bind.
            rawById = emptyMap()
            peopleFlow.value = emptyList()
            boundRoot = null
            return
        }
        rawById = stored.associateBy { it.dto.id }
        peopleFlow.value = stored.map { it.dto.toDomain() }
        boundRoot = root
    }

    /**
     * The stored people, or **null** when a file exists but could not be decoded — so [bind] can
     * refuse to bind rather than expose an empty model that a later write would persist over it.
     * A root with no file at all is simply empty, and binds normally.
     */
    private fun loadFromDisk(root: RootFolder): List<StoredPerson>? {
        val file = root.peopleFile
        if (!Files.exists(file)) return emptyList()
        return runCatching { PeopleFile.decode(json, Files.readString(file)) }.getOrNull()
    }

    private suspend fun writeToDisk(root: RootFolder) {
        if (boundRoot?.path != root.path) return
        val bytes = PeopleFile.encode(
            json,
            peopleFlow.value.map { person ->
                StoredPerson(dto = person.toDto(), raw = rawById[person.id.value]?.raw)
            },
        )
        try {
            withContext(Dispatchers.IO) { AtomicJsonWriter.write(root.peopleFile, bytes) }
        } catch (_: Throwable) {
            // Read-only volume or a locked file: the set is still correct in memory for this session.
        }
    }

    private fun PersonDto.toDomain(): Person = Person(
        id = PersonId(id),
        name = name?.takeIf { it.isNotBlank() },
        faces = faces.map { FaceId(PhotoId(it.photo), it.index) },
        centroid = centroid,
    )

    private fun Person.toDto(): PersonDto = PersonDto(
        id = id.value,
        name = name,
        faces = faces.map { FaceRefDto(photo = it.photo.value, index = it.index) },
        centroid = centroid,
    )
}

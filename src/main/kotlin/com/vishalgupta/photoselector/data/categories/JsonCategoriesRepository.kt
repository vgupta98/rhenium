package com.vishalgupta.photoselector.data.categories

import com.vishalgupta.photoselector.data.io.AtomicJsonWriter
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.CategoriesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Persists flat photo categories to `<root>/.photo-selector-categories.json` (v2).
 *
 * On bind, persisted entries are resolved against the current scan (via [scannedPhotos])
 * so memberships re-attach after a folder rename or move — see [MembershipResolver]. The
 * in-memory model is metadata ([categoriesFlow]) plus a single membership map
 * ([membershipsFlow]) of *current* [PhotoId]s; writes re-serialise the v2 descriptor form.
 *
 * Migration: the first time a root with only the legacy `.photo-selector-favourites.json`
 * is bound (against a populated scan), its entries are transcoded verbatim into the
 * built-in Favourites category, written as v2, and the old file is renamed to `.bak`.
 */
class JsonCategoriesRepository(
    private val json: Json,
    private val scannedPhotos: (RootFolder) -> List<Photo>,
    private val idGenerator: () -> CategoryId = { CategoryId(UUID.randomUUID().toString()) },
) : CategoriesRepository {

    private val mutex = Mutex()

    private var boundRoot: RootFolder? = null
    private var photosById: Map<PhotoId, Photo> = emptyMap()
    private val categoriesFlow = MutableStateFlow(Category.builtIns)
    private val membershipsFlow = MutableStateFlow<Map<CategoryId, Set<PhotoId>>>(emptyMap())
    private val readOnly = MutableStateFlow(false)

    override fun observeCategories(root: RootFolder): StateFlow<List<Category>> {
        if (boundRoot?.path != root.path) bind(root)
        return categoriesFlow.asStateFlow()
    }

    override fun observeMemberships(root: RootFolder): StateFlow<Map<CategoryId, Set<PhotoId>>> {
        if (boundRoot?.path != root.path) bind(root)
        return membershipsFlow.asStateFlow()
    }

    override fun isReadOnly(root: RootFolder): StateFlow<Boolean> {
        if (boundRoot?.path != root.path) bind(root)
        return readOnly.asStateFlow()
    }

    override suspend fun create(root: RootFolder, name: String): CategoryId {
        if (boundRoot?.path != root.path) bind(root)
        return mutex.withLock {
            val id = idGenerator()
            categoriesFlow.value = categoriesFlow.value + Category(id, name.trim(), builtIn = false)
            membershipsFlow.value = membershipsFlow.value + (id to emptySet())
            writeToDisk(root)
            id
        }
    }

    override suspend fun rename(root: RootFolder, id: CategoryId, newName: String) {
        require(id !in Category.BUILT_IN_IDS) { "A built-in category cannot be renamed." }
        if (boundRoot?.path != root.path) bind(root)
        mutex.withLock {
            categoriesFlow.value = categoriesFlow.value.map {
                if (it.id == id) it.copy(name = newName.trim()) else it
            }
            writeToDisk(root)
        }
    }

    override suspend fun delete(root: RootFolder, id: CategoryId) {
        require(id !in Category.BUILT_IN_IDS) { "A built-in category cannot be deleted." }
        if (boundRoot?.path != root.path) bind(root)
        mutex.withLock {
            categoriesFlow.value = categoriesFlow.value.filterNot { it.id == id }
            membershipsFlow.value = membershipsFlow.value - id
            writeToDisk(root)
        }
    }

    override suspend fun toggleMembership(root: RootFolder, id: CategoryId, photo: PhotoId): Boolean {
        if (boundRoot?.path != root.path) bind(root)
        return mutex.withLock {
            val current = membershipsFlow.value[id].orEmpty()
            val nowMember = photo !in current
            val updated = if (nowMember) current + photo else current - photo
            membershipsFlow.value = membershipsFlow.value + (id to updated)
            writeToDisk(root)
            nowMember
        }
    }

    override suspend fun addMemberships(root: RootFolder, id: CategoryId, photos: Collection<PhotoId>): Int {
        if (boundRoot?.path != root.path) bind(root)
        if (photos.isEmpty()) return 0
        return mutex.withLock {
            val current = membershipsFlow.value[id].orEmpty()
            val added = photos.filterTo(mutableSetOf()) { it !in current }
            if (added.isEmpty()) return@withLock 0
            membershipsFlow.value = membershipsFlow.value + (id to (current + added))
            // One disk write for the whole batch — filing 100 tiles touches the file once.
            writeToDisk(root)
            added.size
        }
    }

    override suspend fun removeMemberships(root: RootFolder, photos: Collection<PhotoId>) {
        if (boundRoot?.path != root.path) bind(root)
        if (photos.isEmpty()) return
        val toRemove = photos.toSet()
        mutex.withLock {
            val current = membershipsFlow.value
            val next = current.mapValues { (_, ids) -> ids - toRemove }
            // Nothing was filed anywhere — skip the write so a delete of un-categorised photos
            // leaves the file untouched.
            if (next == current) return@withLock
            membershipsFlow.value = next
            writeToDisk(root)
        }
    }

    override suspend fun clearContext() {
        mutex.withLock {
            boundRoot = null
            photosById = emptyMap()
            categoriesFlow.value = Category.builtIns
            membershipsFlow.value = emptyMap()
            readOnly.value = false
        }
    }

    private fun bind(root: RootFolder) {
        // Synchronous read on the calling thread is acceptable: small file, infrequent.
        val scanned = scannedPhotos(root)
        val loaded = loadFromDisk(root)
        if (loaded == null) {
            // An existing file failed to decode (corrupt, locked, or partially written).
            // Surface it as read-only and leave the root unbound: writeToDisk early-returns
            // while boundRoot is null, so the next mutation can't overwrite — and thereby
            // destroy — a file that may still be salvageable. The next observe/mutation
            // re-reads and recovers once the file is readable again.
            readOnly.value = true
            boundRoot = null
            return
        }
        photosById = scanned.associateBy { it.id }
        categoriesFlow.value = loaded.map { Category(CategoryId(it.id), it.name, it.builtIn) }
        membershipsFlow.value = loaded.associate {
            CategoryId(it.id) to MembershipResolver.resolve(it.photos, scanned)
        }
        readOnly.value = !Files.isWritable(root.path)
        // Only treat the root as bound once we've resolved against a populated scan.
        // Resolving non-empty memberships against an empty scan (scan results not set
        // yet) would silently drop them and stick — leaving boundRoot null lets the next
        // observe/mutation re-bind and recover. Migration is likewise deferred until then.
        if (scanned.isNotEmpty()) {
            boundRoot = root
            if (!readOnly.value && shouldMigrate(root)) migrateLegacyFavourites(root, loaded)
        } else {
            boundRoot = null
        }
    }

    private fun shouldMigrate(root: RootFolder): Boolean =
        !Files.exists(root.categoriesFile) && Files.exists(root.favouritesFile)

    /** Transcodes the just-loaded categories to v2 verbatim and retires the legacy file. */
    private fun migrateLegacyFavourites(root: RootFolder, loaded: List<CategoryDto>) {
        runCatching {
            AtomicJsonWriter.write(root.categoriesFile, CategoriesFile.encode(json, loaded))
            Files.move(root.favouritesFile, root.favouritesBackupFile, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { readOnly.value = true }
    }

    /** Reads the categories file (v2), migrates a legacy favourites file, or starts fresh —
     *  always returning a normalised list with the built-in categories first. Returns null
     *  when a file *exists* but fails to decode, so [bind] can refuse to bind rather than
     *  expose an empty model that a later write would persist over the unreadable file. */
    private fun loadFromDisk(root: RootFolder): List<CategoryDto>? {
        val categoriesFile = root.categoriesFile
        if (Files.exists(categoriesFile)) {
            val decoded = runCatching { CategoriesFile.decode(json, Files.readString(categoriesFile)) }
                .getOrElse { return null }
            return normalise(decoded)
        }
        val favouritesFile = root.favouritesFile
        if (Files.exists(favouritesFile)) {
            val entries = runCatching { LegacyFavouritesFile.decode(json, Files.readString(favouritesFile)) }
                .getOrElse { return null }
            // Seed only the legacy Favourites entries; normalise adds the other built-ins (empty).
            val favourites = CategoryDto(FAVOURITES_ID, Category.FAVOURITES_NAME, builtIn = true, photos = entries)
            return normalise(listOf(favourites))
        }
        return normalise(emptyList())
    }

    /**
     * Guarantees every built-in category exists, is marked built-in, comes first in canonical
     * order ([Category.builtIns]), and is the only built-in; custom categories keep their order
     * after them. Each built-in's stored photos (matched by id) are preserved — a freshly-seeded
     * or legacy file simply starts the missing ones (e.g. Rejects) empty.
     */
    private fun normalise(categories: List<CategoryDto>): List<CategoryDto> {
        val builtIns = Category.builtIns.map { builtIn ->
            val photos = categories.firstOrNull { it.id == builtIn.id.value }?.photos.orEmpty()
            CategoryDto(builtIn.id.value, builtIn.name, builtIn = true, photos = photos)
        }
        val builtInIds = Category.BUILT_IN_IDS.mapTo(HashSet()) { it.value }
        val rest = categories.filterNot { it.id in builtInIds }.map { it.copy(builtIn = false) }
        return builtIns + rest
    }

    private suspend fun writeToDisk(root: RootFolder) {
        if (boundRoot?.path != root.path) return
        val dtos = categoriesFlow.value.map { category ->
            val ids = membershipsFlow.value[category.id].orEmpty()
            val photos = ids.mapNotNull { photosById[it] }
                .map { PhotoEntryDto(it.relativePath, it.sizeBytes, it.lastModifiedEpochMs) }
                .sortedBy { it.path }
            CategoryDto(category.id.value, category.name, category.builtIn, photos)
        }
        val bytes = CategoriesFile.encode(json, dtos)
        try {
            withContext(Dispatchers.IO) {
                AtomicJsonWriter.write(root.categoriesFile, bytes)
            }
            readOnly.value = false
        } catch (_: Throwable) {
            readOnly.value = true
        }
    }

    private companion object {
        val FAVOURITES_ID: String = Category.FAVOURITES_ID.value
    }
}

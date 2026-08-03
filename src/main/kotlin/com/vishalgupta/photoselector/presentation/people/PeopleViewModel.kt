package com.vishalgupta.photoselector.presentation.people

import com.vishalgupta.photoselector.domain.faces.FaceBox
import com.vishalgupta.photoselector.domain.faces.Person
import com.vishalgupta.photoselector.domain.faces.PersonId
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryRule
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.CategoriesRepository
import com.vishalgupta.photoselector.domain.repository.PeopleRepository
import com.vishalgupta.photoselector.presentation.StateHolder
import com.vishalgupta.photoselector.presentation.common.BackgroundPassCoordinator
import com.vishalgupta.photoselector.presentation.common.FaceScanCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

/**
 * One person as the People screen draws them: the crop to show, how many photos they appear in, and
 * the two user-authored bits (name, dismissal). Presentation-shaped on purpose — the screen never
 * touches a [Person], so it can't accidentally depend on scan-derived fields that churn every pass.
 */
data class PersonCard(
    val id: PersonId,
    val name: String?,
    val photoCount: Int,
    // The photo the cover face was found in, plus where on it. Null when the sidecar predates stored
    // boxes, or the photo has since left the root — the card then falls back to a person glyph.
    val coverPhoto: Photo?,
    val coverBox: FaceBox?,
) {
    val isNamed: Boolean get() = !name.isNullOrBlank()
}

/**
 * The People screen's state. [available] and [unreadable] are the two "we cannot do this" signals and
 * are deliberately distinct from "found nobody": a stripped runtime or a missing model resource reads
 * as *no faces* unless the UI says otherwise, which is exactly the trap a packaged build sets.
 * [available] starts true and only goes false once a scan has proved the models unloadable — they are
 * opened lazily, and probing them just to draw a screen would undo that.
 */
data class PeopleUiState(
    val available: Boolean = true,
    val unreadable: Boolean = false,
    val scanning: BackgroundPassCoordinator.Progress? = null,
    val toName: List<PersonCard> = emptyList(),
    val named: List<PersonCard> = emptyList(),
    val skipped: List<PersonCard> = emptyList(),
    // Set when a name collides with an existing category; cleared on the next edit or successful save.
    val nameError: String? = null,
    val purging: Boolean = false,
) {
    val isEmpty: Boolean get() = toName.isEmpty() && named.isEmpty() && skipped.isEmpty()
}

/**
 * Backs the People screen: the naming surface over the face pipeline's clusters.
 *
 * It owns the *join* between the two stores the feature spans — naming a person writes the name to
 * [people] **and** creates (or renames) that person's smart category in [categories], which is what
 * makes them appear in the library rail and the grid. Nothing in the headless engine does that; a
 * person with no category is invisible everywhere but here.
 *
 * The scan itself is not owned here. [scanCoordinator] is container-level and outlives this screen,
 * so leaving People mid-scan does not cancel it and coming back re-attaches to the same pass.
 *
 * PII: a person's name and their face crops are user data. Never log either.
 */
class PeopleViewModel(
    private val root: RootFolder,
    private val people: PeopleRepository,
    private val categories: CategoriesRepository,
    private val scanCoordinator: FaceScanCoordinator,
    // The current scan for [root] — resolves a cover face's PhotoId back to the Photo the crop decodes.
    private val photosForRoot: () -> List<Photo>,
    parentJob: Job? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Swing,
) : StateHolder(parentJob, dispatcher) {

    private val _state = MutableStateFlow(
        PeopleUiState(unreadable = people.isUnreadable(root).value),
    )
    val state: StateFlow<PeopleUiState> = _state.asStateFlow()

    init {
        combine(
            people.observePeople(root),
            people.isUnreadable(root),
        ) { persons, unreadable -> persons to unreadable }
            .onEach { (persons, unreadable) -> applyPeople(persons, unreadable) }
            .launchIn(scope)

        scanCoordinator.progress
            .onEach { progress -> _state.update { it.copy(scanning = progress) } }
            .launchIn(scope)

        // Null until a scan has actually resolved the models (they load lazily, on the pass's own
        // dispatcher); only an explicit false means "this machine cannot scan faces".
        scanCoordinator.available
            .onEach { available -> _state.update { it.copy(available = available != false) } }
            .launchIn(scope)
    }

    /** Starts (or re-attaches to) the whole-root face scan. */
    fun scan() {
        scanCoordinator.scan(photosForRoot())
    }

    /** Stops a scan the user started. Anything already detected stays in the face cache for next time. */
    fun cancelScan() {
        scanCoordinator.cancel()
    }

    /**
     * Names [id] and makes the person a smart category, so they show up in the rail and open as their
     * own grid. Rejects a name another category already uses — two buckets with the same name is a
     * library the user cannot navigate — and reports that through [PeopleUiState.nameError].
     */
    fun name(id: PersonId, rawName: String) {
        val name = rawName.trim()
        if (name.isBlank()) return
        scope.launch {
            val existing = categoryFor(id)
            val clash = categories.observeCategories(root).value
                .any { it.id != existing?.id && it.name.equals(name, ignoreCase = true) }
            if (clash) {
                _state.update { it.copy(nameError = "\"$name\" is already used by another category.") }
                return@launch
            }
            _state.update { it.copy(nameError = null) }
            // Naming clears any earlier dismissal: the user just told us this *is* someone.
            people.setDismissed(root, id, dismissed = false)
            people.rename(root, id, name)
            when {
                existing == null -> categories.create(root, name, CategoryRule.Person(id.value))
                existing.name != name -> categories.rename(root, existing.id, name)
            }
        }
    }

    /** Clears a name error once the user starts typing again, so the message isn't sticky. */
    fun clearNameError() {
        _state.update { if (it.nameError == null) it else it.copy(nameError = null) }
    }

    /**
     * The user's "not this one" verdict on a cluster — reached from both **Skip** ("decide later") and
     * **Not a person**. Deliberately one stored flag for both: they mean the same thing to the model
     * (keep this out of my naming queue, and remember that across rescans), and splitting them would
     * be two fields the clusterer would have to treat identically anyway.
     */
    fun dismiss(id: PersonId) {
        scope.launch { people.setDismissed(root, id, dismissed = true) }
    }

    /** Puts a skipped cluster back in the naming queue. */
    fun restore(id: PersonId) {
        scope.launch { people.setDismissed(root, id, dismissed = false) }
    }

    /**
     * Deletes every trace of the face feature: this root's person categories (so each person is
     * disposed of through the repository's own delete hook), then this root's people sidecar, then the
     * **whole** face cache.
     *
     * The cache cannot be purged per root — it keys on a hash of an absolute path, which this layer
     * cannot invert — so other roots keep their names but re-detect on their next scan. The confirm
     * dialog says exactly that; do not soften it here.
     */
    fun purgeAllFaceData(clearFaceCache: () -> Unit) {
        scope.launch {
            _state.update { it.copy(purging = true) }
            try {
                scanCoordinator.cancel()
                categories.observeCategories(root).value
                    .filter { it.rule is CategoryRule.Person }
                    .forEach { categories.delete(root, it.id) }
                people.deleteAll(root)
                clearFaceCache()
            } finally {
                _state.update { it.copy(purging = false) }
            }
        }
    }

    /** How many person categories a purge would remove — the count the confirm dialog names. */
    fun personCategoryCount(): Int =
        categories.observeCategories(root).value.count { it.rule is CategoryRule.Person }

    private fun categoryFor(id: PersonId): Category? =
        categories.observeCategories(root).value
            .firstOrNull { (it.rule as? CategoryRule.Person)?.personId == id.value }

    private fun applyPeople(persons: List<Person>, unreadable: Boolean) {
        val photos = photosForRoot().associateBy { it.id }
        val cards = persons
            // A cluster whose every photo has left the root has nothing to show and nothing to file;
            // an anchor keeps its stored centroid so it can re-match, but it isn't a card.
            .filter { it.faces.isNotEmpty() || it.isNamed }
            .map { person ->
                val cover = person.coverFace
                PersonCard(
                    id = person.id,
                    name = person.name,
                    photoCount = person.photos.size,
                    coverPhoto = cover?.let { photos[it.id.photo] },
                    coverBox = cover?.box,
                )
            }
        val dismissedIds = persons.filter { it.dismissed }.mapTo(HashSet()) { it.id }
        _state.update { current ->
            current.copy(
                unreadable = unreadable,
                // Biggest clusters first: naming the person who appears in 200 photos pays off most.
                toName = cards.filter { !it.isNamed && it.id !in dismissedIds }.sortedByDescending { it.photoCount },
                named = cards.filter { it.isNamed }.sortedBy { it.name?.lowercase() },
                skipped = cards.filter { !it.isNamed && it.id in dismissedIds }.sortedByDescending { it.photoCount },
            )
        }
    }
}

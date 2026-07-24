package com.vishalgupta.photoselector.data.categories

import com.vishalgupta.photoselector.data.io.AtomicJsonWriter
import com.vishalgupta.photoselector.domain.insight.InsightAnalysisStatus
import com.vishalgupta.photoselector.domain.insight.InsightBand
import com.vishalgupta.photoselector.domain.insight.InsightProviderId
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.CategoryId
import com.vishalgupta.photoselector.domain.model.CategoryKind
import com.vishalgupta.photoselector.domain.model.CategoryRule
import com.vishalgupta.photoselector.domain.model.CategoryRuleResolver
import com.vishalgupta.photoselector.domain.model.InsightComparator
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.model.providerIds
import com.vishalgupta.photoselector.domain.repository.CategoriesRepository
import com.vishalgupta.photoselector.domain.repository.CategoryAnalysisState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
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
 * **Smart categories** ([CategoryKind.SMART]) resolve their membership from a [CategoryRule]
 * rather than a stored set. This repository is the one place stored + computed are merged:
 * a smart category's members are `(ruleMatches ∪ pins) \ excludes`, where pins/excludes are
 * manual *deviations* from the rule (stored in the DTO's `photos`/`excluded`). The rule pass
 * runs off-thread on [scope] after bind (decoupled like the Similarity grouping), folding
 * `ruleMatches` in when ready and pruning now-redundant overrides. Callers never learn whether
 * a membership set was stored or computed — [observeMemberships] exposes the resolved set.
 *
 * Migration: the first time a root with only the legacy `.photo-selector-favourites.json`
 * is bound (against a populated scan), its entries are transcoded verbatim into the
 * built-in Favourites category, written as v2, and the old file is renamed to `.bak`.
 */
class JsonCategoriesRepository(
    private val json: Json,
    private val scannedPhotos: (RootFolder) -> List<Photo>,
    // Resolves each smart category's [CategoryRule] to its natural matches. Injected (feature-agnostic)
    // so a future AI rule slots in without touching this repository.
    private val ruleResolver: CategoryRuleResolver,
    // App/root-lifetime scope the async rule pass launches on. Owned by DI; cancelled per root via the
    // [resolveJob] handle on rebind / [clearContext] (mirrors GroupingCoordinator's decoupled pass).
    private val scope: CoroutineScope,
    // Whether the root's insight pass has run — mirrored from the InsightCoordinator. When it flips
    // (Analyze completes, or the folder goes stale) the repository re-resolves insight-backed smart
    // rules so their membership fills in, and maps it into the per-category analysis-state signal.
    // Defaulted (a constant NotAnalyzed) so JMH/tests that don't exercise insights need no change.
    private val insightStatus: StateFlow<InsightAnalysisStatus> =
        MutableStateFlow(InsightAnalysisStatus.NotAnalyzed).asStateFlow(),
    private val idGenerator: () -> CategoryId = { CategoryId(UUID.randomUUID().toString()) },
) : CategoriesRepository {

    private val mutex = Mutex()

    private var boundRoot: RootFolder? = null
    private var photosById: Map<PhotoId, Photo> = emptyMap()
    // The current scan for the bound root, kept so a re-resolution (on an insight-status flip) can re-run
    // the rule pass without re-reading the file.
    private var boundScanned: List<Photo> = emptyList()
    private val categoriesFlow = MutableStateFlow(Category.builtIns)
    private val membershipsFlow = MutableStateFlow<Map<CategoryId, Set<PhotoId>>>(emptyMap())
    private val analysisStatesFlow = MutableStateFlow<Map<CategoryId, CategoryAnalysisState>>(emptyMap())
    private val readOnly = MutableStateFlow(false)
    // Watches [insightStatus] for the bound root; cancelled per root on rebind / clearContext.
    private var insightStatusJob: Job? = null

    // Backing membership state, from which [membershipsFlow] (the exposed, resolved map) is derived.
    // manualMembers holds the whole set for MANUAL categories; pins/excludes are the SMART manual
    // deviations; ruleMatches is the SMART rule's natural matches (filled by the async pass).
    private var manualMembers: Map<CategoryId, Set<PhotoId>> = emptyMap()
    private var pins: Map<CategoryId, Set<PhotoId>> = emptyMap()
    private var excludes: Map<CategoryId, Set<PhotoId>> = emptyMap()
    private var ruleMatches: Map<CategoryId, Set<PhotoId>> = emptyMap()

    // Categories carrying a rule this build doesn't understand: treated as manual, but their raw
    // `rule`/`excluded` are carried through a rewrite verbatim so a newer build can still use them.
    private var preservedUnknownRuleById: Map<CategoryId, CategoryDto> = emptyMap()

    // Handle on the in-flight rule pass, so a rebind / clearContext can cancel a stale one.
    private var resolveJob: Job? = null

    // The insight-status collector is long-lived (a StateFlow collect never completes), so it runs on a
    // dedicated internal scope rather than the injected [scope]: launching it there would make it a
    // permanent child that a `joinAll(scope.children)`-style await (the repository test harness) would
    // hang on. It is still cancelled explicitly per root via [insightStatusJob].
    private val insightObserverScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    override fun observeCategories(root: RootFolder): StateFlow<List<Category>> {
        if (boundRoot?.path != root.path) bind(root)
        return categoriesFlow.asStateFlow()
    }

    override fun observeMemberships(root: RootFolder): StateFlow<Map<CategoryId, Set<PhotoId>>> {
        if (boundRoot?.path != root.path) bind(root)
        return membershipsFlow.asStateFlow()
    }

    override fun observeAnalysisStates(root: RootFolder): StateFlow<Map<CategoryId, CategoryAnalysisState>> {
        if (boundRoot?.path != root.path) bind(root)
        return analysisStatesFlow.asStateFlow()
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
            manualMembers = manualMembers + (id to emptySet())
            commit(root)
            refreshAnalysisStates()
            id
        }
    }

    override suspend fun rename(root: RootFolder, id: CategoryId, newName: String) {
        require(id !in Category.BUILT_IN_IDS) { "A built-in category cannot be renamed." }
        if (boundRoot?.path != root.path) bind(root)
        require(!isSmart(id)) { "A smart category cannot be renamed." }
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
        require(!isSmart(id)) { "A smart category cannot be deleted." }
        mutex.withLock {
            categoriesFlow.value = categoriesFlow.value.filterNot { it.id == id }
            manualMembers = manualMembers - id
            commit(root)
            refreshAnalysisStates()
        }
    }

    override suspend fun toggleMembership(root: RootFolder, id: CategoryId, photo: PhotoId): Boolean {
        if (boundRoot?.path != root.path) bind(root)
        return mutex.withLock {
            val nowMember = if (isSmart(id)) {
                toggleSmart(id, photo)
            } else {
                val current = manualMembers[id].orEmpty()
                val member = photo !in current
                manualMembers = manualMembers + (id to if (member) current + photo else current - photo)
                member
            }
            commit(root)
            nowMember
        }
    }

    override suspend fun addMemberships(root: RootFolder, id: CategoryId, photos: Collection<PhotoId>): Int {
        if (boundRoot?.path != root.path) bind(root)
        if (photos.isEmpty()) return 0
        return mutex.withLock {
            if (isSmart(id)) {
                val before = membersOf(id)
                val matches = ruleMatches[id].orEmpty()
                var newPins = pins[id].orEmpty()
                var newExcludes = excludes[id].orEmpty()
                // Additive: for a rule-match, drop any exclude; otherwise add a pin. Never removes.
                for (p in photos) {
                    if (p in matches) newExcludes = newExcludes - p else newPins = newPins + p
                }
                pins = pins + (id to newPins)
                excludes = excludes + (id to newExcludes)
                val added = (membersOf(id) - before).size
                if (added == 0) return@withLock 0
                commit(root)
                added
            } else {
                val current = manualMembers[id].orEmpty()
                val added = photos.filterTo(mutableSetOf()) { it !in current }
                if (added.isEmpty()) return@withLock 0
                manualMembers = manualMembers + (id to (current + added))
                // One disk write for the whole batch — filing 100 tiles touches the file once.
                commit(root)
                added.size
            }
        }
    }

    override suspend fun removeMemberships(root: RootFolder, photos: Collection<PhotoId>) {
        if (boundRoot?.path != root.path) bind(root)
        if (photos.isEmpty()) return
        val toRemove = photos.toSet()
        mutex.withLock {
            val nextManual = manualMembers.mapValues { (_, ids) -> ids - toRemove }
            val nextPins = pins.mapValues { (_, ids) -> ids - toRemove }
            val nextExcludes = excludes.mapValues { (_, ids) -> ids - toRemove }
            val nextRuleMatches = ruleMatches.mapValues { (_, ids) -> ids - toRemove }
            // Nothing was filed/pinned/excluded/matched anywhere — skip the write so a delete of
            // un-categorised photos leaves the file untouched.
            if (nextManual == manualMembers && nextPins == pins &&
                nextExcludes == excludes && nextRuleMatches == ruleMatches
            ) {
                return@withLock
            }
            manualMembers = nextManual
            pins = nextPins
            excludes = nextExcludes
            ruleMatches = nextRuleMatches
            commit(root)
        }
    }

    override suspend fun clearContext() {
        mutex.withLock {
            resolveJob?.cancel()
            resolveJob = null
            insightStatusJob?.cancel()
            insightStatusJob = null
            boundRoot = null
            photosById = emptyMap()
            boundScanned = emptyList()
            categoriesFlow.value = Category.builtIns
            membershipsFlow.value = emptyMap()
            analysisStatesFlow.value = emptyMap()
            manualMembers = emptyMap()
            pins = emptyMap()
            excludes = emptyMap()
            ruleMatches = emptyMap()
            preservedUnknownRuleById = emptyMap()
            readOnly.value = false
        }
    }

    /** Resolved membership for one category: stored set (manual) or `(ruleMatches ∪ pins) \ excludes` (smart). */
    private fun membersOf(id: CategoryId): Set<PhotoId> =
        if (categoriesFlow.value.firstOrNull { it.id == id }?.kind == CategoryKind.SMART) {
            (ruleMatches[id].orEmpty() + pins[id].orEmpty()) - excludes[id].orEmpty()
        } else {
            manualMembers[id].orEmpty()
        }

    private fun isSmart(id: CategoryId): Boolean =
        categoriesFlow.value.firstOrNull { it.id == id }?.kind == CategoryKind.SMART

    /**
     * Toggles a photo in a smart category by writing a *deviation* only, then returns the new
     * membership. A rule-matched photo toggles via the exclude set; a non-matched one via the pin
     * set — so re-toggling always returns to the rule's natural state and no override is ever stored
     * for something the rule already agrees with.
     */
    private fun toggleSmart(id: CategoryId, photo: PhotoId): Boolean {
        val matches = photo in ruleMatches[id].orEmpty()
        val currentPins = pins[id].orEmpty()
        val currentExcludes = excludes[id].orEmpty()
        val currentMember = if (matches) photo !in currentExcludes else photo in currentPins
        val nowMember = !currentMember
        if (matches) {
            excludes = excludes + (id to if (nowMember) currentExcludes - photo else currentExcludes + photo)
        } else {
            pins = pins + (id to if (nowMember) currentPins + photo else currentPins - photo)
        }
        return nowMember
    }

    /** Recomputes the exposed membership map from backing state, then persists. */
    private suspend fun commit(root: RootFolder) {
        membershipsFlow.value = resolvedMemberships()
        writeToDisk(root)
    }

    private fun resolvedMemberships(): Map<CategoryId, Set<PhotoId>> =
        categoriesFlow.value.associate { it.id to membersOf(it.id) }

    private fun bind(root: RootFolder) {
        resolveJob?.cancel()
        insightStatusJob?.cancel()
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
        boundScanned = scanned
        categoriesFlow.value = loaded.map { it.toCategory() }

        val manual = LinkedHashMap<CategoryId, Set<PhotoId>>()
        val loadedPins = LinkedHashMap<CategoryId, Set<PhotoId>>()
        val loadedExcludes = LinkedHashMap<CategoryId, Set<PhotoId>>()
        val unknownRule = LinkedHashMap<CategoryId, CategoryDto>()
        for (dto in loaded) {
            val id = CategoryId(dto.id)
            // Route on the *resolved* kind (matching toCategory()/membersOf()/isSmart()), not the raw
            // `rule != null`: an unknown/future rule type resolves to null and must fall to the manual
            // path, or bind and the rest of the model would disagree about the category's kind.
            if (dto.rule?.toDomain() != null) {
                loadedPins[id] = MembershipResolver.resolve(dto.photos, scanned)
                loadedExcludes[id] = MembershipResolver.resolve(dto.excluded, scanned)
            } else {
                manual[id] = MembershipResolver.resolve(dto.photos, scanned)
                // A dto that carries a rule this build doesn't understand is treated as manual, but its
                // `rule`/`excluded` must survive a rewrite untouched (a newer build can still use them) —
                // stash the raw dto so writeToDisk can carry those fields through.
                if (dto.rule != null) unknownRule[id] = dto
            }
        }
        manualMembers = manual
        pins = loadedPins
        excludes = loadedExcludes
        preservedUnknownRuleById = unknownRule
        // Rule matches are computed off-thread below; until then smart categories show pins-only.
        ruleMatches = emptyMap()
        membershipsFlow.value = resolvedMemberships()
        refreshAnalysisStates()
        readOnly.value = !Files.isWritable(root.path)
        // Only treat the root as bound once we've resolved against a populated scan.
        // Resolving non-empty memberships against an empty scan (scan results not set
        // yet) would silently drop them and stick — leaving boundRoot null lets the next
        // observe/mutation re-bind and recover. Migration is likewise deferred until then.
        if (scanned.isNotEmpty()) {
            boundRoot = root
            if (!readOnly.value && shouldMigrate(root)) migrateLegacyFavourites(root, loaded)
            launchRuleResolution(root, scanned)
            // Re-resolve insight-backed rules whenever the pass state flips (Analyze completes, or the
            // folder goes stale) and re-map the tri-state signal. drop(1): the current value is already
            // reflected by the resolution above, so only react to *changes*.
            insightStatusJob = insightObserverScope.launch {
                insightStatus.drop(1).collect {
                    if (boundRoot?.path == root.path) {
                        launchRuleResolution(root, boundScanned)
                        mutex.withLock { refreshAnalysisStates() }
                    }
                }
            }
        } else {
            boundRoot = null
        }
    }

    /**
     * Recomputes the per-category analysis-state map from the current categories and the insight pass
     * [insightStatus]. Manual categories and rules that need no insight pass (RAW files) are
     * [CategoryAnalysisState.AlwaysReady]; an insight-backed smart rule mirrors the pass's status.
     */
    private fun refreshAnalysisStates() {
        val status = insightStatus.value
        analysisStatesFlow.value = categoriesFlow.value.associate { category ->
            val rule = category.rule
            val state = if (rule == null || rule.providerIds().isEmpty()) {
                CategoryAnalysisState.AlwaysReady
            } else {
                when (status) {
                    InsightAnalysisStatus.NotAnalyzed -> CategoryAnalysisState.NotAnalyzed
                    InsightAnalysisStatus.Analyzed -> CategoryAnalysisState.Analyzed
                    InsightAnalysisStatus.Stale -> CategoryAnalysisState.Stale
                }
            }
            category.id to state
        }
    }

    /**
     * Resolves every smart category's rule off-thread, then folds the matches into the membership
     * flow and prunes now-redundant overrides (a pin the rule now matches; an exclude for a photo the
     * rule no longer matches). Decoupled from bind like the Similarity pass — for the RAW rule it is
     * effectively instant. A stale pass (root changed underneath it) bails on the boundRoot check.
     */
    private fun launchRuleResolution(root: RootFolder, scanned: List<Photo>) {
        val smart = categoriesFlow.value.filter { it.kind == CategoryKind.SMART && it.rule != null }
        if (smart.isEmpty()) return
        resolveJob = scope.launch {
            val computed = smart.associate { it.id to ruleResolver.resolve(it.rule!!, scanned) }
            mutex.withLock {
                if (boundRoot?.path != root.path) return@withLock
                ruleMatches = computed
                var overridesChanged = false
                val prunedPins = pins.toMutableMap()
                val prunedExcludes = excludes.toMutableMap()
                for (cat in smart) {
                    val matches = computed[cat.id].orEmpty()
                    val p = pins[cat.id].orEmpty()
                    val keptPins = p - matches // a pin the rule now matches is redundant
                    if (keptPins != p) { prunedPins[cat.id] = keptPins; overridesChanged = true }
                    val e = excludes[cat.id].orEmpty()
                    val keptExcludes = e.intersect(matches) // an exclude the rule no longer matches is redundant
                    if (keptExcludes != e) { prunedExcludes[cat.id] = keptExcludes; overridesChanged = true }
                }
                pins = prunedPins
                excludes = prunedExcludes
                membershipsFlow.value = resolvedMemberships()
                if (overridesChanged) writeToDisk(root)
            }
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
     * Guarantees every built-in category and every seeded smart category exists, marked correctly and
     * in canonical order (built-ins first, then the smart seeds, then custom categories). Each seed's
     * stored overrides (pins in `photos`, excludes in `excluded`) are preserved and its rule is
     * re-attached from [Category.smartSeeds] — a freshly-seeded file simply starts them empty.
     */
    private fun normalise(categories: List<CategoryDto>): List<CategoryDto> {
        val builtIns = Category.builtIns.map { builtIn ->
            val photos = categories.firstOrNull { it.id == builtIn.id.value }?.photos.orEmpty()
            CategoryDto(builtIn.id.value, builtIn.name, builtIn = true, photos = photos)
        }
        val smart = Category.smartSeeds.map { seed ->
            val existing = categories.firstOrNull { it.id == seed.id.value }
            CategoryDto(
                id = seed.id.value,
                name = seed.name,
                builtIn = false,
                photos = existing?.photos.orEmpty(),        // manual pins
                excluded = existing?.excluded.orEmpty(),    // manual excludes
                rule = seed.rule?.toDto(),
            )
        }
        val reserved = HashSet<String>().apply {
            Category.BUILT_IN_IDS.forEach { add(it.value) }
            Category.SMART_SEED_IDS.forEach { add(it.value) }
        }
        val rest = categories.filterNot { it.id in reserved }.map { it.copy(builtIn = false) }
        return builtIns + smart + rest
    }

    private suspend fun writeToDisk(root: RootFolder) {
        if (boundRoot?.path != root.path) return
        val dtos = categoriesFlow.value.map { category ->
            when (category.kind) {
                CategoryKind.SMART -> CategoryDto(
                    id = category.id.value,
                    name = category.name,
                    builtIn = false,
                    photos = entriesFor(pins[category.id].orEmpty()),
                    excluded = entriesFor(excludes[category.id].orEmpty()),
                    rule = category.rule?.toDto(),
                )
                CategoryKind.MANUAL -> {
                    // Normally rule/excluded stay empty; for a category whose rule this build doesn't
                    // understand, carry its raw rule + excluded through untouched (manual edits still
                    // land in photos) so a downgrade/hand-edit can't silently wipe them.
                    val carried = preservedUnknownRuleById[category.id]
                    CategoryDto(
                        id = category.id.value,
                        name = category.name,
                        builtIn = category.builtIn,
                        photos = entriesFor(manualMembers[category.id].orEmpty()),
                        excluded = carried?.excluded.orEmpty(),
                        rule = carried?.rule,
                    )
                }
            }
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

    private fun entriesFor(ids: Set<PhotoId>): List<PhotoEntryDto> =
        ids.mapNotNull { photosById[it] }
            .map { PhotoEntryDto(it.relativePath, it.sizeBytes, it.lastModifiedEpochMs) }
            .sortedBy { it.path }

    private fun CategoryDto.toCategory(): Category {
        val domainRule = rule?.toDomain()
        return Category(
            id = CategoryId(id),
            name = name,
            builtIn = builtIn,
            kind = if (domainRule != null) CategoryKind.SMART else CategoryKind.MANUAL,
            rule = domainRule,
        )
    }

    private fun CategoryRule.toDto(): CategoryRuleDto = when (this) {
        CategoryRule.RawFiles -> CategoryRuleDto(CategoryRuleDto.RAW_FILES)
        is CategoryRule.Leaf -> CategoryRuleDto(
            type = CategoryRuleDto.INSIGHT_LEAF,
            providerId = providerId.value,
            comparator = when (comparator) { InsightComparator.InBand -> CategoryRuleDto.COMPARATOR_IN_BAND },
            band = operand.id,
        )
        is CategoryRule.And -> CategoryRuleDto(CategoryRuleDto.AND, children = children.map { it.toDto() })
        is CategoryRule.Or -> CategoryRuleDto(CategoryRuleDto.OR, children = children.map { it.toDto() })
        is CategoryRule.Not -> CategoryRuleDto(CategoryRuleDto.NOT, children = listOf(child.toDto()))
    }

    /**
     * Decodes a persisted rule tree. An unknown [CategoryRuleDto.type], a malformed leaf (unknown
     * comparator, missing fields), or a composite with any undecodable child returns null → the category
     * is treated as manual and its raw `rule`/`excluded` preserved through a rewrite (the #120 forward-compat
     * path). A leaf whose *provider* is merely unregistered still decodes fine — the tree shape is known,
     * so the category stays smart and the resolver simply matches nothing for it (graceful degrade).
     */
    private fun CategoryRuleDto.toDomain(): CategoryRule? = when (type) {
        CategoryRuleDto.RAW_FILES -> CategoryRule.RawFiles
        CategoryRuleDto.INSIGHT_LEAF -> {
            val provider = providerId
            val comp = comparator?.let { if (it == CategoryRuleDto.COMPARATOR_IN_BAND) InsightComparator.InBand else null }
            val bandId = band
            if (provider != null && comp != null && bandId != null) {
                CategoryRule.Leaf(InsightProviderId(provider), comp, InsightBand(bandId))
            } else {
                null
            }
        }
        CategoryRuleDto.AND -> children?.mapAllOrNull { it.toDomain() }?.let { CategoryRule.And(it) }
        CategoryRuleDto.OR -> children?.mapAllOrNull { it.toDomain() }?.let { CategoryRule.Or(it) }
        CategoryRuleDto.NOT -> children?.singleOrNull()?.toDomain()?.let { CategoryRule.Not(it) }
        else -> null
    }

    /** Maps every element or returns null if any maps to null — an all-or-nothing tree decode. */
    private inline fun <T, R> List<T>.mapAllOrNull(transform: (T) -> R?): List<R>? {
        val out = ArrayList<R>(size)
        for (e in this) out += transform(e) ?: return null
        return out
    }

    private companion object {
        val FAVOURITES_ID: String = Category.FAVOURITES_ID.value
    }
}

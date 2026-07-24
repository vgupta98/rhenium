package com.vishalgupta.photoselector.presentation.common

import com.vishalgupta.photoselector.domain.insight.BandingStrategy
import com.vishalgupta.photoselector.domain.insight.InsightAnalysisStatus
import com.vishalgupta.photoselector.domain.insight.InsightProvider
import com.vishalgupta.photoselector.domain.insight.InsightProviderId
import com.vishalgupta.photoselector.domain.insight.InsightSource
import com.vishalgupta.photoselector.domain.insight.InsightValue
import com.vishalgupta.photoselector.domain.insight.LabeledInsight
import com.vishalgupta.photoselector.domain.insight.ScalarBanding
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Owns the one **explicit, gated** whole-folder insight pass — the insight-platform analogue of
 * [GroupingCoordinator]. Root-scoped, cancellable, and decoupled from navigation: it runs only when the
 * user triggers "Analyze folder" (never on bind or open), then computes each provider's per-photo score
 * over the shared cache and derives the folder's [ScalarBanding] from the resulting distribution. The
 * work is cached to disk (the per-photo [com.vishalgupta.photoselector.data.ai.InsightCache]) and the
 * banding kept in memory, so re-entering an already-analyzed folder is instant. Lifetime is the *root* —
 * [reset] drops the pass, banding and analyzed set on a root change — while the object itself stays
 * stable so a single collector can watch [progress] / [status] across roots.
 *
 * As an [InsightSource] it serves the resolver a **cache-only, gated** banded value: null until the pass
 * has completed and the photo is in the analyzed set, so resolving a smart category never triggers a
 * costly compute off a bind (the whole point of gating). [computeBandedValue] is the panel's separate,
 * eager path — one photo, computed on demand, banded against the last distribution (or a provisional
 * absolute band before any analysis).
 *
 * Thread-safety mirrors [GroupingCoordinator]: mutating entry points guard shared request state under a
 * lock; the pass body runs on the injected dispatcher and only reads the `@Volatile` snapshots and
 * writes the thread-safe flows.
 */
class InsightCoordinator(
    private val bindings: List<Binding>,
    parentJob: Job?,
    dispatcher: CoroutineDispatcher,
) : InsightSource {

    /** One provider paired with the policy that bands its output. */
    data class Binding(val provider: InsightProvider, val banding: BandingStrategy)

    /** Live progress of the running pass: [processed] of [total]. Null while idle or within the grace window. */
    data class Progress(val processed: Int, val total: Int)

    private val scope = CoroutineScope(SupervisorJob(parentJob) + dispatcher)
    private val bindingById: Map<InsightProviderId, Binding> = bindings.associateBy { it.provider.id }

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _status = MutableStateFlow(InsightAnalysisStatus.NotAnalyzed)
    /** Whether this root has been analyzed, for the tri-state rail signal. Stable across roots. */
    val status: StateFlow<InsightAnalysisStatus> = _status.asStateFlow()

    private val lock = Any()
    private var analyzeJob: Job? = null
    @Volatile private var generation = 0
    // Banding per provider, set atomically on a completed pass; empty until then (⇒ resolver gets null).
    @Volatile private var bandingById: Map<InsightProviderId, ScalarBanding> = emptyMap()
    // The photo-id set that was analyzed, for staleness diffing and for gating the resolver read.
    @Volatile private var analyzedIds: Set<PhotoId> = emptySet()

    /** True once a pass has completed for this root (even if it produced no matches). */
    val isAnalyzed: Boolean get() = _status.value != InsightAnalysisStatus.NotAnalyzed

    /**
     * Runs the gated whole-folder pass over [photos] (superseding any in-flight one). On completion it
     * publishes each provider's banding, records the analyzed id set, and flips [status] to `Analyzed`. A
     * cancelled pass writes nothing. Safe to call from any thread (in the app, always the EDT).
     */
    fun analyze(photos: List<Photo>) {
        synchronized(lock) {
            analyzeJob?.cancel()
            _progress.value = null
            val gen = ++generation
            analyzeJob = scope.launch { runPass(photos, gen) }
        }
    }

    private suspend fun runPass(photos: List<Photo>, gen: Int) {
        val total = photos.size * bindings.size.coerceAtLeast(1)
        val processed = AtomicInteger(0)
        val grace = scope.launch {
            delay(GRACE_MS)
            if (generation == gen) _progress.value = Progress(processed.get(), total)
        }
        try {
            val computed = HashMap<InsightProviderId, ScalarBanding>()
            for (binding in bindings) {
                val raws = ArrayList<Float>(photos.size)
                for (photo in photos) {
                    coroutineContext.ensureActive()
                    (binding.provider.value(photo) as? InsightValue.Scalar)?.let { raws += it.raw }
                    val done = processed.incrementAndGet()
                    _progress.update { cur -> if (generation != gen || cur == null) cur else Progress(done, total) }
                }
                computed[binding.provider.id] = binding.banding.fromDistribution(raws)
            }
            synchronized(lock) {
                if (generation != gen) return
                bandingById = computed
                analyzedIds = photos.mapTo(HashSet()) { it.id }
                _status.value = InsightAnalysisStatus.Analyzed
            }
        } finally {
            grace.cancel()
            if (generation == gen) _progress.value = null
        }
    }

    /**
     * Re-checks staleness against the root's *current* photo set: once analyzed, [status] flips to
     * `Stale` when photos have been added/removed since the pass (the per-photo cache self-invalidates on
     * an *edit*, but not on set membership change), and back to `Analyzed` when the sets match again.
     * No-op before the first analysis.
     */
    fun refreshStaleness(currentIds: Set<PhotoId>) {
        synchronized(lock) {
            if (_status.value == InsightAnalysisStatus.NotAnalyzed) return
            _status.value = if (currentIds == analyzedIds) {
                InsightAnalysisStatus.Analyzed
            } else {
                InsightAnalysisStatus.Stale
            }
        }
    }

    /** Drops the in-flight pass, banding, analyzed set and progress — called on a root change. */
    fun reset() {
        synchronized(lock) {
            generation++
            analyzeJob?.cancel()
            analyzeJob = null
            bandingById = emptyMap()
            analyzedIds = emptySet()
            _progress.value = null
            _status.value = InsightAnalysisStatus.NotAnalyzed
        }
    }

    // --- InsightSource: cache-only, gated (for the rule resolver) ---
    override suspend fun bandedValue(providerId: InsightProviderId, photo: Photo): InsightValue? {
        val banding = bandingById[providerId] ?: return null   // unknown provider, or not analyzed yet
        if (photo.id !in analyzedIds) return null              // added since analysis → no signal yet
        val binding = bindingById[providerId] ?: return null
        // provider.value on an analyzed photo is a cache hit — no fresh decode off the resolver path.
        val scalar = binding.provider.value(photo) as? InsightValue.Scalar ?: return null
        return banding.scalar(scalar.raw)
    }

    /**
     * The panel's eager path: compute [photo]'s value for [providerId] on demand (cache-filling this one
     * photo), banded against the last analyzed distribution or, before any analysis, a provisional
     * absolute band. Returns null for an unknown provider or an unassessable photo.
     */
    suspend fun computeBandedValue(providerId: InsightProviderId, photo: Photo): InsightValue? {
        val binding = bindingById[providerId] ?: return null
        val value = binding.provider.value(photo) ?: return null
        val scalar = value as? InsightValue.Scalar ?: return value
        val banding = bandingById[providerId] ?: binding.banding.provisional()
        return banding.scalar(scalar.raw)
    }

    /**
     * Every provider's banded insight for [photo] as labelled rows for the panel — the eager per-photo
     * path (one cheap decode each, cache-filling). Providers with no assessable value are skipped.
     */
    suspend fun insightsFor(photo: Photo): List<LabeledInsight> = bindings.mapNotNull { binding ->
        computeBandedValue(binding.provider.id, photo)?.let { LabeledInsight(binding.provider.displayName, it) }
    }

    private companion object {
        const val GRACE_MS = 200L
    }
}

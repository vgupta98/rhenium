package com.vishalgupta.photoselector.perf

import com.vishalgupta.photoselector.data.categories.JsonCategoriesRepository
import com.vishalgupta.photoselector.domain.model.Category
import com.vishalgupta.photoselector.domain.model.Photo
import com.vishalgupta.photoselector.domain.model.PhotoId
import com.vishalgupta.photoselector.domain.model.RawFilesResolver
import com.vishalgupta.photoselector.domain.model.RootFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit

/**
 * Measures end-to-end latency of toggling a photo's Favourites membership via
 * `JsonCategoriesRepository.toggleMembership`. Each invocation flips a single id and
 * waits for the suspending toggle to return.
 *
 * Same id is flipped every invocation so the set alternately gains and loses one entry —
 * every toggle dirties the JSON and exercises the atomic-rename disk write path (which
 * the toggle performs inline, returning ~hundreds of µs to low ms depending on the
 * filesystem). The delta vs a debounced write is the cost of "no silent loss on quit".
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2)
@State(Scope.Benchmark)
open class FavouritesToggleBenchmark {

    private lateinit var tmpDir: Path
    private lateinit var root: RootFolder
    private lateinit var repo: JsonCategoriesRepository
    private val id = PhotoId("bench-photo")

    @Setup(Level.Trial)
    fun setup() {
        tmpDir = Files.createTempDirectory("favbench-")
        root = RootFolder(tmpDir)
        // A single scanned photo matching the toggled id, so bind resolves a populated
        // scan (the self-healing bind only caches boundRoot for a non-empty scan) and
        // every toggle persists a real v3 descriptor — exercising the disk write path.
        val photo = Photo(
            id = id,
            absolutePath = tmpDir.resolve("bench-photo"),
            relativePath = "bench-photo",
            fileName = "bench-photo",
            sizeBytes = 4823901,
            lastModifiedEpochMs = 1730812401000,
        )
        repo = JsonCategoriesRepository(
            Json { prettyPrint = true; encodeDefaults = true },
            scannedPhotos = { listOf(photo) },
            ruleResolver = RawFilesResolver(emptySet()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        // Force initial bind so the first measured toggle isn't disproportionately slow.
        repo.observeMemberships(root)
    }

    @TearDown(Level.Trial)
    fun teardown() {
        if (Files.exists(tmpDir)) {
            Files.walk(tmpDir).use { stream ->
                stream.sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Benchmark
    fun toggleSameId() = runBlocking {
        repo.toggleMembership(root, Category.FAVOURITES_ID, id)
    }
}

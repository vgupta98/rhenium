package com.vishalgupta.photoselector.domain.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class RawFilesResolverTest {

    private val resolver = RawFilesResolver(setOf("arw", "cr2", "nef", "dng"))

    private fun photo(name: String) = Photo(
        id = PhotoId(name),
        absolutePath = Path.of("/root/$name"),
        relativePath = name,
        fileName = name,
        sizeBytes = 1,
        lastModifiedEpochMs = 1,
    )

    @Test
    fun classifiesRawByExtensionCaseInsensitively() = runTest {
        val photos = listOf(
            photo("a.jpg"),
            photo("b.arw"),
            photo("c.CR2"),   // upper-case extension still matches
            photo("d.NEF"),
            photo("e.png"),
            photo("f.dng"),
            photo("noext"),   // no extension → not RAW
        )

        val matches = resolver.resolve(CategoryRule.RawFiles, photos)

        assertEquals(
            setOf(PhotoId("b.arw"), PhotoId("c.CR2"), PhotoId("d.NEF"), PhotoId("f.dng")),
            matches,
        )
    }

    @Test
    fun emptyInputYieldsNoMatches() = runTest {
        assertEquals(emptySet<PhotoId>(), resolver.resolve(CategoryRule.RawFiles, emptyList()))
    }
}

package com.vishalgupta.photoselector.data.prefs

import com.vishalgupta.photoselector.data.io.AtomicJsonWriter
import com.vishalgupta.photoselector.domain.model.RootFolder
import com.vishalgupta.photoselector.domain.repository.XmpSyncPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files

/**
 * File-backed [XmpSyncPreferences]: one tiny per-root dotfile ([RootFolder.xmpSyncFile]) written
 * atomically through the shared [AtomicJsonWriter] — the same mechanism categories and browse-position
 * use, no new persistence machinery. The single flag is memoised per root; writes are best-effort (a
 * read-only volume just means the toggle doesn't survive the session).
 */
class JsonXmpSyncPreferences(
    private val json: Json,
) : XmpSyncPreferences {

    // @Volatile: isEnabled reads off the EDT while setEnabled writes on it; the volatile gives a
    // happens-before so a read never sees a torn cache. Keyed by path so a root switch re-reads.
    @Volatile private var cachedPath: String? = null
    @Volatile private var cached: Boolean = false

    override fun isEnabled(root: RootFolder): Boolean {
        if (cachedPath == root.path.toString()) return cached
        val value = readFromDisk(root)
        cachedPath = root.path.toString()
        cached = value
        return value
    }

    override fun setEnabled(root: RootFolder, enabled: Boolean) {
        cachedPath = root.path.toString()
        cached = enabled
        val bytes = json.encodeToString(XmpSyncDto.serializer(), XmpSyncDto(enabled = enabled))
            .toByteArray(Charsets.UTF_8)
        try {
            AtomicJsonWriter.write(root.xmpSyncFile, bytes)
        } catch (_: Throwable) {
            // Read-only folder; the flag is still cached in memory for this session.
        }
    }

    private fun readFromDisk(root: RootFolder): Boolean {
        val file = root.xmpSyncFile
        if (!Files.exists(file)) return false
        return try {
            json.decodeFromString(XmpSyncDto.serializer(), Files.readString(file)).enabled
        } catch (_: Throwable) {
            false
        }
    }

    @Serializable
    private data class XmpSyncDto(val enabled: Boolean = false)
}

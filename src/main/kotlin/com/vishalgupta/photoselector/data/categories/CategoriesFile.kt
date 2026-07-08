package com.vishalgupta.photoselector.data.categories

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A persisted photo reference: the root-relative path plus cheap identity hints
 * ([size], [mtimeMs]) used to re-attach it after a folder rename or move. A negative
 * [size]/[mtimeMs] is a sentinel for "path-only" (a legacy v1 favourites entry, or a
 * photo not present in the last scan) — such entries match by exact path only.
 *
 * This is the entry format of the categories v2 file. The (size, mtime) hints are what
 * let a membership re-attach after a folder rename or move, where a path-only legacy
 * favourite (which never carried them) could not.
 */
@Serializable
data class PhotoEntryDto(
    val path: String,
    val size: Long = -1,
    val mtimeMs: Long = -1,
)

/**
 * A rule descriptor as persisted for a smart category. A bare [type] discriminator (not
 * kotlinx polymorphism) keeps the schema flat and additive: an unknown future [type] decodes to a
 * `CategoryRuleDto` the repository maps to `null` (treated as manual — safe), never a hard failure.
 */
@Serializable
data class CategoryRuleDto(val type: String) {
    companion object {
        const val RAW_FILES = "raw-files"
    }
}

/**
 * A category as persisted in the v2 file: metadata plus its photo descriptors. Additive smart-category
 * fields (decoded through `ignoreUnknownKeys`, so old files load unchanged and old readers ignore
 * them — no version bump): [rule] is the self-fill rule when the category is smart (null = manual),
 * and for a smart category [photos] holds the manual **pins** while [excluded] holds the manual
 * **excludes** (deviations from the rule's natural matches). For a manual category [photos] is the
 * whole membership and [excluded] is empty, exactly as before.
 */
@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
    val photos: List<PhotoEntryDto> = emptyList(),
    val excluded: List<PhotoEntryDto> = emptyList(),
    val rule: CategoryRuleDto? = null,
)

/**
 * v2 schema: N flat categories, each wrapping descriptor objects (NOT bare path
 * strings — bare strings would re-orphan memberships on every folder rename and undo
 * the stable-id work). This is the second on-disk generation of the persisted
 * selection: v1 was the shipped single-bucket favourites file (see [LegacyFavouritesFile]);
 * the intermediate descriptor-favourites format never shipped, so it has no number here.
 */
@Serializable
private data class CategoriesV2Dto(
    val version: Int = 2,
    val categories: List<CategoryDto> = emptyList(),
)

/**
 * Decode/encode the categories file. Reads peek the `version` field and dispatch to
 * the matching concrete DTO; writes always emit v2.
 */
object CategoriesFile {
    fun decode(json: Json, text: String): List<CategoryDto> {
        // version is peeked for forward-compatibility; v2 is the only shape today.
        val version = json.parseToJsonElement(text)
            .jsonObject["version"]?.jsonPrimitive?.intOrNull ?: 2
        return when (version) {
            else -> json.decodeFromString(CategoriesV2Dto.serializer(), text).categories
        }
    }

    fun encode(json: Json, categories: List<CategoryDto>): ByteArray {
        val dto = CategoriesV2Dto(categories = categories)
        return json.encodeToString(CategoriesV2Dto.serializer(), dto).toByteArray(Charsets.UTF_8)
    }
}

/**
 * The legacy single-bucket favourites file, read once to migrate into the built-in
 * Favourites category. Only one favourites format ever shipped (v1.2.0 and earlier):
 * bare path strings, as either fieldless `{ "favourites": [...] }` or `{ "version": 1,
 * ... }`. Entries become path-only descriptors (size/mtime = -1), matched by exact path.
 */
object LegacyFavouritesFile {
    @Serializable
    private data class V1(val version: Int = 1, val favourites: List<String> = emptyList())

    fun decode(json: Json, text: String): List<PhotoEntryDto> =
        json.decodeFromString(V1.serializer(), text).favourites.map { PhotoEntryDto(path = it) }
}

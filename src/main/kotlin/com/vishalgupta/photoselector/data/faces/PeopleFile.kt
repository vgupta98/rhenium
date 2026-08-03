package com.vishalgupta.photoselector.data.faces

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A face reference as persisted: the photo's stable id plus its index within that photo's detections. */
@Serializable
data class FaceRefDto(val photo: String, val index: Int = 0)

/**
 * A person as persisted in the v1 people file.
 *
 * [name] is the only user-authored field — everything else is derived and a rescan rewrites it.
 * [centroid] is stored so a *named* person can be re-matched even after the photos that originally
 * defined them leave the root.
 */
@Serializable
data class PersonDto(
    val id: String,
    val name: String? = null,
    val faces: List<FaceRefDto> = emptyList(),
    val centroid: List<Float> = emptyList(),
)

/**
 * A decoded person plus the exact JSON object it came from. Keeping [raw] is what lets a field this
 * build doesn't know about survive a rewrite: `ignoreUnknownKeys` only stops an unknown key from
 * *failing* the decode, it doesn't preserve it. Same discipline the categories file applies to an
 * unknown rule — never silently wipe a newer build's data.
 */
data class StoredPerson(val dto: PersonDto, val raw: JsonObject? = null)

/**
 * Decode/encode `<root>/.photo-selector-people.json`. Reads peek `version` for forward-compatibility
 * and dispatch; writes always emit v1. Same shape as `CategoriesFile`, so the two sidecars stay
 * recognisably siblings.
 */
object PeopleFile {
    const val VERSION = 1

    /**
     * Decodes a v1 people document, **throwing** on anything else — a malformed file or a version
     * this build doesn't know. Both are "I cannot read this", and the repository turns that into a
     * refusal to bind (and therefore a refusal to write), rather than into "no people yet": reading a
     * future v2 as empty and then rewriting it as v1 would destroy the whole newer file, which is
     * exactly what the unknown-field carry-through exists to prevent.
     */
    fun decode(json: Json, text: String): List<StoredPerson> {
        val root = json.parseToJsonElement(text).jsonObject
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: VERSION
        require(version == VERSION) { "unsupported people file version $version (this build reads v$VERSION)" }
        return (root["people"] as? JsonArray).orEmpty().map { element ->
            val obj = element.jsonObject
            StoredPerson(json.decodeFromJsonElement(PersonDto.serializer(), obj), obj)
        }
    }

    fun encode(json: Json, people: List<StoredPerson>): ByteArray {
        val array = JsonArray(
            people.map { stored ->
                val known = json.encodeToJsonElement(PersonDto.serializer(), stored.dto).jsonObject
                // Unknown keys first, known ones last, so this build's values win but nothing is lost.
                JsonObject(stored.raw.orEmpty() + known)
            },
        )
        val document = buildJsonObject {
            put("version", VERSION)
            put("people", array)
        }
        return json.encodeToString(JsonObject.serializer(), document).toByteArray(Charsets.UTF_8)
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
    private fun JsonObject?.orEmpty(): Map<String, kotlinx.serialization.json.JsonElement> = this ?: emptyMap()
}

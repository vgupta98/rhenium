package com.vishalgupta.photoselector.data.export

import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** The rating a cull decision maps to in a sidecar, or [Undecided] for a photo in neither bucket. */
enum class RatingDecision(val rating: Int?) {
    Favourite(5),
    Rejected(-1),
    Undecided(null),
}

/** What [XmpDocument.merge] resolved to for one photo. */
sealed interface XmpMergeOutcome {
    /** New sidecar bytes to write atomically. [cleared] is true when this removed a rating we owned. */
    class Write(val bytes: ByteArray, val cleared: Boolean) : XmpMergeOutcome

    /** Nothing to write: no existing sidecar and no decision, or a user-overridden rating left intact. */
    data object Skip : XmpMergeOutcome
}

/**
 * Merges a cull decision into an XMP sidecar **without clobbering it**. Adobe Bridge's real sidecar is
 * a full RDF document (mirrored EXIF, `xmpMM:History`, an embedded-XMP digest); overwriting it would
 * destroy all of that, so we parse the existing bytes with the JDK DOM, mutate *only* our owned fields
 * ([XMP_RATING] and our private ownership stamp), and re-serialize — every other field is left byte-
 * for-byte untouched. When no sidecar exists we emit a minimal valid packet.
 *
 * Two fields are ours:
 *  - `xmp:Rating` — the single Adobe field shared by stars and the reject flag: reject = `-1`,
 *    favourite = `5` (validated in Bridge). We deliberately do **not** write `xmp:Label` (the wrong
 *    field) nor `photoshop:LabelColor` (deferred: the model has no category-colour data yet).
 *  - `rhenium:managedRating` — a stamp under a namespace we own recording the rating we wrote. It lets
 *    an un-decide (a photo dropped from both buckets) clear `xmp:Rating` **only** when the on-disk
 *    value still equals what we stamped, so a rating the user changed in Bridge is never touched.
 *
 * Both fields are read and written in either representation a DAM may use — an attribute on
 * `rdf:Description` **or** a child element — across *all* `rdf:Description` blocks, and are resolved
 * by **namespace** (the parser is namespace-aware), not by a literal `xmp:` prefix. That matters
 * because tools like exiftool write the rating as a child element in its own `rdf:Description` block,
 * sometimes under a different prefix bound to the same Adobe namespace; a prefix-literal match would
 * miss it and append a *second*, conflicting rating. So a set rewrites the first `xmp:Rating` it finds
 * in place and deletes every other occurrence, leaving the document with exactly one rating.
 */
object XmpDocument {

    const val STAMP_PREFIX = "rhenium"
    const val STAMP_LOCAL = "managedRating"
    const val STAMP_QNAME = "$STAMP_PREFIX:$STAMP_LOCAL"

    private const val XMP_NS = "http://ns.adobe.com/xap/1.0/"
    private const val STAMP_NS = "http://ns.rhenium.app/xmp/1.0/"
    private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"
    private const val RATING_QNAME = "xmp:Rating"
    private const val RATING_LOCAL = "Rating"

    /**
     * Resolves the sidecar bytes to write for [decision], given the [existing] sidecar bytes (null =
     * none on disk yet). A favourite/reject always yields a [XmpMergeOutcome.Write]; an [Undecided]
     * photo yields a clearing write only when we own the on-disk rating, else [XmpMergeOutcome.Skip].
     */
    fun merge(existing: ByteArray?, decision: RatingDecision): XmpMergeOutcome {
        val rating = decision.rating
        if (rating != null) {
            if (existing == null) {
                return XmpMergeOutcome.Write(minimalPacket(rating).toByteArray(StandardCharsets.UTF_8), cleared = false)
            }
            val doc = parse(existing)
            val descs = descriptionElements(doc)
            if (descs.isEmpty()) {
                return XmpMergeOutcome.Write(minimalPacket(rating).toByteArray(StandardCharsets.UTF_8), cleared = false)
            }
            val value = rating.toString()

            // The document must end with EXACTLY ONE xmp:Rating (any prefix, either form, any block).
            // A tool like exiftool can leave a foreign rating in its own block; replace it, don't
            // duplicate it. Rewrite the first occurrence in place, delete every other.
            val ratings = findFields(descs, XMP_NS, RATING_LOCAL)
            val target: Element = if (ratings.isEmpty()) {
                // No rating anywhere yet: place it on our stamped block if we have one, else block 0.
                findFields(descs, STAMP_NS, STAMP_LOCAL).firstOrNull()?.owner ?: descs.first()
            } else {
                ratings.drop(1).forEach { it.remove() }
                ratings.first().owner
            }
            if (ratings.isEmpty()) {
                ensurePrefix(target, "xmp", XMP_NS)
                target.setAttributeNS(XMP_NS, RATING_QNAME, value)
            } else {
                ratings.first().setValue(value)
            }
            // Exactly one stamp, alongside the surviving rating: drop any strays, (re)write on target.
            findFields(descs, STAMP_NS, STAMP_LOCAL).forEach { it.remove() }
            ensurePrefix(target, STAMP_PREFIX, STAMP_NS)
            target.setAttributeNS(STAMP_NS, STAMP_QNAME, value)
            return XmpMergeOutcome.Write(serialize(doc), cleared = false)
        }

        // Undecided: only clear a rating we still own (our stamp present AND, in that same block, the
        // on-disk rating still equals what we stamped). A user's Bridge re-rate is never touched.
        if (existing == null) return XmpMergeOutcome.Skip
        val doc = parse(existing)
        val descs = descriptionElements(doc)
        val stamps = findFields(descs, STAMP_NS, STAMP_LOCAL)
        val stamp = stamps.firstOrNull() ?: return XmpMergeOutcome.Skip
        val ownRatings = findFields(listOf(stamp.owner), XMP_NS, RATING_LOCAL)
        if (ownRatings.firstOrNull()?.value != stamp.value) return XmpMergeOutcome.Skip
        // Remove every rating we own (both forms, our block) and every stamp.
        ownRatings.forEach { it.remove() }
        stamps.forEach { it.remove() }
        return XmpMergeOutcome.Write(serialize(doc), cleared = true)
    }

    // --- DOM plumbing --------------------------------------------------------------------------

    private fun minimalPacket(rating: Int): String = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
         xmlns:xmp="$XMP_NS"
         xmlns:$STAMP_PREFIX="$STAMP_NS"
         xmp:Rating="$rating"
         $STAMP_QNAME="$rating"/>
  </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>
"""

    private fun parse(bytes: ByteArray): Document =
        documentBuilder().parse(ByteArrayInputStream(bytes))

    private fun documentBuilder(): DocumentBuilder =
        DocumentBuilderFactory.newInstance().apply {
            // Namespace-aware so a rating is resolved by URI regardless of the prefix a tool used.
            isNamespaceAware = true
            // Sidecars never carry a DOCTYPE; refusing one hardens the parse against XXE.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }.newDocumentBuilder()

    private fun serialize(doc: Document): ByteArray {
        val out = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }.transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray()
    }

    private fun descriptionElements(doc: Document): List<Element> {
        val nodes = doc.getElementsByTagNameNS(RDF_NS, "Description")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /**
     * Every occurrence of the [namespace]/[local] field across [descs], in **either** form — an
     * attribute on the block or a direct child element — resolved by namespace URI, not prefix.
     */
    private fun findFields(descs: List<Element>, namespace: String, local: String): List<FieldRef> {
        val out = ArrayList<FieldRef>()
        for (desc in descs) {
            desc.getAttributeNodeNS(namespace, local)?.let { out += FieldRef.Attribute(desc, it) }
            val kids = desc.childNodes
            for (i in 0 until kids.length) {
                val n = kids.item(i)
                if (n is Element && namespace == n.namespaceURI && local == n.localName) {
                    out += FieldRef.Child(desc, n)
                }
            }
        }
        return out
    }

    /** Declares [prefix]=[ns] on [el] unless the prefix already resolves to that URI in scope. */
    private fun ensurePrefix(el: Element, prefix: String, ns: String) {
        if (el.lookupNamespaceURI(prefix) != ns) el.setAttributeNS(XMLNS_NS, "xmlns:$prefix", ns)
    }

    /**
     * A single owned-field occurrence with its block — either an attribute node or a child element.
     * The two forms are distinct subtypes so the "exactly one carrier" invariant is guaranteed by the
     * type rather than asserted at each use.
     */
    private sealed class FieldRef(val owner: Element) {
        abstract val value: String
        abstract fun setValue(v: String)
        abstract fun remove()

        /** The `xmp:Rating="..."` attribute form. */
        class Attribute(owner: Element, private val attr: Attr) : FieldRef(owner) {
            override val value: String get() = attr.value
            override fun setValue(v: String) { attr.value = v }
            override fun remove() { owner.removeAttributeNode(attr) }
        }

        /** The `<xmp:Rating>...</xmp:Rating>` child-element form. */
        class Child(owner: Element, private val element: Element) : FieldRef(owner) {
            override val value: String get() = element.textContent
            override fun setValue(v: String) { element.textContent = v }
            override fun remove() { owner.removeChild(element) }
        }
    }
}

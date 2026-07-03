package com.vishalgupta.photoselector.data.export

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XmpDocumentTest {

    // A realistic Bridge-style sidecar: a full RDF document with mirrored EXIF and an xmpMM:History
    // block. Merging our rating must preserve every one of these fields byte-for-content.
    private val richSidecar = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:xmp="http://ns.adobe.com/xap/1.0/"
        xmlns:exif="http://ns.adobe.com/exif/1.0/"
        xmlns:xmpMM="http://ns.adobe.com/xap/1.0/mm/"
        exif:ISOSpeedRatings="400"
        exif:FNumber="28/10"
        xmpMM:DocumentID="xmp.did:ABC123">
      <xmpMM:History>
        <rdf:Seq xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:li>saved</rdf:li>
        </rdf:Seq>
      </xmpMM:History>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>
"""

    // --- Merge preserves everything else -------------------------------------------------------

    @Test
    fun `merging a favourite into a rich sidecar sets our rating and stamp while preserving all other fields`() {
        val out = writeBytes(richSidecar.toByteArray(StandardCharsets.UTF_8), RatingDecision.Favourite)
        val text = String(out, StandardCharsets.UTF_8)
        val desc = descriptionOf(out)

        // Our two owned fields are set.
        assertEquals("5", desc.getAttribute("xmp:Rating"))
        assertEquals("5", desc.getAttribute(XmpDocument.STAMP_QNAME))

        // Every foreign field survives untouched.
        assertEquals("400", desc.getAttribute("exif:ISOSpeedRatings"))
        assertEquals("28/10", desc.getAttribute("exif:FNumber"))
        assertEquals("xmp.did:ABC123", desc.getAttribute("xmpMM:DocumentID"))
        assertTrue(text.contains("xmpMM:History"), "history block preserved")
        assertTrue(text.contains("saved"), "history entry preserved")
        // We never write the wrong-field xmp:Label.
        assertFalse(desc.hasAttribute("xmp:Label"))
    }

    // --- Both field representations round-trip -------------------------------------------------

    @Test
    fun `an existing attribute-form rating is updated in place`() {
        val existing = """<?xpacket begin="" id="x"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="2"/>
</rdf:RDF></x:xmpmeta>""".toByteArray(StandardCharsets.UTF_8)

        val desc = descriptionOf(writeBytes(existing, RatingDecision.Rejected))
        assertEquals("-1", desc.getAttribute("xmp:Rating"))
    }

    @Test
    fun `an existing element-form rating is updated as an element, not duplicated as an attribute`() {
        val existing = """<?xpacket begin="" id="x"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/"><xmp:Rating>2</xmp:Rating></rdf:Description>
</rdf:RDF></x:xmpmeta>""".toByteArray(StandardCharsets.UTF_8)

        val desc = descriptionOf(writeBytes(existing, RatingDecision.Favourite))
        // The element form is preserved and updated; no attribute is added alongside it.
        assertFalse(desc.hasAttribute("xmp:Rating"), "no attribute added")
        assertEquals("5", directChildText(desc, "xmp:Rating"))
    }

    // --- Un-decide safety ----------------------------------------------------------------------

    @Test
    fun `un-decide clears the rating and stamp when the on-disk value still equals what we wrote`() {
        // A sidecar we wrote (rating 5 + stamp 5), then the photo is dropped from both buckets.
        val ours = writeBytes(existing = null, decision = RatingDecision.Favourite)
        val outcome = XmpDocument.merge(ours, RatingDecision.Undecided)

        assertTrue(outcome is XmpMergeOutcome.Write)
        assertTrue((outcome as XmpMergeOutcome.Write).cleared, "this write is a clear")
        val desc = descriptionOf(outcome.bytes)
        assertFalse(desc.hasAttribute("xmp:Rating"))
        assertFalse(desc.hasAttribute(XmpDocument.STAMP_QNAME))
    }

    @Test
    fun `un-decide clears an element-form stamped rating when the on-disk value still matches`() {
        // Our rating and stamp stored as child elements (not attributes). The clear must hit the
        // directChild / removeChild branch, leaving neither the rating element nor the stamp.
        val elementForm = """<?xpacket begin="" id="x"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:rhenium="http://ns.rhenium.app/xmp/1.0/">
<xmp:Rating>5</xmp:Rating><rhenium:managedRating>5</rhenium:managedRating></rdf:Description>
</rdf:RDF></x:xmpmeta>""".toByteArray(StandardCharsets.UTF_8)

        val outcome = XmpDocument.merge(elementForm, RatingDecision.Undecided)
        assertTrue((outcome as XmpMergeOutcome.Write).cleared, "this write is a clear")
        val desc = descriptionOf(outcome.bytes)
        assertFalse(desc.hasAttribute("xmp:Rating"))
        assertNull(directChildText(desc, "xmp:Rating"), "rating element removed")
        assertFalse(desc.hasAttribute(XmpDocument.STAMP_QNAME))
        assertNull(directChildText(desc, XmpDocument.STAMP_QNAME), "stamp element removed")
    }

    @Test
    fun `un-decide leaves an element-form rating the user overrode untouched`() {
        val overridden = """<?xpacket begin="" id="x"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:rhenium="http://ns.rhenium.app/xmp/1.0/">
<xmp:Rating>3</xmp:Rating><rhenium:managedRating>5</rhenium:managedRating></rdf:Description>
</rdf:RDF></x:xmpmeta>""".toByteArray(StandardCharsets.UTF_8)

        assertTrue(XmpDocument.merge(overridden, RatingDecision.Undecided) is XmpMergeOutcome.Skip)
    }

    // --- Multiple rdf:Description blocks -------------------------------------------------------

    @Test
    fun `a rating in a non-first rdf-Description block is updated in place, not duplicated into block 0`() {
        // The rating lives in the SECOND rdf:Description; block 0 holds only foreign fields. Merging
        // must rewrite that second block's rating, never append a conflicting one to block 0.
        val twoBlocks = """<?xpacket begin="" id="x"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:exif="http://ns.adobe.com/exif/1.0/" exif:ISOSpeedRatings="100"/>
<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="2"/>
</rdf:RDF></x:xmpmeta>""".toByteArray(StandardCharsets.UTF_8)

        val out = writeBytes(twoBlocks, RatingDecision.Rejected)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(out))
        val blocks = doc.getElementsByTagName("rdf:Description")
        assertEquals(2, blocks.length, "no block was added or removed")
        // Exactly one rating statement, in the block that already had it, now updated to -1.
        assertFalse((blocks.item(0) as Element).hasAttribute("xmp:Rating"), "block 0 stays rating-free")
        assertEquals("-1", (blocks.item(1) as Element).getAttribute("xmp:Rating"))
        assertEquals("100", (blocks.item(0) as Element).getAttribute("exif:ISOSpeedRatings"))
    }

    @Test
    fun `a foreign element-form rating in a separate block is replaced, never duplicated`() {
        // The exact real-world break: a third-party tool (exiftool) rewrote the sidecar with a rating
        // as a CHILD ELEMENT in its OWN rdf:Description block and stripped our stamp. A re-export SET
        // must leave the document with EXACTLY ONE xmp:Rating equal to our value - not our new one
        // plus the leftover foreign 5.
        val exiftoolStyle = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:exif="http://ns.adobe.com/exif/1.0/" exif:ISOSpeedRatings="100"/>
<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/"><xmp:Rating>5</xmp:Rating></rdf:Description>
</rdf:RDF></x:xmpmeta>
<?xpacket end="w"?>""".toByteArray(StandardCharsets.UTF_8)

        val out = writeBytes(exiftoolStyle, RatingDecision.Rejected)
        assertEquals(listOf("-1"), allRatingValues(out), "exactly one rating, equal to our value")
        assertEquals("100", firstBlockAttr(out, "exif:ISOSpeedRatings"), "foreign field survives")
    }

    @Test
    fun `a foreign rating under a different prefix bound to the xmp namespace is replaced by namespace`() {
        // Same break but the foreign block binds the Adobe namespace to a NON-xmp prefix (xap). A
        // literal prefix match would miss it and duplicate; namespace resolution replaces it.
        val differentPrefix = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:exif="http://ns.adobe.com/exif/1.0/" exif:ISOSpeedRatings="100"/>
<rdf:Description rdf:about="" xmlns:xap="http://ns.adobe.com/xap/1.0/"><xap:Rating>5</xap:Rating></rdf:Description>
</rdf:RDF></x:xmpmeta>
<?xpacket end="w"?>""".toByteArray(StandardCharsets.UTF_8)

        val out = writeBytes(differentPrefix, RatingDecision.Rejected)
        assertEquals(listOf("-1"), allRatingValues(out), "exactly one rating, equal to our value")
    }

    @Test
    fun `a foreign attribute-form rating in a separate block is replaced, never duplicated`() {
        val attrForm = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:exif="http://ns.adobe.com/exif/1.0/" exif:ISOSpeedRatings="100"/>
<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="5"/>
</rdf:RDF></x:xmpmeta>
<?xpacket end="w"?>""".toByteArray(StandardCharsets.UTF_8)

        val out = writeBytes(attrForm, RatingDecision.Rejected)
        assertEquals(listOf("-1"), allRatingValues(out), "exactly one rating, equal to our value")
    }

    @Test
    fun `un-decide leaves a user-overridden rating untouched`() {
        // We stamped 5, but the user re-rated to 3 in Bridge: the on-disk rating no longer matches
        // the stamp, so an un-decide must not touch it.
        val overridden = String(writeBytes(null, RatingDecision.Favourite), StandardCharsets.UTF_8)
            .replace("xmp:Rating=\"5\"", "xmp:Rating=\"3\"")
            .toByteArray(StandardCharsets.UTF_8)

        assertTrue(XmpDocument.merge(overridden, RatingDecision.Undecided) is XmpMergeOutcome.Skip)
    }

    @Test
    fun `un-decide leaves a sidecar we never stamped alone`() {
        val foreign = """<?xpacket begin="" id="x"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="4"/>
</rdf:RDF></x:xmpmeta>""".toByteArray(StandardCharsets.UTF_8)

        assertTrue(XmpDocument.merge(foreign, RatingDecision.Undecided) is XmpMergeOutcome.Skip)
    }

    @Test
    fun `un-decide with no existing sidecar writes nothing`() {
        assertTrue(XmpDocument.merge(null, RatingDecision.Undecided) is XmpMergeOutcome.Skip)
    }

    @Test
    fun `a favourite with no existing sidecar emits a minimal packet carrying rating and stamp`() {
        val desc = descriptionOf(writeBytes(existing = null, decision = RatingDecision.Favourite))
        assertEquals("5", desc.getAttribute("xmp:Rating"))
        assertEquals("5", desc.getAttribute(XmpDocument.STAMP_QNAME))
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun writeBytes(existing: ByteArray?, decision: RatingDecision): ByteArray {
        val outcome = XmpDocument.merge(existing, decision)
        assertTrue(outcome is XmpMergeOutcome.Write, "expected a Write, got $outcome")
        return (outcome as XmpMergeOutcome.Write).bytes
    }

    private fun descriptionOf(bytes: ByteArray): Element {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))
        val nodes = doc.getElementsByTagName("rdf:Description")
        assertEquals(1, nodes.length, "expected exactly one rdf:Description")
        return nodes.item(0) as Element
    }

    private fun directChildText(parent: Element, qname: String): String? {
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.tagName == qname) return n.textContent
        }
        return null
    }

    /**
     * Every xmp:Rating value in the document, resolved by NAMESPACE across all rdf:Description blocks
     * and both forms (attribute + child element) - so a duplicate under any prefix or form is caught.
     */
    private fun allRatingValues(bytes: ByteArray): List<String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val blocks = doc.getElementsByTagNameNS(RDF_NS, "Description")
        val out = ArrayList<String>()
        for (b in 0 until blocks.length) {
            val el = blocks.item(b) as Element
            el.getAttributeNodeNS(XMP_NS, "Rating")?.let { out += it.value }
            val kids = el.childNodes
            for (i in 0 until kids.length) {
                val n = kids.item(i)
                if (n is Element && XMP_NS == n.namespaceURI && "Rating" == n.localName) out += n.textContent
            }
        }
        return out
    }

    private fun firstBlockAttr(bytes: ByteArray, qname: String): String {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))
        return (doc.getElementsByTagName("rdf:Description").item(0) as Element).getAttribute(qname)
    }

    private companion object {
        const val XMP_NS = "http://ns.adobe.com/xap/1.0/"
        const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    }
}

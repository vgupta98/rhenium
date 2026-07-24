package com.vishalgupta.photoselector.domain.insight

/**
 * A human, ordered *band* an [InsightValue.Scalar] is bucketed into (e.g. "sharp" / "soft"). Bands are
 * the vocabulary the rule leaf and the panel speak in — never a raw threshold — because a raw number
 * ("sharpness > 63") is meaningless to a photographer (see the insight-provider platform note). Which
 * band a raw score falls into is a property of the analyzed *set*, computed by an [InsightBanding].
 *
 * A value class over its id so it is `Stable` at Compose boundaries and cheap to compare/serialize.
 */
@JvmInline
value class InsightBand(val id: String) {
    companion object {
        /** The two bands the shipped sharpness provider uses. */
        val Sharp = InsightBand("sharp")
        val Soft = InsightBand("soft")
    }
}

/** The min/max a [InsightValue.Scalar] can span, for the panel's labelled bar. Absent when unknown. */
data class InsightRange(val min: Float, val max: Float)

/**
 * The keystone of the insight platform: a small **closed** set of typed values a provider can emit, so
 * the panel, the tile lane and the predicate leaf are generic over *any* future model rather than
 * special-casing each one (see `proposals/extensibility/02-insight-provider-platform.md`).
 *
 * Only [Scalar] is built out in Phase 1 (it backs the bundled sharpness provider). [Boolean], [Label]
 * and [LabelSet] are **documented scaffold** — they prove the seam is genuinely generic and reserve the
 * shape of the other three taxonomy rows, but carry no comparators and no rendering yet. A later phase
 * fills them in; until then no provider emits them and no consumer renders them.
 */
sealed interface InsightValue {

    /**
     * A float measurement with an optional [range] (for the labelled bar) and an optional human [band].
     * The provider emits the [raw] value only (banding is a set property it can't know in isolation);
     * a consumer bands it against the analyzed distribution — filling [band]/[range] — before display.
     */
    data class Scalar(
        val raw: Float,
        val range: InsightRange? = null,
        val band: InsightBand? = null,
    ) : InsightValue

    /** Scaffold — a yes/no signal (e.g. eyes-open). No comparator/render yet; reserved for a later phase. */
    data class Boolean(val value: kotlin.Boolean) : InsightValue

    /** Scaffold — one-of an enum (e.g. dominant scene). No comparator/render yet; reserved for a later phase. */
    data class Label(val value: String) : InsightValue

    /** Scaffold — many labels (e.g. tags, people present). No comparator/render yet; reserved for a later phase. */
    data class LabelSet(val values: Set<String>) : InsightValue
}

/** A rendered insight paired with the human label it answers to — the row shape the panel lays out. */
data class LabeledInsight(val label: String, val value: InsightValue)

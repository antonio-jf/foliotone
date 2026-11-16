package com.linguareader

/** Millisecond estimates for a section whose narration is generated one page at a time. */
class SectionTimeline(pages: List<String>, speechRate: Float) {
    private val durations = pages.map { text ->
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtLeast(1)
        (words * 60_000.0 / (150 * speechRate.coerceIn(0.75f, 1.2f))).toLong().coerceAtLeast(1_000)
    }
    private val offsets = durations.runningFold(0L, Long::plus)
    val durationMs = offsets.last()

    fun pageDurationMs(page: Int) = durations.getOrElse(page) { 1_000L }

    fun positionMs(page: Int, fraction: Double = 0.0): Long =
        ((offsets.getOrElse(page) { 0L }) + pageDurationMs(page) * fraction.coerceIn(0.0, 1.0))
            .toLong().coerceIn(0, durationMs)

    /** Media scrubbing resumes from the beginning of the containing page. */
    fun pageAt(positionMs: Long): Int {
        val target = positionMs.coerceIn(0, durationMs)
        return durations.indices.lastOrNull { offsets[it] <= target } ?: 0
    }
}

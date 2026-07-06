package com.mica.music.data

/** 歌词与播放进度对齐（播放页三行与全屏歌词页共用）。 */
object LyricsSync {

    /** 略提前切换当前行，抵消听感上的滞后。 */
    const val LEAD_MS = 150

    fun hasTimedLyrics(lyrics: List<LyricLine>): Boolean =
        lyrics.any { it.timeMs > 0 }

    fun indexForPosition(lyrics: List<LyricLine>, positionMs: Int): Int {
        if (lyrics.isEmpty() || !hasTimedLyrics(lyrics)) return -1
        val t = positionMs + LEAD_MS
        var idx = 0
        for (i in lyrics.indices) {
            if (lyrics[i].timeMs <= t) idx = i else break
        }
        return idx
    }

    fun highlightLineIndicesAt(lyrics: List<LyricLine>, positionMs: Int): Set<Int> {
        if (lyrics.isEmpty() || !hasTimedLyrics(lyrics)) return emptySet()
        val t = positionMs + LEAD_MS
        val anchorIndex = primaryLineIndexAt(lyrics, positionMs)
        if (anchorIndex < 0) return emptySet()
        val anchorLine = lyrics[anchorIndex]
        if (t < anchorLine.timeMs) return emptySet()

        var minIndex = anchorIndex
        var maxIndex = anchorIndex
        for (index in lyrics.indices) {
            val line = lyrics[index]
            if (line.timeMs <= t && (index == anchorIndex || lyrics.overlaps(index, anchorIndex))) {
                minIndex = minOf(minIndex, index)
                maxIndex = maxOf(maxIndex, index)
            }
        }
        return (minIndex..maxIndex).filterTo(mutableSetOf()) { lyrics[it].timeMs <= t }
    }

    fun primaryLineIndexAt(lyrics: List<LyricLine>, positionMs: Int): Int {
        if (lyrics.isEmpty() || !hasTimedLyrics(lyrics)) return -1
        val t = positionMs + LEAD_MS
        var bestActiveIndex = -1
        var bestEndedIndex = -1
        var lastStartedIndex = -1

        for (index in lyrics.indices) {
            val line = lyrics[index]
            val lineEnd = lyrics.endTimeAt(index)
            if (line.timeMs <= t && t < lineEnd) {
                if (bestActiveIndex == -1 || line.timeMs >= lyrics[bestActiveIndex].timeMs) {
                    bestActiveIndex = index
                }
            }
            if (lineEnd <= t) {
                if (bestEndedIndex == -1 || lineEnd >= lyrics.endTimeAt(bestEndedIndex)) {
                    bestEndedIndex = index
                }
            }
            if (line.timeMs <= t) {
                lastStartedIndex = index
            }
        }

        return when {
            bestActiveIndex != -1 -> bestActiveIndex
            bestEndedIndex != -1 -> bestEndedIndex
            lastStartedIndex != -1 -> lastStartedIndex
            else -> 0
        }
    }

    /** Returns the active cue in [line], or -1 when the line has no usable word timing. */
    fun cueIndexForPosition(line: LyricLine, positionMs: Int): Int {
        if (line.cues.isEmpty()) return -1
        val t = positionMs + LEAD_MS
        if (t < line.timeMs || t < line.cues.first().timeMs) return -1
        return line.cues.indexOfLast { it.timeMs <= t }
    }

    private fun List<LyricLine>.overlaps(leftIndex: Int, rightIndex: Int): Boolean =
        this[leftIndex].timeMs < endTimeAt(rightIndex) && this[rightIndex].timeMs < endTimeAt(leftIndex)

    private fun List<LyricLine>.endTimeAt(index: Int): Int {
        val line = this[index]
        val nextStart = asSequence()
            .drop(index + 1)
            .firstOrNull { it.timeMs > line.timeMs }
            ?.timeMs
        val fallback = nextStart ?: (line.timeMs + DEFAULT_LINE_DURATION_MS)
        return line.resolvedEndTimeMs(fallback).coerceAtLeast(line.timeMs)
    }

    private const val DEFAULT_LINE_DURATION_MS = 5_000
}

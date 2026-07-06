package com.mica.music.data.scanner

import com.mica.music.data.LyricCell
import com.mica.music.data.LyricCue
import com.mica.music.data.LyricLine

internal object LrcParser {

    private const val DEFAULT_CELL_DURATION_MS = 2_000
    private const val DEFAULT_LINE_DURATION_MS = 5_000

    private val bracketTimestamp = Regex("""\[(\d{1,3}):(\d{2})(?::(\d{2}))?(?:[.:](\d{1,3}))?\]""")
    private val angleTimestamp = Regex("""<(\d{1,3}):(\d{2})(?::(\d{2}))?(?:[.:](\d{1,3}))?>""")
    private val offsetTag = Regex("""(?i)\[offset:\s*([+-]?\d+)\s*\]""")
    private val tagLine = Regex("""\[[^:\]]+:[^\]]*\]""")
    private val kugouLine = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val kugouCue = Regex("""<(\d+),(\d+),(\d+)>([^<]*)""")
    private val leadingVersionMarkerCue = Regex("""(?i)^v\d+:\s*$""")
    private val leadingVersionMarkerText = Regex("""(?i)^v\d+:\s*""")

    fun parse(text: String): List<LyricLine> {
        if (TtmlLyricsParser.looksLikeTtml(text)) return TtmlLyricsParser.parse(text)

        val sourceLines = text.lines()
        val offsetMs = sourceLines.firstNotNullOfOrNull { line ->
            offsetTag.find(line)?.groupValues?.get(1)?.toIntOrNull()
        } ?: 0
        val parsedLines = mutableListOf<LyricLine>()

        for (rawLine in sourceLines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            parseKugouLine(trimmed, offsetMs)?.let { line ->
                if (shouldKeepParsedLine(line)) parsedLines += line
                continue
            }

            val matches = bracketTimestamp.findAll(trimmed).toList()
            val leadingMatches = leadingTimestampMatches(matches)
            if (leadingMatches.isNotEmpty() && leadingMatches.size == matches.size) {
                val body = trimmed.substring(leadingMatches.last().range.last + 1)
                leadingMatches.forEach { match ->
                    val lineTimeMs = (timestampMs(match) + offsetMs).coerceAtLeast(0)
                    parseEnhancedBody(body, lineTimeMs, offsetMs)?.let { line ->
                        if (shouldKeepParsedLine(line)) parsedLines += line
                    }
                }
                continue
            }

            parseInlineBracketLine(trimmed, matches, offsetMs)?.let { line ->
                if (shouldKeepParsedLine(line)) {
                    parsedLines += line
                    continue
                }
            }

            if (!trimmed.startsWith("[") &&
                !LyricsSanitizer.isPlaceholderLyric(trimmed) &&
                !LyricsSanitizer.isBinaryGarbage(trimmed)
            ) {
                timedLine(
                    startTimeMs = 0,
                    endTimeMs = DEFAULT_LINE_DURATION_MS,
                    cells = listOf(LyricCell(0, DEFAULT_LINE_DURATION_MS, MetadataTextFix.normalize(trimmed), false)),
                )?.let { parsedLines += it }
            }
        }

        val parsed = if (parsedLines.isNotEmpty()) {
            parsedLines
        } else {
            text.lines()
                .map { it.trim() }
                .filter {
                    it.isNotEmpty() &&
                        !tagLine.matches(it) &&
                        !LyricsSanitizer.isPlaceholderLyric(it) &&
                        !LyricsSanitizer.isBinaryGarbage(it)
                }
                .mapNotNull { plain ->
                    timedLine(
                        startTimeMs = 0,
                        endTimeMs = DEFAULT_LINE_DURATION_MS,
                        cells = listOf(LyricCell(0, DEFAULT_LINE_DURATION_MS, MetadataTextFix.normalize(plain), false)),
                    )
                }
        }

        return mergeSameTimestampTracks(clampLineEnds(parsed))
    }

    private fun leadingTimestampMatches(matches: List<MatchResult>): List<MatchResult> {
        if (matches.isEmpty() || matches.first().range.first != 0) return emptyList()
        val leading = mutableListOf<MatchResult>()
        var expectedStart = 0
        for (match in matches) {
            if (match.range.first != expectedStart) break
            leading += match
            expectedStart = match.range.last + 1
        }
        return leading
    }

    /** [00:00.000]字[00:00.022]词 — common in NetEase/QQ embedded word lyrics. */
    private fun parseInlineBracketLine(
        trimmed: String,
        matches: List<MatchResult>,
        offsetMs: Int,
    ): LyricLine? {
        if (matches.size < 2 || matches.first().range.first != 0) return null
        val leading = leadingTimestampMatches(matches)
        if (leading.size == matches.size) return null

        val lineTimeMs = (timestampMs(matches.first()) + offsetMs).coerceAtLeast(0)
        val cells = matches.mapIndexedNotNull { index, match ->
            val fragmentStart = match.range.last + 1
            val fragmentEnd = matches.getOrNull(index + 1)?.range?.first ?: trimmed.length
            val fragment = MetadataTextFix.normalizeFragment(trimmed.substring(fragmentStart, fragmentEnd))
            if (fragment.isEmpty()) return@mapIndexedNotNull null
            val start = (timestampMs(match) + offsetMs).coerceAtLeast(0)
            val end = matches.getOrNull(index + 1)
                ?.let { (timestampMs(it) + offsetMs).coerceAtLeast(start) }
                ?: (start + DEFAULT_CELL_DURATION_MS)
            LyricCell(start, end, fragment)
        }
        if (cells.isEmpty()) return null
        return timedLine(lineTimeMs, cells.last().endTimeMs, stripLeadingVersionMarker(cells, lineTimeMs))
    }

    private fun parseEnhancedBody(body: String, lineTimeMs: Int, offsetMs: Int): LyricLine? {
        val matches = angleTimestamp.findAll(body).toList()
        if (matches.isEmpty()) {
            val normalized = MetadataTextFix.normalize(body).trim()
            if (normalized.isEmpty()) return null
            val clean = leadingVersionMarkerText.replaceFirst(normalized, "").trim()
            if (clean.isEmpty()) return null
            return timedLine(
                startTimeMs = lineTimeMs,
                endTimeMs = lineTimeMs + DEFAULT_LINE_DURATION_MS,
                cells = listOf(LyricCell(lineTimeMs, lineTimeMs + DEFAULT_LINE_DURATION_MS, clean, timed = false)),
            )
        }

        val cells = mutableListOf<LyricCell>()
        val firstTimedStart = (timestampMs(matches.first()) + offsetMs).coerceAtLeast(0)
        val prefix = body.substring(0, matches.first().range.first)
        val normalizedPrefix = MetadataTextFix.normalizeFragment(prefix)
        if (normalizedPrefix.isNotEmpty() && normalizedPrefix.isNotBlank()) {
            cells += LyricCell(lineTimeMs, firstTimedStart.coerceAtLeast(lineTimeMs), normalizedPrefix, timed = false)
        }

        matches.forEachIndexed { index, match ->
            val start = (timestampMs(match) + offsetMs).coerceAtLeast(0)
            val textStart = match.range.last + 1
            val textEnd = matches.getOrNull(index + 1)?.range?.first ?: body.length
            val fragment = MetadataTextFix.normalizeFragment(body.substring(textStart, textEnd))
            if (fragment.isEmpty()) return@forEachIndexed
            val end = matches.getOrNull(index + 1)
                ?.let { (timestampMs(it) + offsetMs).coerceAtLeast(start) }
                ?: (start + DEFAULT_CELL_DURATION_MS)
            cells += LyricCell(start, end, fragment)
        }
        if (cells.isEmpty()) return null
        return timedLine(lineTimeMs, cells.last().endTimeMs, stripLeadingVersionMarker(cells, lineTimeMs))
    }

    private fun parseKugouLine(line: String, offsetMs: Int): LyricLine? {
        val match = kugouLine.matchEntire(line) ?: return null
        val sourceLineStart = match.groupValues[1].toLongOrNull() ?: return null
        val lineDuration = match.groupValues[2].toLongOrNull() ?: DEFAULT_LINE_DURATION_MS.toLong()
        val lineStart = (sourceLineStart + offsetMs).coerceToInt()
        val lineEnd = (sourceLineStart + lineDuration + offsetMs).coerceToInt().coerceAtLeast(lineStart)
        val body = match.groupValues[3]
        val cells = kugouCue.findAll(body).mapNotNull { cue ->
            val relative = cue.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val duration = cue.groupValues[2].toLongOrNull() ?: 0L
            val start = (sourceLineStart + relative + offsetMs).coerceToInt().coerceAtLeast(lineStart)
            val end = (sourceLineStart + relative + duration + offsetMs).coerceToInt().coerceAtLeast(start)
            val text = MetadataTextFix.normalizeFragment(cue.groupValues[4])
            text.takeIf { it.isNotEmpty() }?.let { LyricCell(start, end, it) }
        }.toList()
        if (cells.isEmpty()) return null
        return timedLine(lineStart, lineEnd.coerceAtLeast(cells.last().endTimeMs), stripLeadingVersionMarker(cells, lineStart))
    }

    private fun stripLeadingVersionMarker(cells: List<LyricCell>, lineTimeMs: Int): List<LyricCell> {
        val working = cells.dropWhile { leadingVersionMarkerCue.matches(it.text.trim()) }.toMutableList()
        if (working.isNotEmpty()) {
            val first = working.first()
            val stripped = leadingVersionMarkerText.replaceFirst(first.text, "")
            if (stripped.isEmpty()) {
                working.removeAt(0)
            } else if (stripped != first.text) {
                working[0] = first.copy(text = stripped, startTimeMs = first.startTimeMs.coerceAtLeast(lineTimeMs))
            }
        }
        return working
    }

    private fun timedLine(
        startTimeMs: Int,
        endTimeMs: Int,
        cells: List<LyricCell>,
        subText: String? = null,
        romanizationText: String? = null,
    ): LyricLine? {
        val cleanCells = cells.filter { it.text.isNotEmpty() }
        val mainText = MetadataTextFix.normalize(cleanCells.joinToString(separator = "") { it.text }).trim()
        if (mainText.isEmpty()) return null
        val safeEnd = endTimeMs.coerceAtLeast(startTimeMs)
        val boundedCells = cleanCells.mapIndexed { index, cell ->
            val cellEnd = if (index == cleanCells.lastIndex) {
                cell.endTimeMs.coerceAtMost(safeEnd).coerceAtLeast(cell.startTimeMs)
            } else {
                cell.endTimeMs.coerceAtLeast(cell.startTimeMs)
            }
            cell.copy(endTimeMs = cellEnd)
        }
        val cues = boundedCells
            .filter { it.timed && it.text.isNotEmpty() }
            .map { LyricCue(it.startTimeMs, it.text) }
        val translation = subText?.let { MetadataTextFix.normalize(it).trim() }?.takeIf { it.isNotEmpty() }
        val romanization = romanizationText?.let { MetadataTextFix.normalize(it).trim() }?.takeIf { it.isNotEmpty() }
        return LyricLine(
            timeMs = startTimeMs,
            text = displayText(mainText, romanization, translation),
            cues = cues,
            endTimeMs = safeEnd,
            cells = boundedCells,
            subText = translation,
            romanizationText = romanization,
        )
    }

    private fun clampLineEnds(lines: List<LyricLine>): List<LyricLine> {
        if (lines.size < 2) return lines.sortedBy { it.timeMs }
        val sorted = lines.sortedBy { it.timeMs }
        return sorted.mapIndexed { index, line ->
            val nextStart = sorted.asSequence()
                .drop(index + 1)
                .firstOrNull { it.timeMs > line.timeMs }
                ?.timeMs
                ?: return@mapIndexed line
            val end = line.endTimeMs ?: return@mapIndexed line.copy(endTimeMs = nextStart)
            if (end > nextStart || end <= line.timeMs) {
                line.withEndTime(nextStart)
            } else {
                line
            }
        }
    }

    private fun mergeSameTimestampTracks(lines: List<LyricLine>): List<LyricLine> {
        if (lines.size < 2 || lines.none { it.timeMs > 0 }) return lines.sortedBy { it.timeMs }
        val grouped = lines.sortedBy { it.timeMs }.groupBy { it.timeMs }.toSortedMap()
        return buildList {
            grouped.values.forEach { group ->
                if (group.size == 1) {
                    add(group.single())
                    return@forEach
                }

                val original = when {
                    group.size == 2 -> group.firstOrNull { it.hasKaraokeCells() } ?: group.first()
                    else -> group.first()
                }
                val companions = group.filterNot { it === original }
                val romanization = if (companions.size >= 2) companions.first().mainText else null
                val translation = when {
                    companions.size >= 2 -> companions.drop(1).joinToString("\n") { it.mainText }
                    companions.size == 1 -> companions.single().mainText
                    else -> null
                }
                add(
                    original.copy(
                        text = displayText(original.mainText, romanization, translation),
                        subText = translation?.takeIf { it.isNotBlank() },
                        romanizationText = romanization?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    private fun LyricLine.withEndTime(endTimeMs: Int): LyricLine {
        val boundedCells = cells.mapIndexed { index, cell ->
            if (index == cells.lastIndex && cell.endTimeMs > endTimeMs) {
                cell.copy(endTimeMs = endTimeMs.coerceAtLeast(cell.startTimeMs))
            } else {
                cell
            }
        }
        return copy(endTimeMs = endTimeMs, cells = boundedCells)
    }

    private fun LyricLine.hasKaraokeCells(): Boolean =
        cells.count { it.timed } > 1 || cues.size > 1

    private fun shouldKeepParsedLine(line: LyricLine): Boolean {
        if (LyricsSanitizer.isPlaceholderLyric(line.text)) return false
        if (line.cells.isNotEmpty() || line.cues.isNotEmpty()) return true
        return !LyricsSanitizer.isBinaryGarbage(line.text)
    }

    private fun timestampMs(match: MatchResult): Int {
        val first = match.groupValues[1].toIntOrNull() ?: 0
        val second = match.groupValues[2].toIntOrNull() ?: 0
        val third = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull()
        val fraction = match.groupValues.getOrNull(4).orEmpty()
        val fractionMs = when (fraction.length) {
            3 -> fraction.toIntOrNull() ?: 0
            2 -> (fraction.toIntOrNull() ?: 0) * 10
            1 -> (fraction.toIntOrNull() ?: 0) * 100
            else -> 0
        }
        val totalSeconds = if (third == null) {
            first * 60 + second
        } else {
            first * 3_600 + second * 60 + third
        }
        return totalSeconds * 1_000 + fractionMs
    }

    private fun Long.coerceToInt(): Int =
        coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun displayText(mainText: String, romanizationText: String?, subText: String?): String =
        buildList {
            add(mainText)
            romanizationText?.takeIf { it.isNotBlank() }?.let(::add)
            subText?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString("\n")
}

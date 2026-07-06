package com.mica.music.data.scanner

import com.mica.music.data.LyricCell
import com.mica.music.data.LyricCue
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricLineSide
import com.mica.music.util.DiagnosticLog
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

internal object TtmlLyricsParser {
    private const val MAX_DOCUMENT_CHARS = 2_000_000
    private const val MAX_PARAGRAPHS = 5_000
    private const val MAX_CELLS = 50_000
    private const val LYRICS_TRACE = "DEBUG-LYRICS-7C31"
    private const val NS_TTM = "http://www.w3.org/ns/ttml#metadata"
    private const val NS_ITUNES_INTERNAL = "http://music.apple.com/lyric-ttml-internal"
    private const val NS_ITUNES_LEGACY = "http://music.apple.com/itunes/ttml"
    private const val NS_XML = "http://www.w3.org/XML/1998/namespace"

    private val forbiddenDeclaration = Regex("""<!\s*(?:DOCTYPE|ENTITY)\b""", RegexOption.IGNORE_CASE)

    fun looksLikeTtml(text: String): Boolean {
        val normalized = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return normalized.startsWith('<') &&
            Regex("""<\s*(?:\w+:)?tt\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
    }

    fun parse(text: String): List<LyricLine> {
        return parseWithFactory(text, DocumentBuilderFactory.newInstance())
    }

    internal fun parseWithFactory(text: String, factory: DocumentBuilderFactory): List<LyricLine> {
        if (!looksLikeTtml(text) || text.length > MAX_DOCUMENT_CHARS || forbiddenDeclaration.containsMatchIn(text)) {
            return emptyList()
        }
        return runCatching {
            factory.apply {
                isNamespaceAware = true
                isValidating = false
                runCatching { isXIncludeAware = false }
                runCatching { setExpandEntityReferences(false) }
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
                runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }
            val document = builder.parse(InputSource(StringReader(text)))
            val root = document.documentElement ?: return emptyList()
            val paragraphs = root.elementsByLocalName("p")
            if (paragraphs.size !in 1..MAX_PARAGRAPHS) return emptyList()

            val metadataTranslationsByKey = parseMetadataTranslations(root)
            val originals = mutableListOf<KeyedLine>()
            val translations = mutableListOf<KeyedLine>()
            val romanizations = mutableListOf<KeyedLine>()
            var totalCells = 0

            paragraphs.forEach { paragraph ->
                val start = paragraph.attr("begin")?.let(::parseTime)
                val end = paragraph.attr("end")?.let(::parseTime)
                if (start == null && end == null) return@forEach

                val fallbackStart = start ?: 0
                val fallbackEnd = end ?: fallbackStart
                val parsed = parseParagraphText(paragraph, fallbackStart, fallbackEnd)
                totalCells += parsed.cells.size
                if (totalCells > MAX_CELLS) return emptyList()

                val key = paragraph.attr("key", NS_ITUNES_INTERNAL)
                    ?: paragraph.attr("key", NS_ITUNES_LEGACY)
                    ?: paragraph.attr("key")
                val agentId = paragraph.attr("agent", NS_TTM) ?: paragraph.attr("agent")
                val role = paragraph.attr("role", NS_TTM)
                val lineEnd = end
                    ?: parsed.cells.lastOrNull { it.timed }?.endTimeMs
                    ?: fallbackEnd

                when (role) {
                    "x-translation" -> textOnlyLine(parsed.visibleText, fallbackStart, lineEnd, key)?.let {
                        translations += KeyedLine(it, key)
                    }
                    "x-romanization" -> textOnlyLine(parsed.visibleText, fallbackStart, lineEnd, key)?.let {
                        romanizations += KeyedLine(it, key)
                    }
                    "x-bg" -> Unit
                    else -> {
                        lineFromCells(fallbackStart, lineEnd, parsed.cells)?.let { line ->
                            originals += KeyedLine(line, key, agentId)
                        }
                        textOnlyLine(parsed.translationText, fallbackStart, lineEnd, key)?.let {
                            translations += KeyedLine(it, key)
                        }
                        textOnlyLine(parsed.romanizationText, fallbackStart, lineEnd, key)?.let {
                            romanizations += KeyedLine(it, key)
                        }
                    }
                }
            }

            val translationByKey = (metadataTranslationsByKey + translations)
                .mapNotNull { keyed -> keyed.key?.let { it to keyed.line.mainText } }
                .toMap()
            val translationByStart = translations.associate { it.line.timeMs to it.line.mainText }
            val romanizationByKey = romanizations
                .mapNotNull { keyed -> keyed.key?.let { it to keyed.line.mainText } }
                .toMap()
            val romanizationByStart = romanizations.associate { it.line.timeMs to it.line.mainText }

            originals.withDuetSides()
                .map { keyed ->
                    val line = keyed.line
                    val translation = keyed.key?.let { translationByKey[it] } ?: translationByStart[line.timeMs]
                    val romanization = keyed.key?.let { romanizationByKey[it] } ?: romanizationByStart[line.timeMs]
                    line.withCompanionText(translation = translation, romanization = romanization)
                }
                .filter { it.mainText.isNotBlank() }
                .sortedBy { it.timeMs }
        }.onFailure { error ->
            DiagnosticLog.event(
                LYRICS_TRACE,
                "ttml-parser failed error=${error.javaClass.simpleName}:${error.message.orEmpty().take(160)}",
            )
        }.getOrDefault(emptyList())
    }

    private data class KeyedLine(
        val line: LyricLine,
        val key: String?,
        val agentId: String? = null,
    )

    private data class ParsedParagraph(
        val visibleText: String,
        val translationText: String,
        val romanizationText: String,
        val cells: List<LyricCell>,
    )

    private fun List<KeyedLine>.withDuetSides(): List<KeyedLine> {
        val sideByAgent = resolveDuetSideByAgent()
        if (sideByAgent.isEmpty()) return this

        return map { keyed ->
            val side = keyed.agentId?.let { sideByAgent[it] } ?: LyricLineSide.Center
            keyed.copy(line = keyed.line.copy(side = side))
        }
    }

    private fun List<KeyedLine>.resolveDuetSideByAgent(): Map<String, LyricLineSide> {
        if (size < 2) return emptyMap()

        val overlappingAgentIds = linkedSetOf<String>()
        for (firstIndex in 0 until lastIndex) {
            val first = this[firstIndex]
            val firstAgentId = first.agentId?.takeIf { it.isNotBlank() } ?: continue
            for (secondIndex in firstIndex + 1 until size) {
                val second = this[secondIndex]
                val secondAgentId = second.agentId?.takeIf { it.isNotBlank() } ?: continue
                if (firstAgentId == secondAgentId || !first.line.overlaps(second.line)) continue

                overlappingAgentIds += firstAgentId
                overlappingAgentIds += secondAgentId
            }
        }
        if (overlappingAgentIds.size < 2) return emptyMap()

        val orderedAgentIds = linkedSetOf<String>()
        asSequence()
            .mapNotNull { it.agentId?.takeIf { agentId -> agentId.isNotBlank() } }
            .forEach { orderedAgentIds += it }

        return orderedAgentIds.mapIndexed { index, agentId ->
            agentId to if (index % 2 == 0) LyricLineSide.Start else LyricLineSide.End
        }.toMap()
    }

    private fun LyricLine.overlaps(other: LyricLine): Boolean {
        return timeMs < (other.endTimeMs ?: other.timeMs) &&
            other.timeMs < (endTimeMs ?: timeMs)
    }

    private fun parseParagraphText(paragraph: Element, fallbackStart: Int, fallbackEnd: Int): ParsedParagraph {
        val cells = mutableListOf<LyricCell>()
        val original = StringBuilder()
        val translation = StringBuilder()
        val romanization = StringBuilder()

        fun appendVisibleText(node: Node, target: StringBuilder) {
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> target.append(normalizeTtmlText(node.nodeValue.orEmpty()))
                Node.ELEMENT_NODE -> node.childNodesList().forEach { appendVisibleText(it, target) }
            }
        }

        fun appendOriginalText(rawText: String) {
            val normalized = normalizeTtmlText(rawText)
            if (!normalized.isFormattingWhitespaceFrom(rawText)) {
                original.append(normalized)
            }
        }

        fun flushOriginalBeforeTimed(timedStart: Int) {
            val pending = normalizeTtmlText(original.toString())
            if (!pending.isFormattingWhitespaceFrom(original.toString())) {
                cells += LyricCell(fallbackStart, timedStart.coerceAtLeast(fallbackStart), pending, timed = false)
            }
            original.clear()
        }

        fun appendTimedCell(element: Element, rawText: String): Boolean {
            val start = element.attr("begin")?.let(::parseTime) ?: return false
            val normalized = normalizeTtmlText(rawText)
            if (normalized.isFormattingWhitespaceFrom(rawText)) return true
            flushOriginalBeforeTimed(start)
            val end = (element.attr("end")?.let(::parseTime) ?: fallbackEnd).coerceAtLeast(start)
            cells += LyricCell(start, end, normalized)
            return true
        }

        fun visitOriginalNode(node: Node) {
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> appendOriginalText(node.nodeValue.orEmpty())
                Node.ELEMENT_NODE -> {
                    val element = node as Element
                    val role = element.attr("role", NS_TTM)
                    val visible = StringBuilder().also { appendVisibleText(element, it) }.toString()
                    when (role) {
                        "x-translation" -> translation.append(normalizeTtmlText(visible, trimEdges = true))
                        "x-romanization" -> romanization.append(normalizeTtmlText(visible, trimEdges = true))
                        "x-bg" -> Unit
                        else -> {
                            if (!appendTimedCell(element, visible)) {
                                val before = cells.size
                                element.childNodesList().forEach(::visitOriginalNode)
                                if (cells.size == before && element.attr("begin") == null) {
                                    // Child traversal already appended text nodes; no extra work needed.
                                }
                            }
                        }
                    }
                }
            }
        }

        paragraph.childNodesList().forEach(::visitOriginalNode)

        if (cells.isNotEmpty() && original.isNotEmpty()) {
            val pending = normalizeTtmlText(original.toString())
            if (!pending.isFormattingWhitespaceFrom(original.toString())) {
                val anchor = cells.lastOrNull()?.endTimeMs ?: fallbackEnd
                cells += LyricCell(anchor, anchor, pending, timed = false)
            }
            original.clear()
        }

        val finalOriginal = if (cells.isNotEmpty()) {
            cells.joinToString(separator = "") { it.text }
        } else {
            normalizeTtmlText(original.toString(), trimEdges = true)
        }
        val finalCells = cells.ifEmpty {
            if (finalOriginal.isBlank()) {
                emptyList()
            } else {
                listOf(LyricCell(fallbackStart, fallbackEnd.coerceAtLeast(fallbackStart), finalOriginal, timed = false))
            }
        }
        return ParsedParagraph(
            visibleText = finalOriginal,
            translationText = normalizeTtmlText(translation.toString(), trimEdges = true),
            romanizationText = normalizeTtmlText(romanization.toString(), trimEdges = true),
            cells = finalCells,
        )
    }

    private fun parseMetadataTranslations(root: Element): List<KeyedLine> =
        root.elementsByLocalName("translation").flatMap { translation ->
            translation.childElementsByLocalName("text").mapNotNull { text ->
                val key = text.attr("for") ?: return@mapNotNull null
                val value = normalizeTtmlText(text.textContent.orEmpty(), trimEdges = true)
                textOnlyLine(value, 0, 0, key)?.let { KeyedLine(it, key) }
            }
        }

    private fun lineFromCells(startTimeMs: Int, endTimeMs: Int, cells: List<LyricCell>): LyricLine? {
        val cleanCells = cells.filter { it.text.isNotEmpty() }
        val mainText = MetadataTextFix.normalize(cleanCells.joinToString(separator = "") { it.text }).trim()
        if (mainText.isEmpty()) return null
        val safeEnd = endTimeMs.coerceAtLeast(startTimeMs)
        val boundedCells = cleanCells.mapIndexed { index, cell ->
            val end = if (index == cleanCells.lastIndex) {
                cell.endTimeMs.coerceAtMost(safeEnd).coerceAtLeast(cell.startTimeMs)
            } else {
                cell.endTimeMs.coerceAtLeast(cell.startTimeMs)
            }
            cell.copy(endTimeMs = end)
        }
        val cues = boundedCells
            .filter { it.timed && it.text.isNotEmpty() }
            .map { LyricCue(it.startTimeMs, it.text) }
        return LyricLine(
            timeMs = startTimeMs,
            text = mainText,
            cues = cues,
            endTimeMs = safeEnd,
            cells = boundedCells,
        )
    }

    private fun textOnlyLine(text: String, startTimeMs: Int, endTimeMs: Int, key: String?): LyricLine? {
        val normalized = MetadataTextFix.normalize(text).trim()
        if (normalized.isEmpty()) return null
        val safeEnd = endTimeMs.coerceAtLeast(startTimeMs)
        return LyricLine(
            timeMs = startTimeMs,
            text = normalized,
            endTimeMs = safeEnd,
            cells = listOf(LyricCell(startTimeMs, safeEnd, normalized, timed = false)),
        )
    }

    private fun LyricLine.withCompanionText(translation: String?, romanization: String?): LyricLine {
        val cleanTranslation = translation?.let { MetadataTextFix.normalize(it).trim() }?.takeIf { it.isNotBlank() }
        val cleanRomanization = romanization?.let { MetadataTextFix.normalize(it).trim() }?.takeIf { it.isNotBlank() }
        return copy(
            text = displayText(mainText, cleanRomanization, cleanTranslation),
            subText = cleanTranslation,
            romanizationText = cleanRomanization,
        )
    }

    private fun displayText(mainText: String, romanizationText: String?, subText: String?): String =
        buildList {
            add(mainText)
            romanizationText?.takeIf { it.isNotBlank() }?.let(::add)
            subText?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString("\n")

    private fun parseTime(raw: String?): Int? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.endsWith("ms", ignoreCase = true)) {
            return value.dropLast(2).toDoubleOrNull()?.roundToInt()
        }
        if (value.endsWith("s", ignoreCase = true)) {
            return value.dropLast(1).toDoubleOrNull()?.let { (it * 1_000).roundToInt() }
        }
        val parts = value.split(':')
        val seconds = parts.lastOrNull()?.toDoubleOrNull() ?: return null
        val millis = when (parts.size) {
            1 -> seconds * 1_000
            2 -> (parts[0].toLongOrNull() ?: return null) * 60_000 + seconds * 1_000
            3 -> (parts[0].toLongOrNull() ?: return null) * 3_600_000 +
                (parts[1].toLongOrNull() ?: return null) * 60_000 + seconds * 1_000
            else -> return null
        }
        return millis.roundToInt()
    }

    private fun Element.attr(localName: String, namespace: String? = null): String? {
        if (namespace != null) {
            getAttributeNS(namespace, localName).takeIf { it.isNotBlank() }?.let { return it }
        }
        getAttribute(localName).takeIf { it.isNotBlank() }?.let { return it }
        for (index in 0 until attributes.length) {
            val attr = attributes.item(index)
            if (attr.localName == localName || attr.nodeName.endsWith(":$localName")) {
                return attr.nodeValue
            }
        }
        return null
    }

    private fun Element.elementsByLocalName(localName: String): List<Element> {
        val result = mutableListOf<Element>()
        fun visit(node: Node) {
            if (node is Element && node.localName == localName) result += node
            node.childNodesList().forEach(::visit)
        }
        visit(this)
        return result
    }

    private fun Element.childElementsByLocalName(localName: String): List<Element> =
        childNodesList().filterIsInstance<Element>().filter { it.localName == localName }

    private fun Node.childNodesList(): List<Node> =
        (0 until childNodes.length).map { childNodes.item(it) }

    private fun normalizeTtmlText(text: String, trimEdges: Boolean = false): String {
        if (!text.contains('\n') && !text.contains('\r')) {
            return if (trimEdges) text.trim() else text
        }
        val collapsed = text.replace(Regex("\\s+"), " ")
        return if (trimEdges) collapsed.trim() else collapsed
    }

    private fun String.isFormattingWhitespaceFrom(rawText: String): Boolean =
        isEmpty() || (isBlank() && (rawText.contains('\n') || rawText.contains('\r')))
}

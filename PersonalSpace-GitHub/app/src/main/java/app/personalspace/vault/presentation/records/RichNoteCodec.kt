package app.personalspace.vault.presentation.records

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Immutable
import app.personalspace.vault.core.common.IdGenerator
import app.personalspace.vault.core.designsystem.BauhausColors
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val RICH_METADATA_DELIMITER = "\n\u2063PSRICH1:"

/**
 * Marks a rich payload this build could not parse. The original payload is preserved verbatim between
 * these markers so no character of a damaged note is ever lost.
 */
const val DAMAGED_PAYLOAD_MARKER = "[PSRICH-DAMAGED]"
const val DAMAGED_PAYLOAD_END = "[/PSRICH-DAMAGED]"
const val DAMAGE_NONE = "NONE"
const val DAMAGE_UNREADABLE_BLOCK_PAYLOAD = "UNREADABLE_BLOCK_PAYLOAD"
const val DAMAGE_UNREADABLE_SPAN_METADATA = "UNREADABLE_SPAN_METADATA"
private const val RICH_BLOCKS_DELIMITER = "\n\u2063PSRICH2:"

@Serializable
enum class RichSpanKind {
    BOLD,
    ITALIC,
    UNDERLINE,
    STRIKETHROUGH,
    CODE,
    QUOTE,
    H1,
    H2,
    H3,
    TEXT_COLOR,
    HIGHLIGHT,
    ALIGN
}

@Serializable
data class RichSpan(
    val start: Int,
    val end: Int,
    val kind: RichSpanKind,
    val colorHex: String? = null
)

@Immutable
data class RichNoteDocument(
    val plainText: String,
    val spans: List<RichSpan>
)

@Serializable
enum class BlockAlignment {
    LEFT,
    CENTER,
    RIGHT;

    companion object {
        fun fromId(id: String?): BlockAlignment =
            entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: LEFT
    }
}

fun paragraphAlignmentAt(
    text: String,
    position: Int,
    spans: List<RichSpan>
): BlockAlignment {
    if (text.isEmpty()) return BlockAlignment.LEFT
    val (lineStart, lineEnd) = lineBoundsAt(text, position)
    if (lineEnd <= lineStart) return BlockAlignment.LEFT
    return spans.lastOrNull { span ->
        span.kind == RichSpanKind.ALIGN &&
            span.start <= lineStart &&
            span.end >= lineEnd
    }?.let { span -> BlockAlignment.fromId(span.colorHex) } ?: BlockAlignment.LEFT
}

fun lineBoundsAt(text: String, position: Int): Pair<Int, Int> {
    val safe = position.coerceIn(0, text.length)
    val start = text.lastIndexOf('\n', (safe - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val end = text.indexOf('\n', safe).let { if (it < 0) text.length else it }
    return start to end
}

fun applyParagraphAlignment(
    text: String,
    spans: List<RichSpan>,
    position: Int,
    alignment: BlockAlignment
): List<RichSpan> {
    if (text.isEmpty()) return spans
    val (lineStart, lineEnd) = lineBoundsAt(text, position)
    if (lineEnd <= lineStart) return spans
    val withoutThisLine = spans.filterNot { span ->
        span.kind == RichSpanKind.ALIGN && span.start < lineEnd && span.end > lineStart
    }
    val merged = if (alignment == BlockAlignment.LEFT) {
        withoutThisLine
    } else {
        withoutThisLine + RichSpan(
            start = lineStart,
            end = lineEnd,
            kind = RichSpanKind.ALIGN,
            colorHex = alignment.name
        )
    }
    return RichNoteCodec.normalizeSpans(merged, text.length)
}

@Serializable
data class SerializedNoteBlock(
    val type: String,
    val id: String = "",
    val text: String = "",
    val spans: List<RichSpan> = emptyList(),
    val imagePath: String = "",
    val widthFraction: Float = 1.0f,
    val alignment: String = "LEFT"
)

@Immutable
sealed interface RichNoteBlock {
    val id: String

    data class Text(
        override val id: String = IdGenerator.newUuid(),
        val text: String = "",
        val spans: List<RichSpan> = emptyList(),
        val alignment: BlockAlignment = BlockAlignment.LEFT
    ) : RichNoteBlock

    data class Image(
        override val id: String = IdGenerator.newUuid(),
        val imagePath: String,
        val widthFraction: Float = 1.0f,
        val alignment: BlockAlignment = BlockAlignment.LEFT
    ) : RichNoteBlock
}

data class HighlighterSwatch(
    val name: String,
    val hex: String,
    val color: Color
)

data class InkColorSwatch(
    val name: String,
    val hex: String,
    val color: Color
)

val NoteHighlighterPalette = listOf(
    HighlighterSwatch("Yellow", "#FFF176", Color(0xFFFFF176)),
    HighlighterSwatch("Mint", "#B9F6CA", Color(0xFFB9F6CA)),
    HighlighterSwatch("Sky", "#B3E5FC", Color(0xFFB3E5FC)),
    HighlighterSwatch("Coral", "#FFCDD2", Color(0xFFFFCDD2)),
    HighlighterSwatch("Lavender", "#E1BEE7", Color(0xFFE1BEE7)),
    HighlighterSwatch("Peach", "#FFE0B2", Color(0xFFFFE0B2))
)

val NoteExtendedHighlighterPalette = listOf(
    HighlighterSwatch("Yellow", "#FFF176", Color(0xFFFFF176)),
    HighlighterSwatch("Warm Gold", "#FFE082", Color(0xFFFFE082)),
    HighlighterSwatch("Lemon", "#F4FF81", Color(0xFFF4FF81)),
    HighlighterSwatch("Lime", "#CCFF90", Color(0xFFCCFF90)),
    HighlighterSwatch("Mint", "#B9F6CA", Color(0xFFB9F6CA)),
    HighlighterSwatch("Sage", "#C8E6C9", Color(0xFFC8E6C9)),
    HighlighterSwatch("Aqua", "#A7FFEB", Color(0xFFA7FFEB)),
    HighlighterSwatch("Cyan", "#84FFFF", Color(0xFF84FFFF)),
    HighlighterSwatch("Sky", "#B3E5FC", Color(0xFFB3E5FC)),
    HighlighterSwatch("Periwinkle", "#C5CAE9", Color(0xFFC5CAE9)),
    HighlighterSwatch("Lavender", "#E1BEE7", Color(0xFFE1BEE7)),
    HighlighterSwatch("Orchid", "#F8BBD0", Color(0xFFF8BBD0)),
    HighlighterSwatch("Coral", "#FFCDD2", Color(0xFFFFCDD2)),
    HighlighterSwatch("Rose", "#FFAB91", Color(0xFFFFAB91)),
    HighlighterSwatch("Peach", "#FFE0B2", Color(0xFFFFE0B2)),
    HighlighterSwatch("Sand", "#EFEBE9", Color(0xFFEFEBE9)),
    HighlighterSwatch("Soft Red", "#FF8A80", Color(0xFFFF8A80)),
    HighlighterSwatch("Soft Blue", "#82B1FF", Color(0xFF82B1FF))
)

val NoteInkColorPalette = listOf(
    InkColorSwatch("Black", "#101010", Color(0xFF101010)),
    InkColorSwatch("Red", "#E94528", Color(0xFFE94528)),
    InkColorSwatch("Blue", "#25559A", Color(0xFF25559A)),
    InkColorSwatch("Green", "#1B7F5A", Color(0xFF1B7F5A)),
    InkColorSwatch("Purple", "#6B3FA0", Color(0xFF6B3FA0)),
    InkColorSwatch("Orange", "#C85A17", Color(0xFFC85A17))
)

data class ActiveTypingStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val code: Boolean = false,
    val quote: Boolean = false,
    val heading: RichSpanKind? = null, // H1, H2, H3, or null for Normal
    val textColorHex: String? = null,
    val highlightHex: String? = null
)

object RichNoteCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun extractPlainText(rawStored: String): String {
        if (rawStored.isEmpty()) return ""
        val idx2 = rawStored.indexOf(RICH_BLOCKS_DELIMITER)
        if (idx2 >= 0) {
            return rawStored.substring(0, idx2)
        }
        val idx1 = rawStored.indexOf(RICH_METADATA_DELIMITER)
        val rawBody = if (idx1 >= 0) rawStored.substring(0, idx1) else rawStored
        return if (idx1 >= 0) rawBody else decodeLegacyMarkdown(rawBody).plainText
    }

    fun hasMeaningfulContent(rawStored: String): Boolean {
        val blocks = decodeBlocks(rawStored)
        return blocks.any { blk ->
            when (blk) {
                is RichNoteBlock.Text -> blk.text.isNotBlank()
                is RichNoteBlock.Image -> blk.imagePath.isNotBlank()
            }
        }
    }

    fun encode(plainText: String, spans: List<RichSpan>): String {
        val cleaned = normalizeSpans(spans, plainText.length)
        if (cleaned.isEmpty()) return plainText
        val payload = runCatching { json.encodeToString(cleaned) }.getOrDefault("")
        return if (payload.isBlank() || payload == "[]") {
            plainText
        } else {
            plainText + RICH_METADATA_DELIMITER + payload
        }
    }

    fun encodeBlocks(blocks: List<RichNoteBlock>): String {
        if (blocks.isEmpty()) return ""
        val hasImages = blocks.any { it is RichNoteBlock.Image }
        val flatTextOnly = blocks.all { it is RichNoteBlock.Text }
        val anyBlockAligned = blocks.any { block ->
            when (block) {
                is RichNoteBlock.Text -> block.alignment != BlockAlignment.LEFT
                is RichNoteBlock.Image -> block.alignment != BlockAlignment.LEFT
            }
        }
        val anyParagraphAligned = blocks.any { block ->
            block is RichNoteBlock.Text && block.spans.any { it.kind == RichSpanKind.ALIGN }
        }
        if (!hasImages && blocks.size == 1 && blocks[0] is RichNoteBlock.Text && !anyBlockAligned && !anyParagraphAligned) {
            val single = blocks[0] as RichNoteBlock.Text
            return encode(single.text, single.spans)
        }
        if (!hasImages && flatTextOnly && !anyBlockAligned && !anyParagraphAligned) {
            val mergedDoc = flattenBlocksToDocument(blocks)
            return encode(mergedDoc.plainText, mergedDoc.spans)
        }

        val plainSummary = blocks
            .filterIsInstance<RichNoteBlock.Text>()
            .map { it.text }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

        val serialized = blocks.map { blk ->
            when (blk) {
                is RichNoteBlock.Text -> SerializedNoteBlock(
                    type = "TEXT",
                    id = blk.id,
                    text = blk.text,
                    spans = normalizeSpans(blk.spans, blk.text.length),
                    alignment = blk.alignment.name
                )
                is RichNoteBlock.Image -> SerializedNoteBlock(
                    type = "IMAGE",
                    id = blk.id,
                    imagePath = blk.imagePath,
                    widthFraction = blk.widthFraction.coerceIn(0.25f, 1.0f),
                    alignment = blk.alignment.name
                )
            }
        }
        val payload = runCatching { json.encodeToString(serialized) }.getOrDefault("[]")
        return plainSummary + RICH_BLOCKS_DELIMITER + payload
    }

    fun decodeBlocks(rawStored: String): List<RichNoteBlock> {
        if (rawStored.isEmpty()) {
            return listOf(RichNoteBlock.Text(text = "", spans = emptyList()))
        }
        val idx2 = rawStored.indexOf(RICH_BLOCKS_DELIMITER)
        if (idx2 >= 0) {
            val metaStr = rawStored.substring(idx2 + RICH_BLOCKS_DELIMITER.length).trim()
            val attempted = runCatching { json.decodeFromString<List<SerializedNoteBlock>>(metaStr) }
            val parsed = attempted.getOrDefault(emptyList())
            if (parsed.isEmpty()) {
                // Three cases land here and they are NOT the same thing:
                //   * the payload parsed but listed no blocks  -> a clean note with no rich content;
                //   * the payload is unparseable / truncated    -> damage, preserve it verbatim;
                //   * the payload is blank                       -> damage, nothing to preserve.
                // Falling through to decode() in all three (the original behaviour) recursed forever
                // and killed the app with StackOverflowError on opening the note.
                return if (attempted.isSuccess) {
                    listOf(RichNoteBlock.Text(text = rawStored.substring(0, idx2), spans = emptyList()))
                } else {
                    listOf(recoveredTextBlock(rawStored, idx2))
                }
            }
            if (true) {
                val restored = parsed.map { sb ->
                    if (sb.type == "IMAGE" && sb.imagePath.isNotBlank()) {
                        RichNoteBlock.Image(
                            id = sb.id.ifBlank { IdGenerator.newUuid() },
                            imagePath = sb.imagePath,
                            widthFraction = sb.widthFraction.coerceIn(0.25f, 1.0f),
                            alignment = BlockAlignment.fromId(sb.alignment)
                        )
                    } else {
                        val legacyAlignment = BlockAlignment.fromId(sb.alignment)
                        RichNoteBlock.Text(
                            id = sb.id.ifBlank { IdGenerator.newUuid() },
                            text = sb.text,
                            spans = if (legacyAlignment == BlockAlignment.LEFT) {
                                normalizeSpans(sb.spans, sb.text.length)
                            } else {
                                val lineSpans = sb.spans.toMutableList()
                                var cursor = 0
                                sb.text.split("\n").forEach { line ->
                                    if (line.isNotEmpty()) {
                                        lineSpans.add(
                                            RichSpan(cursor, cursor + line.length, RichSpanKind.ALIGN, legacyAlignment.name)
                                        )
                                    }
                                    cursor += line.length + 1
                                }
                                normalizeSpans(lineSpans, sb.text.length)
                            },
                            alignment = BlockAlignment.LEFT
                        )
                    }
                }
                return ensureSurroundingTextBlocks(restored)
            }
        }
        val single = decode(rawStored)
        return listOf(RichNoteBlock.Text(text = single.plainText, spans = single.spans))
    }

    /**
     * Builds the block list for a note whose rich-block payload could not be parsed.
     *
     * LOSSLESS BY CONSTRUCTION: nothing is thrown away. The visible summary that precedes the
     * delimiter is the user's text, and the damaged payload is appended verbatim inside
     * [DAMAGED_PAYLOAD_MARKER] … `[/PSRICH-DAMAGED]` so that
     *   * opening the note never loses a single character,
     *   * editing and re-saving the note preserves the original payload bytes rather than writing the
     *     truncated version back to disk, and
     *   * a future recovery/export can pull the original JSON out and repair it.
     *
     * [inspectDamage] reports exactly this condition so the UI/backup report can tell the user.
     */
    private fun recoveredTextBlock(rawStored: String, delimiterIndex: Int): RichNoteBlock.Text {
        val visible = rawStored.substring(0, delimiterIndex)
        val payload = rawStored.substring(delimiterIndex + RICH_BLOCKS_DELIMITER.length)
        val preserved = if (payload.isBlank()) {
            visible
        } else buildString {
            if (visible.isNotEmpty()) {
                append(visible)
                append('\n')
            }
            append(DAMAGED_PAYLOAD_MARKER)
            append(payload)
            append(DAMAGED_PAYLOAD_END)
        }
        return RichNoteBlock.Text(text = preserved, spans = emptyList())
    }

    /**
     * Reports whether [rawStored] carries rich content that this codec cannot read back.
     *
     * This is the question the user and the backup report actually need answered: "is anything in this
     * note unrecoverable?" — not "did the parser throw?". A note is [Damage.NONE] when every byte can be
     * represented as blocks; [Damage.UNREADABLE_BLOCK_PAYLOAD] when the block JSON is unusable but the
     * original bytes are preserved verbatim; [Damage.UNREADABLE_SPAN_METADATA] when only the older span
     * metadata is unusable (again preserved verbatim).
     */
    @Serializable
    data class DamageReport(
        val damage: String,
        val detail: String,
        val visibleTextLength: Int,
        val preservedPayloadLength: Int
    ) {
        val isDamaged: Boolean get() = damage != DAMAGE_NONE
    }

    fun inspectDamage(rawStored: String): DamageReport {
        if (rawStored.isEmpty()) {
            return DamageReport(DAMAGE_NONE, "empty note", 0, 0)
        }
        val blockIndex = rawStored.indexOf(RICH_BLOCKS_DELIMITER)
        if (blockIndex >= 0) {
            val payload = rawStored.substring(blockIndex + RICH_BLOCKS_DELIMITER.length)
            val parsed = runCatching {
                json.decodeFromString<List<SerializedNoteBlock>>(payload.trim())
            }.getOrNull()
            return when {
                parsed != null -> DamageReport(DAMAGE_NONE, "ok", blockIndex, 0)
                payload.isBlank() -> DamageReport(
                    DAMAGE_UNREADABLE_BLOCK_PAYLOAD,
                    "the rich-block payload is missing",
                    blockIndex,
                    0
                )
                else -> DamageReport(
                    DAMAGE_UNREADABLE_BLOCK_PAYLOAD,
                    "the rich-block payload is not valid block JSON",
                    blockIndex,
                    payload.length
                )
            }
        }
        val metaIndex = rawStored.indexOf(RICH_METADATA_DELIMITER)
        if (metaIndex >= 0) {
            val payload = rawStored.substring(metaIndex + RICH_METADATA_DELIMITER.length)
            val parsed = runCatching {
                json.decodeFromString<List<RichSpan>>(payload.trim())
            }.getOrNull()
            return if (parsed == null) {
                DamageReport(
                    DAMAGE_UNREADABLE_SPAN_METADATA,
                    "the span metadata is not valid JSON",
                    metaIndex,
                    payload.length
                )
            } else {
                DamageReport(DAMAGE_NONE, "ok", metaIndex, 0)
            }
        }
        return DamageReport(DAMAGE_NONE, "ok", rawStored.length, 0)
    }

    /** The exact string this codec would store if the damaged note were re-saved untouched. */
    fun encodeRecoveredRaw(rawStored: String): String = rawStored

    fun ensureSurroundingTextBlocks(blocks: List<RichNoteBlock>): List<RichNoteBlock> {
        if (blocks.isEmpty()) return listOf(RichNoteBlock.Text())
        val out = ArrayList<RichNoteBlock>(blocks.size + 2)
        if (blocks.first() is RichNoteBlock.Image) {
            out.add(RichNoteBlock.Text())
        }
        for (b in blocks) {
            if (b is RichNoteBlock.Image && out.lastOrNull() is RichNoteBlock.Image) {
                out.add(RichNoteBlock.Text())
            }
            out.add(b)
        }
        if (out.last() is RichNoteBlock.Image) {
            out.add(RichNoteBlock.Text())
        }
        return out
    }

    private fun flattenBlocksToDocument(blocks: List<RichNoteBlock>): RichNoteDocument {
        val sb = StringBuilder()
        val allSpans = ArrayList<RichSpan>()
        val textBlocks = blocks.filterIsInstance<RichNoteBlock.Text>()
        textBlocks.forEachIndexed { idx, tb ->
            val offset = sb.length
            sb.append(tb.text)
            tb.spans.forEach { sp ->
                allSpans.add(sp.copy(start = sp.start + offset, end = sp.end + offset))
            }
            if (idx < textBlocks.lastIndex) {
                sb.append('\n')
            }
        }
        val str = sb.toString()
        return RichNoteDocument(str, normalizeSpans(allSpans, str.length))
    }

    fun decode(rawStored: String): RichNoteDocument {
        if (rawStored.isEmpty()) return RichNoteDocument("", emptyList())
        val idx2 = rawStored.indexOf(RICH_BLOCKS_DELIMITER)
        if (idx2 >= 0) {
            // P6: decodeBlocks is now total (it always returns blocks for a block-delimited string),
            // so this cannot bounce back into decode(). The recursion guard is the explicit `parsed`
            // branch in decodeBlocks.
            return flattenBlocksToDocument(decodeBlocks(rawStored))
        }
        val idx = rawStored.indexOf(RICH_METADATA_DELIMITER)
        if (idx >= 0) {
            val text = rawStored.substring(0, idx)
            val metaStr = rawStored.substring(idx + RICH_METADATA_DELIMITER.length).trim()
            val parsed = runCatching { json.decodeFromString<List<RichSpan>>(metaStr) }
                .getOrDefault(emptyList())
            return RichNoteDocument(
                plainText = text,
                spans = normalizeSpans(parsed, text.length)
            )
        }
        return decodeLegacyMarkdown(rawStored)
    }

    private fun decodeLegacyMarkdown(raw: String): RichNoteDocument {
        if (!raw.contains("**") && !raw.contains("_") && !raw.contains("# ") && !raw.contains("- ")) {
            return RichNoteDocument(raw, emptyList())
        }
        val outText = StringBuilder()
        val spans = ArrayList<RichSpan>()
        val lines = raw.lines()
        lines.forEachIndexed { lineIndex, rawLine ->
            val trimmedStart = rawLine.trimStart()
            var lineContent = rawLine
            var lineHeading: RichSpanKind? = null

            when {
                trimmedStart.startsWith("### ") -> {
                    lineContent = trimmedStart.removePrefix("### ")
                    lineHeading = RichSpanKind.H3
                }
                trimmedStart.startsWith("## ") -> {
                    lineContent = trimmedStart.removePrefix("## ")
                    lineHeading = RichSpanKind.H2
                }
                trimmedStart.startsWith("# ") -> {
                    lineContent = trimmedStart.removePrefix("# ")
                    lineHeading = RichSpanKind.H1
                }
                trimmedStart.startsWith("- ") || trimmedStart.startsWith("* ") -> {
                    lineContent = "• " + trimmedStart.drop(2)
                }
            }

            val lineStartOut = outText.length
            var i = 0
            while (i < lineContent.length) {
                if (lineContent.startsWith("**", i)) {
                    val end = lineContent.indexOf("**", i + 2)
                    if (end != -1 && end > i + 2) {
                        val s = outText.length
                        outText.append(lineContent.substring(i + 2, end))
                        spans.add(RichSpan(s, outText.length, RichSpanKind.BOLD))
                        i = end + 2
                        continue
                    }
                }
                if (lineContent[i] == '_') {
                    val end = lineContent.indexOf('_', i + 1)
                    if (end != -1 && end > i + 1) {
                        val s = outText.length
                        outText.append(lineContent.substring(i + 1, end))
                        spans.add(RichSpan(s, outText.length, RichSpanKind.ITALIC))
                        i = end + 1
                        continue
                    }
                }
                outText.append(lineContent[i])
                i++
            }
            val lineEndOut = outText.length
            if (lineHeading != null && lineEndOut > lineStartOut) {
                spans.add(RichSpan(lineStartOut, lineEndOut, lineHeading))
            }
            if (lineIndex < lines.lastIndex) {
                outText.append('\n')
            }
        }
        val finalStr = outText.toString()
        return RichNoteDocument(finalStr, normalizeSpans(spans, finalStr.length))
    }

    fun normalizeSpans(spans: List<RichSpan>, textLength: Int): List<RichSpan> {
        if (spans.isEmpty() || textLength <= 0) return emptyList()
        val clamped = spans.mapNotNull { sp ->
            val s = sp.start.coerceIn(0, textLength)
            val e = sp.end.coerceIn(0, textLength)
            if (s < e) sp.copy(start = s, end = e) else null
        }
        if (clamped.isEmpty()) return emptyList()

        val merged = ArrayList<RichSpan>(clamped.size)
        clamped.groupBy { it.kind to it.colorHex }.forEach { (_, group) ->
            val sorted = group.sortedWith(compareBy<RichSpan> { it.start }.thenBy { it.end })
            var cur = sorted[0]
            for (idx in 1 until sorted.size) {
                val next = sorted[idx]
                if (next.start <= cur.end) {
                    cur = cur.copy(end = maxOf(cur.end, next.end))
                } else {
                    merged.add(cur)
                    cur = next
                }
            }
            merged.add(cur)
        }
        return merged.sortedWith(compareBy<RichSpan> { it.start }.thenBy { it.end })
    }

    fun parseHexColorOrNull(hex: String?): Color? {
        if (hex.isNullOrBlank()) return null
        return runCatching {
            val clean = hex.removePrefix("#")
            val argb = when (clean.length) {
                6 -> (0xFF000000L or clean.toLong(16)).toInt()
                8 -> clean.toLong(16).toInt()
                else -> return null
            }
            Color(argb)
        }.getOrNull()
    }

    fun colorToHex(color: Color): String {
        val r = (color.red * 255f).toInt().coerceIn(0, 255)
        val g = (color.green * 255f).toInt().coerceIn(0, 255)
        val b = (color.blue * 255f).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(r, g, b)
    }

    fun hsvToHighlightColor(hueDegrees: Float): Color {
        val hsv = floatArrayOf(hueDegrees.coerceIn(0f, 360f), 0.42f, 0.99f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /**
     * Builds styled [AnnotatedString]. When [paintHighlightInline] is false (used whenever
     * [drawBalancedHighlightSpans] paints behind the text layout), `HIGHLIGHT` spans omit
     * Skia's tall `ascent` background so top & bottom highlight padding match symmetrically.
     */
    fun buildAnnotatedString(
        text: String,
        spans: List<RichSpan>,
        paintHighlightInline: Boolean = false
    ): AnnotatedString {
        if (text.isEmpty()) return AnnotatedString("")
        val builder = AnnotatedString.Builder(text)
        val ordered = spans.sortedBy {
            when (it.kind) {
                RichSpanKind.HIGHLIGHT -> 0
                RichSpanKind.QUOTE -> 1
                RichSpanKind.CODE -> 2
                RichSpanKind.H1, RichSpanKind.H2, RichSpanKind.H3 -> 3
                else -> 4
            }
        }
        for (sp in ordered) {
            val s = sp.start.coerceIn(0, text.length)
            val e = sp.end.coerceIn(0, text.length)
            if (s >= e) continue
            val style: SpanStyle? = when (sp.kind) {
                RichSpanKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                RichSpanKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                RichSpanKind.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
                RichSpanKind.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                RichSpanKind.CODE -> SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.5.sp,
                    background = Color(0xFFEDE4D7),
                    color = BauhausColors.Black
                )
                RichSpanKind.QUOTE -> SpanStyle(
                    fontStyle = FontStyle.Italic,
                    color = Color(0xFF4A453E),
                    background = Color(0xFFF3ECE1)
                )
                RichSpanKind.H1 -> SpanStyle(
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold
                )
                RichSpanKind.H2 -> SpanStyle(
                    fontSize = 19.5.sp,
                    fontWeight = FontWeight.Bold
                )
                RichSpanKind.H3 -> SpanStyle(
                    fontSize = 17.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                RichSpanKind.TEXT_COLOR -> {
                    val col = parseHexColorOrNull(sp.colorHex) ?: BauhausColors.Black
                    SpanStyle(color = col)
                }
                RichSpanKind.HIGHLIGHT -> {
                    if (paintHighlightInline) {
                        val bg = RichNoteCodec.parseHexColorOrNull(sp.colorHex) ?: Color(0xFFFFF176)
                        SpanStyle(background = bg)
                    } else {
                        null
                    }
                }
                RichSpanKind.ALIGN -> null
            }
            if (style != null) {
                builder.addStyle(style, s, e)
            }
        }
        spans.filter { it.kind == RichSpanKind.ALIGN }.forEach { sp ->
            val paragraphStart = if (sp.start == 0) 0 else sp.start
            val paragraphEnd = sp.end.coerceAtMost(text.length)
            if (paragraphStart < paragraphEnd) {
                builder.addStyle(
                    ParagraphStyle(
                        textAlign = when (BlockAlignment.fromId(sp.colorHex)) {
                            BlockAlignment.LEFT -> TextAlign.Start
                            BlockAlignment.CENTER -> TextAlign.Center
                            BlockAlignment.RIGHT -> TextAlign.End
                        }
                    ),
                    paragraphStart,
                    paragraphEnd
                )
            }
        }
        return builder.toAnnotatedString()
    }

    fun visualTransformation(
        spans: List<RichSpan>,
        paintHighlightInline: Boolean = false
    ): VisualTransformation {
        if (spans.isEmpty()) return VisualTransformation.None
        return VisualTransformation { raw ->
            TransformedText(
                text = buildAnnotatedString(raw.text, spans, paintHighlightInline = paintHighlightInline),
                offsetMapping = OffsetMapping.Identity
            )
        }
    }

    /**
     * Paints highlighter bands behind glyphs with symmetrical top and bottom vertical padding
     * (fixing Android Skia's tall top `ascent` gap while preserving the exact snug bottom padding).
     */
    fun applyTextEdit(
        oldText: String,
        newValue: TextFieldValue,
        oldSpans: List<RichSpan>,
        activeStyle: ActiveTypingStyle
    ): Triple<TextFieldValue, List<RichSpan>, ActiveTypingStyle> {
        val newText = newValue.text
        if (oldText == newText) {
            return Triple(newValue, oldSpans, activeStyle)
        }

        var diffStart = 0
        val minLen = minOf(oldText.length, newText.length)
        while (diffStart < minLen && oldText[diffStart] == newText[diffStart]) {
            diffStart++
        }
        var oldTail = oldText.length
        var newTail = newText.length
        while (oldTail > diffStart && newTail > diffStart && oldText[oldTail - 1] == newText[newTail - 1]) {
            oldTail--
            newTail--
        }

        val removedLen = oldTail - diffStart
        val insertedLen = newTail - diffStart
        val delta = insertedLen - removedLen

        val updatedSpans = ArrayList<RichSpan>(oldSpans.size + 6)
        for (sp in oldSpans) {
            when {
                sp.end <= diffStart -> updatedSpans.add(sp)
                sp.start >= oldTail -> updatedSpans.add(
                    sp.copy(start = sp.start + delta, end = sp.end + delta)
                )
                else -> {
                    val newStart = if (sp.start <= diffStart) {
                        sp.start
                    } else {
                        (diffStart + insertedLen).coerceAtMost(sp.start + delta)
                    }
                    val newEnd = if (sp.end >= oldTail) {
                        sp.end + delta
                    } else {
                        diffStart
                    }
                    if (newStart < newEnd) {
                        updatedSpans.add(sp.copy(start = newStart, end = newEnd))
                    }
                }
            }
        }

        var nextStyle = activeStyle

        if (insertedLen > 0) {
            val insertedSlice = newText.substring(diffStart, newTail)
            var segStart = 0
            while (segStart < insertedSlice.length) {
                val nlIdx = insertedSlice.indexOf('\n', segStart)
                val segEnd = if (nlIdx == -1) insertedSlice.length else nlIdx
                if (segEnd > segStart) {
                    val absS = diffStart + segStart
                    val absE = diffStart + segEnd
                    if (nextStyle.bold) updatedSpans.add(RichSpan(absS, absE, RichSpanKind.BOLD))
                    if (nextStyle.italic) updatedSpans.add(RichSpan(absS, absE, RichSpanKind.ITALIC))
                    if (nextStyle.underline) updatedSpans.add(RichSpan(absS, absE, RichSpanKind.UNDERLINE))
                    if (nextStyle.strikethrough) updatedSpans.add(RichSpan(absS, absE, RichSpanKind.STRIKETHROUGH))
                    if (nextStyle.code) updatedSpans.add(RichSpan(absS, absE, RichSpanKind.CODE))
                    if (nextStyle.quote) updatedSpans.add(RichSpan(absS, absE, RichSpanKind.QUOTE))
                    nextStyle.heading?.let { h -> updatedSpans.add(RichSpan(absS, absE, h)) }
                    nextStyle.textColorHex?.let { hex ->
                        updatedSpans.add(RichSpan(absS, absE, RichSpanKind.TEXT_COLOR, hex))
                    }
                    nextStyle.highlightHex?.let { hex ->
                        updatedSpans.add(RichSpan(absS, absE, RichSpanKind.HIGHLIGHT, hex))
                    }
                }
                if (nlIdx != -1) {
                    nextStyle = nextStyle.copy(heading = null)
                    segStart = nlIdx + 1
                } else {
                    break
                }
            }

            if (insertedSlice == "\n" && removedLen == 0) {
                val prevLineBounds = lineBoundsAt(oldText, diffStart)
                val prevLine = oldText.substring(prevLineBounds.first, diffStart)
                val autoPrefix: String? = when {
                    prevLine == "• " || prevLine == "☐ " || prevLine == "☑ " || Regex("^\\d+\\. $").matches(prevLine) -> {
                        val clearedText = newText.removeRange(prevLineBounds.first, diffStart + 1)
                        val clearedCursor = prevLineBounds.first.coerceIn(0, clearedText.length)
                        val shifted = shiftSpansAfterDeletion(updatedSpans, prevLineBounds.first, diffStart + 1)
                        return Triple(
                            TextFieldValue(clearedText, TextRange(clearedCursor)),
                            normalizeSpans(shifted, clearedText.length),
                            nextStyle
                        )
                    }
                    prevLine.startsWith("• ") -> "• "
                    prevLine.startsWith("☐ ") || prevLine.startsWith("☑ ") -> "☐ "
                    else -> {
                        val numMatch = Regex("^(\\d+)\\. .+").find(prevLine)
                        numMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { "${it + 1}. " }
                    }
                }
                if (autoPrefix != null) {
                    val insertAt = diffStart + 1
                    val withPrefix = newText.substring(0, insertAt) + autoPrefix + newText.substring(insertAt)
                    val shifted = shiftSpansAfterInsertion(updatedSpans, insertAt, autoPrefix.length)
                    return Triple(
                        TextFieldValue(withPrefix, TextRange(insertAt + autoPrefix.length)),
                        normalizeSpans(shifted, withPrefix.length),
                        nextStyle
                    )
                }
            }
        }

        return Triple(newValue, normalizeSpans(updatedSpans, newText.length), nextStyle)
    }

    fun lineBoundsAt(text: String, offset: Int): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        val safe = offset.coerceIn(0, text.length)
        val start = text.lastIndexOf('\n', (safe - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
        val end = text.indexOf('\n', safe).let { if (it == -1) text.length else it }
        return start to end
    }

    fun toggleSpanInRange(
        spans: List<RichSpan>,
        start: Int,
        end: Int,
        kind: RichSpanKind,
        colorHex: String? = null,
        exclusiveGroup: Set<RichSpanKind> = setOf(kind),
        textLength: Int
    ): List<RichSpan> {
        if (start >= end) return spans
        val fullyCoveredSame = spans.any {
            it.kind == kind && it.colorHex == colorHex && it.start <= start && it.end >= end
        }
        val result = ArrayList<RichSpan>(spans.size + 2)
        for (sp in spans) {
            if (sp.kind !in exclusiveGroup || sp.end <= start || sp.start >= end) {
                result.add(sp)
            } else {
                if (sp.start < start) result.add(sp.copy(end = start))
                if (sp.end > end) result.add(sp.copy(start = end))
            }
        }
        if (!fullyCoveredSame) {
            result.add(RichSpan(start, end, kind, colorHex))
        }
        return normalizeSpans(result, textLength)
    }

    fun clearAllFormattingInRange(
        spans: List<RichSpan>,
        start: Int,
        end: Int,
        textLength: Int
    ): List<RichSpan> {
        if (start >= end) return spans
        val result = ArrayList<RichSpan>(spans.size)
        for (sp in spans) {
            if (sp.end <= start || sp.start >= end) {
                result.add(sp)
            } else {
                if (sp.start < start) result.add(sp.copy(end = start))
                if (sp.end > end) result.add(sp.copy(start = end))
            }
        }
        return normalizeSpans(result, textLength)
    }

    fun shiftSpansAfterInsertion(spans: List<RichSpan>, at: Int, count: Int): List<RichSpan> =
        spans.map { sp ->
            when {
                sp.end <= at -> sp
                sp.start >= at -> sp.copy(start = sp.start + count, end = sp.end + count)
                else -> sp.copy(end = sp.end + count)
            }
        }

    fun shiftSpansAfterDeletion(spans: List<RichSpan>, delStart: Int, delEnd: Int): List<RichSpan> {
        val len = delEnd - delStart
        if (len <= 0) return spans
        return spans.mapNotNull { sp ->
            when {
                sp.end <= delStart -> sp
                sp.start >= delEnd -> sp.copy(start = sp.start - len, end = sp.end - len)
                else -> {
                    val ns = if (sp.start < delStart) sp.start else delStart
                    val ne = if (sp.end > delEnd) sp.end - len else delStart
                    if (ns < ne) sp.copy(start = ns, end = ne) else null
                }
            }
        }
    }
}

fun DrawScope.drawBalancedHighlightSpans(
    layoutResult: TextLayoutResult?,
    textLength: Int,
    spans: List<RichSpan>
) {
    if (layoutResult == null || textLength <= 0 || spans.isEmpty()) return
    val corner = CornerRadius(3.dp.toPx(), 3.dp.toPx())
    val hPad = 1.5.dp.toPx()

    for (sp in spans) {
        if (sp.kind != RichSpanKind.HIGHLIGHT) continue
        val s = sp.start.coerceIn(0, textLength)
        val e = sp.end.coerceIn(0, textLength)
        if (s >= e) continue
        val bg = RichNoteCodec.parseHexColorOrNull(sp.colorHex) ?: Color(0xFFFFF176)

        val firstLine = runCatching { layoutResult.getLineForOffset(s) }.getOrNull() ?: continue
        val lastLine = runCatching { layoutResult.getLineForOffset((e - 1).coerceAtLeast(s)) }.getOrNull() ?: continue

        for (line in firstLine..lastLine) {
            val segStart = maxOf(s, layoutResult.getLineStart(line))
            val segEnd = minOf(e, layoutResult.getLineEnd(line, visibleEnd = true))
            if (segStart >= segEnd) continue

            val firstBox = runCatching { layoutResult.getBoundingBox(segStart) }.getOrNull() ?: continue
            val lastBox = runCatching { layoutResult.getBoundingBox(segEnd - 1) }.getOrNull() ?: continue
            val left = (minOf(firstBox.left, lastBox.left) - hPad).coerceAtLeast(0f)
            val right = maxOf(firstBox.right, lastBox.right) + hPad
            if (right <= left) continue

            val baseline = layoutResult.getLineBaseline(line)
            val bottomExtent = (firstBox.bottom - baseline).coerceAtLeast(2.dp.toPx())
            val ascentExtent = (baseline - firstBox.top).coerceAtLeast(bottomExtent * 2f)
            val balancedTop = baseline - (ascentExtent * 0.72f + bottomExtent * 0.35f)
            val balancedBottom = baseline + bottomExtent

            if (balancedBottom > balancedTop) {
                drawRoundRect(
                    color = bg,
                    topLeft = Offset(left, balancedTop),
                    size = Size(right - left, balancedBottom - balancedTop),
                    cornerRadius = corner
                )
            }
        }
    }
}

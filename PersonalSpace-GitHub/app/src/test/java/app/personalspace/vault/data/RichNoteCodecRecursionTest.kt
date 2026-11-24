package app.personalspace.vault.data

import app.personalspace.vault.presentation.records.RichNoteBlock
import app.personalspace.vault.presentation.records.RichNoteCodec
import app.personalspace.vault.presentation.records.RichNoteDocument
import app.personalspace.vault.presentation.records.RichSpan
import app.personalspace.vault.presentation.records.RichSpanKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P6 item 1 — `RichNoteCodec` mutual recursion.
 *
 * `decodeBlocks()` fell back to `decode()` when the block payload could not be parsed, and `decode()`
 * sent every block-delimited string straight back into `decodeBlocks()`: an unparseable block payload
 * therefore recursed forever and crashed with `StackOverflowError`.
 *
 * That payload is not hypothetical — a note written by an older/beta build, a truncated write, or any
 * corruption in the JSON tail lands exactly there, and the crash happened on *open*, i.e. the user's
 * note became unopenable.
 *
 * The fix must keep the original text, formatting and metadata intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RichNoteCodecRecursionTest {

    private val ev = StringBuilder()
    private fun note(line: String) { ev.append(line).append('\n'); println("P6C| $line") }
    private fun dump(tag: String) {
        File("/home/user/verification/evidence/$tag.txt").apply { parentFile?.mkdirs() }
            .writeText(ev.toString())
    }

    private companion object {
        /** exactly `RICH_BLOCKS_DELIMITER` in the codec: newline + \u2063 + "PSRICH2:" */
        const val BLOCKS = "\n\u2063PSRICH2:"
    }

    @Test
    fun `P6-C1 a block note with an unparseable payload decodes without StackOverflowError`() {
        // human text summary + the block delimiter + a payload that is not a JSON block list
        val corrupt = "Alpha\nBeta" + BLOCKS + "{this is not json"
        note("corrupt raw length = ${corrupt.length}")

        val outcome = runCatching { RichNoteCodec.decodeBlocks(corrupt) }
        note("decodeBlocks on corrupt payload -> " +
            (outcome.exceptionOrNull()?.javaClass?.simpleName ?: "ok"))
        assertTrue("must not crash", outcome.isSuccess)

        val doc = runCatching { RichNoteCodec.decode(corrupt) }
        note("decode on corrupt payload -> " +
            (doc.exceptionOrNull()?.javaClass?.simpleName ?: "ok"))
        assertTrue("decode must not crash either", doc.isSuccess)
        // the visible text is preserved, and (item 1) the unreadable payload is preserved with it
        assertTrue("the visible text must be preserved verbatim",
            doc.getOrThrow().plainText.startsWith("Alpha\nBeta"))
        assertTrue("the damaged payload must be preserved, not dropped",
            doc.getOrThrow().plainText.contains("{this is not json"))
        assertTrue("the plain-text extraction must keep the visible text",
            RichNoteCodec.extractPlainText(corrupt).startsWith("Alpha\nBeta"))
        dump("p6_C1_block_payload_recursion")
    }

    @Test
    fun `P6-C2 an empty block list payload decodes without recursion`() {
        val emptyPayload = "Hello" + BLOCKS + "[]"
        val outcome = runCatching { RichNoteCodec.decodeBlocks(emptyPayload) }
        note("decodeBlocks on empty payload -> " +
            (outcome.exceptionOrNull()?.javaClass?.simpleName ?: "ok, blocks=" + outcome.getOrThrow().size))
        assertTrue("must not crash", outcome.isSuccess)
        assertEquals("text must survive", "Hello", RichNoteCodec.extractPlainText(emptyPayload))
        // [] is a VALID empty block list, not corruption: no damage marker, no false alarm
        assertEquals("a valid empty block list is a clean note", "Hello",
            RichNoteCodec.decode(emptyPayload).plainText)
        assertFalse("and it must not be reported as damaged",
            RichNoteCodec.inspectDamage(emptyPayload).isDamaged)
        dump("p6_C2_empty_payload")
    }

    @Test
    fun `P6-C3 a deep mixed note round-trips with text, spans, alignment and images intact`() {
        val image = "note_img_deep_${System.nanoTime()}"
        val blocks = listOf(
            RichNoteBlock.Text(text = "Heading line", spans = listOf(RichSpan(0, 7, RichSpanKind.BOLD))),
            RichNoteBlock.Image(imagePath = image, widthFraction = 0.75f),
            RichNoteBlock.Text(
                text = "second para",
                spans = listOf(RichSpan(0, 6, RichSpanKind.HIGHLIGHT, "#38bdf8"), RichSpan(7, 11, RichSpanKind.ITALIC))
            ),
            RichNoteBlock.Text(text = "third", spans = emptyList())
        )
        val raw = RichNoteCodec.encodeBlocks(blocks)
        note("encoded length = ${raw.length}")
        val decoded = RichNoteCodec.decodeBlocks(raw)
        note("decoded block types = " + decoded.map { it::class.simpleName })
        val texts = decoded.filterIsInstance<RichNoteBlock.Text>()
        val images = decoded.filterIsInstance<RichNoteBlock.Image>()
        assertEquals("all text blocks kept (surrounding ones are inserted, never dropped)",
            listOf("Heading line", "second para", "third"), texts.map { it.text }.filter { it.isNotEmpty() })
        assertEquals("the image must survive", listOf(image), images.map { it.imagePath })
        assertEquals("image width must survive", 0.75f, images.single().widthFraction, 0.0001f)
        val heading = texts.first { it.text == "Heading line" }
        assertTrue("the heading must keep its bold span",
            heading.spans.any { it.kind == RichSpanKind.BOLD && it.start == 0 && it.end == 7 })
        val second = texts.first { it.text == "second para" }
        assertTrue("the highlight and its colour must survive",
            second.spans.any { it.kind == RichSpanKind.HIGHLIGHT && it.colorHex == "#38bdf8" })
        assertTrue("the italic span must survive", second.spans.any { it.kind == RichSpanKind.ITALIC })

        // and the flattened document view must agree with the visible text
        val doc = RichNoteCodec.decode(raw)
        note("document text = " + doc.plainText.replace("\n", "\\n"))
        assertTrue(doc.plainText.contains("Heading line") && doc.plainText.contains("second para"))
        dump("p6_C3_deep_round_trip")
    }

    @Test
    fun `P6-C4 legacy plain text, rich metadata and markdown notes still decode unchanged`() {
        // (a) plain text
        assertEquals("just words", RichNoteCodec.decode("just words").plainText)
        // (b) plain text + span metadata
        val rich = RichNoteCodec.encode("bolded", listOf(RichSpan(0, 6, RichSpanKind.BOLD)))
        note("rich raw = " + rich.replace("\u0001", "<DELIM1>"))
        val doc = RichNoteCodec.decode(rich)
        assertEquals("bolded", doc.plainText)
        assertTrue("the span must survive", doc.spans.any { it.kind == RichSpanKind.BOLD })
        // (c) legacy markdown
        val legacy = RichNoteCodec.decode("# Title\n\nsome **bold** text")
        note("legacy text = " + legacy.plainText.replace("\n", "\\n"))
        assertEquals("Title\n\nsome bold text", legacy.plainText)
        assertTrue("the heading must become a span", legacy.spans.any { it.kind == RichSpanKind.H1 })
        assertTrue("the bold must become a span", legacy.spans.any { it.kind == RichSpanKind.BOLD })
        dump("p6_C4_legacy_compatibility")
    }

    @Test
    fun `P6-C5 very deep block nesting and huge notes do not overflow`() {
        // 400 text blocks: exercises the flatten/ensure-surrounding paths at scale
        val many = (1..400).map { RichNoteBlock.Text(text = "line $it") }
        val raw = RichNoteCodec.encodeBlocks(many)
        note("400 text-only blocks encode compactly (no block delimiter): ${!raw.contains(BLOCKS)}")
        val back = runCatching { RichNoteCodec.decodeBlocks(raw) }
        note("400-block decode -> " + (back.exceptionOrNull()?.javaClass?.simpleName ?: "ok"))
        assertTrue("must not overflow", back.isSuccess)
        // text-only notes use the compact single-document format, so preservation is asserted on the
        // text itself — all 400 lines must be there, in order, and nothing else may appear
        val text = RichNoteCodec.extractPlainText(raw)
        val lines = text.lines()
        assertEquals("every line must be kept", 400, lines.size)
        assertEquals("first line", "line 1", lines.first())
        assertEquals("last line", "line 400", lines.last())
        assertEquals("the document view must agree", text, RichNoteCodec.decode(raw).plainText)

        // alternating text/image through the block path
        val alternating = (1..60).flatMap {
            listOf(RichNoteBlock.Text(text = "t$it"), RichNoteBlock.Image(imagePath = "img$it"))
        }
        val raw2 = RichNoteCodec.encodeBlocks(alternating)
        val back2 = runCatching { RichNoteCodec.decodeBlocks(raw2) }
        assertTrue("alternating must not overflow", back2.isSuccess)
        assertEquals("every image must be kept", 60,
            back2.getOrThrow().filterIsInstance<RichNoteBlock.Image>().size)
        note("alternating blocks = ${back2.getOrThrow().size} (images padded with text blocks)")
        dump("p6_C5_scale")
    }
}

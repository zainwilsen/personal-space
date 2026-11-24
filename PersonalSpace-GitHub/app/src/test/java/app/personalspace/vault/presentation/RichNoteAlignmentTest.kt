package app.personalspace.vault.presentation

import app.personalspace.vault.presentation.records.BlockAlignment
import app.personalspace.vault.presentation.records.RichNoteBlock
import app.personalspace.vault.presentation.records.RichNoteCodec
import app.personalspace.vault.presentation.records.paragraphAlignmentAt
import app.personalspace.vault.presentation.records.applyParagraphAlignment
import org.junit.Assert.assertEquals
import org.junit.Test

class RichNoteAlignmentTest {

    @Test
    fun `text block alignment survives encode and decode`() {
        val blocks = listOf(
            RichNoteBlock.Text(
                text = "center line",
                spans = applyParagraphAlignment("center line", emptyList(), 0, BlockAlignment.CENTER)
            ),
            RichNoteBlock.Text(
                text = "right line",
                spans = applyParagraphAlignment("right line", emptyList(), 0, BlockAlignment.RIGHT)
            )
        )
        val raw = RichNoteCodec.encodeBlocks(blocks)
        val texts = RichNoteCodec.decodeBlocks(raw).filterIsInstance<RichNoteBlock.Text>()

        assertEquals(BlockAlignment.CENTER, paragraphAlignmentAt(texts[0].text, 0, texts[0].spans))
        assertEquals(BlockAlignment.RIGHT, paragraphAlignmentAt(texts[1].text, 0, texts[1].spans))
    }

    @Test
    fun `image block keeps alignment and width together`() {
        val blocks = listOf(
            RichNoteBlock.Text(text = "intro"),
            RichNoteBlock.Image(
                imagePath = "/tmp/pic.jpg",
                widthFraction = 0.6f,
                alignment = BlockAlignment.RIGHT
            )
        )
        val decoded = RichNoteCodec.decodeBlocks(RichNoteCodec.encodeBlocks(blocks))
        val image = decoded.filterIsInstance<RichNoteBlock.Image>().single()

        assertEquals(BlockAlignment.RIGHT, image.alignment)
        assertEquals(0.6f, image.widthFraction, 0.001f)
    }

    @Test
    fun `note saved before alignment feature decodes as left aligned`() {
        val legacyRaw = "old note body"
        val decoded = RichNoteCodec.decodeBlocks(legacyRaw)

        assertEquals(1, decoded.size)
        assertEquals(BlockAlignment.LEFT, (decoded.single() as RichNoteBlock.Text).alignment)
    }

    @Test
    fun `paragraph alignment applies to one paragraph only`() {
        val text = "first line\nsecond line"
        val spans = applyParagraphAlignment(text, emptyList(), position = 2, alignment = BlockAlignment.CENTER)

        assertEquals(BlockAlignment.CENTER, paragraphAlignmentAt(text, 0, spans))
        assertEquals(BlockAlignment.LEFT, paragraphAlignmentAt(text, text.indexOf("second"), spans))
        assertEquals(1, spans.count { it.kind == app.personalspace.vault.presentation.records.RichSpanKind.ALIGN })
    }

    @Test
    fun `switching paragraph back to left removes the alignment span`() {
        val text = "one line"
        val centered = applyParagraphAlignment(text, emptyList(), 0, BlockAlignment.CENTER)
        val left = applyParagraphAlignment(text, centered, 0, BlockAlignment.LEFT)

        assertEquals(BlockAlignment.LEFT, paragraphAlignmentAt(text, 0, left))
        assertEquals(0, left.count { it.kind == app.personalspace.vault.presentation.records.RichSpanKind.ALIGN })
    }

    @Test
    fun `legacy block alignment is migrated to paragraph spans on decode`() {
        val raw = RichNoteCodec.encodeBlocks(
            listOf(
                RichNoteBlock.Text(text = "old centered", alignment = BlockAlignment.CENTER),
                RichNoteBlock.Text(text = "old right", alignment = BlockAlignment.RIGHT)
            )
        )
        val decoded = RichNoteCodec.decodeBlocks(raw).filterIsInstance<RichNoteBlock.Text>()
        val first = decoded.first { it.text.contains("old centered") }

        assertEquals(BlockAlignment.CENTER, paragraphAlignmentAt(first.text, 0, first.spans))
        assertEquals(BlockAlignment.LEFT, first.alignment)
    }

    @Test
    fun `paragraph alignment survives encode and decode round trip`() {
        val text = "kept centered\nplain tail"
        val spans = applyParagraphAlignment(text, emptyList(), 0, BlockAlignment.CENTER)
        val raw = RichNoteCodec.encodeBlocks(listOf(RichNoteBlock.Text(text = text, spans = spans)))
        val decoded = RichNoteCodec.decodeBlocks(raw).filterIsInstance<RichNoteBlock.Text>().first()

        assertEquals(BlockAlignment.CENTER, paragraphAlignmentAt(decoded.text, 0, decoded.spans))
        assertEquals(BlockAlignment.LEFT, paragraphAlignmentAt(decoded.text, decoded.text.indexOf("plain"), decoded.spans))
    }

    @Test
    fun `typing a new line keeps paragraph alignment`() {
        val first = applyParagraphAlignment("Line one", emptyList(), 0, BlockAlignment.CENTER)
        val edit = RichNoteCodec.applyTextEdit(
            oldText = "Line one",
            newValue = androidx.compose.ui.text.input.TextFieldValue(
                "Line one\nLine two",
                androidx.compose.ui.text.TextRange(17)
            ),
            oldSpans = first,
            activeStyle = app.personalspace.vault.presentation.records.ActiveTypingStyle()
        )
        val text = edit.first.text
        val spans = edit.second
        assertEquals(BlockAlignment.CENTER, paragraphAlignmentAt(text, 0, spans))

        val block = RichNoteBlock.Text(text = text, spans = spans)
        val decoded = RichNoteCodec.decodeBlocks(RichNoteCodec.encodeBlocks(listOf(block)))
            .filterIsInstance<RichNoteBlock.Text>().first()
        assertEquals(BlockAlignment.CENTER, paragraphAlignmentAt(decoded.text, 0, decoded.spans))
        assertEquals(BlockAlignment.LEFT, paragraphAlignmentAt(decoded.text, decoded.text.indexOf("Line two"), decoded.spans))
    }

    @Test
    fun `unknown alignment value falls back to left instead of crashing`() {
        assertEquals(BlockAlignment.LEFT, BlockAlignment.fromId("JUSTIFY"))
        assertEquals(BlockAlignment.LEFT, BlockAlignment.fromId(null))
        assertEquals(BlockAlignment.CENTER, BlockAlignment.fromId("center"))
    }
}

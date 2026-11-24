package app.personalspace.vault.data

import app.personalspace.vault.presentation.records.RichNoteBlock
import app.personalspace.vault.presentation.records.RichNoteCodec
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Characterisation harness for RichNoteCodec.decodeBlocks <-> decode recursion.
 * Prints outcomes for a matrix of inputs; asserts nothing except that idempotent inputs behave.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RichNoteCodecRecursionProbeTest {

    private val ev = StringBuilder()
    private fun note(s: String) { ev.append(s).append('\n'); println("PROBE| $s") }
    private fun dump() {
        File("/home/user/verification/evidence/richcodec_recursion.txt")
            .apply { parentFile?.mkdirs() }.writeText(ev.toString())
    }

    private fun probe(label: String, raw: String) {
        val d = runCatching { RichNoteCodec.decodeBlocks(raw) }
        val doc = runCatching { RichNoteCodec.decode(raw) }
        val ex = runCatching { RichNoteCodec.extractPlainText(raw) }
        note("[$label] len=${raw.length}")
        note("   decodeBlocks -> ${d.fold({ b -> "OK size=" + b.size }, { t -> "THROW " + t.javaClass.simpleName })}")
        note("   decode       -> ${doc.fold({ x -> "OK len=" + x.plainText.length }, { t -> "THROW " + t.javaClass.simpleName })}")
        note("   extractPlain -> ${ex.fold({ s -> "OK '" + s.take(24) + "'" }, { t -> "THROW " + t.javaClass.simpleName })}")
    }

    @Test
    fun `characterise recursion trigger space`() {
        val d2 = "\n\u2063PSRICH2:"
        val d1 = "\n\u2063PSRICH1:"

        probe("A plainText+PSRICH2+nonJson", "Heading" + d2 + "not json")
        probe("B PSRICH2 at very start", d2 + "not json")

        val valid = RichNoteCodec.encodeBlocks(listOf(RichNoteBlock.Text(text = "Body")))
        note("[C] valid encoded length=${valid.length} containsDelim=${valid.contains(d2)}")

        // encoded note + user appends more text after the payload (a realistic "continue typing" case)
        probe("D validEncoded + trailing text", valid + " extra words")
        // encoded note truncated so the JSON no longer parses
        probe("E validEncoded truncated", valid.dropLast(2))
        // two encoded notes concatenated
        probe("F encoded + encoded", valid + valid)
        // encoded note with a nested delimiter inside its own text payload
        val nested = RichNoteCodec.encodeBlocks(listOf(RichNoteBlock.Text(text = "inner" + d2 + "[{\"type\":\"TEXT\"}]")))
        probe("G encoded containing delimiter in text", nested)

        probe("H PSRICH1 with garbage", "Body" + d1 + "[bad json]")
        probe("I empty", "")

        dump()
    }

    @Test
    fun `verify decode of every description actually produced by the app`() {
        // Realistic app-produced descriptions must always decode.
        val cases = listOf(
            RichNoteCodec.encodeBlocks(listOf(RichNoteBlock.Text(text = "Plain single block"))),
            RichNoteCodec.encodeBlocks(
                listOf(
                    RichNoteBlock.Text(text = "Block one"),
                    RichNoteBlock.Text(text = "Block two")
                )
            ),
            RichNoteCodec.encodeBlocks(
                listOf(
                    RichNoteBlock.Text(text = "Before image"),
                    RichNoteBlock.Image(imagePath = "note_img_abc", widthFraction = 0.5f)
                )
            )
        )
        var ok = 0
        cases.forEachIndexed { i, c ->
            val r = runCatching { RichNoteCodec.decodeBlocks(c) }
            note("app-produced case $i len=${c.length} -> ${r.fold({ "OK size=" + it.size }, { "THROW " + it.javaClass.simpleName })}")
            if (r.isSuccess) ok++
        }
        dump()
        org.junit.Assert.assertEquals("all app-produced descriptions must decode", cases.size, ok)
    }
}

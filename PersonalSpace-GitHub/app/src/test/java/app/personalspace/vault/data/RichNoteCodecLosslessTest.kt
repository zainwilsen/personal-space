package app.personalspace.vault.data

import app.personalspace.vault.presentation.records.DAMAGED_PAYLOAD_END
import app.personalspace.vault.presentation.records.DAMAGED_PAYLOAD_MARKER
import app.personalspace.vault.presentation.records.DAMAGE_NONE
import app.personalspace.vault.presentation.records.DAMAGE_UNREADABLE_BLOCK_PAYLOAD
import app.personalspace.vault.presentation.records.DAMAGE_UNREADABLE_SPAN_METADATA
import app.personalspace.vault.presentation.records.RichNoteBlock
import app.personalspace.vault.presentation.records.RichNoteCodec
import app.personalspace.vault.presentation.records.RichSpan
import app.personalspace.vault.presentation.records.RichSpanKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Item 1 — **lossless** handling of malformed / truncated rich content.
 *
 * The recursion fix (P6) stopped the crash but still dropped the unreadable payload on the floor. A
 * note whose metadata cannot be parsed is exactly the case where the user's content must be treated as
 * most fragile, so every byte has to survive: the visible text, and the original payload verbatim.
 *
 * These tests assert that property directly — the round trip is compared byte for byte.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RichNoteCodecLosslessTest {

    private val ev = StringBuilder()
    private fun note(line: String) { ev.append(line).append('\n'); println("LOSS| $line") }
    private fun dump(tag: String) {
        File("/home/user/verification/evidence/$tag.txt").apply { parentFile?.mkdirs() }
            .writeText(ev.toString())
    }

    private companion object {
        /** exactly `RICH_BLOCKS_DELIMITER` / `RICH_METADATA_DELIMITER` in the codec */
        const val BLOCKS = "\n\u2063PSRICH2:"
        const val SPANS = "\n\u2063PSRICH1:"
    }

    // ------------------------------------------------------------------ no silent loss

    @Test
    fun `L1 a truncated block payload keeps every original character`() {
        val truncated = "My important note" + BLOCKS + "[{\"type\":\"TEXT\",\"id\":\"a\",\"tex"
        val report = RichNoteCodec.inspectDamage(truncated)
        note("damage = ${report.damage} (${report.detail}), payload=${report.preservedPayloadLength} chars")
        assertTrue("the damage must be reported", report.isDamaged)
        assertEquals(DAMAGE_UNREADABLE_BLOCK_PAYLOAD, report.damage)

        val blocks = RichNoteCodec.decodeBlocks(truncated)
        val text = blocks.filterIsInstance<RichNoteBlock.Text>().joinToString("\n") { it.text }
        note("recovered text = ${text.replace("\n", "\\n")}")
        assertTrue("the visible text must be intact", text.startsWith("My important note"))
        assertTrue("the damaged payload must be preserved verbatim", text.contains(DAMAGED_PAYLOAD_MARKER))
        assertTrue("including the raw JSON fragment",
            text.contains("[{\"type\":\"TEXT\",\"id\":\"a\",\"tex"))
        assertTrue("and it must be closed", text.contains(DAMAGED_PAYLOAD_END))

        // nothing lost: every original character is still reachable from the decoded text
        val payload = truncated.substringAfter(BLOCKS)
        assertTrue("every payload character must survive", text.contains(payload))
        dump("loss_L1_truncated_block_payload")
    }

    @Test
    fun `L2 a damaged note re-saves its payload instead of overwriting it`() {
        val original = "Heading" + BLOCKS + "[{\"type\":\"TEXT\",\"id\":\"b\",\"text\":\"body\""
        val once = RichNoteCodec.decodeBlocks(original)
        val resaved = RichNoteCodec.encodeBlocks(once)
        note("re-saved length = ${resaved.length}, original = ${original.length}")
        // the payload survives the encode pass, so a load/save cycle cannot destroy it
        assertTrue("the damaged payload must still be present after a re-save",
            resaved.contains("[{\"type\":\"TEXT\",\"id\":\"b\",\"text\":\"body\""))
        // and a second round trip is stable (no runaway growth)
        val twice = RichNoteCodec.encodeBlocks(RichNoteCodec.decodeBlocks(resaved))
        assertEquals("a load/save cycle must be idempotent, not grow the note", resaved, twice)
        note("idempotent re-save = true")
        dump("loss_L2_resave_preserves_payload")
    }

    @Test
    fun `L3 unreadable span metadata is preserved and reported`() {
        val raw = "some text" + SPANS + "[{\"start\":0,\"end\":3,\"kin"
        val report = RichNoteCodec.inspectDamage(raw)
        note("damage = ${report.damage}: ${report.detail}")
        assertEquals(DAMAGE_UNREADABLE_SPAN_METADATA, report.damage)
        assertEquals("the visible text must still be exactly the visible text",
            "some text", RichNoteCodec.extractPlainText(raw))
        assertEquals("and the document view must not crash", "some text",
            RichNoteCodec.decode(raw).plainText)
        dump("loss_L3_span_metadata")
    }

    @Test
    fun `L4 a blank block payload is damage but a valid empty list is not`() {
        // (a) `[]` parses: it is a clean note that simply carries no rich blocks. Reporting damage here
        // would be a false alarm on ordinary user data.
        val validEmpty = "only words" + BLOCKS + "[]"
        note("valid empty list -> damage = ${RichNoteCodec.inspectDamage(validEmpty).damage}")
        assertEquals("a valid empty block list is NOT damage", DAMAGE_NONE,
            RichNoteCodec.inspectDamage(validEmpty).damage)
        assertEquals("only words", RichNoteCodec.decode(validEmpty).plainText)
        assertFalse("and it must carry no damage marker",
            RichNoteCodec.decode(validEmpty).plainText.contains(DAMAGED_PAYLOAD_MARKER))

        // (b) a delimiter with nothing after it is a genuinely truncated write: that IS damage, and the
        // visible text must survive.
        val blank = "only words" + BLOCKS
        note("blank payload -> damage = ${RichNoteCodec.inspectDamage(blank).damage}")
        assertEquals(DAMAGE_UNREADABLE_BLOCK_PAYLOAD, RichNoteCodec.inspectDamage(blank).damage)
        assertEquals("only words", RichNoteCodec.extractPlainText(blank))
        assertEquals("only words", RichNoteCodec.decode(blank).plainText)
        dump("loss_L4_empty_vs_blank_payload")
    }

    // ------------------------------------------------------------------ healthy notes are untouched

    @Test
    fun `L5 a healthy rich note reports no damage and round-trips byte-identically`() {
        val image = "note_img_lossless"
        val blocks = listOf(
            RichNoteBlock.Text(
                text = "Bold start",
                spans = listOf(RichSpan(0, 4, RichSpanKind.BOLD), RichSpan(5, 10, RichSpanKind.TEXT_COLOR, "#FF0000"))
            ),
            RichNoteBlock.Image(imagePath = image, widthFraction = 0.42f),
            RichNoteBlock.Text(
                text = "tail",
                spans = listOf(RichSpan(0, 4, RichSpanKind.HIGHLIGHT, "#FFF176"))
            )
        )
        val encoded = RichNoteCodec.encodeBlocks(blocks)
        val report = RichNoteCodec.inspectDamage(encoded)
        note("healthy note damage = ${report.damage}")
        assertEquals("a note this build wrote must report no damage", DAMAGE_NONE, report.damage)

        val decoded = RichNoteCodec.decodeBlocks(encoded)
        val reencoded = RichNoteCodec.encodeBlocks(decoded)
        assertEquals("a healthy note must round-trip byte-identically", encoded, reencoded)
        val texts = decoded.filterIsInstance<RichNoteBlock.Text>().map { it.text }.filter { it.isNotEmpty() }
        assertEquals(listOf("Bold start", "tail"), texts)
        assertTrue("the bold span must survive",
            decoded.filterIsInstance<RichNoteBlock.Text>().first { it.text == "Bold start" }
                .spans.any { it.kind == RichSpanKind.BOLD && it.start == 0 && it.end == 4 })
        assertEquals("the image must survive", listOf(image),
            decoded.filterIsInstance<RichNoteBlock.Image>().map { it.imagePath })
        assertEquals("the width must survive", 0.42f,
            decoded.filterIsInstance<RichNoteBlock.Image>().single().widthFraction, 0.0001f)
        note("byte-identical round trip for a healthy note = true")
        dump("loss_L5_healthy_round_trip")
    }

    @Test
    fun `L6 plain text, span metadata and legacy markdown all report no damage`() {
        val plain = "just plain words"
        assertEquals(DAMAGE_NONE, RichNoteCodec.inspectDamage(plain).damage)
        assertEquals(plain, RichNoteCodec.encodeBlocks(RichNoteCodec.decodeBlocks(plain)))

        val rich = RichNoteCodec.encode("bolded", listOf(RichSpan(0, 6, RichSpanKind.BOLD)))
        assertEquals("span-metadata notes are readable", DAMAGE_NONE, RichNoteCodec.inspectDamage(rich).damage)
        assertEquals("bolded", RichNoteCodec.extractPlainText(rich))

        val legacy = "# Title\n\nsome **bold** text"
        assertEquals("legacy markdown is not damage", DAMAGE_NONE, RichNoteCodec.inspectDamage(legacy).damage)
        assertEquals("Title\n\nsome bold text", RichNoteCodec.decode(legacy).plainText)
        note("plain / span-metadata / legacy markdown: no false damage reports")
        dump("loss_L6_no_false_positives")
    }

    @Test
    fun `L7 damage detection is decided by content, not by throwing`() {
        // A note that merely LOOKS like markup must not be flagged.
        val tricky = "line with \u2063 delimiter-like text but no prefix"
        assertFalse("a note containing the separator character is not damaged",
            RichNoteCodec.inspectDamage(tricky).isDamaged)
        assertEquals(tricky, RichNoteCodec.decode(tricky).plainText)

        // A genuinely broken payload is flagged, and the flag is stable across repeated calls.
        val broken = "text" + BLOCKS + "not json at all"
        val first = RichNoteCodec.inspectDamage(broken)
        val second = RichNoteCodec.inspectDamage(broken)
        assertEquals("inspection must be deterministic", first, second)
        assertTrue(first.isDamaged)
        note("tricky plain note: no damage; broken payload: damage, deterministic")
        dump("loss_L7_detection_semantics")
    }

    @Test
    fun `L8 a damaged note survives a full vault round trip through backup and restore`() = runBlocking {
        val rig = app.personalspace.vault.IsolatedVaultTestRig(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(), "lossless_rt"
        )
        try {
            rig.initializeAndUnlockVault("LosslessPass#2026")
            val db = rig.dbHolder.getDatabase()
            val damagedBody = "Recovered note text" + BLOCKS + "[{\"type\":\"TEXT\",\"id\":\"z\",\"text\":\"trunc"
            val rec = rig.repository.saveRecord(null, "Damaged note", damagedBody, null, null, emptyList())

            val pkg = rig.backupManager.createFullBackupFile(
                app.personalspace.vault.domain.model.BackupKind.FULL_MANUAL,
                targetFile = File(rig.context.cacheDir, "lossless.psbackup"),
                recordHistoryInDb = false
            ).file
            rig.backupManager.restoreFromBackupFile(pkg, "LosslessPass#2026".toCharArray())

            val restored = db.recordDao().getRecordById(rec.id)!!.description
            note("restored body == original body: ${restored == damagedBody}")
            assertEquals("BACKUP/RESTORE MUST BE BYTE-LOSSLESS for a damaged note", damagedBody, restored)
            assertTrue("and the damage is still detectable afterwards",
                RichNoteCodec.inspectDamage(restored).isDamaged)
        } finally { rig.close() }
        dump("loss_L8_backup_restore_byte_lossless")
    }

    private fun runBlocking(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
}

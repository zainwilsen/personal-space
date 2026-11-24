package app.personalspace.vault.presentation.records

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Generates a clean, Minimal Bauhaus editorial PDF for any Note, dynamically sizing single-page
 * notes to fit their content cleanly while seamlessly auto-paginating arbitrarily long notes
 * line-by-line across standard A4 pages with full rich formatting & highlighter colors.
 */
object NotePdfExporter {

    private const val PAGE_WIDTH = 595
    private const val A4_PAGE_HEIGHT = 842
    private const val MIN_SINGLE_PAGE_HEIGHT = A4_PAGE_HEIGHT
    private const val MARGIN_HORIZONTAL = 44f
    private const val MARGIN_TOP = 46f
    private const val MARGIN_BOTTOM = 48f

    private sealed class PreparedItem {
        data class LayoutBlock(
            val layout: StaticLayout,
            val indentX: Float = 0f,
            val spacingBefore: Float = 0f,
            val spacingAfter: Float = 5f
        ) : PreparedItem()

        data class DividerLine(
            val spacingTop: Float = 13f,
            val spacingBottom: Float = 16f
        ) : PreparedItem()

        data class ImageItem(
            val bitmap: Bitmap,
            val drawWidth: Float,
            val drawHeight: Float
        ) : PreparedItem()

        data class BlankGap(val height: Float = 8f) : PreparedItem()
    }

    private data class DrawSlice(
        val height: Float,
        val draw: (android.graphics.Canvas, Float) -> Unit
    )

    suspend fun exportNoteToPdfFile(
        context: Context,
        title: String,
        markdownBody: String,
        resolveImageFile: (suspend (String) -> File?)? = null
    ): File = withContext(Dispatchers.IO) {
        val shareDir = File(context.cacheDir, "ephemeral_share").apply { mkdirs() }
        val cleanBaseName = title.trim()
            .ifBlank { "Note" }
            .replace(Regex("[^a-zA-Z0-9._ -]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .take(48)
            .ifBlank { "Note" }

        val targetFile = File(shareDir, "${cleanBaseName}.pdf")
        val contentWidth = (PAGE_WIDTH - (MARGIN_HORIZONTAL * 2f)).toInt()

        val blocks = RichNoteCodec.decodeBlocks(markdownBody)
        val hasAnyContent = RichNoteCodec.hasMeaningfulContent(markdownBody)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#101010")
            textSize = 27f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#101010")
            textSize = 16.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val emptyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#77716A")
            textSize = 16.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        }
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#DED5C8")
            strokeWidth = 1.1f
        }
        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#77716A")
            textSize = 10.5f
        }
        val bgPaint = Paint().apply {
            color = Color.parseColor("#FFFDF9")
            style = Paint.Style.FILL
        }

        val items = ArrayList<PreparedItem>()

        val displayTitle = title.ifBlank { "Untitled Note" }
        items.add(
            PreparedItem.LayoutBlock(
                layout = buildStaticLayout(displayTitle, titlePaint, contentWidth, lineMult = 1.18f),
                spacingAfter = 0f
            )
        )

        items.add(PreparedItem.DividerLine(spacingTop = 13f, spacingBottom = 16f))

        if (!hasAnyContent) {
            items.add(
                PreparedItem.LayoutBlock(
                    layout = buildStaticLayout("No Notes yet", emptyPaint, contentWidth, lineMult = 1.25f)
                )
            )
        } else {
            blocks.forEach { block ->
                when (block) {
                    is RichNoteBlock.Image -> {
                        val f = resolveImageFile?.invoke(block.imagePath) ?: File(block.imagePath)
                        if (f.exists()) {
                            val bmp = BitmapFactory.decodeFile(f.absolutePath)
                            if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                                val maxImgH = (A4_PAGE_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM - 40f)
                                var drawW = contentWidth * block.widthFraction.coerceIn(0.25f, 1.0f)
                                var drawH = drawW * (bmp.height.toFloat() / bmp.width.toFloat())
                                if (drawH > maxImgH) {
                                    val scale = maxImgH / drawH
                                    drawH = maxImgH
                                    drawW *= scale
                                }
                                items.add(PreparedItem.ImageItem(bmp, drawW, drawH))
                            }
                        }
                    }
                    is RichNoteBlock.Text -> {
                        if (block.text.isNotEmpty()) {
                            var charCursor = 0
                            val lines = block.text.split('\n')
                            lines.forEachIndexed { idx, lineText ->
                                val lineStart = charCursor
                                val lineEnd = lineStart + lineText.length
                                charCursor = lineEnd + if (idx < lines.lastIndex) 1 else 0

                                if (lineText.isBlank()) {
                                    items.add(PreparedItem.BlankGap(height = 8f))
                                } else if (lineText.trim() == "────────────────────") {
                                    items.add(PreparedItem.DividerLine(spacingTop = 8f, spacingBottom = 8f))
                                } else {
                                    val spannable = buildLineSpannable(lineText, lineStart, lineEnd, block.spans)
                                    val isHeading = block.spans.any {
                                        it.start < lineEnd && it.end > lineStart &&
                                            (it.kind == RichSpanKind.H1 || it.kind == RichSpanKind.H2 || it.kind == RichSpanKind.H3)
                                    }
                                    val lineAlignment = when (
                                        paragraphAlignmentAt(block.text, lineStart, block.spans)
                                    ) {
                                        BlockAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
                                        BlockAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                                        BlockAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                                    }
                                    items.add(
                                        PreparedItem.LayoutBlock(
                                            layout = buildStaticLayout(
                                                text = spannable,
                                                paint = bodyPaint,
                                                width = contentWidth,
                                                lineMult = 1.28f,
                                                alignment = lineAlignment
                                            ),
                                            spacingBefore = if (isHeading) 5f else 0f,
                                            spacingAfter = 5f
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val slices = ArrayList<DrawSlice>()
        for (item in items) {
            when (item) {
                is PreparedItem.BlankGap -> {
                    slices.add(DrawSlice(item.height) { _, _ -> })
                }
                is PreparedItem.DividerLine -> {
                    val totalH = item.spacingTop + 1.5f + item.spacingBottom
                    slices.add(
                        DrawSlice(totalH) { canvas, topY ->
                            val lineY = topY + item.spacingTop
                            canvas.drawLine(
                                MARGIN_HORIZONTAL,
                                lineY,
                                PAGE_WIDTH - MARGIN_HORIZONTAL,
                                lineY,
                                dividerPaint
                            )
                        }
                    )
                }
                is PreparedItem.LayoutBlock -> {
                    if (item.spacingBefore > 0f) {
                        slices.add(DrawSlice(item.spacingBefore) { _, _ -> })
                    }
                    val layout = item.layout
                    for (lineIdx in 0 until layout.lineCount) {
                        val lTop = layout.getLineTop(lineIdx)
                        val lBottom = layout.getLineBottom(lineIdx)
                        val lineH = (lBottom - lTop).toFloat().coerceAtLeast(1f)
                        val xOffset = MARGIN_HORIZONTAL + item.indentX

                        slices.add(
                            DrawSlice(lineH) { canvas, topY ->
                                canvas.save()
                                canvas.translate(xOffset, topY - lTop)
                                canvas.clipRect(0f, lTop.toFloat(), layout.width.toFloat(), lBottom.toFloat())
                                layout.draw(canvas)
                                canvas.restore()
                            }
                        )
                    }
                    if (item.spacingAfter > 0f) {
                        slices.add(DrawSlice(item.spacingAfter) { _, _ -> })
                    }
                }
                is PreparedItem.ImageItem -> {
                    val totalH = item.drawHeight + 12f
                    slices.add(
                        DrawSlice(totalH) { canvas, topY ->
                            val dest = RectF(
                                MARGIN_HORIZONTAL,
                                topY + 6f,
                                MARGIN_HORIZONTAL + item.drawWidth,
                                topY + 6f + item.drawHeight
                            )
                            canvas.drawBitmap(item.bitmap, null, dest, null)
                        }
                    )
                }
            }
        }

        val totalContentHeight = slices.sumOf { it.height.toDouble() }.toFloat()
        val requiredSinglePageHeight = (MARGIN_TOP + totalContentHeight + MARGIN_BOTTOM).toInt()

        val maxUsablePerA4Page = A4_PAGE_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM
        val pages = ArrayList<List<DrawSlice>>()
        if (totalContentHeight <= maxUsablePerA4Page) {
            pages.add(slices)
        } else {
            var currentBucket = ArrayList<DrawSlice>()
            var usedH = 0f
            for (slice in slices) {
                if (usedH + slice.height > maxUsablePerA4Page && currentBucket.isNotEmpty()) {
                    pages.add(currentBucket)
                    currentBucket = ArrayList()
                    usedH = 0f
                }
                currentBucket.add(slice)
                usedH += slice.height
            }
            if (currentBucket.isNotEmpty()) {
                pages.add(currentBucket)
            }
        }

        val pdfDocument = PdfDocument()
        var skiaAvailable = true
        try {
            val totalPages = pages.size.coerceAtLeast(1)
            for (pageIndex in pages.indices) {
                val pageSlices = pages[pageIndex]
                val pageHeight = if (totalPages == 1) {
                    requiredSinglePageHeight.coerceIn(MIN_SINGLE_PAGE_HEIGHT, A4_PAGE_HEIGHT)
                } else {
                    A4_PAGE_HEIGHT
                }

                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, pageHeight, pageIndex + 1).create()
                val page = try {
                    pdfDocument.startPage(pageInfo)
                } catch (_: IllegalStateException) {
                    skiaAvailable = false
                    break
                }
                val canvas = page.canvas
                canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), pageHeight.toFloat(), bgPaint)

                var cursorY = MARGIN_TOP
                for (slice in pageSlices) {
                    slice.draw(canvas, cursorY)
                    cursorY += slice.height
                }

                if (totalPages > 1) {
                    val footerLabel = "Page ${pageIndex + 1} / $totalPages"
                    val labelW = footerPaint.measureText(footerLabel)
                    canvas.drawText(
                        footerLabel,
                        PAGE_WIDTH - MARGIN_HORIZONTAL - labelW,
                        pageHeight - 20f,
                        footerPaint
                    )
                }

                pdfDocument.finishPage(page)
            }

            if (skiaAvailable) {
                FileOutputStream(targetFile).buffered().use { out ->
                    pdfDocument.writeTo(out)
                }
            }
        } finally {
            runCatching { pdfDocument.close() }
        }

        if (!skiaAvailable) {
            writePortablePdfFallback(
                targetFile = targetFile,
                title = displayTitle,
                plainBody = blocks.joinToString("\n") { blk -> if (blk is RichNoteBlock.Text) blk.text else "[Image]" },
                singlePageHeight = requiredSinglePageHeight.coerceIn(MIN_SINGLE_PAGE_HEIGHT, A4_PAGE_HEIGHT)
            )
        }

        targetFile
    }

    private fun buildLineSpannable(
        lineText: String,
        lineStart: Int,
        lineEnd: Int,
        spans: List<RichSpan>
    ): CharSequence {
        val sb = SpannableStringBuilder(lineText)
        for (sp in spans) {
            if (sp.end <= lineStart || sp.start >= lineEnd) continue
            val s = (sp.start - lineStart).coerceIn(0, lineText.length)
            val e = (sp.end - lineStart).coerceIn(0, lineText.length)
            if (s >= e) continue
            when (sp.kind) {
                RichSpanKind.ALIGN -> Unit
                RichSpanKind.BOLD -> sb.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                RichSpanKind.ITALIC -> sb.setSpan(StyleSpan(Typeface.ITALIC), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                RichSpanKind.UNDERLINE -> sb.setSpan(UnderlineSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                RichSpanKind.STRIKETHROUGH -> sb.setSpan(StrikethroughSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                RichSpanKind.CODE -> {
                    sb.setSpan(TypefaceSpan("monospace"), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(Color.parseColor("#EDE4D7")), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                RichSpanKind.QUOTE -> {
                    sb.setSpan(StyleSpan(Typeface.ITALIC), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(Color.parseColor("#4A453E")), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                RichSpanKind.H1 -> {
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(1.42f), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                RichSpanKind.H2 -> {
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(1.22f), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                RichSpanKind.H3 -> {
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(1.10f), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                RichSpanKind.TEXT_COLOR -> {
                    val col = runCatching { Color.parseColor(sp.colorHex) }.getOrNull()
                    if (col != null) {
                        sb.setSpan(ForegroundColorSpan(col), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                RichSpanKind.HIGHLIGHT -> {
                    val bg = runCatching { Color.parseColor(sp.colorHex) }.getOrNull()
                    if (bg != null) {
                        sb.setSpan(BackgroundColorSpan(bg), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
        }
        return sb
    }

    private fun buildStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        lineMult: Float,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
    ): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width.coerceAtLeast(60))
            .setAlignment(alignment)
            .setLineSpacing(2f, lineMult)
            .setIncludePad(false)
            .build()
    }

    private fun writePortablePdfFallback(
        targetFile: File,
        title: String,
        plainBody: String,
        singlePageHeight: Int
    ) {
        fun esc(s: String): String = s
            .replace("\\", "\\\\")
            .replace("(", "\\(")
            .replace(")", "\\)")

        val wrappedLines = ArrayList<String>()
        val rawLines = if (plainBody.isBlank()) listOf("No Notes yet") else plainBody.lines()
        for (line in rawLines) {
            if (line.isBlank()) {
                wrappedLines.add("")
            } else {
                line.chunked(78).forEach { wrappedLines.add(it) }
            }
        }

        val linesPerPage = 42
        val lineChunks = if (wrappedLines.isEmpty()) listOf(emptyList()) else wrappedLines.chunked(linesPerPage)
        val totalPages = lineChunks.size
        val mediaHeight = if (totalPages == 1) singlePageHeight else A4_PAGE_HEIGHT

        val objects = ArrayList<String>()
        objects.add("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        objects.add("")
        objects.add("3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")

        val pageObjNums = ArrayList<Int>()
        lineChunks.forEachIndexed { pageIdx, pageLines ->
            val pageObjNum = objects.size + 1
            val contentObjNum = objects.size + 2
            pageObjNums.add(pageObjNum)

            val ops = StringBuilder()
            val y = mediaHeight - 52
            ops.append("BT\n")
            if (pageIdx == 0) {
                ops.append("/F1 18 Tf\n44 $y Td\n(${esc(title)}) Tj\n")
                ops.append("/F1 11 Tf\n0 -24 Td\n")
            } else {
                ops.append("/F1 11 Tf\n44 $y Td\n")
            }
            for (ln in pageLines) {
                ops.append("(${esc(ln)}) Tj\n0 -16 Td\n")
            }
            ops.append("ET\n")
            val streamStr = ops.toString()
            val streamBytes = streamStr.toByteArray(StandardCharsets.US_ASCII)

            objects.add(
                "$pageObjNum 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $PAGE_WIDTH $mediaHeight] " +
                    "/Resources << /Font << /F1 3 0 R >> >> /Contents $contentObjNum 0 R >>\nendobj\n"
            )
            objects.add(
                "$contentObjNum 0 obj\n<< /Length ${streamBytes.size} >>\nstream\n${streamStr}endstream\nendobj\n"
            )
        }

        val kidsRef = pageObjNums.joinToString(" ") { "$it 0 R" }
        objects[1] = "2 0 obj\n<< /Type /Pages /Kids [$kidsRef] /Count $totalPages >>\nendobj\n"

        val pdfBuf = StringBuilder("%PDF-1.4\n")
        val offsets = IntArray(objects.size)
        objects.forEachIndexed { idx, obj ->
            offsets[idx] = pdfBuf.toString().toByteArray(StandardCharsets.US_ASCII).size
            pdfBuf.append(obj)
        }
        val xrefOffset = pdfBuf.toString().toByteArray(StandardCharsets.US_ASCII).size
        pdfBuf.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (off in offsets) {
            pdfBuf.append(String.format("%010d 00000 n \n", off))
        }
        pdfBuf.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")

        FileOutputStream(targetFile).buffered().use { out ->
            out.write(pdfBuf.toString().toByteArray(StandardCharsets.US_ASCII))
            out.flush()
        }
    }
}

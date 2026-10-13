package com.umsify.app.data.local.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Local on-device document text & layout extractor.
 * 100% offline — uses Android PdfRenderer + Google ML Kit on-device Text Recognition.
 * Absolutely ZERO AI model or network calls.
 */
class LocalDocumentExtractor(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractDocument(
        uri: Uri,
        mimeType: String,
        fileName: String,
        scaleMultiplier: Float = 1.0f
    ): ExtractedDocument = withContext(Dispatchers.IO) {
        val isPdf = mimeType.equals("application/pdf", ignoreCase = true) ||
                fileName.endsWith(".pdf", ignoreCase = true)

        if (isPdf) {
            extractFromPdf(uri, scaleMultiplier)
        } else {
            extractFromImage(uri)
        }
    }

    suspend fun extractDocumentFromBytes(
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        scaleMultiplier: Float = 1.0f
    ): ExtractedDocument = withContext(Dispatchers.IO) {
        val isPdf = mimeType.equals("application/pdf", ignoreCase = true) ||
                fileName.endsWith(".pdf", ignoreCase = true) ||
                isPdfBytes(bytes)

        if (isPdf) {
            val tempFile = File.createTempFile("umsify_doc_", ".pdf", context.cacheDir)
            try {
                FileOutputStream(tempFile).use { it.write(bytes) }
                extractFromPdfFile(tempFile, scaleMultiplier)
            } finally {
                tempFile.delete()
            }
        } else {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalArgumentException("Failed to decode image data.")
            try {
                val page = extractFromBitmap(bitmap, 0)
                ExtractedDocument(
                    pages = listOf(page),
                    combinedText = page.fullText
                )
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun isPdfBytes(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
                bytes[0] == '%'.code.toByte() &&
                bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'D'.code.toByte() &&
                bytes[3] == 'F'.code.toByte()
    }

    private fun extractFromPdf(uri: Uri, scaleMultiplier: Float = 1.0f): ExtractedDocument {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Cannot open PDF file descriptor")
        return pfd.use { descriptor ->
            processPdfDescriptor(descriptor, scaleMultiplier)
        }
    }

    private fun extractFromPdfFile(file: File, scaleMultiplier: Float = 1.0f): ExtractedDocument {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return pfd.use { descriptor ->
            processPdfDescriptor(descriptor, scaleMultiplier)
        }
    }

    private fun processPdfDescriptor(descriptor: ParcelFileDescriptor, scaleMultiplier: Float = 1.0f): ExtractedDocument {
        val renderer = PdfRenderer(descriptor)
        val pages = mutableListOf<ExtractedPage>()
        val fullCombinedText = StringBuilder()

        try {
            val pageCount = renderer.pageCount
            for (pageIndex in 0 until pageCount) {
                val page = renderer.openPage(pageIndex)
                try {
                    // Render at high resolution (target ~2000px width for crystal clear OCR)
                    val baseScale = (2000f / page.width.toFloat()).coerceIn(1.5f, 3.0f)
                    val scale = (baseScale * scaleMultiplier).coerceIn(1.5f, 3.5f)
                    val targetWidth = (page.width * scale).toInt()
                    val targetHeight = (page.height * scale).toInt()

                    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                    val extractedPage = extractFromBitmap(bitmap, pageIndex)
                    pages.add(extractedPage)
                    fullCombinedText.append(extractedPage.fullText).append("\n\n")

                    bitmap.recycle()
                } finally {
                    page.close()
                }
            }
        } finally {
            renderer.close()
        }

        return ExtractedDocument(
            pages = pages,
            combinedText = fullCombinedText.toString()
        )
    }

    private fun extractFromImage(uri: Uri): ExtractedDocument {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open image stream")
        val bitmap = inputStream.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalArgumentException("Cannot decode image from Uri")

        return try {
            val page = extractFromBitmap(bitmap, 0)
            ExtractedDocument(
                pages = listOf(page),
                combinedText = page.fullText
            )
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun extractFromImages(uris: List<Uri>): ExtractedDocument = withContext(Dispatchers.IO) {
        val pages = mutableListOf<ExtractedPage>()
        val fullCombinedText = StringBuilder()

        uris.forEachIndexed { index, uri ->
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val bitmap = inputStream.use { BitmapFactory.decodeStream(it) }
                if (bitmap != null) {
                    try {
                        val page = extractFromBitmap(bitmap, index)
                        pages.add(page)
                        fullCombinedText.append(page.fullText).append("\n\n")
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }

        ExtractedDocument(
            pages = pages,
            combinedText = fullCombinedText.toString()
        )
    }

    private fun extractFromBitmap(bitmap: Bitmap, pageIndex: Int): ExtractedPage {
        val image = InputImage.fromBitmap(bitmap, 0)
        val visionText = Tasks.await(recognizer.process(image))

        val blocks = mutableListOf<ExtractedBlock>()
        val allLines = mutableListOf<ExtractedBlock>()

        for (block in visionText.textBlocks) {
            val bounds = block.boundingBox ?: Rect(0, 0, 0, 0)
            blocks.add(
                ExtractedBlock(
                    text = block.text,
                    bounds = bounds,
                    pageIndex = pageIndex,
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom
                )
            )
            for (line in block.lines) {
                val lineBounds = line.boundingBox ?: bounds
                allLines.add(
                    ExtractedBlock(
                        text = line.text,
                        bounds = lineBounds,
                        pageIndex = pageIndex,
                        left = lineBounds.left,
                        top = lineBounds.top,
                        right = lineBounds.right,
                        bottom = lineBounds.bottom
                    )
                )
            }
        }

        // Reconstruct true horizontal rows across table columns
        val sortedLines = allLines.sortedBy { it.centerY }
        val horizontalRows = mutableListOf<MutableList<ExtractedBlock>>()
        for (item in sortedLines) {
            val matchedRow = horizontalRows.firstOrNull { row ->
                val rowCenterY = row.map { it.centerY }.average()
                val rowHeight = row.map { it.height }.average().coerceAtLeast(15.0)
                Math.abs(item.centerY - rowCenterY) < (rowHeight * 0.7)
            }
            if (matchedRow != null) {
                matchedRow.add(item)
            } else {
                horizontalRows.add(mutableListOf(item))
            }
        }

        val reconstructedText = horizontalRows.map { row ->
            row.sortedBy { it.effLeft }.joinToString(" ") { it.text.trim() }
        }.joinToString("\n")

        val combinedPageText = if (reconstructedText.isNotBlank()) {
            reconstructedText + "\n\n" + visionText.text
        } else {
            visionText.text
        }

        // Combine textBlocks and individual cell lines so geometric parser has both cluster and high-precision block coordinates
        val allCandidateBlocks = (blocks + allLines).distinctBy { "${it.text.trim()}|${it.left}|${it.top}" }

        return ExtractedPage(
            pageIndex = pageIndex,
            fullText = combinedPageText,
            blocks = allCandidateBlocks,
            width = bitmap.width,
            height = bitmap.height
        )
    }
}

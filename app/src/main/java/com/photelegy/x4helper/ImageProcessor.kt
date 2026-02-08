package com.photelegy.x4helper

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

object ImageProcessor {
    data class ProcessedResult(
        val bitmap: Bitmap,
        val suggestedName: String
    )

    private const val TARGET_WIDTH = 1872
    private const val TARGET_HEIGHT = 1404

    fun loadBitmap(contentResolver: ContentResolver, uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        }
    }

    fun processForPhoto(bitmap: Bitmap): ProcessedResult {
        val scaled = scaleToTarget(bitmap)
        val grayscale = toGrayscale(scaled)
        return ProcessedResult(grayscale, "x4_photo_${UUID.randomUUID()}")
    }

    fun processForScreenshot(bitmap: Bitmap): ProcessedResult {
        val scaled = scaleToTarget(bitmap)
        val grayscale = toGrayscale(scaled)
        val boosted = applyContrast(grayscale, 1.4f)
        val thresholded = applyThreshold(boosted, 180)
        return ProcessedResult(thresholded, "x4_screenshot_${UUID.randomUUID()}")
    }

    fun saveBitmap(context: Context, bitmap: Bitmap, baseName: String, format: OutputFormat): File {
        val outputDir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        return when (format) {
            OutputFormat.JPG -> {
                val file = File(outputDir, "$baseName.jpg")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                file
            }
            OutputFormat.BMP -> {
                val file = File(outputDir, "$baseName.bmp")
                FileOutputStream(file).use { out ->
                    val bmpData = bmpFromBitmap(bitmap)
                    out.write(bmpData)
                }
                file
            }
            OutputFormat.EPUB -> {
                val file = File(outputDir, "$baseName.epub")
                writeEpub(file, bitmap, "$baseName.jpg")
                file
            }
        }
    }

    private fun scaleToTarget(bitmap: Bitmap): Bitmap {
        val scale = min(
            TARGET_WIDTH.toFloat() / bitmap.width.toFloat(),
            TARGET_HEIGHT.toFloat() / bitmap.height.toFloat()
        )
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        val output = Bitmap.createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        val left = (TARGET_WIDTH - newWidth) / 2f
        val top = (TARGET_HEIGHT - newHeight) / 2f
        canvas.drawBitmap(scaled, left, top, null)
        return output
    }

    private fun toGrayscale(bitmap: Bitmap): Bitmap {
        val grayscale = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscale)
        val paint = Paint()
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return grayscale
    }

    private fun applyContrast(bitmap: Bitmap, contrast: Float): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()
        val scale = contrast
        val translate = (-0.5f * scale + 0.5f) * 255f
        val matrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return out
    }

    private fun applyThreshold(bitmap: Bitmap, threshold: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val gray = Color.red(color)
            val bw = if (gray > threshold) 255 else 0
            pixels[i] = Color.rgb(bw, bw, bw)
        }
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    private fun bmpFromBitmap(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val rowPadding = (4 - (width * 3) % 4) % 4
        val imageSize = (width * 3 + rowPadding) * height
        val headerSize = 54
        val fileSize = headerSize + imageSize

        val buffer = ByteArrayOutputStream(fileSize)
        buffer.write(byteArrayOf(0x42, 0x4D))
        buffer.write(intToByteArray(fileSize))
        buffer.write(intToByteArray(0))
        buffer.write(intToByteArray(headerSize))
        buffer.write(intToByteArray(40))
        buffer.write(intToByteArray(width))
        buffer.write(intToByteArray(height))
        buffer.write(shortToByteArray(1))
        buffer.write(shortToByteArray(24))
        buffer.write(intToByteArray(0))
        buffer.write(intToByteArray(imageSize))
        buffer.write(intToByteArray(2835))
        buffer.write(intToByteArray(2835))
        buffer.write(intToByteArray(0))
        buffer.write(intToByteArray(0))

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (y in height - 1 downTo 0) {
            for (x in 0 until width) {
                val color = pixels[y * width + x]
                buffer.write(Color.blue(color))
                buffer.write(Color.green(color))
                buffer.write(Color.red(color))
            }
            repeat(rowPadding) { buffer.write(0) }
        }
        return buffer.toByteArray()
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }

    private fun writeEpub(file: File, bitmap: Bitmap, imageName: String) {
        val imageStream = ByteArrayOutputStream().apply {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, this)
        }
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            val mimeEntry = ZipEntry("mimetype").apply { method = ZipEntry.STORED }
            val mimeBytes = "application/epub+zip".toByteArray()
            mimeEntry.size = mimeBytes.size.toLong()
            mimeEntry.compressedSize = mimeBytes.size.toLong()
            mimeEntry.crc = java.util.zip.CRC32().apply { update(mimeBytes) }.value
            zip.putNextEntry(mimeEntry)
            zip.write(mimeBytes)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """
                <?xml version=\"1.0\"?>
                <container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">
                    <rootfiles>
                        <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\" />
                    </rootfiles>
                </container>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(
                """
                <?xml version=\"1.0\" encoding=\"UTF-8\"?>
                <package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\">
                    <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">
                        <dc:identifier id=\"bookid\">x4-${UUID.randomUUID()}</dc:identifier>
                        <dc:title>X4 Export</dc:title>
                        <dc:language>de</dc:language>
                    </metadata>
                    <manifest>
                        <item id=\"html\" href=\"index.xhtml\" media-type=\"application/xhtml+xml\" />
                        <item id=\"img\" href=\"images/$imageName\" media-type=\"image/jpeg\" />
                    </manifest>
                    <spine>
                        <itemref idref=\"html\" />
                    </spine>
                </package>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/index.xhtml"))
            zip.write(
                """
                <?xml version=\"1.0\" encoding=\"UTF-8\"?>
                <html xmlns=\"http://www.w3.org/1999/xhtml\">
                    <head>
                        <title>X4 Export</title>
                        <style>body{margin:0;}img{width:100%;height:auto;}</style>
                    </head>
                    <body>
                        <img src=\"images/$imageName\" alt=\"X4 Image\" />
                    </body>
                </html>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/images/$imageName"))
            zip.write(imageStream.toByteArray())
            zip.closeEntry()
        }
    }
}

enum class OutputFormat {
    JPG,
    BMP,
    EPUB
}

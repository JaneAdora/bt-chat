package dev.jane.btchat.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Decodes, rotates (via EXIF, handled by ImageDecoder), downscales, and encodes JPEGs. */
class PhotoProcessor(private val context: Context) {
    class Prepared(val jpeg: ByteArray, val width: Int, val height: Int)

    /** Full-size picked photo -> 1280 px long edge JPEG at quality 80. Throws IOException if undecodable. */
    fun prepare(uri: Uri): Prepared {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.setTargetSampleSize(PhotoScale.sampleSize(info.size.width, info.size.height, PhotoScale.MAX_LONG_EDGE))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } catch (e: ImageDecoder.DecodeException) {
            throw IOException("cannot decode photo: ${e.message}")
        }
        return encode(decoded, PhotoScale.MAX_LONG_EDGE, PhotoScale.JPEG_QUALITY)
    }

    /** A 256 px long edge thumbnail of an already prepared JPEG. */
    fun thumbnail(jpeg: ByteArray): ByteArray {
        val (w, h) = dimensions(jpeg)
        val options = BitmapFactory.Options().apply {
            inSampleSize = PhotoScale.sampleSize(w, h, PhotoScale.THUMB_LONG_EDGE)
        }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            ?: throw IOException("cannot decode thumbnail source")
        return encode(bitmap, PhotoScale.THUMB_LONG_EDGE, PhotoScale.THUMB_QUALITY).jpeg
    }

    fun dimensions(jpeg: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("not an image")
        return options.outWidth to options.outHeight
    }

    private fun encode(bitmap: Bitmap, maxLongEdge: Int, quality: Int): Prepared {
        val (w, h) = PhotoScale.fit(bitmap.width, bitmap.height, maxLongEdge)
        val scaled = if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw IOException("jpeg encode failed")
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return Prepared(out.toByteArray(), w, h)
    }
}

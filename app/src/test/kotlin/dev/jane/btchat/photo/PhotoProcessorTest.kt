package dev.jane.btchat.photo

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoProcessorTest {
    @Test
    fun `a 12MP photo comes out at 1280 wide and under 400KB`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val width = 4000
        val height = 3000
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // A photo-like gradient with per-pixel random jitter: real photos have local
        // correlation (unlike uniform white noise, which is worst-case for JPEG and would
        // never compress under any reasonable size limit), but still carry real texture.
        val random = Random(42)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = (x * 255 / width + random.nextInt(-16, 17)).coerceIn(0, 255)
                val g = (y * 255 / height + random.nextInt(-16, 17)).coerceIn(0, 255)
                val b = (128 + random.nextInt(-16, 17)).coerceIn(0, 255)
                row[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(row, 0, width, 0, y, width, 1)
        }
        val src = File(context.cacheDir, "big.jpg")
        src.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }

        val prepared = PhotoProcessor(context).prepare(Uri.fromFile(src))

        assertEquals(1280, prepared.width)
        assertEquals(960, prepared.height)
        assertTrue("jpeg was ${prepared.jpeg.size} bytes", prepared.jpeg.size < 400_000)
        val thumb = PhotoProcessor(context).thumbnail(prepared.jpeg)
        assertEquals(256 to 192, PhotoProcessor(context).dimensions(thumb))
    }

    @Test
    fun `undecodable bytes raise IOException from prepare, dimensions, and thumbnail`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val garbage = "not an image".toByteArray()
        val src = File(context.cacheDir, "garbage.jpg")
        src.writeBytes(garbage)
        val processor = PhotoProcessor(context)

        try {
            processor.prepare(Uri.fromFile(src))
            fail("expected IOException from prepare()")
        } catch (e: IOException) {
            // expected
        }

        try {
            processor.dimensions(garbage)
            fail("expected IOException from dimensions()")
        } catch (e: IOException) {
            // expected
        }

        try {
            processor.thumbnail(garbage)
            fail("expected IOException from thumbnail()")
        } catch (e: IOException) {
            // expected
        }
    }
}

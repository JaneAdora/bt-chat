package dev.jane.btchat.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoProcessorTest {
    @Test
    fun `a 12MP photo comes out at 1280 wide and under 400KB`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bmp = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        for (x in 0 until 4000 step 97) for (y in 0 until 3000 step 89) bmp.setPixel(x, y, Color.rgb(x % 255, y % 255, 128))
        val src = File(context.cacheDir, "big.jpg")
        src.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }

        val prepared = PhotoProcessor(context).prepare(Uri.fromFile(src))

        assertEquals(1280, prepared.width)
        assertEquals(960, prepared.height)
        assertTrue("jpeg was ${prepared.jpeg.size} bytes", prepared.jpeg.size < 400_000)
        val thumb = PhotoProcessor(context).thumbnail(prepared.jpeg)
        assertEquals(256 to 192, PhotoProcessor(context).dimensions(thumb))
    }
}

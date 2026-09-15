package dev.jane.btchat.photo

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoScaleTest {
    @Test
    fun `landscape 12MP fits to 1280 wide`() {
        assertEquals(1280 to 960, PhotoScale.fit(4000, 3000, 1280))
    }

    @Test
    fun `portrait fits to 1280 tall`() {
        assertEquals(960 to 1280, PhotoScale.fit(3000, 4000, 1280))
    }

    @Test
    fun `small images are not upscaled`() {
        assertEquals(640 to 480, PhotoScale.fit(640, 480, 1280))
    }

    @Test
    fun `odd ratios round and never hit zero`() {
        assertEquals(1280 to 1, PhotoScale.fit(100_000, 10, 1280))
    }

    @Test
    fun `sample size is the largest power of two that keeps the long edge at or above the target`() {
        assertEquals(2, PhotoScale.sampleSize(4000, 3000, 1280))
        assertEquals(4, PhotoScale.sampleSize(8000, 6000, 1280))
        assertEquals(1, PhotoScale.sampleSize(2000, 1500, 1280))
        assertEquals(1, PhotoScale.sampleSize(640, 480, 1280))
        assertEquals(8, PhotoScale.sampleSize(4000, 3000, 256))
    }
}

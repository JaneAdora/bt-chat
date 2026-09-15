package dev.jane.btchat.service

/** Where photo bytes live. The engine and UI never touch the filesystem directly. */
interface PhotoStore {
    class Saved(val photoPath: String, val thumbPath: String, val width: Int, val height: Int)

    /** Store an already downscaled JPEG that this phone is about to send. */
    suspend fun saveOutbound(id: Long, peer: String, jpeg: ByteArray, width: Int, height: Int): Saved

    /** Store a JPEG received from the peer, also exporting it to the gallery. */
    suspend fun saveInbound(id: Long, peer: String, jpeg: ByteArray): Saved

    suspend fun readOutbound(path: String): ByteArray

    /** Copy a stored photo into the device gallery (used by the viewer's Save button). */
    suspend fun exportToGallery(path: String)
}

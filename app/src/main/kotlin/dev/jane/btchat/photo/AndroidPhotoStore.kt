package dev.jane.btchat.photo

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import dev.jane.btchat.service.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Photos live in the app's private files dir: photos/<peer>-<id>.jpg and thumbs/<peer>-<id>.jpg.
 * The peer address is part of the file name because message ids are only unique per peer.
 * Inbound photos are also written to the gallery album "BT Chat" via MediaStore.
 */
class AndroidPhotoStore(
    private val context: Context,
    private val processor: PhotoProcessor,
) : PhotoStore {
    private val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
    private val thumbsDir = File(context.filesDir, "thumbs").apply { mkdirs() }

    override suspend fun saveOutbound(id: Long, peer: String, jpeg: ByteArray, width: Int, height: Int): PhotoStore.Saved =
        withContext(Dispatchers.IO) { write(id, peer, jpeg, width, height) }

    override suspend fun saveInbound(id: Long, peer: String, jpeg: ByteArray): PhotoStore.Saved = withContext(Dispatchers.IO) {
        val (w, h) = processor.dimensions(jpeg)
        val saved = write(id, peer, jpeg, w, h)
        runCatching { exportToGallery(saved.photoPath) }
            .onFailure { Log.w(TAG, "exportToGallery failed for ${saved.photoPath}", it) }
        saved
    }

    override suspend fun readOutbound(path: String): ByteArray = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile) throw IOException("missing photo $path")
        file.readBytes()
    }

    override suspend fun exportToGallery(path: String) = withContext(Dispatchers.IO) {
        val file = File(path)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "btchat-${file.nameWithoutExtension}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BT Chat")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("cannot open $uri")
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Unit
    }

    override suspend fun deleteForPeer(peer: String) = withContext(Dispatchers.IO) {
        val prefix = "${peer.replace(":", "")}-"
        for (dir in listOf(photosDir, thumbsDir)) {
            dir.listFiles { file -> file.name.startsWith(prefix) }?.forEach { it.delete() }
        }
        Unit
    }

    private fun write(id: Long, peer: String, jpeg: ByteArray, width: Int, height: Int): PhotoStore.Saved {
        val name = "${peer.replace(":", "")}-$id.jpg"
        val photo = File(photosDir, name)
        val thumb = File(thumbsDir, name)
        photo.writeBytes(jpeg)
        thumb.writeBytes(processor.thumbnail(jpeg))
        return PhotoStore.Saved(photo.path, thumb.path, width, height)
    }

    private companion object {
        const val TAG = "AndroidPhotoStore"
    }
}

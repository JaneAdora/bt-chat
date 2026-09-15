package dev.jane.btchat.service

import java.io.File

class FakePhotoStore(private val dir: File) : PhotoStore {
    val exported = mutableListOf<String>()

    init {
        dir.mkdirs()
    }

    override suspend fun saveOutbound(id: Long, jpeg: ByteArray, width: Int, height: Int): PhotoStore.Saved {
        val photo = File(dir, "$id.jpg").apply { writeBytes(jpeg) }
        val thumb = File(dir, "$id.thumb.jpg").apply { writeBytes(jpeg) }
        return PhotoStore.Saved(photo.path, thumb.path, width, height)
    }

    override suspend fun saveInbound(id: Long, jpeg: ByteArray): PhotoStore.Saved {
        val saved = saveOutbound(id, jpeg, 1280, 960)
        exported += saved.photoPath
        return saved
    }

    override suspend fun readOutbound(path: String): ByteArray = File(path).readBytes()

    override suspend fun exportToGallery(path: String) {
        exported += path
    }
}

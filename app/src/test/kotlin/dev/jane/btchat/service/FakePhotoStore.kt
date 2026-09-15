package dev.jane.btchat.service

import java.io.File

class FakePhotoStore(private val dir: File) : PhotoStore {
    val exported = mutableListOf<String>()

    /** When true, saveInbound throws IOException instead of saving, to simulate undecodable bytes. */
    var failInbound = false

    init {
        dir.mkdirs()
    }

    override suspend fun saveOutbound(id: Long, peer: String, jpeg: ByteArray, width: Int, height: Int): PhotoStore.Saved {
        val name = "${peer.replace(":", "")}-$id"
        val photo = File(dir, "$name.jpg").apply { writeBytes(jpeg) }
        val thumb = File(dir, "$name.thumb.jpg").apply { writeBytes(jpeg) }
        return PhotoStore.Saved(photo.path, thumb.path, width, height)
    }

    override suspend fun saveInbound(id: Long, peer: String, jpeg: ByteArray): PhotoStore.Saved {
        if (failInbound) throw java.io.IOException("undecodable bytes")
        val saved = saveOutbound(id, peer, jpeg, 1280, 960)
        exported += saved.photoPath
        return saved
    }

    override suspend fun readOutbound(path: String): ByteArray = File(path).readBytes()

    override suspend fun exportToGallery(path: String) {
        exported += path
    }

    override suspend fun deleteForPeer(peer: String) {
        val prefix = "${peer.replace(":", "")}-"
        dir.listFiles { file -> file.name.startsWith(prefix) }?.forEach { it.delete() }
    }
}

package dev.jane.btchat.ui

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Scratch files for the camera capture intent. The processor copies the result into the photo store. */
object CameraFiles {
    fun newUri(context: Context): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
        val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}

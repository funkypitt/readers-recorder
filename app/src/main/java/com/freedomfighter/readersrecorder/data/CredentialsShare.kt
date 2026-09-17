package com.freedomfighter.readersrecorder.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** The credentials file handed to the share menu (Telegram, kDrive, Drive, e-mail…) and cleaned up after. */
object CredentialsShare {
    private fun dir(context: Context) = File(context.cacheDir, "credentials")

    /** Leftovers hold a password: gone at the next export and at every start. */
    fun cleanUp(context: Context) { dir(context).listFiles()?.forEach { it.delete() } }

    fun share(context: Context, json: String, chooserTitle: String) {
        cleanUp(context)
        val file = File(dir(context).apply { mkdirs() }, Credentials.FILE_NAME)
        file.writeText(json)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, Credentials.FILE_NAME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(Credentials.FILE_NAME, uri)
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    fun readText(context: Context, uri: android.net.Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
}

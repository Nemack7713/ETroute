package com.etroute.app.storage

import android.content.Context
import android.content.Intent
import android.net.Uri

class DocumentGateway(private val context: Context) {
    fun openDocumentIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
    }

    fun createDocumentIntent(suggestedName: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }

    fun read(uri: Uri): ByteArray =
        requireNotNull(context.contentResolver.openInputStream(uri)) {
            "Unable to open document for reading"
        }.use { it.readBytes() }

    fun write(uri: Uri, bytes: ByteArray) {
        requireNotNull(context.contentResolver.openOutputStream(uri, "w")) {
            "Unable to open document for writing"
        }.use { it.write(bytes) }
    }
}

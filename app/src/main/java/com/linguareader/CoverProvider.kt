package com.linguareader

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Serves book covers to Android Auto, which shows them in its Continue and Library screens. Not
 * exported: each cover URI is granted read access for the car app only ([uri]).
 * content://com.linguareader.covers/<book id>
 */
class CoverProvider : ContentProvider() {
    companion object {
        private const val AUTHORITY = "com.linguareader.covers"
        /** Android Auto's phone app, which loads browse-item artwork. */
        private val CAR_PACKAGES = listOf("com.google.android.projection.gearhead")

        /** The cover URI for [book], readable by Android Auto; null if the book has no cover. */
        fun uri(context: Context, store: BookStore, book: Book): Uri? {
            if (store.coverFile(book) == null) return null
            val uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(book.id).build()
            CAR_PACKAGES.forEach { runCatching { context.grantUriPermission(it, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            return uri
        }
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Covers are read-only")
        val context = context ?: throw FileNotFoundException()
        val id = uri.lastPathSegment ?: throw FileNotFoundException()
        val store = BookStore.get(context)
        val book = store.books().firstOrNull { it.id == id } ?: throw FileNotFoundException()
        val file = store.coverFile(book) ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/*"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

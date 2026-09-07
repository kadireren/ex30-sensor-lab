package com.kadireren.ex30sensorlab.logging

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

object DownloadStorage {
    const val FOLDER_NAME = "EX30SensorLab"

    fun displayPath(): String = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME"

    sealed class Entry {
        abstract val name: String

        data class Media(val displayName: String, val uri: Uri) : Entry() {
            override val name: String get() = displayName
        }

        data class Legacy(val file: File) : Entry() {
            override val name: String get() = file.name
        }
    }

    data class ExportedFile(val name: String, val folderPath: String = displayPath())

    fun listJsonFiles(context: Context): List<Entry> {
        val seen = linkedSetOf<String>()
        val entries = mutableListOf<Entry>()
        listJsonFromMediaStore(context, seen, entries)
        listJsonFromLegacyFolder(seen, entries)
        return entries.sortedBy { it.name.lowercase() }
    }

    fun readText(context: Context, entry: Entry): String = when (entry) {
        is Entry.Media -> context.contentResolver.openInputStream(entry.uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Dosya okunamadı: ${entry.name}")
        is Entry.Legacy -> entry.file.readText()
    }

    fun exportFile(context: Context, source: File, targetName: String = source.name): ExportedFile {
        require(source.isFile) { "Kaynak dosya yok: ${source.name}" }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, targetName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType(targetName))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME/")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Download klasörüne yazılamadı")
        resolver.openOutputStream(uri).use { output ->
            requireNotNull(output) { "Download çıktı akışı açılamadı" }
            FileInputStream(source).use { input -> input.copyTo(output) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val published = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, published, null, null)
        }
        return ExportedFile(targetName)
    }

    private fun listJsonFromMediaStore(context: Context, seen: MutableSet<String>, entries: MutableList<Entry>) {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH,
        )
        val selection = buildString {
            append("(${MediaStore.Downloads.RELATIVE_PATH} LIKE ? OR ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?)")
            append(" AND lower(${MediaStore.Downloads.DISPLAY_NAME}) LIKE ?")
        }
        val args = arrayOf(
            "%/$FOLDER_NAME/%",
            "%/$FOLDER_NAME",
            "%.json",
        )
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.Downloads.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                if (!seen.add(name)) continue
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                entries += Entry.Media(name, uri)
            }
        }
    }

    private fun listJsonFromLegacyFolder(seen: MutableSet<String>, entries: MutableList<Entry>) {
        val folder = legacyFolder()
        folder.mkdirs()
        folder.listFiles { file -> file.isFile && file.name.endsWith(".json", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { file ->
                if (seen.add(file.name)) entries += Entry.Legacy(file)
            }
    }

    private fun legacyFolder(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER_NAME)

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "csv" -> "text/csv"
        "json" -> "application/json"
        "jsonl" -> "application/x-ndjson"
        else -> "application/octet-stream"
    }
}

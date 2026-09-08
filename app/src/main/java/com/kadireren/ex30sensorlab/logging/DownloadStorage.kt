package com.kadireren.ex30sensorlab.logging

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

object DownloadStorage {
    const val FOLDER_NAME = "EX30SensorLab"
    private const val DOWNLOAD_SEGMENT = "Download"

    fun displayPath(): String = "$DOWNLOAD_SEGMENT/$FOLDER_NAME"

    sealed class Entry {
        abstract val name: String

        data class Media(val displayName: String, val uri: Uri) : Entry() {
            override val name: String get() = displayName
        }

        data class Legacy(val file: File) : Entry() {
            override val name: String get() = file.name
        }
    }

    data class ExportedFile(
        val name: String,
        val folderPath: String = displayPath(),
        val absolutePath: String = "",
    )

    fun listJsonFiles(context: Context): List<Entry> {
        val seen = linkedSetOf<String>()
        val entries = mutableListOf<Entry>()
        listJsonFromDirectFiles(seen, entries)
        listJsonFromMediaStore(context, seen, entries)
        return entries.sortedBy { it.name.lowercase() }
    }

    fun scanDiagnostics(context: Context): String = buildString {
        appendLine("Aranan Download konumları:")
        downloadRoots().forEach { root ->
            appendLine("• ${root.absolutePath}")
            appendLine("  var=${root.exists()} okunur=${root.canRead()} yazılır=${root.canWrite()}")
            val lab = File(root, FOLDER_NAME)
            if (lab.exists()) {
                appendLine("  $FOLDER_NAME içeriği: ${lab.listFiles()?.joinToString { it.name } ?: "—"}")
            }
            root.listFiles { file -> file.isFile && file.name.endsWith(".json", ignoreCase = true) }
                ?.forEach { appendLine("  json: ${it.name}") }
        }
        appendLine()
        append("Bulunan profil sayısı: ${listJsonFiles(context).size}")
    }

    fun readText(context: Context, entry: Entry): String = when (entry) {
        is Entry.Media -> context.contentResolver.openInputStream(entry.uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Dosya okunamadı: ${entry.name}")
        is Entry.Legacy -> entry.file.readText()
    }

    fun exportFile(context: Context, source: File, targetName: String = source.name): ExportedFile {
        require(source.isFile) { "Kaynak dosya yok: ${source.name}" }
        val folder = writableLabFolder() ?: return exportViaMediaStore(context, source, targetName)
        folder.mkdirs()
        val target = File(folder, targetName)
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return ExportedFile(
            name = targetName,
            folderPath = "${folder.parentFile?.name ?: DOWNLOAD_SEGMENT}/$FOLDER_NAME",
            absolutePath = target.absolutePath,
        )
    }

    private fun exportViaMediaStore(context: Context, source: File, targetName: String): ExportedFile {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, targetName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType(targetName))
            put(MediaStore.Downloads.RELATIVE_PATH, "$DOWNLOAD_SEGMENT/$FOLDER_NAME/")
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

    private fun listJsonFromDirectFiles(seen: MutableSet<String>, entries: MutableList<Entry>) {
        downloadRoots().forEach { root ->
            collectJsonFiles(root, seen, entries)
            collectJsonFiles(File(root, FOLDER_NAME), seen, entries)
        }
    }

    private fun collectJsonFiles(folder: File, seen: MutableSet<String>, entries: MutableList<Entry>) {
        if (!folder.isDirectory || !folder.canRead()) return
        folder.listFiles { file -> file.isFile && file.name.endsWith(".json", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { file ->
                if (seen.add(file.name)) entries += Entry.Legacy(file)
            }
    }

    private fun listJsonFromMediaStore(context: Context, seen: MutableSet<String>, entries: MutableList<Entry>) {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH,
        )
        val selection = buildString {
            append("lower(${MediaStore.Downloads.DISPLAY_NAME}) LIKE ?")
            append(" AND (")
            append("${MediaStore.Downloads.RELATIVE_PATH} LIKE ?")
            append(" OR ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?")
            append(" OR ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?")
            append(" OR ${MediaStore.Downloads.RELATIVE_PATH} IS NULL")
            append(")")
        }
        val args = arrayOf(
            "%.json",
            "%/$FOLDER_NAME/%",
            "%/$FOLDER_NAME",
            "%$DOWNLOAD_SEGMENT/%",
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

    private fun writableLabFolder(): File? =
        downloadRoots().firstOrNull { root ->
            (root.exists() || root.mkdirs()) && root.canWrite()
        }?.let { root ->
            File(root, FOLDER_NAME).also { lab -> lab.mkdirs() }.takeIf { it.canWrite() }
        }

    private fun downloadRoots(): List<File> {
        val userId = Process.myUid() / 100_000
        @Suppress("DEPRECATION")
        val legacyPublic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return listOf(
            File("/storage/self/primary/$DOWNLOAD_SEGMENT"),
            File("/storage/emulated/$userId/$DOWNLOAD_SEGMENT"),
            File("/sdcard/$DOWNLOAD_SEGMENT"),
            legacyPublic,
        ).distinctBy { it.absolutePath }
    }

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "csv" -> "text/csv"
        "json" -> "application/json"
        "jsonl" -> "application/x-ndjson"
        else -> "application/octet-stream"
    }
}

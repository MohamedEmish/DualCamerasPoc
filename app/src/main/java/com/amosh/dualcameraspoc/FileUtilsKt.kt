package com.amosh.dualcameraspoc

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

object FileUtilsKt {

    @JvmStatic
    fun getFileUri(file: File?, context: Context): Uri? {
        return when {
            file == null -> null
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> saveImageInQ(
                filename = file.name,
                file,
                context
            )
            else -> saveImageInLegacy(file, context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveImageInQ(filename: String, file: File, context: Context): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/QR_CODE_FILE_EXT")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + MY_DIRECTORY
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri =
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            resolver.openOutputStream(uri)?.use {
                try {
                    FileInputStream(file).use { inputStream ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val sz = inputStream.read(buf)
                            if (sz <= 0) break
                            it.write(buf, 0, sz)
                        }
                    }
                    it.flush()
                    it.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }

        return uri
    }

    private fun saveImageInLegacy(
        file: File,
        context: Context,
    ): Uri? {
        val direct = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                .toString() + MY_DIRECTORY
        )
        if (!direct.exists()) {
            direct.mkdirs()
        }
        val out: FileOutputStream
        try {
            out = FileOutputStream(file, false)
            try {
                out.flush()
                out.close()
                return Uri.fromFile(file)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        }
        return null
    }

    fun getPath(context: Context, uri: Uri): String? {
        val selection: String?
        val selectionArgs: Array<String>?
        // DocumentProvider

        // ExternalStorageProvider
        if (isExternalStorageDocument(uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            val split = docId.split(":".toRegex()).toTypedArray()
            val type = split[0]
            val fullPath: String = getPathFromExtSD(split) ?: ""
            return if (fullPath !== "") {
                fullPath
            } else {
                null
            }
        }


        // DownloadsProvider
        if (isDownloadsDocument(uri)) {
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val fileName = cursor.getString(0)
                    val path = Environment.getExternalStorageDirectory().toString() + "/Download/" + fileName
                    if (!TextUtils.isEmpty(path)) {
                        return path
                    }
                }
            } finally {
                cursor?.close()
            }
            val id: String = DocumentsContract.getDocumentId(uri)
            if (!TextUtils.isEmpty(id)) {
                if (id.startsWith("raw:")) {
                    return id.replaceFirst("raw:".toRegex(), "")
                }
                val contentUriPrefixesToTry = arrayOf(
                    "content://downloads/public_downloads",
                    "content://downloads/my_downloads"
                )
                for (contentUriPrefix in contentUriPrefixesToTry) {
                    return try {
                        val contentUri = ContentUris.withAppendedId(Uri.parse(contentUriPrefix), java.lang.Long.valueOf(id))
                        getDataColumn(context, contentUri, null, null)
                    } catch (e: NumberFormatException) {
                        //In Android 8 and Android P the id is not a number
                        uri.path!!.replaceFirst("^/document/raw:".toRegex(), "").replaceFirst("^raw:".toRegex(), "")
                    }
                }
            }
        }


        // MediaProvider
        if (isMediaDocument(uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            val split = docId.split(":".toRegex()).toTypedArray()
            val contentUri: Uri? = when (split[0]) {
                "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> null
            }
            selection = "_id=?"
            selectionArgs = arrayOf(split[1])
            return getDataColumn(context, contentUri ?: return null, selection,
                selectionArgs)
        }
        if (isGoogleDriveUri(uri)) {
            return getDriveFilePath(context, uri)
        }
        if (isWhatsAppFile(uri)) {
            return getFilePathForWhatsApp(context, uri)
        }
        if ("content".equals(uri.scheme, ignoreCase = true)) {
            if (isGooglePhotosUri(uri)) {
                return uri.lastPathSegment
            }
            if (isGoogleDriveUri(uri)) {
                return getDriveFilePath(context, uri)
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                // return getFilePathFromURI(context,uri);
                copyFileToInternalStorage(context, uri, "userfiles")
                // return getRealPathFromURI(context,uri);
            } else {
                getDataColumn(context, uri, null, null)
            }
        }
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }
        return null
    }

    private fun fileExists(filePath: String): Boolean {
        val file = File(filePath)
        return file.exists()
    }

    private fun getPathFromExtSD(pathData: Array<String>): String? {
        val type = pathData[0]
        val relativePath = "/" + pathData[1]
        var fullPath = ""

        // on my Sony devices (4.4.4 & 5.1.1), `type` is a dynamic string
        // something like "71F8-2C0A", some kind of unique id per storage
        // don't know any API that can get the root path of that storage based on its id.
        //
        // so no "primary" type, but let the check here for other devices
        if ("primary".equals(type, ignoreCase = true)) {
            fullPath = Environment.getExternalStorageDirectory().toString() + relativePath
            if (fileExists(fullPath)) {
                return fullPath
            }
        }

        // Environment.isExternalStorageRemovable() is `true` for external and internal storage
        // so we cannot relay on it.
        //
        // instead, for each possible path, check if file exists
        // we'll start with secondary storage as this could be our (physically) removable sd card
        fullPath = System.getenv("SECONDARY_STORAGE") + relativePath
        if (fileExists(fullPath)) {
            return fullPath
        }
        fullPath = System.getenv("EXTERNAL_STORAGE") + relativePath
        return if (fileExists(fullPath)) {
            fullPath
        } else fullPath
    }

    private fun getDriveFilePath(context: Context, uri: Uri): String? {
        val returnCursor: Cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
        /*
         * Get the column indexes of the data in the Cursor,
         *     * move to the first row in the Cursor, get the data,
         *     * and display it.
         * */
        val nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = returnCursor.getColumnIndex(OpenableColumns.SIZE)
        returnCursor.moveToFirst()
        val name = returnCursor.getString(nameIndex)
        val size = returnCursor.getLong(sizeIndex).toString()
        val file: File = File(context.cacheDir, name)
        try {
            val inputStream: InputStream = context.contentResolver.openInputStream(uri) ?: return null
            val outputStream = FileOutputStream(file)
            var read = 0
            val maxBufferSize = 1 * 1024 * 1024
            val bytesAvailable = inputStream.available()

            //int bufferSize = 1024;
            val bufferSize = bytesAvailable.coerceAtMost(maxBufferSize)
            val buffers = ByteArray(bufferSize)
            while (inputStream.read(buffers).also { read = it } != -1) {
                outputStream.write(buffers, 0, read)
            }
            Log.e("File Size", "Size " + file.length())
            inputStream.close()
            outputStream.close()
            Log.e("File Path", "Path " + file.path)
            Log.e("File Size", "Size " + file.length())
        } catch (e: java.lang.Exception) {
            Log.e("Exception", e.message!!)
        }
        return file.path
    }

    /***
     * Used for Android Q+
     * @param uri
     * @param newDirName if you want to create a directory, you can set this variable
     * @return
     */
    private fun copyFileToInternalStorage(context: Context, uri: Uri, newDirName: String): String? {
        val returnCursor: Cursor = context.contentResolver.query(uri, arrayOf(
            OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE
        ), null, null, null) ?: return null


        /*
         * Get the column indexes of the data in the Cursor,
         *     * move to the first row in the Cursor, get the data,
         *     * and display it.
         * */
        val nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = returnCursor.getColumnIndex(OpenableColumns.SIZE)
        returnCursor.moveToFirst()
        val name = returnCursor.getString(nameIndex)
        val size = returnCursor.getLong(sizeIndex).toString()
        val output: File = when {
            newDirName != "" -> {
                val dir = File(context.filesDir.toString() + "/" + newDirName)
                if (!dir.exists()) {
                    dir.mkdir()
                }
                File(context.filesDir.toString() + "/" + newDirName + "/" + name)
            }
            else -> {
                File(context.filesDir.toString() + "/" + name)
            }
        }
        try {
            val inputStream: InputStream = context.getContentResolver().openInputStream(uri) ?: return null
            val outputStream = FileOutputStream(output)
            var read = 0
            val bufferSize = 1024
            val buffers = ByteArray(bufferSize)
            while (inputStream.read(buffers).also { read = it } != -1) {
                outputStream.write(buffers, 0, read)
            }
            inputStream.close()
            outputStream.close()
        } catch (e: java.lang.Exception) {
            Log.e("Exception", e.message!!)
        }
        return output.path
    }

    private fun getFilePathForWhatsApp(context: Context, uri: Uri): String? {
        return copyFileToInternalStorage(context, uri, "whatsapp")
    }

    private fun getDataColumn(context: Context, uri: Uri, selection: String?, selectionArgs: Array<String>?): String? {
        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(column)
        try {
            cursor = context.contentResolver.query(uri, projection,
                selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(index)
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    private fun isGooglePhotosUri(uri: Uri): Boolean {
        return "com.google.android.apps.photos.content" == uri.authority
    }

    fun isWhatsAppFile(uri: Uri): Boolean {
        return "com.whatsapp.provider.media" == uri.authority
    }

    private fun isGoogleDriveUri(uri: Uri): Boolean {
        return "com.google.android.apps.docs.storage" == uri.authority || "com.google.android.apps.docs.storage.legacy" == uri.authority
    }

    fun writeFileOnInternalStorage(context: Context): File? {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), MY_DIRECTORY)
        var gpxfile: File? = null
        if (!dir.exists()) {
            dir.mkdir()
        }
        try {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            gpxfile = File(dir, "VID_combined_${sdf.format(Date())}.$QR_CODE_FILE_EXT")
            val writer = FileWriter(gpxfile)
            writer.append("")
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return gpxfile
    }
}

const val MY_DIRECTORY = "/Dual_Camera_Docs"
private const val QR_CODE_FILENAME = "recorded_video_"
const val QR_CODE_FILE_EXT = "mp4"
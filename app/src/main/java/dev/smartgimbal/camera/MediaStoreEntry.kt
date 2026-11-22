package dev.smartgimbal.camera

import android.content.ContentValues
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** File name for a new photo or video, e.g. `SmartGimbal_20261122_143000`. */
internal fun newMediaName(): String =
    "SmartGimbal_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())

/** MediaStore row for a new file; the column names are the same for images and videos. */
internal fun mediaValues(name: String, mimeType: String, relativePath: String) = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
}

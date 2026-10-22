package com.localbots.app.util

import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

object FileUtils {

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        val value = bytes / 1024.0.pow(index.toDouble())
        return DecimalFormat("#,##0.#").format(value) + " " + units[index]
    }
}

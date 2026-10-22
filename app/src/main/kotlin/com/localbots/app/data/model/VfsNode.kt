package com.localbots.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class VfsNode(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val lastModified: Long = System.currentTimeMillis(),
    val extension: String = "",
    val children: List<VfsNode> = emptyList()
) {
    val isCode: Boolean
        get() = extension in listOf("kt", "java", "py", "ts", "js", "cpp", "c", "h", "hpp", "sh", "json", "xml", "yaml", "yml", "html", "css")

    val isImage: Boolean
        get() = extension in listOf("png", "jpg", "jpeg", "webp", "gif", "svg", "bmp")

    val isText: Boolean
        get() = extension in listOf("txt", "md", "csv", "log") || isCode
}

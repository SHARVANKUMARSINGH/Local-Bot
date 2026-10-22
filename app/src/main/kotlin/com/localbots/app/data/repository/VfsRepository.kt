package com.localbots.app.data.repository

import com.localbots.app.data.model.VfsNode
import java.io.File

class VfsRepository {

    /**
     * Resolve a relative path inside the isolated project workspace safely.
     * Prevents directory traversal attacks (e.g. "../../../usr/bin").
     */
    private fun resolveSafeFile(workspacePath: String, relativePath: String): File {
        val rootDir = File(workspacePath).canonicalFile
        val target = File(rootDir, relativePath.trimStart('/')).canonicalFile
        if (!target.path.startsWith(rootDir.path)) {
            throw SecurityException("Access denied: Path '$relativePath' attempts to escape the isolated project VFS.")
        }
        return target
    }

    fun listDirectory(workspacePath: String, relativePath: String = ""): List<VfsNode> {
        val dir = resolveSafeFile(workspacePath, relativePath)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val files = dir.listFiles() ?: return emptyList()
        val rootCanonical = File(workspacePath).canonicalPath

        return files.map { file ->
            val rel = file.canonicalPath.removePrefix(rootCanonical).trimStart('/')
            val ext = if (file.isDirectory) "" else file.extension.lowercase()
            VfsNode(
                name = file.name,
                relativePath = rel,
                isDirectory = file.isDirectory,
                sizeBytes = if (file.isDirectory) 0L else file.length(),
                lastModified = file.lastModified(),
                extension = ext
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun getFullTree(workspacePath: String, relativePath: String = ""): VfsNode {
        val dir = resolveSafeFile(workspacePath, relativePath)
        val rootCanonical = File(workspacePath).canonicalPath
        val rel = dir.canonicalPath.removePrefix(rootCanonical).trimStart('/')

        val children = if (dir.isDirectory) {
            dir.listFiles()?.map { file ->
                val childRel = file.canonicalPath.removePrefix(rootCanonical).trimStart('/')
                if (file.isDirectory) {
                    getFullTree(workspacePath, childRel)
                } else {
                    VfsNode(
                        name = file.name,
                        relativePath = childRel,
                        isDirectory = false,
                        sizeBytes = file.length(),
                        lastModified = file.lastModified(),
                        extension = file.extension.lowercase()
                    )
                }
            }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        } else {
            emptyList()
        }

        return VfsNode(
            name = if (rel.isEmpty()) "workspace" else dir.name,
            relativePath = rel,
            isDirectory = true,
            children = children
        )
    }

    fun pushText(workspacePath: String, relativePath: String, content: String): Boolean {
        val file = resolveSafeFile(workspacePath, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return true
    }

    fun appendText(workspacePath: String, relativePath: String, content: String): Boolean {
        val file = resolveSafeFile(workspacePath, relativePath)
        file.parentFile?.mkdirs()
        file.appendText(content, Charsets.UTF_8)
        return true
    }

    fun readFile(workspacePath: String, relativePath: String): String {
        val file = resolveSafeFile(workspacePath, relativePath)
        if (!file.exists()) throw NoSuchFileException(file, reason = "File does not exist in VFS.")
        return file.readText(Charsets.UTF_8)
    }

    fun createFolder(workspacePath: String, relativePath: String): Boolean {
        val dir = resolveSafeFile(workspacePath, relativePath)
        return dir.mkdirs()
    }

    fun deleteItem(workspacePath: String, relativePath: String): Boolean {
        val target = resolveSafeFile(workspacePath, relativePath)
        return if (target.isDirectory) {
            target.deleteRecursively()
        } else {
            target.delete()
        }
    }

    fun renameMove(workspacePath: String, sourcePath: String, destinationPath: String): Boolean {
        val src = resolveSafeFile(workspacePath, sourcePath)
        val dest = resolveSafeFile(workspacePath, destinationPath)
        dest.parentFile?.mkdirs()
        return src.renameTo(dest)
    }

    fun searchFiles(workspacePath: String, query: String, isRegex: Boolean = false): List<VfsNode> {
        val root = resolveSafeFile(workspacePath, "")
        val results = mutableListOf<VfsNode>()
        val rootCanonical = root.canonicalPath

        val regex = if (isRegex) {
            try { Regex(query, RegexOption.IGNORE_CASE) } catch (e: Exception) { Regex.fromLiteral(query) }
        } else {
            Regex(".*${Regex.escape(query)}.*", RegexOption.IGNORE_CASE)
        }

        root.walkTopDown().forEach { file ->
            if (file != root) {
                val matchesName = regex.containsMatchIn(file.name)
                val matchesContent = if (!file.isDirectory && file.length() < 1_000_000) {
                    try { regex.containsMatchIn(file.readText(Charsets.UTF_8)) } catch (e: Exception) { false }
                } else false

                if (matchesName || matchesContent) {
                    val rel = file.canonicalPath.removePrefix(rootCanonical).trimStart('/')
                    results.add(
                        VfsNode(
                            name = file.name,
                            relativePath = rel,
                            isDirectory = file.isDirectory,
                            sizeBytes = if (file.isDirectory) 0L else file.length(),
                            lastModified = file.lastModified(),
                            extension = if (file.isDirectory) "" else file.extension.lowercase()
                        )
                    )
                }
            }
        }
        return results
    }
}

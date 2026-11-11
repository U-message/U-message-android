package org.telegram.configuration

import java.io.File
import java.io.IOException

/** Only pass an app-owned, dedicated directory, never the whole app data root. */
object LocalDirectory {
    @JvmStatic
    fun size(directory: File): Long = entries(directory).sumOf { if (it.isFile) it.length() else 0L }

    @JvmStatic
    fun clear(directory: File) {
        // Validate the complete tree before deleting anything. Refuse links and unreadable paths.
        val entries = entries(directory)
        entries.asReversed().forEach { file ->
            if (file.exists() && !file.delete()) throw IOException("Local file could not be removed")
        }
        if (directory.exists() && directory.listFiles()?.isNotEmpty() != false) {
            throw IOException("Local directory is not empty")
        }
    }

    private fun entries(directory: File): List<File> {
        val absolute = directory.absoluteFile.normalize()
        val parent = absolute.parentFile ?: throw IOException("A filesystem root is not a data directory")
        val root = File(parent.canonicalFile, absolute.name)
        if (root.canonicalFile != root) throw IOException("Linked directory is not allowed")
        if (!root.exists()) return emptyList()
        if (!root.isDirectory) throw IOException("Expected a local directory")
        val result = ArrayList<File>()
        fun visit(parent: File, depth: Int) {
            if (depth > 64) throw IOException("Local directory depth exceeded")
            val children = parent.listFiles() ?: throw IOException("Local directory cannot be read")
            for (child in children) {
                if (child.canonicalFile != child.absoluteFile.normalize()) {
                    throw IOException("Linked file is not allowed")
                }
                result.add(child)
                if (child.isDirectory) visit(child, depth + 1)
            }
        }
        visit(root, 0)
        return result
    }
}

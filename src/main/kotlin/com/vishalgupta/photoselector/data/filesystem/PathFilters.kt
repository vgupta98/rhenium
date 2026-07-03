package com.vishalgupta.photoselector.data.filesystem

import com.vishalgupta.photoselector.domain.model.RootFolder
import java.nio.file.Path
import kotlin.io.path.name

object PathFilters {
    private val systemDirNames = setOf("__MACOSX")

    fun isHiddenOrSystem(path: Path): Boolean {
        val name = path.name
        if (name.isEmpty()) return false
        if (name.startsWith(".")) return true
        if (name in systemDirNames) return true
        return false
    }

    fun shouldExclude(file: Path, root: RootFolder): Boolean {
        if (isHiddenOrSystem(file)) return true
        if (file == root.favouritesFile) return true
        if (file == root.positionFile) return true
        if (file == root.xmpSyncFile) return true
        return false
    }
}

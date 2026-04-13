package com.github.pderakhshanfar.codecocoonplugin.intellij.vfs

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import okio.Path.Companion.toPath
import java.nio.file.Path


fun String.refreshAndFindVirtualFile(): VirtualFile? = LocalFileSystem.getInstance()
    .refreshAndFindFileByPath(
        FileUtil.toSystemIndependentName(this),
    )

fun Path.refreshAndFindVirtualFile(): VirtualFile? = this.toString().refreshAndFindVirtualFile()


/**
 * Attempts to find a [VirtualFile] by the given relative path
 * [relativePath].
 */
fun Project.findVirtualFile(relativePath: String): VirtualFile? {
    return this.guessProjectDir()?.findFileByRelativePath(relativePath)
}

/**
 * Attempts to return [virtualFile]'s path relative to the project root.
 * Otherwise, returns the [virtualFile]'s absolute path as-is (i.e., `virtualFile.path`).
 */
fun Project.relativeToRootOrAbsPath(virtualFile: VirtualFile): String {
    val projectRoot = this.guessProjectDir()?.path?.toPath()
    return when {
        // trying to make it relative to the project root
        projectRoot != null -> virtualFile.path.toPath().relativeTo(projectRoot).toString()
        else -> virtualFile.path
    }
}
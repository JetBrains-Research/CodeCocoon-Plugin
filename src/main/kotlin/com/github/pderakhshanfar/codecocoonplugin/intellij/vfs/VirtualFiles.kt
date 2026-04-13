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
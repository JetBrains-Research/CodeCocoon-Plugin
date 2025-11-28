package com.github.pderakhshanfar.codecocoonplugin.components

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path


fun String.refreshAndFindVirtualFile(): VirtualFile? = LocalFileSystem.getInstance()
    .refreshAndFindFileByPath(
        FileUtil.toSystemIndependentName(this),
    )

fun Path.refreshAndFindVirtualFile(): VirtualFile? = this.toString().refreshAndFindVirtualFile()
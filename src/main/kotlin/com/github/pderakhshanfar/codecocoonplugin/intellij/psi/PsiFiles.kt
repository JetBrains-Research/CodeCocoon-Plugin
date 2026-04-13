package com.github.pderakhshanfar.codecocoonplugin.intellij.psi

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile

/**
 * Retrieves the corresponding [PsiFile] for the given [VirtualFile] within this project.
 *
 * @param virtualFile [VirtualFile] for which [PsiFile] is to be retrieved
 * @return [PsiFile] associated with the given [VirtualFile], or `null` if no such PSI file was found
 *
 * @see [PsiManager.findFile]
 */
fun Project.psiFile(virtualFile: VirtualFile): PsiFile? {
    return PsiManager.getInstance(this).findFile(virtualFile)
}

/**
 * Returns the document for the specified PSI file.
 *
 * @see [PsiDocumentManager.getDocument]
 */
fun PsiFile.document(): Document? = this.let { file ->
    PsiDocumentManager.getInstance(file.project).getDocument(file)
}

/**
 * Extracts all declarations from the given PSI file.
 *
 * Useful to see the file content without requesting the entire content
 * if it is too large.
 */
fun PsiJavaFile.declarations(): String? {
    val file = this
    if (file.isDirectory) {
        return null
    }

    val packageString = file.packageStatement?.text
    val imports = file.importList?.text

    val declarations = buildList {
        for (clazz in file.classes) {
            add(clazz.declaration())
        }
    }

    return buildString {
        if (packageString != null) {
            appendLine(packageString)
            appendLine()
        }

        if (imports != null) {
            appendLine(imports)
            appendLine()
        }

        append(declarations.joinToString("\n\n"))
    }
}

/**
 * Returns a string representation of the declaration of this class.
 *
 * Example:
 *
 * ```java
 * class ExampleClass {
 *    private int x;
 *
 *    public void function() {
 *       System.out.println("Hello, world!");
 *    }
 * }
 * ```
 * Will return:
 * ```
 * class ExampleClass {
 *    private int x;
 *    public void function()
 * }
 * ```
 */
fun PsiClass.declaration(): String {
    val clazz = this
    val builder = StringBuilder()

    // Get class header (modifiers + class/interface/enum keyword + name + extends/implements)
    val classText = clazz.text
    val braceIndex = classText.indexOf('{')
    if (braceIndex > 0) {
        builder.append(classText.substring(0, braceIndex).trim())
        builder.append(" {\n")
    }

    // Add fields
    for (field in clazz.fields) {
        val fieldText = field.text.trim()
        // Remove initializers if present, keep only declaration
        val declarationPart = if (fieldText.contains('=')) {
            fieldText.substring(0, fieldText.indexOf('=')).trim() + ";"
        } else {
            fieldText
        }
        builder.append("   ")
            .append(declarationPart)
            .append("\n")
    }

    // Add method signatures
    for (method in clazz.methods) {
        if (!method.isConstructor) {
            val modifiers = method.modifierList.text
            val returnType = method.returnType?.presentableText ?: "void"
            val methodName = method.name
            val params = method.parameterList.text

            builder.append("   ")
            if (modifiers.isNotBlank()) {
                builder.append(modifiers).append(" ")
            }
            builder.append(returnType)
                .append(" ")
                .append(methodName)
                .append(params)
                .append("\n")
        } else {
            // For constructors
            val modifiers = method.modifierList.text
            val params = method.parameterList.text

            builder.append("   ")
            if (modifiers.isNotBlank()) {
                builder.append(modifiers).append(" ")
            }
            builder.append(method.name)
                .append(params)
                .append("\n")
        }
    }

    // closing '}'
    builder.append("}")

    return builder.toString().trimEnd()
}
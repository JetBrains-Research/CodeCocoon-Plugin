package com.github.pderakhshanfar.codecocoonplugin.memory

import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

/**
 * Generates unique signatures for PSI elements to enable deterministic tracking.
 */
object PsiSignatureGenerator {

    private val logger = thisLogger().withStdout()
    /**
     * Generates a unique signature for the given PSI element.
     *
     * @param psiElement The PSI element to generate a signature for
     * @return A unique signature string, or null if the element type is not supported
     *         or if the signature cannot be generated.
     */
    fun generateSignature(psiElement: PsiElement): String? {
        return try {
            when (psiElement) {
                is PsiClass -> psiElement.generateSignature()
                is PsiField -> psiElement.generateSignature()
                is PsiMethod -> psiElement.generateSignature()
                is PsiParameter -> psiElement.generateSignature()
                is PsiLocalVariable -> psiElement.generateSignature()
                else -> null
            }
        } catch (e: Exception) {
            logger.warn("Error in generating signature for PsiElement (${e.message})")
            null
        }
    }
}

/**
 * Generates signature for a PsiClass.
 * Format: package.ClassName
 */
private fun PsiClass.generateSignature(): String? {
    return qualifiedName
}

/**
 * Generates signature for a PsiField.
 * Format: fully.qualified.ClassName#fieldName
 */
private fun PsiField.generateSignature(): String? {
    val classFqn = containingClass?.qualifiedName ?: return null
    val fieldName = name
    return "$classFqn#$fieldName"
}

/**
 * Generates signature for a PsiMethod.
 * Format: fully.qualified.ClassName#methodName(param.Type1,param.Type2)
 *
 * Uses fully qualified names for all types for consistency and simplicity.
 */
private fun PsiMethod.generateSignature(): String? {
    val classFqn = containingClass?.qualifiedName ?: return null
    val methodName = name

    // Build parameter list using canonical (fully qualified) type names
    val paramTypes = parameterList.parameters.joinToString(", ") { param ->
        param.type.canonicalText
    }

    return "$classFqn#$methodName($paramTypes)"
}

/**
 * Generates signature for a PsiParameter.
 * Format: fully.qualified.ClassName#methodName(param.Type1,param.Type2)#param:parameterName
 */
private fun PsiParameter.generateSignature(): String? {
    val containingMethod = PsiTreeUtil.getParentOfType(this, PsiMethod::class.java) ?: return null
    val methodSignature = containingMethod.generateSignature() ?: return null
    val paramName = name
    return "$methodSignature#param:$paramName"
}

/**
 * Generates signature for a PsiLocalVariable.
 * Format: fully.qualified.ClassName#methodName(param.Type1,param.Type2)#localVar:variableName
 */
private fun PsiLocalVariable.generateSignature(): String? {
    val containingMethod = PsiTreeUtil.getParentOfType(this, PsiMethod::class.java) ?: return null
    val methodSignature = containingMethod.generateSignature() ?: return null
    val varName = name
    return "$methodSignature#localVar:$varName"
}

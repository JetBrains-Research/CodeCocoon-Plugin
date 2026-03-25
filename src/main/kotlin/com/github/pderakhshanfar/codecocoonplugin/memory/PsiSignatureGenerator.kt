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
                is PsiClass -> generateClassSignature(psiElement)
                is PsiField -> generateFieldSignature(psiElement)
                is PsiMethod -> generateMethodSignature(psiElement)
                is PsiParameter -> generateParameterSignature(psiElement)
                is PsiLocalVariable -> generateLocalVariableSignature(psiElement)
                else -> null
            }
        } catch (e: Exception) {
            logger.warn("Error in generating signature for PsiElement (${e.message})")
            null
        }
    }

    /**
     * Generates signature for a PsiClass.
     * Format: package.ClassName
     */
    private fun generateClassSignature(psiClass: PsiClass): String? {
        return psiClass.qualifiedName
    }

    /**
     * Generates signature for a PsiField.
     * Format: fully.qualified.ClassName#fieldName
     */
    private fun generateFieldSignature(psiField: PsiField): String? {
        val classFqn = psiField.containingClass?.qualifiedName ?: return null
        val fieldName = psiField.name
        return "$classFqn#$fieldName"
    }

    /**
     * Generates signature for a PsiMethod.
     * Format: fully.qualified.ClassName#methodName(param.Type1,param.Type2)
     *
     * Uses fully qualified names for all types for consistency and simplicity.
     */
    private fun generateMethodSignature(psiMethod: PsiMethod): String? {
        val classFqn = psiMethod.containingClass?.qualifiedName ?: return null
        val methodName = psiMethod.name

        // Build parameter list using canonical (fully qualified) type names
        val paramTypes = psiMethod.parameterList.parameters.joinToString(", ") { param ->
            param.type.canonicalText
        }

        return "$classFqn#$methodName($paramTypes)"
    }

    /**
     * Generates signature for a PsiParameter.
     * Format: fully.qualified.ClassName#methodName(param.Type1,param.Type2)#param:parameterName
     */
    private fun generateParameterSignature(psiParameter: PsiParameter): String? {
        val containingMethod = PsiTreeUtil.getParentOfType(psiParameter, PsiMethod::class.java) ?: return null
        val methodSignature = generateMethodSignature(containingMethod) ?: return null
        val paramName = psiParameter.name
        return "$methodSignature#param:$paramName"
    }

    /**
     * Generates signature for a PsiLocalVariable.
     * Format: fully.qualified.ClassName#methodName(param.Type1,param.Type2)#localVar:variableName
     */
    private fun generateLocalVariableSignature(psiLocalVariable: PsiLocalVariable): String? {
        val containingMethod = PsiTreeUtil.getParentOfType(psiLocalVariable, PsiMethod::class.java) ?: return null
        val methodSignature = generateMethodSignature(containingMethod) ?: return null
        val varName = psiLocalVariable.name
        return "$methodSignature#localVar:$varName"
    }
}

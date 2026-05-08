package com.github.pderakhshanfar.codecocoonplugin.services

import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import java.io.File

/**
 * Service for analyzing Java file dependencies using IntelliJ PSI.
 *
 * This service can:
 * - Analyze direct and transitive dependencies of Java files
 * - Build a dependency graph
 * - Traverse the graph to a specified depth
 * - Filter out standard library dependencies
 */
class DependencyAnalyzer(private val project: Project) {
    private val logger = thisLogger().withStdout()

    /**
     * Result of dependency analysis
     */
    data class DependencyAnalysisResult(
        val seedFiles: List<String>,
        val neighboringFiles: List<String>,
        val depth: Int,
        val totalFilesAnalyzed: Int
    )

    /**
     * Analyzes dependencies of the given seed files up to the specified depth.
     *
     * @param seedFilePaths List of file paths (relative to project root) to start analysis from
     * @param depth Maximum depth to traverse (1 = direct dependencies only, 2 = dependencies of dependencies, etc.)
     * @return DependencyAnalysisResult containing all neighboring files found
     */
    fun analyzeDependencies(seedFilePaths: List<String>, depth: Int = 1): DependencyAnalysisResult {
        logger.info("[DependencyAnalyzer] Starting analysis for ${seedFilePaths.size} seed files with depth=$depth")

        val projectBasePath = project.basePath ?: run {
            logger.error("[DependencyAnalyzer] Project has no base path")
            return DependencyAnalysisResult(seedFilePaths, emptyList(), depth, 0)
        }

        // Convert seed paths to PsiFiles
        val seedPsiFiles = seedFilePaths.mapNotNull { relativePath ->
            val absolutePath = File(projectBasePath, relativePath).absolutePath
            logger.info("[DependencyAnalyzer] Looking for seed file:")
            logger.info("[DependencyAnalyzer]   Relative path: $relativePath")
            logger.info("[DependencyAnalyzer]   Project base: $projectBasePath")
            logger.info("[DependencyAnalyzer]   Absolute path: $absolutePath")
            logger.info("[DependencyAnalyzer]   File exists: ${File(absolutePath).exists()}")

            val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
            if (virtualFile == null) {
                logger.warn("[DependencyAnalyzer] Could not find virtual file for: $relativePath")
                logger.warn("[DependencyAnalyzer]   VFS lookup failed for: $absolutePath")
                null
            } else {
                logger.info("[DependencyAnalyzer]   Virtual file found: ${virtualFile.path}")
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                if (psiFile == null) {
                    logger.warn("[DependencyAnalyzer]   PSI file is null!")
                } else {
                    logger.info("[DependencyAnalyzer]   PSI file created successfully")
                }
                psiFile
            }
        }

        if (seedPsiFiles.isEmpty()) {
            logger.warn("[DependencyAnalyzer] No valid seed files found")
            return DependencyAnalysisResult(seedFilePaths, emptyList(), depth, 0)
        }

        logger.info("[DependencyAnalyzer] Found ${seedPsiFiles.size} valid seed PSI files")

        // Build dependency graph
        val allDependencies = mutableSetOf<VirtualFile>()
        val visited = mutableSetOf<VirtualFile>()
        val queue = mutableListOf<Pair<PsiFile, Int>>() // (file, currentDepth)

        // Add seed files to queue
        seedPsiFiles.forEach { psiFile ->
            psiFile.virtualFile?.let { vFile ->
                queue.add(Pair(psiFile, 0))
                visited.add(vFile)
            }
        }

        // BFS traversal
        while (queue.isNotEmpty()) {
            val (currentFile, currentDepth) = queue.removeAt(0)

            if (currentDepth >= depth) {
                continue
            }

            // Find dependencies of current file
            val dependencies = findFileDependencies(currentFile)

            dependencies.forEach { depVirtualFile ->
                if (!visited.contains(depVirtualFile)) {
                    visited.add(depVirtualFile)
                    allDependencies.add(depVirtualFile)

                    // Add to queue for further traversal if we haven't reached max depth
                    if (currentDepth + 1 < depth) {
                        val depPsiFile = PsiManager.getInstance(project).findFile(depVirtualFile)
                        if (depPsiFile != null) {
                            queue.add(Pair(depPsiFile, currentDepth + 1))
                        }
                    }
                }
            }
        }

        // Convert VirtualFiles back to relative paths
        val neighboringPaths = allDependencies.mapNotNull { vFile ->
            val absolutePath = vFile.path
            val relativePath = if (absolutePath.startsWith(projectBasePath)) {
                absolutePath.substring(projectBasePath.length).trimStart('/')
            } else {
                null
            }
            relativePath
        }.sorted()

        logger.info("[DependencyAnalyzer] Analysis complete: found ${neighboringPaths.size} neighboring files")

        return DependencyAnalysisResult(
            seedFiles = seedFilePaths,
            neighboringFiles = neighboringPaths,
            depth = depth,
            totalFilesAnalyzed = visited.size
        )
    }

    /**
     * Finds all file dependencies for a given PsiFile.
     * This includes:
     * - Import statements
     * - Class references
     * - Method calls to other classes
     *
     * @param psiFile The file to analyze
     * @return Set of VirtualFiles that this file depends on (excluding standard library)
     */
    private fun findFileDependencies(psiFile: PsiFile): Set<VirtualFile> {
        val dependencies = mutableSetOf<VirtualFile>()
        val projectBasePath = project.basePath ?: return emptySet()

        logger.info("[DependencyAnalyzer] Analyzing file: ${psiFile.virtualFile?.path}")

        // Only analyze Java files
        if (psiFile !is PsiJavaFile) {
            logger.info("[DependencyAnalyzer] Skipping non-Java file")
            return emptySet()
        }

        val javaPsiFile = psiFile as PsiJavaFile

        // 1. Analyze import statements
        val importCount = javaPsiFile.importList?.allImportStatements?.size ?: 0
        logger.info("[DependencyAnalyzer] Found $importCount imports")

        javaPsiFile.importList?.allImportStatements?.forEach { importStatement ->
            val importedClassName = when (importStatement) {
                is PsiImportStatement -> importStatement.qualifiedName
                is PsiImportStaticStatement -> importStatement.importReference?.qualifiedName
                else -> null
            }

            if (importedClassName != null) {
                logger.info("[DependencyAnalyzer] Processing import: $importedClassName")
                if (!isStandardLibrary(importedClassName)) {
                    val classFile = findClassFile(importedClassName)
                    if (classFile != null) {
                        logger.info("[DependencyAnalyzer]   -> Found class file: ${classFile.path}")
                        dependencies.add(classFile)
                    } else {
                        logger.info("[DependencyAnalyzer]   -> Class file not found")
                    }
                } else {
                    logger.info("[DependencyAnalyzer]   -> Skipped (standard library)")
                }
            }
        }

        // 2. Analyze class references in the code
        val classRefCount = PsiTreeUtil.findChildrenOfType(javaPsiFile, PsiJavaCodeReferenceElement::class.java).size
        logger.info("[DependencyAnalyzer] Found $classRefCount class references")

        PsiTreeUtil.findChildrenOfType(javaPsiFile, PsiJavaCodeReferenceElement::class.java).forEach { reference ->
            val resolved = reference.resolve()
            if (resolved is PsiClass) {
                val qualifiedName = resolved.qualifiedName
                if (qualifiedName != null && !isStandardLibrary(qualifiedName)) {
                    resolved.containingFile?.virtualFile?.let { vFile ->
                        // Only include files within the project
                        if (vFile.path.startsWith(projectBasePath)) {
                            dependencies.add(vFile)
                        }
                    }
                }
            }
        }

        // 3. Analyze method call expressions
        val methodCallCount = PsiTreeUtil.findChildrenOfType(javaPsiFile, PsiMethodCallExpression::class.java).size
        logger.info("[DependencyAnalyzer] Found $methodCallCount method calls")

        PsiTreeUtil.findChildrenOfType(javaPsiFile, PsiMethodCallExpression::class.java).forEach { methodCall ->
            val resolvedMethod = methodCall.resolveMethod()
            if (resolvedMethod != null) {
                val containingClass = resolvedMethod.containingClass
                if (containingClass != null) {
                    val qualifiedName = containingClass.qualifiedName
                    if (qualifiedName != null && !isStandardLibrary(qualifiedName)) {
                        resolvedMethod.containingFile?.virtualFile?.let { vFile ->
                            if (vFile.path.startsWith(projectBasePath)) {
                                dependencies.add(vFile)
                            }
                        }
                    }
                }
            }
        }

        logger.info("[DependencyAnalyzer] Total dependencies found for this file: ${dependencies.size}")
        return dependencies
    }

    /**
     * Checks if a fully qualified class name belongs to the standard library.
     * Returns true for java.*, javax.*, sun.*, com.sun.*, etc.
     */
    private fun isStandardLibrary(qualifiedName: String): Boolean {
        return qualifiedName.startsWith("java.") ||
               qualifiedName.startsWith("javax.") ||
               qualifiedName.startsWith("sun.") ||
               qualifiedName.startsWith("com.sun.") ||
               qualifiedName.startsWith("jdk.") ||
               qualifiedName.startsWith("org.w3c.") ||
               qualifiedName.startsWith("org.xml.") ||
               qualifiedName.startsWith("org.ietf.")
    }

    /**
     * Finds the VirtualFile for a given fully qualified class name.
     */
    private fun findClassFile(qualifiedName: String): VirtualFile? {
        val javaPsiFacade = JavaPsiFacade.getInstance(project)
        val searchScope = GlobalSearchScope.projectScope(project)

        val psiClass = javaPsiFacade.findClass(qualifiedName, searchScope)
        return psiClass?.containingFile?.virtualFile
    }
}

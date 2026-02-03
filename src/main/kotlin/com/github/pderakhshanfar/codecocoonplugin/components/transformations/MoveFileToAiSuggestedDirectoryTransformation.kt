package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.common.Language
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.suggestions.SuggestionsApi
import com.github.pderakhshanfar.codecocoonplugin.transformation.requireOrDefault
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files


/**
 * A transformation that moves a given file to a directory suggested by AI.
 *
 * This transformation analyzes the context of the provided file and relocates it to an AI-recommended
 * directory based on its content, usage, or other metadata. The target directory is determined dynamically based
 * on AI algorithms, making project organization more intuitive and efficient.
 *
 * TODO: this transformation is tailored to Java projects; not multi-lingual!
 *
 * Expected config schema:
 * ```yaml
 * config:
 *   existingOnly: boolean (default: false) # optional, whether to suggestion yet non-existent directories
 * ```
 *
 * @property config Configuration parameters required for the transformation.
 * @constructor Initializes the transformation with the provided configuration map.
 */
class MoveFileToAiSuggestedDirectoryTransformation(
    override val config: Map<String, Any>,
) : IntelliJAwareTransformation {
    override val id = ID
    override val description = "Places the given file into a directory suggested by AI"

    private val existingOnly: Boolean = config.requireOrDefault("existingOnly", false)

    private val logger = thisLogger().withStdout()

    // TODO: for now, concentrate only of Java
    override fun accepts(context: FileContext): Boolean {
        return context.language == Language.JAVA
    }

    override fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile
    ): TransformationResult {
        val project = psiFile.project
        val requestor = this

        // Validate that this is a Java file
        if (psiFile !is PsiJavaFile) {
            return TransformationResult.Failure("File ${virtualFile.name} is not a Java file")
        }

        return try {
            // Step 1: Get AI directory suggestion
            logger.info("Requesting AI directory suggestion for ${virtualFile.path}")
            // TODO: anything better than runBlocking?
            val suggestedDirectories = runBlocking {
                SuggestionsApi.suggestNewDirectory(
                    token = System.getenv("OPENAI_API_KEY"),
                    projectRoot = project.basePath!!,
                    filepath = virtualFile.path,
                    content = { psiFile.text },
                    existingOnly = existingOnly
                )
            }

            if (suggestedDirectories.isEmpty()) {
                return TransformationResult.Failure("No directory suggestions received from AI")
            }

            // TODO: traverse through the suggestions and peek the first applicable?
            val targetDirectoryPath = suggestedDirectories.first()
            logger.info("Selected target directory: $targetDirectoryPath")

            // Step 2: Collect pre-move information
            val oldPackageName = psiFile.packageName
            val fileIndex = ProjectFileIndex.getInstance(project)
            val sourceRoot = fileIndex.getSourceRootForFile(virtualFile)
                ?: return TransformationResult.Failure("Cannot find source root for ${virtualFile.path}")

            logger.info("Original package: $oldPackageName")

            // Collect public classes/interfaces that will need import updates in other files
            val publicClasses: List<PsiClass> = collectPublicClasses(psiFile)
            logger.info("Found ${publicClasses.size} public classes: ${publicClasses.map { it.name }}")

            // Find all files that reference these classes
            val referencingFiles = findReferencingFiles(project, publicClasses)
            logger.info("Found ${referencingFiles.size} files referencing classes from this file")

            // Step 3: Calculate the target package name
            // In case when the suggestion API returns paths relative to the project root
            val projectBasePath = project.basePath
                ?: return TransformationResult.Failure("Cannot determine project base path")
            val absoluteTargetPath = when {
                targetDirectoryPath.toPath().isAbsolute -> targetDirectoryPath
                else -> File(projectBasePath, targetDirectoryPath).canonicalPath
            }

            logger.info("Resolved absolute target path: $absoluteTargetPath")

            // Verify the target directory is under a source root
            val targetFile = File(absoluteTargetPath)

            // Create a directory structure if it doesn't exist, then get VirtualFile
            Files.createDirectories(targetFile.toPath())
            val targetVirtualFile = VfsUtil.findFileByIoFile(targetFile, /* refreshIfNeeded = */ true)
                ?: return TransformationResult.Failure("Cannot find virtual file for target: $absoluteTargetPath")

            val targetSourceRoot = fileIndex.getSourceRootForFile(targetVirtualFile)
                    ?: return TransformationResult.Failure(
                        "Target directory $targetDirectoryPath is not under any source root in the project")

            // Calculate new package name relative to source root
            val newPackageName = run {
                val targetSourceRootPath = File(targetSourceRoot.path)
                when {
                    targetFile.startsWith(targetSourceRootPath) -> {
                        val relativePath = targetFile.relativeTo(targetSourceRootPath).path
                        if (relativePath.isEmpty()) "" else relativePath.replace(File.separatorChar, '.')
                    }
                    else -> null
                }
            }
            if (newPackageName == null) {
                return TransformationResult.Failure(
                    "Target directory $absoluteTargetPath is not under source root ${targetSourceRoot.path}")
            }

            logger.info("New package will be: $newPackageName")

            // Validate package name
            if (!isValidPackageName(newPackageName)) {
                return TransformationResult.Failure("Invalid package name: $newPackageName")
            }

            val modifiedFiles = mutableSetOf<PsiFile>()

            WriteCommandAction.runWriteCommandAction(project) {
                // Step 4: Move the file
                logger.info("Moving file to new location...")
                virtualFile.move(requestor, targetVirtualFile)

                // Refresh and get updated PSI
                val movedPsiFile = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile
                    ?: throw IllegalStateException("Cannot find moved file")

                modifiedFiles.add(movedPsiFile)

                // Step 5: Update package statement
                logger.info("Updating package statement to $newPackageName")
                updatePackageStatement(movedPsiFile, newPackageName)

                // Step 6: Fix imports in the moved file
                logger.info("Fixing imports in moved file...")
                fixImportsInMovedFile(movedPsiFile, oldPackageName)

                // Step 7: Update imports in other files that reference moved classes
                logger.info("Updating imports in ${referencingFiles.size} referencing files...")
                for (referencingFile in referencingFiles) {
                    updateImportsInReferencingFile(referencingFile, oldPackageName, newPackageName, publicClasses)
                    modifiedFiles.add(referencingFile)
                }
            }

            TransformationResult.Success(
                message = "Moved ${virtualFile.name} from package '$oldPackageName' to '$newPackageName'",
                filesModified = modifiedFiles.size
            )
        } catch (e: Exception) {
            logger.error("Failed to move file ${virtualFile.name}", e)
            TransformationResult.Failure("Failed to move file ${virtualFile.name}: ${e.message}", e)
        }
    }

    /**
     * Collects all public classes and **interfaces** from the given Java file.
     */
    private fun collectPublicClasses(javaFile: PsiJavaFile): List<PsiClass> {
        val publicClasses = mutableListOf<PsiClass>()
        javaFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                // TODO: public abstract classes included? enums? what else?
                if (element is PsiClass && element.hasModifierProperty(PsiModifier.PUBLIC)) {
                    publicClasses.add(element)
                }
            }
        })
        return publicClasses
    }

    /**
     * Finds all Java files that reference any of the given classes.
     */
    private fun findReferencingFiles(project: Project, classes: List<PsiClass>): Set<PsiJavaFile> {
        val referencingFiles = mutableSetOf<PsiJavaFile>()
        val searchScope = GlobalSearchScope.projectScope(project)

        for (psiClass in classes) {
            val references = ReferencesSearch.search(psiClass, searchScope).findAll()
            for (reference in references) {
                val containingFile = reference.element.containingFile
                if (containingFile is PsiJavaFile && containingFile != psiClass.containingFile) {
                    referencingFiles.add(containingFile)
                }
            }
        }

        return referencingFiles
    }

    // TODO: write comment that these methods require clients to wrap them with write actions
    /**
     * Updates the package statement in the moved file.
     */
    private fun updatePackageStatement(javaFile: PsiJavaFile, newPackageName: String) {
        val elementFactory = JavaPsiFacade.getElementFactory(javaFile.project)
        val newPackageStatement = elementFactory.createPackageStatement(newPackageName)

        val existingPackageStatement = javaFile.packageStatement
        if (existingPackageStatement != null) {
            existingPackageStatement.replace(newPackageStatement)
        } else {
            // If no package statement exists, add one at the beginning
            val firstChild = javaFile.firstChild
            if (firstChild != null) {
                javaFile.addBefore(newPackageStatement, firstChild)
            }
        }
    }

    /**
     * Fixes imports in the moved file by adding explicit imports for classes
     * that were previously accessible from the same package without imports.
     */
    private fun fixImportsInMovedFile(movedFile: PsiJavaFile, oldPackageName: String) {
        val elementFactory = JavaPsiFacade.getElementFactory(movedFile.project)
        val referencesToFix = mutableSetOf<PsiClass>()

        // Find all unqualified references in the moved file
        movedFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                if (element is PsiJavaCodeReferenceElement) {
                    val resolved = element.resolve()
                    if (resolved is PsiClass) {
                        // Check if this class is from the old package and not imported
                        val resolvedFile = resolved.containingFile as? PsiJavaFile
                        if (resolvedFile != null && resolvedFile.packageName == oldPackageName) {
                            // Check if there's already an import for this class
                            val qualifiedName = resolved.qualifiedName
                            if (qualifiedName != null && !hasImport(movedFile, qualifiedName)) {
                                referencesToFix.add(resolved)
                            }
                        }
                    }
                }
            }
        })

        // Add imports for all references that need fixing
        val importList = movedFile.importList
        for (classToImport in referencesToFix) {
            val qualifiedName = classToImport.qualifiedName
            if (qualifiedName != null && importList != null) {
                val importStatement = elementFactory.createImportStatement(classToImport)
                // TODO: need any update on VFS? is simply adding to the list enough?
                importList.add(importStatement)
                logger.info("  - Added import: $qualifiedName")
            }
        }
    }

    /**
     * Updates imports in files that reference classes from the moved file.
     */
    private fun updateImportsInReferencingFile(
        referencingFile: PsiJavaFile,
        oldPackageName: String,
        newPackageName: String,
        movedClasses: List<PsiClass>
    ) {
        val elementFactory = JavaPsiFacade.getElementFactory(referencingFile.project)
        val importList = referencingFile.importList ?: return

        val movedClassNames = movedClasses.mapNotNull { it.name }.toSet()

        // Find and update import statements
        val importsToUpdate = mutableListOf<PsiImportStatement>()
        for (importStatement in importList.importStatements) {
            val importedName = importStatement.qualifiedName ?: continue

            // Check if this import is from the old package and references a moved class
            if (importedName.startsWith("$oldPackageName.")) {
                val className = importedName.substringAfterLast('.')
                if (className in movedClassNames) {
                    importsToUpdate.add(importStatement)
                }
            }
        }

        // Replace old imports with new ones
        for (oldImport in importsToUpdate) {
            val className = oldImport.qualifiedName?.substringAfterLast('.') ?: continue

            // Find the actual class to import
            val movedClass = movedClasses.find { it.name == className }
            if (movedClass != null) {
                val newImportStatement = elementFactory.createImportStatement(movedClass)
                // TODO: should be done update write action? check other places also
                oldImport.replace(newImportStatement)

                val newQualifiedName = "$newPackageName.$className"
                logger.info("  Updated import in ${referencingFile.name}: $oldPackageName.$className -> $newQualifiedName")
            }
        }
    }

    /**
     * Checks if the given file has an import for the specified qualified name.
     */
    private fun hasImport(javaFile: PsiJavaFile, qualifiedName: String): Boolean {
        val importList = javaFile.importList ?: return false
        for (importStatement in importList.importStatements) {
            if (importStatement.qualifiedName == qualifiedName) {
                return true
            }
        }
        return false
    }

    /**
     * Validates that the package name is a valid Java identifier.
     */
    private fun isValidPackageName(packageName: String): Boolean {
        if (packageName.isEmpty()) return true // default package
        return packageName.split('.').all { part ->
            part.isNotEmpty() && part[0].isJavaIdentifierStart() && part.all { it.isJavaIdentifierPart() }
        }
    }

    companion object {
        const val ID = "move-file-to-ai-suggested-directory-transformation"
    }
}
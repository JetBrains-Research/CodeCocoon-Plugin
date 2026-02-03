package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.common.Language
import com.github.pderakhshanfar.codecocoonplugin.common.TransformationStepFailed
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
            // Step 2: Collect pre-move information
            val oldPackageName = psiFile.packageName
            val fileIndex = ProjectFileIndex.getInstance(project)
            logger.info("Original package: $oldPackageName")



            // Step 1: Get AI directory suggestion
            logger.info("Requesting AI directory suggestion for ${virtualFile.path}")
            val suggestedDirectories = runBlocking {
                SuggestionsApi.suggestNewDirectory(
                    token = System.getenv("OPENAI_API_KEY"),
                    projectRoot = project.basePath!!,
                    filepath = virtualFile.path,
                    // TODO: extract only declarations if the file is big
                    content = { psiFile.text },
                    existingOnly = existingOnly
                )
            }

            if (suggestedDirectories.isEmpty()) {
                return TransformationResult.Failure("No directory suggestions received from AI")
            }

            val projectRoot = project.basePath ?: return TransformationResult.Failure("Cannot determine project base path")
            val result = findSuitableDestinationDirectory(
                project = project,
                projectRoot = projectRoot,
                suggestions = suggestedDirectories,
            )

            val (destinationDirectory, newPackageName) = when {
                result.isSuccess -> result.getOrThrow()
                else -> return TransformationResult.Failure(result.exceptionOrNull()?.message ?: "Unknown error")
            }

            /*
            // TODO: traverse through the suggestions and peek the first applicable?
            val targetDirectoryPath = suggestedDirectories.first()
            logger.info("Selected target directory: $targetDirectoryPath")


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
            // TODO: use `VfsUtil.createDirectories`
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
            } else if (newPackageName == oldPackageName) {
                // TODO: iterate through the suggested file locations
                return TransformationResult.Skipped(
                    "The new package equals to the original one: $newPackageName. " +
                        "The suggested directory would remain unchanged: $absoluteTargetPath")
            }

            // Validate package name
            if (!isValidPackageName(newPackageName)) {
                return TransformationResult.Failure("Invalid package name: $newPackageName")
            }
             */

            logger.info("New package will be: $newPackageName")

            // TODO: modify the changed files count
            var modifiedFilesCount = 0
            val elementFactory = JavaPsiFacade.getElementFactory(project)

            WriteCommandAction.runWriteCommandAction<Unit>(project) {
                // import referenced classes from the same package BEFORE moving this file,
                // as these imports will be required AFTER moving the file.
                psiFile.importClassesFromPackage(oldPackageName)

                /*
                // add imports of components within the same package to the file being moved
                // that will be missing after the move operation
                val referencesToImport: List<PsiClass> = buildList {
                    psiFile.accept(object : PsiRecursiveElementVisitor() {
                        override fun visitElement(element: PsiElement) {
                            super.visitElement(element)
                            if (element is PsiJavaCodeReferenceElement) {
                                val resolved = element.resolve()

                                logger.info("Considering resolved instance of element ${element.qualifiedName} (isResolvedNull=${resolved == null}, isResolvedPsiClass=${resolved is PsiClass}) with text:\n'''\n${resolved?.text?.take(200)}\n'''")

                                if (resolved is PsiClass) {
                                    // Check if this class is from the old package and not imported
                                    val resolvedFile = resolved.containingFile as? PsiJavaFile
                                    if (resolvedFile != null && resolvedFile.packageName == oldPackageName) {
                                        // Check if there's already an import for this class
                                        val qualifiedName = resolved.qualifiedName
                                        if (qualifiedName != null && !hasImport(psiFile, qualifiedName)) {
                                            // add into the lists of references to import in the considered PSI file
                                            add(resolved)
                                        }
                                    }
                                }
                            }
                        }
                    })
                }
                logger.info("Found ${referencesToImport.size} unqualified references in ${psiFile.name} to import: ${referencesToImport.map { it.name }}")

                val importList = psiFile.importList
                logger.info("Import list of ${psiFile.name} (isNull=${importList == null}):\n'''\n${importList?.text ?: importList}\n'''")

                // importing references (namely, classes) into the considered PSI file BEFORE moving this file
                for (reference in referencesToImport) {
                    val importStatement = elementFactory.createImportStatement(reference)

                    logger.info("Adding a new import into the import list of ${psiFile.name}: `${importStatement.text}` (for the qualified name: ${reference.qualifiedName})")

                    if (importList != null) {
                        importList.add(importStatement)
                        logger.info("  - Added import for `${reference.qualifiedName}`: ${importStatement.text}")
                    }
                }
                 */
            }


            // collect public classes/interfaces that will need import updates in other files
            val publicClasses: List<PsiClass> = collectPublicClasses(psiFile)
            val publicQualifiedNames = publicClasses.mapNotNull { it.qualifiedName }.toSet()
            logger.info("Found ${publicClasses.size} public classes of `${psiFile.name}` file: ${publicClasses.map { it.name }} -> corresponding qualified names ${publicQualifiedNames.toList()} (their imports in other files will be updated with a new package $newPackageName)")

            // Find all files that reference these classes. We intentionally collect referencing
            // files before the file is moved. Otherwise, the references become corrupted.
            val referencedClassesInFiles: Map<PsiJavaFile, Set<PsiClass>> = findReferencingFiles(project, publicClasses)
            logger.info("References from the `${psiFile.name}` file: ${referencedClassesInFiles.map { (key, value) -> "${key.name} -> ${value.map { it.name }}" }}")


            // ==== Moving the file into the new directory ==== //
            logger.info("Moving the file into a target directory...")
            WriteCommandAction.runWriteCommandAction<Unit>(project) {
                // move the file
                logger.info("Moving file to new location...")
                virtualFile.move(requestor, destinationDirectory)

                // refresh and get updated PSI
                val movedPsiFile = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile
                    ?: throw IllegalStateException("Cannot find moved file ${virtualFile.path}")

                // update package statement
                logger.info("Updating package statement to $newPackageName")
                updatePackageStatement(movedPsiFile, newPackageName)
            }

            // TODO: move into methods all three stages.
            // TODO: unit all three stages under a single write command action.
            // add/update symbol imports in referencing files
            WriteCommandAction.runWriteCommandAction<Unit>(project) {
                logger.info("Updating imports in referencing files...")
                for ((referencingFile, referencedClasses) in referencedClassesInFiles) {
                    // a referencing file can be either:
                    //   1. From a different package -> update its import of the referenced class
                    //   2. Within the same package, hence, it may not have an import of the referenced class -> add a new import
                    val importList = referencingFile.importList ?: continue

                    for (reference in referencedClasses) {
                        val newImportStatement = elementFactory.createImportStatement(reference)
                        logger.info("Considered reference `${reference.qualifiedName}` (contained by `${reference.containingFile}` file) referenced in `${referencingFile.name}`:")

                        // search for the import statement that corresponds to the referenced class
                        val oldImportStatement = importList.importStatements.find { it.qualifiedName == reference.qualifiedName }

                        when {
                            oldImportStatement != null -> {
                                // update this import statement with the new package prefix
                                logger.info("Replacing import in `${referencingFile.name}` `${oldImportStatement.text}` -> `${newImportStatement.text}`")
                                oldImportStatement.replace(newImportStatement)
                            }
                            referencingFile.packageName == oldPackageName -> {
                                // otherwise, if a referencing file was within the same package
                                // as the moved file, add a new import statement
                                logger.info("Adding a new import into the import list of `${referencingFile.name}`: `${newImportStatement.text}` (for the qualified name: ${reference.qualifiedName})")
                                importList.add(newImportStatement)
                            }
                            else -> logger.error("Cannot find/add import statement for `${reference.qualifiedName}` in `${referencingFile.virtualFile.path}`. The transformation may be incorrect.")
                        }
                    }
                }
            }


            TransformationResult.Success(
                message = "Moved ${virtualFile.name} from package '$oldPackageName' to '$newPackageName'",
                filesModified = modifiedFilesCount
            )
        } catch (e: Exception) {
            logger.error("Failed to move file ${virtualFile.name}", e)
            TransformationResult.Failure("Failed to move file ${virtualFile.name}: ${e.message}", e)
        }
    }

    data class DestinationDirectory(
        val directory: VirtualFile,
        val packageName: String,
    )

    private fun findSuitableDestinationDirectory(
        project: Project,
        projectRoot: String,
        suggestions: List<String>,
    ): Result<DestinationDirectory> {
        val fileIndex = ProjectFileIndex.getInstance(project)
        // TODO: traverse through the suggestions and peek the first applicable?
        val targetDirectoryPath = suggestions.first()
        logger.info("Selected target directory: $targetDirectoryPath")


        // Step 3: Calculate the target package name
        // In case when the suggestion API returns paths relative to the project root
        val absoluteTargetPath = when {
            targetDirectoryPath.toPath().isAbsolute -> targetDirectoryPath
            else -> File(projectRoot, targetDirectoryPath).canonicalPath
        }

        logger.info("Resolved absolute target path: $absoluteTargetPath")

        // Verify the target directory is under a source root
        val targetFile = File(absoluteTargetPath)

        // Create a directory structure if it doesn't exist, then get VirtualFile
        // TODO: use `VfsUtil.createDirectories` and remove `targetFile`
        Files.createDirectories(targetFile.toPath())

        val targetVirtualFile = VfsUtil.findFileByIoFile(targetFile, /* refreshIfNeeded = */ true)
            ?: return Result.failure(TransformationStepFailed(
                "Cannot find virtual file for target: $absoluteTargetPath"))

        val targetSourceRoot = fileIndex.getSourceRootForFile(targetVirtualFile)
            ?: return Result.failure(TransformationStepFailed(
                "Target directory $targetDirectoryPath is not under any source root in the project"))

        // Calculate new package name relative to source root
        val targetSourceRootPath = File(targetSourceRoot.path)
        val newPackageName = when {
            targetFile.startsWith(targetSourceRootPath) -> {
                val relativePath = targetFile.relativeTo(targetSourceRootPath).path
                if (relativePath.isEmpty()) "" else relativePath.replace(File.separatorChar, '.')
            }
            else -> null
        }

        // TODO: make the following checks for the package
        /*
        if (newPackageName == null) {
                return TransformationResult.Failure(
                    "Target directory $absoluteTargetPath is not under source root ${targetSourceRoot.path}")
            } else if (newPackageName == oldPackageName) {
                // TODO: iterate through the suggested file locations
                return TransformationResult.Skipped(
                    "The new package equals to the original one: $newPackageName. " +
                        "The suggested directory would remain unchanged: $absoluteTargetPath")
            }

            // Validate package name
            if (!isValidPackageName(newPackageName)) {
                return TransformationResult.Failure("Invalid package name: $newPackageName")
            }
         */

        return Result.success(
            DestinationDirectory(
                directory = targetVirtualFile,
                // TODO: remove !!
                packageName = newPackageName!!,
            )
        )
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
    private fun findReferencingFiles(project: Project, classes: List<PsiClass>): Map<PsiJavaFile, Set<PsiClass>> {
        val referencedClassesInFiles = mutableMapOf<PsiJavaFile, MutableSet<PsiClass>>()
        // val referencingFiles = mutableSetOf<PsiJavaFile>()
        val searchScope = GlobalSearchScope.projectScope(project)

        for (psiClass in classes) {
            val references = ReferencesSearch.search(psiClass, searchScope).findAll()
            for (reference in references) {
                val containingFile = reference.element.containingFile
                // don't add a file that contains the given reference
                if (containingFile is PsiJavaFile && containingFile != psiClass.containingFile) {
                    if (!referencedClassesInFiles.containsKey(containingFile)) {
                        referencedClassesInFiles[containingFile] = mutableSetOf(psiClass)
                    } else {
                        referencedClassesInFiles[containingFile]!!.add(psiClass)
                    }
                }
            }
        }

        return referencedClassesInFiles
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
     * TODO: descr
     */
    private fun PsiJavaFile.importClassesFromPackage(fromPackage: String) {
        val psiFile = this
        val elementFactory = JavaPsiFacade.getElementFactory(psiFile.project)

        // add imports of components within the same package to the file being moved
        // that will be missing after the move operation
        val referencesToImport: List<PsiClass> = buildList {
            psiFile.accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    super.visitElement(element)
                    if (element is PsiJavaCodeReferenceElement) {
                        val resolved = element.resolve()

                        logger.info("Considering resolved instance of element ${element.qualifiedName} (isResolvedNull=${resolved == null}, isResolvedPsiClass=${resolved is PsiClass}) with text:\n'''\n${resolved?.text?.take(200)}\n'''")

                        if (resolved is PsiClass) {
                            // Check if this class is from the old package and not imported
                            val resolvedFile = resolved.containingFile as? PsiJavaFile
                            if (resolvedFile != null && resolvedFile.packageName == fromPackage) {
                                // Check if there's already an import for this class
                                val qualifiedName = resolved.qualifiedName
                                if (qualifiedName != null && !hasImport(psiFile, qualifiedName)) {
                                    // add into the lists of references to import in the considered PSI file
                                    add(resolved)
                                }
                            }
                        }
                    }
                }
            })
        }
        logger.info("Found ${referencesToImport.size} unqualified references in ${psiFile.name} to import: ${referencesToImport.map { it.name }}")

        val importList = psiFile.importList
        logger.info("Import list of ${psiFile.name} (isNull=${importList == null}):\n'''\n${importList?.text ?: importList}\n'''")

        // importing references (namely, classes) into the considered PSI file BEFORE moving this file
        for (reference in referencesToImport) {
            val importStatement = elementFactory.createImportStatement(reference)

            logger.info("Adding a new import into the import list of ${psiFile.name}: `${importStatement.text}` (for the qualified name: ${reference.qualifiedName})")

            if (importList != null) {
                importList.add(importStatement)
                logger.info("  - Added import for `${reference.qualifiedName}`: ${importStatement.text}")
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


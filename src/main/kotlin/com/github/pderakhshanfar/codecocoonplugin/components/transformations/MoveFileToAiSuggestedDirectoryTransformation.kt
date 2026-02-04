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
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File


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

    override fun accepts(context: FileContext): Boolean {
        // NOTE: accepting only Java files for now
        return context.language == Language.JAVA
    }

    override fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile
    ): TransformationResult {
        val project = psiFile.project
        val requestor = this

        // validate that this is a Java file
        if (psiFile !is PsiJavaFile) {
            return TransformationResult.Failure("File ${virtualFile.path} is not a Java file")
        }

        return try {
            val projectRoot = project.basePath
                ?: return TransformationResult.Failure("Cannot determine project base path")

            /**
             * STEP 0: Generate AI Directory Suggestions and Create a Select New Package
             */
            logger.info("STEP 0: Generate AI Directory Suggestions and Create a Select New Package")
            val suggestedDirectories = runBlocking {
                SuggestionsApi.suggestNewDirectory(
                    token = System.getenv("OPENAI_API_KEY"),
                    projectRoot = projectRoot,
                    filepath = virtualFile.path,
                    // TODO: extract only declarations if the file is big
                    content = { psiFile.text },
                    existingOnly = existingOnly
                )
            }
            if (suggestedDirectories.isEmpty()) {
                return TransformationResult.Failure("No directory suggestions received from AI")
            }

            // select suitable directory from AI suggestions
            val oldPackageName = psiFile.packageName
            logger.info("Suggestions produced by AI: $suggestedDirectories")
            val result = findSuitableDestinationDirectory(
                project = project,
                projectRoot = projectRoot,
                oldPackageName = oldPackageName,
                suggestions = suggestedDirectories,
            )
            // validate
            val (destinationDirectory, newPackageName) = when {
                result.isSuccess -> result.getOrThrow()
                else -> return TransformationResult.Failure(
                    result.exceptionOrNull()?.message ?: "Unknown error")
            }
            logger.info("Selected destination directory: $destinationDirectory, and new package: $newPackageName")
            logger.info("Package change for file ${virtualFile.path}: $oldPackageName -> $newPackageName")

            var modifiedFilesCount = 0
            WriteCommandAction.runWriteCommandAction<Unit>(project) {
                /**
                 * STEP 1: Import Class From The Same Package
                 *
                 * Rationale:
                 * Import referenced classes from the same package BEFORE moving this file,
                 * as these imports will be required AFTER moving the file.
                 */
                logger.info("STEP 1: Import Class From The Same Package")
                psiFile.importClassesFromPackage(oldPackageName)

                /**
                 * STEP 2: Collect Symbols Referenced In Other Files Before Move
                 *
                 * Rationale:
                 * The package of the following collected symbols will be updated.
                 * Therefore, BEFORE moving the file and updating its package,
                 * we collect all files that reference the symbols to update or add
                 * imports AFTER the move.
                 */
                logger.info("STEP 2: Collect Symbols Referenced In Other Files Before Move")
                // collect public classes/interfaces that will need import updates in other files
                // and find all files that reference these classes.
                val publicClasses: List<PsiClass> = psiFile.collectPublicClasses()
                val referencingFilesToClasses: Map<PsiJavaFile, Set<PsiClass>> =
                    publicClasses.findReferencingFiles(project)

                logger.info("${psiFile.name} contains ${publicClasses.size} public classes: ${publicClasses.map { it.qualifiedName }}")
                logger.info("${referencingFilesToClasses.size} files reference classes from ${psiFile.name}: ${referencingFilesToClasses.map { (file, refs) -> "${file.name} -> ${refs.map { it.qualifiedName }}" }}")

                /**
                 * STEP 3: Move the File and Update its Package
                 */
                logger.info("STEP 3: Move the File and Update its Package")
                val result = virtualFile.moveAndUpdatePackage(
                    project = project,
                    requestor = requestor,
                    where = destinationDirectory,
                    packageName = newPackageName,
                )
                if (result.isFailure) {
                    val err = result.exceptionOrNull() ?: TransformationStepFailed(
                        "Failed to move file ${virtualFile.name} or update its package to $newPackageName")
                    throw err
                }
                // we change the package within the moved file
                modifiedFilesCount += 1

                /**
                 * STEP 4: Update Imports In Referencing Files
                 */
                logger.info("STEP 4: Update Imports In Referencing Files")
                for ((referencingFile, referencedClasses) in referencingFilesToClasses) {
                    // update or add imports of the moved classes in the referencing files
                    // IMPORTANT: this step MUST be done AFTER the file is moved and its package is updated
                    val fileModified = referencingFile.updateImportsOfMovedReferencedClasses(
                        referencedClasses,
                        oldPackageName,
                    )
                    if (fileModified) {
                        modifiedFilesCount += 1
                    }
                }
            }

            TransformationResult.Success(
                message = "Moved ${virtualFile.name} from package '$oldPackageName' to '$newPackageName'",
                filesModified = modifiedFilesCount,
            )
        } catch (e: Exception) {
            logger.error("Failed to move file ${virtualFile.name}", e)
            TransformationResult.Failure("Failed to move file ${virtualFile.path}: ${e.message}", e)
        }
    }

    data class DestinationDirectory(
        val directory: VirtualFile,
        val packageName: String,
    )

    private fun findSuitableDestinationDirectory(
        project: Project,
        projectRoot: String,
        oldPackageName: String,
        suggestions: List<String>,
    ): Result<DestinationDirectory> {
        val fileIndex = ProjectFileIndex.getInstance(project)

        /**
         * Produces a side effect for the unsuccessful suggestions:
         * The directory path of the suggestion gets created on the file system.
         */
        fun toDestinationDirectoryIfSuitable(suggestion: String): Result<DestinationDirectory> {
            val targetDirectoryPath = suggestion
            logger.info("Considering suggested target directory: $targetDirectoryPath")

            // in case when the suggestion API returns paths relative to the project root
            val absoluteTargetPath = when {
                targetDirectoryPath.toPath().isAbsolute -> targetDirectoryPath
                else -> File(projectRoot, targetDirectoryPath).canonicalPath
            }
            logger.info("Resolved absolute target path: $absoluteTargetPath")

            // verify the target directory is under a source root
            // Create a directory structure if it doesn't exist, then get VirtualFile
            // val targetFile = File(absoluteTargetPath)
            // Files.createDirectories(targetFile.toPath())
            // val targetVirtualFile = VfsUtil.find(targetFile, /* refreshIfNeeded = */ true)
            val targetVirtualFile = VfsUtil.createDirectories(absoluteTargetPath)
                ?: return Result.failure(TransformationStepFailed(
                    "Cannot find virtual file for target: $absoluteTargetPath"))

            val targetSourceRoot = fileIndex.getSourceRootForFile(targetVirtualFile)
                ?: return Result.failure(TransformationStepFailed(
                    "Target directory $targetDirectoryPath is not under any source root in the project"))

            // Calculate new package name relative to source root
            // val targetSourceRootPath = File(targetSourceRoot.path)
            val newPackageName = when {
                // targetFile.startsWith(targetSourceRootPath)
                absoluteTargetPath.startsWith(targetSourceRoot.path) -> {
                    val target = File(absoluteTargetPath)
                    val sourceRoot = File(targetSourceRoot.path)

                    val relativePath = target.relativeTo(sourceRoot).path
                    if (relativePath.isEmpty()) "" else relativePath.replace(File.separatorChar, '.')
                }
                else -> null
            }
            logger.info("""
                Suggestion: $suggestion
                oldPackageName: $oldPackageName
                newPackageName: $newPackageName (isValid=${newPackageName?.isValidPackageName()})
                targetSourceRoot: $targetSourceRoot
            """.trimIndent())
            // validate package
            when {
                newPackageName == null -> return Result.failure(TransformationStepFailed(
                    "Cannot create new package name for target directory $absoluteTargetPath: " +
                            "target directory is not under source root ${targetSourceRoot.path}"
                ))
                newPackageName == oldPackageName -> return Result.failure(TransformationStepFailed(
                "The new package equals to the original one: $newPackageName. " +
                        "The suggested directory would remain unchanged: $absoluteTargetPath"
                ))
                !newPackageName.isValidPackageName() -> return Result.failure(TransformationStepFailed(
                    "Suggestion $absoluteTargetPath leads to invalid package name: $newPackageName"
                ))
            }

            return Result.success(DestinationDirectory(
                directory = targetVirtualFile,
                packageName = newPackageName
            ))
        }

        /**
         * Turning into sequence to lazily evaluate suggestions:
         * The `firstOrNull` at the end ensures only the minimal
         * number of suggestions is evaluated.
         */
        val suitableResult = suggestions.asSequence()
            .map { suggestion ->
                val result = toDestinationDirectoryIfSuitable(suggestion)
                if (result.isFailure) {
                    logger.warn(
                        "Failed to create target directory for suggestion $suggestion: ${result.exceptionOrNull()?.message}")
                }
                result
            }
            .firstOrNull { it.isSuccess }

        if (suitableResult == null) {
            return Result.failure(TransformationStepFailed(
                "No suitable target directory found among suggestions: ${suggestions.joinToString(",")}"))
        }

        return suitableResult


        /*
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

        val targetVirtualFile = VfsUtil.findFileByIoFile(targetFile, *//* refreshIfNeeded = *//* true)
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

        return Result.success(
            DestinationDirectory(
                directory = targetVirtualFile,
                // TODO: remove !!
                packageName = newPackageName!!,
            )
        )
        */
    }


    /**
     * Collects all public classes and **interfaces** from the given Java file.
     */
    private fun PsiFile.collectPublicClasses(): List<PsiClass> {
        val psiFile = this
        val publicClasses: List<PsiClass> = buildList {
            psiFile.accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    super.visitElement(element)
                    // TODO: public abstract classes included? enums? what else?
                    if (element is PsiClass && element.hasModifierProperty(PsiModifier.PUBLIC)) {
                        // adding this class to the list of public classes
                        add(element)
                    }
                }
            })
        }
        return publicClasses
    }

    /**
     * Finds all Java files that reference any of the given classes.
     */
    private fun List<PsiClass>.findReferencingFiles(project: Project): Map<PsiJavaFile, Set<PsiClass>> {
        val classes = this
        val searchScope = GlobalSearchScope.projectScope(project)
        val referencingFilesToClasses = mutableMapOf<PsiJavaFile, MutableSet<PsiClass>>()

        for (psiClass in classes) {
            val referencingFiles = psiClass.findReferencingFiles(searchScope)

            // val references = ReferencesSearch.search(psiClass, searchScope).findAll()
            for (referencingFile in referencingFiles) {
                // don't add a file that contains the given class
                if (referencingFile is PsiJavaFile && referencingFile != psiClass.containingFile) {
                    if (!referencingFilesToClasses.containsKey(referencingFile)) {
                        referencingFilesToClasses[referencingFile] = mutableSetOf(psiClass)
                    } else {
                        referencingFilesToClasses[referencingFile]!!.add(psiClass)
                    }
                }
            }
        }

        return referencingFilesToClasses
    }

    /**
     * Returns a set of files that reference the given [PsiClass].
     * **May include the file that contains the given [PsiClass]**
     */
    private fun PsiClass.findReferencingFiles(searchScope: SearchScope): Set<PsiFile> {
        return ReferencesSearch.search(this, searchScope)
            .findAll()
            .map { it.element.containingFile }
            .toSet()
    }

    // TODO: write comment that these methods require clients to wrap them with write actions
    /**
     * Updates the package statement in the moved file.
     */
    private fun PsiJavaFile.updatePackageStatement(newPackageName: String) {
        val file = this
        val elementFactory = JavaPsiFacade.getElementFactory(file.project)
        val newPackageStatement = elementFactory.createPackageStatement(newPackageName)

        val existingPackageStatement = file.packageStatement
        if (existingPackageStatement != null) {
            existingPackageStatement.replace(newPackageStatement)
        } else {
            // If no package statement exists, add one at the beginning
            val firstChild = file.firstChild
            if (firstChild != null) {
                file.addBefore(newPackageStatement, firstChild)
            }
        }
    }

    /**
     * TODO: descr
     *
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
                                if (qualifiedName != null && !psiFile.imports(qualifiedName)) {
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

            // TODO: check that this import is not present already
            if (importList != null) {
                importList.add(importStatement)
                logger.info("  - Added import for `${reference.qualifiedName}`: ${importStatement.text}")
            }
        }
    }

    private fun VirtualFile.moveAndUpdatePackage(
        project: Project,
        requestor: Any,
        where: VirtualFile,
        packageName: String
    ): Result<PsiJavaFile> {
        // moving the file
        val fileToMove = this
        fileToMove.move(requestor, where)

        // refresh and get updated PSI
        val movedPsiFile = PsiManager.getInstance(project).findFile(fileToMove) as? PsiJavaFile
            ?: return Result.failure(
                TransformationStepFailed("Cannot find moved file ${fileToMove.path}"))

        // updating package statement
        movedPsiFile.updatePackageStatement(packageName)

        return Result.success(movedPsiFile)
    }

    /**
     * Updates the import statements in the current Java file for a set of referenced classes
     * that have been moved to a different package. This method ensures that the imports
     * are updated or added based on whether the referenced classes are from a different package
     * or the same package as the current file.
     *
     * @param referencedClasses A set of `PsiClass` instances representing the classes that
     *                          need to have their import statements updated in the current file.
     * @param oldPackageName The previous package name of the referenced classes, which is used
     *                       to determine if the current file was in the same package as the moved classes.
     * @return `true` if the file was modified, false otherwise.
     */
    private fun PsiJavaFile.updateImportsOfMovedReferencedClasses(
        referencedClasses: Set<PsiClass>,
        oldPackageName: String,
    ): Boolean {
        // The referencing file can be either:
        //   1. From a different package -> update its import of the referenced class
        //   2. Within the same package, hence, it may not have an import of the referenced class -> add a new import
        val referencingFile = this
        val elementFactory = JavaPsiFacade.getElementFactory(project)
        val importList = referencingFile.importList ?: return false

        var fileModified = false
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
                    fileModified = true
                }
                referencingFile.packageName == oldPackageName -> {
                    // otherwise, if a referencing file was within the same package
                    // as the moved file, add a new import statement
                    logger.info("Adding a new import into the import list of `${referencingFile.name}`: `${newImportStatement.text}` (for the qualified name: ${reference.qualifiedName})")
                    importList.add(newImportStatement)
                    fileModified = true
                }
                else -> logger.error("Cannot find/add import statement for `${reference.qualifiedName}` in `${referencingFile.virtualFile.path}`. The transformation may be incorrect.")
            }
        }

        return fileModified
    }

    /**
     * Checks if the given file has an import for the specified qualified name.
     */
    private fun PsiJavaFile.imports(qualifiedName: String): Boolean {
        val importList = this.importList ?: return false
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
    private fun String.isValidPackageName(): Boolean {
        // default package
        val packageName = this
        if (packageName.isEmpty()) {
            return true
        }
        return packageName.split('.').all { part ->
            part.isNotEmpty() && part[0].isJavaIdentifierStart() && part.all { it.isJavaIdentifierPart() }
        }
    }

    companion object {
        const val ID = "move-file-to-ai-suggested-directory-transformation"
    }
}




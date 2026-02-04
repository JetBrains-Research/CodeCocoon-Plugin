package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import com.github.pderakhshanfar.codecocoonplugin.common.TransformationStepFailed
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch

class MoveFileToNewDirectoryTransformationImpl {
    private val logger = thisLogger().withStdout()

    /**
     * @param psiFile PSI file of the file to move
     * @param virtualFile VFS file of the file to move
     * @param where the target directory to which the file should be moved
     */
    fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile,
        where: VirtualFile,
        newPackageName: String,
        requestor: Any
    ): TransformationResult {
        val project = psiFile.project
        // validate that this is a Java file
        if (psiFile !is PsiJavaFile) {
            return TransformationResult.Failure("File ${virtualFile.path} is not a Java file")
        }

        val oldPackageName = psiFile.packageName

        logger.info("Destination directory: ${where.path}, and new package: $newPackageName")
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
                where = where,
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

        return TransformationResult.Success(
            message = "Moved ${virtualFile.name} from package '$oldPackageName' to '$newPackageName'",
            filesModified = modifiedFilesCount,
        )
    }

    /**
     * Collects all public classes and **interfaces** from the given Java file.
     *
     * Read-only operation.
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
     *
     * Read-only operation.
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
     *
     * Read-only operation.
     */
    private fun PsiClass.findReferencingFiles(searchScope: SearchScope): Set<PsiFile> {
        return ReferencesSearch.search(this, searchScope)
            .findAll()
            .map { it.element.containingFile }
            .toSet()
    }

    /**
     * Updates the package statement in the moved file.
     *
     * NOTES:
     * 1. **REQUIRES the write action scope**.
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
     * Adds import statements for all classes within the specified package that are referenced
     * in the current Java file but are missing from the file's import list. This ensures that
     * the moved file has the necessary imports to resolve unqualified class references from
     * the original package.
     *
     * NOTES:
     * 1. **REQUIRES the write action scope**.
     *
     * @param fromPackage The fully qualified name of the package that contains the classes
     *                    to be imported into the current Java file.
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

            // import list doesn't contain this import statement
            if (importList != null && importList.importStatements.none { it.qualifiedName == importStatement.qualifiedName }) {
                importList.add(importStatement)
                logger.info("  - Added import for `${reference.qualifiedName}`: ${importStatement.text}")
            }
        }
    }

    /**
     * Moves the current `VirtualFile` to a specified directory and updates its package statement
     * according to the provided package name.
     *
     * NOTES:
     * 1. **REQUIRES the write action scope**.
     *
     * @param project The IntelliJ Platform `Project` in which the file resides.
     * @param requestor The entity requesting the move operation, used to ensure proper permissions.
     * @param where The destination `VirtualFile` directory where the file should be moved.
     * @param packageName The new package name to update in the file after the move.
     * @return A `Result` object containing the updated `PsiJavaFile` if the operation succeeds,
     *         or a failure if the file cannot be moved or updated.
     */
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
     * NOTES:
     * 1. **REQUIRES the write action scope**.
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
}
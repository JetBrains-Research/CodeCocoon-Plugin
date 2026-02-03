package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.common.Language
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.suggestions.SuggestionsApi
import com.github.pderakhshanfar.codecocoonplugin.transformation.requireOrDefault
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
            } else if (newPackageName == oldPackageName) {
                // TODO: iterate through the suggested file location
                return TransformationResult.Skipped(
                    "The new package equals to the original one: $newPackageName. " +
                        "The suggested directory would remain unchanged: $absoluteTargetPath")
            }

            // Validate package name
            if (!isValidPackageName(newPackageName)) {
                return TransformationResult.Failure("Invalid package name: $newPackageName")
            }

            logger.info("New package will be: $newPackageName")

            var modifiedFilesCount = 0




            // add imports to the to-be-moved file that will be missing after the move operation
            val referencesToImport: List<PsiClass> = buildList {
                psiFile.accept(object : PsiRecursiveElementVisitor() {
                    override fun visitElement(element: PsiElement) {
                        super.visitElement(element)
                        if (element is PsiJavaCodeReferenceElement) {
                            // or element.getElement()?
                            val resolved = element.resolve()

                            println("Considering resolved instance of element ${element.qualifiedName} (isResolvedNull=${resolved == null}, isResolvedPsiClass=${resolved is PsiClass}) with text:\n'''\n${resolved?.text?.take(200)}\n'''")

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
            val elementFactory = JavaPsiFacade.getElementFactory(project)

            // importing references (namely, classes) into the considered PSI file BEFORE moving this file
            for (reference in referencesToImport) {
                val importStatement = elementFactory.createImportStatement(reference)

                logger.info("Adding a new import into the import list of ${psiFile.name}: `${importStatement.text}` (for the qualified name: ${reference.qualifiedName})")

                if (importList != null) {
                    importList.add(importStatement)
                    logger.info("  - Added import for `${reference.qualifiedName}`: ${importStatement.text}")
                }
            }




            // ==== Update imports in other files that reference classes to be moved ==== //

            // Collect public classes/interfaces that will need import updates in other files
            val publicClasses: List<PsiClass> = collectPublicClasses(psiFile)
            val publicQualifiedNames = publicClasses.mapNotNull { it.qualifiedName }.toSet()
            logger.info("Found ${publicClasses.size} public classes of `${psiFile.name}` file: ${publicClasses.map { it.name }} -> corresponding qualified names ${publicQualifiedNames.toList()} (their imports in other files will be updated with a new package $newPackageName)")

            // Find all files that reference these classes
            val referencedClassesInFiles: Map<PsiJavaFile, Set<PsiClass>> = findReferencingFiles(project, publicClasses)
            logger.info("References from the `${psiFile.name}` file: ${referencedClassesInFiles.map { (key, value) -> "${key.name} -> ${value.map { it.name }}" }}")

            logger.info("Updating imports in referencing files...")

            for ((referencingFile, referencedClasses) in referencedClassesInFiles) {
                // a referencing file can be either:
                //   1. From a different package -> update its import of the referenced class
                //   2. Within the same package, hence, it may not have an import of the referenced class -> add a new import
                val importList = referencingFile.importList ?: continue

                for (reference in referencedClasses) {
                    // TODO: no use of !!
                    val newImportedName = reference.qualifiedName!!.replaceFirst(oldPackageName, newPackageName)
                    val newImportStatement = elementFactory.createImportStatementOnDemand(newImportedName)

                    // search for the import statement that corresponds to the referenced class
                    val oldImportStatement = importList.importStatements.find { it.qualifiedName == reference.qualifiedName }

                    if (oldImportStatement != null) {
                        // update this import statement with the new package prefix
                        logger.info("Replacing import in `${referencingFile.name}` `${oldImportStatement.text}` -> `${newImportStatement.text}`")
                        oldImportStatement.replace(newImportStatement)
                    } else if (referencingFile.packageName == oldPackageName) {
                        // otherwise, if a referencing file is within the same package
                        // as the file to be moved, add a new import statement
                        logger.info("Adding a new import into the import list of `${referencingFile.name}`: `${newImportStatement.text}` (for the qualified name: ${reference.qualifiedName})")
                        importList.add(newImportStatement)
                    }
                }

                // updateImportsInReferencingFile(referencingFile, oldPackageName, newPackageName, publicClasses)
                // modifiedFilesCount += 1


                // TODO: remove the below to pieces
                // collect imports that need to be updated with the new package prefix, i.e.:
                // `import oldPackageName.SymbolName` -> `import newPackageName.SymbolName`
                val importsToUpdate: List<PsiImportStatement> = buildList {
                    for (importStatement in importList.importStatements) {
                        val importedName = importStatement.qualifiedName ?: continue
                        if (importedName in publicQualifiedNames) {
                            add(importStatement)
                        }
                    }
                }
                // update the imports according to the rule above
                for (oldImportStatement in importsToUpdate) {
                    val qualifiedName = oldImportStatement.qualifiedName ?: continue
                    val newImportedName = qualifiedName.replaceFirst(oldPackageName, newPackageName)
                    val newImportStatement = elementFactory.createImportStatementOnDemand(newImportedName)

                    logger.info("Replacing import in `${referencingFile.name}` file: `$qualifiedName` -> `$newImportedName` (new import statement: `${newImportStatement.text}`)")
                    oldImportStatement.replace(newImportStatement)
                }
            }



            /*
            val movedPsiFile = WriteCommandAction.runWriteCommandAction<PsiJavaFile>(project) {
                // Step 4: Move the file
                logger.info("Moving file to new location...")
                virtualFile.move(requestor, targetVirtualFile)

                // Refresh and get updated PSI
                val movedPsiFile = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile
                    ?: throw IllegalStateException("Cannot find moved file")

                // Step 5: Update package statement
                logger.info("Updating package statement to $newPackageName")
                updatePackageStatement(movedPsiFile, newPackageName)
                modifiedFilesCount += 1

                movedPsiFile
            }

            // use com.intellij.openapi.application.smartReadAction / NonBlockingReadAction(...).inSmartMode()
            // Step 6: Fix imports in the moved file
            logger.info("Fixing imports in moved file...")
            fixImportsInMovedFile(movedPsiFile, oldPackageName)

            // Step 7: Update imports in other files that reference moved classes
            logger.info("Updating imports in ${referencingFiles.size} referencing files...")
            for (referencingFile in referencingFiles) {
                updateImportsInReferencingFile(referencingFile, oldPackageName, newPackageName, publicClasses)
                modifiedFilesCount += 1
            }
            */

            TransformationResult.Success(
                message = "Moved ${virtualFile.name} from package '$oldPackageName' to '$newPackageName'",
                filesModified = modifiedFilesCount
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
    private fun findReferencingFiles(project: Project, classes: List<PsiClass>): Map<PsiJavaFile, Set<PsiClass>> {
        val referencedClassesInFiles = mutableMapOf<PsiJavaFile, MutableSet<PsiClass>>()
        // val referencingFiles = mutableSetOf<PsiJavaFile>()
        val searchScope = GlobalSearchScope.projectScope(project)

        for (psiClass in classes) {
            val references = ReferencesSearch.search(psiClass, searchScope).findAll()
            for (reference in references) {
                val containingFile = reference.element.containingFile
                if (containingFile is PsiJavaFile && containingFile != psiClass.containingFile) {
                    // referencingFiles.add(containingFile)
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
     * Fixes imports in the moved file by adding explicit imports for classes
     * that were previously accessible from the same package without imports.
     */
    private fun fixImportsInMovedFile(movedFile: PsiJavaFile, oldPackageName: String) {
        // TODO: look at `movedFile.implicitlyImportedPackages`
        val project = movedFile.project
        val elementFactory = JavaPsiFacade.getElementFactory(project)

        val referencesToFix: List<PsiClass> = buildList {
            // Find all unqualified references in the moved file
            movedFile.accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    super.visitElement(element)
                    if (element is PsiJavaCodeReferenceElement) {
                        // val resolved = element.resolve()
                        println("Considering reference: ${element.qualifiedName} with text (isPsiClass=${element is PsiClass}):\n'''\n${element.text}\n'''")
                        if (element/*resolved*/ is PsiClass) {
                            // Check if this class is from the old package and not imported
                            val resolvedFile = element/*resolved*/.containingFile as? PsiJavaFile
                            if (resolvedFile != null && resolvedFile.packageName == oldPackageName) {
                                // Check if there's already an import for this class
                                val qualifiedName = element/*resolved*/.qualifiedName
                                if (qualifiedName != null && !hasImport(movedFile, qualifiedName)) {
                                    // add into the lists of references to fix
                                    add(element/*resolved*/)
                                }
                            }
                        }
                    }
                }
            })
        }
        logger.info("Found ${referencesToFix.size} unqualified references in moved file to fix: ${referencesToFix.map { it.name }}")

        // Add imports for all references that need fixing
        val importList = movedFile.importList
        logger.info("Import list of ${movedFile.name}: isNull=${importList == null}")
        logger.info("Import list of ${movedFile.name}: ${importList?.text ?: importList}")

        for (classToImport in referencesToFix) {
            val qualifiedName = classToImport.qualifiedName
            // TODO: need any update on VFS? is simply adding to the list enough?
            val importStatement = elementFactory.createImportStatement(classToImport)

            logger.info("Adding a new import into the import list of ${movedFile.name}: `${importStatement.text}` ($qualifiedName)")

            if (qualifiedName != null && importList != null) {
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
# Agent Documentation

This file provides context for AI agents (like Claude) working with the CodeCocoon plugin codebase.

## Quick Reference

**For comprehensive project documentation, see [`CLAUDE.md`](./CLAUDE.md).**

## What is CodeCocoon?

CodeCocoon is an IntelliJ Platform plugin for **metamorphic testing** of Java projects. It applies semantic-preserving transformations (renaming, moving files) to verify software behavior and test suite robustness.

## Key Points for AI Agents

1. **Multi-module project**: `core/` (abstractions), `java/` (Java markers), `src/main/` (IntelliJ implementation)

2. **Transformation system**:
   - Base: `Transformation` interface
   - IntelliJ: `IntelliJAwareTransformation` (PSI-aware)
   - Self-managed: `SelfManagedTransformation` (uses refactoring processors)

3. **Built-in transformations**:
   - `rename-method-transformation` - Rename methods via LLM suggestions (supports annotation whitelist/blacklist)
   - `rename-class-transformation` - Rename classes via LLM suggestions (supports annotation whitelist/blacklist)
   - `rename-variable-transformation` - Rename fields/parameters/locals via LLM suggestions (supports annotation blacklist only)
   - `move-file-into-suggested-directory-transformation/ai` - Move files (LLM suggests destination)
   - `move-file-into-suggested-directory-transformation/config` - Move files (config specifies destination)
   - `add-comment-transformation` - Example transformation (adds comment to file start)

4. **LLM Integration**: Uses Grazie/Koog to generate semantically similar names

5. **Memory System**:
   - Persistent cache storing LLM suggestions in `.codecocoon-memory/<project-name>.json`
   - Signature-based: Each element (class/method/variable/file) gets unique signature
   - Controlled via `useMemory` and `generateWhenNotInMemory` config options
   - Auto-saves on transformation completion via `PersistentMemory.use {}`

6. **Annotation Filtering**:
   - **Methods/Classes**: Support both whitelist and blacklist modes
     - **Whitelist mode**: Only rename elements WITH specified annotations
     - **Blacklist mode** (recommended): Rename all EXCEPT those with specified annotations
   - **Variables**: Support blacklist mode only (no whitelist)
   - **`"_default"` keyword**: Merges 35-40+ framework annotations with custom ones
     - Methods: 40+ annotations (Spring, JPA, JAX-RS, JUnit, etc.)
     - Classes: 25+ annotations (JPA, Spring, JAX-RS, JAXB, etc.)
     - Variables: 35+ annotations (JPA, Jackson, JAXB, Spring, validation, CDI, etc.)
   - Warning logged if blacklist used without `"_default"`

7. **Configuration**: `codecocoon.yml` in project root defines transformations and target files

8. **Important Threading Rules**:
   - PSI reads require `readAction { }` or `IntelliJAwareTransformation.withReadAction { }`
   - PSI writes require `writeCommandAction { }` or use self-managed refactoring processors

9. **Import Optimization Prevention**: Code style settings configured to prevent wildcard imports and minimize automatic import modifications
   - ✅ Prevents wildcard imports (`import package.*`)
   - ✅ Forces single class imports
   - ❌ Cannot prevent unused import removal (IntelliJ limitation)

## Common Tasks

- **Adding a transformation**: Implement `IntelliJAwareTransformation`, register in `TransformationRegistry.kt`
- **Running the plugin**: `./gradlew runIde -PcodecoonConfig=/path/to/codecocoon.yml`
- **Finding transformation logic**: Check `src/main/kotlin/.../components/transformations/`
- **Understanding execution flow**: Start at `HeadlessModeStarter.kt` → `TransformationService.kt` → `IntelliJTransformationExecutor.kt`

## When to Consult CLAUDE.md

Refer to [`CLAUDE.md`](./CLAUDE.md) for:
- **Import optimization prevention** - Detailed explanation of settings and limitations
- Detailed architecture explanations
- PSI utilities and helper functions
- Configuration schema and examples
- Transformation implementation details
- Memory system internals (`PsiSignatureGenerator`, signature format)
- Annotation filtering implementation (whitelist/blacklist logic)
- Error handling patterns
- File structure overview
- Dependencies and testing

## Project Context

This plugin is designed for **research purposes** to apply controlled metamorphic transformations to Java codebases, enabling:
- Detection of flaky tests
- Verification of test suite effectiveness
- Identification of semantic bugs
- Automated refactoring validation

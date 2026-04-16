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
   - `rename-method-transformation` - Rename methods via LLM suggestions
   - `rename-class-transformation` - Rename classes
   - `rename-variable-transformation` - Rename fields/parameters/locals
   - `move-file-into-suggested-directory-transformation/ai` - Move files (LLM suggests destination)
   - `move-file-into-suggested-directory-transformation/config` - Move files (config specifies destination)

4. **LLM Integration**: Uses Grazie/Koog to generate semantically similar names

5. **Memory System**: Caches LLM suggestions in `.codecocoon-memory/` to avoid redundant API calls

6. **Configuration**: `codecocoon.yml` in project root defines transformations and target files

7. **Important Threading Rules**:
   - PSI reads require `readAction { }` or `IntelliJAwareTransformation.withReadAction { }`
   - PSI writes require `writeCommandAction { }` or use self-managed refactoring processors

8. **Import Optimization Prevention**: Code style settings configured to prevent wildcard imports and minimize automatic import modifications
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
- Error handling patterns
- File structure overview
- Dependencies and testing

## Project Context

This plugin is designed for **research purposes** to apply controlled metamorphic transformations to Java codebases, enabling:
- Detection of flaky tests
- Verification of test suite effectiveness
- Identification of semantic bugs
- Automated refactoring validation

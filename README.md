# CodeCocoon-Plugin

![Build](https://github.com/JetBrains-Research/CodeCocoon-Plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)


## Configuration

### The config file (codecocoon.yml)
The plugin requires a YAML configuration file. By default, it looks for `codecocoon.yml` in the project root.
Alternatively, you can overwrite the path in `gradle.properties`:
```properties
codecocoon.config=/path/to/config.yml
```

Example structure for the config file:
```yaml
# Absolute or project-local path to the root of the project you want to transform
projectRoot: "/absolute/path/to/your/project"

# Optional: limit transformations to these files (relative to the root). Leave empty to target the entire project
files: ['path/to/file1.kt', 'path/to/file2.kt']

# The transformation pipeline. Order matters. Each transformation has:
#   - id: unique identifier
#   - config: arbitrary nested settings; only the selected transformation should interpret it
transformations:
  - id: "TransformationA"
    config:
      prefix: "Tmp_"
      includeScopes:
        - "src/main"
```

## Memory System

CodeCocoon includes a **persistent memory system** that caches LLM-generated suggestions to avoid redundant API calls and ensure consistency across runs. Memory is stored in `.codecocoon-memory/` directory as JSON files, one per project.

**Key features:**
- Signature-based caching: Each renamed element (class/method/variable) gets a unique signature
- Automatic persistence: Memory is saved automatically when transformations complete
- Reusability: Run the same transformation multiple times without re-querying the LLM
- Optional generation: Configure whether to generate new suggestions for missing entries

All renaming transformations support memory via `useMemory` and `generateWhenNotInMemory` config options.

## Built-in Transformations

### 1. Rename Method (`rename-method-transformation`)

Renames Java methods to LLM-suggested, semantically similar names and updates all usages/overrides. Processes methods in **overload families** to ensure consistency.

**Filters (methods are skipped if):**
- Override super methods
- In test sources
- In interfaces extending library interfaces
- Belong to library classes
- Are constructors or Object methods (equals, hashCode, toString, etc.)
- Match excluded patterns (toString, get*, set*, is*)
- Have no public references
- Referenced from non-Java/Kotlin files
- Fail annotation filter (whitelist/blacklist mode)

**Configuration:**
```yaml
- id: "rename-method-transformation"
  config:
    # Memory configuration
    useMemory: true                    # Optional, default: false. Use cached suggestions
    generateWhenNotInMemory: true      # Optional, default: false. Generate if not cached
    searchInComments: false            # Optional, default: false. Rename in comments too

    # Annotation filtering (choose whitelist OR blacklist mode)
    annotationFilterMode: "blacklist"  # Optional, default: "blacklist" if blacklistedAnnotations non-empty, else "whitelist"

    # Blacklist mode (recommended): Rename all methods EXCEPT those with these annotations
    blacklistedAnnotations:
      - "_default"                     # Special keyword: includes 40+ framework annotations (Spring, JPA, JAX-RS, JUnit, etc.)
      - "MyCustomAnnotation"           # Add your own annotations

    # Whitelist mode: Only rename methods WITH these annotations
    whitelistedAnnotations:
      - "SuppressWarnings"
      - "Deprecated"
```

**Annotation filter modes:**
- **Blacklist** (recommended): Rename everything EXCEPT framework-managed methods. Use `"_default"` to include all standard framework annotations (Spring `@RequestMapping`, JPA `@PrePersist`, JAX-RS `@GET`/`@POST`, JUnit `@Test`/`@BeforeEach`, etc.) plus custom ones.
- **Whitelist**: Only rename methods with specific annotations. Empty whitelist = only non-annotated methods.
- **⚠ Warning**: Omitting `"_default"` in blacklist mode will NOT exclude framework annotations automatically.

---

### 2. Rename Class (`rename-class-transformation`)

Renames Java classes to LLM-suggested, semantically similar names and updates all usages.

**Filters (classes are skipped if):**
- Referenced from non-Java files
- In test sources
- Class name is null or ≤1 character
- Fail annotation filter (whitelist/blacklist mode)

**Configuration:**
```yaml
- id: "rename-class-transformation"
  config:
    # Memory configuration
    useMemory: true                    # Optional, default: false
    generateWhenNotInMemory: true      # Optional, default: false
    searchInComments: false            # Optional, default: false

    # Annotation filtering (choose whitelist OR blacklist mode)
    annotationFilterMode: "blacklist"  # Optional, default: "blacklist" if blacklistedAnnotations non-empty, else "whitelist"

    # Blacklist mode (recommended): Rename all classes EXCEPT those with these annotations
    blacklistedAnnotations:
      - "_default"                     # Special keyword: includes 25+ framework annotations (JPA, Spring, JAX-RS, JAXB, etc.)
      - "MyCustomAnnotation"

    # Whitelist mode: Only rename classes WITH these annotations
    whitelistedAnnotations:
      - "Deprecated"
```

**Annotation filter modes:** Same as rename-method (see above). Default blacklist includes JPA `@Entity`/`@Table`, Spring `@Component`/`@Service`/`@Controller`, JAX-RS `@Path`, JAXB `@XmlRootElement`, etc.

---

### 3. Rename Variable (`rename-variable-transformation`)

Renames Java variables (fields, parameters, locals) to LLM-suggested, semantically similar names and updates all usages.

**Filters (variables are skipped if):**
- In test sources
- Enum constants
- Annotated with `@Column`
- Declared in library/compiled code
- Public/protected fields (to avoid breaking external consumers)

**Configuration:**
```yaml
- id: "rename-variable-transformation"
  config:
    useMemory: true                    # Optional, default: false
    generateWhenNotInMemory: true      # Optional, default: false
    searchInComments: false            # Optional, default: false
```

**Note:** Variable renaming does NOT support annotation filtering.

---

### 4. Move File (AI-Suggested) (`move-file-into-suggested-directory-transformation/ai`)

Moves Java files into directories suggested by an LLM based on file content and project structure.

**Filters (files are skipped if):**
- Not a Java file
- In test sources
- Contains package-local classes used by other files (would break compilation)

**Configuration:**
```yaml
- id: "move-file-into-suggested-directory-transformation/ai"
  config:
    useMemory: true                    # Optional, default: null (no memory)
    generateWhenNotInMemory: true      # Optional, default: false
    maxAgentIterations: 60             # Optional, default: 50. Max LLM iterations for directory search
```

---

### 5. Move File (Config-Specified) (`move-file-into-suggested-directory-transformation/config`)

Moves Java files into a specific directory provided in the configuration.

**Configuration:**
```yaml
- id: "move-file-into-suggested-directory-transformation/config"
  config:
    destination: "src/main/java/services/impl"  # Required. Absolute or relative to project root. Can be new or existing.
```

**Note:** This transformation does NOT use memory (destination is explicit).

---

### 6. Add Comment (`add-comment-transformation`)

**Example transformation** that adds a comment at the beginning of a file. Not for production use.

**Configuration:**
```yaml
- id: "add-comment-transformation"
  config:
    message: "This file was transformed"  # Required. Comment text (without "//" prefix)
```

## Template ToDo list
- [x] Create a new [IntelliJ Platform Plugin Template][template] project.
- [ ] Get familiar with the [template documentation][template].
- [ ] Adjust the [pluginGroup](./gradle.properties) and [pluginName](./gradle.properties), as well as the [id](./src/main/resources/META-INF/plugin.xml) and [sources package](./src/main/kotlin).
- [ ] Adjust the plugin description in `README` (see [Tips][docs:plugin-description])
- [ ] Review the [Legal Agreements](https://plugins.jetbrains.com/docs/marketplace/legal-agreements.html?from=IJPluginTemplate).
- [ ] [Publish a plugin manually](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginTemplate) for the first time.
- [ ] Set the `MARKETPLACE_ID` in the above README badges. You can obtain it once the plugin is published to JetBrains Marketplace.
- [ ] Set the [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html?from=IJPluginTemplate) related [secrets](https://github.com/JetBrains/intellij-platform-plugin-template#environment-variables).
- [ ] Set the [Deployment Token](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html?from=IJPluginTemplate).
- [ ] Click the <kbd>Watch</kbd> button on the top of the [IntelliJ Platform Plugin Template][template] to be notified about releases containing new features and fixes.
- [ ] Configure the [CODECOV_TOKEN](https://docs.codecov.com/docs/quick-start) secret for automated test coverage reports on PRs

<!-- Plugin description -->
This Fancy IntelliJ Platform Plugin is going to be your implementation of the brilliant ideas that you have.

This specific section is a source for the [plugin.xml](/src/main/resources/META-INF/plugin.xml) file which will be extracted by the [Gradle](/build.gradle.kts) during the build process.

To keep everything working, do not remove `<!-- ... -->` sections. 
<!-- Plugin description end -->

## Installation

- Using the IDE built-in plugin system:
  
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "CodeCocoon-Plugin"</kbd> >
  <kbd>Install</kbd>
  
- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/JetBrains-Research/CodeCocoon-Plugin/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation

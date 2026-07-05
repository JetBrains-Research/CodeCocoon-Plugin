# CodeCocoon-Plugin

![Build](https://github.com/JetBrains-Research/CodeCocoon-Plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

<!-- Plugin description -->
CodeCocoon is an IntelliJ Platform plugin for metamorphic testing of Java projects. It applies
semantic-preserving transformations, such as renaming and moving files, to verify software
behavior and test suite robustness.
<!-- Plugin description end -->

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
- Fail annotation filter (blacklist mode only - no whitelist support)
- Declared in library/compiled code
- Public/protected fields (to avoid breaking external consumers)

**Configuration:**
```yaml
- id: "rename-variable-transformation"
  config:
    # Memory configuration
    useMemory: true                    # Optional, default: false
    generateWhenNotInMemory: true      # Optional, default: false
    searchInComments: false            # Optional, default: false

    # Annotation blacklist filtering (no whitelist support)
    blacklistedAnnotations:
      - "_default"                     # Special keyword: includes 35+ framework annotations (JPA, Jackson, JAXB, Spring, validation, etc.)
      - "MyCustomAnnotation"           # Add your own annotations
```

**Annotation filtering (blacklist mode only):**
- **Blacklist mode**: Rename all variables EXCEPT those with specified annotations. Use `"_default"` to include JPA (`@Column`/`@Id`/`@JoinColumn`), Jackson (`@JsonProperty`), JAXB (`@XmlElement`/`@XmlAttribute`), Spring (`@Value`/`@Autowired`), validation (`@NotNull`/`@Size`/`@Email`), and CDI (`@Inject`) annotations.
- **⚠ Warning**: Omitting `"_default"` in blacklist will NOT exclude framework annotations automatically.
- **Note**: Variables do NOT support whitelist mode (methods/classes only).

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

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation

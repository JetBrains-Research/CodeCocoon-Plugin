package com.github.pderakhshanfar.codecocoonplugin.suggestions.impl

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.ext.agent.reActStrategy
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.rag.base.files.JVMFileSystemProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File


// TODO: use the `LLM` module somehow?
internal suspend fun suggestNewDirectoryImpl(
    token: String,
    model: LLModel,
    projectRoot: String,
    filepath: String,
    content: () -> String,
    existingOnly: Boolean = false
): List<String> {
    val executor = simpleOpenAIExecutor(token)

    val agent = AIAgent(
        promptExecutor = executor,
        // TODO: how to set temperature?
        agentConfig = AIAgentConfig(
            prompt = Prompt.build("file-relocator") {
                system(buildSystemPrompt(projectRoot, existingOnly))
            },
            model = model,
            maxAgentIterations = 15
        ),
        toolRegistry = ToolRegistry {
            tool(ListDirectoryTool(JVMFileSystemProvider.ReadOnly))
            tool(ReadFileTool(JVMFileSystemProvider.ReadOnly))
            tool(CheckSymbolConflictsTool(projectRoot))
        },
        strategy = reActStrategy(
            // Reason after every tool call
            name = "file_relocation_suggester",
            reasoningInterval = 1,
        ),
    ) {
        handleEvents {
            onToolCallStarting { ctx ->
                println("→ Calling `${ctx.tool.name}` with: ${ctx.toolArgs}")
            }
        }
    }

    val result = agent.run(agentInput = buildUserPrompt(filepath, content()))
    println("""
result:
'''
$result
'''
    """.trimIndent())

    return parseDirectorySuggestions(result)
}

private class CheckSymbolConflictsTool(
    private val projectRoot: String
) : SimpleTool<CheckSymbolConflictsTool.Args>() {
    override val name = "check_conflicts"
    override val argsSerializer = Args.serializer()
    override val description = "Checks if a class name conflicts with existing classes in a target directory"

    @Serializable
    data class Args(
        @property:LLMDescription("Simple class name without .java extension")
        val className: String,
        @property:LLMDescription("Target directory path to check")
        val targetDirectory: String
    )

    override suspend fun doExecute(args: Args): String {
        val targetDir = File(projectRoot, args.targetDirectory)
        val conflictingFile = File(targetDir, "${args.className}.java")

        return if (conflictingFile.exists()) {
            "CONFLICT: ${args.className}.java already exists in ${args.targetDirectory}"
        } else {
            "OK: No conflict detected for ${args.className} in ${args.targetDirectory}"
        }
    }
}

private fun buildSystemPrompt(projectRoot: String, existingOnly: Boolean): String {
    val basePrompt = """
        You are a Java code organization expert analyzing files to suggest optimal directory locations.
        
        PROJECT ROOT: $projectRoot
        
        ANALYSIS PROCESS (follow strictly):
        
        1. STRUCTURE DISCOVERY
           - Use `list_directory` to understand the project's existing package organization
           - Identify whether the project uses package-by-feature or package-by-layer
        
        2. CLASS PURPOSE IDENTIFICATION
           Classify based on these indicators (NOT always present!):
           
           DOMAIN ENTITY: @Entity, @Table, @Id, implements Serializable
           → Typical location: domain/, model/, entity/
           
           SERVICE: @Service, @Component, business logic methods, injected repositories
           → Typical location: service/, [feature]/service/
           
           REPOSITORY: extends Repository interfaces, @Repository, findBy*/save*/delete*
           → Typical location: repository/, [feature]/repository/
           
           CONTROLLER: @Controller, @RestController, @RequestMapping
           → Typical location: controller/, api/, web/
           
           UTILITY: static methods only, no instance state, generic helpers
           → Typical location: util/, common/, shared/
           
           CONFIGURATION: @Configuration, @Bean methods
           → Typical location: config/, infrastructure/
        
        3. SYMBOL CLASH DETECTION
           Check the target directory for:
           - Classes with identical simple names using the `check_conflicts` tool
           - Commonly imported java.util.* conflicts (List, Map, Set, Date)
           - java.lang.* shadows (String, Object, System)
        
        4. SUGGESTION GENERATION
           Output exactly 3-5 directory suggestions in this JSON format:
        ```json
           {
             "suggestions": [
               {
                    "path": "src/main/java/com/example/service",
                    "confidence": "high",
                    "reason": "...",
                    "conflicts": [{ "symbol": "Class/function name", "filepath": "target/path" }],
               },
               ...
             ],
             "file_purpose": "SERVICE"
           }
        ```
        
        CRITICAL RULES:
        - DO NOT output the same directory where the given file already resides!
        - Never suggest locations that would cause naming conflicts
        - Prefer existing directories unless the class clearly belongs to a new feature module
        - Consider import dependencies visible in the source file
        - Stay within the same module when dealing with multimodule environments to avoid missing dependencies
    """.trimIndent()

    val directoryConstraint = if (existingOnly) {
        """
        DIRECTORY CONSTRAINT: Only suggest directories that already exist in the project.
        Use `list_directory` to enumerate available directories before making suggestions.
        Never propose creating new package structures.
        """
    } else {
        """
        DIRECTORY FLEXIBILITY: You may suggest both existing directories and new paths.
        For new paths, follow the project's existing naming conventions.
        Prefer feature-based packages (e.g., com.app.user.service) for new structures.
        """
    }

    return basePrompt + "\n\n" + directoryConstraint
}

private fun buildUserPrompt(filepath: String, content: String): String = """
    Analyze this Java file and suggest appropriate directory locations.
    
    FILE PATH: $filepath
    
    FILE CONTENT (may be truncated):
    ```java
    $content
    ```
    
    First, use the `list_directory` tool to understand the current project structure.
    Then analyze the class and provide directory suggestions.
""".trimIndent()

// TODO: parse the requested JSON structure into data class, not list of strings
private fun parseDirectorySuggestions(llmOutput: String): List<String> {
    // Extract JSON from LLM response and parse suggestions
    val jsonPattern = Regex("""\{[\s\S]*"suggestions"[\s\S]*\}""")
    val match = jsonPattern.find(llmOutput) ?: return emptyList()

    return try {
        val json = Json.parseToJsonElement(match.value).jsonObject
        json["suggestions"]?.jsonArray?.mapNotNull { suggestion ->
            suggestion.jsonObject["path"]?.jsonPrimitive?.content
        } ?: emptyList()
    } catch (err: Exception) {
        println(err)
        emptyList()
    }
}
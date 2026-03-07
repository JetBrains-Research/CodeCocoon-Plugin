package com.github.pderakhshanfar.codecocoonplugin

import com.github.pderakhshanfar.codecocoonplugin.suggestions.SuggestionsApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test

@EnabledIfEnvironmentVariable(
    named = "GRAZIE_TOKEN",
    matches = ".*",
    disabledReason = "OpenAI Token should be set as `GRAZIE_TOKEN` to run the test suite",
)
class SuggestionsTest {
    private val token = System.getenv("GRAZIE_TOKEN")

    @Test
    fun test() = runTest {
        val projectRoot = getResource("sample-project")
        val filepath = projectRoot.resolve("src/main/java/package1/Component2.java")
        val content = filepath.toFile().readText()

        val suggestions = SuggestionsApi.suggestNewDirectory(
            token = token,
            projectRoot = projectRoot.toString(),
            filepath = filepath.toString(),
            content = { content },
            existingOnly = false,
        )

        println("suggestions:\n$suggestions")
    }

    fun getResource(resource: String): Path {
        val result = javaClass.classLoader.getResource(resource)?.toURI()?.let { Paths.get(it) }
        return result ?: throw IllegalArgumentException("Resource not found: $resource")
    }
}
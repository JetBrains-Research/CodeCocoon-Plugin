package com.github.pderakhshanfar.codecocoonplugin

import com.github.pderakhshanfar.codecocoonplugin.suggestions.SuggestionsApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
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
        val suggestions = SuggestionsApi.suggestNewDirectory(
            token = token,
            projectRoot = "/Users/vartiukhov/dev/projects/samples/ij-demo",
            filepath = "/Users/vartiukhov/dev/projects/samples/ij-demo/src/main/java/impl/others/library/A.java",
            content = {
                """
                    // Hello from `add-comment-transformation`!
                    
                    package impl.others.library;
                    
                    
                    /**
                     * interface for a letter "A".
                     */
                    public interface A extends B, C {}
                """.trimIndent()
            },
            existingOnly = false,
        )

        println("suggestions:\n$suggestions")
    }
}
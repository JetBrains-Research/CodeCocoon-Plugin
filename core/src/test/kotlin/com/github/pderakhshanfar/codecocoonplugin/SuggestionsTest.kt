package com.github.pderakhshanfar.codecocoonplugin

import com.github.pderakhshanfar.codecocoonplugin.suggestions.SuggestionsApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SuggestionsTest {
    @Test
    fun test() = runTest {
        val suggestions = SuggestionsApi.suggestNewDirectory(
            token = System.getenv("OPENAI_API_KEY"),
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
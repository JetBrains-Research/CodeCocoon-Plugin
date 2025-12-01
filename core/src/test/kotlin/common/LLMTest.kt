package common

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.jetbrains.research.codecocoon.grazie.convertToJBAI
import org.jetbrains.research.codecocoon.grazie.createGraziePromptExecutor
import kotlin.test.Test

class LLMTest {


    @Test
    fun `test simple request`() = runTest {
        val model = convertToJBAI(OpenAIModels.Chat.GPT5Mini)
        val executor = createGraziePromptExecutor(System.getenv("GRAZIE_TOKEN"))

        val llm = LLM(
            model = model,
            executor = executor,
        )
        val prompt = prompt("pizza-prompt") {
            system {
                +"You are an italian"
                +"Return the ingredients of the requested pizza"
            }
            user {
                +"What are the ingredients of a pizza calzone?"
            }
        }

        val pizzaMargheritaIngredientsExample =
            PizzaIngredients(listOf("Dough", "Tomato sauce", "Mozzarella cheese"))

        val result = llm.structuredRequest<PizzaIngredients>(
            prompt = prompt,
            examples = listOf(pizzaMargheritaIngredientsExample)
        )

        println(result)
    }

    @Serializable
    data class PizzaIngredients(val ingredients: List<String>)

}
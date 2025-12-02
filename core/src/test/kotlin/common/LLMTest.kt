package common

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Disabled
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.fail

class LLMTest {


//    @Disabled("LLM-related test - requires GRAZIE_TOKEN as env variable")
    @Test
    fun `test simple request`() = runTest {
        val reflectClass = Class.forName("org.jetbrains.research.codecocoon.grazie.MainKt")
        val convertToJBAI = reflectClass.methods.find { it.name == "convertToJBAI" }
        val createGraziePromptExecutor = reflectClass.methods.find { it.name == "createGraziePromptExecutor" }

        if (convertToJBAI == null || createGraziePromptExecutor == null) fail("Could not find grazie dependencies")

        val model: LLModel = convertToJBAI.invoke(null, OpenAIModels.Chat.GPT5Mini) as LLModel

        val executor: PromptExecutor = suspendCoroutineUninterceptedOrReturn { continuation ->
            createGraziePromptExecutor.invoke(null, System.getenv("GRAZIE_TOKEN"), continuation)
        }
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
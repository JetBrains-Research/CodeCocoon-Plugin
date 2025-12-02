package common

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Disabled
import kotlin.test.Test
import kotlin.test.assertEquals

class LLMTest {


    @Serializable
    data class PizzaIngredients(val ingredients: List<String>)

    @Disabled("LLM-related test - requires grazie to be available and GRAZIE_TOKEN as env variable")
    @Test
    fun `test simple request`() = runTest {
        val llm = LLM.fromGrazie(OpenAIModels.Chat.GPT5Mini, System.getenv("GRAZIE_TOKEN"))
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

    @Test
    fun `test returns successfully if the first response is parseable`() = runTest {
        val executor = FakeExecutor(originalUnsuccessfulAttempts = 0, fixingModelUnsuccessfulAttempts = 0)
        val originalModel = OpenAIModels.Chat.GPT5Mini
        val fixingModel = OpenAIModels.Chat.GPT5Mini
        val llm = LLM(originalModel, fixingModel, executor)
        val prompt = prompt("pizza-prompt") {
            system { +"You are an italian" }
            user { +"What are the ingredients of a pizza calzone?" }
        }

        val output = llm.structuredRequest<PizzaIngredients>(prompt, listOf(sampleTestStructure))
        assertEquals(1, executor.originalAttemptsCount)
        assertEquals(0, executor.fixingAttemptsCount)
        assertEquals(sampleTestStructure, output)
    }

    class FakeExecutor(
        private val originalUnsuccessfulAttempts: Int,
        private val fixingModelUnsuccessfulAttempts: Int,
    ) : PromptExecutor {
        var originalAttemptsCount = 0
        var fixingAttemptsCount = 0
        private lateinit var originalModel: LLModel
        private lateinit var fixingModel: LLModel

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): List<Message.Response> {
            setModels(model)
            val response = increaseAttemptsCountAndRespond(model)
            val metaInfo = ResponseMetaInfo.create(Clock.System)
            return listOf(Message.Assistant(response, metaInfo))
        }

        private fun setModels(model: LLModel) {
            if (!::originalModel.isInitialized) originalModel = model
            else if (::originalModel.isInitialized && model != originalModel) fixingModel = model
        }

        private fun increaseAttemptsCountAndRespond(model: LLModel): String = when (model) {
            originalModel -> {
                originalAttemptsCount++
                if (originalAttemptsCount > originalUnsuccessfulAttempts) {
                    parseableResponse
                } else {
                    nonParseableResponse
                }
            }

            fixingModel -> {
                fixingAttemptsCount++
                if (fixingAttemptsCount > fixingModelUnsuccessfulAttempts) {
                    parseableResponse
                } else {
                    nonParseableResponse
                }
            }

            else -> throw IllegalArgumentException("Unknown model")
        }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> {
            throw UnsupportedOperationException()
        }

        override suspend fun moderate(
            prompt: Prompt,
            model: LLModel
        ): ModerationResult {
            throw UnsupportedOperationException()
        }
    }

    companion object {
        private val sampleTestStructure = PizzaIngredients(listOf("Dough", "Tomato sauce", "Mozzarella cheese"))
        private val parseableResponse = Json.encodeToString(PizzaIngredients.serializer(), sampleTestStructure)
        private val nonParseableResponse = "Not a valid JSON"
    }

}
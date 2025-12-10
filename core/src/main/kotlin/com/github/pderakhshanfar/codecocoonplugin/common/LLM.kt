package com.github.pderakhshanfar.codecocoonplugin.common

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.agent.requestLLMStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.features.eventHandler.feature.EventHandlerConfig
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.StructureFixingParser
import ai.koog.prompt.structure.StructuredResponse
import ai.koog.prompt.text.text
import com.intellij.openapi.diagnostic.thisLogger
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class LLM(
    val model: LLModel,
    val fixingModel: LLModel = model,
    val executor: PromptExecutor,
) {
    companion object {
        suspend fun fromGrazie(model: LLModel, token: String) : LLM {
            if (!grazieIsAvailable()) throw IllegalStateException("Grazie is not available")
            return LLM(model = convertModel(model), executor = createExecutor(token))
        }

        private fun grazieIsAvailable(): Boolean {
            try {
                getMainKtGrazieClass()
            } catch (_: ClassNotFoundException) {
                return false
            }
            return true
        }

        private fun getMainKtGrazieClass(): Class<*> {
            return Class.forName("org.jetbrains.research.codecocoon.grazie.MainKt")
        }

        private fun convertModel(model: LLModel) : LLModel {
            val reflectClass = getMainKtGrazieClass()
            val convertToJBAI = reflectClass.methods.find { it.name == "convertToJBAI" }!!
            // We need to pass in the class when invoking a reflective method. In case the methods are static, we pass null.
            return convertToJBAI(null, model) as LLModel
        }

        private suspend fun createExecutor(token: String) : PromptExecutor {
            val reflectClass = getMainKtGrazieClass()
            val createGraziePromptExecutor = reflectClass.methods.find { it.name == "createGraziePromptExecutor" }!!
            // We need to pass in the continuation because it is a suspend function.
            val executor: PromptExecutor = suspendCoroutineUninterceptedOrReturn { continuation ->
                createGraziePromptExecutor(null, token, continuation)
            }
            return executor
        }

    }


    val handler = CustomEventHandler.create()

    /**
     * Performs a structured request to a large language model and returns a typed response.
     *
     * This method uses a zero-shot structured strategy to generate and parse responses
     * from a prompt-input interaction, allowing for structured outputs defined by the generic type `Output`.
     *
     * @param prompt The prompt containing messages and parameters for the language model.
     * @param examples A list of example outputs used for providing context to the model.
     * @param maxRetries The maximum number of retries for parsing a response. Defaults to 3.
     * @param maxFixingAttempts The maximum number of attempts to fix a parsing issue. Defaults to 1.
     * @return A structured response of type `Output`, or null if the request fails after the specified retries and fixing attempts.
     */
    suspend inline fun <reified Output> structuredRequest(
        prompt: Prompt,
        examples: List<Output> = emptyList(),
        maxRetries: Int = 3,
        maxFixingAttempts: Int = 1,
    ) : Output? {
        val runner = AIAgent(
            id = "structured_agent",
            promptExecutor = executor,
            toolRegistry = ToolRegistry.EMPTY,
            strategy = zeroShotStructuredStrategy<Output>(
                maxRetries, maxFixingAttempts, examples, fixingModel
            ),
            agentConfig = AIAgentConfig(
                prompt = Prompt(prompt.messages.dropLast(1), prompt.id, prompt.params),
                // max iterations can be controlled by maxRetries and maxFixingAttempts args
                // specifying a big number here because it's still required to pass this arg
                maxAgentIterations = 500,
                model = model,
            ),
        ) {
            install(EventHandler) {
                handler()
            }
        }
        return runner.run(prompt.messages.last().content).getOrNull()?.structure
    }

    /**
     * NOT TO BE USED OUTSIDE THIS CLASS. it cannot be private because it is inline
     */
    inline fun <reified Output> zeroShotStructuredStrategy(
        maxRetries: Int,
        maxFixingAttempts: Int,
        examples: List<Output> = emptyList(),
        fixingModel: LLModel,
    ) = functionalStrategy<String, Result<StructuredResponse<Output>>>("zero_shot_structured_strategy") { input ->

        var result: Result<StructuredResponse<Output>>? = null
        var newMessage: String = input

        for (attemptCount in 1..maxRetries.coerceAtLeast(1)) {
            result = requestLLMStructured(
                message = newMessage,
                examples = examples,
                fixingParser = StructureFixingParser(
                    fixingModel = fixingModel,
                    retries = maxFixingAttempts + 1, // + 1 because of an internal koog issue
                )
            )

            if (result.isSuccess) {
                break
            } else {
                val exception = result.exceptionOrNull()!!
                thisLogger().warn("Failed to parse structure. Attempt $attemptCount/$maxRetries failed")
                newMessage = text {
                    text("Your response is not parsable due to the following error: ")
                    text("${exception.message}. ")
                    text("Provide a parsable response")
                }
            }
        }

        result!!
    }

    private object CustomEventHandler {
        fun create(): EventHandlerConfig.() -> Unit = {
            onAgentExecutionFailed {
                thisLogger().error("Agent run error: ${it.throwable}")
            }

            onAgentCompleted {
                thisLogger().debug("Agent finished: ${it.agentId}")
                thisLogger().debug("Result: ${it.result}")
            }
        }
    }
}


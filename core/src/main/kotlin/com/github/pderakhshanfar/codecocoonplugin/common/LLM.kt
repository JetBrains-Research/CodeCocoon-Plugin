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

object CustomEventHandler {
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


class LLM(
    val model: LLModel,
    val fixingModel: LLModel = model,
    val executor: PromptExecutor,
) {
    val handler = CustomEventHandler.create()

    suspend inline fun <reified Output> structuredRequest(
        prompt: Prompt,
        examples: List<Output>,
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
}

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
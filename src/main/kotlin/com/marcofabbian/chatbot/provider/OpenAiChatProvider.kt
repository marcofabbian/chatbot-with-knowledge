package com.marcofabbian.chatbot.provider

import com.marcofabbian.chatbot.config.ChatbotProperties
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

@Service
@Profile("prod")
class OpenAiChatProvider(
    properties: ChatbotProperties,
    restClientBuilder: RestClient.Builder
) : ChatProvider {

    private val logger = LoggerFactory.getLogger(OpenAiChatProvider::class.java)

    private val model = properties.openai.model
    private val maxOutputTokens = properties.openai.maxOutputTokens

    // Fail fast on a missing key so prod never starts half-configured. The builder
    // arrives preconfigured with timeouts (see RestClientConfig) so tests can bind
    // a MockRestServiceServer to it.
    private val restClient: RestClient = run {
        require(properties.openai.apiKey.isNotBlank()) {
            "OPENAI_API_KEY must be set when the prod profile is active"
        }
        restClientBuilder
            .baseUrl(properties.openai.baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.openai.apiKey}")
            .build()
    }

    override fun answer(question: String): String {
        val request = OpenAiChatRequest(
            model = model,
            maxTokens = maxOutputTokens,
            messages = listOf(
                OpenAiChatRequest.Message("system", SYSTEM_PROMPT),
                OpenAiChatRequest.Message("user", question)
            )
        )

        var lastFailure: ChatProviderException? = null
        // Bounded retry: at most 2 retries, and only for retryable failures.
        for (attempt in 0..MAX_RETRIES) {
            if (attempt > 0) {
                Thread.sleep(BACKOFF_BASE_MS * (1L shl (attempt - 1)))
            }
            try {
                return callProvider(request).also {
                    logger.info("AI provider answered: model={}, questionLength={}, attempt={}", model, question.length, attempt)
                }
            } catch (e: ChatProviderException) {
                if (e.reason == ChatProviderException.Reason.AUTH || attempt == MAX_RETRIES) {
                    throw e
                }
                logger.warn("AI provider call failed (reason={}, attempt={}); retrying", e.reason, attempt)
                lastFailure = e
            }
        }
        throw lastFailure ?: ChatProviderException.Upstream()
    }

    private fun callProvider(request: OpenAiChatRequest): String {
        val response = try {
            restClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(OpenAiChatResponse::class.java)
        } catch (e: RestClientResponseException) {
            throw mapHttpError(e.statusCode)
        } catch (e: ResourceAccessException) {
            // Read/connect timeouts surface as ResourceAccessException wrapping a SocketTimeoutException.
            throw if (e.cause is java.net.SocketTimeoutException) {
                ChatProviderException.Timeout()
            } else {
                ChatProviderException.Upstream()
            }
        }

        val content = response?.choices?.firstOrNull()?.message?.content
        return content?.takeIf { it.isNotBlank() }
            ?: throw ChatProviderException.Malformed()
    }

    private fun mapHttpError(status: HttpStatusCode): ChatProviderException = when {
        status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN -> ChatProviderException.Auth()
        status == HttpStatus.TOO_MANY_REQUESTS -> ChatProviderException.RateLimit()
        status.is5xxServerError -> ChatProviderException.Upstream()
        else -> ChatProviderException.Malformed()
    }

    private companion object {
        const val MAX_RETRIES = 2
        const val BACKOFF_BASE_MS = 500L

        // Fixed system prompt; contains no secrets or internal details.
        const val SYSTEM_PROMPT =
            "You are a helpful assistant that answers questions about custody, clearing, settlement, " +
                "and the securities trade lifecycle. Answer concisely and factually."
    }
}

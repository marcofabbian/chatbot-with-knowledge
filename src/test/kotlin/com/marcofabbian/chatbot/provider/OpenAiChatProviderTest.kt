package com.marcofabbian.chatbot.provider

import com.marcofabbian.chatbot.config.ChatbotProperties
import com.marcofabbian.chatbot.knowledge.KnowledgeNotFoundException
import com.marcofabbian.chatbot.knowledge.KnowledgePassage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.SocketTimeoutException

/**
 * All tests bind a MockRestServiceServer to the RestClient builder, so no real
 * network calls happen.
 */
class OpenAiChatProviderTest {

    private lateinit var restClientBuilder: RestClient.Builder
    private lateinit var server: MockRestServiceServer
    private lateinit var provider: OpenAiChatProvider
    private val passages = listOf(KnowledgePassage("How to Invest Money", 91, "Municipal bonds fund public needs.", 1.0))

    private val properties = ChatbotProperties(
        openai = ChatbotProperties.OpenAi(apiKey = "test-key")
    )

    @BeforeEach
    fun setUp() {
        restClientBuilder = RestClient.builder()
        server = MockRestServiceServer.bindTo(restClientBuilder).build()
        provider = OpenAiChatProvider(properties, restClientBuilder)
    }

    @AfterEach
    fun verifyServer() {
        server.verify()
    }

    @Test
    fun `maps question and returns the provider answer`() {
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
            .andExpect(header("Authorization", "Bearer test-key"))
            .andExpect(jsonPath("$.model").value("gpt-5.4"))
            .andExpect(jsonPath("$.max_completion_tokens").value(500))
            .andExpect(jsonPath("$.max_tokens").doesNotExist())
            .andExpect(jsonPath("$.messages[0].role").value("system"))
            .andExpect(jsonPath("$.messages[1].role").value("user"))
            .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("[How to Invest Money p.91]")))
            .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("Municipal bonds fund public needs.")))
            .andExpect(jsonPath("$.messages[1].content").value("What are municipal bonds?"))
            .andRespond(
                withSuccess(
                    """{"choices":[{"message":{"content":"They fund public needs. [How to Invest Money p.91]"}}]}""",
                    MediaType.APPLICATION_JSON
                )
            )

        assertEquals("They fund public needs. [How to Invest Money p.91]", provider.answer("What are municipal bonds?", passages))
    }

    @Test
    fun `configured model and token limit are sent to provider`() {
        val configured = ChatbotProperties(
            openai = ChatbotProperties.OpenAi(apiKey = "test-key", model = "gpt-5.4-mini", maxOutputTokens = 300)
        )
        val configuredProvider = OpenAiChatProvider(configured, restClientBuilder)
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
            .andExpect(jsonPath("$.model").value("gpt-5.4-mini"))
            .andExpect(jsonPath("$.max_completion_tokens").value(300))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"An answer [How to Invest Money p.91]"}}]}""",
                MediaType.APPLICATION_JSON
            ))

        assertEquals("An answer [How to Invest Money p.91]", configuredProvider.answer("question", passages))
    }

    @Test
    fun `401 fails without retry`() {
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        val exception = assertThrows(ChatProviderException.Auth::class.java) {
            provider.answer("question", passages)
        }
        // If a retry happened, server.verify() would fail on the missing expectation.
        assertEquals(ChatProviderException.Reason.AUTH, exception.reason)
    }

    @Test
    fun `429 is retried and then mapped to rate limit failure`() {
        repeat(3) {
            server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        }

        val exception = assertThrows(ChatProviderException.RateLimit::class.java) {
            provider.answer("question", passages)
        }
        assertEquals(ChatProviderException.Reason.RATE_LIMIT, exception.reason)
    }

    @Test
    fun `5xx is retried and a later success is returned`() {
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
            .andRespond(
                withSuccess(
                    """{"choices":[{"message":{"content":"Recovered answer [How to Invest Money p.91]"}}]}""",
                    MediaType.APPLICATION_JSON
                )
            )

        assertEquals("Recovered answer [How to Invest Money p.91]", provider.answer("question", passages))
    }

    @Test
    fun `read timeout maps to a timeout failure`() {
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
            .andRespond { throw ResourceAccessException("Read timed out", SocketTimeoutException()) }
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
            .andRespond { throw ResourceAccessException("Read timed out", SocketTimeoutException()) }
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
            .andRespond { throw ResourceAccessException("Read timed out", SocketTimeoutException()) }

        val exception = assertThrows(ChatProviderException.Timeout::class.java) {
            provider.answer("question", passages)
        }
        assertEquals(ChatProviderException.Reason.TIMEOUT, exception.reason)
    }

    @Test
    fun `empty provider answer maps to a malformed failure`() {
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[]}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[]}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[]}""", MediaType.APPLICATION_JSON))

        val exception = assertThrows(ChatProviderException.Malformed::class.java) {
            provider.answer("question", passages)
        }
        assertEquals(ChatProviderException.Reason.MALFORMED, exception.reason)
    }

    @Test
    fun `exceptions never contain the api key or the question`() {
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        val exception = assertThrows(ChatProviderException.Auth::class.java) {
            provider.answer("my secret question", passages)
        }
        val message = exception.message ?: ""
        assertEquals(false, message.contains("test-key"))
        assertEquals(false, message.contains("my secret question"))
    }

    @Test
    fun `prod provider requires an api key`() {
        val noKey = ChatbotProperties(openai = ChatbotProperties.OpenAi(apiKey = ""))

        assertThrows(IllegalArgumentException::class.java) {
            OpenAiChatProvider(noKey, RestClient.builder())
        }

        @Test
        fun `refusal is returned as knowledge error without a generic answer`() {
            server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess("""{"choices":[{"message":{"content":"[NOT_IN_KNOWLEDGE]"}}]}""", MediaType.APPLICATION_JSON))
            assertThrows(KnowledgeNotFoundException::class.java) { provider.answer("question", passages) }
        }

        @Test
        fun `empty passages do not call provider`() {
            assertThrows(KnowledgeNotFoundException::class.java) { provider.answer("question", emptyList()) }
        }

        @Test
        fun `invented citations are rejected`() {
            repeat(3) {
                server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                    .andRespond(withSuccess("""{"choices":[{"message":{"content":"Answer [How to Invest Money p.99]"}}]}""", MediaType.APPLICATION_JSON))
            }
            assertThrows(ChatProviderException.Malformed::class.java) { provider.answer("question", passages) }
        }

        @Test
        fun `uncited answers are rejected`() {
            repeat(3) {
                server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                    .andRespond(withSuccess("""{"choices":[{"message":{"content":"An uncited answer"}}]}""", MediaType.APPLICATION_JSON))
            }
            assertThrows(ChatProviderException.Malformed::class.java) { provider.answer("question", passages) }
        }
    }
}

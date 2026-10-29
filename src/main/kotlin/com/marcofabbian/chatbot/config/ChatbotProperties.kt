package com.marcofabbian.chatbot.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration
import java.net.URI

/**
 * Settings for the real AI provider integration. The API key must come from the
 * OPENAI_API_KEY environment variable; an empty key makes prod startup fail fast.
 */
@Validated
@ConfigurationProperties(prefix = "chatbot")
data class ChatbotProperties(
    val openai: OpenAi = OpenAi(),
    @field:Min(1) @field:Max(10000)
    val maxQuestionLength: Int = 1000,
    val rateLimit: RateLimit = RateLimit(),
    @field:Valid
    val knowledge: Knowledge = Knowledge()
) {
    data class OpenAi(
        val apiKey: String = "",
        @field:Pattern(regexp = "https://.*", message = "base-url must use https")
        val baseUrl: String = "https://api.openai.com",
        @field:NotBlank
        val model: String = "gpt-5.4",
        @field:Min(1) @field:Max(4096)
        val maxOutputTokens: Int = 500,
        val connectTimeout: Duration = Duration.ofSeconds(5),
        val readTimeout: Duration = Duration.ofSeconds(30)
    )

    data class RateLimit(
        @field:Min(1) @field:Max(1000)
        val requestsPerMinute: Int = 10
    )

    data class Knowledge(
        val enabled: Boolean = true,
        @field:NotBlank
        val sourceUrl: String = "https://www.gutenberg.org/cache/epub/34463/pg34463-images.html",
        @field:NotBlank
        @field:Pattern(regexp = "[^\\[\\]\\r\\n]+", message = "source-name must not contain brackets or line breaks")
        val sourceName: String = "How to Invest Money",
        val fetchTimeout: Duration = Duration.ofSeconds(10),
        @field:Min(200) @field:Max(4000)
        val chunkSizeChars: Int = 800,
        @field:Min(0) @field:Max(3999)
        val chunkOverlapChars: Int = 150,
        @field:Min(1) @field:Max(20)
        val topK: Int = 4,
        @field:Min(500) @field:Max(16000)
        val maxContextChars: Int = 4000
    ) {
        @AssertTrue(message = "source-url must be an HTTPS URL without user info")
        fun isSourceUrlValid(): Boolean = try {
            val uri = URI(sourceUrl)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: java.net.URISyntaxException) {
            false
        }

        @AssertTrue(message = "fetch-timeout must be between 1 and 60 seconds")
        fun isFetchTimeoutValid(): Boolean =
            fetchTimeout >= Duration.ofSeconds(1) && fetchTimeout <= Duration.ofSeconds(60)

        @AssertTrue(message = "chunk-overlap-chars must be less than chunk-size-chars")
        fun isChunkOverlapValid(): Boolean = chunkOverlapChars < chunkSizeChars

        @AssertTrue(message = "max-context-chars must be at least chunk-size-chars")
        fun isContextSizeValid(): Boolean = maxContextChars >= chunkSizeChars
    }
}

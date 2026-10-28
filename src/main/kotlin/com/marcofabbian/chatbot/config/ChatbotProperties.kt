package com.marcofabbian.chatbot.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

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
    val rateLimit: RateLimit = RateLimit()
) {
    data class OpenAi(
        val apiKey: String = "",
        @field:Pattern(regexp = "https://.*", message = "base-url must use https")
        val baseUrl: String = "https://api.openai.com",
        @field:NotBlank
        val model: String = "gpt-4o-mini",
        @field:Min(1) @field:Max(4096)
        val maxOutputTokens: Int = 500,
        val connectTimeout: Duration = Duration.ofSeconds(5),
        val readTimeout: Duration = Duration.ofSeconds(30)
    )

    data class RateLimit(
        @field:Min(1) @field:Max(1000)
        val requestsPerMinute: Int = 10
    )
}

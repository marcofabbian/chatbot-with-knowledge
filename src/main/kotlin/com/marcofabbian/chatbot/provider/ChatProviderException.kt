package com.marcofabbian.chatbot.provider

/**
 * Why a sealed hierarchy: the controller maps provider failures to stable HTTP
 * statuses, and the reason must never carry provider response bodies or secrets.
 */
sealed class ChatProviderException(val reason: Reason, message: String) : RuntimeException(message) {

    enum class Reason { AUTH, RATE_LIMIT, TIMEOUT, UPSTREAM, MALFORMED }

    class Auth : ChatProviderException(Reason.AUTH, "AI provider authentication failed")

    class RateLimit : ChatProviderException(Reason.RATE_LIMIT, "AI provider rate limit exceeded")

    class Timeout : ChatProviderException(Reason.TIMEOUT, "AI provider request timed out")

    class Upstream : ChatProviderException(Reason.UPSTREAM, "AI provider returned an error")

    class Malformed : ChatProviderException(Reason.MALFORMED, "AI provider returned an unexpected response")
}

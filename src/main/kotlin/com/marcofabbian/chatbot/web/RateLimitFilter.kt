package com.marcofabbian.chatbot.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.marcofabbian.chatbot.ChatbotErrorResponse
import com.marcofabbian.chatbot.config.ChatbotProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-client in-memory token bucket on POST /api/chat. Why in-memory: the app runs
 * with a small replica count, so a per-instance limit bounded by max_replicas is
 * acceptable; no external store is needed.
 */
@Component
class RateLimitFilter(
    properties: ChatbotProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC()
) : OncePerRequestFilter() {

    private val capacity = properties.rateLimit.requestsPerMinute.toDouble()
    private val refillPerSecond = capacity / 60.0

    private val buckets = ConcurrentHashMap<String, Bucket>()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        if (!isChatRequest(request)) {
            filterChain.doFilter(request, response)
            return
        }

        val bucket = buckets.computeIfAbsent(clientKey(request)) { Bucket(capacity, nowSeconds()) }
        if (bucket.tryConsume()) {
            filterChain.doFilter(request, response)
            return
        }

        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.setHeader(HttpHeaders.RETRY_AFTER, "60")
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write(
            objectMapper.writeValueAsString(ChatbotErrorResponse("Too many requests, please retry later"))
        )
    }

    private fun isChatRequest(request: HttpServletRequest): Boolean =
        request.method == "POST" && request.requestURI == "/api/chat"

    private fun clientKey(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddr

    private fun nowSeconds(): Double = clock.millis() / 1000.0

    private inner class Bucket(
        @Volatile private var tokens: Double,
        @Volatile private var lastRefillSeconds: Double
    ) {
        @Synchronized
        fun tryConsume(): Boolean {
            val now = nowSeconds()
            tokens = minOf(capacity, tokens + (now - lastRefillSeconds) * refillPerSecond)
            lastRefillSeconds = now
            return if (tokens >= 1.0) {
                tokens -= 1.0
                true
            } else {
                false
            }
        }
    }
}

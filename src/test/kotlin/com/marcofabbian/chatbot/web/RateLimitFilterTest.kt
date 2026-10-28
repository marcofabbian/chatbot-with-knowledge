package com.marcofabbian.chatbot.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.marcofabbian.chatbot.config.ChatbotProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RateLimitFilterTest {

    private val filter = RateLimitFilter(
        ChatbotProperties(rateLimit = ChatbotProperties.RateLimit(requestsPerMinute = 3)),
        ObjectMapper()
    )

    private fun chatRequest(ip: String = "10.0.0.1") = MockHttpServletRequest("POST", "/api/chat").apply {
        remoteAddr = ip
    }

    @Test
    fun `requests within the limit pass through`() {
        repeat(3) {
            val response = MockHttpServletResponse()
            filter.doFilter(chatRequest(), response, MockFilterChain())

            assertEquals(HttpStatus.OK.value(), response.status)
        }
    }

    @Test
    fun `request beyond the limit is rejected with 429 and Retry-After`() {
        repeat(3) { filter.doFilter(chatRequest(), MockHttpServletResponse(), MockFilterChain()) }

        val response = MockHttpServletResponse()
        filter.doFilter(chatRequest(), response, MockFilterChain())

        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.status)
        assertEquals("60", response.getHeader(HttpHeaders.RETRY_AFTER))
        assertTrue(response.contentAsString.contains("Too many requests"))
    }

    @Test
    fun `limits are tracked per client`() {
        repeat(3) { filter.doFilter(chatRequest(ip = "10.0.0.2"), MockHttpServletResponse(), MockFilterChain()) }

        val otherClient = MockHttpServletResponse()
        filter.doFilter(chatRequest(ip = "10.0.0.3"), otherClient, MockFilterChain())

        assertEquals(HttpStatus.OK.value(), otherClient.status)
    }

    @Test
    fun `other paths are not limited`() {
        repeat(10) {
            val request = MockHttpServletRequest("GET", "/about").apply { remoteAddr = "10.0.0.4" }
            val response = MockHttpServletResponse()
            filter.doFilter(request, response, MockFilterChain())

            assertEquals(HttpStatus.OK.value(), response.status)
        }
    }
}

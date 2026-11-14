package com.marcofabbian.chatbot.web

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class SecurityHeadersFilterTest {

    private val filter = SecurityHeadersFilter(ObjectMapper())

    private fun chatRequest(body: ByteArray, declareLength: Boolean): MockHttpServletRequest {
        val request = object : MockHttpServletRequest("POST", "/api/chat") {
            // A chunked request has no Content-Length; report -1 to simulate it.
            override fun getContentLengthLong(): Long = if (declareLength) body.size.toLong() else -1L
            override fun getContentLength(): Int = if (declareLength) body.size else -1
        }
        request.setContent(body)
        return request
    }

    @Test
    fun `adds security headers`() {
        val response = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("GET", "/about"), response, MockFilterChain())

        assertTrue(response.getHeader("Content-Security-Policy")!!.contains("default-src 'self'"))
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"))
        assertEquals("DENY", response.getHeader("X-Frame-Options"))
        assertTrue(response.getHeader("Strict-Transport-Security")!!.startsWith("max-age="))
    }

    @Test
    fun `rejects oversized body declared by Content-Length`() {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(chatRequest(ByteArray(9 * 1024), declareLength = true), response, chain)

        assertEquals(413, response.status)
        assertEquals(null, chain.request)
    }

    @Test
    fun `rejects oversized body without Content-Length`() {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(chatRequest(ByteArray(9 * 1024), declareLength = false), response, chain)

        assertEquals(413, response.status)
        assertEquals(null, chain.request)
    }

    @Test
    fun `small body passes through and can still be read`() {
        val payload = """{"question":"hello"}""".toByteArray()
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(chatRequest(payload, declareLength = false), response, chain)

        assertFalse(response.status == 413)
        val forwarded = chain.request!!
        assertEquals(String(payload), String(forwarded.inputStream.readAllBytes()))
    }
}


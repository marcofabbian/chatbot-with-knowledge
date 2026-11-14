package com.marcofabbian.chatbot.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.marcofabbian.chatbot.ChatbotErrorResponse
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * Adds baseline security headers to every response and rejects oversized chat
 * request bodies before they are read. The 1000 character validation on the
 * question still applies; this cap bounds the raw payload.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class SecurityHeadersFilter(
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        response.setHeader("Content-Security-Policy", CSP)
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("X-Frame-Options", "DENY")
        response.setHeader("Referrer-Policy", "no-referrer")
        if (request.isSecure) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }

        if (request.method == "POST" && request.requestURI == "/api/chat") {
            // Content-Length is absent for chunked requests, so the cap is enforced on the bytes
            // actually read: at most MAX_BODY_BYTES + 1 are consumed, then the body is replayed.
            val body = if (request.contentLengthLong > MAX_BODY_BYTES) {
                null
            } else {
                request.inputStream.readNBytes((MAX_BODY_BYTES + 1).toInt()).takeIf { it.size <= MAX_BODY_BYTES }
            }

            if (body == null) {
                response.status = HttpStatus.PAYLOAD_TOO_LARGE.value()
                response.contentType = MediaType.APPLICATION_JSON_VALUE
                response.writer.write(objectMapper.writeValueAsString(ChatbotErrorResponse("Request body too large")))
                return
            }
            filterChain.doFilter(BufferedBodyRequest(request, body), response)
            return
        }
        filterChain.doFilter(request, response)
    }

    /** Serves an already-read, size-checked body to the rest of the chain. */
    private class BufferedBodyRequest(
        request: HttpServletRequest,
        private val body: ByteArray
    ) : HttpServletRequestWrapper(request) {

        override fun getInputStream(): ServletInputStream {
            val source = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read(): Int = source.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len)
                override fun isFinished(): Boolean = source.available() == 0
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener?) {
                    throw UnsupportedOperationException("Async reads are not supported")
                }
            }
        }

        override fun getReader(): BufferedReader =
            BufferedReader(InputStreamReader(inputStream, characterEncoding ?: "UTF-8"))

        override fun getContentLength(): Int = body.size
        override fun getContentLengthLong(): Long = body.size.toLong()
    }

    companion object {
        const val MAX_BODY_BYTES = 8 * 1024L
        const val CSP = "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    }
}

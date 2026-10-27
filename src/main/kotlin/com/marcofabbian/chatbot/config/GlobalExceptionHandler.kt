package com.marcofabbian.chatbot.config

import com.marcofabbian.chatbot.ChatbotErrorResponse
import com.marcofabbian.chatbot.provider.ChatProviderException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps failures to stable, generic error bodies. Provider messages, stack traces
 * and request content are never exposed to the caller.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(IllegalArgumentException::class, HttpMessageNotReadableException::class)
    fun badRequest(e: Exception): ResponseEntity<ChatbotErrorResponse> =
        error(HttpStatus.BAD_REQUEST, e.message ?: "Invalid request")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validationFailure(e: MethodArgumentNotValidException): ResponseEntity<ChatbotErrorResponse> =
        error(HttpStatus.BAD_REQUEST, e.bindingResult.fieldErrors.firstOrNull()?.defaultMessage ?: "Invalid request")

    @ExceptionHandler(ChatProviderException::class)
    fun providerFailure(e: ChatProviderException): ResponseEntity<ChatbotErrorResponse> {
        // Log the reason only; question/answer content must not be logged.
        logger.warn("AI provider failure: reason={}", e.reason)
        val (status, message) = when (e.reason) {
            ChatProviderException.Reason.AUTH -> HttpStatus.BAD_GATEWAY to "The AI service is not available"
            ChatProviderException.Reason.RATE_LIMIT -> HttpStatus.SERVICE_UNAVAILABLE to "The AI service is busy, please retry later"
            ChatProviderException.Reason.TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT to "The AI service took too long to respond"
            ChatProviderException.Reason.UPSTREAM -> HttpStatus.BAD_GATEWAY to "The AI service is not available"
            ChatProviderException.Reason.MALFORMED -> HttpStatus.BAD_GATEWAY to "The AI service returned an unexpected response"
        }
        return error(status, message)
    }

    private fun error(status: HttpStatus, message: String): ResponseEntity<ChatbotErrorResponse> =
        ResponseEntity.status(status)
            .body(ChatbotErrorResponse(message))
}

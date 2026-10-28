package com.marcofabbian.chatbot

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ChatbotRequest(
    @field:NotBlank(message = "Question must not be blank")
    @field:Size(max = 1000, message = "Question must be at most 1000 characters")
    val question: String = ""
)

data class ChatbotResponse(
    val answer: String
)

data class ChatbotErrorResponse(
    val error: String
)

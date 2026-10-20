package com.marcofabbian.chatbot

data class ChatbotRequest(
    val question: String = ""
)

data class ChatbotResponse(
    val answer: String
)

data class ChatbotErrorResponse(
    val error: String
)

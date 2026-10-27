package com.marcofabbian.chatbot.provider

interface ChatProvider {
    fun answer(question: String): String
}

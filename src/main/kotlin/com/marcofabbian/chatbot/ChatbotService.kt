package com.marcofabbian.chatbot

import org.springframework.stereotype.Service

interface ChatProvider {
    fun answer(question: String): String
}

@Service
class MockChatProvider : ChatProvider {
    override fun answer(question: String): String {
        val normalizedQuestion = question.trim()
        return if (normalizedQuestion.contains("custody", true) || normalizedQuestion.contains("clearing", true)) {
            "This chatbot can explain custody and clearing services in the securities trade lifecycle."
        } else if (normalizedQuestion.contains("settlement", true) || normalizedQuestion.contains("trade", true)) {
            "This chatbot can explain the securities trade lifecycle and settlement flows."
        } else {
            "This chatbot provides guidance on custody, clearing, settlement, and the securities trade lifecycle."
        }
    }
}

@Service
class ChatbotService(
    private val chatProvider: ChatProvider
) {
    fun answerQuestion(question: String): String {
        val trimmedQuestion = question.trim()
        if (trimmedQuestion.isEmpty()) {
            throw IllegalArgumentException("Question must not be blank")
        }

        return "Mock answer: '$trimmedQuestion' -> ${chatProvider.answer(trimmedQuestion)}"
    }
}

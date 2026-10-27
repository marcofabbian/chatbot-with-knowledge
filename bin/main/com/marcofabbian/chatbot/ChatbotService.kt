package com.marcofabbian.chatbot

import com.marcofabbian.chatbot.config.ChatbotProperties
import com.marcofabbian.chatbot.provider.ChatProvider
import org.springframework.stereotype.Service

@Service
class ChatbotService(
    private val chatProvider: ChatProvider,
    private val properties: ChatbotProperties
) {
    fun answerQuestion(question: String): String {
        val trimmedQuestion = question.trim()
        if (trimmedQuestion.isEmpty()) {
            throw IllegalArgumentException("Question must not be blank")
        }
        if (trimmedQuestion.length > properties.maxQuestionLength) {
            throw QuestionTooLongException(properties.maxQuestionLength)
        }
        return chatProvider.answer(trimmedQuestion)
    }
}

class QuestionTooLongException(maxLength: Int) :
    IllegalArgumentException("Question must be at most $maxLength characters")

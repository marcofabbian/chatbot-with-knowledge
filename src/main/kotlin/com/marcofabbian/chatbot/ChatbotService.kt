package com.marcofabbian.chatbot

import com.marcofabbian.chatbot.config.ChatbotProperties
import com.marcofabbian.chatbot.knowledge.KnowledgeLoader
import com.marcofabbian.chatbot.provider.ChatProvider
import org.springframework.stereotype.Service

@Service
class ChatbotService(
    private val chatProvider: ChatProvider,
    private val properties: ChatbotProperties,
    private val knowledge: KnowledgeLoader
) {
    val knowledgeAvailable: Boolean get() = knowledge.available

    fun answerQuestion(question: String): String {
        val trimmedQuestion = question.trim()
        if (trimmedQuestion.isEmpty()) {
            throw IllegalArgumentException("Question must not be blank")
        }
        if (trimmedQuestion.length > properties.maxQuestionLength) {
            throw QuestionTooLongException(properties.maxQuestionLength)
        }
        val passages = knowledge.search(trimmedQuestion)
        // The book is the starting point; with no matching passage the provider answers from its wider knowledge.
        return chatProvider.answer(trimmedQuestion, passages)
    }
}

class QuestionTooLongException(maxLength: Int) :
    IllegalArgumentException("Question must be at most $maxLength characters")

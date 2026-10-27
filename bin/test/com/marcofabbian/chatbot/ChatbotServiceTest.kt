package com.marcofabbian.chatbot

import com.marcofabbian.chatbot.config.ChatbotProperties
import com.marcofabbian.chatbot.provider.MockChatProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ChatbotServiceTest {

    private val service = ChatbotService(MockChatProvider(), ChatbotProperties())

    @Test
    fun `service returns the provider answer for a valid question`() {
        val result = service.answerQuestion("What is custody and clearing?")

        assertEquals(
            "Mock answer: This chatbot can explain custody and clearing services in the securities trade lifecycle.",
            result
        )
    }

    @Test
    fun `service rejects blank questions`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            service.answerQuestion("   ")
        }

        assertEquals("Question must not be blank", exception.message)
    }

    @Test
    fun `service rejects questions longer than the configured maximum`() {
        val exception = assertThrows(QuestionTooLongException::class.java) {
            service.answerQuestion("a".repeat(1001))
        }

        assertEquals("Question must be at most 1000 characters", exception.message)
    }

    @Test
    fun `service accepts a question exactly at the maximum length`() {
        val result = service.answerQuestion("a".repeat(1000))

        assertEquals(
            "Mock answer: This chatbot provides guidance on custody, clearing, settlement, and the securities trade lifecycle.",
            result
        )
    }
}

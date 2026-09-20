package com.marcofabbian.chatbot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ChatbotServiceTest {

    private val service = ChatbotService(MockChatProvider())

    @Test
    fun `service returns a deterministic answer for a valid question`() {
        val result = service.answerQuestion("What is custody and clearing?")

        assertEquals("Mock answer: 'What is custody and clearing?' -> This chatbot can explain custody and clearing services in the securities trade lifecycle.", result)
    }

    @Test
    fun `service rejects blank questions`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            service.answerQuestion("   ")
        }

        assertEquals("Question must not be blank", exception.message)
    }
}

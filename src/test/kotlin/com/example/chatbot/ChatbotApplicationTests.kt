package com.example.chatbot

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class ChatbotApplicationTests {

    @Test
    @DisplayName("Context loads successfully")
    fun contextLoads() {
        assertTrue(true, "Application context initialized successfully")
    }
}

package com.marcofabbian.chatbot

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(properties = ["chatbot.knowledge.enabled=false"])
class ChatbotApplicationTests {

    @Test
    @DisplayName("Context loads successfully")
    fun contextLoads() {
        assertTrue(true, "Application context initialized successfully")
    }
}

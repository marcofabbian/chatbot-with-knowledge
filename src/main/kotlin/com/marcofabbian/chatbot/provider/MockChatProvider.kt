package com.marcofabbian.chatbot.provider

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Service
@Profile("!prod", "default")
class MockChatProvider : ChatProvider {

    private val logger = LoggerFactory.getLogger(MockChatProvider::class.java)

    init {
        logger.info("MockChatProvider active; set SPRING_PROFILES_ACTIVE=prod with OPENAI_API_KEY for the real provider")
    }

    override fun answer(question: String): String {
        val normalizedQuestion = question.trim()
        return if (normalizedQuestion.contains("custody", true) || normalizedQuestion.contains("clearing", true)) {
            "Mock answer: This chatbot can explain custody and clearing services in the securities trade lifecycle."
        } else if (normalizedQuestion.contains("settlement", true) || normalizedQuestion.contains("trade", true)) {
            "Mock answer: This chatbot can explain the securities trade lifecycle and settlement flows."
        } else {
            "Mock answer: This chatbot provides guidance on custody, clearing, settlement, and the securities trade lifecycle."
        }
    }
}

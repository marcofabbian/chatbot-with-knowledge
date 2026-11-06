package com.marcofabbian.chatbot.provider

import com.marcofabbian.chatbot.knowledge.KnowledgeNotFoundException
import com.marcofabbian.chatbot.knowledge.KnowledgePassage
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Service
@Profile("default")
class MockChatProvider : ChatProvider {

    private val logger = LoggerFactory.getLogger(MockChatProvider::class.java)

    init {
        logger.info("MockChatProvider active; set SPRING_PROFILES_ACTIVE=prod with OPENAI_API_KEY for the real provider")
    }

    override fun answer(question: String, passages: List<KnowledgePassage>): String {
        val passage = passages.firstOrNull() ?: throw KnowledgeNotFoundException()
        return "Mock excerpt (not an evaluated answer): ${passage.text} ${passage.label}"
    }
}

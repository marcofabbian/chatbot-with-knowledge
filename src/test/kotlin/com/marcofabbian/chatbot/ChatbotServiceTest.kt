package com.marcofabbian.chatbot

import com.marcofabbian.chatbot.config.ChatbotProperties
import com.marcofabbian.chatbot.provider.MockChatProvider
import com.marcofabbian.chatbot.provider.ChatProvider
import com.marcofabbian.chatbot.knowledge.KnowledgeLoader
import com.marcofabbian.chatbot.knowledge.KnowledgeNotFoundException
import com.marcofabbian.chatbot.knowledge.KnowledgePassage
import com.marcofabbian.chatbot.knowledge.KnowledgeUnavailableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class ChatbotServiceTest {

    private val knowledge = Mockito.mock(KnowledgeLoader::class.java)
    private val service = ChatbotService(MockChatProvider(), ChatbotProperties(), knowledge)

    @Test
    fun `service returns the provider answer for a valid question`() {
        Mockito.`when`(knowledge.search("What are bonds?"))
            .thenReturn(listOf(KnowledgePassage("Book", 7, "Bonds are securities.", 1.0)))
        val result = service.answerQuestion("What are bonds?")

        assertEquals(
            "Mock excerpt (not an evaluated answer): Bonds are securities. [Book p.7]",
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
        Mockito.`when`(knowledge.search("a".repeat(1000)))
            .thenReturn(listOf(KnowledgePassage("Book", 7, "Text.", 1.0)))
        val result = service.answerQuestion("a".repeat(1000))

        assertEquals(
            "Mock excerpt (not an evaluated answer): Text. [Book p.7]",
            result
        )
    }

    @Test
    fun `unavailable and unmatched knowledge never reach provider`() {
        val provider = Mockito.mock(ChatProvider::class.java)
        val guardedService = ChatbotService(provider, ChatbotProperties(), knowledge)
        Mockito.`when`(knowledge.search("unavailable")).thenThrow(KnowledgeUnavailableException())
        Mockito.`when`(knowledge.search("unmatched")).thenReturn(emptyList())
        assertThrows(KnowledgeUnavailableException::class.java) { guardedService.answerQuestion("unavailable") }
        assertThrows(KnowledgeNotFoundException::class.java) { guardedService.answerQuestion("unmatched") }
        Mockito.verifyNoInteractions(provider)
    }
}
